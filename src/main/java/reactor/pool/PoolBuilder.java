/*
 * Copyright (c) 2018-Present Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.pool;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Predicate;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * A builder for {@link Pool}. .
 *
 * @author Simon Baslé
 * @author Stephane Maldini
 */
public class PoolBuilder<T> {

    //TODO tests

    /**
     * Start building a {@link Pool} by describing how new objects are to be asynchronously allocated.
     * Note that the {@link Mono} {@code allocator} should NEVER block its thread (thus adapting from blocking code,
     * eg. a constructor, via {@link Mono#fromCallable(Callable)} should be augmented with {@link Mono#subscribeOn(Scheduler)}).
     * <p>
     * The returned {@link Pool} is based on MPSC queues for both idle resources and pending {@link Pool#acquire()} Monos.
     * It uses non-blocking drain loops to deliver resources to borrowers, which means that a resource could
     * be handed off on any of the following {@link Thread threads}:
     * <ul>
     *     <li>any thread on which a resource was last allocated</li>
     *     <li>any thread on which a resource was recently released</li>
     *     <li>any thread on which an {@link Pool#acquire()} {@link Mono} was subscribed</li>
     * </ul>
     * For a more deterministic approach, the {@link #acquisitionScheduler(Scheduler)} property of the builder can be used.
     *
     * @param allocator the asynchronous creator of poolable resources.
     * @param <T> the type of resource created and recycled by the {@link Pool}
     * @return a builder of {@link Pool}
     */
    //Use a single from ?
    // There are use cases for FIFO (connections), LIFO/RANDOM (for fast lookup, like 
    // protecting a WebFlux endpoint with limited token), 
    // Affinity vs not Affinity (usually also for faster lookup, but default should be 
    // affinity unless told otherwise). :
     
    public static <T> PoolBuilder<T> from(Mono<T> allocator) {
        return new PoolBuilder<>(allocator);
    }

    final Mono<T> allocator;

    boolean                 isAffinity           = true;
    int                     initialSize          = 0;
    AllocationStrategy      allocationStrategy   = AllocationStrategies.unbounded();
    Function<T, Mono<Void>> releaseHandler       = noopHandler();
    Function<T, Mono<Void>> destroyHandler       = noopHandler();
    Predicate<PooledRef<T>> evictionPredicate    = neverPredicate();
    Scheduler               acquisitionScheduler = Schedulers.immediate();
    PoolMetricsRecorder     metricsRecorder      = NoOpPoolMetricsRecorder.INSTANCE;

    //TODO add validation in this class (started with NPE validation but needs
    // size/duration

    //Add queue strategy option

    PoolBuilder(Mono<T> allocator) {
        this.allocator = allocator;
    }

    /**
     * How many resources the {@link Pool} should allocate upon creation.
     * This parameter MAY be ignored by some implementations (although they should state so in their documentation).
     * <p>
     * Defaults to {@code 0}.
     *
     * @param n the initial size of the {@link Pool}.
     * @return this {@link Pool} builder
     */
    //TODO should we have a initial AND a minimum size when idle timeout is used
    public PoolBuilder<T> initialSize(int n) {
        this.initialSize = n;
        return this;
    }

    /**
     * Configure a {@link Pool} by describing how new objects are to be asynchronously
     * allocated.
     * Note that the {@link Mono} {@code allocator} should NEVER block its thread (thus adapting from blocking code,
     * eg. a constructor, via {@link Mono#fromCallable(Callable)} should be augmented with {@link Mono#subscribeOn(Scheduler)}).
     * <p>
     * The returned {@link Pool} attempts to keep resources on the same thread, by prioritizing
     * pending {@link Pool#acquire()} {@link Mono Monos} that were subscribed on the same thread on which a resource is
     * released. In case no such borrower exists, but some are pending from another thread, it will deliver to these
     * borrowers instead (a slow path with no fairness guarantees).
     *
     * @param isAffinity true if pool should cache by thread
     * @return this {@link Pool} builder
     */
    //TODO should we have a initial AND a minimum size when idle timeout is used
    public PoolBuilder<T> cacheByThread(boolean isAffinity) {
        this.isAffinity = isAffinity;
        return this;
    }

    /**
     * Limits in how many resources can be allocated and managed by the {@link Pool} are driven by the
     * provided {@link AllocationStrategy}.
     * <p>
     * Defaults to an unbounded creation of resources, although it is not a recommended one.
     * See {@link AllocationStrategies} for readily available strategies based on counters.
     *
     * @param allocationStrategy the {@link AllocationStrategy} to use
     * @return this {@link Pool} builder
     */
    public PoolBuilder<T> allocationStrategy(AllocationStrategy allocationStrategy) {
        this.allocationStrategy = Objects.requireNonNull(allocationStrategy, "allocationStrategy");
        return this;
    }

    /**
     * Let the {@link Pool} allocate new resources when no idle resource is available,
     * without limit.
     *
     * @return this {@link Pool} builder
     */
    public PoolBuilder<T> unboundedSize() {
        return allocationStrategy(AllocationStrategies.unbounded());
    }

    /**
     * Let the {@link Pool} allocate at most {@code max} resources, rejecting further allocations until
     * {@link AllocationStrategy#returnPermits(int)} has been called.
     *
     * @param maxSize the maximum number of live resources to keep in the pool
     *
     * @return this {@link Pool} builder
     */
    public PoolBuilder<T> maxSize(int maxSize) {
        return allocationStrategy(AllocationStrategies.allocatingMax(maxSize));
    }

