package io.github.rocketbunny727.swiftmapper.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.rocketbunny727.swiftmapper.utils.logger.SwiftLogger;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class QueryCache {
    private final SwiftLogger log = SwiftLogger.getLogger(QueryCache.class);
    private Cache<String, List<?>> cache;
    private boolean enabled = true;
    private long maximumSize = 1000;
    private long expireAfterWriteMinutes = 10;
    private Class<?> cacheProvider = CaffeineCacheProvider.class;

    public QueryCache() {
        initializeCache();
    }

    public void configure(boolean enabled, long maxSize, long expireMinutes, String providerClass) {
        this.enabled = enabled;
        this.maximumSize = maxSize;
        this.expireAfterWriteMinutes = expireMinutes;
        if (providerClass != null && !providerClass.isEmpty()) {
            try {
                this.cacheProvider = Class.forName(providerClass);
            } catch (ClassNotFoundException e) {
                log.warn("Cache provider class not found: {}, using default", providerClass);
            }
        }
        initializeCache();
    }

    @SuppressWarnings("unchecked")
    private void initializeCache() {
        if (!enabled) {
            this.cache = null;
            return;
        }

        try {
            CacheProvider provider = (CacheProvider) cacheProvider.getDeclaredConstructor().newInstance();
            this.cache = provider.createCache(maximumSize, expireAfterWriteMinutes);
            log.info("QueryCache initialized with provider: {}", cacheProvider.getSimpleName());
        } catch (Exception e) {
            log.error("Failed to initialize cache provider, using default Caffeine", e);
            this.cache = new CaffeineCacheProvider().createCache(maximumSize, expireAfterWriteMinutes);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> get(String key, Supplier<List<T>> loader) {
        if (!enabled || cache == null) {
            return loader.get();
        }

        List<T> result = (List<T>) cache.getIfPresent(key);
        if (result == null) {
            result = loader.get();
            if (result != null) {
                cache.put(key, result);
                log.debug("Cache miss for key: {}", key);
            }
        } else {
            log.debug("Cache hit for key: {}", key);
        }
        return result;
    }

    public void invalidate(String key) {
        if (cache != null) {
            cache.invalidate(key);
            log.debug("Invalidated cache key: {}", key);
        }
    }

    public void invalidateAll() {
        if (cache != null) {
            cache.invalidateAll();
            log.info("All cache entries invalidated");
        }
    }

    public void invalidatePattern(String pattern) {
        if (cache == null) return;

        cache.asMap().keySet().removeIf(key -> key.matches(pattern.replace("*", ".*")));
        log.info("Invalidated cache keys matching pattern: {}", pattern);
    }

    public CacheStats getStats() {
        if (cache == null) return new CacheStats(0, 0, 0);
        return new CacheStats(
                cache.estimatedSize(),
                -1,
                -1
        );
    }

    public interface CacheProvider {
        Cache<String, List<?>> createCache(long maxSize, long expireMinutes);
    }

    public static class CaffeineCacheProvider implements CacheProvider {
        @Override
        public Cache<String, List<?>> createCache(long maxSize, long expireMinutes) {
            return Caffeine.newBuilder()
                    .maximumSize(maxSize)
                    .expireAfterWrite(expireMinutes, TimeUnit.MINUTES)
                    .recordStats()
                    .build();
        }
    }

    public static class CacheStats {
        private final long size;
        private final long hitCount;
        private final long missCount;

        public CacheStats(long size, long hitCount, long missCount) {
            this.size = size;
            this.hitCount = hitCount;
            this.missCount = missCount;
        }

        public long getSize() { return size; }
        public long getHitCount() { return hitCount; }
        public long getMissCount() { return missCount; }
    }
}