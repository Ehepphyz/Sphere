package com.sphere.core.bridge;

import com.sphere.components.rootview.RootAxis;
import com.sphere.components.rootview.RootGraph;
import com.sphere.components.rootview.RootHistogram;
import com.sphere.components.rootview.RootPlotsPanel;
import com.sphere.utils.AppLogger;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The way back: what any engine publishes lands in the Plots tab by itself.
 *
 * A Julia session, a Fortran run, a C++ program or a ROOT macro calls its
 * library's publish(), which writes a table into the bridge's outbox; this
 * watches the outbox and draws each table as it arrives. Nothing has to be
 * typed on the Sphere side, and the result arrives as the numbers the engine
 * computed, not as a picture of them.
 */
public final class BridgeOutbox {

    private static volatile Thread watcher;
    private static volatile WatchService service;
    /** When each file was last drawn, so one write seen twice is drawn once. */
    private static final Map<Path, Long> SHOWN = new ConcurrentHashMap<>();

    private BridgeOutbox() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static boolean running() {
        final Thread t = watcher;
        return t != null && t.isAlive();
    }

    /** Starts watching the outbox, once. */
    public static synchronized void start() {
        if (running()) return;
        try {
            final Path box = Bridge.outbox();
            Files.createDirectories(box);
            service = FileSystems.getDefault().newWatchService();
            box.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            final Thread t = new Thread(() -> watch(box), "sphere-bridge-outbox");
            t.setDaemon(true);
            t.start();
            watcher = t;
        } catch (IOException | UnsupportedOperationException unavailable) {
            AppLogger.warn("The bridge outbox cannot be watched: " + unavailable.getMessage());
        }
    }

    public static synchronized void stop() {
        try {
            if (service != null) service.close();
        } catch (IOException ignored) {
            // Closing is all that was wanted.
        }
        service = null;
        watcher = null;
    }

    private static void watch(Path box) {
        final WatchService ws = service;
        while (ws != null) {
            final WatchKey key;
            try {
                key = ws.take();
            } catch (InterruptedException | ClosedWatchServiceException stopped) {
                return;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                if (!(event.context() instanceof Path name)) continue;
                if (!name.toString().endsWith(".spx")) continue;
                final Path file = box.resolve(name);
                // The writer may still be at it: give it a moment, then read
                // only once the file has reached the size its header states.
                for (int attempt = 0; attempt < 20; attempt++) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException stopped) {
                        return;
                    }
                    if (complete(file)) {
                        show(file);
                        break;
                    }
                }
            }
            if (!key.reset()) return;
        }
    }

    /** True when the file is as long as its header says it will be. */
    static boolean complete(Path file) {
        try (FileChannel in = FileChannel.open(file, StandardOpenOption.READ)) {
            if (in.size() < 64) return false;
            final java.nio.ByteBuffer head = java.nio.ByteBuffer.allocate(40).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            in.read(head, 0);
            return head.getLong(32) <= in.size();
        } catch (IOException notYet) {
            return false;
        }
    }

    /** Draws one published table, if it has not been drawn in this form already. */
    public static void show(Path file) {
        try {
            final long stamp = Files.getLastModifiedTime(file).toMillis() ^ Files.size(file);
            final Long before = SHOWN.put(file, stamp);
            if (before != null && before == stamp) return;
            try (Spx.Reader r = new Spx.Reader(file)) {
                draw(r, file);
            }
        } catch (IOException | RuntimeException unreadable) {
            AppLogger.warn("Could not read " + file.getFileName() + " from the outbox: " + unreadable.getMessage());
        }
    }

    /**
     * A table drawn the way it asks: a histogram when it has edges and values,
     * with its band as asymmetric errors when it carries one, otherwise the
     * first two columns against each other.
     */
    public static void draw(Spx.Reader r, Path file) throws IOException {
        final Map<String, String> meta = r.meta();
        final String title = meta.getOrDefault("Title", r.title());
        final String engine = meta.getOrDefault("Engine", "an engine");
        if (r.has("edges") && r.has("values")) {
            final double[] edges = r.numbers("edges");
            final double[] values = r.numbers("values");
            if (edges.length != values.length + 1) {
                throw new IOException("edges must hold one more value than values");
            }
            if (r.has("err_plus")) {
                final double[] up = r.numbers("err_plus");
                final double[] down = r.has("err_minus") ? r.numbers("err_minus") : up;
                final RootGraph g = new RootGraph();
                g.name = title;
                g.title = title;
                g.className = "TGraphAsymmErrors";
                final int n = values.length;
                g.x = new double[n];
                g.y = values.clone();
                g.exLow = new double[n];
                g.exHigh = new double[n];
                g.eyLow = new double[n];
                g.eyHigh = new double[n];
                for (int k = 0; k < n; k++) {
                    g.x[k] = 0.5 * (edges[k] + edges[k + 1]);
                    g.exLow[k] = g.x[k] - edges[k];
                    g.exHigh[k] = edges[k + 1] - g.x[k];
                    g.eyLow[k] = down[k];
                    g.eyHigh[k] = up[k];
                }
                RootPlotsPanel.instance().showGraph(g, title);
            } else {
                RootPlotsPanel.instance().showHistogram(histogram(title, meta.getOrDefault("XLabel", title),
                    edges, values), title);
            }
            AppLogger.info(engine + " published " + title + " (" + values.length + " bins) -> Plots.");
            return;
        }
        final java.util.List<Spx.Section> numeric = r.sections().stream()
            .filter(s -> s.type() != Spx.TEXT).toList();
        if (numeric.size() >= 2) {
            final double[] x = r.numbers(numeric.get(0).name());
            final double[] y = r.numbers(numeric.get(1).name());
            RootPlotsPanel.instance().showCurve(numeric.get(0).name(), numeric.get(1).name(), x, y);
            AppLogger.info(engine + " published " + title + " (" + numeric.get(1).name() + " against "
                + numeric.get(0).name() + ") -> Plots.");
        } else if (numeric.size() == 1) {
            RootPlotsPanel.instance().showColumn(title, r.numbers(numeric.get(0).name()));
            AppLogger.info(engine + " published " + title + " -> Plots.");
        } else {
            AppLogger.info(engine + " published " + file.getFileName() + ", which holds nothing to draw.");
        }
    }

    /** A histogram with the given edges, uneven ones included. */
    public static RootHistogram histogram(String name, String xLabel, double[] edges, double[] values) {
        final RootHistogram h = new RootHistogram();
        final int n = values.length;
        h.name = name;
        h.title = name;
        h.className = "TH1D";
        h.dimensions = 1;
        h.xAxis = new RootAxis();
        h.xAxis.name = xLabel;
        h.xAxis.title = xLabel;
        h.xAxis.bins = n;
        h.xAxis.min = edges[0];
        h.xAxis.max = edges[n];
        h.xAxis.edges = edges.clone();
        h.contents = new double[n + 2];
        double sum = 0;
        double sumx = 0;
        double sumx2 = 0;
        for (int k = 0; k < n; k++) {
            h.contents[k + 1] = values[k];
            final double c = 0.5 * (edges[k] + edges[k + 1]);
            sum += values[k];
            sumx += values[k] * c;
            sumx2 += values[k] * c * c;
        }
        h.entries = sum;
        h.tsumw = sum;
        h.tsumw2 = sum;
        h.tsumwx = sumx;
        h.tsumwx2 = sumx2;
        return h;
    }
}
