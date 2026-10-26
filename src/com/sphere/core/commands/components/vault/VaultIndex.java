package com.sphere.components.vault;

import com.sphere.core.rootbackend.RootUserPipeline;
import com.sphere.utils.AppLogger;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Everything in snippets/ and user_scripts/, read once and kept current.
 *
 * Four folders are covered: the global snippets/ beside Sphere and the active
 * project's, the global rootbackend/user_scripts/ and the project's, which are
 * the layers SnippetResolver and RootUserPipeline already search. A file is read
 * again only when its size or date changed, so a rescan after an edit costs one
 * file, and the folders are watched, so what is written by the editor, a
 * terminal or a script shows up by itself.
 *
 * Files with the same content are twins: the index groups them, since copies
 * that drift apart are how a fixed bug comes back.
 */
public final class VaultIndex {

    public interface Listener {
        void vaultChanged();
    }

    public record Root(Path dir, VaultItem.Kind kind, VaultItem.Layer layer) {
    }

    public record Stats(int items, int snippets, int macros, int twins, int problems, long millis) {
        static final Stats EMPTY = new Stats(0, 0, 0, 0, 0, 0);
    }

    static final String TRASH = ".vault-trash";
    private static final int MAX_ITEMS = 4000;
    private static final int MAX_DEPTH = 8;
    private static final Set<String> SKIPPED_FOLDERS = Set.of(
        "__pycache__", "node_modules", "build", "cmake-build-debug", "cmake-build-release", ".git");
    /** What ACLiC, compilers and runs leave beside a macro: not something to run. */
    private static final Set<String> SKIPPED_EXTENSIONS = Set.of(
        "so", "dll", "dylib", "d", "pcm", "o", "obj", "lib", "exp", "pdb", "pyc", "pyo", "exe",
        "root", "png", "jpg", "jpeg", "gif", "bmp", "pdf", "eps", "ps", "tiff", "zip", "gz", "tar",
        "rootmap", "class", "jar", "spx", "vars", "log", "tmp", "swp", "bak");

    private static VaultIndex shared;

    private final VaultState state = new VaultState();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(daemon("sphere-vault-index"));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(daemon("sphere-vault-timer"));
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Read and written on the worker only. */
    private final Map<Path, VaultItem> cache = new HashMap<>();
    private final Map<WatchKey, Path> watched = new HashMap<>();
    private final Set<Path> watchedDirs = new HashSet<>();

    private volatile List<VaultItem> items = List.of();
    private volatile Map<Long, List<VaultItem>> twins = Map.of();
    private volatile List<Root> roots = List.of();
    private volatile Stats stats = Stats.EMPTY;
    private volatile String project;
    private volatile boolean started;

    private WatchService watcher;
    private ScheduledFuture<?> pendingScan;

    public static synchronized VaultIndex shared() {
        if (shared == null) {
            shared = new VaultIndex();
        }
        return shared;
    }

