package com.sphere.core.cpp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

public final class CppBackendMetrics {

    // Immutable Snapshot record exposing a unified, perfectly aligned historical metric state
    public static final class MetricsSnapshot {
        private final long totalCompileTimeMillis;
        private final long totalRunTimeMillis;
        private final long compileCount;
        private final long runCount;
        private final long errorCount;
        private final long timeoutCount;

        public MetricsSnapshot(long totalCompileTime, long totalRunTime, long compileCount, 
                               long runCount, long errorCount, long timeoutCount) {
            this.totalCompileTimeMillis = totalCompileTime;
            this.totalRunTimeMillis = totalRunTime;
            this.compileCount = compileCount;
            this.runCount = runCount;
            this.errorCount = errorCount;
            this.timeoutCount = timeoutCount;
        }

        public long getTotalCompileTimeMillis() { return totalCompileTimeMillis; }
        public long getTotalRunTimeMillis() { return totalRunTimeMillis; }
        public long getCompileCount() { return compileCount; }
        public long getRunCount() { return runCount; }
        public long getErrorCount() { return errorCount; }
        public long getTimeoutCount() { return timeoutCount; }

        public double getAverageCompileTimeMillis() {
            return compileCount == 0 ? 0.0 : (double) totalCompileTimeMillis / compileCount;
        }

        public double getAverageRunTimeMillis() {
            return runCount == 0 ? 0.0 : (double) totalRunTimeMillis / runCount;
        }

        public double getSuccessRatePercentage() {
            long totalOperations = compileCount + runCount;
            if (totalOperations == 0) return 100.0;
            return 100.0 - (((double) errorCount / totalOperations) * 100.0);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("totalCompileTimeMillis", totalCompileTimeMillis);
            map.put("totalRunTimeMillis", totalRunTimeMillis);
            map.put("compileCount", compileCount);
            map.put("runCount", runCount);
            map.put("errorCount", errorCount);
            map.put("timeoutCount", timeoutCount);
            map.put("avgCompileTimeMillis", getAverageCompileTimeMillis());
            map.put("avgRunTimeMillis", getAverageRunTimeMillis());
            map.put("successRatePercentage", getSuccessRatePercentage());
            return map;
        }

        @Override
        public String toString() {
            return String.format("Compilations: %d (Avg: %.2fms) | Runs: %d (Avg: %.2fms) | Errors: %d | Timeouts: %d",
                    compileCount, getAverageCompileTimeMillis(), runCount, getAverageRunTimeMillis(), errorCount, timeoutCount);
        }
    }

    // High performance striped internal thread allocators bypassing read-contention blocks
    private final LongAdder totalCompileTimeMillis = new LongAdder();
    private final LongAdder totalRunTimeMillis = new LongAdder();
    private final LongAdder compileCount = new LongAdder();
    private final LongAdder runCount = new LongAdder();
    private final LongAdder errorCount = new LongAdder();
    private final LongAdder timeoutCount = new LongAdder();

    /**
     * Records telemetry metadata captured from a single execution of the C++ compiler.
     */
    public void recordCompile(long durationMillis, boolean error) {
        if (durationMillis > 0) {
            totalCompileTimeMillis.add(durationMillis);
        }
        compileCount.increment();
        if (error) {
            errorCount.increment();
        }
    }

    /**
     * Records telemetry metadata captured from running a compiled C++ binary artifact.
     */
    public void recordRun(long durationMillis, boolean error, boolean timeout) {
        if (durationMillis > 0) {
            totalRunTimeMillis.add(durationMillis);
        }
        runCount.increment();
        if (error) {
            errorCount.increment();
        }
        if (timeout) {
            timeoutCount.increment();
        }
    }

    public long getTotalCompileTimeMillis() { return totalCompileTimeMillis.sum(); }
    public long getTotalRunTimeMillis() { return totalRunTimeMillis.sum(); }
    public long getCompileCount() { return compileCount.sum(); }
    public long getRunCount() { return runCount.sum(); }
    public long getErrorCount() { return errorCount.sum(); }
    public long getTimeoutCount() { return timeoutCount.sum(); }

    public double getAverageCompileTimeMillis() {
        long count = compileCount.sum();
        return count == 0 ? 0.0 : (double) totalCompileTimeMillis.sum() / count;
    }

    public double getAverageRunTimeMillis() {
        long count = runCount.sum();
        return count == 0 ? 0.0 : (double) totalRunTimeMillis.sum() / count;
    }

    /**
     * Generates an atomic, unmodifiable structural snapshot of all tracked telemetry data.
     * Use this method when printing stats to logs or flushing UI display updates to prevent 
     * cross-variable drift mid-read.
     */
    public MetricsSnapshot getSnapshot() {
        return new MetricsSnapshot(
                totalCompileTimeMillis.sum(),
                totalRunTimeMillis.sum(),
                compileCount.sum(),
                runCount.sum(),
                errorCount.sum(),
                timeoutCount.sum()
        );
    }

    /**
     * Completely resets all accumulated telemetry statistics back to zero.
     */
    public void reset() {
        totalCompileTimeMillis.reset();
        totalRunTimeMillis.reset();
        compileCount.reset();
        runCount.reset();
        errorCount.reset();
        timeoutCount.reset();
    }
}