package com.sphere.core.fs;

import com.sphere.core.CommandRouter;
import com.sphere.utils.AppLogger;

import javax.swing.SwingWorker;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** :tree -- the shape of a directory, drawn with box characters. */
public class TreePlugin implements CommandRouter.CommandPlugin {

    private static final int DEFAULT_DEPTH = 3;
    private static final int DEFAULT_MAX_ENTRIES = 2000;

    private final CommandRouter router;

    public TreePlugin(CommandRouter router) {
        this.router = router;
    }

    @Override
    public String getName() {
        return "tree";
    }

    @Override
    public boolean supports(String input) {
        if (input == null) return false;
        String t = input.trim();
        if (t.startsWith("::")) return false;
        return t.equals(":tree") || t.startsWith(":tree ");
    }

    @Override
    public void execute(String input) {
        List<String> tokens = FsSupport.tokenize(input.trim());

        int depth = DEFAULT_DEPTH;
        boolean showHidden = false;
        boolean directoriesOnly = false;
        boolean showSizes = false;
        int limit = DEFAULT_MAX_ENTRIES;
        List<String> operands = new ArrayList<>();

        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("--help")) { usage(); return; }
            if (t.equals("--all")) { limit = Integer.MAX_VALUE; continue; }
            if (t.startsWith("-L") || t.startsWith("-d") && t.length() > 2) {
                String v = t.length() > 2 ? t.substring(2) : (i + 1 < tokens.size() ? tokens.get(++i) : "");
                try { depth = Integer.parseInt(v); }
                catch (NumberFormatException bad) { AppLogger.error("Expected a number after -L"); return; }
                continue;
            }
            if (t.equals("-L")) {
                if (i + 1 >= tokens.size()) { AppLogger.error("Missing value after -L"); return; }
                try { depth = Integer.parseInt(tokens.get(++i)); }
                catch (NumberFormatException bad) { AppLogger.error("Expected a number after -L"); return; }
                continue;
            }
            if (t.startsWith("-") && !t.startsWith("--")) {
                for (int k = 1; k < t.length(); k++) {
                    switch (t.charAt(k)) {
                        case 'a': showHidden = true; break;
                        case 'd': directoriesOnly = true; break;
                        case 's': showSizes = true; break;
                        default:
                            AppLogger.error("Unknown option: -" + t.charAt(k));
                            return;
                    }
                }
                continue;
            }
            operands.add(t);
        }

        Path root = operands.isEmpty() ? router.getCurrentDirectory()
                : FsSupport.resolve(router, String.join(" ", operands));
        if (!Files.isDirectory(root)) {
            AppLogger.error("Not a directory: " + root);
            return;
        }

        final Path finalRoot = root;
        final int finalDepth = Math.max(1, depth);
        final boolean finalHidden = showHidden;
        final boolean finalDirsOnly = directoriesOnly;
        final boolean finalSizes = showSizes;
        final int finalLimit = limit;

        new SwingWorker<Void, String>() {
            private int files = 0;
            private int folders = 0;
            private boolean stopped = false;

            @Override
            protected Void doInBackground() {
                publish(finalRoot.toString());
                walk(finalRoot, "", 1);
                return null;
            }

            private void walk(Path dir, String prefix, int level) {
                if (stopped || level > finalDepth) return;

                List<Path> entries = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                    for (Path p : stream) {
                        String name = p.getFileName().toString();
                        if (!finalHidden && (name.startsWith(".") || isHidden(p))) continue;
                        if (finalDirsOnly && !Files.isDirectory(p)) continue;
                        entries.add(p);
                    }
                } catch (IOException e) {
                    publish(prefix + "|-- [unreadable: " + e.getMessage() + "]");
                    return;
                }

                Collator collator = Collator.getInstance(Locale.getDefault());
                entries.sort(Comparator
                        .comparing((Path p) -> Files.isDirectory(p) ? 0 : 1)
                        .thenComparing(p -> p.getFileName().toString(), collator::compare));

                for (int i = 0; i < entries.size(); i++) {
                    if (files + folders >= finalLimit) {
                        stopped = true;
                        return;
                    }
                    Path entry = entries.get(i);
                    boolean last = i == entries.size() - 1;
                    boolean directory = Files.isDirectory(entry);
                    if (directory) folders++; else files++;

                    StringBuilder label = new StringBuilder(entry.getFileName().toString());
                    if (directory) {
                        label.append('/');
                    } else if (finalSizes) {
                        try {
                            label.append("  (").append(FsSupport.humanBytes(Files.size(entry))).append(')');
                        } catch (IOException ignored) {
                            // A size that cannot be read is simply not shown
                        }
                    }
                    if (Files.isSymbolicLink(entry)) {
                        try { label.append(" -> ").append(Files.readSymbolicLink(entry)); }
                        catch (IOException ignored) { label.append(" -> ?"); }
                    }

                    publish(prefix + (last ? "`-- " : "|-- ") + label);
                    if (directory && !Files.isSymbolicLink(entry)) {
                        walk(entry, prefix + (last ? "    " : "|   "), level + 1);
                    }
                }
            }

            private boolean isHidden(Path p) {
                try { return Files.isHidden(p); } catch (IOException e) { return false; }
            }

            @Override
            protected void process(List<String> chunks) {
                chunks.forEach(AppLogger::raw);
            }

            @Override
            protected void done() {
                AppLogger.raw(folders + " directories, " + files + " files");
                if (stopped) {
                    AppLogger.warn("Stopped at " + finalLimit + " entries. Use  --all  for everything.");
                }
            }
        }.execute();
    }

    private void usage() {
        AppLogger.raw("Usage: :tree [-L N] [-a] [-d] [-s] [--all] [path]");
        AppLogger.raw("Options:");
        AppLogger.raw("  -L N          descend N levels (default " + DEFAULT_DEPTH + ")");
        AppLogger.raw("  -a            include hidden entries");
        AppLogger.raw("  -d            directories only");
        AppLogger.raw("  -s            show file sizes");
        AppLogger.raw("  --all         no limit on the number of entries");
        AppLogger.raw("  --help        show this help");
    }
}
