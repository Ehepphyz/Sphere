package com.sphere.core.fs;

import com.sphere.core.CommandRouter;
import com.sphere.utils.AppLogger;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.text.Collator;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced plugin to list files with accurate permission display.
 * Exclusively intercepts the internal ':ls' syntax to avoid OS terminal conflicts.
 *
 * Cross-platform (Windows, Linux, macOS, WSL) ready-to-compile implementation.
 *
 * Features:
 * - Accepts ":ls" and common options: -l (long), -a (all), -h (human-readable), -r (reverse), --sort=name|size|time, --all
 * - Rejects hybrid syntax starting with "::"
 * - Streams output asynchronously to avoid blocking UI
 * - Locale-aware sorting (collation)
 * - POSIX permissions when available; clear Windows fallback marked as approximation
 * - Symbolic link handling (shows "name -> target")
 * - Human-readable sizes with -h
 * - Safe handling of access errors with clear messages
 * - Limits output by default for very large directories; supports --all to list everything
 */
public class LsPlugin implements CommandRouter.CommandPlugin {

    private final CommandRouter router;

    // Default maximum entries to list without explicit --all
    private static final int DEFAULT_MAX_ENTRIES = 10000;

    // Robust regex argument splitting to support quoted directory paths with spaces
    private static final Pattern ARG_SPLIT = Pattern.compile(
        "\"((?:\\\\\"|[^\"])+)\"|'((?:\\\\'|[^'])+)'|([^\\s]+)"
    );

    public LsPlugin(CommandRouter router) {
        this.router = router;
    }

    @Override
    public String getName() {
        return "ls";
    }

    @Override
    public boolean supports(String input) {
        if (input == null) return false;
        String trimmed = input.trim();

        // Reject hybrid syntax (::ls ...)
        if (trimmed.startsWith("::")) return false;

        // Accept ":ls" and ":ls " followed by args
        return trimmed.equals(":ls") || trimmed.startsWith(":ls ");
    }

