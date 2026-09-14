package com.sphere.components.rootview;

import com.sphere.components.imaging.ImageFileIO;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Watches folders and reports the image files that appear or change.
 *
 * Nothing here knows which language drew the picture. ROOT, python, C++, Julia,
 * Fortran and Sphere itself all finish by writing a file, and that is the one
 * thing they have in common, so that is what is watched. A file is only
 * reported once its size and date have stopped moving, which keeps a picture
 * still being written from being read half way.
 */
public final class RootImageWatch {

    /** Told about a picture that has finished being written. */
    public interface Listener {
        void imageAppeared(File file);
    }

    /** A cap per folder, so a directory of thousands of files stays cheap. */
    private static final int MAX_FILES = 2000;

    private final Listener listener;
    private final Set<Path> folders = new LinkedHashSet<>();
    /** Last stamp reported for a file. */
    private final Map<String, String> reported = new LinkedHashMap<>();
    /** Stamp seen on the previous pass, to tell a finished file from a growing one. */
    private final Map<String, String> previous = new LinkedHashMap<>();

    private Supplier<Path> following;
    private Path followed;
    private Thread worker;
    private volatile boolean running;
    private volatile long intervalMillis = 2000;

    public RootImageWatch(Listener listener) {
        this.listener = listener;
    }

    /** A folder that moves under the watch, such as the console's own. */
    public void follow(Supplier<Path> directory) {
        this.following = directory;
    }

    public synchronized void add(Path directory) {
        if (directory != null && directory.toFile().isDirectory()) {
            folders.add(directory.toAbsolutePath().normalize());
            prime(directory.toAbsolutePath().normalize());
        }
    }

    public synchronized boolean remove(Path directory) {
        return directory != null
            && folders.remove(directory.toAbsolutePath().normalize());
    }

    /** The folders being watched, the followed one first. */
    public synchronized List<Path> folders() {
        List<Path> all = new ArrayList<>();
        final Path moving = following == null ? null : following.get();
        if (moving != null) {
            all.add(moving.toAbsolutePath().normalize());
        }
        for (Path folder : folders) {
            if (!all.contains(folder)) {
                all.add(folder);
            }
        }
        return all;
    }

    public void setInterval(long millis) {
        this.intervalMillis = Math.max(200, millis);
    }

    /**
     * Marks a file as already accounted for.
     *
     * Sphere writes pictures into the watched folder itself, and the watch has
     * no business announcing back what Sphere just put there.
     */
    public synchronized void ignore(File file) {
        if (file != null && file.isFile()) {
            final String key = file.getAbsolutePath();
            previous.put(key, stamp(file));
            reported.put(key, stamp(file));
        }
    }

    /** Records what a folder already holds, so only what comes after is reported. */
    public synchronized void prime(Path directory) {
        for (File image : imagesIn(directory)) {
            final String key = image.getAbsolutePath();
            final String stamp = stamp(image);
            previous.put(key, stamp);
            reported.put(key, stamp);
        }
    }

    public synchronized void primeAll() {
        for (Path folder : folders()) {
            prime(folder);
        }
        followed = following == null ? null : following.get();
    }

    /**
     * One pass over the watched folders.
     *
     * A file is handed back when its stamp has been the same twice running and
     * differs from what was last reported for it.
     */
    public synchronized List<File> scan() {
        List<File> found = new ArrayList<>();

        // Moving into another folder should not pour its whole contents in.
        final Path moving = following == null ? null : following.get();
        if (moving != null && !moving.equals(followed)) {
            followed = moving;
            prime(moving);
            return found;
        }

        for (Path folder : folders()) {
            for (File image : imagesIn(folder)) {
                final String key = image.getAbsolutePath();
                final String stamp = stamp(image);
                final String before = previous.put(key, stamp);
                if (!stamp.equals(before) || stamp.equals(reported.get(key))) {
                    continue;
                }
                reported.put(key, stamp);
                found.add(image);
            }
        }
        return found;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        primeAll();
        running = true;
        worker = new Thread(this::loop, "sphere-plot-watch");
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
            for (File image : scan()) {
                listener.imageAppeared(image);
            }
        }
    }

    private static List<File> imagesIn(Path directory) {
        List<File> images = new ArrayList<>();
        if (directory == null) {
            return images;
        }
        File[] entries = directory.toFile().listFiles();
        if (entries == null) {
            return images;
        }
        int looked = 0;
        for (File entry : entries) {
            if (looked++ >= MAX_FILES) {
                break;
            }
            if (entry.isFile() && ImageFileIO.isImage(entry)) {
                images.add(entry);
            }
        }
        return images;
    }

    private static String stamp(File file) {
        return file.lastModified() + ":" + file.length();
    }
}
