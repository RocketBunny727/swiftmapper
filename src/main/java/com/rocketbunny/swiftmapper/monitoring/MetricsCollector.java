package com.rocketbunny.swiftmapper.monitoring;

import com.rocketbunny.swiftmapper.utils.logger.SwiftLogger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class MetricsCollector {
    private static final SwiftLogger log = SwiftLogger.getLogger(MetricsCollector.class);
    private static final MetricsCollector INSTANCE = new MetricsCollector();

    private final Map<String, OperationMetrics> metrics = new ConcurrentHashMap<>();
    private final LongAdder totalQueries = new LongAdder();
    private final LongAdder totalErrors = new LongAdder();
    private final AtomicLong startTime = new AtomicLong(System.currentTimeMillis());

    private MetricsCollector() {}

    public static MetricsCollector getInstance() {
        return INSTANCE;
    }

    public void recordQuery(String operation, Duration duration, boolean success) {
        totalQueries.increment();
        if (!success) {
            totalErrors.increment();
        }

        metrics.computeIfAbsent(operation, k -> new OperationMetrics())
                .record(duration.toMillis(), success);

        if (duration.toMillis() > 1000) {
            log.warn("Slow query detected: {} took {}ms", operation, duration.toMillis());
        }
    }

    public void recordConnectionAcquired(Duration waitTime) {
        if (waitTime.toMillis() > 100) {
            log.warn("Slow connection acquisition: {}ms", waitTime.toMillis());
        }
    }

    public void recordCacheHit(String cacheType) {
        metrics.computeIfAbsent(cacheType + "_cache", k -> new OperationMetrics())
                .recordHit();
    }

    public void recordCacheMiss(String cacheType) {
        metrics.computeIfAbsent(cacheType + "_cache", k -> new OperationMetrics())
                .recordMiss();
    }

    public MetricsSnapshot getSnapshot() {
        Map<String, OperationStats> stats = new ConcurrentHashMap<>();
        metrics.forEach((k, v) -> stats.put(k, v.getStats()));

        return new MetricsSnapshot(
                totalQueries.sum(),
                totalErrors.sum(),
                Duration.ofMillis(System.currentTimeMillis() - startTime.get()),
                stats
        );
    }

    public void reset() {
        metrics.clear();
        totalQueries.reset();
        totalErrors.reset();
        startTime.set(System.currentTimeMillis());
    }

    private static class OperationMetrics {
        private final LongAdder count = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final LongAdder totalTime = new LongAdder();
        private final LongAdder hits = new LongAdder();
        private final LongAdder misses = new LongAdder();
        private volatile long maxTime = 0;

        synchronized void record(long millis, boolean success) {
            count.increment();
            totalTime.add(millis);
            if (!success) {
                errors.increment();
            }
            if (millis > maxTime) {
                maxTime = millis;
            }
        }

        void recordHit() {
            hits.increment();
        }

        void recordMiss() {
            misses.increment();
        }

        OperationStats getStats() {
            long cnt = count.sum();
            long total = totalTime.sum();
            return new OperationStats(
                    cnt,
                    errors.sum(),
                    cnt > 0 ? total / cnt : 0,
                    maxTime,
                    hits.sum(),
                    misses.sum()
            );
        }
    }

    public record OperationStats(
            long count,
            long errors,
            long avgTimeMs,
            long maxTimeMs,
            long cacheHits,
            long cacheMisses
    ) {
        public double errorRate() {
            return count > 0 ? (double) errors / count : 0;
        }

        public double cacheHitRate() {
            long total = cacheHits + cacheMisses;
            return total > 0 ? (double) cacheHits / total : 0;
        }
    }

    public record MetricsSnapshot(
            long totalQueries,
            long totalErrors,
            Duration uptime,
            Map<String, OperationStats> operations
    ) {
        public double overallErrorRate() {
            return totalQueries > 0 ? (double) totalErrors / totalQueries : 0;
        }
    }
}