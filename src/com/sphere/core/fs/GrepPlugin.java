package com.sphere.core.fs;

import com.sphere.core.CommandRouter;
import com.sphere.utils.AppLogger;

import javax.swing.SwingWorker;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * :grep. Windows has no equivalent worth the name, and findstr does not do
 * recursion with a file filter. Binary files are skipped rather than dumped,
 * which matters in a tree full of .root files.
 */
public class GrepPlugin implements CommandRouter.CommandPlugin {

    private static final int DEFAULT_MAX_HITS = 2000;

    private final CommandRouter router;

    public GrepPlugin(CommandRouter router) {
        this.router = router;
    }

    @Override
    public String getName() {
        return "grep";
    }

    @Override
    public boolean supports(String input) {
        if (input == null) return false;
        String t = input.trim();
        if (t.startsWith("::")) return false;
        return t.equals(":grep") || t.startsWith(":grep ");
    }

    @Override
    public void execute(String input) {
        List<String> tokens = FsSupport.tokenize(input.trim());

        boolean recursive = false;
        boolean ignoreCase = false;
        boolean showNumbers = true;
        boolean namesOnly = false;
        boolean invert = false;
        boolean fixed = false;
        int maxHits = DEFAULT_MAX_HITS;
        String include = null;
        List<String> operands = new ArrayList<>();

        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("--help")) { usage(); return; }
            if (t.startsWith("--include=")) { include = t.substring(10); continue; }
            if (t.equals("--all")) { maxHits = Integer.MAX_VALUE; continue; }
            if (t.startsWith("-") && t.length() > 1 && !t.startsWith("--") && operands.isEmpty()) {
                for (int k = 1; k < t.length(); k++) {
                    switch (t.charAt(k)) {
                        case 'r': case 'R': recursive = true; break;
                        case 'i': ignoreCase = true; break;
                        case 'n': showNumbers = true; break;
                        case 'l': namesOnly = true; break;
                        case 'v': invert = true; break;
                        case 'F': fixed = true; break;
                        case 'h': showNumbers = false; break;
                        default:
                            AppLogger.error("Unknown option: -" + t.charAt(k));
                            return;
                    }
                }
            } else {
                operands.add(t);
            }
        }

        if (operands.isEmpty()) { usage(); return; }

        String expression = operands.get(0);
        Path root = operands.size() > 1
                ? FsSupport.resolve(router, String.join(" ", operands.subList(1, operands.size())))
                : router.getCurrentDirectory();

        if (!Files.exists(root)) {
            AppLogger.error("Not found: " + root);
            return;
        }
        if (Files.isDirectory(root) && !recursive) {
            AppLogger.error(root.getFileName() + " is a directory. Use  :grep -r  to search it.");
            return;
        }

        Pattern pattern;
        try {
            int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            pattern = fixed ? Pattern.compile(Pattern.quote(expression), flags)
                            : Pattern.compile(expression, flags);
        } catch (PatternSyntaxException bad) {
            AppLogger.error("Invalid pattern: " + bad.getDescription());
            return;
        }

        PathMatcher matcher = null;
        if (include != null && !include.isBlank()) {
            try {
                matcher = FileSystems.getDefault().getPathMatcher("glob:" + include);
            } catch (IllegalArgumentException bad) {
                AppLogger.error("Invalid --include pattern: " + include);
                return;
            }
        }

        final Pattern finalPattern = pattern;
        final PathMatcher finalMatcher = matcher;
        final Path finalRoot = root;
        final boolean finalNumbers = showNumbers;
        final boolean finalNames = namesOnly;
        final boolean finalInvert = invert;
        final int finalMax = maxHits;

        new SwingWorker<Void, String>() {
            private int hits = 0;
            private int scanned = 0;
            private int skipped = 0;

            @Override
            protected Void doInBackground() {
                try {
                    if (Files.isDirectory(finalRoot)) {
                        Files.walkFileTree(finalRoot, new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                if (hits >= finalMax) return FileVisitResult.TERMINATE;
                                if (finalMatcher != null
                                        && !finalMatcher.matches(file.getFileName())) {
                                    return FileVisitResult.CONTINUE;
                                }
                                search(file);
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path file, IOException e) {
                                skipped++;
                                return FileVisitResult.CONTINUE;
                            }
                        });
                    } else {
                        search(finalRoot);
                    }
                } catch (IOException e) {
                    publish("[!] Search stopped: " + e.getMessage());
                }
                return null;
            }

            private void search(Path file) {
                if (FsSupport.looksBinary(file)) { skipped++; return; }
                scanned++;
                String shown = finalRoot.equals(file) ? file.toString()
                             : relative(finalRoot, file);
                int number = 0;
                try (BufferedReader reader = FsSupport.utf8Reader(file)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        number++;
                        boolean found = finalPattern.matcher(line).find();
                        if (found == finalInvert) continue;
                        hits++;
                        if (finalNames) {
                            publish(shown);
                            return;
                        }
                        publish(finalNumbers ? shown + ":" + number + ": " + line
                                             : shown + ": " + line);
                        if (hits >= finalMax) return;
                    }
                } catch (IOException e) {
                    skipped++;
                }
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
                if (hits == 0) {
                    AppLogger.info("No match in " + scanned + " files"
                                   + (skipped > 0 ? ", " + skipped + " skipped" : "") + ".");
                } else if (hits >= finalMax) {
                    AppLogger.warn(hits + " matches shown, stopped at the limit. Use  --all  for everything.");
                }
            }
        }.execute();
    }

    private static String relative(Path root, Path file) {
        try {
            return root.relativize(file).toString();
        } catch (IllegalArgumentException different) {
            return file.toString();
        }
    }

    private void usage() {
        AppLogger.raw("Usage: :grep [options] <pattern> [path]");
        AppLogger.raw("Options:");
        AppLogger.raw("  -r            search a directory and everything under it");
        AppLogger.raw("  -i            ignore case");
        AppLogger.raw("  -n            show line numbers (on by default)");
        AppLogger.raw("  -h            hide line numbers");
        AppLogger.raw("  -l            list matching file names only");
        AppLogger.raw("  -v            show the lines that do not match");
        AppLogger.raw("  -F            treat the pattern as plain text, not a regex");
        AppLogger.raw("  --include=GLOB  only files whose name matches, e.g. --include=*.cpp");
        AppLogger.raw("  --all         no limit on the number of matches");
        AppLogger.raw("  --help        show this help");
        AppLogger.raw("Binary files are skipped. The pattern is Java regex syntax.");
    }
}
