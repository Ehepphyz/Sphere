package com.sphere.components.variables;

import com.sphere.utils.AppLogger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Watches folders and publishes the variable files that appear or change.
 *
 * Nothing here knows which language wrote the file. A program that has ended
 * cannot be asked what it held, so what it left on disk is all there is, and
 * that is what is watched. A file is only read once its size and date have
 * stopped moving, which keeps one still being written from being read halfway.
 */
public final class VariableWatch {

    /** A cap per folder, so a directory of thousands of files stays cheap. */
    private static final int MAX_FILES = 500;

    private final Set<Path> folders = new LinkedHashSet<>();
    /** Folders whose contents have still to be read. */
    private final Set<Path> pending = new LinkedHashSet<>();
    /** Stamp seen on the previous pass, to tell a finished file from a growing one. */
    private final Map<String, String> previous = new LinkedHashMap<>();
    /** Last stamp published for a file. */
    private final Map<String, String> published = new LinkedHashMap<>();

    private Thread worker;
    private volatile boolean running;
    private volatile long intervalMillis = 1500;

    public synchronized void add(Path directory) {
        if (directory != null && directory.toFile().isDirectory()) {
            final Path folder = directory.toAbsolutePath().normalize();
            // Only a folder that was not there yet has to be read in full; adding
            // one twice must not make every pass republish its whole contents.
            if (folders.add(folder)) {
                pending.add(folder);
            }
        }
    }

    public synchronized boolean remove(Path directory) {
        return directory != null
            && folders.remove(directory.toAbsolutePath().normalize());
    }

    public synchronized List<Path> folders() {
        return new ArrayList<>(folders);
    }

    public void setInterval(long millis) {
        this.intervalMillis = Math.max(200, millis);
    }

    /**
     * One pass over the watched folders.
     *
     * A folder just added is read in full, so opening a project shows what its
     * last run left behind rather than waiting for the next one.
     */
    public synchronized int scan() {
        int published = 0;
        List<Path> toRead = new ArrayList<>(pending);
        pending.clear();

        for (Path folder : folders) {
            final boolean firstPass = toRead.contains(folder);
            for (File file : variableFilesIn(folder)) {
                final String key = file.getAbsolutePath();
                final String stamp = stamp(file);
                final String before = previous.put(key, stamp);
                final boolean settled = firstPass || stamp.equals(before);
                if (!settled || stamp.equals(this.published.get(key))) {
                    continue;
                }
                this.published.put(key, stamp);
                if (publish(file.toPath())) {
                    published++;
                }
            }
        }
        return published;
    }

    private static boolean publish(Path file) {
        try {
            VariableStore.publish(VariableFile.sourceOf(file), VariableFile.read(file));
            return true;
        } catch (IOException unreadable) {
            AppLogger.error("Could not read the variables in " + file + ": "
                            + unreadable.getMessage());
            return false;
        }
    }

    /**
     * Reads every variable file at once, whether or not it has settled.
     *
     * The waiting pass exists for a file still being written by a program that
     * is running. Someone who presses the refresh has finished waiting, so the
     * files are taken as they stand; a truncated line is skipped when read, and
     * the next pass corrects whatever the file becomes.
     */
    public synchronized int readNow() {
        int published = 0;
        pending.clear();
        for (Path folder : folders) {
            for (File file : variableFilesIn(folder)) {
                final String stamp = stamp(file);
                previous.put(file.getAbsolutePath(), stamp);
                this.published.put(file.getAbsolutePath(), stamp);
                if (publish(file.toPath())) {
                    published++;
                }
            }
        }
        return published;
    }

    /**
     * Removes what a source left on disk, so that clearing it holds.
     *
     * Emptying the table alone was not enough: the file the run wrote is still
     * there, and the next pass over the folder publishes it again. A null source
     * removes every variable file.
     */
    public synchronized int forget(String source) {
        int removed = 0;
        for (Path folder : folders) {
            for (File file : variableFilesIn(folder)) {
                final String owner = VariableFile.sourceOf(file.toPath());
                if (source != null && !source.equalsIgnoreCase(owner)) {
                    continue;
                }
                previous.remove(file.getAbsolutePath());
                published.remove(file.getAbsolutePath());
                if (file.delete()) {
                    removed++;
                } else {
                    AppLogger.error("Could not remove " + file);
                }
            }
        }
        return removed;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::loop, "sphere-variable-watch");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            }
            scan();
        }
    }

    private static List<File> variableFilesIn(Path directory) {
        List<File> found = new ArrayList<>();
        if (directory == null) {
            return found;
        }
        File[] entries = directory.toFile().listFiles();
        if (entries == null) {
            return found;
        }
        int looked = 0;
        for (File entry : entries) {
            if (looked++ >= MAX_FILES) {
                break;
            }
            if (entry.isFile() && VariableFile.isVariableFile(entry.toPath())) {
                found.add(entry);
            }
        }
        return found;
    }

    private static String stamp(File file) {
        return file.lastModified() + ":" + file.length();
    }
}
