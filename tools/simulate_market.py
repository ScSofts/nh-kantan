#!/usr/bin/env python3
"""Python-first dynamics/config diagnostic. No Java, Minecraft, or financial ledger is executed."""
import argparse
import csv
import json
import math
import random
import re
from collections import Counter, deque
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULTS_FILE = ROOT / 'src/main/java/com/yuelengm/nhkantan/config/RealisticConfig.java'
EXTRA_DEFAULTS = dict(anchorFollowRate=.001, flowFollowRate=.22, maxFlowImpactBps=20., fairVwapWeight=.15,
                      vwapDeviationVolMultiplier=2., fairNoiseMemory=.85, fairNoiseLimitMultiplier=3.)
PHASES = ('RANGE', 'TREND_UP', 'TREND_DOWN', 'REVERSION', 'BREAKOUT_UP', 'BREAKOUT_DOWN')
MASK = (1 << 64) - 1


def defaults():
    text = DEFAULTS_FILE.read_text()
    result = {}
    pattern = r'@Config.Default(IntList|DoubleList|Boolean|Int|Double)\(([^)]*)\)(?:(?!@Config.Default).)*?public static [\w\[\]]+ (\w+);'
    for kind, raw, name in re.findall(pattern, text, re.S):
        raw = raw.strip().replace('{', '[').replace('}', ']')
        result[name] = json.loads(raw)
    return {**EXTRA_DEFAULTS, **result}


def read_config(path):
    if path.suffix.lower() != '.cfg':
        return json.loads(path.read_text())
    result = {}
    active = False
    pending = None
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith('#'): continue
        if line == 'realistic {': active = True; continue
        if active and line == '}': break
        if not active: continue
        if pending:
            if line == '>': pending = None
            else: result[pending].append(float(line) if any(c in line.lower() for c in ('.','e','nan','inf')) else int(line))
            continue
        match = re.match(r'[BID]:([A-Za-z0-9_]+)\s*(=|<)\s*(.*)', line)
        if not match: continue
        name, separator, value = match.groups()
        if separator == '<': result[name] = []; pending = name
        else:
            try: result[name] = json.loads(value.lower())
            except (ValueError, json.JSONDecodeError):
                try: result[name] = float(value)
                except ValueError: result[name] = None
    return result


def normalize(raw):
    """Matches the planned Java normalization; corrections are reported, never written back to cfg."""
    d = defaults()
    c = {**d, **raw}
    warnings = []
    bounds = dict(regimeMinSeconds=(2,3600), regimeMaxSeconds=(2,3600), fairAverageSeconds=(1,3600),
                  trendMoveBps=(0,5000), breakoutMoveBps=(0,10000), tickValueCents=(1,1000000),
                  makerSpreadBps=(1,200), makerInventorySkewBps=(0,100), makerRepriceBpsPerSecond=(1,1000),
                  automatedQuoteBandBps=(1,1000), automatedQuoteLifetimeSeconds=(1,60), botMaxSlippageBps=(1,500),
                  botMaxLiquidityFraction=(.01,1), makerQuoteSizeMultiplier=(1,100), makerIntervalScale=(.05,2),
                  whaleMaxOrderContracts=(1,100000), initialMarginBps=(1,10000), maintenanceMarginBps=(0,10000), commissionPpm=(0,1000000),
                  maxPosition=(1,1000000), depth=(1,100), botOrderSize=(1,10000), botInitialCash=(1,100000000),
                  institutionalCapitalMultiplier=(1,1000), institutionalOrderMultiplier=(1,100),
                  institutionalPositionLimit=(1000,1000000), intervalJitter=(0,1), fairVolatilityTicks=(0,10000),
                  volatilityMultiplier=(0,100), flowImpact=(0,1), anchorFollowRate=(0,1), flowFollowRate=(0,1),
                  maxFlowImpactBps=(0,1000), fairVwapWeight=(0,1), vwapDeviationVolMultiplier=(0,100),
                  fairNoiseMemory=(0,.999), fairNoiseLimitMultiplier=(0,100), expiryGameDays=(1,14),
                  seriesEveryGameDays=(1,365), baseIV=(.001,10), optionMarkup=(0,1), optionMinimumMarkup=(0,10000))
    for name, (lo,hi) in bounds.items():
        v = c.get(name)
        if isinstance(v,bool) or not isinstance(v,(int,float)) or not math.isfinite(v) or not lo <= v <= hi:
            c[name] = d[name]
            warnings.append(name + ': default restored')
    if c['regimeMaxSeconds'] < c['regimeMinSeconds']:
        c['regimeMinSeconds'], c['regimeMaxSeconds'] = c['regimeMaxSeconds'], c['regimeMinSeconds']
        warnings.append('regime duration bounds reordered')
    if c['maintenanceMarginBps'] > c['initialMarginBps']:
        c['maintenanceMarginBps'] = c['initialMarginBps'] // 2
        warnings.append('maintenance margin corrected')
    minimum_band=max(c['makerSpreadBps'],c['botMaxSlippageBps'])
    if c['automatedQuoteBandBps']<minimum_band:
        c['automatedQuoteBandBps']=minimum_band
        warnings.append('automated quote band widened for spread/slippage')
    if c.get('botCounts')==[3,4,4,8,1]:
        c['botCounts']=[2,2,2,2,2]
        warnings.append('legacy default institution counts migrated to pairs')
    for name, lo, hi in [('botCounts',0,100),('botIntervalsMillis',100,86400000),('botProbabilities',0,1)]:
        values = c[name] if isinstance(c.get(name),list) else []
        fixed = [values[i] if i<len(values) and isinstance(values[i],(int,float))
                 and math.isfinite(values[i]) and lo<=values[i]<=hi else d[name][i] for i in range(5)]
        if fixed != values: warnings.append(name + ': profile defaults restored')
        c[name] = fixed
    return c,warnings


