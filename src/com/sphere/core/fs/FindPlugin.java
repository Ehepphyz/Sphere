package com.sphere.core.fs;

import com.sphere.core.CommandRouter;
import com.sphere.utils.AppLogger;

import javax.swing.SwingWorker;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * :find, by name, size or age. The Unix argument order is kept, so a command
 * copied from a shell script works here, including under Windows where the
 * name find belongs to a completely different tool.
 */
public class FindPlugin implements CommandRouter.CommandPlugin {

    private static final int DEFAULT_MAX_RESULTS = 5000;

    private final CommandRouter router;

    public FindPlugin(CommandRouter router) {
        this.router = router;
    }

    @Override
    public String getName() {
        return "find";
    }

    @Override
    public boolean supports(String input) {
        if (input == null) return false;
        String t = input.trim();
        if (t.startsWith("::")) return false;
        return t.equals(":find") || t.startsWith(":find ");
    }

    @Override
    public void execute(String input) {
        List<String> tokens = FsSupport.tokenize(input.trim());

        String namePattern = null;
        boolean nameIgnoreCase = false;
        String kind = null;              // f | d | l
        long minSize = -1, maxSize = -1;
        int newerDays = -1, olderDays = -1;
        int maxDepth = Integer.MAX_VALUE;
        int limit = DEFAULT_MAX_RESULTS;
        String start = null;

        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            try {
                switch (t) {
                    case "--help": usage(); return;
                    case "--all": limit = Integer.MAX_VALUE; break;
                    case "-name": namePattern = tokens.get(++i); break;
                    case "-iname": namePattern = tokens.get(++i); nameIgnoreCase = true; break;
                    case "-type": kind = tokens.get(++i).toLowerCase(Locale.ROOT); break;
                    case "-size+": case "+size": minSize = bytes(tokens.get(++i)); break;
                    case "-size": {
                        String v = tokens.get(++i);
                        if (v.startsWith("+")) minSize = bytes(v.substring(1));
                        else if (v.startsWith("-")) maxSize = bytes(v.substring(1));
                        else { minSize = bytes(v); maxSize = minSize; }
                        break;
                    }
                    case "-newer": newerDays = Integer.parseInt(tokens.get(++i)); break;
                    case "-older": olderDays = Integer.parseInt(tokens.get(++i)); break;
                    case "-maxdepth": maxDepth = Integer.parseInt(tokens.get(++i)); break;
                    default:
                        if (t.startsWith("-")) {
                            AppLogger.error("Unknown option: " + t);
                            return;
                        }
                        start = start == null ? t : start + " " + t;
                }
            } catch (IndexOutOfBoundsException missing) {
                AppLogger.error("Missing value after " + t);
                return;
            } catch (NumberFormatException bad) {
                AppLogger.error("Expected a number after " + t);
                return;
            } catch (IllegalArgumentException bad) {
                AppLogger.error(bad.getMessage());
                return;
            }
        }

        Path root = start == null ? router.getCurrentDirectory()
                                  : FsSupport.resolve(router, start);
        if (!Files.isDirectory(root)) {
            AppLogger.error("Not a directory: " + root);
            return;
        }
        if (kind != null && !kind.equals("f") && !kind.equals("d") && !kind.equals("l")) {
            AppLogger.error("Unknown -type: " + kind + ". Use f, d or l.");
            return;
        }

        PathMatcher matcher = null;
        if (namePattern != null) {
            String glob = nameIgnoreCase ? namePattern.toLowerCase(Locale.ROOT) : namePattern;
            try {
                matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            } catch (IllegalArgumentException bad) {
                AppLogger.error("Invalid name pattern: " + namePattern);
                return;
            }
        }

        final PathMatcher finalMatcher = matcher;
        final boolean finalIgnoreCase = nameIgnoreCase;
        final String finalKind = kind;
        final long finalMin = minSize, finalMax = maxSize;
        final long newerThan = newerDays < 0 ? -1
                : System.currentTimeMillis() - newerDays * 86_400_000L;
        final long olderThan = olderDays < 0 ? -1
                : System.currentTimeMillis() - olderDays * 86_400_000L;
        final int finalDepth = maxDepth;
        final int finalLimit = limit;
        final Path finalRoot = root;

