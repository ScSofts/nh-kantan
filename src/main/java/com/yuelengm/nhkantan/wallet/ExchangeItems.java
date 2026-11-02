package com.yuelengm.nhkantan.wallet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

/**
 * Merged view of all {@link ExchangeProvider}s in registration order. The merged lists are rebuilt only when a
 * provider hands out a different list instance, i.e. when its configuration changed.
 */
public class ExchangeItems {

    private static final List<ExchangeProvider> PROVIDERS = new ArrayList<>();

    private static List<List<CurrencyItem>> currencySources = Collections.emptyList();
    private static List<CurrencyItem> currencyItems = Collections.emptyList();
    private static List<List<DebtItem>> debtSources = Collections.emptyList();
    private static List<DebtItem> debtItems = Collections.emptyList();

    static {
        register(new ConfigExchangeProvider());
    }

    private ExchangeItems() {}

    /** Adds a provider; its items are listed after those of earlier providers. */
    public static synchronized void register(ExchangeProvider provider) {
        PROVIDERS.add(provider);
    }

    public static synchronized List<CurrencyItem> currencyItems() {
        if (changed(currencySources, ExchangeProvider::currencyItems)) {
            currencySources = collect(ExchangeProvider::currencyItems);
            currencyItems = merge(currencySources);
        }
        return currencyItems;
    }

    public static synchronized List<DebtItem> debtItems() {
        if (changed(debtSources, ExchangeProvider::debtItems)) {
            debtSources = collect(ExchangeProvider::debtItems);
            debtItems = merge(debtSources);
        }
        return debtItems;
    }

    /** The currency item whose family contains the stack, in either direction. */
    @Nullable
    public static CurrencyItem findCurrency(ItemStack stack) {
        for (CurrencyItem item : currencyItems()) {
            if (item.items()
                .unitsPer(stack) > 0) return item;
        }
        return null;
    }

    /** The currency item whose family contains the stack, if it can be deposited. */
    @Nullable
    public static CurrencyItem findDeposit(ItemStack stack) {
        CurrencyItem item = findCurrency(stack);
        return item != null && item.canDeposit() ? item : null;
    }

    @Nullable
    public static DebtItem findDebt(ItemStack stack) {
        for (DebtItem item : debtItems()) {
            if (item.items()
                .unitsPer(stack) > 0) return item;
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

    private static <T> List<List<T>> collect(Function<ExchangeProvider, List<T>> getter) {
        List<List<T>> sources = new ArrayList<>(PROVIDERS.size());
        for (ExchangeProvider provider : PROVIDERS) sources.add(getter.apply(provider));
        return sources;
    }

    /** Whether any provider now returns a different list instance than the cached one; allocation free. */
    private static <T> boolean changed(List<List<T>> cached, Function<ExchangeProvider, List<T>> getter) {
        if (cached.size() != PROVIDERS.size()) return true;
        for (int i = 0; i < cached.size(); i++) {
            if (cached.get(i) != getter.apply(PROVIDERS.get(i))) return true;
        }
        return false;
    }

    private static <T> List<T> merge(List<List<T>> sources) {
        List<T> merged = new ArrayList<>();
        for (List<T> source : sources) merged.addAll(source);
        return Collections.unmodifiableList(merged);
    }
}