    @Override
    public void execute(String input) {
        if (input == null) {
            AppLogger.error("Null input.");
            return;
        }

        String trimmed = input.trim();

        // Reject hybrid syntax explicitly
        if (trimmed.startsWith("::")) {
            AppLogger.error("Hybrid syntax (::ls) is not allowed for directory listing.");
            return;
        }

        // Remove leading colon
        if (!trimmed.startsWith(":")) {
            AppLogger.error("Internal command must start with ':' prefix.");
            return;
        }
        String withoutColon = trimmed.substring(1).trim();

        // Tokenize arguments using robust regex splitting to support quoted paths
        List<String> tokens = splitArgs(withoutColon);
        if (tokens.isEmpty() || !tokens.get(0).equals("ls")) {
            AppLogger.error("Invalid ls command.");
            return;
        }

        // Default options
        boolean longFormat = false;
        boolean showAll = false;
        boolean humanReadable = false;
        boolean reverse = false;
        String sortKey = "name"; // name | size | time
        boolean listAllEntries = false;

        Path targetDir = router.getCurrentDirectory();

        // Parse options and optional path (options must appear before the path)
        String pathArg = null;
        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("--help")) {
                printUsage();
                return;
            }
            if (t.startsWith("-") && pathArg == null) {
                // short combined options like -la
                if (t.startsWith("-") && !t.startsWith("--") && t.length() > 2) {
                    for (int k = 1; k < t.length(); k++) {
                        char c = t.charAt(k);
                        switch (c) {
                            case 'l': longFormat = true; break;
                            case 'a': showAll = true; break;
                            case 'h': humanReadable = true; break;
                            case 'r': reverse = true; break;
                            default:
                                AppLogger.error("Unknown option: -" + c);
                                return;
                        }
                    }
                } else {
                    // long or single short option
                    switch (t) {
                        case "-l": longFormat = true; break;
                        case "-a": showAll = true; break;
                        case "-h": humanReadable = true; break;
                        case "-r": reverse = true; break;
                        case "--all": listAllEntries = true; break;
                        default:
                            if (t.startsWith("--sort=")) {
                                String v = t.substring("--sort=".length()).toLowerCase(Locale.ROOT);
                                if (v.equals("name") || v.equals("size") || v.equals("time")) {
                                    sortKey = v;
                                } else {
                                    AppLogger.error("Unknown sort key: " + v + ". Use name|size|time.");
                                    return;
                                }
                            } else if (t.equals("--sort")) {
                                if (i + 1 < tokens.size()) {
                                    String v = tokens.get(++i).toLowerCase(Locale.ROOT);
                                    if (v.equals("name") || v.equals("size") || v.equals("time")) {
                                        sortKey = v;
                                    } else {
                                        AppLogger.error("Unknown sort key: " + v + ". Use name|size|time.");
                                        return;
                                    }
                                } else {
                                    AppLogger.error("Missing value for --sort");
                                    return;
                                }
                            } else {
                                AppLogger.error("Unknown option: " + t);
                                return;
                            }
                            break;
                    }
                }
            } else {
                // first non-option token is path; remaining tokens appended to path (support unquoted spaces)
                if (pathArg == null) pathArg = t;
                else pathArg = pathArg + " " + t;
            }
        }

        // Resolve pathArg if provided
        if (pathArg != null && !pathArg.isBlank()) {
            pathArg = expandHome(pathArg);
            try {
                Path p = Paths.get(pathArg);
                targetDir = p.isAbsolute() ? p.toAbsolutePath().normalize()
                        : router.getCurrentDirectory().resolve(p).toAbsolutePath().normalize();
            } catch (InvalidPathException ipe) {
                AppLogger.error("Invalid path: " + ipe.getMessage());
                return;
            }
        }

        // Validate targetDir
        if (!Files.exists(targetDir)) {
            AppLogger.error("Directory not found: " + targetDir);
            return;
        }
        if (!Files.isDirectory(targetDir)) {
            AppLogger.error("Not a directory: " + targetDir);
            return;
        }

        final boolean finalLong = longFormat;
        final boolean finalShowAll = showAll;
        final boolean finalHuman = humanReadable;
        final boolean finalReverse = reverse;
        final String finalSortKey = sortKey;
        final boolean finalListAll = listAllEntries;
        final Path finalTarget = targetDir;

        // Run listing asynchronously to avoid blocking UI
        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                // Pass a line consumer that calls SwingWorker.publish for each line
                listDirectory(finalTarget, finalLong, finalShowAll, finalHuman, finalReverse, finalSortKey, finalListAll, s -> publish(s));
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                // Batched output processed safely on the Event Dispatch Thread (EDT)
                for (String line : chunks) {
                    AppLogger.raw(line);
                }
            }
        }.execute();
    }

    // -------------------------
    // Core listing implementation
    // -------------------------
    private void listDirectory(Path dir, boolean longFormat, boolean showAll, boolean humanReadable,
                               boolean reverse, String sortKey, boolean listAll, Consumer<String> linePublisher) {
        // Collator for locale-aware sorting
        Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.TERTIARY);

        // Date formatter
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

        List<Entry> entries = new ArrayList<>();
        AtomicBoolean hadAccessError = new AtomicBoolean(false);

        // Stream directory and collect metadata safely (catching per-entry IO errors)
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                try {
                    if (!showAll && isHidden(p)) continue;
                } catch (IOException e) {
                    hadAccessError.set(true);
                }

                Entry e = readEntryMetadata(p);
                entries.add(e);
            }
        } catch (AccessDeniedException ade) {
            linePublisher.accept("Permission denied: " + ade.getMessage());
            return;
        } catch (IOException e) {
            linePublisher.accept("Error listing directory: " + e.getMessage());
            return;
        }

        // Sorting comparator based on requested key
        Comparator<Entry> comparator;
        switch (sortKey) {
            case "size":
                comparator = Comparator.comparingLong(en -> en.size);
                break;
            case "time":
                comparator = Comparator.comparingLong(en -> en.mtime);
                break;
            case "name":
            default:
                comparator = Comparator.comparing(en -> en.name, collator);
                break;
        }

        // Directories first, then comparator
        comparator = Comparator.comparing((Entry en) -> !en.isDirectory)
                .thenComparing(comparator);

        if (reverse) comparator = comparator.reversed();

        // Sort entries
        entries.sort(comparator);

        // Limit entries unless --all specified
        int limit = listAll ? Integer.MAX_VALUE : DEFAULT_MAX_ENTRIES;
        if (entries.size() > limit && !listAll) {
            linePublisher.accept("[Listing truncated: showing first " + limit + " entries. Use --all to show everything]");
        }

        int shown = 0;
        for (Entry en : entries) {
            if (shown >= limit) break;
            if (longFormat) {
                String line = formatLongLine(en, humanReadable, dateFmt);
                linePublisher.accept(line);
            } else {
                String name = en.name + (en.isDirectory ? "/" : "");
                linePublisher.accept(name);
            }
            shown++;
        }

        if (hadAccessError.get()) {
            linePublisher.accept("[Warning] Some entries could not be inspected due to access restrictions.");
        }
    }

    // Read metadata for a single entry safely
    private Entry readEntryMetadata(Path p) {
        Entry e = new Entry();
        e.path = p;
        e.name = p.getFileName() != null ? p.getFileName().toString() : p.toString();
        try {
            e.isDirectory = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
        } catch (Exception ex) {
            e.isDirectory = false;
        }
        try {
            e.isSymbolic = Files.isSymbolicLink(p);
        } catch (Exception ex) {
            e.isSymbolic = false;
        }
        try {
            e.size = Files.size(p);
        } catch (Exception ex) {
            e.size = 0L;
        }
        try {
            FileTime ft = Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS);
            e.mtime = ft.toMillis();
        } catch (Exception ex) {
            e.mtime = 0L;
        }
        // Owner
        try {
            UserPrincipal up = Files.getOwner(p, LinkOption.NOFOLLOW_LINKS);
            e.owner = up != null ? up.getName() : "N/A";
        } catch (Exception ex) {
            e.owner = "N/A";
        }
        // Group and POSIX perms
        try {
            PosixFileAttributes posix = Files.readAttributes(p, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            e.group = posix.group() != null ? posix.group().getName() : "N/A";
            e.perms = PosixFilePermissions.toString(posix.permissions());
            // Try to read unix:nlink if available
            try {
                Object nlink = Files.getAttribute(p, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
                if (nlink instanceof Number) e.links = String.valueOf(((Number) nlink).longValue());
            } catch (Exception ignored) { }
        } catch (UnsupportedOperationException uoe) {
            // Non-POSIX (Windows) fallback
            e.group = "N/A";
            e.perms = approximateWindowsPerms(p);
            e.links = "-";
        } catch (IOException ioe) {
            e.group = "N/A";
            e.perms = "---------";
            e.links = "-";
        }
        // Symbolic link target
        if (e.isSymbolic) {
            try {
                Path target = Files.readSymbolicLink(p);
                e.linkTarget = target != null ? target.toString() : "[unknown]";
            } catch (IOException ex) {
                e.linkTarget = "[broken link]";
            }
        }
        return e;
    }

    // Format a single entry in long format
    private String formatLongLine(Entry e, boolean humanReadable, DateTimeFormatter dateFmt) {
        String typeChar = fileTypeChar(e);
        String perms = e.perms != null ? e.perms : "---------";
        String links = e.links != null ? e.links : "-";
        String owner = e.owner != null ? e.owner : "N/A";
        String group = e.group != null ? e.group : "N/A";
        String sizeStr = humanReadable ? humanReadableByteCount(e.size) : String.valueOf(e.size);
        String timeStr = e.mtime > 0 ? dateFmt.format(java.time.Instant.ofEpochMilli(e.mtime)) : "?";
        String name = e.name;
        if (e.isSymbolic) {
            name = name + " -> " + e.linkTarget;
        } else if (e.isDirectory) {
            name = name + "/";
        }

        // Compose formatted line with consistent columns
        return String.format("%-10s %4s %-16s %-16s %12s %s %s",
                typeChar + perms,
                links,
                owner,
                group,
                padLeft(sizeStr, 12),
                timeStr,
                name
        );
    }

    // Determine file type character similar to ls: d, l, -, s, ?
    private String fileTypeChar(Entry e) {
        if (e.isSymbolic) return "l";
        if (e.isDirectory) return "d";
        if (!e.isDirectory) return "-";
        return "?";
    }

    // Approximate Windows permissions string (9 chars) and append '~' to indicate approximation
    private String approximateWindowsPerms(Path p) {
        String r = Files.isReadable(p) ? "r" : "-";
        String w = Files.isWritable(p) ? "w" : "-";
        String x = Files.isExecutable(p) ? "x" : "-";
        // Repeat for group and other as approximation
        return (r + w + x + r + w + x + r + w + x) + "~";
    }

    // -------------------------
    // Utility helpers
    // -------------------------

    private List<String> splitArgs(String input) {
        List<String> parts = new ArrayList<>();
        Matcher m = ARG_SPLIT.matcher(input);
        while (m.find()) {
            String dq = m.group(1);
            String sq = m.group(2);
            String bare = m.group(3);
            if (dq != null) parts.add(dq.replace("\\\"", "\""));
            else if (sq != null) parts.add(sq.replace("\\'", "'"));
            else if (bare != null) parts.add(bare);
        }
        return parts;
    }

    private boolean isHidden(Path p) throws IOException {
        try {
            return Files.isHidden(p);
        } catch (IOException e) {
            // Fallback: on Unix, files starting with '.' are hidden
            String name = p.getFileName() != null ? p.getFileName().toString() : "";
            return name.startsWith(".");
        }
    }

    private void printUsage() {
        AppLogger.raw("Usage: :ls [options] [path]");
        AppLogger.raw("Options:");
        AppLogger.raw("  -l            long format");
        AppLogger.raw("  -a            show all (including hidden)");
        AppLogger.raw("  -h            human-readable sizes (e.g., 4.0KiB)");
        AppLogger.raw("  -r            reverse sort");
        AppLogger.raw("  --sort name|size|time   sort key (default: name)");
        AppLogger.raw("  --all         list everything (no truncation)");
        AppLogger.raw("  --help        show this help");
    }

    private String humanReadableByteCount(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        // Cap exp to available prefixes length to avoid index errors for extremely large values
        int maxExp = Math.min(exp, 6); // supports up to 'P' (petabytes) safely
        String pre = "KMGTPE".charAt(Math.max(0, maxExp - 1)) + "i";
        double value = bytes / Math.pow(1024, maxExp);
        return String.format(Locale.ROOT, "%.1f %sB", value, pre);
    }

    private String padLeft(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < width - s.length(); i++) sb.append(' ');
        sb.append(s);
        return sb.toString();
    }

    private String expandHome(String path) {
        if (path == null || path.isEmpty()) return path;
        if (path.equals("~")) return System.getProperty("user.home");
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    // Small metadata holder for sorting and formatting
    private static class Entry {
        Path path;
        String name;
        boolean isDirectory;
        boolean isSymbolic;
        long size;
        long mtime;
        String owner;
        String group;
        String perms;
        String links;
        String linkTarget;
    }
}
