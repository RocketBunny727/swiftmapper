package com.rocketbunny.swiftmapper.core;

import com.rocketbunny.swiftmapper.exception.TransactionException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;
import java.util.function.Function;

public class TransactionTemplate {
    private final ConnectionManager connectionManager;
    private int defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED;
    private boolean defaultReadOnly = false;

    public TransactionTemplate(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public void setDefaultIsolationLevel(int level) {
        this.defaultIsolationLevel = level;
    }

    public void setDefaultReadOnly(boolean readOnly) {
        this.defaultReadOnly = readOnly;
    }

    public <T> T execute(Function<Connection, T> callback) {
        return executeWithIsolation(callback, defaultIsolationLevel);
    }

    public <T> T executeWithIsolation(Function<Connection, T> callback, int isolationLevel) {
        Transaction tx = new Transaction(connectionManager);
        try {
            tx.setIsolationLevel(isolationLevel);
            tx.begin();
            T result = callback.apply(tx.getConnection());
            tx.commit();
            return result;
        } catch (Exception e) {
            try {
                if (tx.isActive()) {
                    tx.rollback();
                }
            } catch (SQLException ex) {
                throw new TransactionException("Failed to rollback after error", ex);
            }
            throw new TransactionException("Transaction failed", e);
        }
    }

    public void executeWithoutResult(Consumer<Connection> callback) {
        execute(conn -> {
            callback.accept(conn);
            return null;
        });
    }

    public void executeWithNestedSavepoints(Consumer<Connection> callback, int maxRetries) {
        int attempt = 0;
        while (attempt < maxRetries) {
            attempt++;
            Transaction tx = new Transaction(connectionManager);
            try {
                tx.begin();

                try {
                    tx.nestedSavepoint();
                    callback.accept(tx.getConnection());
                    tx.commitToSavepoint();
                    tx.commit();
                    return;
                } catch (Exception e) {
                    if (isRetryableException(e) && attempt < maxRetries) {
                        try {
                            tx.rollbackToSavepoint();
                            Thread.sleep(100 * attempt);
                            continue;
                        } catch (SQLException | InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new TransactionException("Retry failed", ex);
                        }
                    }
                    throw e;
                }
            } catch (Exception e) {
                try {
                    if (tx.isActive()) {
                        tx.rollback();
                    }
                } catch (SQLException ex) {
                    throw new TransactionException("Failed to rollback", ex);
                }
                throw new TransactionException("Transaction failed after " + attempt + " attempts", e);
            }
        }
    }

    private boolean isRetryableException(Exception e) {
        if (e instanceof SQLException sqlEx) {
            String state = sqlEx.getSQLState();
            return state != null && (state.startsWith("40") || state.startsWith("08"));
        }
        return false;
    }
}