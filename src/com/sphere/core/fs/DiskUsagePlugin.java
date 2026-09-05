package com.sphere.core.fs;

import com.sphere.core.CommandRouter;
import com.sphere.utils.AppLogger;

import javax.swing.SwingWorker;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * :du and :df. With ROOT output files a folder grows quietly until a volume
 * fills up, and neither question has an answer under cmd.exe.
 */
public class DiskUsagePlugin implements CommandRouter.CommandPlugin {

    private static final int DEFAULT_DEPTH = 1;

    private final CommandRouter router;

    public DiskUsagePlugin(CommandRouter router) {
        this.router = router;
    }

    @Override
    public String getName() {
        return "du";
    }

    @Override
    public boolean supports(String input) {
        if (input == null) return false;
        String t = input.trim();
        if (t.startsWith("::")) return false;
        return t.equals(":du") || t.startsWith(":du ")
            || t.equals(":df") || t.startsWith(":df ");
    }

    @Override
    public void execute(String input) {
        List<String> tokens = FsSupport.tokenize(input.trim());
        if (tokens.get(0).equals(":df")) {
            reportFreeSpace(tokens);
            return;
        }

        int depth = DEFAULT_DEPTH;
        boolean sortBySize = true;
        int top = Integer.MAX_VALUE;
        List<String> operands = new ArrayList<>();

        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("--help")) { usageDu(); return; }
            if (t.equals("--name")) { sortBySize = false; continue; }
            if (t.startsWith("-d")) {
                String v = t.length() > 2 ? t.substring(2) : (i + 1 < tokens.size() ? tokens.get(++i) : "");
                try { depth = Integer.parseInt(v); }
                catch (NumberFormatException bad) { AppLogger.error("Expected a number after -d"); return; }
                continue;
            }
            if (t.startsWith("--top")) {
                String v = t.contains("=") ? t.substring(t.indexOf('=') + 1)
                                           : (i + 1 < tokens.size() ? tokens.get(++i) : "");
                try { top = Integer.parseInt(v); }
                catch (NumberFormatException bad) { AppLogger.error("Expected a number after --top"); return; }
                continue;
            }
            if (t.startsWith("-")) { AppLogger.error("Unknown option: " + t); return; }
            operands.add(t);
        }

        Path root = operands.isEmpty() ? router.getCurrentDirectory()
                : FsSupport.resolve(router, String.join(" ", operands));
        if (!Files.isDirectory(root)) {
            AppLogger.error("Not a directory: " + root);
            return;
        }

        final Path finalRoot = root;
        final int finalDepth = Math.max(0, depth);
        final boolean finalBySize = sortBySize;
        final int finalTop = top;

        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                Map<Path, long[]> perBranch = new TreeMap<>();
                long[] whole = new long[2];

                try {
                    Files.walkFileTree(finalRoot, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                            if (!a.isRegularFile()) return FileVisitResult.CONTINUE;
                            whole[0] += a.size();
                            whole[1]++;
                            Path branch = branchOf(file);
                            if (branch != null) {
                                long[] cell = perBranch.computeIfAbsent(branch, k -> new long[2]);
                                cell[0] += a.size();
                                cell[1]++;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (IOException e) {
                    publish("[!] " + e.getMessage());
                    return null;
                }

                List<Map.Entry<Path, long[]>> rows = new ArrayList<>(perBranch.entrySet());
                if (finalBySize) {
                    rows.sort(Comparator.comparingLong((Map.Entry<Path, long[]> e) -> e.getValue()[0])
                                        .reversed());
                }

                int shown = 0;
                for (Map.Entry<Path, long[]> row : rows) {
                    if (shown++ >= finalTop) break;
                    publish(String.format("%10s  %7d  %s",
                            FsSupport.humanBytes(row.getValue()[0]), row.getValue()[1],
                            finalRoot.relativize(row.getKey())));
                }
                publish(String.format("%10s  %7d  %s",
                        FsSupport.humanBytes(whole[0]), whole[1], "TOTAL"));
                return null;
            }

            /** The ancestor of the file at the requested depth below the root. */
            private Path branchOf(Path file) {
                Path relative;
                try { relative = finalRoot.relativize(file); }
                catch (IllegalArgumentException other) { return null; }
                if (relative.getNameCount() <= finalDepth) return null;
                return finalRoot.resolve(relative.subpath(0, Math.max(1, finalDepth)));
            }

            @Override
            protected void process(List<String> chunks) {
                for (String line : chunks) {
                    if (line.startsWith("[!] ")) AppLogger.error(line.substring(4));
                    else AppLogger.raw(line);
                }
            }
        }.execute();
    }

    private void reportFreeSpace(List<String> tokens) {
        if (tokens.size() > 1 && tokens.get(1).equals("--help")) {
            AppLogger.raw("Usage: :df [path]");
            AppLogger.raw("Without a path, every mounted volume is listed.");
            return;
        }

        AppLogger.raw(String.format("%-28s %10s %10s %10s  %s",
                "volume", "size", "used", "free", "type"));

        if (tokens.size() > 1) {
            Path path = FsSupport.resolve(router, String.join(" ", tokens.subList(1, tokens.size())));
            try {
                describe(Files.getFileStore(path));
            } catch (IOException e) {
                AppLogger.error("Could not read " + path + ": " + e.getMessage());
            }
            return;
        }

        for (FileStore store : FileSystems.getDefault().getFileStores()) {
            describe(store);
        }
    }

    private void describe(FileStore store) {
        try {
            long total = store.getTotalSpace();
            long free = store.getUsableSpace();
            if (total <= 0) return;
            AppLogger.raw(String.format("%-28s %10s %10s %10s  %s",
                    shorten(store.name()),
                    FsSupport.humanBytes(total),
                    FsSupport.humanBytes(total - free),
                    FsSupport.humanBytes(free),
                    store.type()));
        } catch (IOException e) {
            AppLogger.error("Could not read " + store.name() + ": " + e.getMessage());
        }
    }

    private static String shorten(String name) {
        return name.length() <= 28 ? name : "..." + name.substring(name.length() - 25);
    }

    private void usageDu() {
        AppLogger.raw("Usage: :du [-d N] [--top N] [--name] [path]");
        AppLogger.raw("Options:");
        AppLogger.raw("  -d N          group at N levels below the path (default " + DEFAULT_DEPTH + ")");
        AppLogger.raw("  --top N       only the N largest");
        AppLogger.raw("  --name        sort by name instead of size");
        AppLogger.raw("  --help        show this help");
    }
}
