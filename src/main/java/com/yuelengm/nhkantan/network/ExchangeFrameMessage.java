package com.yuelengm.nhkantan.network;

import java.nio.charset.StandardCharsets;

import com.google.gson.Gson;
import com.yuelengm.nhkantan.market.view.ClientExchangeData;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Versioned bounded public snapshot, sent by the main-thread adapter. */
public final class ExchangeFrameMessage implements IMessage {

    private static final int MAX_BYTES = 1024 * 1024;
    private String json;

    public ExchangeFrameMessage() {}

    public ExchangeFrameMessage(ClientExchangeData.Frame frame) {
        json = new Gson().toJson(frame);
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Public market snapshot exceeds message budget");
        buffer.writeInt(bytes.length);
        buffer.writeBytes(bytes);
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        int length = buffer.readInt();
        if (length < 1 || length > MAX_BYTES || length > buffer.readableBytes()) {
            throw new IllegalArgumentException("Invalid public snapshot length");
        }
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        json = new String(bytes, StandardCharsets.UTF_8);
    }

    public static final class Handler implements IMessageHandler<ExchangeFrameMessage, IMessage> {

        @Override
        public IMessage onMessage(ExchangeFrameMessage message, MessageContext context) {
            ClientExchangeData.accept(new Gson().fromJson(message.json, ClientExchangeData.Frame.class));
            return null;
        }
    }
}
