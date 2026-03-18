package io.github.rocketbunny727.swiftmapper.utils.logger;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Set;

public class SwiftLogger {
    private final String name;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private static final String PID = getPid();

    private static boolean colorsEnabled = true;

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String BLUE = "\u001B[34m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String CYAN = "\u001B[36m";
    private static final String PURPLE = "\u001B[35m";

    private static final String BOLD   = "\u001B[1m";
    private static final String DIM    = "\u001B[2m";

    private static final Map<String, Integer> LEVEL_MAP = Map.of(
            "TRACE", 0,
            "DEBUG", 1,
            "INFO", 2,
            "WARN", 3,
            "ERROR", 4,
            "OFF", Integer.MAX_VALUE
    );

    private static final Set<String> SQL_HIGHLIGHT = Set.of(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES",
            "UPDATE", "SET", "DELETE", "JOIN", "LEFT", "RIGHT", "INNER",
            "ON", "AND", "OR", "NOT", "IN", "IS", "NULL", "ORDER", "BY",
            "GROUP", "HAVING", "LIMIT", "OFFSET", "AS", "DISTINCT",
            "COUNT", "SUM", "AVG", "MIN", "MAX", "BETWEEN", "LIKE",
            "EXISTS", "CREATE", "TABLE", "ALTER", "DROP", "IF"
    );

    private static volatile int currentLevel = 2;
    private static volatile boolean sqlLoggingEnabled = true;

    public static void setLevel(String level) {
        if (level != null) {
            Integer lvl = LEVEL_MAP.get(level.toUpperCase());
            if (lvl != null) currentLevel = lvl;
        }
    }

    public static void setSqlLogging(boolean enabled) {
        sqlLoggingEnabled = enabled;
    }

    private SwiftLogger(String name) {
        this.name = name;
    }

    public static SwiftLogger getLogger(Class<?> clazz) {
        return new SwiftLogger(clazz.getName());
    }

    public static SwiftLogger getLogger(String name) {
        return new SwiftLogger(name);
    }

    public static void enableColors(boolean enabled) {
        colorsEnabled = enabled;
    }

    private static String getPid() {
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            return name.split("@")[0];
        } catch (Exception e) {
            return "?????";
        }
    }

    private String formatTime() {
        return ZonedDateTime.now().format(TIME_FORMAT);
    }

    private String color(String text, String color) {
        return colorsEnabled ? color + text + RESET : text;
    }

    private void log(String level, String levelColor, String msg, Object... varargs) {
        Integer msgLevel = LEVEL_MAP.get(level);
        if (msgLevel == null || msgLevel < currentLevel) return;

        String threadName = Thread.currentThread().getName();
        String formattedMessage = formatMessage(msg, varargs);

        if (!level.equals("ERROR")) {
            String logLine = String.format("%s %s %s%s%s --- [%s] %s%s%s : %s",
                    formatTime(),
                    color(padLeft(level, 5), levelColor),
                    PURPLE,
                    PID,
                    RESET,
                    padLeft(threadName, 15),
                    CYAN,
                    padRight(formatClassName(40), 40),
                    RESET,
                    formattedMessage
            );
            System.out.println(logLine);
        } else {
            String logLine = String.format("%s %s %s --- [%s] %s : %s",
                    formatTime(),
                    color(padLeft(level, 5), levelColor),
                    PID,
                    padLeft(threadName, 15),
                    padRight(formatClassName(40), 40),
                    formattedMessage
            );

            System.err.println(logLine);
        }
    }

    public void trace(String msg, Object... varargs) {
        log("TRACE", BLUE, msg, varargs);
    }

    public void debug(String msg, Object... varargs) {
        log("DEBUG", GREEN, msg, varargs);
    }

    public void info(String msg, Object... varargs) {
        log("INFO", GREEN, msg, varargs);
    }

    public void warn(String msg, Object... varargs) {
        log("WARN", YELLOW, msg, varargs);
    }

    public void error(String msg, Object... varargs) {
        log("ERROR", RED, msg, varargs);
    }

    public void error(String msg, Throwable t, Object... varargs) {
        error(msg, varargs);
        if (t != null) {
            t.printStackTrace();
        }
    }

