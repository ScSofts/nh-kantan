package com.yuelengm.nhkantan.market.view;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import com.yuelengm.nhkantan.market.order.SimulatedInstrument;

/** Detached public exchange data. No account, private order ID or bot fair-price state is present. */
public final class ClientExchangeData {

    public static final class Frame {

        public int version;
        public UUID generation;
        public long gameTick;
        public long revision;
        public int tickValueCents;
        public int marginBps;
        public int commissionPpm;
        public Map<String, String> names = Collections.emptyMap();
        public Map<String, SimulatedInstrument.Snapshot> instruments = Collections.emptyMap();
    }

    private static volatile Frame frame;

    private ClientExchangeData() {}

    public static Frame get() {
        return frame;
    }

    public static void accept(Frame incoming) {
        if (incoming == null || incoming.version != 1
            || incoming.generation == null
            || incoming.gameTick < 0
            || incoming.tickValueCents < 1
            || incoming.marginBps < 1
            || incoming.marginBps > 10000
            || incoming.instruments == null
            || incoming.instruments.size() > 1000
            || incoming.names == null) {
            throw new IllegalArgumentException("Invalid exchange frame");
        }
        Frame previous = frame;
        if (previous != null && previous.generation.equals(incoming.generation)
            && incoming.revision <= previous.revision) return;
        incoming.instruments = Collections.unmodifiableMap(incoming.instruments);
        incoming.names = Collections.unmodifiableMap(incoming.names);
        for (SimulatedInstrument.Snapshot instrument : incoming.instruments.values()) {
            if (instrument == null || instrument.capacity < 1
                || instrument.capacity > 1000
                || instrument.candles == null
                || instrument.candles.size() > instrument.capacity) {
                throw new IllegalArgumentException("Invalid exchange candle window");
            }
        }
        ClientMarketData.INSTANCE.applyExchange(incoming);
        frame = incoming;
    }

    public static void clear() {
        frame = null;
    }
}
