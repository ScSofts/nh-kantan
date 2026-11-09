package com.yuelengm.nhkantan.network;

import com.yuelengm.nhkantan.market.server.RealisticService;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Bounded main-thread terminal-open request after the client warning. */
public final class OpenExchangeMessage implements IMessage {

    @Override
    public void toBytes(ByteBuf buffer) {}

    @Override
    public void fromBytes(ByteBuf buffer) {
        if (buffer.readableBytes() != 0) throw new IllegalArgumentException("Unexpected open-terminal payload");
    }

    public static final class Handler implements IMessageHandler<OpenExchangeMessage, IMessage> {

        @Override
        public IMessage onMessage(OpenExchangeMessage message, MessageContext context) {
            RealisticService service = RealisticService.get();
            if (service != null) service.requestOpen(context.getServerHandler().playerEntity.getUniqueID());
            return null;
        }
    }
}
