package com.yuelengm.nhkantan.gui;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

import com.cleanroommc.modularui.api.IGuiHolder;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.factory.GuiData;
import com.cleanroommc.modularui.factory.SimpleGuiFactory;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.IntValue;
import com.cleanroommc.modularui.value.StringValue;
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
import com.yuelengm.nhkantan.market.order.ProtectionPrices;
import com.yuelengm.nhkantan.market.order.SimulatedInstrument;
import com.yuelengm.nhkantan.market.server.RealisticService;
import com.yuelengm.nhkantan.market.view.ChartLine;
import com.yuelengm.nhkantan.market.view.ClientExchangeData;
import com.yuelengm.nhkantan.market.view.ClientMarketData;
import com.yuelengm.nhkantan.options.OptionMarket;
import com.yuelengm.nhkantan.options.OptionPricing;
import com.yuelengm.nhkantan.wallet.CurrencyFormat;

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
        Flow content = Flow.column()
            .fullWidth()
            .expanded()
            .childPadding(4);
        Flow header = Flow.row()
            .fullWidth()
            .height(20)
            .childPadding(3)
            .child(
                Widgets.text(
                    IKey.str(
                        "§b◆ §f" + IKey.lang("gui.nhkantan.exchange.title")
                            .get()))
                    .expanded())
            .child(button("trade_tab", () -> state.page = 0))
            .child(button("wallet_tab", () -> sync.callSyncedAction("exchange_wallet_open", packet -> {})))
            .child(button("options_tab", () -> state.page = 2));
        Flow layout = Flow.column()
            .full()
            .childPadding(3)
            .child(header)
            .child(
                Widgets.text(IKey.dynamic(state::summary))
                    .color(0xFFCEE3FA)
                    .fullWidth())
            .child(content)
            .child(
                Widgets.text(IKey.dynamic(() -> state.feedbackText()))
                    .color(0xFF91B3D6)
                    .fullWidth());
        sync.onClientTick(() -> state.refresh(content));
        ModularPanel panel = ModularPanel.defaultPanel("nhkantan_exchange")
            .sizeRel(0.95F, 0.9F)
            .padding(7)
            .background(
                new Rectangle().color(0xFF0A1020),
                new Rectangle().color(0xFF3E638A)
                    .hollow(1))
            .child(layout);
        if (data.getPlayer().worldObj.isRemote)
            NHKantan.proxy.trackExchangeView(panel, () -> state.page, () -> state.selected, () -> state.expiryDay);
        return panel;
    }

    private static ButtonWidget<?> button(String key, Runnable action) {
        return new ButtonWidget<>().height(18)
            .width(62)
            .background(
                new Rectangle().verticalGradient(0xFF263C55, 0xFF182638),
                new Rectangle().color(0xFF426080)
                    .hollow(1))
            .hoverBackground(
                new Rectangle().color(0xFF345675),
                new Rectangle().color(0xFF64D9E9)
                    .hollow(1))
            .overlay(
                IKey.str(
                    "§f" + IKey.lang("gui.nhkantan.exchange." + key)
                        .get()))
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
        private String limitInput = "100.00";
        private String stopInput = "99.00";
        private String takeInput = "101.00";
        private String lossInput = "5.00";
        private String gainInput = "10.00";
        private boolean profitMode;
        private int expiryDay = 1;
        private String localError = "";
        private String requestedView = "";
        private boolean limit;
        private final IntValue.Dynamic quantityValue = new IntValue.Dynamic(
            () -> quantity,
            value -> quantity = Math.max(1, value));

        private State(EntityPlayer player, PanelSyncManager sync) {
            this.player = player;
            this.sync = sync;
            snapshot = new StringSyncValue(() -> "{}", () -> {
                RealisticService service = RealisticService.get();
                return service == null || !(player instanceof EntityPlayerMP) ? "{}"
                    : service.privateView((EntityPlayerMP) player, page == 2 ? selected : null, expiryDay);
            });
            feedback = new StringSyncValue(() -> "", result::get);
            sync.registerSyncedAction("exchange_view", Side.SERVER, packet -> {
                int requestedPage = packet.readUnsignedByte();
                String instrument = ByteBufUtils.readUTF8String(packet);
                int day = packet.readUnsignedByte();
                if ((requestedPage == 0 || requestedPage == 2) && instrument.length() <= 128 && day >= 1 && day <= 14) {
                    page = requestedPage;
                    selected = instrument;
                    expiryDay = day;
                }
            });
            sync.registerSyncedAction(
                "exchange_wallet_open",
                Side.SERVER,
                packet -> { if (ItemMarketTerminal.isHeldBy(player)) WalletGui.open(serverPlayer()); });
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
            sync.registerSyncedAction("exchange_close_all", Side.SERVER, packet -> {
                try {
                    java.util.UUID epoch = java.util.UUID.fromString(ByteBufUtils.readUTF8String(packet));
                    long request = packet.readLong();
                    track(service().closePosition(serverPlayer(), epoch, request, ByteBufUtils.readUTF8String(packet)));
                } catch (Exception e) {
                    result.set("REJECTED");
                }
            });
            sync.registerSyncedAction("exchange_option_exit", Side.SERVER, packet -> {
                try {
                    java.util.UUID epoch = java.util.UUID.fromString(ByteBufUtils.readUTF8String(packet));
                    long request = packet.readLong();
                    track(
                        service().exitOption(
                            serverPlayer(),
                            epoch,
                            request,
                            packet.readLong(),
                            packet.readLong(),
                            packet.readBoolean(),
                            packet.readLong(),
                            packet.readLong()));
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
            if (!localError.isEmpty()) return "§c" + IKey.lang("gui.nhkantan.exchange.invalid_input")
                .get();
            String code = feedback.getStringValue();
            return ("REJECTED".equals(code) ? "§c" : "PENDING".equals(code) ? "§e" : "§b") + IKey.lang(
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

        private void refresh(Flow container) {
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
            if (frame != null && !frame.instruments.containsKey(selected)) select(
                frame.instruments.keySet()
                    .stream()
                    .findFirst()
                    .orElse(""));
            String viewKey = page + ":" + selected + ":" + expiryDay;
            if (!viewKey.equals(requestedView)) {
                requestedView = viewKey;
                sync.callSyncedAction("exchange_view", packet -> {
                    packet.writeByte(page);
                    ByteBufUtils.writeUTF8String(packet, selected);
                    packet.writeByte(expiryDay);
                });
            }
            StringBuilder key = new StringBuilder().append(page)
                .append(':')
                .append(selected)
                .append(':')
                .append(expiryDay);
            if (frame != null) key.append(frame.names.keySet());
            if (account != null && account.positions != null) {
                key.append(account.account != null && account.account.cash < 0)
                    .append(account.closing);
                for (Map.Entry<String, MarginLedger.Position> entry : account.positions.entrySet())
                    key.append(entry.getKey())
                        .append(entry.getValue().quantity);
                if (account.orders != null) for (java.util.List<OrderBook.Order> orders : account.orders.values()) {
                    for (OrderBook.Order order : orders) key.append(order.id)
                        .append(':')
                        .append(order.remaining);
                }
            }
            if (page == 2 && frame != null) key.append(frame.gameTick / 24000);
            if (page == 2 && account != null && account.quotes != null) key.append(account.quotes.keySet())
                .append(new Gson().toJson(account.options));
            String nextStructure = key.toString();
            if (renderedPage == page && structure.equals(nextStructure)) return;
            structure = nextStructure;
            renderedPage = page;
            container.removeAll();
            if (page == 2) optionPage(container);
            else trade(container);
            if (container.isValid()) container.scheduleResize();
        }

        private void trade(Flow container) {
            ClientExchangeData.Frame frame = ClientExchangeData.get();
            if (frame == null) return;
            if (!frame.instruments.containsKey(selected)) select(
                frame.instruments.keySet()
                    .stream()
                    .findFirst()
                    .orElse(""));
            Flow main = Flow.row()
                .fullWidth()
                .expanded()
                .childPadding(4);
            Flow chart = Flow.column()
                .expanded()
                .fullHeight()
                .childPadding(2)
                .child(
                    NHKantan.proxy.createStockChart(() -> ClientMarketData.INSTANCE.get(selected), this::chartLines)
                        .fullWidth()
                        // Use explicit remaining height: nested Flow expansion can leave a bare Widget at 18 px.
                        .heightRelOffset(1F, -13))
                .child(
                    Widgets.text(IKey.lang("gui.nhkantan.exchange.chart_hint"))
                        .height(11)
                        .color(0xFF91A8C6)
                        .textAlign(Alignment.CenterLeft)
                        .fullWidth());
            main.child(chart);
            ListWidget<IWidget, ?> sidebar = new ListWidget<>().width(184)
                .fullHeight()
                .background(new Rectangle().color(0xFF141F31))
                .padding(5);
            Flow form = Flow.column()
                .fullWidth()
                .coverChildrenHeight()
                .childPadding(4);
            form.child(label("quantity"));
            form.child(
                Flow.row()
                    .fullWidth()
                    .height(18)
                    .childPadding(3)
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
                            .size(20, 18))
                    .child(number("quantity", quantityValue).expanded())
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
                            .size(20, 18)));
            form.child(
                Widgets
                    .text(
                        IKey.lang("gui.nhkantan.exchange.margin", frame.marginBps / 100D, frame.commissionPpm / 10000D))
                    .color(0xFF9BB7D4)
                    .fullWidth());
            form.child(button(limit ? "limit" : "market", this::toggleOrderType).fullWidth());
            if (limit) form.child(label("limit_price"))
                .child(decimal(() -> limitInput, value -> limitInput = value));
            form.child(
                Flow.row()
                    .fullWidth()
                    .height(22)
                    .childPadding(4)
                    .child(
                        button("buy", () -> order(OrderBook.Side.BUY, quantity, false)).expanded()
                            .height(22)
                            .background(new Rectangle().verticalGradient(0xFF15755D, 0xFF103E39)))
                    .child(
                        button("sell", () -> order(OrderBook.Side.SELL, quantity, false)).expanded()
                            .height(22)
                            .background(new Rectangle().verticalGradient(0xFF983F58, 0xFF50283D))));
            form.child(button("close_quantity", this::closeQuantity).fullWidth());
            form.child(label("protection_title"));
            form.child(button(profitMode ? "profit_mode" : "price_mode", this::toggleProtectionMode).fullWidth());
            form.child(label(profitMode ? "stop_loss" : "stop_price"))
                .child(decimal(() -> profitMode ? lossInput : stopInput, value -> {
                    if (profitMode) lossInput = value;
                    else stopInput = value;
                }))
                .child(label(profitMode ? "take_profit" : "take_price"))
                .child(decimal(() -> profitMode ? gainInput : takeInput, value -> {
                    if (profitMode) gainInput = value;
                    else takeInput = value;
                }))
                .child(
                    Widgets.text(IKey.dynamic(this::protectionPreview))
                        .color(0xFF8ECDE4)
                        .fullWidth())
                .child(button("protect", this::protect).fullWidth());
            form.child(label("instruments"));
            for (Map.Entry<String, String> name : frame.names.entrySet()) {
                form.child(
                    new ButtonWidget<>().fullWidth()
                        .height(22)
                        .background(
                            new Rectangle().color(
                                name.getKey()
                                    .equals(selected) ? 0xFF25455E : 0xFF1C2C40))
                        .hoverBackground(new Rectangle().color(0xFF345675))
                        .overlay(
                            IKey.dynamic(
                                () -> "§b" + (name.getKey()
                                    .equals(selected) ? "› " : "") + name.getValue() + " §f" + price(name.getKey())))
                        .onMousePressed(GuiActions.leftClick(() -> select(name.getKey()))));
            }
            sidebar.child(form);
            main.child(sidebar);
            container.child(main);
            ListWidget<IWidget, ?> positions = new ListWidget<>().fullWidth()
                .height(60)
                .background(new Rectangle().color(0xFF141F31))
                .padding(3);
            positions.child(label("positions_orders"));
            container.child(positions);
            if (account == null || account.positions == null) return;
            for (Map.Entry<String, MarginLedger.Position> entry : account.positions.entrySet()) {
                if (entry.getValue().quantity == 0) continue;
                positions.child(
                    Flow.row()
                        .fullWidth()
                        .height(19)
                        .childPadding(4)
                        .child(
                            Widgets.text(IKey.dynamic(() -> positionLabel(entry.getKey())))
                                .color(0xFFDAE6F5)
                                .expanded())
                        .child(
                            button(
                                account.closing != null && account.closing.contains(entry.getKey()) ? "closing"
                                    : "close_all",
                                () -> {
                                    long request = sequence++;
                                    sync.callSyncedAction("exchange_close_all", packet -> {
                                        ByteBufUtils.writeUTF8String(packet, generation);
                                        packet.writeLong(request);
                                        ByteBufUtils.writeUTF8String(packet, entry.getKey());
                                    });
                                })));
            }
            if (account.orders != null)
                for (Map.Entry<String, List<OrderBook.Order>> entry : account.orders.entrySet()) {
                    for (OrderBook.Order order : entry.getValue()) {
                        positions.child(
                            Flow.row()
                                .fullWidth()
                                .height(19)
                                .childPadding(4)
                                .child(
                                    Widgets
                                        .text(
                                            IKey.str(
                                                (order.side == OrderBook.Side.BUY ? "§a" : "§c")
                                                    + instrumentName(entry.getKey())
                                                    + " "
                                                    + IKey
                                                        .lang(
                                                            "gui.nhkantan.exchange."
                                                                + (order.side == OrderBook.Side.BUY ? "buy" : "sell"))
                                                        .get()
                                                    + " "
                                                    + order.remaining
                                                    + " @ "
                                                    + tickPrice(order.price)))
                                        .expanded())
                                .child(button("cancel", () -> sync.callSyncedAction("exchange_cancel", packet -> {
                                    ByteBufUtils.writeUTF8String(packet, generation);
                                    ByteBufUtils.writeUTF8String(packet, entry.getKey());
                                    packet.writeLong(order.id);
                                }))));
                    }
                }
        }

        private com.cleanroommc.modularui.widgets.TextWidget<?> label(String key) {
            return Widgets.text(IKey.lang("gui.nhkantan.exchange." + key))
                .color(0xFFBDCCE0)
                .textAlign(Alignment.CenterLeft)
                .fullWidth();
        }

        private TextFieldWidget decimal(java.util.function.Supplier<String> getter,
            java.util.function.Consumer<String> setter) {
            return new TextFieldWidget().fullWidth()
                .height(18)
                .setMaxLength(18)
                .setPattern(java.util.regex.Pattern.compile("[0-9]*([.][0-9]{0,2})?"))
                .value(new StringValue.Dynamic(getter, setter))
                .autoUpdateOnChange(true);
        }

        private void select(String instrument) {
            selected = instrument;
            renderedPage = -1;
            ClientExchangeData.Frame frame = ClientExchangeData.get();
            if (frame == null || !frame.instruments.containsKey(instrument)) return;
            long last = frame.instruments.get(instrument).lastPrice;
            limitInput = tickPrice(Math.max(1, last));
            SimulatedInstrument.Protection existing = account == null || account.protections == null ? null
                : account.protections.get(instrument);
            MarginLedger.Position held = account == null || account.positions == null ? null
                : account.positions.get(instrument);
            long entry = held == null || held.quantity == 0 ? Math.max(1, last)
                : Math.max(1, Math.round(held.cost / (double) Math.abs(held.quantity) / frame.tickValueCents));
            int direction = held != null && held.quantity < 0 ? -1 : 1;
            stopInput = tickPrice(
                existing == null ? Math.max(1, entry - direction * Math.max(1, entry / 100)) : existing.stopPrice);
            takeInput = tickPrice(
                existing == null ? Math.max(1, entry + direction * Math.max(1, entry / 100)) : existing.takePrice);
        }

        private void toggleOrderType() {
            limit = !limit;
            if (limit) {
                ClientExchangeData.Frame frame = ClientExchangeData.get();
                if (frame != null && frame.instruments.containsKey(selected))
                    limitInput = tickPrice(Math.max(1, frame.instruments.get(selected).lastPrice));
            }
            renderedPage = -1;
        }

        private String tickPrice(long ticks) {
            ClientExchangeData.Frame frame = ClientExchangeData.get();
            return BigDecimal.valueOf(ticks)
                .multiply(BigDecimal.valueOf(frame == null ? 1 : frame.tickValueCents))
                .movePointLeft(2)
                .setScale(2)
                .toPlainString();
        }

        private long cents(String value) {
            return new BigDecimal(value).movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        }

        private long inputTicks(String value) {
            ClientExchangeData.Frame frame = ClientExchangeData.get();
            if (frame == null) throw new IllegalStateException("No market");
            return BigDecimal.valueOf(cents(value))
                .divide(BigDecimal.valueOf(frame.tickValueCents), 0, RoundingMode.HALF_UP)
                .longValueExact();
        }

        private long[] protectionTargets() {
            if (!profitMode) return new long[] { inputTicks(stopInput), inputTicks(takeInput) };
            MarginLedger.Position held = account == null || account.positions == null ? null
                : account.positions.get(selected);
            ClientExchangeData.Frame frame = ClientExchangeData.get();
            if (held == null || held.quantity == 0 || frame == null) throw new IllegalStateException("No position");
            long count = Math.min(quantity, Math.abs(held.quantity));
            long loss = cents(lossInput), gain = cents(gainInput);
            if (loss < 0 || gain < 0) throw new IllegalArgumentException("Negative amount");
            return new long[] {
                loss == 0 ? 0
                    : ProtectionPrices.fromProfit(held.quantity, held.cost, count, -loss, frame.tickValueCents),
                gain == 0 ? 0
                    : ProtectionPrices.fromProfit(held.quantity, held.cost, count, gain, frame.tickValueCents) };
        }

        private void toggleProtectionMode() {
            try {
                if (profitMode) {
                    long[] targets = protectionTargets();
                    stopInput = tickPrice(targets[0]);
                    takeInput = tickPrice(targets[1]);
                } else {
                    MarginLedger.Position held = account == null || account.positions == null ? null
                        : account.positions.get(selected);
                    if (held != null && held.quantity != 0) {
                        long count = Math.min(quantity, Math.abs(held.quantity));
                        double entryCents = held.cost / (double) Math.abs(held.quantity);
                        int direction = Long.signum(held.quantity);
                        lossInput = inputTicks(stopInput) == 0 ? "0.00"
                            : BigDecimal.valueOf(Math.max(0, (entryCents - cents(stopInput)) * count * direction))
                                .movePointLeft(2)
                                .setScale(2, RoundingMode.HALF_UP)
                                .toPlainString();
                        gainInput = inputTicks(takeInput) == 0 ? "0.00"
                            : BigDecimal.valueOf(Math.max(0, (cents(takeInput) - entryCents) * count * direction))
                                .movePointLeft(2)
                                .setScale(2, RoundingMode.HALF_UP)
                                .toPlainString();
                    }
                }
                localError = "";
            } catch (RuntimeException e) {
                localError = "invalid";
            }
            profitMode = !profitMode;
            renderedPage = -1;
        }

        private String protectionPreview() {
            try {
                long[] targets = protectionTargets();
                return IKey
                    .lang("gui.nhkantan.exchange.protection_preview", tickPrice(targets[0]), tickPrice(targets[1]))
                    .get();
            } catch (RuntimeException e) {
                return IKey.lang("gui.nhkantan.exchange.protection_hint")
                    .get();
            }
        }

        private String instrumentName(String id) {
            ClientExchangeData.Frame frame = ClientExchangeData.get();
            return frame == null ? id : frame.names.getOrDefault(id, id);
        }

        private String positionLabel(String id) {
            MarginLedger.Position held = account.positions.get(id);
            if (held == null || held.quantity == 0) return "";
            return instrumentName(id) + " "
                + held.quantity
                + " @ "
                + String.format(java.util.Locale.ROOT, "%.2f", held.cost / (double) Math.abs(held.quantity) / 100)
                + "  "
                + profit(id, held);
        }

        private List<ChartLine> chartLines() {
            List<ChartLine> lines = new ArrayList<>();
            ClientExchangeData.Frame frame = ClientExchangeData.get();
            if (frame == null || account == null || account.positions == null) return lines;
            MarginLedger.Position held = account.positions.get(selected);
            if (held != null && held.quantity != 0) lines.add(
                new ChartLine(
                    held.cost / (double) Math.abs(held.quantity) / 100,
                    IKey.lang("gui.nhkantan.exchange.position_line")
                        .get() + " "
                        + held.quantity
                        + " "
                        + profit(selected, held),
                    0x64B5FF));
            SimulatedInstrument.Protection protection = account.protections == null ? null
                : account.protections.get(selected);
            if (protection != null) {
                if (protection.stopPrice > 0) lines.add(
                    new ChartLine(
                        protection.stopPrice * frame.tickValueCents / 100D,
                        IKey.lang("gui.nhkantan.exchange.stop_line")
                            .get(),
                        0xFF647F));
                if (protection.takePrice > 0) lines.add(
                    new ChartLine(
                        protection.takePrice * frame.tickValueCents / 100D,
                        IKey.lang("gui.nhkantan.exchange.take_line")
                            .get(),
                        0x42E5A4));
            }
            if (account.orders != null)
                for (OrderBook.Order order : account.orders.getOrDefault(selected, java.util.Collections.emptyList())) {
                    if (protection != null && order.reduceOnly && order.price == protection.takePrice) continue;
                    lines.add(
                        new ChartLine(
                            order.price * frame.tickValueCents / 100D,
                            IKey.lang("gui.nhkantan.exchange.order_line")
                                .get() + " #"
                                + order.id
                                + " ×"
                                + order.remaining,
                            order.side == OrderBook.Side.BUY ? 0x50D9EE : 0xFFBD68));
                }
            return lines;
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
            return (pnl.signum() < 0 ? "§c" : pnl.signum() > 0 ? "§a" : "§7")
                + CurrencyFormat.signedAmount(pnl.longValue())
                + "§r";
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
            long parsedPrice;
            try {
                parsedPrice = limit && !reduce ? inputTicks(limitInput) : 0;
                if (limit && !reduce && parsedPrice < 1) throw new IllegalArgumentException("Invalid limit");
                localError = "";
            } catch (RuntimeException e) {
                localError = "invalid";
                return;
            }
            long price = limit && !reduce ? parsedPrice
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
            if (account == null || account.positions == null) return;
            MarginLedger.Position held = account.positions.get(selected);
            if (held == null || held.quantity == 0) {
                localError = "invalid";
                return;
            }
            final long[] targets;
            try {
                targets = protectionTargets();
                if (targets[0] < 0 || targets[1] < 0 || (targets[0] == 0 && targets[1] == 0))
                    throw new IllegalArgumentException("Empty protection");
                localError = "";
            } catch (RuntimeException e) {
                localError = "invalid";
                return;
            }
            long request = sequence++;
            long count = Math.min(quantity, Math.abs(held.quantity));
            sync.callSyncedAction("exchange_protect", packet -> {
                ByteBufUtils.writeUTF8String(packet, generation);
                packet.writeLong(request);
                ByteBufUtils.writeUTF8String(packet, selected);
                packet.writeLong(count);
                packet.writeLong(targets[0]);
                packet.writeLong(targets[1]);
            });
        }

        private void optionPage(Flow container) {
            ClientExchangeData.Frame frame = ClientExchangeData.get();
            if (frame == null) return;
            Flow instruments = Flow.row()
                .fullWidth()
                .height(20)
                .childPadding(3);
            for (Map.Entry<String, String> item : frame.names.entrySet()) {
                instruments.child(
                    new ButtonWidget<>().expanded()
                        .height(20)
                        .background(
                            new Rectangle().color(
                                item.getKey()
                                    .equals(selected) ? 0xFF285A70 : 0xFF1D3048))
                        .overlay(IKey.str("§f" + item.getValue()))
                        .onMousePressed(GuiActions.leftClick(() -> select(item.getKey()))));
            }
            container.child(instruments);
            for (int row = 0; row < 2; row++) {
                Flow tabs = Flow.row()
                    .fullWidth()
                    .height(18)
                    .childPadding(2);
                for (int i = 1; i <= 7; i++) {
                    final int day = row * 7 + i;
                    tabs.child(
                        new ButtonWidget<>().expanded()
                            .height(18)
                            .background(new Rectangle().color(day == expiryDay ? 0xFF367C94 : 0xFF20314B))
                            .hoverBackground(new Rectangle().color(0xFF3C6885))
                            .overlay(
                                IKey.str(
                                    "§f" + IKey.lang("gui.nhkantan.exchange.expiry_tab", day)
                                        .get()))
                            .onMousePressed(GuiActions.leftClick(() -> {
                                expiryDay = day;
                                renderedPage = -1;
                            })));
                }
                container.child(tabs);
            }
            container.child(
                Flow.row()
                    .fullWidth()
                    .height(18)
                    .childPadding(4)
                    .child(label("option_quantity").expanded())
                    .child(number("quantity", quantityValue)));
            container.child(label("option_rules"));
            container.child(
                Flow.row()
                    .fullWidth()
                    .height(18)
                    .childPadding(2)
                    .child(
                        Widgets.text(IKey.lang("gui.nhkantan.exchange.call_column"))
                            .color(0xFF58E7AD)
                            .expanded())
                    .child(
                        Widgets.text(IKey.lang("gui.nhkantan.exchange.strike_column"))
                            .color(0xFFFFDA87)
                            .width(76))
                    .child(
                        Widgets.text(IKey.lang("gui.nhkantan.exchange.put_column"))
                            .color(0xFFFF8DAB)
                            .expanded()));
            ListWidget<IWidget, ?> chain = new ListWidget<>().fullWidth()
                .expanded()
                .padding(3)
                .background(new Rectangle().color(0xFF121F32));
            container.child(chain);
            if (account == null || account.contracts == null || account.quotes == null) return;
            java.util.SortedMap<Double, OptionMarket.Contract[]> rows = new java.util.TreeMap<>();
            long now = frame.gameTick;
            for (OptionMarket.Contract contract : account.contracts) {
                if (!contract.underlying.equals(selected) || contract.expiryTick <= now
                    || (contract.expiryTick - now + 23999) / 24000 != expiryDay) continue;
                OptionMarket.Contract[] pair = rows
                    .computeIfAbsent(contract.strike, key -> new OptionMarket.Contract[2]);
                pair[contract.kind == OptionPricing.Kind.CALL ? 0 : 1] = contract;
            }
            if (rows.isEmpty()) chain.child(label("loading"));
            for (Map.Entry<Double, OptionMarket.Contract[]> row : rows.entrySet()) {
                OptionMarket.Contract[] pair = row.getValue();
                chain.child(
                    Flow.row()
                        .fullWidth()
                        .height(25)
                        .childPadding(2)
                        .child(optionButton(pair[0], true).expanded())
                        .child(
                            Widgets.text(IKey.str("§e" + String.format(java.util.Locale.ROOT, "%.2f", row.getKey())))
                                .width(76)
                                .height(25)
                                .background(new Rectangle().color(0xFF27374B)))
                        .child(optionButton(pair[1], false).expanded()));
            }
            if (!rows.isEmpty()) {
                OptionMarket.Contract first = rows.get(rows.firstKey())[0];
                if (first != null) chain.child(
                    Widgets.text(IKey.dynamic(() -> "§b" + remainingDays(first.expiryTick)))
                        .fullWidth());
            }
            chain.child(label("option_holdings"));
            if (account.options != null) for (OptionMarket.Holding held : account.options) {
                if (held.remainingQuantity() == 0) continue;
                chain.child(
                    Flow.column()
                        .fullWidth()
                        .height(43)
                        .childPadding(3)
                        .child(
                            Widgets.text(IKey.dynamic(() -> optionHoldingLabel(held.id)))
                                .color(0xFFDAE5F5)
                                .fullWidth())
                        .child(
                            Flow.row()
                                .fullWidth()
                                .height(20)
                                .childPadding(4)
                                .child(
                                    Widgets.text(IKey.dynamic(() -> "§7×" + exitQuantity(held.id)))
                                        .expanded())
                                .child(exitButton(held.id, false))
                                .child(exitButton(held.id, true))));
            }
        }

        private OptionMarket.Holding optionHolding(long id) {
            if (account != null && account.options != null)
                for (OptionMarket.Holding held : account.options) if (held.id == id) return held;
            return null;
        }

        private long exitQuantity(long id) {
            OptionMarket.Holding held = optionHolding(id);
            return held == null ? 0 : Math.min(quantity, held.remainingQuantity());
        }

        private long exitPayout(long id, boolean exercise) {
            OptionMarket.Holding held = optionHolding(id);
            if (held == null || held.remainingQuantity() == 0) return 0;
            long count = exitQuantity(id);
            if (!exercise) {
                OptionMarket.Quote quote = account.holdingQuotes == null ? null
                    : account.holdingQuotes.get(held.contract);
                return quote == null ? 0 : Math.multiplyExact(quote.bidUnitCents, count);
            }
            OptionMarket.Contract contract = account.holdingContracts == null ? null
                : account.holdingContracts.get(held.contract);
            ClientExchangeData.Frame frame = ClientExchangeData.get();
            if (contract == null || frame == null || !frame.instruments.containsKey(contract.underlying)) return 0;
            return OptionMarket.intrinsicCents(
                contract,
                frame.instruments.get(contract.underlying).lastPrice * frame.tickValueCents / 100D,
                count);
        }

        private String optionHoldingLabel(long id) {
            OptionMarket.Holding held = optionHolding(id);
            if (held == null) return "";
            OptionMarket.Contract contract = account.holdingContracts == null ? null
                : account.holdingContracts.get(held.contract);
            OptionMarket.Quote quote = account.holdingQuotes == null ? null : account.holdingQuotes.get(held.contract);
            long pnl = quote == null ? 0
                : Math.subtractExact(
                    Math.multiplyExact(quote.bidUnitCents, held.remainingQuantity()),
                    held.remainingCostCents());
            String detail = contract == null ? held.contract
                : instrumentName(contract.underlying) + " "
                    + contract.kind
                    + " K="
                    + String.format(java.util.Locale.ROOT, "%.2f", contract.strike);
            return detail + " ×"
                + held.remainingQuantity()
                + " "
                + (pnl < 0 ? "§c" : "§a")
                + CurrencyFormat.signedAmount(pnl)
                + "§r";
        }

        private ButtonWidget<?> exitButton(long holding, boolean exercise) {
            return new ButtonWidget<>().width(112)
                .height(20)
                .background(new Rectangle().color(exercise ? 0xFF504265 : 0xFF215665))
                .hoverBackground(new Rectangle().color(exercise ? 0xFF77618E : 0xFF337A87))
                .overlay(
                    IKey.dynamic(
                        () -> "§f"
                            + IKey.lang("gui.nhkantan.exchange." + (exercise ? "exercise_option" : "close_option"))
                                .get()
                            + " ≈"
                            + CurrencyFormat.amount(exitPayout(holding, exercise))))
                .addTooltipLine(
                    IKey.lang("gui.nhkantan.exchange." + (exercise ? "exercise_hint" : "close_option_hint")))
                .onMousePressed(GuiActions.leftClick(() -> exitOption(holding, exercise)));
        }

        private void exitOption(long id, boolean exercise) {
            OptionMarket.Holding held = optionHolding(id);
            if (held == null || held.remainingQuantity() == 0) return;
            long minimum = exitPayout(id, exercise);
            OptionMarket.Quote quote = account.holdingQuotes == null ? null : account.holdingQuotes.get(held.contract);
            if ((exercise && minimum <= 0) || (!exercise && quote == null)) {
                localError = "invalid";
                return;
            }
            localError = "";
            long count = exitQuantity(id);
            long request = sequence++;
            sync.callSyncedAction("exchange_option_exit", packet -> {
                ByteBufUtils.writeUTF8String(packet, generation);
                packet.writeLong(request);
                packet.writeLong(id);
                packet.writeLong(count);
                packet.writeBoolean(exercise);
                packet.writeLong(quote == null ? 0 : quote.id);
                packet.writeLong(minimum);
            });
        }

        private ButtonWidget<?> optionButton(OptionMarket.Contract contract, boolean call) {
            return new ButtonWidget<>().height(25)
                .background(new Rectangle().verticalGradient(call ? 0xFF164D46 : 0xFF573047, 0xFF17283D))
                .hoverBackground(new Rectangle().color(call ? 0xFF277363 : 0xFF81445C))
                .overlay(IKey.dynamic(() -> {
                    OptionMarket.Quote quote = contract == null || account.quotes == null ? null
                        : account.quotes.get(contract.id);
                    return quote == null ? "§7—"
                        : (call ? "§a" : "§d") + CurrencyFormat.amount(quote.unitCents)
                            + " §f"
                            + IKey.lang("gui.nhkantan.exchange.buy")
                                .get();
                }))
                .addTooltipLine(IKey.dynamic(() -> {
                    OptionMarket.Quote quote = contract == null || account.quotes == null ? null
                        : account.quotes.get(contract.id);
                    return IKey
                        .lang(
                            "gui.nhkantan.exchange.buyback_quote",
                            quote == null ? "—" : CurrencyFormat.amount(quote.bidUnitCents))
                        .get();
                }))
                .onMousePressed(GuiActions.leftClick(() -> buyOption(contract)));
        }

        private void buyOption(OptionMarket.Contract contract) {
            if (contract == null || account == null || account.quotes == null) return;
            OptionMarket.Quote quote = account.quotes.get(contract.id);
            if (quote == null) return;
            long request = sequence++;
            long count = quantity;
            long maximum = Math.multiplyExact(quote.unitCents, count);
            sync.callSyncedAction("exchange_option", packet -> {
                ByteBufUtils.writeUTF8String(packet, generation);
                packet.writeLong(request);
                ByteBufUtils.writeUTF8String(packet, contract.id);
                packet.writeLong(quote.id);
                packet.writeLong(count);
                packet.writeLong(maximum);
            });
        }

        private String remainingDays(long expiry) {
            ClientExchangeData.Frame frame = ClientExchangeData.get();
            double days = Math.max(0, expiry - (frame == null ? 0 : frame.gameTick)) / 24000D;
            return IKey.lang("gui.nhkantan.exchange.days", String.format(java.util.Locale.ROOT, "%.2f", days))
                .get();
        }
    }
}
