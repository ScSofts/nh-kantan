package com.yuelengm.nhkantan.wallet;

import java.util.Collections;
import java.util.List;

import com.yuelengm.nhkantan.config.ExchangeConfig;
import com.yuelengm.nhkantan.util.ConfigEntries;
import com.yuelengm.nhkantan.util.Denominations;

/**
 * Items from {@link ExchangeConfig}. Entries are parsed lazily, after all items are registered, and re-parsed when
 * the config array is replaced (e.g. by server-to-client config sync). Invalid entries are logged and skipped.
 */
public class ConfigExchangeProvider implements ExchangeProvider {

    private static final String CONTEXT = "exchange config";

    private String[] currencySource;
    private List<CurrencyItem> currencyItems = Collections.emptyList();
    private String[] debtSource;
    private List<DebtItem> debtItems = Collections.emptyList();

    /**
     * Entries of {@code currencyItems}: {@code modid:itemName;meta;depositValue;depositFee;withdrawValue;withdrawFee}
     */
    @Override
    public synchronized List<CurrencyItem> currencyItems() {
        String[] source = ExchangeConfig.currencyItems;
        if (source != currencySource) {
            currencySource = source;
            currencyItems = ConfigEntries.parseEntries(source, 6, ConfigExchangeProvider::parseCurrency, CONTEXT);
        }
        return currencyItems;
    }

    /** Entries of {@code debtItems}: {@code modid:itemName;meta;value} */
    @Override
    public synchronized List<DebtItem> debtItems() {
        String[] source = ExchangeConfig.debtItems;
        if (source != debtSource) {
            debtSource = source;
            debtItems = ConfigEntries.parseEntries(source, 3, ConfigExchangeProvider::parseDebt, CONTEXT);
        }
        return debtItems;
    }

    private static CurrencyItem parseCurrency(String[] fields) {
        double depositValue = parseValue(fields[2], "depositValue");
        double withdrawValue = parseValue(fields[4], "withdrawValue");
        if (depositValue < 0.0D && withdrawValue < 0.0D) {
            throw new IllegalArgumentException("depositValue and withdrawValue are both disabled");
        }
        return new CurrencyItem(
            Denominations.single(ConfigEntries.parseItem(fields[0], fields[1])),
            depositValue,
            ConfigEntries.parseFraction(fields[3], "depositFee", 1.0D),
            withdrawValue,
            ConfigEntries.parseFraction(fields[5], "withdrawFee", Double.MAX_VALUE));
    }

    private static DebtItem parseDebt(String[] fields) {
        return new DebtItem(
            Denominations.single(ConfigEntries.parseItem(fields[0], fields[1])),
            ConfigEntries.parsePositive(fields[2], "value"));
    }

    /** A positive value, or a negative one meaning the direction is disabled; zero is rejected. */
    private static double parseValue(String text, String name) {
        double value = ConfigEntries.parseDouble(text, name);
        if (value == 0.0D) throw new IllegalArgumentException(name + " must not be 0; use a negative value to disable");
        return value;
    }
}
