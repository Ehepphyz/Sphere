package com.sphere.components.terminal;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

public class TerminalEngine {

    public interface OutputListener {
        void onOutput(String text);
    }

    private final String shellCommand;
    private final CopyOnWriteArrayList<OutputListener> listeners = new CopyOnWriteArrayList<>();

    private Process process;
    private BufferedWriter writer;
    private Thread readerThread;

    public TerminalEngine(String shellCommand) {
        this.shellCommand = shellCommand;
    }

    public void addOutputListener(OutputListener l) {
        listeners.add(l);
    }

    public void removeOutputListener(OutputListener l) {
        listeners.remove(l);
    }

    private void fireOutput(String text) {
        for (OutputListener l : listeners) {
            l.onOutput(text);
        }
    }

    /**
     * Dynamically determines the appropriate charset for the current native shell environment.
     * Supports multi-platform accent processing on Windows, Linux, and macOS.
     */
    private Charset getShellCharset() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            // Check if Windows console is explicitly configured to handle global UTF-8 streams
            String stdoutEncoding = System.getProperty("sun.stdout.encoding");
            if (stdoutEncoding != null && stdoutEncoding.equalsIgnoreCase("UTF-8")) {
                return StandardCharsets.UTF_8;
            }
            // Standard fallback console OEM encoding for Western European/French Windows environments
            return Charset.forName("IBM850"); 
        }
        // macOS and Linux systems natively speak UTF-8
        return StandardCharsets.UTF_8;
    }

    public void start() {
        if (process != null) return;

        try {
            process = new ProcessBuilder(shellCommand)
                    .redirectErrorStream(true)
                    .start();

            // Detect shell encoding configuration once upon launch sequence
            Charset shellCharset = getShellCharset();

            // Explicitly set the stream writer charset so accents sent to the process do not corrupt
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), shellCharset));

            readerThread = new Thread(() -> {
                // Explicitly set the stream reader charset to interpret incoming process bytes correctly
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), shellCharset))) {

                    String line;
                    while ((line = br.readLine()) != null) {
                        fireOutput(line + "\n");
                    }

                } catch (IOException e) {
                    fireOutput("[!] Terminal stopped: " + e.getMessage() + "\n");
                }
            });

            readerThread.setDaemon(true);
            readerThread.start();

            fireOutput("[i] Terminal started: " + shellCommand + "\n");

        } catch (IOException e) {
            fireOutput("[!] Failed to start shell: " + e.getMessage() + "\n");
        }
    }

    public void sendCommand(String cmd) {
        if (cmd == null || cmd.isEmpty() || writer == null) return;

        try {
            writer.write(cmd);
            writer.newLine();
            writer.flush();
            fireOutput("> " + cmd + "\n");
        } catch (IOException e) {
            fireOutput("[!] Failed to send command: " + e.getMessage() + "\n");
        }
    }

    public void stop() {
        if (process != null) {
            process.destroy();
            process = null;
            fireOutput("[i] Terminal stopped.\n");
        }
    }

    public String getShellCommand() {
        return shellCommand;
    }
}
