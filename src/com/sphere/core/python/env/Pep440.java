package com.sphere.core.python.env;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versions and version specifiers as PEP 440 defines them, which is what pip
 * compares with: epochs, pre-, post- and development releases, local labels,
 * and the operators ~= == != <= >= < > === with the wildcard ==1.2.*.
 *
 * Sphere needs them on its side to choose, without starting Python, the
 * newest version of a package that every package depending on it accepts.
 */
public final class Pep440 {

    private Pep440() {
    }

    private static final Pattern VERSION = Pattern.compile(
        "^\\s*v?(?:(?:(?<epoch>[0-9]+)!)?(?<release>[0-9]+(?:\\.[0-9]+)*)"
            + "(?<pre>[-_.]?(?<prel>a|b|c|rc|alpha|beta|pre|preview)[-_.]?(?<pren>[0-9]+)?)?"
            + "(?<post>(?:-(?<postn1>[0-9]+))|(?:[-_.]?(?<postl>post|rev|r)[-_.]?(?<postn2>[0-9]+)?))?"
            + "(?<dev>[-_.]?(?<devl>dev)[-_.]?(?<devn>[0-9]+)?)?)"
            + "(?:\\+(?<local>[a-z0-9]+(?:[-_.][a-z0-9]+)*))?\\s*$",
        Pattern.CASE_INSENSITIVE);

    /** A version, comparable as PEP 440 orders them. */
    public static final class Version implements Comparable<Version> {
        public final String text;
        final int epoch;
        final long[] release;
        /** 'a', 'b' or 'c' (rc), 0 when none. */
        final char preLetter;
        final long preNumber;
        final long post;
        final long dev;
        final String local;

        private Version(String text, int epoch, long[] release, char preLetter, long preNumber, long post, long dev, String local) {
            this.text = text;
            this.epoch = epoch;
            this.release = release;
            this.preLetter = preLetter;
            this.preNumber = preNumber;
            this.post = post;
            this.dev = dev;
            this.local = local;
        }

        public boolean isPrerelease() {
            return preLetter != 0 || dev >= 0;
        }

        public boolean isPostrelease() {
            return post >= 0;
        }

        /** The n-th part of the release, 0 past its end: 2.1 has part(2) = 0. */
        public long part(int n) {
            return n < release.length ? release[n] : 0;
        }

        public int parts() {
            return release.length;
        }

        /** The same version without its local label and its pre, post and dev parts. */
        public Version base() {
            return new Version(text, epoch, release, (char) 0, 0, -1, -1, null);
        }

        @Override
        public int compareTo(Version o) {
            if (epoch != o.epoch) return Integer.compare(epoch, o.epoch);
            final int n = Math.max(release.length, o.release.length);
            for (int i = 0; i < n; i++) {
                final int c = Long.compare(part(i), o.part(i));
                if (c != 0) return c;
            }
            // dev without pre and post comes before every pre-release of the same release.
            final int c = Long.compare(preKey(), o.preKey());
            if (c != 0) return c;
            final int p = Long.compare(post, o.post);
            if (p != 0) return p;
            final int d = Long.compare(devKey(), o.devKey());
            if (d != 0) return d;
            if (local == null || o.local == null) return local == null ? (o.local == null ? 0 : -1) : 1;
            return compareLocal(local, o.local);
        }

        /** Where the pre-release puts the version: before any pre for a lone dev, after them all for a final. */
        private long preKey() {
            if (preLetter == 0 && post < 0 && dev >= 0) return Long.MIN_VALUE;
            if (preLetter == 0) return Long.MAX_VALUE;
            return (preLetter - 'a') * 1_000_000_000L + preNumber;
        }

        private long devKey() {
            return dev < 0 ? Long.MAX_VALUE : dev;
        }

        private static int compareLocal(String a, String b) {
            final String[] x = a.split("[-_.]");
            final String[] y = b.split("[-_.]");
            for (int i = 0; i < Math.min(x.length, y.length); i++) {
                final boolean nx = x[i].matches("[0-9]+");
                final boolean ny = y[i].matches("[0-9]+");
                final int c = nx && ny ? Long.compare(Long.parseLong(x[i]), Long.parseLong(y[i]))
                    : nx ? 1 : ny ? -1 : x[i].compareToIgnoreCase(y[i]);
                if (c != 0) return c;
            }
            return Integer.compare(x.length, y.length);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Version v && compareTo(v) == 0;
        }

