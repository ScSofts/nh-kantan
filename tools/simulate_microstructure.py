#!/usr/bin/env python3
"""Python order-book diagnostic for robot-generated wicks. No Java or Minecraft execution."""
import argparse
import csv
import heapq
import json
import math
import random
from collections import defaultdict, deque
from pathlib import Path

import simulate_market as model

DEFAULT_FIX = dict(makerSpreadBps=10, makerInventorySkewBps=5, makerRepriceBpsPerSecond=120,
                   automatedQuoteBandBps=30, automatedQuoteLifetimeSeconds=2,
                   botMaxSlippageBps=20, botMaxLiquidityFraction=.25, makerQuoteSizeMultiplier=10,
                   makerIntervalScale=.4, whaleMaxOrderContracts=5000)


class Book:
    def __init__(self):
        self.active = {}
        self.heaps = [[], []]
        self.sequence = 0
        self.positions = defaultdict(int)

    def cancel(self, order):
        self.active.pop(order['id'], None)

    def orders(self, owner):
        return [o for o in self.active.values() if o['owner'] == owner]

    def submit(self, owner, side, price, quantity, market, tick):
        same_side = sum(o['remaining'] for o in self.orders(owner) if o['side'] == side)
        if quantity > 100000 or abs(self.positions[owner] + (1 if side == 0 else -1) * (same_side + quantity)) > 100000:
            return []
        fills = []
        opposite = self.heaps[1-side]
        while quantity and opposite:
            _, oid = opposite[0]
            if oid not in self.active:
                heapq.heappop(opposite)
                continue
            resting = self.active[oid]
            if (side == 0 and resting['price'] > price) or (side == 1 and resting['price'] < price):
                break
            if resting['owner'] == owner:
                break
            count = min(quantity, resting['remaining'])
            quantity -= count
            resting['remaining'] -= count
            self.positions[owner] += count * (1 if side == 0 else -1)
            self.positions[resting['owner']] -= count * (1 if side == 0 else -1)
            fills.append((resting['price'], count))
            if not resting['remaining']:
                self.cancel(resting)
        self.sequence += 1
        if quantity and not market:
            crossing_self = bool(opposite and opposite[0][1] in self.active
                and self.active[opposite[0][1]]['owner'] == owner
                and ((side == 0 and self.active[opposite[0][1]]['price'] <= price)
                     or (side == 1 and self.active[opposite[0][1]]['price'] >= price)))
            if not crossing_self:
                order = dict(id=self.sequence, owner=owner, side=side, price=price, remaining=quantity, tick=tick)
                self.active[self.sequence] = order
                heapq.heappush(self.heaps[side], (price if side == 1 else -price, self.sequence))
        return fills