    private VaultIndex() {
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            final Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };
    }

    /** Reads the folders for the first time and starts watching them. Safe to call twice. */
    public synchronized void start() {
        if (started) return;
        started = true;
        project = activeProject();
        try {
            watcher = FileSystems.getDefault().newWatchService();
            final Thread loop = new Thread(this::watchLoop, "sphere-vault-watch");
            loop.setDaemon(true);
            loop.start();
        } catch (IOException | UnsupportedOperationException noWatch) {
            AppLogger.warn("The Code Vault cannot watch its folders (" + noWatch.getMessage()
                + "): its list is refreshed when asked.");
        }
        rescan();
        // A project opened from the console moves two of the four folders.
        timer.scheduleWithFixedDelay(() -> {
            final String now = activeProject();
            if (!Objects.equals(now, project)) {
                project = now;
                rescan();
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    // ---- what the rest of Sphere reads ------------------------------------------

    public List<VaultItem> items() {
        return items;
    }

    public Stats stats() {
        return stats;
    }

    public List<Root> roots() {
        return roots;
    }

    public String project() {
        return project;
    }

    VaultState state() {
        return state;
    }

    /** The other files holding the same content, empty when there are none. */
    public List<VaultItem> twinsOf(VaultItem item) {
        if (item == null || item.info.hash == 0) return List.of();
        final List<VaultItem> group = twins.get(item.info.hash);
        if (group == null) return List.of();
        final List<VaultItem> others = new ArrayList<>(group.size() - 1);
        for (VaultItem one : group) {
            if (!one.path.equals(item.path)) others.add(one);
        }
        return others;
    }

    public Map<Long, List<VaultItem>> twinGroups() {
        return twins;
    }

    /** The item for a file, when the index has it. */
    public VaultItem find(Path path) {
        if (path == null) return null;
        final Path wanted = path.toAbsolutePath().normalize();
        for (VaultItem item : items) {
            if (item.path.equals(wanted)) return item;
        }
        return null;
    }

    /** The folder new items of a kind go into, made when missing. */
    public Path rootFor(VaultItem.Kind kind, VaultItem.Layer layer) {
        final Path base = layer == VaultItem.Layer.PROJECT ? projectBase(project) : null;
        final Path dir;
        if (kind == VaultItem.Kind.SNIPPET) {
            dir = (base != null ? base : Paths.get(System.getProperty("user.dir"))).resolve("snippets");
        } else {
            dir = (base != null ? base : RootUserPipeline.globalRoot()).resolve(RootUserPipeline.SCRIPTS_DIR);
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException unwritable) {
            AppLogger.warn("Could not create " + dir + ": " + unwritable.getMessage());
        }
        return dir.toAbsolutePath().normalize();
    }

    /** The root a path lives under, or null when it is outside all four. */
    public Root rootOf(Path path) {
        final Path p = path.toAbsolutePath().normalize();
        Root best = null;
        for (Root root : roots) {
            if (p.startsWith(root.dir()) && (best == null || root.dir().getNameCount() > best.dir().getNameCount())) {
                best = root;
            }
        }
        return best;
    }

    // ---- scanning -----------------------------------------------------------------

    /** Reads the folders again, on the index's own thread. */
    public Future<?> rescan() {
        return worker.submit(this::scan);
    }

    private synchronized void rescanSoon() {
        if (pendingScan != null) pendingScan.cancel(false);
        pendingScan = timer.schedule(() -> { rescan(); }, 220, TimeUnit.MILLISECONDS);
    }

    private void scan() {
        final long start = System.nanoTime();
        final List<Root> now = computeRoots(project);
        final List<VaultItem> found = new ArrayList<>();
        final Set<Path> seen = new HashSet<>();
        final Set<Path> dirs = new HashSet<>();

        for (Root root : now) {
            if (!Files.isDirectory(root.dir())) continue;
            try {
                Files.walkFileTree(root.dir(), EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            final String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                            if (!dir.equals(root.dir()) && (name.startsWith(".") || SKIPPED_FOLDERS.contains(name))) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            dirs.add(dir.toAbsolutePath().normalize());
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (found.size() >= MAX_ITEMS) return FileVisitResult.TERMINATE;
                            if (!attrs.isRegularFile() || !wanted(file)) return FileVisitResult.CONTINUE;
                            final Path abs = file.toAbsolutePath().normalize();
                            if (!seen.add(abs)) return FileVisitResult.CONTINUE;
                            found.add(itemFor(abs, root, attrs.size(), attrs.lastModifiedTime().toMillis()));
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
            } catch (IOException unreadable) {
                AppLogger.warn("Code Vault: could not read " + root.dir() + ": " + unreadable.getMessage());
            }
        }

        cache.keySet().retainAll(seen);
        watch(dirs);

        final Map<Long, List<VaultItem>> groups = new LinkedHashMap<>();
        for (VaultItem item : found) {
            if (item.info.hash != 0) groups.computeIfAbsent(item.info.hash, h -> new ArrayList<>()).add(item);
        }
        groups.values().removeIf(group -> group.size() < 2);
        int twinCount = 0;
        for (List<VaultItem> group : groups.values()) twinCount += group.size();

        int snippets = 0, macros = 0, problems = 0;
        for (VaultItem item : found) {
            if (item.kind == VaultItem.Kind.SNIPPET) snippets++;
            else macros++;
            if (item.info.hasProblems()) problems++;
        }

        roots = List.copyOf(now);
        items = Collections.unmodifiableList(found);
        twins = Collections.unmodifiableMap(groups);
        stats = new Stats(found.size(), snippets, macros, twinCount, problems,
            (System.nanoTime() - start) / 1_000_000L);
        publish();
    }

    private VaultItem itemFor(Path file, Root root, long size, long modified) {
        final VaultItem cached = cache.get(file);
        if (cached != null && cached.size == size && cached.modified == modified
                && cached.kind == root.kind() && cached.layer == root.layer()) {
            return cached;
        }
        final String text = VaultAnalyzer.read(file);
        VaultAnalysis info;
        try {
            info = VaultAnalyzer.analyze(file, root.kind(), text);
        } catch (RuntimeException failed) {
            // One file the reader trips on must not cost the whole list.
            info = VaultAnalyzer.analyze(file, root.kind(), null);
            AppLogger.debug("Code Vault could not analyze " + file + ": " + failed);
        }
        final VaultItem item = new VaultItem(file, root.dir(), root.kind(), root.layer(), size, modified, info);
        cache.put(file, item);
        return item;
    }

    private static boolean wanted(Path file) {
        final String name = file.getFileName().toString();
        if (name.startsWith(".") || name.startsWith("sphere_launch_") || name.contains("_ACLiC_dict")) {
            return false;
        }
        final int dot = name.lastIndexOf('.');
        if (dot > 0 && SKIPPED_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            return false;
        }
        return !name.endsWith("~");
    }

    private static List<Root> computeRoots(String project) {
        final List<Root> list = new ArrayList<>(4);
        final Path here = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        final Path globalSnippets = here.resolve("snippets");
        final Path globalMacros = RootUserPipeline.globalRoot().resolve(RootUserPipeline.SCRIPTS_DIR);
        for (Path dir : new Path[]{globalSnippets, globalMacros}) {
            try {
                Files.createDirectories(dir);
            } catch (IOException ignored) {
                // Listed empty until it can be made.
            }
        }
        list.add(new Root(globalSnippets, VaultItem.Kind.SNIPPET, VaultItem.Layer.GLOBAL));
        list.add(new Root(globalMacros.toAbsolutePath().normalize(), VaultItem.Kind.MACRO, VaultItem.Layer.GLOBAL));
        final Path base = projectBase(project);
        if (base != null) {
            final Path ps = base.resolve("snippets").toAbsolutePath().normalize();
            final Path pm = base.resolve(RootUserPipeline.SCRIPTS_DIR).toAbsolutePath().normalize();
            if (!ps.equals(globalSnippets)) list.add(new Root(ps, VaultItem.Kind.SNIPPET, VaultItem.Layer.PROJECT));
            if (!pm.equals(globalMacros)) list.add(new Root(pm, VaultItem.Kind.MACRO, VaultItem.Layer.PROJECT));
        }
        return list;
    }

    private static Path projectBase(String project) {
        return RootUserPipeline.projectRoot(project);
    }

    static String activeProject() {
        try {
            final com.sphere.core.CommandRouter router = com.sphere.core.CommandRouter.active();
            if (router == null || router.getCommandContext() == null) return null;
            final String name = router.getCommandContext().getActiveProject();
            return name == null || name.isBlank() ? null : name;
        } catch (RuntimeException notYet) {
            return null;
        }
    }

    // ---- watching -------------------------------------------------------------------

    private void watch(Set<Path> dirs) {
        if (watcher == null) return;
        watched.entrySet().removeIf(entry -> {
            if (!dirs.contains(entry.getValue()) || !entry.getKey().isValid()) {
                entry.getKey().cancel();
                watchedDirs.remove(entry.getValue());
                return true;
            }
            return false;
        });
        for (Path dir : dirs) {
            if (watchedDirs.contains(dir)) continue;
            try {
                final WatchKey key = dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
                watched.put(key, dir);
                watchedDirs.add(dir);
            } catch (IOException | RuntimeException cannot) {
                // Listed still, refreshed at the next scan.
            }
        }
    }

    private void watchLoop() {
        while (true) {
            final WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException | ClosedWatchServiceException stopped) {
                return;
            }
            boolean relevant = false;
            for (var event : key.pollEvents()) {
                final Object context = event.context();
                final String name = context == null ? "" : context.toString();
                // What a compiler writes beside a macro is not worth a rescan.
                if (event.kind() == StandardWatchEventKinds.OVERFLOW || name.isEmpty()
                        || !name.startsWith("sphere_launch_")) {
                    relevant = true;
                }
            }
            key.reset();
            if (relevant) rescanSoon();
        }
    }

    private void publish() {
        SwingUtilities.invokeLater(() -> {
            for (Listener listener : listeners) {
                try {
                    listener.vaultChanged();
                } catch (RuntimeException failed) {
                    AppLogger.debug("Code Vault listener failed: " + failed);
                }
            }
        });
    }
}
