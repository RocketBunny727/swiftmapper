package com.rocketbunny.swiftmapper.utils.logger;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class QueryLogger {
    private static final SwiftLogger log = SwiftLogger.getLogger(QueryLogger.class);
    private final ConcurrentLinkedQueue<QueryLogEntry> queryLog = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<TransactionLogEntry> transactionLog = new ConcurrentLinkedQueue<>();
    private final AtomicLong queryCounter = new AtomicLong(0);
    private final AtomicLong transactionCounter = new AtomicLong(0);
    private volatile boolean enabled = true;
    private volatile boolean logToConsole = true;
    private volatile Consumer<QueryLogEntry> queryListener;
    private volatile Consumer<TransactionLogEntry> transactionListener;
    private long slowQueryThresholdMs = 1000;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setLogToConsole(boolean logToConsole) {
        this.logToConsole = logToConsole;
    }

    public void setSlowQueryThreshold(long thresholdMs) {
        this.slowQueryThresholdMs = thresholdMs;
    }

    public void setQueryListener(Consumer<QueryLogEntry> listener) {
        this.queryListener = listener;
    }

    public void setTransactionListener(Consumer<TransactionLogEntry> listener) {
        this.transactionListener = listener;
    }

    public QueryLogEntry logQueryStart(String sql, Object... params) {
        if (!enabled) return null;

        long id = queryCounter.incrementAndGet();
        QueryLogEntry entry = new QueryLogEntry(
                id,
                Instant.now(),
                sql,
                params != null ? List.of(params) : List.of(),
                Thread.currentThread().getName()
        );

        queryLog.offer(entry);

        if (logToConsole) {
            log.debug("Query #{} started: {}", id, sql);
        }

        return entry;
    }

    public void logQueryEnd(QueryLogEntry entry, int rowsAffected, Throwable error) {
        if (!enabled || entry == null) return;

        entry.complete(rowsAffected, error);

        if (logToConsole) {
            Duration duration = entry.getDuration();
            if (error != null) {
                log.error("Query #{} failed after {}ms: {}", error, entry.getId(), duration.toMillis(), error.getMessage());
            } else if (duration.toMillis() > slowQueryThresholdMs) {
                log.warn("Query #{} completed in {}ms (slow): {}", entry.getId(), duration.toMillis(), entry.getSql());
            } else {
                log.debug("Query #{} completed in {}ms, rows: {}", entry.getId(), duration.toMillis(), rowsAffected);
            }
        }

        if (queryListener != null) {
            queryListener.accept(entry);
        }
    }

    public TransactionLogEntry logTransactionStart(String operation) {
        if (!enabled) return null;

        long id = transactionCounter.incrementAndGet();
        TransactionLogEntry entry = new TransactionLogEntry(
                id,
                Instant.now(),
                operation,
                Thread.currentThread().getName()
        );

        transactionLog.offer(entry);

        if (logToConsole) {
            log.debug("Transaction #{} started: {}", id, operation);
        }

        return entry;
    }

    public void logTransactionEnd(TransactionLogEntry entry, boolean committed, Throwable error) {
        if (!enabled || entry == null) return;

        entry.complete(committed, error);

        if (logToConsole) {
            Duration duration = entry.getDuration();
            String status = committed ? "committed" : "rolled back";
            if (error != null) {
                log.error("Transaction #{} failed after {}ms: {}", error, entry.getId(), duration.toMillis(), error.getMessage());
            } else {
                log.debug("Transaction #{} {} in {}ms", entry.getId(), status, duration.toMillis());
            }
        }

        if (transactionListener != null) {
            transactionListener.accept(entry);
        }
    }

    public List<QueryLogEntry> getQueryLog() {
        return new ArrayList<>(queryLog);
    }

    public List<TransactionLogEntry> getTransactionLog() {
        return new ArrayList<>(transactionLog);
    }

    public void clearQueryLog() {
        queryLog.clear();
    }

    public void clearTransactionLog() {
        transactionLog.clear();
    }

    public QueryStatistics getStatistics() {
        long totalQueries = queryCounter.get();
        long slowQueries = queryLog.stream()
                .filter(e -> e.getDuration().toMillis() > slowQueryThresholdMs)
                .count();
        long failedQueries = queryLog.stream()
                .filter(e -> e.getError() != null)
                .count();

        double avgDuration = queryLog.stream()
                .mapToLong(e -> e.getDuration().toMillis())
                .average()
                .orElse(0.0);

        return new QueryStatistics(totalQueries, slowQueries, failedQueries, avgDuration);
    }

    public static class QueryLogEntry {
        private final long id;
        private final Instant startTime;
        private final String sql;
        private final List<Object> params;
        private final String threadName;
        private volatile Instant endTime;
        private volatile int rowsAffected;
        private volatile Throwable error;

        public QueryLogEntry(long id, Instant startTime, String sql, List<Object> params, String threadName) {
            this.id = id;
            this.startTime = startTime;
            this.sql = sql;
            this.params = params;
            this.threadName = threadName;
        }

        void complete(int rowsAffected, Throwable error) {
            this.endTime = Instant.now();
            this.rowsAffected = rowsAffected;
            this.error = error;
        }

        public long getId() { return id; }
        public Instant getStartTime() { return startTime; }
        public String getSql() { return sql; }
        public List<Object> getParams() { return params; }
        public String getThreadName() { return threadName; }
        public Instant getEndTime() { return endTime; }
        public int getRowsAffected() { return rowsAffected; }
        public Throwable getError() { return error; }

        public Duration getDuration() {
            if (endTime == null) return Duration.ZERO;
            return Duration.between(startTime, endTime);
        }
    }

    public static class TransactionLogEntry {
        private final long id;
        private final Instant startTime;
        private final String operation;
        private final String threadName;
        private volatile Instant endTime;
        private volatile boolean committed;
        private volatile Throwable error;

        public TransactionLogEntry(long id, Instant startTime, String operation, String threadName) {
            this.id = id;
            this.startTime = startTime;
            this.operation = operation;
            this.threadName = threadName;
        }

        void complete(boolean committed, Throwable error) {
            this.endTime = Instant.now();
            this.committed = committed;
            this.error = error;
        }

        public long getId() { return id; }
        public Instant getStartTime() { return startTime; }
        public String getOperation() { return operation; }
        public String getThreadName() { return threadName; }
        public Instant getEndTime() { return endTime; }
        public boolean isCommitted() { return committed; }
        public Throwable getError() { return error; }

        public Duration getDuration() {
            if (endTime == null) return Duration.ZERO;
            return Duration.between(startTime, endTime);
        }
    }

    public record QueryStatistics(
            long totalQueries,
            long slowQueries,
            long failedQueries,
            double averageDurationMs
    ) {}
}