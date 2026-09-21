package com.sphere.core.rootbackend;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Finding a set by its name, and fetching one that is not there.
 *
 * A set is referred to by a name -- CT18NNLO, NNPDF31_nnlo_as_0118 -- and not by
 * a path, because that name is what a paper quotes and what a configuration
 * file carries. Turning it into a directory means looking through the folders
 * the installation declares, which LHAPDF does through LHAPDF_DATA_PATH. The
 * same variable is read here, so a machine that already has LHAPDF installed
 * needs no further configuration, and folders of Sphere's own can be added
 * beside it.
 *
 * The index is a plain list of every published set with the number it was given
 * and how many members it has. It is what turns a number in an old steering
 * file into a name, and what lets a name be checked before anything is
 * downloaded.
 */
public final class RootPdfCatalog {

    /** Where the published sets are kept. */
    public static final String DOWNLOAD_BASE =
        "https://lhapdfsets.web.cern.ch/lhapdfsets/current/";

    /**
     * Where sets are actually fetched from.
     *
     * A laboratory usually keeps a mirror, and a machine behind a proxy often
     * cannot reach the one at CERN at all, so the address is a setting rather
     * than a constant.
     */
    private static String downloadBase = DOWNLOAD_BASE;

    /** Fetch sets from somewhere else, a mirror or a local copy. */
    public static void downloadFrom(String base) {
        if (base != null && !base.isBlank()) {
            downloadBase = base.trim().endsWith("/") ? base.trim() : base.trim() + "/";
        }
    }

    /** Where sets are fetched from right now. */
    public static String downloadBase() {
        return downloadBase;
    }