    public void showSQL(String sql, Object... params) {
        if (!sqlLoggingEnabled) return;

        String threadName = Thread.currentThread().getName();
        String border = colorsEnabled ? (CYAN + "┌─ SQL " + "─".repeat(60) + RESET) : ("┌─ SQL " + "─".repeat(60));

        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(border).append("\n");

        String formatted = formatSql(sql);
        for (String line : formatted.split("\n")) {
            sb.append(colorsEnabled ? (CYAN + "│ " + RESET) : "│ ")
                    .append(line).append("\n");
        }

        if (params != null && params.length > 0) {
            sb.append(colorsEnabled ? (CYAN + "├─ Params " + "─".repeat(57) + RESET) : ("├─ Params " + "─".repeat(57))).append("\n");
            StringBuilder paramLine = new StringBuilder();
            for (int i = 0; i < params.length; i++) {
                Object p = params[i];
                String formatted_p = formatParam(p);
                paramLine.append("[").append(i + 1).append("] ").append(formatted_p);
                if (i < params.length - 1) paramLine.append("  ");
            }
            sb.append(colorsEnabled ? (CYAN + "│ " + RESET) : "│ ")
                    .append(paramLine).append("\n");
        }

        String bottom = colorsEnabled ? (CYAN + "└" + "─".repeat(65) + RESET) : ("└" + "─".repeat(65));
        sb.append(bottom);

        String header = String.format("%s %s %s%s%s --- [%s] %s%s%s :",
                formatTime(),
                color(padLeft("SQL", 5), BLUE),
                PURPLE, PID, RESET,
                padLeft(threadName, 15),
                CYAN, padRight(formatClassName(40), 40), RESET
        );
        System.out.println(header + sb);
    }

    private String formatSql(String sql) {
        if (sql == null) return "";
        String result = sql.trim()
                .replaceAll("(?i)\\bFROM\\b",       "\nFROM")
                .replaceAll("(?i)\\bWHERE\\b",      "\nWHERE")
                .replaceAll("(?i)\\bAND\\b",        "\n  AND")
                .replaceAll("(?i)\\bOR\\b",         "\n  OR")
                .replaceAll("(?i)\\bLEFT JOIN\\b",  "\nLEFT JOIN")
                .replaceAll("(?i)\\bINNER JOIN\\b", "\nINNER JOIN")
                .replaceAll("(?i)\\bORDER BY\\b",   "\nORDER BY")
                .replaceAll("(?i)\\bGROUP BY\\b",   "\nGROUP BY")
                .replaceAll("(?i)\\bHAVING\\b",     "\nHAVING")
                .replaceAll("(?i)\\bLIMIT\\b",      "\nLIMIT")
                .replaceAll("(?i)\\bSET\\b",        "\nSET");

        if (!colorsEnabled) return result;

        for (String keyword : SQL_HIGHLIGHT) {
            result = result.replaceAll(
                    "(?i)\\b" + keyword + "\\b",
                    BOLD + YELLOW + keyword + RESET
            );
        }
        return result;
    }

    private String formatParam(Object p) {
        if (p == null) return colorsEnabled ? (RED + "NULL" + RESET) : "NULL";
        if (p instanceof String) return colorsEnabled ? (GREEN + "'" + p + "'" + RESET) : "'" + p + "'";
        if (p instanceof Number) return colorsEnabled ? (CYAN + p + RESET) : String.valueOf(p);
        if (p instanceof Boolean) return colorsEnabled ? (PURPLE + p + RESET) : String.valueOf(p);
        return colorsEnabled ? (DIM + p + RESET) : String.valueOf(p);
    }

    private static String padLeft(String s, int n) {
        if (s.length() >= n) {
            return s.substring(0, n);
        }
        return " ".repeat(n - s.length()) + s;
    }

    private static String padRight(String s, int n) {
        if (s.length() >= n) {
            return s.substring(0, n);
        }
        return String.format("%-" + n + "s", s);
    }

    private String formatClassName(int n) {
        if (name == null || name.length() <= n) {
            return name;
        }

        String[] parts = name.split("\\.");
        String className = parts[parts.length - 1];

        if (className.length() >= n) {
            return className;
        }

        for (int i = 0; i < parts.length - 1; i++) {
            parts[i] = String.valueOf(parts[i].charAt(0));

            String currentJoin = String.join(".", parts);
            if (currentJoin.length() <= n) {
                return currentJoin;
            }
        }

        String[] shortParts = parts;
        while (String.join(".", shortParts).length() > n && shortParts.length > 1) {
            String[] nextIteration = new String[shortParts.length - 1];
            System.arraycopy(shortParts, 1, nextIteration, 0, nextIteration.length);
            shortParts = nextIteration;
        }

        return String.join(".", shortParts);
    }

    private static String formatMessage(String base, Object[] varargs) {
        if (base == null) return "";
        if (varargs == null || varargs.length == 0) return base;

        StringBuilder sb = new StringBuilder();
        int lastIndex = 0;

        for (Object arg : varargs) {
            int placeholderIndex = base.indexOf("{}", lastIndex);
            if (placeholderIndex == -1) break;

            sb.append(base, lastIndex, placeholderIndex);
            sb.append(arg != null ? arg : "null");
            lastIndex = placeholderIndex + 2;
        }

        sb.append(base.substring(lastIndex));
        return sb.toString();
    }
}