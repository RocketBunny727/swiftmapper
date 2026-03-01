package com.rocketbunny.swiftmapper.cache;

import com.rocketbunny.swiftmapper.utils.logger.SwiftLogger;

import java.lang.ref.Cleaner;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StatementCache {
    private final Map<ConnectionKey, Map<String, PreparedStatement>> cache = new ConcurrentHashMap<>();
    private final SwiftLogger log = SwiftLogger.getLogger(StatementCache.class);
    private final int maxStatementsPerConnection;
    private final Cleaner cleaner = Cleaner.create();

    public StatementCache(int maxStatementsPerConnection) {
        this.maxStatementsPerConnection = maxStatementsPerConnection;
    }

    public PreparedStatement getStatement(Connection connection, String sql) throws SQLException {
        cleanupStaleEntries();

        ConnectionKey key = findOrCreateKey(connection);

        Map<String, PreparedStatement> connCache = cache.computeIfAbsent(key, k -> new ConcurrentHashMap<>());

        PreparedStatement stmt = connCache.get(sql);

        if (stmt != null) {
            try {
                if (!stmt.isClosed() && stmt.getConnection() == connection) {
                    log.debug("Cache hit for SQL: {}", sql);
                    return stmt;
                }
            } catch (SQLException e) {
                log.warn("Stale statement in cache, removing");
            }
            connCache.remove(sql);
        }

        if (connCache.size() >= maxStatementsPerConnection) {
            evictOldestStatement(connCache);
        }

        stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        connCache.put(sql, stmt);
        log.debug("Cached new statement for SQL: {}", sql);
        return stmt;
    }

    private ConnectionKey findOrCreateKey(Connection connection) {
        for (ConnectionKey key : cache.keySet()) {
            if (key.connection == connection) {
                return key;
            }
        }
        ConnectionKey newKey = new ConnectionKey(connection);
        cache.put(newKey, new ConcurrentHashMap<>());
        return newKey;
    }

    private void evictOldestStatement(Map<String, PreparedStatement> connCache) {
        String oldestKey = connCache.keySet().iterator().next();
        PreparedStatement oldestStmt = connCache.remove(oldestKey);
        try {
            if (oldestStmt != null) oldestStmt.close();
        } catch (SQLException e) {
            log.warn("Failed to close old statement", e);
        }
    }

    private void cleanupStaleEntries() {
        cache.entrySet().removeIf(entry -> {
            ConnectionKey key = entry.getKey();
            if (key.connection == null || isClosed(key.connection)) {
                closeAllStatements(entry.getValue());
                return true;
            }
            return false;
        });
    }

    private boolean isClosed(Connection connection) {
        try {
            return connection.isClosed();
        } catch (SQLException e) {
            return true;
        }
    }

    private void closeAllStatements(Map<String, PreparedStatement> statements) {
        for (PreparedStatement stmt : statements.values()) {
            try {
                stmt.close();
            } catch (SQLException e) {
                log.warn("Failed to close statement during cleanup", e);
            }
        }
    }

    public void clearForConnection(Connection connection) {
        cache.entrySet().removeIf(entry -> {
            if (entry.getKey().connection == connection) {
                closeAllStatements(entry.getValue());
                return true;
            }
            return false;
        });
    }

    public void clear() {
        for (Map<String, PreparedStatement> connCache : cache.values()) {
            closeAllStatements(connCache);
        }
        cache.clear();
    }

    private static class ConnectionKey {
        final Connection connection;

        ConnectionKey(Connection connection) {
            this.connection = connection;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ConnectionKey that = (ConnectionKey) o;
            return connection == that.connection;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(connection);
        }
    }
}