    /**
     * One line of the index: the number, the name, and the data version.
     *
     * The third field is the version of the data files, which is what mkindex
     * writes, and not the number of members. How many members a set has is in
     * its own info file, so it is only known once the set is on disk.
     */
    public record Entry(int id, String name, int version) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%-6d %-38s data version %d",
                id, name, version);
        }
    }

    /** Folders added by Sphere, searched before the ones the environment names. */
    private static final Set<Path> OWN = new LinkedHashSet<>();

    /** The index, read once. */
    private static Map<String, Entry> byName;
    private static Map<Integer, Entry> byId;

    private RootPdfCatalog() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /* ------------------------------------------------------------------ */
    /* Where to look                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Every folder searched for a set, in the order they are searched.
     *
     * Sphere's own folders come first so that a set placed there wins over one
     * an old installation left behind, which is what someone adding a folder
     * meant. After them come the folders of LHAPDF_DATA_PATH, then LHAPATH for
     * the installations that still use it.
     */
    public static List<Path> paths() {
        List<Path> out = new ArrayList<>(OWN);
        String said = System.getenv("LHAPDF_DATA_PATH");
        if (said == null || said.isBlank()) {
            said = System.getenv("LHAPATH");
        }
        if (said != null) {
            for (String one : said.split(java.io.File.pathSeparator)) {
                if (!one.isBlank()) {
                    out.add(Path.of(one.trim()));
                }
            }
        }
        // The usual install prefixes, for a machine where the variable is unset.
        for (String guess : new String[]{"/usr/share/LHAPDF", "/usr/local/share/LHAPDF",
                                         "/usr/share/lhapdf/PDFsets",
                                         "/usr/local/share/lhapdf/PDFsets"}) {
            final Path at = Path.of(guess);
            if (Files.isDirectory(at) && !out.contains(at)) {
                out.add(at);
            }
        }
        return out;
    }

    /** Adds a folder to look in, ahead of the ones the environment names. */
    public static boolean addPath(Path folder) {
        if (folder == null || !Files.isDirectory(folder)) {
            return false;
        }
        return OWN.add(folder.toAbsolutePath().normalize());
    }

    /** Removes a folder Sphere was told about. Folders from the environment stay. */
    public static boolean dropPath(Path folder) {
        return folder != null && OWN.remove(folder.toAbsolutePath().normalize());
    }

    /** The folders Sphere itself was told about. */
    public static List<Path> ownPaths() {
        return new ArrayList<>(OWN);
    }

    /* ------------------------------------------------------------------ */
    /* Finding                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * The folder a set name stands for, or null when no folder holds it.
     *
     * A directory only counts when member zero is actually in it: a folder left
     * behind by an interrupted download has the right name and nothing to read,
     * and finding that one rather than the good copy beside it is worse than
     * finding nothing.
     */
    public static Path find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        final String bare = name.trim();
        // An outright path is taken as one, which is what a script that already
        // knows where its set lives expects.
        final Path direct = Path.of(bare);
        if (holdsMemberZero(direct)) {
            return direct;
        }
        for (Path base : paths()) {
            final Path at = base.resolve(bare);
            if (holdsMemberZero(at)) {
                return at;
            }
        }
        return null;
    }

    private static boolean holdsMemberZero(Path folder) {
        if (folder == null || !Files.isDirectory(folder)) {
            return false;
        }
        final String stem = folder.getFileName().toString();
        return Files.isRegularFile(folder.resolve(stem + "_0000.dat"));
    }

    /** Opens a set by name, looking through every declared folder. */
    public static RootPdfSet open(String name) throws IOException {
        return open(name, 1, RootPdfGrid.Accuracy.LHAPDF);
    }

    /** The same, saying how many members to read and how to interpolate. */
    public static RootPdfSet open(String name, int howMany, RootPdfGrid.Accuracy accuracy)
            throws IOException {
        final Path at = find(name);
        if (at == null) {
            throw new IOException("No set named " + name + " in any of "
                + paths().size() + " folders. ':root pdf where' lists them, "
                + "':root pdf fetch " + name + "' downloads it.");
        }
        return RootPdfSet.open(at, howMany, accuracy);
    }

    /** Every set already on disk, by the name of its folder. */
    public static List<String> installed() {
        Set<String> found = new LinkedHashSet<>();
        for (Path base : paths()) {
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (var list = Files.list(base)) {
                list.filter(RootPdfCatalog::holdsMemberZero)
                    .map(one -> one.getFileName().toString())
                    .forEach(found::add);
            } catch (IOException cannotList) {
                // A folder that cannot be listed simply holds nothing findable.
            }
        }
        List<String> out = new ArrayList<>(found);
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* The index                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * The published sets, read from pdfsets.index.
     *
     * Three fields per line: the number the set was given, its name, and how
     * many members it has. The number is what an old steering file carries, and
     * turning it back into a name is the only way to know what such a file
     * actually asked for.
     */
    public static synchronized Map<String, Entry> index() {
        if (byName == null) {
            readIndex();
        }
        return byName;
    }

    private static void readIndex() {
        byName = new LinkedHashMap<>();
        byId = new LinkedHashMap<>();
        Path file = null;
        for (Path base : paths()) {
            final Path at = base.resolve("pdfsets.index");
            if (Files.isRegularFile(at)) {
                file = at;
                break;
            }
        }
        if (file == null) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                final String[] word = line.trim().split("\\s+");
                if (word.length < 2) {
                    continue;
                }
                try {
                    final int id = Integer.parseInt(word[0]);
                    final int version = word.length > 2 ? Integer.parseInt(word[2]) : 1;
                    final Entry one = new Entry(id, word[1], version);
                    byName.put(word[1], one);
                    byId.put(id, one);
                } catch (NumberFormatException notAnEntry) {
                    // A line that is not an entry is not one.
                }
            }
        } catch (IOException cannotRead) {
            // An index that cannot be read leaves the catalog empty, which is
            // the same position as not having one.
        }
    }

    /** What the index says about a set, or null when it says nothing. */
    public static Entry describe(String name) {
        return index().get(name == null ? "" : name.trim());
    }

    /** The set an old numeric identifier stands for. */
    public static Entry describe(int id) {
        index();
        return byId.get(id);
    }

    /** The sets whose name holds a piece of text, case ignored. */
    public static List<Entry> search(String text) {
        final String wanted = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        for (Entry one : index().values()) {
            if (one.name().toLowerCase(Locale.ROOT).contains(wanted)) {
                out.add(one);
            }
        }
        return out;
    }

    /** Where the index was read from, or null when none was found. */
    public static Path indexFile() {
        for (Path base : paths()) {
            final Path at = base.resolve("pdfsets.index");
            if (Files.isRegularFile(at)) {
                return at;
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* Fetching                                                            */
    /* ------------------------------------------------------------------ */

    /** What a download did. */
    public record Fetched(boolean succeeded, Path folder, long bytes, String message) { }

    /**
     * Downloads a set and unpacks it into the first writable folder.
     *
     * The archive is a gzipped tar of one directory named after the set. It is
     * unpacked entry by entry rather than through an external tool, and an
     * entry whose name would land outside the destination is refused: an
     * archive is data from elsewhere, and a path in it is not a permission.
     */
    public static Fetched fetch(String name, Path into) {
        if (name == null || name.isBlank()) {
            return new Fetched(false, null, 0, "No set named.");
        }
        final String bare = name.trim();
        final Path destination = into != null ? into : firstWritable();
        if (destination == null) {
            return new Fetched(false, null, 0,
                "No folder to install into. ':root pdf where add <folder>' names one.");
        }
        if (holdsMemberZero(destination.resolve(bare))) {
            return new Fetched(true, destination.resolve(bare), 0,
                bare + " is already in " + destination);
        }

        final URI where = URI.create(downloadBase + bare + ".tar.gz");
        Path archive = null;
        try {
            Files.createDirectories(destination);
            archive = Files.createTempFile("sphere-pdf-", ".tar.gz");
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
            HttpRequest request = HttpRequest.newBuilder(where)
                .timeout(Duration.ofMinutes(20))
                .GET()
                .build();
            HttpResponse<Path> answer = client.send(request,
                HttpResponse.BodyHandlers.ofFile(archive,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE));
            if (answer.statusCode() != 200) {
                return new Fetched(false, null, 0, "The server answered "
                    + answer.statusCode() + " for " + where
                    + (answer.statusCode() == 404
                       ? ". Check the name: ':root pdf search " + bare + "' looks for it."
                       : "."));
            }
            final long size = Files.size(archive);
            final int written = untar(archive, destination);
            final Path folder = destination.resolve(bare);
            if (!holdsMemberZero(folder)) {
                return new Fetched(false, null, size, "The archive unpacked " + written
                    + " files but " + bare + "_0000.dat is not among them.");
            }
            return new Fetched(true, folder, size,
                bare + " unpacked into " + destination + " (" + written + " files, "
                + (size / 1024 / 1024) + " MB downloaded)");

        } catch (IOException cannotFetch) {
            return new Fetched(false, null, 0, "Could not fetch " + where + ": "
                + cannotFetch.getMessage());
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return new Fetched(false, null, 0, "The download was interrupted.");
        } finally {
            if (archive != null) {
                try {
                    Files.deleteIfExists(archive);
                } catch (IOException leaveIt) {
                    // A leftover in the temp folder is not worth a message.
                }
            }
        }
    }

    private static Path firstWritable() {
        for (Path base : paths()) {
            if (Files.isDirectory(base) && Files.isWritable(base)) {
                return base;
            }
        }
        return null;
    }

    /**
     * Unpacks a gzipped tar.
     *
     * Only what the archives actually contain is handled: ordinary files and
     * directories, with the long-name extension some of them use. Anything
     * else is skipped rather than guessed at, and a name that climbs out of the
     * destination is refused outright.
     */
    private static int untar(Path archive, Path destination) throws IOException {
        int written = 0;
        final Path root = destination.toAbsolutePath().normalize();
        try (InputStream raw = Files.newInputStream(archive);
             GZIPInputStream in = new GZIPInputStream(raw, 1 << 16)) {

            byte[] header = new byte[512];
            String longName = null;
            while (true) {
                if (!readFully(in, header, 512)) {
                    break;
                }
                if (empty(header)) {
                    break;
                }
                String name = longName != null ? longName : text(header, 0, 100);
                longName = null;
                if (name.isEmpty()) {
                    continue;
                }
                final String prefix = text(header, 345, 155);
                if (!prefix.isEmpty() && !name.startsWith(prefix)) {
                    name = prefix + "/" + name;
                }
                final long size = octal(header, 124, 12);
                final char kind = (char) (header[156] & 0xff);

                if (kind == 'L') {
                    // The long-name extension: the next block holds the name.
                    byte[] buffer = new byte[(int) size];
                    readFully(in, buffer, buffer.length);
                    skip(in, pad(size));
                    longName = new String(buffer, StandardCharsets.UTF_8).trim()
                                   .replace("\u0000", "");
                    continue;
                }

                final Path at = root.resolve(name).normalize();
                if (!at.startsWith(root)) {
                    throw new IOException("The archive holds an entry that would be "
                        + "written outside " + root + ": " + name);
                }
                if (kind == '5' || name.endsWith("/")) {
                    Files.createDirectories(at);
                    skip(in, pad(size));
                    continue;
                }
                if (kind != '0' && kind != 0 && kind != '7') {
                    skip(in, size + pad(size));
                    continue;
                }
                if (at.getParent() != null) {
                    Files.createDirectories(at.getParent());
                }
                Path scratch = Files.createTempFile(at.getParent(), "sphere-", ".part");
                try (var out = Files.newOutputStream(scratch)) {
                    long left = size;
                    byte[] buffer = new byte[1 << 16];
                    while (left > 0) {
                        final int want = (int) Math.min(buffer.length, left);
                        final int got = in.read(buffer, 0, want);
                        if (got < 0) {
                            throw new IOException("The archive ended inside " + name);
                        }
                        out.write(buffer, 0, got);
                        left -= got;
                    }
                }
                Files.move(scratch, at, StandardCopyOption.REPLACE_EXISTING);
                skip(in, pad(size));
                written++;
            }
        }
        return written;
    }

    private static long pad(long size) {
        final long over = size % 512L;
        return over == 0 ? 0 : 512L - over;
    }

    private static boolean readFully(InputStream in, byte[] into, int count)
            throws IOException {
        int at = 0;
        while (at < count) {
            final int got = in.read(into, at, count - at);
            if (got < 0) {
                return at != 0 ? false : false;
            }
            at += got;
        }
        return true;
    }

    private static void skip(InputStream in, long count) throws IOException {
        long left = count;
        byte[] waste = new byte[512];
        while (left > 0) {
            final int got = in.read(waste, 0, (int) Math.min(waste.length, left));
            if (got < 0) {
                return;
            }
            left -= got;
        }
    }

    private static boolean empty(byte[] block) {
        for (byte one : block) {
            if (one != 0) {
                return false;
            }
        }
        return true;
    }

    private static String text(byte[] block, int from, int length) {
        int end = from;
        while (end < from + length && block[end] != 0) {
            end++;
        }
        return new String(block, from, end - from, StandardCharsets.UTF_8).trim();
    }

    private static long octal(byte[] block, int from, int length) {
        long out = 0;
        for (int i = from; i < from + length; i++) {
            final int c = block[i] & 0xff;
            if (c == 0 || c == ' ') {
                if (out != 0) {
                    break;
                }
                continue;
            }
            if (c < '0' || c > '7') {
                break;
            }
            out = out * 8 + (c - '0');
        }
        return out;
    }

    /** What the catalog can see, written out. */
    public static String report() {
        StringBuilder text = new StringBuilder("Folders searched, in order:");
        final List<Path> where = paths();
        if (where.isEmpty()) {
            text.append("\n  none. ':root pdf where add <folder>' names one, or set "
                + "LHAPDF_DATA_PATH.");
        }
        for (Path one : where) {
            int held = 0;
            if (Files.isDirectory(one)) {
                try (var list = Files.list(one)) {
                    held = (int) list.filter(RootPdfCatalog::holdsMemberZero).count();
                } catch (IOException cannotList) {
                    held = -1;
                }
            }
            text.append(String.format(Locale.ROOT, "%n  %-58s %s", one,
                !Files.isDirectory(one) ? "(not there)"
                    : held < 0 ? "(cannot be read)"
                    : held + " set" + (held == 1 ? "" : "s")
                      + (Files.isWritable(one) ? "" : ", read only")));
        }
        final Path idx = indexFile();
        text.append(String.format(Locale.ROOT, "%n%nIndex: %s",
            idx == null ? "none found, so a set cannot be looked up by name or number"
                        : idx + "  (" + index().size() + " published sets)"));
        return text.toString();
    }

    /** Unpacks a local archive, for a set fetched by other means. */
    public static Fetched install(Path archive, Path into) {
        final Path destination = into != null ? into : firstWritable();
        if (destination == null) {
            return new Fetched(false, null, 0, "No folder to install into.");
        }
        try {
            final long size = Files.size(archive);
            final int written = untar(archive, destination);
            return new Fetched(true, destination, size,
                written + " files unpacked into " + destination);
        } catch (IOException cannotRead) {
            return new Fetched(false, null, 0,
                "Could not unpack " + archive + ": " + cannotRead.getMessage());
        } catch (UncheckedIOException cannotRead) {
            return new Fetched(false, null, 0,
                "Could not unpack " + archive + ": " + cannotRead.getMessage());
        }
    }
}
