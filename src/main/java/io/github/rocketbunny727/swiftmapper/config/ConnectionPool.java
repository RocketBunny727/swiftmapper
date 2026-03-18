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