package io.github.devsha256.boomiprofilemigration.internal;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Utility to map Boomi primitive type names to XSD and JSON Schema types.
 * Functional, stateless helpers used by converters.
 */
public final class BoomiTypeMapper {

    private static final Map<String, String> XSD_MAP = Map.ofEntries(
            Map.entry("character", "xs:string"),
            Map.entry("string", "xs:string"),
            Map.entry("number", "xs:decimal"),
            Map.entry("decimal", "xs:decimal"),
            Map.entry("integer", "xs:integer"),
            Map.entry("date", "xs:date"),
            Map.entry("datetime", "xs:dateTime"),
            Map.entry("boolean", "xs:boolean")
    );

    private static final Set<String> NUMBER_TYPES = Set.of("number", "decimal", "integer");

    public static String toXsd(String boomiType) {
        if (boomiType == null) return "xs:string";
        return XSD_MAP.getOrDefault(boomiType.toLowerCase(Locale.ROOT), "xs:string");
    }

    public static String toJsonType(String boomiType) {
        if (boomiType == null) return "string";
        String lower = boomiType.toLowerCase(Locale.ROOT);
        if ("boolean".equals(lower)) return "boolean";
        if (NUMBER_TYPES.contains(lower)) return "number";
        return "string";
    }
}
