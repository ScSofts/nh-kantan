package com.yuelengm.nhkantan.network;

import java.util.ArrayList;
import java.util.List;

import com.yuelengm.nhkantan.market.LeverageRange;
import com.yuelengm.nhkantan.market.StockSnapshot;
import com.yuelengm.nhkantan.market.view.ClientMarketData;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: public market state with the most recent prices of every stock. Sent on login.
 */
public class MarketSnapshotMessage implements IMessage {

    private int recentPrices;
    private List<StockSnapshot> stocks;

    /** Required by FML for decoding. */
    public MarketSnapshotMessage() {}

    /**
     * @param recentPrices number of prices the client keeps per stock
     */
    public MarketSnapshotMessage(int recentPrices, List<StockSnapshot> stocks) {
        this.recentPrices = recentPrices;
        this.stocks = stocks;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(recentPrices);
        buf.writeInt(stocks.size());
        for (StockSnapshot stock : stocks) {
            ByteBufUtils.writeUTF8String(buf, stock.id());
            ByteBufUtils.writeUTF8String(buf, stock.name());
            buf.writeDouble(stock.lowerBound());
            buf.writeDouble(stock.upperBound());
            buf.writeInt(
                stock.leverage()
                    .min());
            buf.writeInt(
                stock.leverage()
                    .max());
            float[] prices = stock.prices();
            buf.writeInt(prices.length);
            for (float price : prices) buf.writeFloat(price);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        recentPrices = buf.readInt();
        int count = buf.readInt();
        stocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = ByteBufUtils.readUTF8String(buf);
            String name = ByteBufUtils.readUTF8String(buf);
            double lowerBound = buf.readDouble();
            double upperBound = buf.readDouble();
            LeverageRange leverage = new LeverageRange(buf.readInt(), buf.readInt());
            float[] prices = new float[buf.readInt()];
            for (int j = 0; j < prices.length; j++) prices[j] = buf.readFloat();
            stocks.add(new StockSnapshot(id, name, lowerBound, upperBound, leverage, prices));
        }
    }

    public static class Handler implements IMessageHandler<MarketSnapshotMessage, IMessage> {

        @Override
        public IMessage onMessage(MarketSnapshotMessage message, MessageContext ctx) {
            ClientMarketData.INSTANCE.applySnapshot(message.recentPrices, message.stocks);
            return null;
        }
    }
}
