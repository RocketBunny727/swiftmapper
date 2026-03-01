package com.rocketbunny.swiftmapper.config;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class ConfigReader {
    private final Map<String, String> config = new HashMap<>();

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
        return config.getOrDefault(key, defaultValue);
    }

    public DatasourceConfig getDatasourceConfig() {
        if (config.isEmpty()) {
            throw new IllegalStateException("Configuration file (application.yml or application.properties) not found.");
        }
        return new DatasourceConfig(
                getString("swiftmapper.datasource.url"),
                getString("swiftmapper.datasource.username"),
                getString("swiftmapper.datasource.password"),
                getString("swiftmapper.datasource.driver-class-name", "org.h2.Driver"),
                getString("swiftmapper.migrations.location", "db/migrations")
        );
    }
}