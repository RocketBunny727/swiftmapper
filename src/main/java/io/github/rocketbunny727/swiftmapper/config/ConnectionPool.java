package io.github.rocketbunny727.swiftmapper.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public class ConnectionPool {
    private final HikariDataSource dataSource;

    public ConnectionPool(DatasourceConfig config, ConfigReader.PoolConfig poolConfig) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.url());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setDriverClassName(config.driverClassName());

        hikariConfig.setMaximumPoolSize(poolConfig.maxSize());
        hikariConfig.setMinimumIdle(poolConfig.minIdle());
        hikariConfig.setConnectionTimeout(poolConfig.connectionTimeout());
        hikariConfig.setIdleTimeout(poolConfig.idleTimeout());
        hikariConfig.setMaxLifetime(poolConfig.maxLifetime());
        hikariConfig.setLeakDetectionThreshold(poolConfig.leakDetectionThreshold());

        hikariConfig.setAutoCommit(false);
        this.dataSource = new HikariDataSource(hikariConfig);
        validateConnection(config);
    }

    private void validateConnection(DatasourceConfig config) {
        try (Connection conn = dataSource.getConnection()) {
            if (!conn.isValid(5)) {
                throw new IllegalStateException(
                        "Database connection is not valid (isValid returned false). URL: " + config.url());
            }
        } catch (SQLException e) {
            dataSource.close();
            throw new IllegalStateException(
                    "Failed to establish database connection. " +
                            "Please check that the database is running and the credentials are correct." +
                    "  URL:  " + config.url() +
                    "  User: " + config.username() +
                    "  Cause: " + e.getMessage(), e);
        }
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