        @Override
        public int hashCode() {
            long h = epoch;
            int n = release.length;
            while (n > 1 && release[n - 1] == 0) n--;
            for (int i = 0; i < n; i++) h = h * 31 + release[i];
            return Long.hashCode(h * 31 + preKey() * 7 + post * 3 + devKey());
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** Versions read before: the same few hundred come back thousands of times while choosing upgrades. */
    private static final Map<String, Object> PARSED = new ConcurrentHashMap<>();
    private static final Object INVALID = new Object();

    /** A version, or null when the text is not one PEP 440 reads. */
    public static Version parse(String text) {
        if (text == null) return null;
        if (PARSED.size() > 50_000) PARSED.clear();
        final Object v = PARSED.computeIfAbsent(text, t -> {
            final Version r = parseNow(t);
            return r == null ? INVALID : r;
        });
        return v == INVALID ? null : (Version) v;
    }

    private static Version parseNow(String text) {
        final Matcher m = VERSION.matcher(text);
        if (!m.matches()) return null;
        final int epoch = m.group("epoch") == null ? 0 : Integer.parseInt(m.group("epoch"));
        final String[] parts = m.group("release").split("\\.");
        final long[] release = new long[parts.length];
        for (int i = 0; i < parts.length; i++) release[i] = parseLong(parts[i]);
        char pre = 0;
        long preN = 0;
        if (m.group("prel") != null) {
            final String l = m.group("prel").toLowerCase(Locale.ROOT);
            pre = l.startsWith("a") ? 'a' : l.startsWith("b") ? 'b' : 'c';
            preN = m.group("pren") == null ? 0 : parseLong(m.group("pren"));
        }
        long post = -1;
        if (m.group("post") != null) {
            final String n = m.group("postn1") != null ? m.group("postn1") : m.group("postn2");
            post = n == null ? 0 : parseLong(n);
        }
        long dev = -1;
        if (m.group("dev") != null) dev = m.group("devn") == null ? 0 : parseLong(m.group("devn"));
        final String local = m.group("local") == null ? null : m.group("local").toLowerCase(Locale.ROOT);
        return new Version(text.strip(), epoch, release, pre, preN, post, dev, local);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE / 4;
        }
    }

    /** How far apart two versions are, for the colour of an update. */
    public enum Jump { NONE, PATCH, MINOR, MAJOR, DOWNGRADE }

    public static Jump jump(Version from, Version to) {
        if (from == null || to == null) return Jump.NONE;
        final int c = to.compareTo(from);
        if (c == 0) return Jump.NONE;
        if (c < 0) return Jump.DOWNGRADE;
        if (to.epoch != from.epoch || to.part(0) != from.part(0)) return Jump.MAJOR;
        // 0.x: a change of the second number breaks as a major one does (semver's rule for 0.x).
        if (to.part(1) != from.part(1)) return from.part(0) == 0 ? Jump.MAJOR : Jump.MINOR;
        return Jump.PATCH;
    }

    /* ------------------------------------------------------------------ */
    /* Specifiers                                                          */
    /* ------------------------------------------------------------------ */

    private record Clause(String op, String version, Version parsed, boolean wildcard) {
    }

    /** A specifier set: ">=1.20,<2", "~=3.8", "==1.2.*", or "" for any version. */
    public static final class Specifier {
        public final String text;
        private final List<Clause> clauses;

        private Specifier(String text, List<Clause> clauses) {
            this.text = text;
            this.clauses = clauses;
        }

        public boolean isEmpty() {
            return clauses.isEmpty();
        }

        /** Whether a clause names a pre-release, which lets pre-releases in. */
        public boolean mentionsPrerelease() {
            for (Clause c : clauses) if (c.parsed != null && c.parsed.isPrerelease()) return true;
            return false;
        }

