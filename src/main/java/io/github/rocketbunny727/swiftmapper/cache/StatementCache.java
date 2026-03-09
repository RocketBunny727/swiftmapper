package io.github.rocketbunny727.swiftmapper.cache;

import io.github.rocketbunny727.swiftmapper.utils.logger.SwiftLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class StatementCache {
    private final Map<ConnectionKey, Map<String, PreparedStatement>> cache = new ConcurrentHashMap<>();
    private final SwiftLogger log = SwiftLogger.getLogger(StatementCache.class);
    private final int maxStatementsPerConnection;
    private final ReentrantReadWriteLock globalLock = new ReentrantReadWriteLock();

    public StatementCache(int maxStatementsPerConnection) {
        this.maxStatementsPerConnection = maxStatementsPerConnection;
    }

    public PreparedStatement getStatement(Connection connection, String sql) throws SQLException {
        globalLock.readLock().lock();
        try {
            ConnectionKey key = findOrCreateKey(connection);

            Map<String, PreparedStatement> connCache = cache.computeIfAbsent(key, k -> new ConcurrentHashMap<>());

            synchronized (connCache) {
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
        } finally {
            globalLock.readLock().unlock();
        }
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

    public void cleanupStaleEntries() {
        globalLock.writeLock().lock();
        try {
            cache.entrySet().removeIf(entry -> {
                ConnectionKey key = entry.getKey();
                if (key.connection == null || isClosed(key.connection)) {
                    closeAllStatements(entry.getValue());
                    return true;
                }
                return false;
            });
        } finally {
            globalLock.writeLock().unlock();
        }
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
        globalLock.writeLock().lock();
        try {
            cache.entrySet().removeIf(entry -> {
                if (entry.getKey().connection == connection) {
                    closeAllStatements(entry.getValue());
                    return true;
                }
                return false;
            });
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    public void clear() {
        globalLock.writeLock().lock();
        try {
            for (Map<String, PreparedStatement> connCache : cache.values()) {
                closeAllStatements(connCache);
            }
            cache.clear();
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    private record ConnectionKey(Connection connection) {

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