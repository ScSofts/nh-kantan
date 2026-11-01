package com.yuelengm.nhkantan.wallet;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.yuelengm.nhkantan.config.ExchangeConfig;
import com.yuelengm.nhkantan.util.ConfigEntries;

/**
 * Parsed view of the exchange config. Entries are parsed lazily, after all items are registered, and re-parsed when
 * the config array is replaced (e.g. by server-to-client config sync). Invalid entries are logged and skipped.
 */
public class ExchangeItems {

    private static final String CONTEXT = "exchange config";

    private static String[] currencySource;
    private static List<CurrencyItem> currencyItems = Collections.emptyList();
    private static String[] debtSource;
    private static List<DebtItem> debtItems = Collections.emptyList();

    private ExchangeItems() {}

    /**
     * Entries of {@code currencyItems}: {@code modid:itemName;meta;depositValue;depositFee;withdrawValue;withdrawFee}
     */
    public static synchronized List<CurrencyItem> currencyItems() {
        String[] source = ExchangeConfig.currencyItems;
        if (source != currencySource) {
            currencySource = source;
            currencyItems = ConfigEntries.parseEntries(source, 6, ExchangeItems::parseCurrency, CONTEXT);
        }
        return currencyItems;
    }

    /** Entries of {@code debtItems}: {@code modid:itemName;meta;value} */
    public static synchronized List<DebtItem> debtItems() {
        String[] source = ExchangeConfig.debtItems;
        if (source != debtSource) {
            debtSource = source;
            debtItems = ConfigEntries.parseEntries(source, 3, ExchangeItems::parseDebt, CONTEXT);
        }
        return debtItems;
    }

    /** The currency item matching the stack, in either direction. */
    @Nullable
    public static CurrencyItem findCurrency(ItemStack stack) {
        for (CurrencyItem item : currencyItems()) {
            if (item.item()
                .matches(stack)) return item;
        }
        return null;
    }

    /** The currency item matching the stack if it can be deposited. */
    @Nullable
    public static CurrencyItem findDeposit(ItemStack stack) {
        CurrencyItem item = findCurrency(stack);
        return item != null && item.canDeposit() ? item : null;
    }

    @Nullable
    public static DebtItem findDebt(ItemStack stack) {
        for (DebtItem item : debtItems()) {
            if (item.item()
                .matches(stack)) return item;
        }
        return null;
    }

    /** The currency item at {@code index} if it exists and can be withdrawn; validates indices sent by clients. */
    @Nullable
    public static CurrencyItem withdrawable(int index) {
        List<CurrencyItem> items = currencyItems();
        if (index < 0 || index >= items.size()) return null;
        CurrencyItem item = items.get(index);
        return item.canWithdraw() ? item : null;
    }

    private static CurrencyItem parseCurrency(String[] fields) {
        double depositValue = parseValue(fields[2], "depositValue");
        double depositFee = ConfigEntries.parseFraction(fields[3], "depositFee", 1.0D);
        double withdrawValue = parseValue(fields[4], "withdrawValue");
        double withdrawFee = ConfigEntries.parseFraction(fields[5], "withdrawFee", Double.MAX_VALUE);
        if (depositValue < 0.0D && withdrawValue < 0.0D) {
            throw new IllegalArgumentException("depositValue and withdrawValue are both disabled");
        }
        return new CurrencyItem(
            ConfigEntries.parseItem(fields[0], fields[1]),
            depositValue,
            depositFee,
            withdrawValue,
            withdrawFee);
    }

    private static DebtItem parseDebt(String[] fields) {
        return new DebtItem(
            ConfigEntries.parseItem(fields[0], fields[1]),
            ConfigEntries.parsePositive(fields[2], "value"));
    }

    /** A positive value, or a negative one meaning the direction is disabled; zero is rejected. */
    private static double parseValue(String text, String name) {
        double value = ConfigEntries.parseDouble(text, name);
        if (value == 0.0D) throw new IllegalArgumentException(name + " must not be 0; use a negative value to disable");
        return value;
    }
}
