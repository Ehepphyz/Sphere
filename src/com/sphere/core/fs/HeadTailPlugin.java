package com.sphere.core.fs;

import com.sphere.core.CommandRouter;
import com.sphere.utils.AppLogger;

import javax.swing.SwingWorker;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * :head and :tail as their own names, plus :tail -f, which is the one that
 * earns its keep here: watching a Geant4 or ROOT job write its log while it
 * runs, without a second terminal.
 */
public class HeadTailPlugin implements CommandRouter.CommandPlugin {

    private static final int DEFAULT_LINES = 10;
    private static final long POLL_MILLIS = 400;

    private final CommandRouter router;
    private volatile Thread follower;

    public HeadTailPlugin(CommandRouter router) {
        this.router = router;
    }

    @Override
    public String getName() {
        return "head";
    }

    @Override
    public boolean supports(String input) {
        if (input == null) return false;
        String t = input.trim();
        if (t.startsWith("::")) return false;
        return t.equals(":head") || t.startsWith(":head ")
            || t.equals(":tail") || t.startsWith(":tail ")
            || t.equals(":tail-stop");
    }

    @Override
    public void execute(String input) {
        String trimmed = input.trim();

        if (trimmed.equals(":tail-stop")) {
            stopFollowing(true);
            return;
        }

        List<String> tokens = FsSupport.tokenize(trimmed);
        boolean tailing = tokens.get(0).equals(":tail");

        int count = DEFAULT_LINES;
        boolean follow = false;
        List<String> operands = new ArrayList<>();

        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("--help")) { usage(tailing); return; }
            if (t.equals("-f") || t.equals("--follow")) { follow = true; continue; }
            if (t.equals("-n")) {
                if (i + 1 >= tokens.size()) { AppLogger.error("Missing value after -n"); return; }
                try {
                    count = Integer.parseInt(tokens.get(++i));
                } catch (NumberFormatException bad) {
                    AppLogger.error("Expected a number after -n");
                    return;
                }
                continue;
            }
            if (t.startsWith("-n")) {
                try {
                    count = Integer.parseInt(t.substring(2));
                } catch (NumberFormatException bad) {
                    AppLogger.error("Expected a number after -n");
                    return;
                }
                continue;
            }
            if (t.matches("-\\d+")) { count = Integer.parseInt(t.substring(1)); continue; }
            operands.add(t);
        }

        if (operands.isEmpty()) { usage(tailing); return; }
        if (count <= 0) { AppLogger.error("The number of lines must be positive."); return; }

        Path file = FsSupport.resolve(router, String.join(" ", operands));
        if (!Files.exists(file)) { AppLogger.error("File not found: " + file); return; }
        if (Files.isDirectory(file)) { AppLogger.error("Not a file: " + file); return; }
        if (FsSupport.looksBinary(file)) {
            AppLogger.error("This looks like a binary file: " + file.getFileName());
            return;
        }

        final int finalCount = count;
        final Path finalFile = file;
        final boolean finalTail = tailing;
        final boolean finalFollow = follow && tailing;

        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                try {
                    if (finalTail) publishTail(finalFile, finalCount, this::publish);
                    else publishHead(finalFile, finalCount, this::publish);
                } catch (IOException e) {
                    publish("[!] " + e.getMessage());
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String line : chunks) {
                    if (line.startsWith("[!] ")) AppLogger.error(line.substring(4));
                    else AppLogger.raw(line);
                }
            }

            @Override
            protected void done() {
                if (finalFollow) startFollowing(finalFile);
            }
        }.execute();
    }

    private void publishHead(Path file, int count, java.util.function.Consumer<String> out)
            throws IOException {
        try (BufferedReader reader = FsSupport.utf8Reader(file)) {
            String line;
            int shown = 0;
            while (shown < count && (line = reader.readLine()) != null) {
                out.accept(line);
                shown++;
            }
            if (reader.readLine() != null) out.accept("[Output truncated]");
        }
    }

    private void publishTail(Path file, int count, java.util.function.Consumer<String> out)
            throws IOException {
        Deque<String> window = new ArrayDeque<>(count);
        try (BufferedReader reader = FsSupport.utf8Reader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (window.size() == count) window.removeFirst();
                window.addLast(line);
            }
        }
        window.forEach(out);
    }

    /** Polls the file for growth. Stopped by :tail-stop or by the next :tail -f. */
    private void startFollowing(Path file) {
        stopFollowing(false);
        AppLogger.info("Following " + file.getFileName() + ". Type  :tail-stop  to stop.");

        Thread thread = new Thread(() -> {
            long position = 0;
            try {
                position = Files.size(file);
            } catch (IOException ignored) {
                // A file that vanished is reported by the read loop below
            }
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(POLL_MILLIS);
                    long size = Files.size(file);
                    if (size < position) {
                        // The file was truncated or rotated: start over
                        position = 0;
                    }
                    if (size > position) {
                        try (var channel = Files.newByteChannel(file)) {
                            channel.position(position);
                            var buffer = java.nio.ByteBuffer.allocate((int) Math.min(
                                    size - position, 1 << 20));
                            int read = channel.read(buffer);
                            if (read > 0) {
                                String chunk = new String(buffer.array(), 0, read,
                                        java.nio.charset.StandardCharsets.UTF_8);
                                for (String line : chunk.split("\n", -1)) {
                                    if (!line.isEmpty()) AppLogger.raw(line.replace("\r", ""));
                                }
                                position += read;
                            }
                        }
                    }
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException e) {
                    AppLogger.error("Stopped following " + file.getFileName() + ": " + e.getMessage());
                    return;
                }
            }
        }, "sphere-tail-follow");
        thread.setDaemon(true);
        follower = thread;
        thread.start();
    }

    private void stopFollowing(boolean announce) {
        Thread thread = follower;
        follower = null;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            if (announce) AppLogger.success("Stopped following.");
        } else if (announce) {
            AppLogger.info("Nothing is being followed.");
        }
    }

    private void usage(boolean tailing) {
        String name = tailing ? ":tail" : ":head";
        AppLogger.raw("Usage: " + name + " [-n N] " + (tailing ? "[-f] " : "") + "<file>");
        AppLogger.raw("Options:");
        AppLogger.raw("  -n N          number of lines (default " + DEFAULT_LINES + ")");
        if (tailing) {
            AppLogger.raw("  -f            keep printing as the file grows");
            AppLogger.raw("  :tail-stop    stop following");
        }
        AppLogger.raw("  --help        show this help");
    }
}
