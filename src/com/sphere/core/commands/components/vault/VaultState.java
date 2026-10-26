package com.sphere.components.vault;

import com.sphere.utils.AppLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * What the vault remembers between sessions: how often and how lately each item
 * was run, which ones are pinned, and what was last typed in its form.
 *
 * Use is counted as frecency, a score that gains one at each run and halves
 * every three days, so what is run today outranks what was run often a month
 * ago. The file is written a moment after the last change, off the event thread.
 */
final class VaultState {

    private static final long HALF_LIFE_MS = 3L * 24 * 3600 * 1000;
    private static final String SEPARATOR = "\u001F";

    private final Path file;
    private final Properties props = new Properties();
    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "sphere-vault-state");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pending;

    VaultState() {
        this(Paths.get(System.getProperty("user.dir"), "config", "vault_state.properties"));
    }

    VaultState(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException | IllegalArgumentException unreadable) {
                AppLogger.warn("The Code Vault state could not be read: " + unreadable.getMessage());
            }
        }
    }

    // ---- use --------------------------------------------------------------

    synchronized double frecency(String key, long now) {
        final double score = number(key, "score");
        if (score <= 0) return 0;
        final long last = (long) number(key, "last");
        return score * Math.pow(0.5, Math.max(0, now - last) / (double) HALF_LIFE_MS);
    }

    synchronized void used(String key) {
        final long now = System.currentTimeMillis();
        final double score = frecency(key, now) + 1.0;
        props.setProperty(key + ".score", String.format(java.util.Locale.ROOT, "%.4f", score));
        props.setProperty(key + ".last", Long.toString(now));
        props.setProperty(key + ".runs", Integer.toString(runs(key) + 1));
        saveSoon();
    }

    synchronized int runs(String key) {
        return (int) number(key, "runs");
    }

    synchronized long lastRun(String key) {
        return (long) number(key, "last");
    }

    // ---- pins ---------------------------------------------------------------

    synchronized boolean pinned(String key) {
        return "true".equals(props.getProperty(key + ".pin"));
    }

    synchronized void setPinned(String key, boolean pin) {
        if (pin) props.setProperty(key + ".pin", "true");
        else props.remove(key + ".pin");
        saveSoon();
    }

    // ---- what the form held -------------------------------------------------

    synchronized String get(String key, String field) {
        return props.getProperty(key + "." + field, "");
    }

    synchronized void put(String key, String field, String value) {
        if (value == null || value.isEmpty()) props.remove(key + "." + field);
        else props.setProperty(key + "." + field, value);
        saveSoon();
    }

    synchronized List<String> values(String key) {
        final String raw = props.getProperty(key + ".values");
        if (raw == null || raw.isEmpty()) return List.of();
        return new ArrayList<>(Arrays.asList(raw.split(SEPARATOR, -1)));
    }

    synchronized void putValues(String key, List<String> values) {
        boolean any = false;
        for (String v : values) any |= v != null && !v.isEmpty();
        if (!any) props.remove(key + ".values");
        else props.setProperty(key + ".values", String.join(SEPARATOR, values));
        saveSoon();
    }

    /** Carries everything known about an item to its new name, after a rename. */
    synchronized void rename(String from, String to) {
        final String prefix = from + ".";
        for (String name : new ArrayList<>(props.stringPropertyNames())) {
            if (name.startsWith(prefix)) {
                props.setProperty(to + "." + name.substring(prefix.length()), props.getProperty(name));
                props.remove(name);
            }
        }
        saveSoon();
    }

    // ---- disk -------------------------------------------------------------------

    private double number(String key, String field) {
        try {
            return Double.parseDouble(props.getProperty(key + "." + field, "0"));
        } catch (NumberFormatException bad) {
            return 0;
        }
    }

    private void saveSoon() {
        if (pending != null) pending.cancel(false);
        pending = saver.schedule(this::save, 600, TimeUnit.MILLISECONDS);
    }

    private void save() {
        final Properties copy = new Properties();
        synchronized (this) {
            copy.putAll(props);
        }
        try {
            Files.createDirectories(file.getParent());
            final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                copy.store(out, "Sphere Code Vault: use, pins and the last values of each form");
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | UnsupportedOperationException unwritable) {
            AppLogger.warn("The Code Vault state could not be saved: " + unwritable.getMessage());
        }
    }
}
