package com.sphere.core.fs;

import com.sphere.core.CommandRouter;
import com.sphere.utils.OSValidator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared ground for the file system plugins: argument splitting, home expansion,
 * size formatting and the guards that keep a destructive command away from a
 * system directory. Everything here is pure Java, so Windows, Linux, macOS and
 * WSL behave identically.
 */
public final class FsSupport {

    private FsSupport() { }

    /** Quoted paths with spaces, single or double, or a bare token. */
    private static final Pattern ARG_SPLIT = Pattern.compile(
        "\"((?:\\\\\"|[^\"])+)\"|'((?:\\\\'|[^'])+)'|([^\\s]+)"
    );

    public static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public static List<String> tokenize(String input) {
        List<String> out = new ArrayList<>();
        if (input == null) return out;
        Matcher m = ARG_SPLIT.matcher(input);
        while (m.find()) {
            if (m.group(1) != null) out.add(m.group(1).replace("\\\"", "\""));
            else if (m.group(2) != null) out.add(m.group(2).replace("\\'", "'"));
            else out.add(m.group(3));
        }
        return out;
    }

    /**
     * Turns a path of the other world into one this system can open.
     *
     * Windows and WSL share a disk and name it two ways. Under WSL, C:\Users\x
     * is /mnt/c/Users/x; under Windows, /mnt/c/Users/x is C:\Users\x. A path
     * copied from one window and pasted in the other named nothing, when the
     * file was there the whole time. Anywhere else, and for anything that is
     * not one of those two shapes, the text comes back untouched.
     */
    public static String acrossWsl(String path) {
        if (path == null || path.length() < 2) {
            return path;
        }
        if (OSValidator.isWindows()) {
            // /mnt/c/x, and the /c/x that Git Bash and MSYS use, -> C:\x
            Matcher mounted = WSL_MOUNT.matcher(path.replace(BACKSLASH, '/'));
            if (mounted.matches()) {
                String rest = mounted.group(2) == null
                    ? String.valueOf(BACKSLASH) : mounted.group(2).replace('/', BACKSLASH);
                return mounted.group(1).toUpperCase(Locale.ROOT) + ":" + rest;
            }
            return path;
        }
        // C:\x and C:/x -> /mnt/c/x, where that mount exists
        if (path.length() >= 3 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':'
                && (path.charAt(2) == BACKSLASH || path.charAt(2) == '/')) {
            String mounted = "/mnt/" + Character.toLowerCase(path.charAt(0))
                           + path.substring(2).replace(BACKSLASH, '/');
            if (Files.exists(Paths.get(mounted))) {
                return mounted;
            }
        }
        return path;
    }

    private static final char BACKSLASH = '\\';

    private static final Pattern WSL_MOUNT =
        Pattern.compile("^(?:/mnt)?/([A-Za-z])(/.*)?$");

