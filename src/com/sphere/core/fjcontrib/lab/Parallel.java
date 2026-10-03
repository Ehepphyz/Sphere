package com.sphere.core.fjcontrib.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

/** Independent tasks 0..n-1 on several cores, results in task order: a serial run's answer, sooner. */
final class Parallel {

    private Parallel() {
    }

    static <R> List<R> map(int n, IntFunction<R> task, int threads) {
        if (threads <= 1 || n < 2) {
            final List<R> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(task.apply(i));
            return out;
        }
        final ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            return pool.submit(() -> IntStream.range(0, n).parallel().mapToObj(task).toList()).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        } finally {
            pool.shutdown();
        }
    }
}
