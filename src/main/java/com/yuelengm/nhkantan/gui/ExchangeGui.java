package com.yuelengm.nhkantan.gui;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

import com.cleanroommc.modularui.api.IGuiHolder;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.factory.GuiData;
import com.cleanroommc.modularui.factory.SimpleGuiFactory;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.IntValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.google.gson.Gson;
import com.yuelengm.nhkantan.NHKantan;
import com.yuelengm.nhkantan.gui.util.GuiActions;
import com.yuelengm.nhkantan.gui.util.Widgets;
import com.yuelengm.nhkantan.item.ItemMarketTerminal;
import com.yuelengm.nhkantan.market.order.ExchangeRuntime;
import com.yuelengm.nhkantan.market.order.MarginLedger;
import com.yuelengm.nhkantan.market.order.OrderBook;
import com.yuelengm.nhkantan.market.server.RealisticService;
import com.yuelengm.nhkantan.market.view.ClientExchangeData;
import com.yuelengm.nhkantan.market.view.ClientMarketData;
import com.yuelengm.nhkantan.wallet.CurrencyFormat;
import com.yuelengm.nhkantan.wallet.CurrencyItem;
import com.yuelengm.nhkantan.wallet.ExchangeItems;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** ModularUI2 exchange window; all cash, order and inventory actions are confirmed by the server. */
public final class ExchangeGui implements IGuiHolder<GuiData> {

    private static SimpleGuiFactory factory;

    public static void register() {
        factory = new SimpleGuiFactory(NHKantan.MODID + ":exchange", ExchangeGui::new);
    }

