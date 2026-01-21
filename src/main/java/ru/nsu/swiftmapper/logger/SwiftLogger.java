package ru.nsu.swiftmapper.logger;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class SwiftLogger {
    private final String name;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String BLUE = "\u001B[34m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String CYAN = "\u001B[36m";

    private SwiftLogger(String name) {
        this.name = name;
    }

    public static SwiftLogger getLogger(Class<?> clazz) {
        return new SwiftLogger(clazz.getSimpleName());
    }

    public static SwiftLogger getLogger(String name) {
        return new SwiftLogger(name);
    }

    private String formatTime() {
        return LocalTime.now().format(TIME_FORMAT);
    }

    public void trace(String msg, Object ... varargs) {
        System.out.printf("[%s] %s %sTRACE%s %s.%s - %s%n",
                formatTime(), CYAN, RESET, RESET, BLUE, name, formatMessage(msg, varargs));
    }

    public void debug(String msg, Object ... varargs) {
        System.out.printf("[%s] %sDEBUG%s %s.%s - %s%n",
                formatTime(), CYAN, RESET, BLUE, name, formatMessage(msg, varargs));
    }

    public void info(String msg, Object ... varargs) {
        System.out.printf("[%s] %sINFO %s%s.%s - %s%n",
                formatTime(), GREEN, BLUE, name, RESET, formatMessage(msg, varargs));
    }

    public void warn(String msg, Object ... varargs) {
        System.err.printf("[%s] %sWARN %s%s.%s - %s%s%n",
                formatTime(), YELLOW, BLUE, name, RESET, formatMessage(msg, varargs), RESET);
    }

    public void errorH(String msg, Object ... varargs) {
        System.err.printf("[%s] %sERROR%s %s%s.%s - %s%n",
                formatTime(), RED, RESET, RED, name, RESET, formatMessage(msg, varargs));
    }

    public void error(String msg, Throwable t, Object ... varargs) {
        errorH(formatMessage(msg, varargs));
        t.printStackTrace();
    }
    private static String formatMessage(String base, Object[] varargs) {
        if (base == null) return null;
        if (varargs == null || varargs.length == 0) return base;

        StringBuilder sb = new StringBuilder();
        int lastIndex = 0;

        for (Object arg : varargs) {
            int placeholderIndex = base.indexOf("{}", lastIndex);

            if (placeholderIndex == -1) {
                break;
            }

            sb.append(base, lastIndex, placeholderIndex);

            sb.append(arg != null ? arg : "null");

            lastIndex = placeholderIndex + 2;
        }

        sb.append(base.substring(lastIndex));

        return sb.toString();
    }
}
