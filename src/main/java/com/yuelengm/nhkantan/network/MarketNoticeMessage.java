package com.yuelengm.nhkantan.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.yuelengm.nhkantan.NHKantan;
import com.yuelengm.nhkantan.market.order.MarketNotices;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Private, bounded server event batch; only the authenticated account owner receives it. */
public final class MarketNoticeMessage implements IMessage {

    private UUID generation;
    private int tickValueCents;
    private List<MarketNotices.Notice> notices;

    public MarketNoticeMessage() {}

    public MarketNoticeMessage(UUID generation, int tickValueCents, List<MarketNotices.Notice> notices) {
        this.generation = generation;
        this.tickValueCents = tickValueCents;
        this.notices = new ArrayList<>(notices);
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeLong(generation.getMostSignificantBits());
        buffer.writeLong(generation.getLeastSignificantBits());
        buffer.writeInt(tickValueCents);
        buffer.writeByte(notices.size());
        for (MarketNotices.Notice notice : notices) {
            buffer.writeLong(notice.id);
            buffer.writeByte(notice.kind.ordinal());
            ByteBufUtils.writeUTF8String(buffer, notice.instrument);
            buffer.writeLong(notice.gameTick);
            buffer.writeLong(notice.quantity);
            buffer.writeLong(notice.priceTicks);
            buffer.writeLong(notice.payoutCents);
            buffer.writeLong(notice.expiryTick);
        }
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        generation = new UUID(buffer.readLong(), buffer.readLong());
        tickValueCents = buffer.readInt();
        int count = buffer.readUnsignedByte();
        if (tickValueCents < 1 || tickValueCents > 1000000 || count > 16)
            throw new IllegalArgumentException("Invalid private notification batch");
        notices = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long id = buffer.readLong();
            int kind = buffer.readUnsignedByte();
            String instrument = ByteBufUtils.readUTF8String(buffer);
            long tick = buffer.readLong(), quantity = buffer.readLong(), price = buffer.readLong();
            long payout = buffer.readLong(), expiry = buffer.readLong();
            if (id < 1 || kind >= MarketNotices.Kind.values().length
                || instrument.length() > 128
                || tick < 0
                || quantity < 1
                || price < 0
                || payout < 0
                || expiry < 0) throw new IllegalArgumentException("Invalid notification payload");
            notices.add(
                new MarketNotices.Notice(
                    id,
                    MarketNotices.Kind.values()[kind],
                    instrument,
                    tick,
                    quantity,
                    price,
                    payout,
                    expiry));
        }
        if (buffer.isReadable()) throw new IllegalArgumentException("Trailing notification payload");
    }

    public static final class Handler implements IMessageHandler<MarketNoticeMessage, IMessage> {

        @Override
        public IMessage onMessage(MarketNoticeMessage message, MessageContext context) {
            NHKantan.proxy.receiveMarketNotices(message.generation, message.tickValueCents, message.notices);
            return null;
        }
    }
}
