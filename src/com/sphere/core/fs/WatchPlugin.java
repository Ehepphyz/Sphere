package com.sphere.core.fs;

import com.sphere.core.CommandRouter;
import com.sphere.utils.AppLogger;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * :watch -- rerun a console command on a timer while a job runs, so a :du or a
 * :ls of an output directory refreshes itself. One watch at a time; :watch-stop
 * ends it, and so does closing Sphere, since the thread is a daemon.
 */
public class WatchPlugin implements CommandRouter.CommandPlugin {

    private static final int DEFAULT_SECONDS = 5;
    private static final int MIN_SECONDS = 1;

    private final CommandRouter router;
    private final AtomicReference<Thread> watcher = new AtomicReference<>();

    public WatchPlugin(CommandRouter router) {
        this.router = router;
    }

    @Override
    public String getName() {
        return "watch";
    }

    @Override
    public boolean supports(String input) {
        if (input == null) return false;
        String t = input.trim();
        if (t.startsWith("::")) return false;
        return t.equals(":watch") || t.startsWith(":watch ") || t.equals(":watch-stop");
    }

    @Override
    public void execute(String input) {
        String trimmed = input.trim();

        if (trimmed.equals(":watch-stop")) {
            stop(true);
            return;
        }

        List<String> tokens = FsSupport.tokenize(trimmed);
        int seconds = DEFAULT_SECONDS;
        int first = 1;

        if (tokens.size() > 2 && (tokens.get(1).equals("-n") || tokens.get(1).equals("--interval"))) {
            try {
                seconds = Integer.parseInt(tokens.get(2));
            } catch (NumberFormatException bad) {
                AppLogger.error("Expected a number of seconds after " + tokens.get(1));
                return;
            }
            first = 3;
        }

        if (first >= tokens.size()) {
            AppLogger.raw("Usage: :watch [-n SECONDS] <command>");
            AppLogger.raw("  -n SECONDS    how often to rerun (default " + DEFAULT_SECONDS + ")");
            AppLogger.raw("  :watch-stop   stop watching");
            AppLogger.raw("Example:  :watch -n 10 :du -d 1 output");
            return;
        }

        if (seconds < MIN_SECONDS) {
            AppLogger.error("The interval must be at least " + MIN_SECONDS + " second.");
            return;
        }

        String command = String.join(" ", tokens.subList(first, tokens.size()));

        // A watch that watches itself would multiply without bound
        String head = command.trim();
        if (head.startsWith(":watch")) {
            AppLogger.error(":watch cannot watch itself.");
            return;
        }

        stop(false);
        AppLogger.info("Watching  " + command + "  every " + seconds
                       + "s. Type  :watch-stop  to stop.");

        final int interval = seconds;
        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                AppLogger.separator();
                try {
                    router.processInput(command);
                } catch (RuntimeException e) {
                    AppLogger.error("Watch stopped: " + e.getMessage());
                    return;
                }
                try {
                    Thread.sleep(interval * 1000L);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "sphere-watch");
        thread.setDaemon(true);
        watcher.set(thread);
        thread.start();
    }

    private void stop(boolean announce) {
        Thread thread = watcher.getAndSet(null);
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            if (announce) AppLogger.success("Stopped watching.");
        } else if (announce) {
            AppLogger.info("Nothing is being watched.");
        }
    }
}
