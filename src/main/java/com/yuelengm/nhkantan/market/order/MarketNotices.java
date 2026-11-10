package com.yuelengm.nhkantan.market.order;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;

/** Writer-owned, bounded private event outbox. Acknowledgments do not change cash or orders. */
public final class MarketNotices {

    public enum Kind {
        STOP_TRIGGERED,
        TAKE_FILLED,
        OPTION_EXPIRED,
        LIQUIDATION_TRIGGERED
    }

    public static final class Notice {

        public final long id;
        public final Kind kind;
        public final String instrument;
        public final long gameTick;
        public final long quantity;
        public final long priceTicks;
        public final long payoutCents;
        public final long expiryTick;
        private transient boolean offered;

        public Notice(long id, Kind kind, String instrument, long tick, long quantity, long price, long payout,
            long expiry) {
            this.id = id;
            this.kind = kind;
            this.instrument = instrument;
            gameTick = tick;
            this.quantity = quantity;
            priceTicks = price;
            payoutCents = payout;
            expiryTick = expiry;
        }
    }

    private static final class State {

        private int version = 1;
        private long nextId = 1;
        private Map<UUID, List<Notice>> pending = new LinkedHashMap<>();
    }

    private State state = new State();
    private int size;

    public void record(UUID owner, Kind kind, String instrument, long tick, long quantity, long price, long payout,
        long expiry) {
        // A full notification queue must never interrupt a successful trade or settlement.
        if (owner == null || quantity < 1 || state.nextId == Long.MAX_VALUE) return;
        List<Notice> notices = state.pending.computeIfAbsent(owner, key -> new ArrayList<>());
        if (kind == Kind.OPTION_EXPIRED) {
            for (int i = notices.size() - 1; i >= 0; i--) {
                Notice prior = notices.get(i);
                if (prior.offered || prior.kind != kind
                    || prior.gameTick != tick
                    || prior.expiryTick != expiry
                    || !prior.instrument.equals(instrument)) continue;
                if (quantity <= Long.MAX_VALUE - prior.quantity && payout <= Long.MAX_VALUE - prior.payoutCents) {
                    notices.set(
                        i,
                        new Notice(
                            prior.id,
                            kind,
                            instrument,
                            tick,
                            prior.quantity + quantity,
                            price,
                            prior.payoutCents + payout,
                            expiry));
                    return;
                }
            }
        }
        if (notices.size() >= 64) {
            notices.remove(0);
            size--;
        }
        while (size >= 4096) {
            UUID oldest = state.pending.keySet()
                .iterator()
                .next();
            List<Notice> list = state.pending.get(oldest);
            if (!list.isEmpty()) {
                list.remove(0);
                size--;
            }
            if (list.isEmpty()) state.pending.remove(oldest);
        }
        // Global eviction may have removed this owner's now-empty list.
        state.pending.putIfAbsent(owner, notices);
        notices.add(new Notice(state.nextId++, kind, instrument, tick, quantity, price, payout, expiry));
        size++;
    }

    public List<Notice> pending(UUID owner) {
        List<Notice> list = state.pending.get(owner);
        if (list == null) return Collections.emptyList();
        List<Notice> result = new ArrayList<>(list.subList(0, Math.min(16, list.size())));
        for (Notice notice : result) notice.offered = true;
        return Collections.unmodifiableList(result);
    }

    public void acknowledge(UUID owner, List<Long> ids) {
        List<Notice> list = state.pending.get(owner);
        if (list == null) return;
        int before = list.size();
        list.removeIf(notice -> ids.contains(notice.id));
        size -= before - list.size();
        if (list.isEmpty()) state.pending.remove(owner);
    }

    public String checkpoint() {
        return new Gson().toJson(state);
    }

    public static MarketNotices restore(String json) {
        MarketNotices outbox = new MarketNotices();
        if (json == null) return outbox;
        State saved = new Gson().fromJson(json, State.class);
        if (saved == null || saved.version != 1
            || saved.nextId < 1
            || saved.pending == null
            || saved.pending.size() > 4096) throw new IllegalArgumentException("Invalid notification outbox");
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Map.Entry<UUID, List<Notice>> entry : saved.pending.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                || entry.getValue()
                    .size() > 64)
                throw new IllegalArgumentException("Invalid private notification list");
            for (Notice notice : entry.getValue()) {
                if (notice == null || notice.id < 1
                    || notice.id >= saved.nextId
                    || !seen.add(notice.id)
                    || notice.kind == null
                    || notice.instrument == null
                    || notice.instrument.length() > 128
                    || notice.quantity < 1
                    || notice.gameTick < 0
                    || notice.priceTicks < 0
                    || notice.payoutCents < 0
                    || notice.expiryTick < 0) throw new IllegalArgumentException("Invalid market notification");
                outbox.size++;
            }
        }
        if (outbox.size > 4096) throw new IllegalArgumentException("Notification budget exceeded");
        outbox.state = saved;
        return outbox;
    }
}
