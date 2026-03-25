package io.github.rocketbunny727.swiftmapper.annotations.entity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ValueSpec {
    private static final Pattern PATTERN_REGEX = Pattern.compile("^(.*?)(\\d+)$");
    private final String prefix;
    private final int width;
    private final long sequenceStart;
    private final long formattingOffset;
    private final boolean formatted;

    private ValueSpec(String prefix, int width, long sequenceStart, long formattingOffset, boolean formatted) {
        this.prefix = prefix;
        this.width = width;
        this.sequenceStart = sequenceStart;
        this.formattingOffset = formattingOffset;
        this.formatted = formatted;
    }

    public static ValueSpec parse(String value, Strategy strategy) {
        if (value == null || value.trim().isEmpty()) {
            return new ValueSpec("", 0, 1L, 0L, false);
        }
        String trimmed = value.trim();
        if (strategy == Strategy.PATTERN) {
            Matcher m = PATTERN_REGEX.matcher(trimmed);
            if (m.matches()) {
                long templateNum = Long.parseLong(m.group(2));
                int digitWidth   = m.group(2).length();


                long dbStart = templateNum == 0 ? 1L : templateNum;
                long offset  = templateNum == 0 ? 1L : 0L;

                return new ValueSpec(m.group(1), digitWidth, dbStart, offset, true);
            } else {
                return new ValueSpec(trimmed, 0, 1L, 0L, true);
            }
        }
        try {
            long start = Long.parseLong(trimmed);
            if (start < 1) {
                throw new IllegalArgumentException(
                        "Invalid 'value' for strategy " + strategy + ": '" + trimmed +
                                "'. Numeric start value must be >= 1.");
            }
            return new ValueSpec("", 0, start, 0L, false);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid 'value' attribute for strategy " + strategy + ": '" + trimmed +
                            "'. Expected a numeric string (e.g. \"1\") or use Strategy.PATTERN for prefixed IDs.", e);
        }
    }

    public String format(long counter) {
        long display = counter - formattingOffset;
        if (formatted) {
            return width > 0
                    ? prefix + String.format("%0" + width + "d", display)
                    : prefix + display;
        }
        return String.valueOf(display);
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