        /** Whether the version satisfies every clause; pre-releases only when allowed or named. */
        public boolean contains(Version v, boolean prereleases) {
            if (v == null) return false;
            if (v.isPrerelease() && !prereleases && !mentionsPrerelease()) return false;
            for (Clause c : clauses) if (!matches(c, v)) return false;
            return true;
        }

        public boolean contains(String version) {
            final Version v = parse(version);
            return v != null && contains(v, true);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private static final Pattern CLAUSE = Pattern.compile("^\\s*(~=|===|==|!=|<=|>=|<|>)\\s*([^\\s,;]+)\\s*$");
    private static final Map<String, Specifier> SPECIFIERS = new ConcurrentHashMap<>();

    /** A specifier set; clauses it cannot read are ignored, as accepting is the safer guess. */
    public static Specifier specifier(String text) {
        final String t = text == null ? "" : text.strip();
        if (SPECIFIERS.size() > 20_000) SPECIFIERS.clear();
        return SPECIFIERS.computeIfAbsent(t, Pep440::specifierNow);
    }

    private static Specifier specifierNow(String t) {
        final List<Clause> out = new ArrayList<>();
        if (!t.isEmpty()) {
            for (String part : t.split(",")) {
                final Matcher m = CLAUSE.matcher(part);
                if (!m.matches()) continue;
                String v = m.group(2);
                final boolean wildcard = v.endsWith(".*");
                if (wildcard) v = v.substring(0, v.length() - 2);
                out.add(new Clause(m.group(1), v, parse(v), wildcard));
            }
        }
        return new Specifier(t, out);
    }

    private static boolean matches(Clause c, Version v) {
        if (c.op.equals("===")) return v.text.equalsIgnoreCase(c.version);
        final Version s = c.parsed;
        if (s == null) return true;
        switch (c.op) {
            case "==" -> {
                if (c.wildcard) return prefixMatch(v, s);
                // Without a local label in the specifier, the candidate's local label is ignored.
                return (s.local == null ? withoutLocal(v) : v).compareTo(s) == 0;
            }
            case "!=" -> {
                if (c.wildcard) return !prefixMatch(v, s);
                return (s.local == null ? withoutLocal(v) : v).compareTo(s) != 0;
            }
            case ">=" -> {
                return withoutLocal(v).compareTo(s) >= 0;
            }
            case "<=" -> {
                return withoutLocal(v).compareTo(s) <= 0;
            }
            case ">" -> {
                // >1.7 does not let 1.7.post1 in, nor 1.7+local.
                if (withoutLocal(v).compareTo(s) <= 0) return false;
                return !(v.isPostrelease() && !s.isPostrelease() && sameRelease(v, s));
            }
            case "<" -> {
                // <1.7 does not let 1.7rc1 in unless the specifier is itself a pre-release.
                if (withoutLocal(v).compareTo(s) >= 0) return false;
                return !(v.isPrerelease() && !s.isPrerelease() && sameRelease(v, s));
            }
            case "~=" -> {
                // ~=2.2.1 means >=2.2.1, ==2.2.*
                if (withoutLocal(v).compareTo(s) < 0) return false;
                final int keep = Math.max(1, s.release.length - 1);
                for (int i = 0; i < keep; i++) if (v.part(i) != s.part(i)) return false;
                return v.epoch == s.epoch;
            }
            default -> {
                return true;
            }
        }
    }

    private static boolean sameRelease(Version a, Version b) {
        final int n = Math.max(a.release.length, b.release.length);
        for (int i = 0; i < n; i++) if (a.part(i) != b.part(i)) return false;
        return a.epoch == b.epoch;
    }

    private static Version withoutLocal(Version v) {
        return v.local == null ? v : new Version(v.text, v.epoch, v.release, v.preLetter, v.preNumber, v.post, v.dev, null);
    }

    /** ==1.2.* : the first parts of the release equal. */
    private static boolean prefixMatch(Version v, Version prefix) {
        if (v.epoch != prefix.epoch) return false;
        for (int i = 0; i < prefix.release.length; i++) if (v.part(i) != prefix.part(i)) return false;
        return true;
    }

    /** A distribution name as pip compares them: lower case, runs of - _ . as one -. */
    public static String normalize(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }
}
