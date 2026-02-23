package com.rocketbunny.swiftmapper.cache;

import com.rocketbunny.swiftmapper.utils.logger.SwiftLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StatementCache {
    private final Map<Connection, Map<String, PreparedStatement>> cache = new ConcurrentHashMap<>();
    private final SwiftLogger log = SwiftLogger.getLogger(StatementCache.class);
    private final int maxStatementsPerConnection;

    public StatementCache(int maxStatementsPerConnection) {
        this.maxStatementsPerConnection = maxStatementsPerConnection;
    }

    public PreparedStatement getStatement(Connection connection, String sql) throws SQLException {
        Map<String, PreparedStatement> connCache = cache.computeIfAbsent(connection, k -> new ConcurrentHashMap<>());

        PreparedStatement stmt = connCache.get(sql);

        if (stmt != null) {
            try {
                if (!stmt.isClosed()) {
                    log.debug("Cache hit for SQL: {}", sql);
                    return stmt;
                }
            } catch (SQLException e) {
                log.warn("Stale statement in cache, removing");
            }
            connCache.remove(sql);
        }

        if (connCache.size() >= maxStatementsPerConnection) {
            Iterator<Map.Entry<String, PreparedStatement>> it = connCache.entrySet().iterator();
            if (it.hasNext()) {
                Map.Entry<String, PreparedStatement> oldest = it.next();
                it.remove();
                try {
                    if (oldest.getValue() != null) oldest.getValue().close();
                } catch (SQLException e) {
                    log.warn("Failed to close old statement", e);
                }
            }
        }

        stmt = connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS);
        connCache.put(sql, stmt);
        log.debug("Cached new statement for SQL: {}", sql);
        return stmt;
    }

    public void clearForConnection(Connection connection) {
        Map<String, PreparedStatement> connCache = cache.remove(connection);
        if (connCache != null) {
            for (PreparedStatement stmt : connCache.values()) {
                try {
                    stmt.close();
                } catch (SQLException e) {
                    log.warn("Failed to close statement during cleanup", e);
                }
            }
        }
    }

    public void clear() {
        for (Connection conn : cache.keySet()) {
            clearForConnection(conn);
        }
    }
}