class Dynamics:
    def __init__(self, price, seed):
        self.random_state=seed & MASK
        self.price=self.fundamental=self.anchor=self.start=self.target=price
        self.age=self.duration=0
        self.regime=None
        self.impact=self.noise=0.

    def uniform(self):
        self.random_state=(self.random_state+0x9E3779B97F4A7C15) & MASK
        v=self.random_state
        v=((v^(v>>30))*0xBF58476D1CE4E5B9) & MASK
        v=((v^(v>>27))*0x94D049BB133111EB) & MASK
        return ((v^(v>>31))>>11)*2.**-53

    def next(self,c):
        choice=self.uniform()
        if self.regime in ('BREAKOUT_UP','BREAKOUT_DOWN'):
            self.regime='RANGE' if choice<.5 else 'REVERSION'
        elif self.regime in ('TREND_UP','TREND_DOWN'):
            self.regime='REVERSION' if choice<.45 else 'RANGE' if choice<.65 else ('BREAKOUT_UP' if self.uniform()<.5 else 'BREAKOUT_DOWN')
        else:
            self.regime='RANGE' if choice<.18 else 'TREND_UP' if choice<.48 else 'TREND_DOWN' if choice<.78 else ('BREAKOUT_UP' if self.uniform()<.5 else 'BREAKOUT_DOWN')
        lo,hi=int(c['regimeMinSeconds']*10),int(c['regimeMaxSeconds']*10)
        self.duration=lo+int(self.uniform()*(hi-lo+1));self.age=0;self.start=self.fundamental
        if self.regime=='RANGE': self.target=self.start;return
        if self.regime=='REVERSION':
            self.target=self.bound(self.start+(self.anchor-self.start)*(.5+self.uniform()*.3));return
        breakout=self.regime.startswith('BREAKOUT')
        sign=-1 if self.regime.endswith('DOWN') else 1
        move=self.start*(c['breakoutMoveBps'] if breakout else c['trendMoveBps'])/10000*(.7+self.uniform()*.6)
        self.target=self.bound(self.start+sign*move)
        if breakout:
            self.duration=max(20,self.duration//3);self.anchor=self.bound(self.anchor+sign*move*.65)

    @staticmethod
    def bound(value): return max(1.,min(100000.,value))

    def step(self,c,flow,average):
        if self.regime is None or self.age>=self.duration:self.next(c)
        self.age+=1;p=self.age/self.duration;path=p;speed=.1;scale=.45
        if self.regime=='RANGE':path=1;speed=.035;scale=.65
        elif self.regime=='REVERSION':path=(1-math.exp(-3*p))/(1-math.exp(-3))
        elif self.regime.startswith('BREAKOUT'):
            path=min(1,p/.22);speed=.28;scale=.9 if p<.22 else .45
        volatility=c['fairVolatilityTicks']*c['volatilityMultiplier']
        gaussian=math.sqrt(-2*math.log(max(1e-12,self.uniform())))*math.cos(2*math.pi*self.uniform())
        destination=self.start+(self.target-self.start)*path
        self.fundamental=self.bound(self.fundamental+(destination-self.fundamental)*speed+gaussian*volatility*scale)
        max_impact=self.fundamental*c['maxFlowImpactBps']/10000
        desired=max_impact*c['flowImpact']*max(-1,min(1,flow))
        self.impact+=(desired-self.impact)*c['flowFollowRate']
        self.price=self.bound(self.fundamental+self.impact)
        self.anchor=self.bound(self.anchor+(self.fundamental-self.anchor)*c['anchorFollowRate'])
        shock=(self.uniform()+self.uniform()+self.uniform()-1.5)*volatility
        cap=c['fairNoiseLimitMultiplier']*volatility
        self.noise=max(-cap,min(cap,self.noise*c['fairNoiseMemory']+shock))
        limit=c['vwapDeviationVolMultiplier']*volatility
        deviation=max(-limit,min(limit,average-self.price))
        return self.bound(self.price+c['fairVwapWeight']*deviation+self.noise)


def simulate(config,seed,seconds):
    model=Dynamics(10000.,seed);volume_random=random.Random(seed ^ 4242)
    history=deque();rows=[];counts=Counter();transitions=[]
    previous=None;weighted=volume=0.;fair=10000.
    for step in range(seconds*10):
        now=step/10
        while history and history[0][0]<=now-config['fairAverageSeconds']:
            _,price,qty=history.popleft();weighted-=price*qty;volume-=qty
        mean=weighted/volume if volume else fair
        flow=math.sin(step*.071)*.6
        fair=model.step(config,flow,mean)
        qty=300+int(volume_random.random()*600)  # proxy volume for reference feedback, not a matching-engine claim
        history.append((now,fair,qty));weighted+=fair*qty;volume+=qty
        assert math.isfinite(fair) and 1<=fair<=100000
        assert 0<model.age<=model.duration
        counts[model.regime]+=1
        if previous!=model.regime:
            transitions.append(dict(seconds=now,phase=model.regime,reference=round(fair/100,4)))
            previous=model.regime
        if step%10==0:rows.append((now,model.regime,fair/100,model.fundamental/100,mean/100))
    clone=Dynamics(10000.,seed)
    clone.__dict__.update(json.loads(json.dumps(model.__dict__)))
    for i in range(100):assert model.step(config,.2,fair)==clone.step(config,.2,fair)
    return rows,counts,transitions


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seconds',type=int,default=1800)
    parser.add_argument('--seeds',type=int,default=16)
    parser.add_argument('--config',type=Path)
    parser.add_argument('--output',type=Path,default=ROOT/'build/reports/python-market')
    args=parser.parse_args()
    raw=read_config(args.config) if args.config else {}
    config,warnings=normalize(raw)
    invalid={**config,'regimeMinSeconds':90,'regimeMaxSeconds':3,'fairVolatilityTicks':float('nan'),
             'botCounts':[-1],'botIntervalsMillis':[0],'maintenanceMarginBps':99999}
    repaired,notes=normalize(invalid)
    assert repaired['regimeMinSeconds']<=repaired['regimeMaxSeconds'] and len(repaired['botCounts'])==5
    assert repaired['maintenanceMarginBps']<=repaired['initialMarginBps'] and notes
    args.output.mkdir(parents=True,exist_ok=True)
    seen=Counter();samples=[];transition_count=0
    for seed in range(args.seeds):
        rows,counts,transitions=simulate(config,seed,args.seconds)
        seen.update(counts);transition_count+=len(transitions)
        assert len(transitions)>1,'State machine never transitioned'
        if seed==0:
            samples=rows
            with (args.output/'series.csv').open('w',newline='') as f:
                writer=csv.writer(f);writer.writerow(['seconds','phase','reference_nhc','fundamental_nhc','average_nhc']);writer.writerows(rows)
            (args.output/'transitions.json').write_text(json.dumps(transitions,indent=2))
    assert set(PHASES)==set(seen),'Some phases were never reached'
    summary=dict(seeds=args.seeds,seconds_per_seed=args.seconds,transitions=transition_count,phases=dict(seen),
                 reference_range=[min(r[2] for r in samples),max(r[2] for r in samples)],
                 snapshot_resume='exact',invalid_config='repaired',config_warnings=warnings,
                 scope='Python reference/phase model with proxy VWAP; no Java tests or live matching engine executed')
    (args.output/'summary.json').write_text(json.dumps(summary,indent=2))
    (args.output/'effective-config.json').write_text(json.dumps(config,indent=2))
    print(json.dumps(summary,ensure_ascii=False))

if __name__=='__main__':main()
