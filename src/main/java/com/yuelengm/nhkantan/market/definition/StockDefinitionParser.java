package com.yuelengm.nhkantan.market.definition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.yuelengm.nhkantan.market.LeverageRange;

/**
 * Parses and validates the root object of {@code stocks.json} against the already parsed modes.
 */
public class StockDefinitionParser {

    private static final Pattern STOCK_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private StockDefinitionParser() {}

    /**
     * @return stocks in file order
     */
    public static List<StockDefinition> parse(JsonObject root, Map<String, ModeDefinition> modes) {
        JsonArray array = JsonFields.requireArray(root, "stocks", "stocks.json");
        if (array.size() == 0) {
            throw new DefinitionException("stocks.json must define at least one stock");
        }
        List<StockDefinition> stocks = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            StockDefinition stock = parseStock(
                JsonFields.asObject(array.get(i), "stocks[" + i + "]"),
                "stocks[" + i + "]",
                modes);
            if (!ids.add(stock.id())) {
                throw new DefinitionException("Duplicate stock id '" + stock.id() + "'");
            }
            stocks.add(stock);
        }
        return stocks;
    }

    private static StockDefinition parseStock(JsonObject json, String location, Map<String, ModeDefinition> modes) {
        String id = JsonFields.requireString(json, "id", location);
        String where = "stock '" + id + "'";
        if (!STOCK_ID.matcher(id)
            .matches()) {
            // The id names the stock's candle history file, so it must be file-name safe
            throw new DefinitionException(where + " id may only contain letters, digits, '_' and '-'");
        }
        String name = JsonFields.optionalString(json, "name", where, id);
        boolean enabled = JsonFields.optionalBoolean(json, "enabled", where, true);
        double initialPrice = JsonFields.requireNumber(json, "initialPrice", where);
        double lowerBound = JsonFields.requireNumber(json, "lowerBound", where);
        double upperBound = JsonFields.requireNumber(json, "upperBound", where);
        if (!(lowerBound > 0.0D && lowerBound < initialPrice && initialPrice < upperBound)) {
            throw new DefinitionException(where + " must satisfy 0 < lowerBound < initialPrice < upperBound");
        }

        JsonArray modesJson = JsonFields.requireArray(json, "modes", where);
        if (modesJson.size() == 0) {
            throw new DefinitionException(where + " must reference at least one mode");
        }
        List<StockModeEntry> entries = new ArrayList<>();
        Set<String> used = new HashSet<>();
        double totalWeight = 0.0D;
        for (int i = 0; i < modesJson.size(); i++) {
            String entryWhere = where + ".modes[" + i + "]";
            JsonObject entry = JsonFields.asObject(modesJson.get(i), entryWhere);
            String modeId = JsonFields.requireString(entry, "modeId", entryWhere);
            ModeDefinition mode = modes.get(modeId);
            if (mode == null) {
                throw new DefinitionException(where + " references unknown mode '" + modeId + "'");
            }
            if (!used.add(modeId)) {
                throw new DefinitionException(where + " references mode '" + modeId + "' twice");
            }
            double weight = JsonFields.requireNumber(entry, "weight", entryWhere);
            if (weight < 0.0D) {
                throw new DefinitionException(entryWhere + ".weight must not be negative");
            }
            double switchProbability = JsonFields.requireNumber(entry, "switchProbability", entryWhere);
            if (switchProbability < 0.0D || switchProbability > 1.0D) {
                throw new DefinitionException(entryWhere + ".switchProbability must be between 0 and 1");
            }
            totalWeight += weight;
            entries.add(new StockModeEntry(mode, weight, switchProbability));
        }
        if (totalWeight <= 0.0D) {
            throw new DefinitionException(where + " mode weights must not all be 0");
        }
        LeverageRange leverage = parseLeverage(json, where);
        return new StockDefinition(id, name, enabled, initialPrice, lowerBound, upperBound, leverage, entries);
    }

    /** Optional {@code minLeverage} / {@code maxLeverage}; a missing end falls back to the trading config. */
    private static LeverageRange parseLeverage(JsonObject json, String where) {
        int min = JsonFields.optionalInt(json, "minLeverage", where, LeverageRange.UNSET);
        int max = JsonFields.optionalInt(json, "maxLeverage", where, LeverageRange.UNSET);
        LeverageRange range = new LeverageRange(min, max);
        if ((range.hasMin() && min < 1) || (range.hasMax() && max < 1)) {
            throw new DefinitionException(where + " leverage must be at least 1");
        }
        if (range.hasMin() && range.hasMax() && min > max) {
            throw new DefinitionException(where + " must satisfy minLeverage <= maxLeverage");
        }
        return range;
    }
}
