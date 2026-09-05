package com.sphere.core.fs;

import com.sphere.core.CommandRouter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
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
        Path target;
        if (cleaned.equals("~")) {
            target = Paths.get(System.getProperty("user.home"));
        } else if (cleaned.startsWith("~/") || cleaned.startsWith("~\\")) {
            target = Paths.get(System.getProperty("user.home")).resolve(cleaned.substring(2));
        } else {
            Path p = Paths.get(cleaned);
            target = p.isAbsolute() ? p : base.resolve(p);
        }
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

        for (String unix : new String[]{"/etc", "/usr", "/bin", "/sbin", "/lib", "/lib64",
                                        "/boot", "/dev", "/proc", "/sys", "/var", "/opt"}) {
            if (text.equals(unix) || text.startsWith(unix + "/")) return "a system directory";
        }

        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            String sr = systemRoot.replace('\\', '/').toLowerCase(Locale.ROOT);
            if (lower.equals(sr) || lower.startsWith(sr + "/")) return "a system directory";
        }
        for (String win : new String[]{"c:/windows", "c:/program files", "c:/program files (x86)"}) {
            if (lower.equals(win) || lower.startsWith(win + "/")) return "a system directory";
        }
        if (lower.equals("c:/users")) return "the users directory";

        return null;
    }

    public static BufferedReader utf8Reader(Path file) throws IOException {
        return new BufferedReader(new InputStreamReader(
                Files.newInputStream(file), StandardCharsets.UTF_8));
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

            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer decoded;
            try {
                decoded = decoder.decode(ByteBuffer.wrap(head, 0, read));
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
