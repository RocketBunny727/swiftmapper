package io.github.rocketbunny727.swiftmapper.config;

import io.github.rocketbunny727.swiftmapper.dialect.SqlDialect;
import io.github.rocketbunny727.swiftmapper.utils.logger.SwiftLogger;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class ConfigReader {
    private final Map<String, String> config = new HashMap<>();
    private final SwiftLogger log = SwiftLogger.getLogger(ConfigReader.class);

    public ConfigReader() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        if (loadYaml(classLoader, "application.yml")) return;
        if (loadYaml(classLoader, "application.yaml")) return;
        if (loadProperties(classLoader, "application.properties")) return;
    }

    private boolean loadYaml(ClassLoader classLoader, String filename) {
        try (InputStream input = classLoader.getResourceAsStream(filename)) {
            if (input != null) {
                Yaml yaml = new Yaml();
                Map<String, Object> yamlMap = yaml.load(input);
                if (yamlMap != null) {
                    flatten("", yamlMap);
                }
                log.info("Loaded configuration from {}", filename);
                return true;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load " + filename, e);
        }
        return false;
    }

    private boolean loadProperties(ClassLoader classLoader, String filename) {
        try (InputStream input = classLoader.getResourceAsStream(filename)) {
            if (input != null) {
                Properties properties = new Properties();
                properties.load(input);
                for (String key : properties.stringPropertyNames()) {
                    config.put(key, properties.getProperty(key));
                }
                log.info("Loaded configuration from {}", filename);
                return true;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load " + filename, e);
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void flatten(String prefix, Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map) {
                flatten(key, (Map<String, Object>) entry.getValue());
            } else if (entry.getValue() != null) {
                config.put(key, entry.getValue().toString());
            }
        }
    }

    public String getString(String key) {
        return getString(key, null);
    }

    public String getString(String key, String defaultValue) {
        String raw = config.getOrDefault(key, defaultValue);
        return resolvePlaceholders(raw);
    }

    /**
     * Resolves Spring-style placeholders: ${VAR:default}.
     * Resolution order: environment variable → system property → declared default.
     */
    private String resolvePlaceholders(String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        StringBuilder result = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            int start = value.indexOf("${", i);
            if (start == -1) {
                result.append(value, i, value.length());
                break;
            }
            result.append(value, i, start);
            int end = value.indexOf('}', start);
            if (end == -1) {
                result.append(value, start, value.length());
                break;
            }
            String placeholder = value.substring(start + 2, end);
            int colonIdx = placeholder.indexOf(':');
            String varName  = colonIdx >= 0 ? placeholder.substring(0, colonIdx)  : placeholder;
            String fallback = colonIdx >= 0 ? placeholder.substring(colonIdx + 1) : null;

            String resolved = System.getenv(varName);
            if (resolved == null) resolved = System.getProperty(varName);
            if (resolved == null) resolved = fallback;
            if (resolved == null) {
                resolved = "${".concat(placeholder).concat("}");
                log.warn("Unresolvable placeholder '{}' — no env var, system property, or default value", varName);
            }
            result.append(resolved);
            i = end + 1;
        }
        return result.toString();
    }

    public int getInt(String key, int defaultValue) {
        String value = getString(key, null);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                log.warn("Invalid integer value for {}: {}", key, value);
            }
        }
        return defaultValue;
    }

    public long getLong(String key, long defaultValue) {
        String value = getString(key, null);
        if (value != null) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                log.warn("Invalid long value for {}: {}", key, value);
            }
        }
        return defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = getString(key, null);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return defaultValue;
    }

    public DatasourceConfig getDatasourceConfig() {
        if (config.isEmpty()) {
            throw new IllegalStateException("Configuration file (application.yml or application.properties) not found.");
        }
        String url = getString("swiftmapper.datasource.url");
        String configuredDriver = getString("swiftmapper.datasource.driver-class-name", null);
        String driverClassName = configuredDriver != null && !configuredDriver.isBlank()
                ? configuredDriver
                : SqlDialect.inferDriverClassName(url).orElse(null);

        return new DatasourceConfig(
                url,
                getString("swiftmapper.datasource.username"),
                getString("swiftmapper.datasource.password"),
                driverClassName,
                getString("swiftmapper.migrations.location", "db/migrations"),
                getString("swiftmapper.datasource.ddl-auto", "update"),
                getString("swiftmapper.datasource.dialect", null)
        );
    }

    public LoggingConfig getLoggingConfig() {
        String rawLevel = getString("swiftmapper.logging.level", "INFO");
        String level = normalizeLoglevel(rawLevel);
        return new LoggingConfig(
                level,
                getBoolean("swiftmapper.logging.sql", true),
                getBoolean("swiftmapper.logging.transactions", true),
                getLong("swiftmapper.logging.slow-query-threshold", 1000)
        );
    }

    private static String normalizeLoglevel(String raw) {
        if (raw == null) return "INFO";
        return switch (raw.toLowerCase()) {
            case "false", "off"  -> "OFF";
            case "true"          -> "INFO";
            case "trace"         -> "TRACE";
            case "debug"         -> "DEBUG";
            case "info"          -> "INFO";
            case "warn"          -> "WARN";
            case "error"         -> "ERROR";
            default              -> raw.toUpperCase();
        };
    }

    public CacheConfig getCacheConfig() {
        return new CacheConfig(
                getBoolean("swiftmapper.cache.enabled", true),
                getLong("swiftmapper.cache.max-size", 1000),
                getLong("swiftmapper.cache.expire-minutes", 10),
                getString("swiftmapper.cache.provider-class", null)
        );
    }

    public PoolConfig getPoolConfig() {
        return new PoolConfig(
                getInt("swiftmapper.pool.max-size", 10),
                getInt("swiftmapper.pool.min-idle", 5),
                getLong("swiftmapper.pool.connection-timeout", 30000),
                getLong("swiftmapper.pool.idle-timeout", 600000),
                getLong("swiftmapper.pool.max-lifetime", 1800000),
                getLong("swiftmapper.pool.leak-detection-threshold", 60000)
        );
    }

    public record LoggingConfig(String level, boolean logSql, boolean logTransactions, long slowQueryThreshold) {}
    public record CacheConfig(boolean enabled, long maxSize, long expireMinutes, String providerClass) {}
    public record PoolConfig(int maxSize, int minIdle, long connectionTimeout, long idleTimeout,
                             long maxLifetime, long leakDetectionThreshold) {}
}
