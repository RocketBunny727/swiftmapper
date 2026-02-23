package com.rocketbunny.swiftmapper.core;

import com.rocketbunny.swiftmapper.exception.TransactionException;
import com.rocketbunny.swiftmapper.utils.logger.SwiftLogger;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;

public class Transaction {
    private final ConnectionManager connectionManager;
    private final SwiftLogger log = SwiftLogger.getLogger(Transaction.class);
    private Connection currentConnection;
    private boolean active = false;

    public Transaction(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public void begin() throws SQLException {
        if (active) {
            throw new TransactionException("Transaction already active");
        }
        currentConnection = connectionManager.getConnection();
        currentConnection.setAutoCommit(false);
        active = true;
        log.debug("Transaction started");
    }

    public void commit() throws SQLException {
        if (!active) {
            throw new TransactionException("No active transaction");
        }
        try {
            currentConnection.commit();
            log.debug("Transaction committed");
        } finally {
            cleanup();
        }
    }

    public void rollback() throws SQLException {
        if (!active) {
            throw new TransactionException("No active transaction");
        }
        try {
            currentConnection.rollback();
            log.debug("Transaction rolled back");
        } finally {
            cleanup();
        }
    }

    private void cleanup() throws SQLException {
        if (currentConnection != null) {
            currentConnection.setAutoCommit(true);
            currentConnection.close();
            currentConnection = null;
        }
        active = false;
    }

    public <T> T execute(Function<Connection, T> operation) {
        try {
            begin();
            T result = operation.apply(currentConnection);
            commit();
            return result;
        } catch (Exception e) {
            try {
                rollback();
            } catch (SQLException ex) {
                log.error("Failed to rollback transaction", ex);
            }
            throw new TransactionException("Transaction failed", e);
        }
    }

    public Connection getConnection() {
        if (!active) {
            throw new TransactionException("No active transaction");
        }
        return currentConnection;
    }

    public boolean isActive() {
        return active;
    }
}