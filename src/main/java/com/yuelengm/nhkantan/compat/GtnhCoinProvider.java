package com.yuelengm.nhkantan.compat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.item.Item;

import com.yuelengm.nhkantan.NHKantan;
import com.yuelengm.nhkantan.config.CompatConfig;
import com.yuelengm.nhkantan.util.Denominations;
import com.yuelengm.nhkantan.util.ItemSpec;
import com.yuelengm.nhkantan.wallet.CurrencyItem;
import com.yuelengm.nhkantan.wallet.DebtItem;
import com.yuelengm.nhkantan.wallet.ExchangeItems;
import com.yuelengm.nhkantan.wallet.ExchangeProvider;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.registry.GameRegistry;

/**
 * GTNH quest coins from NewHorizonsCoreMod ({@code dreamcraft}). Every coin type comes in five sizes; each type is one
 * entry whose family covers all sizes, priced per base coin, so withdrawals are paid out in the largest coins.
 * Configured by {@link CompatConfig}; rebuilt whenever one of its values changes.
 */
public class GtnhCoinProvider implements ExchangeProvider {

    private static final String MOD_ID = "dreamcraft";
    private static final List<String> COIN_TYPES = Arrays.asList(
        "CoinAdventure",
        "CoinBees",
        "CoinBlood",
        "CoinChemist",
        "CoinCook",
        "CoinDarkWizard",
        "CoinFarmer",
        "CoinFlower",
        "CoinForestry",
        "CoinSmith",
        "CoinSpace",
        "CoinSurvivor",
        "CoinTechnician",
        "CoinWitch");
    private static final String[] SIZE_SUFFIXES = { "IV", "III", "II", "I", "" };
    private static final int[] SIZE_UNITS = { 10000, 1000, 100, 10, 1 };

    private double[] source;
    private List<CurrencyItem> currencyItems = Collections.emptyList();
    private List<DebtItem> debtItems = Collections.emptyList();

    /** Registers the provider when the core mod is present. */
    public static void registerIfLoaded() {
        if (Loader.isModLoaded(MOD_ID)) ExchangeItems.register(new GtnhCoinProvider());
    }

    @Override
    public synchronized List<CurrencyItem> currencyItems() {
        refresh();
        return currencyItems;
    }

    @Override
    public synchronized List<DebtItem> debtItems() {
        refresh();
        return debtItems;
    }

    private void refresh() {
        double[] current = { CompatConfig.gtnhCoins ? 1.0D : 0.0D, CompatConfig.gtnhCoinDepositValue,
            CompatConfig.gtnhCoinDepositFee, CompatConfig.gtnhCoinWithdrawValue, CompatConfig.gtnhCoinWithdrawFee,
            CompatConfig.gtnhCoinDebtValue };
        if (Arrays.equals(current, source)) return;
        source = current;
        List<CurrencyItem> currency = new ArrayList<>();
        List<DebtItem> debt = new ArrayList<>();
        if (CompatConfig.gtnhCoins) build(currency, debt);
        currencyItems = Collections.unmodifiableList(currency);
        debtItems = Collections.unmodifiableList(debt);
    }

    private static void build(List<CurrencyItem> currency, List<DebtItem> debt) {
        boolean exchangeable = CompatConfig.gtnhCoinDepositValue >= 0.0D || CompatConfig.gtnhCoinWithdrawValue >= 0.0D;
        boolean repayable = CompatConfig.gtnhCoinDebtValue > 0.0D;
        for (String type : COIN_TYPES) {
            Denominations coins = coinFamily(type);
            if (coins == null) continue;
            if (exchangeable) {
                currency.add(
                    new CurrencyItem(
                        coins,
                        CompatConfig.gtnhCoinDepositValue,
                        CompatConfig.gtnhCoinDepositFee,
                        CompatConfig.gtnhCoinWithdrawValue,
                        CompatConfig.gtnhCoinWithdrawFee));
            }
            if (repayable) debt.add(new DebtItem(coins, CompatConfig.gtnhCoinDebtValue));
        }
    }

    /** All registered sizes of one coin type, or {@code null} if the base coin is missing. */
    @Nullable
    private static Denominations coinFamily(String type) {
        List<Denominations.Denomination> sizes = new ArrayList<>(SIZE_SUFFIXES.length);
        for (int i = 0; i < SIZE_SUFFIXES.length; i++) {
            Item item = GameRegistry.findItem(MOD_ID, type + SIZE_SUFFIXES[i]);
            if (item != null) sizes.add(new Denominations.Denomination(new ItemSpec(item, 0), SIZE_UNITS[i]));
        }
        boolean hasBase = sizes.stream()
            .anyMatch(size -> size.units() == 1);
        if (!hasBase) {
            NHKantan.LOG.warn("Skipping GTNH coin type {}: base coin not found", type);
            return null;
        }
        return new Denominations(sizes);
    }
}