class Market:
    def __init__(self, config, seed, fixed):
        self.c = {**config, **DEFAULT_FIX}
        self.fixed = fixed
        self.c['botCounts'] = [2]*5 if fixed else [3,4,4,8,1]
        self.book = Book()
        self.model = model.Dynamics(10000., seed)
        self.fair = self.center = 10000.
        self.last = 0
        self.flow = 0.
        self.bars = {}
        self.volume = 0
        self.fills = []
        self.roles = {}
        self.actors = []
        self.depth = self.c['depth']
        self.base = self.c['botOrderSize'] * self.c['institutionalOrderMultiplier']
        for role, count in enumerate(self.c['botCounts']):
            for index in range(count):
                owner = len(self.actors)
                rng = random.Random(seed * 1009 + owner * 1543)
                self.roles[owner] = role
                pace=(.85 if index%2==0 else 1.15) if fixed else 1
                scale=self.c['makerIntervalScale'] if fixed and role==0 else 1
                self.actors.append(dict(owner=owner, role=role, rng=rng, index=index,
                    due=rng.random()*max(100,self.c['botIntervalsMillis'][role]*pace*scale)))

    def pressure(self):
        phase = self.model.regime
        if phase == 'RANGE': return 0.
        sign = (self.model.target > self.model.start) - (self.model.target < self.model.start)
        return sign * (.45 if phase == 'REVERSION' else .9 if phase.startswith('BREAKOUT') else .7)

    def burst(self):
        return self.model.regime.startswith('BREAKOUT') and self.model.age < self.model.duration*.25

    def purge(self, tick):
        if not self.fixed: return
        half = max(1,math.ceil(self.center*self.c['makerSpreadBps']/20000))
        minimum = math.ceil(half*2.1+self.depth+3)
        band = max(minimum, self.center * self.c['automatedQuoteBandBps']/10000)
        for order in list(self.book.active.values()):
            if order['owner'] in self.roles and (tick-order['tick'] >= self.c['automatedQuoteLifetimeSeconds']*20
                    or abs(order['price']-self.center) > band):
                self.book.cancel(order)

    def submit(self, owner, side, price, count, market, tick):
        self.purge(tick)
        fills = self.book.submit(owner, side, max(1,min(100000,int(price))), count, market, tick)
        for price, qty in fills:
            key = tick//40
            if key not in self.bars:
                self.bars[key] = dict(tick=key*40, open=price, high=price, low=price, close=price, volume=0, turnover=0.)
            bar = self.bars[key]
            bar['high'] = max(bar['high'],price); bar['low'] = min(bar['low'],price); bar['close'] = price
            bar['volume'] += qty; bar['turnover'] += price*qty
            self.last = price; self.volume += qty
            self.flow = max(-1,min(1,self.flow*.95 + (.05 if side == 0 else -.05)))
            self.fills.append((tick,price,qty))
        return fills

    def update(self, step):
        tick = step*2
        recent = [b for b in self.bars.values() if b['tick']+40 > tick-self.c['fairAverageSeconds']*20]
        qty = sum(b['volume'] for b in recent)
        average = sum(b['turnover'] for b in recent)/qty if qty else (self.last or self.fair)
        self.fair = self.model.step(self.c,self.flow,average)
        self.flow *= .95
        pressure = self.pressure()
        premium_bps = self.c['breakoutMoveBps']*.5 if self.model.regime.startswith('BREAKOUT') else self.c['trendMoveBps']*.35
        desired = self.fair + self.fair*premium_bps/10000*pressure + self.flow*max(self.c['fairVolatilityTicks']*self.c['volatilityMultiplier']*3,self.fair*.001)
        speed = max(1,self.center*self.c['makerRepriceBpsPerSecond']/100000)
        self.center += max(-speed,min(speed,desired-self.center))
        self.center = max(1,min(100000,self.center))
        self.purge(tick)
        for actor in self.actors:
            if step*100 < actor['due']: continue
            rng,role = actor['rng'],actor['role']
            pace=(.85 if actor['index']%2==0 else 1.15) if self.fixed else 1
            scale=self.c['makerIntervalScale'] if self.fixed and role==0 else 1
            interval = max(100,self.c['botIntervalsMillis'][role]*pace*scale)
            actor['due'] = step*100 + max(1,interval*(1+self.c['intervalJitter']*(2*rng.random()-1)))
            if rng.random() < self.c['botProbabilities'][role]: self.act(actor,tick)

    def act(self, actor, tick):
        owner,role,rng = actor['owner'],actor['role'],actor['rng']
        inventory = self.book.positions[owner]
        pressure = self.pressure()
        fast=actor['index']%2==0
        volume_scale=(.8 if fast else 1.2) if self.fixed else 1
        if role == 0:
            owned = sorted(self.book.orders(owner),key=lambda o:abs(o['price']-self.fair),reverse=True)
            distance = max(self.depth*3,self.c['fairVolatilityTicks']*self.c['volatilityMultiplier']*8)
            removed=0
            for order in owned:
                if removed>=2:break
                if abs(order['price']-self.fair)>distance or len(owned)-removed>=self.depth*2:
                    self.book.cancel(order);removed+=1
            if len(owned)-removed+2>self.depth*2:
                for order in owned:
                    if removed>=2:break
                    if order['id'] in self.book.active:self.book.cancel(order);removed+=1
            if self.fixed:
                half = max(1,math.ceil(self.center*self.c['makerSpreadBps']/20000*(.9 if fast else 1.15)))
                cap = min(half*.75,self.center*self.c['makerInventorySkewBps']/10000)
                center = self.center-max(-1,min(1,inventory/75000))*cap
            else:
                half=0
                premium=self.c['breakoutMoveBps']*.5 if self.model.regime.startswith('BREAKOUT') else self.c['trendMoveBps']*.35
                center=self.fair+self.fair*premium/10000*pressure-inventory*.01+self.flow*max(self.c['fairVolatilityTicks']*self.c['volatilityMultiplier']*3,self.fair*.001)
            for side in [0,1]:
                if (inventory>=75000 and side==0) or (inventory<=-75000 and side==1):continue
                count=sum(o['side']==side for o in self.book.orders(owner))
                for level in range(min(3,max(0,self.depth-count))):
                    offset=half+1+rng.randrange(self.depth)+level
                    price=round(center)+(-offset if side==0 else offset)
                    quote_base=self.base*(self.c['makerQuoteSizeMultiplier']*volume_scale if self.fixed else 1)
                    qty=min(100000,int(quote_base*(1+rng.random()*2)))
                    self.submit(owner,side,price,qty,False,tick)
            return
        if role==3:
            for order in self.book.orders(owner):self.book.cancel(order)
        bars=list(self.bars.values())[-50:]
        reference=self.last or round(self.fair)
        if role==1:
            if len(bars)<2:return
            lookback=min(len(bars)-1,(3 if fast else 8)+rng.randrange(6 if fast else 10)) if self.fixed else min(len(bars)-1,3+rng.randrange(10))
            signal=reference-bars[-1-lookback]['close']+pressure*max(self.c['fairVolatilityTicks']*self.c['volatilityMultiplier']*8,reference*.002)
        elif role==2:
            if not bars:return
            window=min(len(bars),(5 if fast else 12)+rng.randrange(8 if fast else 12)) if self.fixed else min(len(bars),5+rng.randrange(15))
            signal=sum(b['close'] for b in bars[-window:])/window-reference
        else:
            signal=1 if rng.random()<.5+pressure*(.45 if self.burst() else .35 if role==4 else .22) else -1
        if not signal:return
        if inventory>=75000:signal=-1
        elif inventory<=-75000:signal=1
        side=0 if signal>0 else 1
        qty=(30+rng.randrange(121))*max(1,self.base//3) if role==4 else max(1,self.base//3)+int(rng.random()*self.base)
        if self.burst():qty*=3
        qty=max(1,int(qty*volume_scale))
        if self.fixed and role==4:qty=min(qty,self.c['whaleMaxOrderContracts'])
        qty=min(qty,100000)
        market=role!=3 or rng.random() < ((.65 if fast else .4) if self.fixed else .5)
        if market:
            half=max(1,math.ceil(self.center*self.c['makerSpreadBps']/20000))
            distance=max(math.ceil(half*2.1+self.depth+3),self.center*self.c['botMaxSlippageBps']/10000)
            price=(math.floor(self.center+distance) if side==0 else math.ceil(self.center-distance)) if self.fixed else (100000 if side==0 else 1)
            if self.fixed:
                self.purge(tick)
                available=sum(o['remaining'] for o in self.book.active.values() if o['side']!=side
                    and ((side==0 and o['price']<=price) or (side==1 and o['price']>=price)))
                if not available:return
                qty=min(qty,max(1,int(available*self.c['botMaxLiquidityFraction'])))
        else:price=reference+(-1 if side==0 else 1)*(1+rng.randrange(5))
        self.submit(owner,side,price,qty,market,tick)


def metrics(bars):
    bars=list(bars)
    wicks=[max(b['high']-max(b['open'],b['close']),min(b['open'],b['close'])-b['low'])/b['open']*10000 for b in bars]
    spikes=sum(w>=30 and w>=3*abs(b['close']-b['open'])/b['open']*10000 for b,w in zip(bars,wicks))
    ordered=sorted(wicks)
    bodies=sorted(abs(b['close']-b['open'])/b['open']*10000 for b in bars)
    return dict(candles=len(bars),body_p95_bps=round(bodies[int((len(bodies)-1)*.95)],2),needle_candles=spikes,wick_p95_bps=round(ordered[int((len(ordered)-1)*.95)],2),
                max_wick_bps=round(max(wicks),2),price_range=[min(b['low'] for b in bars)/100,max(b['high'] for b in bars)/100],
                volume=sum(b['volume'] for b in bars))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seconds',type=int,default=600)
    parser.add_argument('--seeds',type=int,default=4)
    parser.add_argument('--output',type=Path,default=model.ROOT/'build/reports/microstructure')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    c,_=model.normalize({})
    reports=[]
    for seed in range(args.seeds):
        for fixed in [False,True]:
            market=Market(c,seed,fixed)
            for step in range(args.seconds*10):market.update(step)
            result=metrics(market.bars.values());result.update(seed=seed,mode='fixed' if fixed else 'before')
            reports.append(result)
            if seed==0:
                with (args.output/('fixed.csv' if fixed else 'before.csv')).open('w',newline='') as handle:
                    writer=csv.DictWriter(handle,fieldnames=['tick','open','high','low','close','volume','turnover'])
                    writer.writeheader();writer.writerows(market.bars.values())
    before=[r for r in reports if r['mode']=='before'];after=[r for r in reports if r['mode']=='fixed']
    summary=dict(parameters=DEFAULT_FIX,samples=reports,
        before_needles=sum(r['needle_candles'] for r in before),after_needles=sum(r['needle_candles'] for r in after),
        scope='Python price-time order book and bot model; no Java/Minecraft or exact margin/fee ledger execution')
    assert summary['after_needles']<summary['before_needles'],'Needle frequency did not improve'
    assert all(r['volume']>100000 and r['price_range'][1]-r['price_range'][0]>1 for r in after),'Trading or trends collapsed'
    (args.output/'summary.json').write_text(json.dumps(summary,indent=2))
    print(json.dumps(summary))

if __name__=='__main__':main()
