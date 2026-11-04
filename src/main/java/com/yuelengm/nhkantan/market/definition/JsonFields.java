package com.yuelengm.nhkantan.market.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Typed accessors for JSON objects that report the offending location on failure.
 */
public class JsonFields {

    private JsonFields() {}

    public static JsonObject asObject(JsonElement element, String location) {
        if (element == null || !element.isJsonObject()) {
            throw new DefinitionException(location + " must be an object");
        }
        return element.getAsJsonObject();
    }

    public static JsonArray requireArray(JsonObject object, String key, String location) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonArray()) {
            throw new DefinitionException(location + "." + key + " must be an array");
        }
        return element.getAsJsonArray();
    }

    public static JsonArray optionalArray(JsonObject object, String key, String location) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return new JsonArray();
        if (!element.isJsonArray()) {
            throw new DefinitionException(location + "." + key + " must be an array");
        }
        return element.getAsJsonArray();
    }

    public static String requireString(JsonObject object, String key, String location) {
        String value = optionalString(object, key, location, null);
        if (value == null || value.trim()
            .isEmpty()) {
            throw new DefinitionException(location + "." + key + " must be a non-empty string");
        }
        return value.trim();
    }

    public static String optionalString(JsonObject object, String key, String location, String fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return fallback;
        if (!isPrimitive(element) || !element.getAsJsonPrimitive()
            .isString()) {
            throw new DefinitionException(location + "." + key + " must be a string");
        }
        return element.getAsString();
    }

    public static double requireNumber(JsonObject object, String key, String location) {
        JsonElement element = object.get(key);
        if (!isNumber(element)) {
            throw new DefinitionException(location + "." + key + " must be a number");
        }
        double value = element.getAsDouble();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new DefinitionException(location + "." + key + " must be finite");
        }
        return value;
    }

    public static int optionalInt(JsonObject object, String key, String location, int fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return fallback;
        if (!isNumber(element)) {
            throw new DefinitionException(location + "." + key + " must be a number");
        }
        double value = element.getAsDouble();
        if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new DefinitionException(location + "." + key + " must be a whole number");
        }
        return (int) value;
    }

    public static boolean optionalBoolean(JsonObject object, String key, String location, boolean fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return fallback;
        if (!isPrimitive(element) || !element.getAsJsonPrimitive()
            .isBoolean()) {
            throw new DefinitionException(location + "." + key + " must be a boolean");
        }
        return element.getAsBoolean();
    }

    public static boolean isNumber(JsonElement element) {
        return isPrimitive(element) && element.getAsJsonPrimitive()
            .isNumber();
    }

    private static boolean isPrimitive(JsonElement element) {
        return element != null && element.isJsonPrimitive();
    }
}
