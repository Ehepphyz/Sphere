package com.sphere.core.cpp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class CppProcessRunner {

    private final CppBackendMetrics metrics;

    /**
     * Initializes the process runner with a shared metrics tracker.
     * @param metrics The centralized metrics engine instance.
     */
    public CppProcessRunner(CppBackendMetrics metrics) {
        this.metrics = metrics != null ? metrics : new CppBackendMetrics();
    }

    /**
     * Executes a general process command line pipeline (like clang-format) and streams 
     * its runtime output logs line-by-line directly into a consumer functional handler.
     * @param command The full command list matrix to execute.
     * @param outputLineConsumer Functional receiver intercepting stdout/stderr lines (e.g., SwingWorker publish).
     * @return The exit status code returned by the sub-process (0 generally means success).
     */
    public int run(List<String> command, Consumer<String> outputLineConsumer) {
        long startTime = System.nanoTime();
        int exitCode = -1;
        boolean hasError = false;

        try {
            // Merge error stream into standard output to consume everything from a single pipe
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (outputLineConsumer != null) {
                        outputLineConsumer.accept(line);
                    }
                }
            }

            exitCode = process.waitFor();
            hasError = (exitCode != 0);
            return exitCode;

        } catch (Exception e) {
            hasError = true;
            if (outputLineConsumer != null) {
                outputLineConsumer.accept("Process execution failure: " + e.getMessage());
            }
            return exitCode;
        } finally {
            long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            // FIX: Reusing the verified metric channel to register execution telemetry logs
            metrics.recordCompile(durationMillis, hasError);
        }
    }

    /**
     * Executes a C++ compilation command line pipeline and records analytics tracking data.
     * @param command The compilation command target tokens.
     * @return true if compilation succeeded with a zero status code; false otherwise.
     */
    public boolean runCompile(List<String> command) {
        long startTime = System.nanoTime();
        boolean hasError = false;

        try {
            Process process = new ProcessBuilder(command).start();
            
            // Consume output streams to prevent OS buffers from hanging the process
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) { /* Loop-drain stdout */ }
            }

            int exitCode = process.waitFor();
            hasError = (exitCode != 0);
            return !hasError;

        } catch (Exception e) {
            hasError = true;
            return false;
        } finally {
            long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            // Seamless Integration: Pass the duration and failure status directly to the telemetry block
            metrics.recordCompile(durationMillis, hasError);
        }
    }

    /**
     * Runs a compiled C++ binary executable, safely enforcing constraints like execution timeouts.
     * @param command The executable execution context commands.
     * @param timeoutSeconds Maximal run window allocated to the execution before hard cancellation.
     * @return true if completion finished cleanly within limits and returned zero code blocks.
     */
    public boolean runExecutable(List<String> command, long timeoutSeconds) {
        long startTime = System.nanoTime();
        boolean hasError = false;
        boolean isTimeout = false;

        try {
            Process process = new ProcessBuilder(command).start();
            
            // Safe execution constraint enforcement loop
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            
            if (!finished) {
                isTimeout = true;
                hasError = true;
                process.destroyForcibly(); // Hard-kill unresponsive binaries
            } else {
                hasError = (process.exitValue() != 0);
            }
            
            return !hasError;

        } catch (Exception e) {
            hasError = true;
            return false;
        } finally {
            long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            // Seamless Integration: Push process execution data into the striped counters
            metrics.recordRun(durationMillis, hasError, isTimeout);
        }
    }
}