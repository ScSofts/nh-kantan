package com.yuelengm.nhkantan.wallet;

import java.util.List;

/**
 * A source of exchangeable and debt items, e.g. the exchange config or a mod compatibility module.
 * <p>
 * Implementations return cached, unmodifiable lists and return the same list instances until their configuration
 * changes; {@link ExchangeItems} relies on that to rebuild its merged view only when needed.
 */
public interface ExchangeProvider {

    List<CurrencyItem> currencyItems();

    List<DebtItem> debtItems();
}
