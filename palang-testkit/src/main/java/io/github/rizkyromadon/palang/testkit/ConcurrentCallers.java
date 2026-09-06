package io.github.rizkyromadon.palang.testkit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Fires N callers at the same instant and collects what each one got back.
 *
 * <p>Testing deduplication sequentially proves nothing: the interesting failures
 * only appear when callers genuinely overlap. This harness holds every thread at a
 * latch until all of them are ready, then releases them together, so the requests
 * actually race instead of trickling.
 *
 * <pre>{@code
 * CallerResults<Integer> results = ConcurrentCallers.of(32)
 *         .fire(() -> restTemplate.postForEntity(url, request, String.class)
 *                                 .getStatusCode().value());
 *
 * results.assertNoFailures();
 * assertThat(results.count(status -> status == 201)).isEqualTo(1);
 * }</pre>
 */
public final class ConcurrentCallers {

    private final int callers;
    private Duration timeout = Duration.ofSeconds(30);

    private ConcurrentCallers(int callers) {
        if (callers < 2) {
            throw new IllegalArgumentException("Concurrency testing needs at least 2 callers, got " + callers);
        }
        this.callers = callers;
    }

    /**
     * Prepares a burst of the given size.
     *
     * @param callers how many threads fire simultaneously
     * @return a configurable harness
     */
    public static ConcurrentCallers of(int callers) {
        return new ConcurrentCallers(callers);
    }

    /**
     * Sets how long to wait for all callers to finish before giving up.
     *
     * @param timeout the overall budget
     * @return this harness
     */
    public ConcurrentCallers timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * Runs the action on every caller at once.
     *
     * @param action the work each caller performs
     * @param <T>    the result type
     * @return every result and every failure
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws IllegalStateException if the callers do not finish within the timeout
     */
    public <T> CallerResults<T> fire(Callable<T> action) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(callers);
        ConcurrentLinkedQueue<T> results = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < callers; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        results.add(action.call());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failures.add(e);
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        finished.countDown();
                    }
                });
            }

            if (!ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Callers did not reach the start line within " + timeout);
            }
            start.countDown();
            if (!finished.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Callers did not finish within " + timeout);
            }
        } finally {
            pool.shutdownNow();
        }

        return new CallerResults<>(new ArrayList<>(results), new ArrayList<>(failures));
    }

    /**
     * What a burst of concurrent callers produced.
     *
     * @param results  values returned by callers that succeeded
     * @param failures throwables from callers that did not
     * @param <T>      the result type
     */
    public record CallerResults<T>(List<T> results, List<Throwable> failures) {

        /**
         * Fails loudly if any caller threw, naming the first failure.
         *
         * @return this result, for chaining
         */
        public CallerResults<T> assertNoFailures() {
            if (!failures.isEmpty()) {
                throw new AssertionError(
                        failures.size() + " of " + (results.size() + failures.size())
                                + " callers failed; first was: " + failures.get(0),
                        failures.get(0));
            }
            return this;
        }

        /**
         * Counts results matching a predicate.
         *
         * @param predicate the test to apply
         * @return how many results matched
         */
        public long count(java.util.function.Predicate<T> predicate) {
            return results.stream().filter(predicate).count();
        }
    }
}
