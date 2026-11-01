package com.yuelengm.nhkantan.wallet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.Item;

import com.yuelengm.nhkantan.NHKantan;
import com.yuelengm.nhkantan.config.ExchangeConfig;

import cpw.mods.fml.common.registry.GameRegistry;

/**
 * Parsed view of the exchange config. Entries are parsed lazily, after all items are registered, and re-parsed when
 * the config array is replaced (e.g. by server-to-client config sync). Invalid entries are logged and skipped.
 */
public class ExchangeItems {

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
            currencyItems = parseAll(source, 6, ExchangeItems::parseCurrency);
        }
        return currencyItems;
    }

    /** Entries of {@code debtItems}: {@code modid:itemName;meta;value} */
    public static synchronized List<DebtItem> debtItems() {
        String[] source = ExchangeConfig.debtItems;
        if (source != debtSource) {
            debtSource = source;
            debtItems = parseAll(source, 3, ExchangeItems::parseDebt);
        }
        return debtItems;
    }

    private interface EntryParser<T> {

        T parse(ItemSpec item, String[] parts);
    }

    private static <T> List<T> parseAll(String[] source, int fieldCount, EntryParser<T> parser) {
        if (source == null) return Collections.emptyList();
        List<T> result = new ArrayList<>();
        for (String raw : source) {
            String entry = raw.trim();
            if (entry.isEmpty()) continue;
            try {
                String[] parts = entry.split(";");
                if (parts.length != fieldCount) {
                    throw new IllegalArgumentException("expected " + fieldCount + " fields separated by ';'");
                }
                result.add(parser.parse(parseItem(parts[0].trim(), parts[1].trim()), parts));
            } catch (IllegalArgumentException e) {
                NHKantan.LOG.warn("Ignoring exchange config entry '{}': {}", entry, e.getMessage());
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static ItemSpec parseItem(String id, String meta) {
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) {
            throw new IllegalArgumentException("item must be written as modid:itemName");
        }
        Item item = GameRegistry.findItem(id.substring(0, colon), id.substring(colon + 1));
        if (item == null) throw new IllegalArgumentException("unknown item " + id);
        return new ItemSpec(item, parseMeta(meta));
    }

    private static CurrencyItem parseCurrency(ItemSpec item, String[] parts) {
        double depositValue = parseValue(parts[2], "depositValue");
        double depositFee = parseFraction(parts[3], "depositFee", 1.0D);
        double withdrawValue = parseValue(parts[4], "withdrawValue");
        double withdrawFee = parseFraction(parts[5], "withdrawFee", Double.MAX_VALUE);
        if (depositValue < 0.0D && withdrawValue < 0.0D) {
            throw new IllegalArgumentException("depositValue and withdrawValue are both disabled");
        }
        return new CurrencyItem(item, depositValue, depositFee, withdrawValue, withdrawFee);
    }

    private static DebtItem parseDebt(ItemSpec item, String[] parts) {
        return new DebtItem(item, parsePositive(parts[2], "value"));
    }

    private static int parseMeta(String text) {
        try {
            int value = Integer.parseInt(text);
            if (value < 0) throw new IllegalArgumentException("meta must not be negative");
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("meta is not a whole number: " + text);
        }
    }

    /** A positive value, or a negative one meaning the direction is disabled; zero is rejected. */
    private static double parseValue(String text, String name) {
        double value = parseDouble(text, name);
        if (value == 0.0D) throw new IllegalArgumentException(name + " must not be 0; use a negative value to disable");
        return value;
    }

    private static double parsePositive(String text, String name) {
        double value = parseDouble(text, name);
        if (value <= 0.0D) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static double parseFraction(String text, String name, double max) {
        double value = parseDouble(text, name);
        if (value < 0.0D || value > max) throw new IllegalArgumentException(name + " is out of range");
        return value;
    }

    private static double parseDouble(String text, String name) {
        try {
            double value = Double.parseDouble(text.trim());
            if (Double.isNaN(value) || Double.isInfinite(value)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " is not a number: " + text);
        }
    }
}
