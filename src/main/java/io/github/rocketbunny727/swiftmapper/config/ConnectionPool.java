package io.github.rocketbunny727.swiftmapper.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public class ConnectionPool {
    private final HikariDataSource dataSource;

    public ConnectionPool(DatasourceConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.url());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setDriverClassName(config.driverClassName());

        hikariConfig.setMaximumPoolSize(getIntProperty("swiftmapper.pool.maxSize", 10));
        hikariConfig.setMinimumIdle(getIntProperty("swiftmapper.pool.minIdle", 5));
        hikariConfig.setConnectionTimeout(getLongProperty("swiftmapper.pool.connectionTimeout", 30000));
        hikariConfig.setIdleTimeout(getLongProperty("swiftmapper.pool.idleTimeout", 600000));
        hikariConfig.setMaxLifetime(getLongProperty("swiftmapper.pool.maxLifetime", 1800000));
        hikariConfig.setLeakDetectionThreshold(getLongProperty("swiftmapper.pool.leakDetectionThreshold", 60000));

        hikariConfig.setAutoCommit(false);

        this.dataSource = new HikariDataSource(hikariConfig);
    }

    private int getIntProperty(String key, int defaultValue) {
        String value = System.getProperty(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private long getLongProperty(String key, long defaultValue) {
        String value = System.getProperty(key);
        if (value != null) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}