    public static void open(EntityPlayerMP player) {
        if (factory != null && ItemMarketTerminal.isHeldBy(player)) factory.open(player);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public ModularScreen createScreen(GuiData data, ModularPanel panel) {
        return new ModularScreen(NHKantan.MODID, panel);
    }

    @Override
    public ModularPanel buildUI(GuiData data, PanelSyncManager sync, UISettings settings) {
        settings.canInteractWith(ItemMarketTerminal::isHeldBy);
        State state = new State(data.getPlayer(), sync);
        ListWidget<IWidget, ?> content = new ListWidget<>().fullWidth()
            .expanded();
        Flow header = Flow.row()
            .fullWidth()
            .height(16)
            .childPadding(3)
            .child(
                Widgets.text(IKey.lang("gui.nhkantan.exchange.title"))
                    .expanded())
            .child(button("trade_tab", () -> state.page = 0))
            .child(button("wallet_tab", () -> state.page = 1))
            .child(button("options_tab", () -> state.page = 2));
        Flow layout = Flow.column()
            .full()
            .childPadding(3)
            .child(header)
            .child(
                Widgets.text(IKey.dynamic(state::summary))
                    .fullWidth())
            .child(content)
            .child(
                Widgets.text(IKey.dynamic(() -> state.feedbackText()))
                    .fullWidth());
        sync.onClientTick(() -> state.refresh(content));
        return ModularPanel.defaultPanel("nhkantan_exchange")
            .sizeRel(0.95F, 0.9F)
            .padding(7)
            .child(layout);
    }

    private static ButtonWidget<?> button(String key, Runnable action) {
        return new ButtonWidget<>().height(18)
            .width(62)
            .overlay(IKey.lang("gui.nhkantan.exchange." + key))
            .onMousePressed(GuiActions.leftClick(action));
    }

    private static TextFieldWidget number(String key, IntValue.Dynamic value) {
        return new TextFieldWidget().width(80)
            .height(18)
            .numbersLong(1, Integer.MAX_VALUE)
            .value(value)
            .autoUpdateOnChange(true)
            .addTooltipLine(IKey.lang("gui.nhkantan.exchange." + key));
    }

    private static final class State {

        private final EntityPlayer player;
        private final PanelSyncManager sync;
        private final StringSyncValue snapshot;
        private final StringSyncValue feedback;
        private final AtomicReference<String> result = new AtomicReference<>("");
        private ExchangeRuntime.PrivateSnapshot account;
        private String decoded = "";
        private String selected = "";
        private String generation = "";
        private long sequence;
        private String structure = "";
        private int page;
        private int renderedPage = -1;
        private int quantity = 1;
        private int limitPrice = 10000;
        private int stopPrice = 9000;
        private int takePrice = 11000;
        private boolean limit;
        private int itemIndex;
        private final IntValue.Dynamic quantityValue = new IntValue.Dynamic(
            () -> quantity,
            value -> quantity = Math.max(1, value));

        private State(EntityPlayer player, PanelSyncManager sync) {
            this.player = player;
            this.sync = sync;
            snapshot = new StringSyncValue(() -> "{}", () -> {
                RealisticService service = RealisticService.get();
                return service == null || !(player instanceof EntityPlayerMP) ? "{}"
                    : service.privateView((EntityPlayerMP) player);
            });
            feedback = new StringSyncValue(() -> "", result::get);
            sync.syncValue("exchange_account", snapshot);
            sync.syncValue("exchange_feedback", feedback);
            sync.registerSyncedAction("exchange_order", Side.SERVER, packet -> {
                try {
                    java.util.UUID epoch = java.util.UUID.fromString(ByteBufUtils.readUTF8String(packet));
                    long request = packet.readLong();
                    String instrument = ByteBufUtils.readUTF8String(packet);
                    int side = packet.readUnsignedByte();
                    long price = packet.readLong();
                    long count = packet.readLong();
                    boolean market = packet.readBoolean();
                    boolean reduce = packet.readBoolean();
                    if (side > 1) throw new IllegalArgumentException("Invalid direction");
                    track(
                        service().order(
                            serverPlayer(),
                            epoch,
                            request,
                            instrument,
                            OrderBook.Side.values()[side],
                            price,
                            count,
                            market,
                            reduce));
                } catch (Exception e) {
                    result.set("REJECTED");
                }
            });
            sync.registerSyncedAction("exchange_cancel", Side.SERVER, packet -> {
                try {
                    java.util.UUID epoch = java.util.UUID.fromString(ByteBufUtils.readUTF8String(packet));
                    String instrument = ByteBufUtils.readUTF8String(packet);
                    track(service().cancel(serverPlayer(), epoch, instrument, packet.readLong()));
                } catch (Exception e) {
                    result.set("REJECTED");
                }
            });
            sync.registerSyncedAction("exchange_protect", Side.SERVER, packet -> {
                try {
                    java.util.UUID epoch = java.util.UUID.fromString(ByteBufUtils.readUTF8String(packet));
                    long request = packet.readLong();
                    String instrument = ByteBufUtils.readUTF8String(packet);
                    track(
                        service().protect(
                            serverPlayer(),
                            epoch,
                            request,
                            instrument,
                            packet.readLong(),
                            packet.readLong(),
                            packet.readLong()));
                } catch (Exception e) {
                    result.set("REJECTED");
                }
            });
            sync.registerSyncedAction("exchange_option", Side.SERVER, packet -> {
                try {
                    java.util.UUID epoch = java.util.UUID.fromString(ByteBufUtils.readUTF8String(packet));
                    long request = packet.readLong();
                    String contract = ByteBufUtils.readUTF8String(packet);
                    track(
                        service().buyOption(
                            serverPlayer(),
                            epoch,
                            request,
                            contract,
                            packet.readLong(),
                            packet.readLong(),
                            packet.readLong()));
                } catch (Exception e) {
                    result.set("REJECTED");
                }
            });
            sync.registerSyncedAction("exchange_wallet", Side.SERVER, packet -> {
                try {
                    boolean accepted = service()
                        .exchange(serverPlayer(), packet.readInt(), packet.readInt(), packet.readBoolean());
                    result.set(accepted ? "OK" : "REJECTED");
                } catch (Exception e) {
                    NHKantan.LOG.warn("Wallet exchange rejected or pending for {}", player.getUniqueID(), e);
                    result.set("REJECTED");
                }
            });
        }

        private RealisticService service() {
            if (!ItemMarketTerminal.isHeldBy(player) || RealisticService.get() == null)
                throw new IllegalStateException("Terminal unavailable");
            return RealisticService.get();
        }

        private EntityPlayerMP serverPlayer() {
            return (EntityPlayerMP) player;
        }

        private void track(CompletableFuture<?> operation) {
            AtomicReference<String> target = result;
            target.set("PENDING");
            operation.whenComplete((value, error) -> {
                boolean rejected = error != null || Boolean.FALSE.equals(value)
                    || (value instanceof OrderBook.Result
                        && ((OrderBook.Result) value).order.status == OrderBook.Status.REJECTED);
                target.set(rejected ? "REJECTED" : "OK");
            });
        }

        private String feedbackText() {
            String code = feedback.getStringValue();
            return IKey.lang(
                "gui.nhkantan.exchange.status." + (code.isEmpty() ? "ready" : code.toLowerCase(java.util.Locale.ROOT)))
                .get();
        }

        private String summary() {
            if (account == null || account.account == null) return IKey.lang("gui.nhkantan.exchange.loading")
                .get();
            MarginLedger.AccountView value = account.account;
            return IKey
                .lang(
                    "gui.nhkantan.exchange.summary",
                    CurrencyFormat.amount(value.cash),
                    CurrencyFormat.amount(value.equity),
                    CurrencyFormat.amount(value.available),
                    CurrencyFormat.amount(value.frozen))
                .get();
        }

        private void refresh(ListWidget<IWidget, ?> container) {
            String json = snapshot.getStringValue();
            boolean changed = !json.equals(decoded);
            if (changed) {
                decoded = json;
                account = new Gson().fromJson(json, ExchangeRuntime.PrivateSnapshot.class);
                if (account != null && account.generation != null) {
                    if (!account.generation.toString()
                        .equals(generation)) {
                        generation = account.generation.toString();
                        sequence = account.nextRequest;
                    } else sequence = Math.max(sequence, account.nextRequest);
                }
            }
            ClientExchangeData.Frame frame = ClientExchangeData.get();
            StringBuilder key = new StringBuilder().append(page)
                .append(':')
                .append(selected);
            if (frame != null) key.append(frame.names.keySet());
            if (account != null && account.positions != null) {
                key.append(account.account != null && account.account.cash < 0);
                for (Map.Entry<String, MarginLedger.Position> entry : account.positions.entrySet())
                    key.append(entry.getKey())
                        .append(entry.getValue().quantity);
                if (account.orders != null) for (java.util.List<OrderBook.Order> orders : account.orders.values()) {
                    for (OrderBook.Order order : orders) key.append(order.id)
                        .append(':')
                        .append(order.remaining);
                }
            }
            if (page == 2 && account != null && account.quotes != null) key.append(account.quotes.keySet())
                .append(new Gson().toJson(account.options));
            String nextStructure = key.toString();
            if (renderedPage == page && structure.equals(nextStructure)) return;
            structure = nextStructure;
            renderedPage = page;
            container.removeAll();
            if (page == 1) wallet(container);
            else if (page == 2) optionPage(container);
            else trade(container);
            if (container.isValid()) container.scheduleResize();
        }

        private void trade(ListWidget<IWidget, ?> container) {
            ClientExchangeData.Frame frame = ClientExchangeData.get();
            if (frame == null) return;
            if (!frame.instruments.containsKey(selected)) selected = frame.instruments.keySet()
                .stream()
                .findFirst()
                .orElse("");
            Flow main = Flow.row()
                .fullWidth()
                .height(160)
                .childPadding(3);
            main.child(
                NHKantan.proxy.createStockChart(() -> ClientMarketData.INSTANCE.get(selected))
                    .expanded()
                    .height(160));
            Flow form = Flow.column()
                .width(170)
                .coverChildrenHeight()
                .childPadding(2);
            form.child(Widgets.text(IKey.lang("gui.nhkantan.exchange.quantity")))
                .child(
                    Flow.row()
                        .fullWidth()
                        .height(18)
                        .childPadding(2)
                        .child(
                            Widgets
                                .stepButton(
                                    "-",
                                    quantityValue,
                                    -1,
                                    -10,
                                    1,
                                    1000000,
                                    IKey.lang("gui.nhkantan.exchange.step_hint"))
                                .size(18, 18))
                        .child(number("quantity", quantityValue))
                        .child(
                            Widgets
                                .stepButton(
                                    "+",
                                    quantityValue,
                                    1,
                                    10,
                                    1,
                                    1000000,
                                    IKey.lang("gui.nhkantan.exchange.step_hint"))
                                .size(18, 18)))
                .child(
                    Widgets.text(
                        IKey.lang(
                            "gui.nhkantan.exchange.margin",
                            frame.marginBps / 100D,
                            frame.commissionPpm / 10000D)))
                .child(button(limit ? "limit" : "market", () -> {
                    limit = !limit;
                    renderedPage = -1;
                }))
                .child(
                    number(
                        "price_ticks",
                        new IntValue.Dynamic(() -> limitPrice, value -> limitPrice = Math.max(1, value))))
                .child(
                    Flow.row()
                        .fullWidth()
                        .height(18)
                        .childPadding(2)
                        .child(button("buy", () -> order(OrderBook.Side.BUY, quantity, false)))
                        .child(button("sell", () -> order(OrderBook.Side.SELL, quantity, false))))
                .child(button("close_quantity", this::closeQuantity));
            main.child(
                new ListWidget<>().width(170)
                    .height(160)
                    .child(form));
            container.child(main);
            for (Map.Entry<String, String> name : frame.names.entrySet()) {
                form.child(
                    new ButtonWidget<>().fullWidth()
                        .height(18)
                        .overlay(
                            IKey.dynamic(
                                () -> (name.getKey()
                                    .equals(selected) ? "> " : "") + name.getValue() + " " + price(name.getKey())))
                        .onMousePressed(GuiActions.leftClick(() -> {
                            selected = name.getKey();
                            renderedPage = -1;
                        })));
            }
            if (account == null || account.positions == null) return;
            for (Map.Entry<String, MarginLedger.Position> entry : account.positions.entrySet()) {
                MarginLedger.Position held = entry.getValue();
                if (held.quantity == 0) continue;
                container.child(
                    Flow.row()
                        .fullWidth()
                        .height(18)
                        .child(
                            Widgets
                                .text(
                                    IKey.dynamic(
                                        () -> entry.getKey() + " "
                                            + held.quantity
                                            + " @ "
                                            + String.format(
                                                java.util.Locale.ROOT,
                                                "%.2f",
                                                held.cost / (double) Math.abs(held.quantity) / 100)
                                            + " P/L "
                                            + profit(entry.getKey(), held)))
                                .expanded())
                        .child(button("close_all", () -> {
                            selected = entry.getKey();
                            marketClose(held.quantity, Math.abs(held.quantity));
                        })));
            }
            container.child(
                Flow.row()
                    .fullWidth()
                    .height(18)
                    .childPadding(2)
                    .child(
                        number(
                            "stop_ticks",
                            new IntValue.Dynamic(() -> stopPrice, value -> stopPrice = Math.max(1, value))))
                    .child(
                        number(
                            "take_ticks",
                            new IntValue.Dynamic(() -> takePrice, value -> takePrice = Math.max(1, value))))
                    .child(button("protect", this::protect)));
            if (account.orders != null)
                for (Map.Entry<String, java.util.List<OrderBook.Order>> entry : account.orders.entrySet()) {
                    for (OrderBook.Order order : entry.getValue()) {
                        container.child(
                            Flow.row()
                                .fullWidth()
                                .height(18)
                                .child(
                                    Widgets
                                        .text(
                                            IKey.str(
                                                entry.getKey() + " "
                                                    + order.side
                                                    + " "
                                                    + order.remaining
                                                    + " @ "
                                                    + order.price))
                                        .expanded())
                                .child(button("cancel", () -> sync.callSyncedAction("exchange_cancel", packet -> {
                                    ByteBufUtils.writeUTF8String(packet, generation);
                                    ByteBufUtils.writeUTF8String(packet, entry.getKey());
                                    packet.writeLong(order.id);
                                }))));
                    }
                }
        }

        private String profit(String instrument, MarginLedger.Position held) {
            ClientExchangeData.Frame frame = ClientExchangeData.get();
            if (frame == null || !frame.instruments.containsKey(instrument)) return "-";
            long mark = frame.instruments.get(instrument).lastPrice;
            if (mark <= 0) return "-";
            java.math.BigInteger pnl = java.math.BigInteger.valueOf(mark)
                .multiply(java.math.BigInteger.valueOf(frame.tickValueCents))
                .multiply(java.math.BigInteger.valueOf(Math.abs(held.quantity)))
                .subtract(java.math.BigInteger.valueOf(held.cost))
                .multiply(java.math.BigInteger.valueOf(Long.signum(held.quantity)));
            return CurrencyFormat.signedAmount(pnl.longValue());
        }

        private String price(String instrument) {
            com.yuelengm.nhkantan.market.view.StockView view = ClientMarketData.INSTANCE.get(instrument);
            return view == null ? "-"
                : com.yuelengm.nhkantan.market.view.PriceFormat.price(view.price(), view.priceDecimals());
        }

        private void order(OrderBook.Side side, long count, boolean reduce) {
            ClientExchangeData.Frame frame = ClientExchangeData.get();
            if (account == null || frame == null || !frame.instruments.containsKey(selected)) return;
            com.yuelengm.nhkantan.market.order.SimulatedInstrument.Snapshot market = frame.instruments.get(selected);
            if (market.lastPrice <= 0) return;
            long price = limit && !reduce ? limitPrice
                : side == OrderBook.Side.BUY
                    ? Math.min(market.upperPrice, market.lastPrice + Math.max(1, market.lastPrice / 20))
                    : Math.max(market.lowerPrice, market.lastPrice - Math.max(1, market.lastPrice / 20));
            long request = sequence++;
            sync.callSyncedAction("exchange_order", packet -> {
                ByteBufUtils.writeUTF8String(packet, generation);
                packet.writeLong(request);
                ByteBufUtils.writeUTF8String(packet, selected);
                packet.writeByte(side.ordinal());
                packet.writeLong(price);
                packet.writeLong(count);
                packet.writeBoolean(!limit || reduce);
                packet.writeBoolean(reduce);
            });
        }

        private void marketClose(long signed, long count) {
            order(signed > 0 ? OrderBook.Side.SELL : OrderBook.Side.BUY, count, true);
        }

        private void closeQuantity() {
            if (account == null || account.positions == null) return;
            MarginLedger.Position held = account.positions.get(selected);
            if (held != null && held.quantity != 0)
                marketClose(held.quantity, Math.min(quantity, Math.abs(held.quantity)));
        }

        private void protect() {
            if (account == null) return;
            long request = sequence++;
            sync.callSyncedAction("exchange_protect", packet -> {
                ByteBufUtils.writeUTF8String(packet, generation);
                packet.writeLong(request);
                ByteBufUtils.writeUTF8String(packet, selected);
                packet.writeLong(quantity);
                packet.writeLong(stopPrice);
                packet.writeLong(takePrice);
            });
        }

        private void optionPage(ListWidget<IWidget, ?> container) {
            container.child(Widgets.text(IKey.lang("gui.nhkantan.exchange.option_rules")))
                .child(number("quantity", quantityValue));
            if (account == null || account.contracts == null || account.quotes == null) return;
            for (com.yuelengm.nhkantan.options.OptionMarket.Contract contract : account.contracts) {
                com.yuelengm.nhkantan.options.OptionMarket.Quote quote = account.quotes.get(contract.id);
                if (quote == null) continue;
                container.child(
                    Flow.row()
                        .fullWidth()
                        .height(20)
                        .child(
                            Widgets.text(IKey.dynamic(() -> optionLabel(contract)))
                                .expanded())
                        .child(button("buy_option", () -> {
                            long request = sequence++;
                            long count = quantity;
                            com.yuelengm.nhkantan.options.OptionMarket.Quote currentQuote = account.quotes
                                .get(contract.id);
                            if (currentQuote == null) return;
                            long maximum = Math.multiplyExact(currentQuote.unitCents, count);
                            sync.callSyncedAction("exchange_option", packet -> {
                                ByteBufUtils.writeUTF8String(packet, generation);
                                packet.writeLong(request);
                                ByteBufUtils.writeUTF8String(packet, contract.id);
                                packet.writeLong(currentQuote.id);
                                packet.writeLong(count);
                                packet.writeLong(maximum);
                            });
                        })));
            }
            if (account.options != null)
                for (com.yuelengm.nhkantan.options.OptionMarket.Holding held : account.options) {
                    container
                        .child(
                            Widgets
                                .text(
                                    IKey.str(
                                        held.contract + " x"
                                            + held.quantity
                                            + " "
                                            + CurrencyFormat.amount(held.costCents)
                                            + (held.settled() ? " -> " + CurrencyFormat.amount(held.payoutCents())
                                                : "")))
                                .fullWidth());
                }
        }

        private String optionLabel(com.yuelengm.nhkantan.options.OptionMarket.Contract contract) {
            com.yuelengm.nhkantan.options.OptionMarket.Quote quote = account.quotes.get(contract.id);
            return contract.underlying + " "
                + contract.kind
                + " K="
                + String.format(java.util.Locale.ROOT, "%.2f", contract.strike)
                + " "
                + (quote == null ? "-" : CurrencyFormat.amount(quote.unitCents))
                + " / "
                + remainingDays(contract.expiryTick);
        }

        private String remainingDays(long expiry) {
            ClientExchangeData.Frame frame = ClientExchangeData.get();
            double days = Math.max(0, expiry - (frame == null ? 0 : frame.gameTick)) / 24000D;
            return IKey.lang("gui.nhkantan.exchange.days", String.format(java.util.Locale.ROOT, "%.2f", days))
                .get();
        }

        private void wallet(ListWidget<IWidget, ?> container) {
            boolean debt = account != null && account.account != null && account.account.cash < 0;
            java.util.List<CurrencyItem> items = debt ? ExchangeItems.debtItems() : ExchangeItems.currencyItems();
            container.child(Widgets.text(IKey.lang("gui.nhkantan.exchange.wallet_inventory")))
                .child(number("quantity", quantityValue));
            for (int i = 0; i < items.size(); i++) {
                final int index = i;
                CurrencyItem item = items.get(i);
                container.child(
                    new ButtonWidget<>().fullWidth()
                        .height(20)
                        .overlay(
                            IKey.str(
                                item.displayStack()
                                    .getDisplayName() + " +"
                                    + item.depositValue()
                                    + " / -"
                                    + item.withdrawValue()))
                        .onMousePressed(GuiActions.leftClick(() -> itemIndex = index)));
            }
            container.child(
                Flow.row()
                    .fullWidth()
                    .height(20)
                    .childPadding(3)
                    .child(button(debt ? "repay" : "deposit", () -> walletAction(false)))
                    .child(button("withdraw", () -> walletAction(true))));
        }

        private void walletAction(boolean withdraw) {
            sync.callSyncedAction("exchange_wallet", packet -> {
                packet.writeInt(itemIndex);
                packet.writeInt(quantity);
                packet.writeBoolean(withdraw);
            });
        }
    }
}
