package ru.nsu.swiftmapper.config;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

public class ConfigReader {
    private final Map<String, Object> config;

    public ConfigReader() {
        Yaml yaml = new Yaml();
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("application.yml")) {
            if (input == null) {
                throw new IllegalStateException("application.yml not found in resources");
            }
            config = yaml.load(input);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load application.yml", e);
        }
    }

    public String getString(String key) {
        return getString(key, null);
    }

    public String getString(String key, String defaultValue) {
        String[] parts = key.split("\\.");
        Object value = config;

        for (String part : parts) {
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) value;
                value = map.get(part);
                if (value == null) return defaultValue;
            } else {
                return defaultValue;
            }
        }
        return value != null ? value.toString() : defaultValue;
    }

    public DatasourceConfig getDatasourceConfig() {
        return new DatasourceConfig(
                getString("swiftmapper.datasource.url"),
                getString("swiftmapper.datasource.username"),
                getString("swiftmapper.datasource.password"),
                getString("swiftmapper.datasource.driver-class-name", "org.h2.Driver")
        );
    }

    public static class DatasourceConfig {
        public final String url, username, password, driverClassName;

        public DatasourceConfig(String url, String username, String password, String driverClassName) {
            this.url = url;
            this.username = username;
            this.password = password;
            this.driverClassName = driverClassName;
        }
    }
}
