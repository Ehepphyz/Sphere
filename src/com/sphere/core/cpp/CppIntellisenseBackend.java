package com.sphere.core.cpp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SecurityManager;

/**
 * Intelligent Language Server Protocol (LSP) backend manager for clangd.
 * Orchestrates non-blocking JSON-RPC message streaming for real-time C++ Intellisense
 * across cross-platform environments (Windows, Linux, macOS, and WSL).
 */
public final class CppIntellisenseBackend {
    // Replace the old rigid line with this adaptive initialization:
    private static final ExecutorService EXECUTOR = com.sphere.utils.ThreadUtils.createAdaptiveExecutor();
    
    private Process clangdProcess;
    private BufferedWriter processWriter;
    private IntellisenseListener activeListener;

    public synchronized boolean isRunning() {
        return clangdProcess != null && clangdProcess.isAlive();
    }

    /**
     * Starts the clangd LSP server with intelligent default arguments and non-blocking stream consumers.
     * Supports automatic WSL wrapping and file path mapping if targeting a WSL environment.
     *
     * @param clangdExecutable    The absolute system path or name of the clangd binary.
     * @param compileCommandsPath Optional path pointing to the compile_commands.json location directory.
     * @param listener            The active callback receiver for structural JSON-RPC message frames.
     */
    public synchronized void start(String clangdExecutable, String compileCommandsPath, IntellisenseListener listener) throws IOException {
        if (isRunning()) {
            AppLogger.info("Clangd Intellisense instance is already running.");
            return;
        }

        if (clangdExecutable == null || !SecurityManager.isCommandSafe(clangdExecutable)) {
            throw new SecurityException("Security Violation: Rejected unsafe clangd binary path invocation.");
        }

        this.activeListener = listener;

        // Automatically determine if target toolchain is running inside WSL
        boolean isWslTarget = clangdExecutable.toLowerCase().contains("wsl") || 
                              (compileCommandsPath != null && compileCommandsPath.toLowerCase().contains("wsl"));

        List<String> rawArgs = new ArrayList<>();
        rawArgs.add(normalizePathForToolchain(clangdExecutable, isWslTarget));
        
        // Smart performance flags for responsive IDE behavior
        rawArgs.add("-log=error");               // Reduce console noise, focus on actual runtime errors
        rawArgs.add("--background-index");       // Build index in background tasks automatically
        rawArgs.add("--clang-tidy");             // Enable live linting and code diagnostics
        rawArgs.add("--completion-style=detailed"); // Provide enriched payload for code completions
        
        if (compileCommandsPath != null && !compileCommandsPath.isEmpty()) {
            String cleanCommandsDir = normalizePathForToolchain(compileCommandsPath, isWslTarget);
            rawArgs.add("--compile-commands-dir=" + cleanCommandsDir);
        }

        // Apply WSL execution command mapping wrap if active
        List<String> finalCommand = rawArgs;
        if (isWslTarget) {
            List<String> wrappedCmd = new ArrayList<>();
            wrappedCmd.add("wsl");
            for (String arg : rawArgs) {
                if (arg.contains(":\\") || arg.contains(":/")) {
                    wrappedCmd.add(normalizePathForToolchain(arg, true));
                } else {
                    wrappedCmd.add(arg);
                }
            }
            finalCommand = wrappedCmd;
        }

        ProcessBuilder pb = new ProcessBuilder(finalCommand);
        pb.redirectErrorStream(false);

        try {
            clangdProcess = pb.start();
            processWriter = new BufferedWriter(new OutputStreamWriter(clangdProcess.getOutputStream(), StandardCharsets.UTF_8));
            
            // Spawn concurrent volatile task workers to safely empty OS stream pipe allocations
            startStdoutConsumer();
            startStderrConsumer();
            
            AppLogger.info("Clangd LSP Intellisense engine successfully initialized.");
        } catch (IOException e) {
            stop();
            throw e;
        }
    }

    /**
     * Asynchronously transmits a raw JSON-RPC text frame payload directly into clangd's input stream.
     */
    public synchronized void sendRpcMessage(String jsonPayload) {
        if (!isRunning()) {
            AppLogger.error("Cannot dispatch payload frame: Clangd process is down.");
            return;
        }

        if (jsonPayload == null) return;

        EXECUTOR.submit(() -> {
            try {
                synchronized (this) {
                    if (processWriter == null) return;
                    
                    // Standard LSP Protocol Framing format definition
                    byte[] bytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
                    processWriter.write("Content-Length: " + bytes.length + "\r\n\r\n");
                    processWriter.write(jsonPayload);
                    processWriter.flush();
                }
            } catch (IOException e) {
                AppLogger.error("Failed to stream JSON-RPC message payload frame: " + e.getMessage());
            }
        });
    }

    /**
     * Terminates the compilation context and safely closes internal streaming channels.
     */
    public synchronized void stop() {
        if (processWriter != null) {
            try {
                processWriter.close();
            } catch (IOException ignored) {}
            processWriter = null;
        }

        if (clangdProcess != null) {
            if (clangdProcess.isAlive()) {
                clangdProcess.destroy();
                try {
                    if (!clangdProcess.waitFor(3, TimeUnit.SECONDS)) {
                        clangdProcess.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    clangdProcess.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
            clangdProcess = null;
        }
        activeListener = null;
    }

    /**
     * Normalizes absolute host paths into correct POSIX paths whenever WSL is active.
     */
    private String normalizePathForToolchain(String path, boolean isWsl) {
        if (path == null) return "";
        String forwardSlashes = path.replace("\\", "/");
        if (isWsl && forwardSlashes.matches("^[a-zA-Z]:.*")) {
            String drive = forwardSlashes.substring(0, 1).toLowerCase();
            return "/mnt/" + drive + forwardSlashes.substring(2);
        }
        return forwardSlashes;
    }

    /**
     * Processes incoming structural message sequences emitted on stdout by clangd.
     */
    private void startStdoutConsumer() {
        EXECUTOR.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(clangdProcess.getInputStream(), StandardCharsets.UTF_8))) {
                
                String line;
                int contentLength = -1;

                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    
                    // Parse LSP headers
                    if (line.startsWith("Content-Length:")) {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    } else if (line.isEmpty() && contentLength > 0) {
                        // Header boundary reached; read the exact payload content body allocation length
                        char[] buffer = new char[contentLength];
                        int readBytes = 0;
                        while (readBytes < contentLength) {
                            int chunk = reader.read(buffer, readBytes, contentLength - readBytes);
                            if (chunk == -1) break;
                            readBytes += chunk;
                        }

                        String rpcResponse = new String(buffer);
                        contentLength = -1; // Reset boundary marker configuration for upcoming message frames

                        IntellisenseListener currentListener = this.activeListener;
                        if (currentListener != null) {
                            currentListener.onLspResponse(rpcResponse);
                        }
                    }
                }
            } catch (IOException e) {
                AppLogger.error("Intellisense stdout channel loop closed: " + e.getMessage());
            }
        });
    }

    /**
     * Consumes diagnostics and operational logs routed over stderr by the clangd executable.
     */
    private void startStderrConsumer() {
        EXECUTOR.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(clangdProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    AppLogger.error("[Clangd Diagnostic] " + line);
                }
            } catch (IOException e) {
                // Pipe stream dropped during safe process termination sequences
            }
        });
    }

    /**
     * Direct interface definition to process streamed async LSP notifications or response frames.
     */
    public interface IntellisenseListener {
        void onLspResponse(String rawJsonRpcMessage);
    }
}