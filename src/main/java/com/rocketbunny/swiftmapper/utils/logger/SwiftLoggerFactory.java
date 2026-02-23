package com.rocketbunny.swiftmapper.utils.logger;

public class SwiftLoggerFactory {
    public static SwiftLogger getLogger(Class<?> clazz) {
        return SwiftLogger.getLogger(clazz);
    }

    public static SwiftLogger getLogger(String name) {
        return SwiftLogger.getLogger(name);
    }
}
