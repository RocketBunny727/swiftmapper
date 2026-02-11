package ru.nsu.swiftmapper.utils.converters;

public final class CamelToSnakeConverter {
    private CamelToSnakeConverter() {
        // Utility class, only for static use
    }

    public static String convert(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        String result = input.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        result = result.replaceAll("([A-Z])([A-Z][a-z])", "$1_$2");

        return result.toLowerCase();
    }

    public static String convertClean(String input) {
        String result = convert(input);
        return result.startsWith("_") ? result.substring(1) : result;
    }
}