    /**
     * Provide a {@link Function handler} that will derive a release {@link Mono}
     * whenever a resource is released.
     * The reset procedure is applied asynchronously before vetting the object through {@link #evictionPredicate}.
     * If the reset Mono couldn't put the resource back in a usable state, it will be {@link #destroyHandler(Function) destroyed}.
     * <p>
     * Defaults to not resetting anything.
     *
     * @param releaseHandler the {@link Function} supplying the state-resetting {@link Mono}
     * @return this {@link Pool} builder
     */
    public PoolBuilder<T> releaseHandler(Function<T, Mono<Void>> releaseHandler) {
        this.releaseHandler = Objects.requireNonNull(releaseHandler, "releaseHandler");
        return this;
    }

    /**
     * Provide a {@link Function handler} that will derive a destroy {@link Mono} whenever a resource isn't fit for
     * usage anymore (either through eviction, manual invalidation, or because something went wrong with it).
     * The destroy procedure is applied asynchronously and errors are swallowed.
     * <p>
     * Defaults to recognizing {@link Disposable} and {@link java.io.Closeable} elements and disposing them.
     *
     * @param destroyHandler the {@link Function} supplying the state-resetting {@link Mono}
     * @return this {@link Pool} builder
     */
    public PoolBuilder<T> destroyHandler(Function<T, Mono<Void>> destroyHandler) {
        this.destroyHandler = Objects.requireNonNull(destroyHandler, "destroyHandler");
        return this;
    }

    /**
     * Provide an eviction {@link Predicate} that allows to decide if a resource is fit for being placed in the {@link Pool}.
     * This can happen whenever a resource is {@link PooledRef#release() released} back to the {@link Pool} (after
     * it has been {@link #releaseHandler(Function) reset}), but also when being {@link Pool#acquire() acquired}
     * from the pool (triggering a second pass if the object is found to be unfit, eg. it has been idle for too long).
     * Finally, some pool implementations MAY implement a reaper thread mechanism that detect idle resources through
     * this predicate and destroy them.
     * <p>
     * Defaults to never evicting. See {@link EvictionPredicates} for pre-build eviction predicates.
     *
     * @param evictionPredicate a {@link Predicate} that returns {@code true} if the resource is unfit for the pool and should be destroyed
     * @return this {@link Pool} builder
     */
    public PoolBuilder<T> evictionPredicate(Predicate<PooledRef<T>> evictionPredicate) {
        this.evictionPredicate = Objects.requireNonNull(evictionPredicate,
                "evictionPredicate");
        return this;
    }

    /**
     /**
     * Configure the builder with an {@link #evictionPredicate(Predicate)} that matches
     * {@link PooledRef} of resources that have been idle (ie released and available in
     * the {@link Pool}) for more than the {@code ttl} {@link Duration} (inclusive).
     * Such a predicate could be used to evict too idle objects when next encountered by an {@link Pool#acquire()}.
     *
     * @param ttl the {@link Duration} after which an object should not be passed to a borrower, but destroyed (resolution: ms)
     * @return this {@link Pool} builder
     */
    public PoolBuilder<T> evictionIdle(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        return evictionPredicate(EvictionPredicates.idleMoreThan(ttl));
    }

    /**
     * Provide a {@link Scheduler} that can optionally be used by a {@link Pool} to deliver its resources in a more
     * deterministic (albeit potentially less efficient) way, thread-wise. Other implementations MAY completely ignore
     * this parameter.
     * <p>
     * Defaults to {@link Schedulers#immediate()}.
     *
     * @param acquisitionScheduler the {@link Scheduler} on which to deliver acquired resources
     * @return this {@link Pool} builder
     */
    public PoolBuilder<T> acquisitionScheduler(Scheduler acquisitionScheduler) {
        this.acquisitionScheduler = Objects.requireNonNull(acquisitionScheduler,
                "acquisitionScheduler");
        return this;
    }

    /**
     * Set up the optional {@link PoolMetricsRecorder} for {@link Pool} to use for instrumentation purposes.
     *
     * @param recorder the {@link PoolMetricsRecorder}
     * @return this {@link Pool} builder
     */
    public PoolBuilder<T> metricsRecorder(PoolMetricsRecorder recorder) {
        this.metricsRecorder = Objects.requireNonNull(recorder, "metricsRecorder");
        return this;
    }

    /**
     * Build the {@link Pool}.
     *
     * @return the {@link Pool}
     */
    public Pool<T> build() {
        AbstractPool.DefaultPoolConfig<T> config = buildConfig();
        if (isAffinity) {
            return new AffinityPool<>(config);
        }
        return new QueuePool<>(config);
    }

    //kept package-for the benefit of tests
    AbstractPool.DefaultPoolConfig<T> buildConfig() {
        return new AbstractPool.DefaultPoolConfig<>(allocator, initialSize, allocationStrategy,
                releaseHandler,
                destroyHandler,
                evictionPredicate,
                acquisitionScheduler, metricsRecorder);
    }


    @SuppressWarnings("unchecked")
    static <T> Function<T, Mono<Void>> noopHandler() {
        return (Function<T, Mono<Void>>) NOOP_HANDLER;
    }

    @SuppressWarnings("unchecked")
    static <T> Predicate<PooledRef<T>>  neverPredicate() {
        return (Predicate<PooledRef<T>>) NEVER_PREDICATE;
    }

    static final Function<?, Mono<Void>> NOOP_HANDLER    = it -> Mono.empty();
    static final Predicate<?>            NEVER_PREDICATE = it -> false;
}
