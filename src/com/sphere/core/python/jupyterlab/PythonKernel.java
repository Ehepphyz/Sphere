package com.sphere.core.python.jupyterlab;

import javax.swing.SwingUtilities;
import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * PythonKernel
 *
 * Safer process invocation pattern that consumes process streams
 * in a background thread to avoid UI freezing and deadlocks on large output.
 *
 * WARNING: Running arbitrary Python code is potentially dangerous.
 * Provide a user option to disable execution for untrusted notebooks.
 */
public class PythonKernel {

    /**
     * Callback interface to notify the Swing Event Dispatch Thread (EDT)
     * when execution completes or encounters an error.
     */
    public interface KernelCallback {
        void onResult(String output, int exitCode);
        void onError(String message);
    }

    /**
     * Executes Python code asynchronously in a background thread.
     */
    public static void runProcessAsync(String code, KernelCallback callback) {
        new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("python", "-c", code);
                pb.redirectErrorStream(true);
                Process p = pb.start();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();

                // Background thread to swallow the stdout/stderr stream continuously
                Thread reader = new Thread(() -> {
                    try (InputStream in = p.getInputStream()) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            baos.write(buf, 0, n);
                        }
                    } catch (IOException ignored) {}
                }, "python-output-reader");
                reader.setDaemon(true);
                reader.start();

                // Wait for process termination on the worker thread (does not freeze the UI)
                int exit = p.waitFor();
                reader.join();

                String output = baos.toString(StandardCharsets.UTF_8);
                
                // Safely hand the result back to the Swing EDT
                SwingUtilities.invokeLater(() -> callback.onResult(output, exit));

            } catch (Exception e) {
                // Safely hand errors back to the Swing EDT
                SwingUtilities.invokeLater(() -> callback.onError(e.getMessage()));
            }
        }, "python-kernel-worker").start();
    }
}