    /**
     * Expands a leading ~ and translates a path of the other world.
     *
     * Four plugins carried the same expansion and none of them translated,
     * so :stat opened a Windows path under WSL and :cat, :ls, :mkdir and
     * :symlink did not.
     */
    public static String expandHome(String path) {
        if (path == null || path.isEmpty()) return path;
        if (path.equals("~")) {
            return System.getProperty("user.home");
        }
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + java.io.File.separator + path.substring(2);
        }
        if (path.startsWith("~" + BACKSLASH)) {
            // Typed the Windows way, so the rest of it is read the Windows way
            // too: ~\\Documents\\note.txt is one path under WSL, not a home
            // directory holding a file whose name carries two backslashes.
            return System.getProperty("user.home") + java.io.File.separator
                 + path.substring(2).replace(BACKSLASH, java.io.File.separatorChar);
        }
        return acrossWsl(path);
    }

    /** Accepts ~, ~/sub and ~\sub on every platform, then normalizes. */
    public static Path resolve(CommandRouter router, String arg) {
        String cleaned = arg == null ? "" : arg.trim();
        if (cleaned.length() > 1
                && ((cleaned.charAt(0) == '"' && cleaned.endsWith("\""))
                 || (cleaned.charAt(0) == '\'' && cleaned.endsWith("'")))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }

        Path base = router != null ? router.getCurrentDirectory()
                                   : Paths.get(System.getProperty("user.dir"));

        // Windows refuses * ? " < > | and : in a path, so Paths.get throws
        // there on a name the shell would have expanded, where Linux and
        // macOS accept it and the caller reports "not found". The type is
        // kept -- sixteen callers rely on a path coming back -- but the
        // message now says what happened instead of naming a character.
        final Path typed;
        try {
            typed = Paths.get(expandHome(cleaned));
        } catch (java.nio.file.InvalidPathException notAPath) {
            throw new java.nio.file.InvalidPathException(cleaned,
                "this system does not allow that character in a file name; "
                + "wildcards are not expanded here, so name the file");
        }
        Path target = typed.isAbsolute() ? typed : base.resolve(typed);
        return target.toAbsolutePath().normalize();
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] unit = {"KiB", "MiB", "GiB", "TiB", "PiB"};
        double value = bytes;
        int i = -1;
        while (value >= 1024 && i < unit.length - 1) { value /= 1024; i++; }
        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s", value, unit[i]);
    }

    /**
     * Refuses a path that no console command should ever walk into destructively:
     * a filesystem root, the user home itself, and the usual system trees.
     * Returns the reason, or null when the path is fair game.
     */
    public static String protectedReason(Path path) {
        if (path == null) return "no path";
        Path p = path.toAbsolutePath().normalize();

        if (p.getParent() == null) return "filesystem root";

        Path home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (p.equals(home)) return "your home directory";

        String text = p.toString().replace('\\', '/');
        String lower = text.toLowerCase(Locale.ROOT);

        // Compared in lower case throughout. Windows and macOS both have
        // case-insensitive volumes by default, so /USR/local and C:/WINDOWS
        // name the same directories as their lower-case spellings and have to
        // be refused with them; a case-sensitive comparison let them through.
        for (String unix : new String[]{"/etc", "/usr", "/bin", "/sbin", "/lib", "/lib64",
                                        "/boot", "/dev", "/proc", "/sys", "/var", "/opt",
                                        // macOS keeps its own system trees elsewhere, and
                                        // none of them are covered by the list above.
                                        "/system", "/library", "/applications", "/private",
                                        "/volumes", "/cores"}) {
            if (lower.equals(unix) || lower.startsWith(unix + "/")) return "a system directory";
        }

        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            String sr = systemRoot.replace('\\', '/').toLowerCase(Locale.ROOT);
            if (lower.equals(sr) || lower.startsWith(sr + "/")) return "a system directory";
        }

        // Any drive letter, not only C. A machine with Windows on D: was
        // protected nowhere, and under WSL the same trees appear under
        // /mnt/<letter>/ where SystemRoot is not set at all and none of the
        // spellings above can match.
        for (String mounted : new String[]{"", "/mnt", "/cygdrive", "/media"}) {
            for (char drive = 'a'; drive <= 'z'; drive++) {
                final String root = mounted.isEmpty()
                    ? drive + ":/"
                    : mounted + "/" + drive + "/";
                if (!lower.startsWith(root)) {
                    continue;
                }
                final String rest = lower.substring(root.length());
                if (rest.isEmpty()) {
                    return "a drive root";
                }
                for (String win : new String[]{"windows", "program files",
                                               "program files (x86)", "programdata"}) {
                    if (rest.equals(win) || rest.startsWith(win + "/")) {
                        return "a system directory";
                    }
                }
                if (rest.equals("users")) return "the users directory";
            }
        }

        return null;
    }

    /**
     * A reader for a text file, whatever the platform wrote it in.
     *
     * UTF-8 is the usual answer and the one tried first. It is not the only
     * one on Windows: PowerShell's redirection writes UTF-16 little endian by
     * default, and Notepad wrote the local code page for years. Reading either
     * as UTF-8 gave a file full of NUL characters, which every tool here then
     * reported as binary and refused. The byte order mark settles it when
     * there is one; otherwise UTF-8 is used if the head of the file decodes as
     * UTF-8, and the platform charset if it does not.
     */
    public static BufferedReader utf8Reader(Path file) throws IOException {
        final Charset charset = charsetOf(file);
        var in = Files.newInputStream(file);
        // The mark is metadata, not text. Left in, it becomes an invisible
        // first character that breaks a grep anchored at the start of a line
        // and shows as a stray glyph at the head of a cat.
        final long mark = markLength(file, charset);
        if (mark > 0) {
            in.skipNBytes(mark);
        }
        return new BufferedReader(new InputStreamReader(in, charset));
    }

    private static long markLength(Path file, Charset charset) {
        byte[] head = new byte[3];
        int read;
        try (var in = Files.newInputStream(file)) {
            read = in.read(head);
        } catch (IOException cannotRead) {
            return 0;
        }
        if (read >= 3 && (head[0] & 0xff) == 0xEF && (head[1] & 0xff) == 0xBB
                      && (head[2] & 0xff) == 0xBF) {
            return 3;
        }
        if (read >= 2 && (head[0] & 0xff) == 0xFF && (head[1] & 0xff) == 0xFE) {
            return 2;
        }
        if (read >= 2 && (head[0] & 0xff) == 0xFE && (head[1] & 0xff) == 0xFF) {
            return 2;
        }
        return 0;
    }

    /** What a file is most likely written in. */
    public static Charset charsetOf(Path file) {
        byte[] head = new byte[8192];
        int read;
        try (var in = Files.newInputStream(file)) {
            read = in.read(head);
        } catch (IOException cannotRead) {
            return StandardCharsets.UTF_8;
        }
        if (read <= 0) {
            return StandardCharsets.UTF_8;
        }
        // A byte order mark is the file saying so itself.
        if (read >= 2 && (head[0] & 0xff) == 0xFF && (head[1] & 0xff) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (read >= 2 && (head[0] & 0xff) == 0xFE && (head[1] & 0xff) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        if (read >= 3 && (head[0] & 0xff) == 0xEF && (head[1] & 0xff) == 0xBB
                      && (head[2] & 0xff) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        // Tested before UTF-8, because a NUL byte is valid UTF-8 and ASCII text
        // written as UTF-16 is nothing but letters separated by NUL: it passes
        // the UTF-8 test and then reads as text full of holes.
        // UTF-16 without a mark shows as every other byte being zero, which is
        // what PowerShell writes for ASCII text.
        if (read >= 4) {
            int zeroOdd = 0;
            int zeroEven = 0;
            for (int i = 0; i + 1 < read; i += 2) {
                if (head[i] == 0) zeroEven++;
                if (head[i + 1] == 0) zeroOdd++;
            }
            final int pairs = read / 2;
            if (zeroOdd * 2 > pairs && zeroEven * 4 < pairs) {
                return StandardCharsets.UTF_16LE;
            }
            if (zeroEven * 2 > pairs && zeroOdd * 4 < pairs) {
                return StandardCharsets.UTF_16BE;
            }
        }
        if (decodes(head, read, StandardCharsets.UTF_8)) {
            return StandardCharsets.UTF_8;
        }
        // The local code page, which is what Notepad wrote for years. When even
        // that refuses the bytes, Latin-1 accepts every one of them: a file is
        // then read as text and judged binary by its control characters rather
        // than by an encoding it was never written in.
        final Charset local = Charset.defaultCharset();
        if (local != StandardCharsets.UTF_8 && decodes(head, read, local)) {
            return local;
        }
        return StandardCharsets.ISO_8859_1;
    }

    private static boolean decodes(byte[] bytes, int length, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes, 0, length));
            return true;
        } catch (CharacterCodingException notThat) {
            return false;
        }
    }

    /**
     * Decodes the head of a file as UTF-8 and counts control characters. Byte
     * level tests fail here: an accent is two bytes and a box drawing character
     * three, so a French or semigraphic text file reads as binary.
     */
    public static boolean looksBinary(Path file) {
        try {
            byte[] head = new byte[8192];
            int read;
            try (var in = Files.newInputStream(file)) {
                read = in.read(head);
            }
            if (read <= 0) return false;

            // Judged in the charset the file is actually written in. Judging
            // every file as UTF-8 made a UTF-16 one look like text separated by
            // NUL bytes, which is exactly the shape this test calls binary.
            final Charset charset = charsetOf(file);
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer decoded;
            try {
                // An odd byte count in UTF-16 is a truncated head, not a defect.
                final int usable = (charset == StandardCharsets.UTF_16LE
                                 || charset == StandardCharsets.UTF_16BE)
                                 ? read - (read % 2) : read;
                decoded = decoder.decode(ByteBuffer.wrap(head, 0, usable));
            } catch (CharacterCodingException notText) {
                return true;
            }

            int control = 0;
            for (int i = 0; i < decoded.length(); i++) {
                char c = decoded.charAt(i);
                if (c == '\n' || c == '\r' || c == '\t') continue;
                if (c == 0) return true;
                if (Character.isISOControl(c)) control++;
            }
            return decoded.length() > 0 && control * 10 > decoded.length();
        } catch (IOException e) {
            return false;
        }
    }

    /** What ends the lines of a text file, and whether the last one is ended. */
    public record LineEndings(long crlf, long lf, long cr, boolean finalNewline) {
        /** CRLF, LF, CR, both of the two that occur, or none for a single line. */
        public String name() {
            List<String> seen = new ArrayList<>(3);
            if (crlf > 0) seen.add("CRLF");
            if (lf > 0) seen.add("LF");
            if (cr > 0) seen.add("CR");
            return seen.isEmpty() ? "none" : String.join("+", seen);
        }

        public boolean mixed() {
            return (crlf > 0 ? 1 : 0) + (lf > 0 ? 1 : 0) + (cr > 0 ? 1 : 0) > 1;
        }
    }

    /**
     * Counts the line terminators of a file.
     *
     * A reader hands back lines with the terminator removed, so a file written
     * on Windows and the same text written on Linux read the same and a diff
     * of the two reports no difference, when git and every compiler see two
     * different files. Counting them is what lets the answer say so.
     */
    public static LineEndings lineEndings(Path file) throws IOException {
        long crlf = 0, lf = 0, cr = 0;
        int last = -1;
        try (java.io.InputStream in = new java.io.BufferedInputStream(Files.newInputStream(file))) {
            int b, previous = -1;
            while ((b = in.read()) >= 0) {
                if (b == '\n') {
                    if (previous == '\r') {
                        crlf++;
                        cr--;
                    } else {
                        lf++;
                    }
                } else if (b == '\r') {
                    cr++;
                }
                previous = b;
                last = b;
            }
        }
        return new LineEndings(crlf, lf, cr, last == '\n' || last == '\r');
    }

    /**
     * Deletes a tree and reports what it could not delete.
     *
     * A file another program holds open refuses to be deleted on Windows and
     * accepts on Unix, so a walk that stops on the first refusal empties the
     * tree on one system and half of it on the other. This one keeps going and
     * hands back the paths it had to leave, so the caller says what remains.
     */
    public static List<Path> deleteTree(Path root) throws IOException {
        final List<Path> refused = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException held) {
                    refused.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException unreadable) {
                // Listing it failed, deleting it may still work: a dangling
                // symbolic link reads as a failure and removes cleanly.
                try {
                    Files.deleteIfExists(file);
                } catch (IOException held) {
                    refused.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException walkFailure) {
                try {
                    Files.deleteIfExists(dir);
                } catch (IOException notEmpty) {
                    refused.add(dir);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return refused;
    }

    /** The first few of what a delete left behind, for one line of report. */
    public static String refusedSummary(List<Path> refused) {
        if (refused.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        b.append(refused.size()).append(refused.size() == 1 ? " entry" : " entries")
         .append(" could not be removed: ");
        for (int i = 0; i < Math.min(3, refused.size()); i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(refused.get(i));
        }
        if (refused.size() > 3) {
            b.append(", and ").append(refused.size() - 3).append(" more");
        }
        return b.toString();
    }

    /**
     * Whether an entry is hidden, by one rule on all four systems.
     *
     * Files.isHidden answers with the platform: on Windows it reads the DOS
     * attribute and calls .gitignore visible, on Unix it looks at the leading
     * dot. A tree carried from one system to the other would then be listed
     * differently, so both rules count here: a leading dot, or the attribute
     * where there is one.
     */
    public static boolean isHidden(Path path) {
        Path name = path.getFileName();
        String text = name == null ? "" : name.toString();
        // The two directories every directory holds are not hidden, and
        // Files.isHidden calls them hidden on Unix because of the dot.
        if (text.equals(".") || text.equals("..")) {
            return false;
        }
        if (text.startsWith(".")) {
            return true;
        }
        try {
            return Files.isHidden(path);
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * A glob that means the same thing on every system.
     *
     * The matcher the platform hands out follows the platform: on Windows it
     * folds case, so ":find -name '*.ROOT'" returns the .root files there and
     * nothing on Linux or macOS. The same command has to answer the same way,
     * so the pattern is compiled here and case is decided by the option, not
     * by the machine.
     *
     * Syntax is the usual one: * within a name, ** across directories, ?, a
     * [abc] or [a-z] class with [!...] to negate it, {a,b} alternatives, and a
     * backslash to take the next character literally.
     */
    public static Pattern glob(String pattern, boolean ignoreCase) {
        StringBuilder out = new StringBuilder(pattern.length() * 2);
        int depth = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '\\' -> {
                    if (++i == pattern.length()) {
                        throw new IllegalArgumentException("pattern ends on a backslash");
                    }
                    out.append(Pattern.quote(String.valueOf(pattern.charAt(i))));
                }
                case '*' -> {
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                        out.append(".*");
                        i++;
                    } else {
                        out.append("[^/\\\\]*");
                    }
                }
                case '?' -> out.append("[^/\\\\]");
                case '[' -> {
                    int close = closingBracket(pattern, i);
                    if (close < 0) {
                        out.append("\\[");
                        break;
                    }
                    out.append('[');
                    int j = i + 1;
                    if (j < close && (pattern.charAt(j) == '!' || pattern.charAt(j) == '^')) {
                        out.append('^');
                        j++;
                    }
                    for (; j < close; j++) {
                        char k = pattern.charAt(j);
                        if (k == '\\' || k == '[' || k == ']' || k == '&' || k == '^') {
                            out.append('\\');
                        }
                        out.append(k);
                    }
                    out.append(']');
                    i = close;
                }
                case '{' -> {
                    depth++;
                    out.append("(?:");
                }
                case '}' -> {
                    if (depth > 0) {
                        depth--;
                        out.append(')');
                    } else {
                        out.append("\\}");
                    }
                }
                case ',' -> out.append(depth > 0 ? "|" : "\\,");
                default -> {
                    if (".()+|^$@%".indexOf(c) >= 0) {
                        out.append('\\');
                    }
                    out.append(c);
                }
            }
        }
        if (depth != 0) {
            throw new IllegalArgumentException("unclosed { in the pattern");
        }
        int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        return Pattern.compile(out.toString(), flags);
    }

    /** Where the class opened at i ends, or -1 when it never does. */
    private static int closingBracket(String pattern, int open) {
        int i = open + 1;
        if (i < pattern.length() && (pattern.charAt(i) == '!' || pattern.charAt(i) == '^')) {
            i++;
        }
        if (i < pattern.length() && pattern.charAt(i) == ']') {
            i++;
        }
        for (; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == ']') {
                return i;
            }
        }
        return -1;
    }

    /** POSIX permissions where the platform has them, a plain approximation elsewhere. */
    public static String permissions(Path path) {
        try {
            return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
        } catch (UnsupportedOperationException | IOException noPosix) {
            StringBuilder b = new StringBuilder();
            b.append(Files.isReadable(path) ? 'r' : '-');
            b.append(Files.isWritable(path) ? 'w' : '-');
            b.append(Files.isExecutable(path) ? 'x' : '-');
            return b + " (approximate)";
        }
    }
}
