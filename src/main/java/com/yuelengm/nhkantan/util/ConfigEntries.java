package com.yuelengm.nhkantan.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.Item;

import com.yuelengm.nhkantan.NHKantan;

import cpw.mods.fml.common.registry.GameRegistry;

/**
 * Parsing of config string lists whose entries are {@code ;}-separated fields. Field parsers throw
 * {@link IllegalArgumentException} with a readable message; {@link #parseEntries} logs and skips such entries.
 */
public class ConfigEntries {

    private ConfigEntries() {}

    /** Turns the fields of one entry into a value. */
    @FunctionalInterface
    public interface EntryParser<T> {

        T parse(String[] fields);
    }

    /**
     * @param context describes the config option in log messages, e.g. {@code "exchange config"}
     * @return the successfully parsed entries in order; blank entries are skipped
     */
    public static <T> List<T> parseEntries(String[] source, int fieldCount, EntryParser<T> parser, String context) {
        if (source == null) return Collections.emptyList();
        List<T> result = new ArrayList<>();
        for (String raw : source) {
            String entry = raw.trim();
            if (entry.isEmpty()) continue;
            try {
                String[] fields = entry.split(";");
                if (fields.length != fieldCount) {
                    throw new IllegalArgumentException("expected " + fieldCount + " fields separated by ';'");
                }
                for (int i = 0; i < fields.length; i++) fields[i] = fields[i].trim();
                result.add(parser.parse(fields));
            } catch (IllegalArgumentException e) {
                NHKantan.LOG.warn("Ignoring {} entry '{}': {}", context, entry, e.getMessage());
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** An item written as {@code modid:itemName} with a separate metadata field. */
    public static ItemSpec parseItem(String id, String meta) {
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) {
            throw new IllegalArgumentException("item must be written as modid:itemName");
        }
        Item item = GameRegistry.findItem(id.substring(0, colon), id.substring(colon + 1));
        if (item == null) throw new IllegalArgumentException("unknown item " + id);
        return new ItemSpec(item, parseNonNegativeInt(meta, "meta"));
    }

    public static int parseNonNegativeInt(String text, String name) {
        int value;
        try {
            value = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " is not a whole number: " + text);
        }
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    /** A finite number. */
    public static double parseDouble(String text, String name) {
        try {
            double value = Double.parseDouble(text.trim());
            if (Double.isNaN(value) || Double.isInfinite(value)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " is not a number: " + text);
        }
    }

    public static double parsePositive(String text, String name) {
        double value = parseDouble(text, name);
        if (value <= 0.0D) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    /** A number in {@code [0, max]}. */
    public static double parseFraction(String text, String name, double max) {
        double value = parseDouble(text, name);
        if (value < 0.0D || value > max) throw new IllegalArgumentException(name + " is out of range");
        return value;
    }
}
