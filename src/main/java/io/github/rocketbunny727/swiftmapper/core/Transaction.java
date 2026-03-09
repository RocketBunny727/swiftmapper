package io.github.rocketbunny727.swiftmapper.core;

import io.github.rocketbunny727.swiftmapper.exception.TransactionException;
import io.github.rocketbunny727.swiftmapper.utils.logger.SwiftLogger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public class Transaction {
    private final ConnectionManager connectionManager;
    private final SwiftLogger log = SwiftLogger.getLogger(Transaction.class);
    private final Deque<SavepointInfo> savepointStack = new ArrayDeque<>();
    private final AtomicInteger savepointCounter = new AtomicInteger(0);

    private Connection currentConnection;
    private boolean active = false;
    private int isolationLevel = Connection.TRANSACTION_READ_COMMITTED;
    private boolean readOnly = false;

    public Transaction(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public void begin() throws SQLException {
        if (active) {
            throw new TransactionException("Transaction already active. Use nestedSavepoint() for nested transactions.");
        }

        currentConnection = connectionManager.getConnection();

        if (currentConnection.getAutoCommit()) {
            currentConnection.setAutoCommit(false);
        }

        if (currentConnection.getTransactionIsolation() != isolationLevel) {
            currentConnection.setTransactionIsolation(isolationLevel);
        }

        currentConnection.setReadOnly(readOnly);

        active = true;
        log.debug("Transaction started with isolation level {}", getIsolationLevelName(isolationLevel));
    }

    public Savepoint nestedSavepoint() throws SQLException {
        if (!active) {
            throw new TransactionException("No active transaction. Call begin() first.");
        }

        String savepointName = "SP_" + savepointCounter.incrementAndGet();
        Savepoint savepoint = currentConnection.setSavepoint(savepointName);

        SavepointInfo info = new SavepointInfo(savepointName, savepoint);
        savepointStack.push(info);

        log.debug("Nested savepoint created: {}", savepointName);
        return savepoint;
    }

    public void commit() throws SQLException {
        if (!active) {
            throw new TransactionException("No active transaction");
        }

        if (!savepointStack.isEmpty()) {
            throw new TransactionException("Cannot commit with active savepoints. Release or rollback savepoints first.");
        }

        try {
            currentConnection.commit();
            log.debug("Transaction committed");
        } finally {
            cleanup();
        }
    }

    public void commitToSavepoint() throws SQLException {
        if (!active) {
            throw new TransactionException("No active transaction");
        }

        if (savepointStack.isEmpty()) {
            throw new TransactionException("No savepoint to commit to");
        }

        SavepointInfo released = savepointStack.pop();
        currentConnection.releaseSavepoint(released.savepoint());
        log.debug("Savepoint released: {}", released.name());
    }

    public void rollback() throws SQLException {
        if (!active) {
            throw new TransactionException("No active transaction");
        }

        try {
            if (!savepointStack.isEmpty()) {
                savepointStack.clear();
            }

            currentConnection.rollback();
            log.debug("Transaction rolled back completely");
        } finally {
            cleanup();
        }
    }

    public void rollbackToSavepoint() throws SQLException {
        rollbackToSavepoint(null);
    }

    public void rollbackToSavepoint(Savepoint savepoint) throws SQLException {
        if (!active) {
            throw new TransactionException("No active transaction");
        }

        if (savepoint == null && savepointStack.isEmpty()) {
            throw new TransactionException("No savepoint specified and no active savepoints");
        }

        Savepoint targetSavepoint = savepoint;

        if (targetSavepoint == null) {
            SavepointInfo info = savepointStack.peek();
            targetSavepoint = info.savepoint();
        }

        currentConnection.rollback(targetSavepoint);

        while (!savepointStack.isEmpty() && !savepointStack.peek().savepoint().equals(targetSavepoint)) {
            savepointStack.pop();
        }

        log.debug("Rolled back to savepoint");
    }

    private void cleanup() throws SQLException {
        if (currentConnection != null) {
            try {
                if (!currentConnection.isClosed()) {
                    currentConnection.setAutoCommit(true);
                    currentConnection.setReadOnly(false);
                }
            } catch (SQLException e) {
                log.warn("Failed to reset connection state", e);
            } finally {
                try {
                    currentConnection.close();
                } catch (SQLException e) {
                    log.error("Failed to close connection", e);
                }
                currentConnection = null;
            }
        }

        savepointStack.clear();
        savepointCounter.set(0);
        active = false;
        readOnly = false;
    }

    public <T> T execute(Function<Connection, T> operation) {
        return executeWithIsolation(operation, isolationLevel);
    }

    public <T> T executeWithIsolation(Function<Connection, T> operation, int isolationLevel) {
        int previousIsolation = this.isolationLevel;
        this.isolationLevel = isolationLevel;

        try {
            begin();
            T result = operation.apply(currentConnection);
            commit();
            return result;
        } catch (Exception e) {
            try {
                if (active) {
                    rollback();
                }
            } catch (SQLException ex) {
                log.error("Failed to rollback transaction", ex);
            }
            throw new TransactionException("Transaction failed", e);
        } finally {
            this.isolationLevel = previousIsolation;
        }
    }

    public <T> T executeReadOnly(Function<Connection, T> operation) {
        this.readOnly = true;
        try {
            return execute(operation);
        } finally {
            this.readOnly = false;
        }
    }

    public void executeWithinSavepoint(Runnable operation) {
        Savepoint savepoint = null;
        try {
            savepoint = nestedSavepoint();
            operation.run();
            commitToSavepoint();
        } catch (Exception e) {
            try {
                if (savepoint != null) {
                    rollbackToSavepoint(savepoint);
                }
            } catch (SQLException ex) {
                log.error("Failed to rollback to savepoint", ex);
            }
            throw new TransactionException("Savepoint operation failed", e);
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

    public boolean hasSavepoints() {
        return !savepointStack.isEmpty();
    }

    public int getSavepointCount() {
        return savepointStack.size();
    }

    public void setIsolationLevel(int level) throws SQLException {
        if (active) {
            throw new TransactionException("Cannot change isolation level of active transaction");
        }
        this.isolationLevel = level;
    }

    private String getIsolationLevelName(int level) {
        return switch (level) {
            case Connection.TRANSACTION_READ_UNCOMMITTED -> "READ_UNCOMMITTED";
            case Connection.TRANSACTION_READ_COMMITTED -> "READ_COMMITTED";
            case Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE_READ";
            case Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE";
            default -> "UNKNOWN(" + level + ")";
        };
    }

    private record SavepointInfo(String name, Savepoint savepoint) {}
}