        new SwingWorker<Void, String>() {
            private int found = 0;

            @Override
            protected Void doInBackground() {
                try {
                    Files.walkFileTree(finalRoot, java.util.EnumSet.noneOf(FileVisitOption.class),
                            finalDepth == Integer.MAX_VALUE ? Integer.MAX_VALUE : finalDepth + 1,
                            new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                            if (!dir.equals(finalRoot)) consider(dir, a);
                            return found >= finalLimit ? FileVisitResult.TERMINATE
                                                       : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                            consider(file, a);
                            return found >= finalLimit ? FileVisitResult.TERMINATE
                                                       : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (IOException e) {
                    publish("[!] " + e.getMessage());
                }
                return null;
            }

            private void consider(Path path, BasicFileAttributes a) {
                if (finalKind != null) {
                    boolean link = a.isSymbolicLink() || Files.isSymbolicLink(path);
                    if (finalKind.equals("f") && !a.isRegularFile()) return;
                    if (finalKind.equals("d") && !a.isDirectory()) return;
                    if (finalKind.equals("l") && !link) return;
                }
                if (finalMatcher != null) {
                    Path name = path.getFileName();
                    if (name == null) return;
                    Path probe = finalIgnoreCase
                            ? Paths.get(name.toString().toLowerCase(Locale.ROOT)) : name;
                    if (!finalMatcher.matches(probe)) return;
                }
                if (a.isRegularFile()) {
                    if (finalMin >= 0 && a.size() < finalMin) return;
                    if (finalMax >= 0 && a.size() > finalMax) return;
                } else if (finalMin >= 0 || finalMax >= 0) {
                    return;
                }
                long modified = a.lastModifiedTime().toMillis();
                if (newerThan >= 0 && modified < newerThan) return;
                if (olderThan >= 0 && modified > olderThan) return;

                found++;
                publish(relative(path) + (a.isDirectory() ? "/" : ""));
            }

            private String relative(Path path) {
                try { return finalRoot.relativize(path).toString(); }
                catch (IllegalArgumentException other) { return path.toString(); }
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
                if (found == 0) AppLogger.info("Nothing matched under " + finalRoot);
                else if (found >= finalLimit) {
                    AppLogger.warn(found + " results, stopped at the limit. Use  --all  for everything.");
                }
            }
        }.execute();
    }

    /** Accepts 4096, 12k, 3M, 2G. */
    private static long bytes(String text) {
        String t = text.trim().toUpperCase(Locale.ROOT);
        long factor = 1;
        if (t.endsWith("K")) { factor = 1024L; t = t.substring(0, t.length() - 1); }
        else if (t.endsWith("M")) { factor = 1024L * 1024; t = t.substring(0, t.length() - 1); }
        else if (t.endsWith("G")) { factor = 1024L * 1024 * 1024; t = t.substring(0, t.length() - 1); }
        try {
            return Long.parseLong(t.trim()) * factor;
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("Bad size: " + text + ". Use 4096, 12k, 3M or 2G.");
        }
    }

    private void usage() {
        AppLogger.raw("Usage: :find [path] [tests]");
        AppLogger.raw("Tests:");
        AppLogger.raw("  -name GLOB    file name matches, e.g. -name \"*.root\"");
        AppLogger.raw("  -iname GLOB   the same, ignoring case");
        AppLogger.raw("  -type f|d|l   regular file, directory or symbolic link");
        AppLogger.raw("  -size +10M    larger than; -size -1k smaller than; -size 512 exactly");
        AppLogger.raw("  -newer N      modified within the last N days");
        AppLogger.raw("  -older N      not modified for N days");
        AppLogger.raw("  -maxdepth N   do not descend past N levels");
        AppLogger.raw("  --all         no limit on the number of results");
        AppLogger.raw("  --help        show this help");
    }
}
