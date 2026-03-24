package io.github.rocketbunny727.swiftmapper.annotations.entity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ValueSpec {
    private static final java.util.regex.Pattern PATTERN_REGEX = java.util.regex.Pattern.compile("^(.*?)(\\d+)$");
    private final String prefix;
    private final int width;
    private final long sequenceStart;
    private final boolean formatted;

    private ValueSpec(String prefix, int width, long sequenceStart, boolean formatted) {
        this.prefix = prefix;
        this.width = width;
        this.sequenceStart = sequenceStart;
        this.formatted = formatted;
    }

    public static ValueSpec parse(String value, Strategy strategy) {
        if (value == null || value.trim().isEmpty()) {
            return new ValueSpec("", 0, 1L, false);
        }
        String trimmed = value.trim();
        if (strategy == Strategy.PATTERN) {
            java.util.regex.Matcher m = PATTERN_REGEX.matcher(trimmed);
            if (m.matches()) {
                return new ValueSpec(m.group(1), m.group(2).length(), Long.parseLong(m.group(2)), true);
            }
        }
        try {
            return new ValueSpec("", 0, Long.parseLong(trimmed), false);
        } catch (NumberFormatException e) {
            return new ValueSpec("", 0, 1L, false);
        }
    }

    public String format(long counter) {
        return formatted ? prefix + String.format("%0" + width + "d", counter) : String.valueOf(counter);
    }

    public boolean isFormatted() { return formatted; }
    public long sequenceStart() { return sequenceStart; }

    public static void validateStrategyAttributes(GeneratedValue gen) {
        if (gen == null) return;
        if (gen.strategy() == Strategy.IDENTITY && !gen.value().isEmpty()) {
            throw new IllegalArgumentException("IDENTITY strategy does not support 'value'");
        }
    }
}