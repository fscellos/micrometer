/**
 * Copyright 2018 VMware, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.micrometer.core.instrument.binder.cache;

import static java.util.Objects.requireNonNull;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.lang.NonNullApi;
import io.micrometer.core.lang.NonNullFields;
import org.checkerframework.checker.index.qual.NonNegative;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.concurrent.TimeUnit;

/**
 * A {@link StatsCounter} instrumented with Micrometer. This will provide more detailed metrics than using
 * {@link CaffeineCacheMetrics}.
 * <p>
 * Note that this doesn't instrument the cache's size by default. Use {@link #registerSizeMetric(Cache)} to do so after
 * the cache has been built.
 * <p>
 * Use {@link com.github.benmanes.caffeine.cache.Caffeine#recordStats} to supply this class to the cache builder:
 * <pre>{@code
 * MeterRegistry registry = ...;
 * Cache<Key, Graph> graphs = Caffeine.newBuilder()
 *     .maximumSize(10_000)
 *     .recordStats(() -> new CaffeineStatsCounter(registry, "graphs"))
 *     .build();
 * }</pre>
 *
 * @author Ben Manes
 * @author John Karp
 * @see CaffeineCacheMetrics
 */
@NonNullApi
@NonNullFields
public final class CaffeineStatsCounter implements StatsCounter {

    private final MeterRegistry registry;
    private final Tags tags;

    private final Counter hitCount;
    private final Counter missCount;
    private final Timer loadSuccesses;
    private final Timer loadFailures;
    private final DistributionSummary evictions;
    private final EnumMap<RemovalCause, DistributionSummary> evictionMetrics;

    /**
     * Constructs an instance for use by a single cache.
     *
     * @param registry  the registry of metric instances
     * @param cacheName will be used to tag metrics with "cache".
     */
    public CaffeineStatsCounter(MeterRegistry registry, String cacheName) {
        this(registry, cacheName, Tags.empty());
    }

    /**
     * Constructs an instance for use by a single cache.
     *
     * @param registry  the registry of metric instances
     * @param cacheName will be used to tag metrics with "cache".
     * @param extraTags tags to apply to all recorded metrics.
     */
    public CaffeineStatsCounter(MeterRegistry registry, String cacheName, Iterable<Tag> extraTags) {
        requireNonNull(registry);
        requireNonNull(cacheName);
        requireNonNull(extraTags);
        this.registry = registry;
        this.tags = Tags.concat(extraTags, "cache", cacheName);

        hitCount = Counter.builder("cache.gets").tag("result", "hit").tags(tags)
                .description("The number of times cache lookup methods have returned a cached value.")
                .register(registry);
        missCount = Counter.builder("cache.gets").tag("result", "miss").tags(tags)
                .description("The number of times cache lookup methods have returned an uncached (newly loaded) value.")
                .register(registry);
        loadSuccesses = Timer.builder("cache.loads").tag("result", "success").tags(tags)
                .description("Successful cache loads.").register(registry);
        loadFailures = Timer.builder("cache.loads").tag("result", "failure").tags(tags)
                .description("Failed cache loads.").register(registry);
        evictions = DistributionSummary.builder("cache.evictions").tags(tags)
                .description("Entries evicted from cache.").register(registry);

        evictionMetrics = new EnumMap<>(RemovalCause.class);
        Arrays.stream(RemovalCause.values()).forEach(cause -> evictionMetrics.put(
                cause,
                DistributionSummary.builder("cache.evictions").tag("cause", cause.name()).tags(tags)
                        .description("Entries evicted from cache.").register(registry)));
    }

    /**
     * Register a metric for the size of the cache.
     * @param cache
     */
    public void registerSizeMetric(Cache<?,?> cache) {
        Gauge.builder("cache.size", cache, Cache::estimatedSize).tags(tags)
                .description("The approximate number of entries in this cache.")
                .register(registry);
    }

    @Override
    public void recordHits(@NonNegative int count) {
        hitCount.increment(count);
    }

    @Override
    public void recordMisses(@NonNegative int count) {
        missCount.increment(count);
    }

    @Override
    public void recordLoadSuccess(@NonNegative long loadTime) {
        loadSuccesses.record(loadTime, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordLoadFailure(@NonNegative long loadTime) {
        loadFailures.record(loadTime, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordEviction() {
        recordEviction(1);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void recordEviction(@NonNegative int weight) {
        evictions.record(weight);
    }

    @Override
    public void recordEviction(@NonNegative int weight, RemovalCause cause) {
        evictionMetrics.get(cause).record(weight);
    }

    @Override
    public CacheStats snapshot() {
        return new CacheStats(
                (long) hitCount.count(),
                (long) missCount.count(),
                loadSuccesses.count(),
                loadFailures.count(),
                (long) loadSuccesses.totalTime(TimeUnit.NANOSECONDS)
                + (long) loadFailures.totalTime(TimeUnit.NANOSECONDS),
                evictions.count() + evictionMetrics.values().stream().mapToLong(DistributionSummary::count).sum(),
                (long) (evictions.totalAmount()
                        + evictionMetrics.values().stream().mapToDouble(DistributionSummary::totalAmount).sum())
        );
    }

    @Override
    public String toString() {
        return snapshot().toString();
    }
}
