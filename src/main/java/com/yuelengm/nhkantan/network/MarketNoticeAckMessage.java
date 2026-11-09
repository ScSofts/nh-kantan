package com.yuelengm.nhkantan.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.yuelengm.nhkantan.market.server.RealisticService;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Notification receipts acknowledge only the sender's own outbox; they never affect a financial operation. */
public final class MarketNoticeAckMessage implements IMessage {

    private UUID generation;
    private List<Long> ids;

    public MarketNoticeAckMessage() {}

    public MarketNoticeAckMessage(UUID generation, List<Long> ids) {
        this.generation = generation;
        this.ids = new ArrayList<>(ids);
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeLong(generation.getMostSignificantBits());
        buffer.writeLong(generation.getLeastSignificantBits());
        buffer.writeByte(ids.size());
        for (long id : ids) buffer.writeLong(id);
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        generation = new UUID(buffer.readLong(), buffer.readLong());
        int count = buffer.readUnsignedByte();
        if (count > 16) throw new IllegalArgumentException("Notification receipt budget exceeded");
        ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long id = buffer.readLong();
            if (id < 1) throw new IllegalArgumentException("Invalid notification receipt");
            ids.add(id);
        }
        if (buffer.isReadable()) throw new IllegalArgumentException("Trailing notification receipt payload");
    }

    public static final class Handler implements IMessageHandler<MarketNoticeAckMessage, IMessage> {

        @Override
        public IMessage onMessage(MarketNoticeAckMessage message, MessageContext context) {
            RealisticService service = RealisticService.get();
            if (service != null) service.acknowledgeNotices(
                message.generation,
                context.getServerHandler().playerEntity.getUniqueID(),
                message.ids);
            return null;
        }
    }
}
