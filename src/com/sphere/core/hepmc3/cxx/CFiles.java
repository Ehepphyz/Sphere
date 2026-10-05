package com.sphere.core.hepmc3.cxx;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * The working directory of a C++ process, for the port: a file name given as
 * a string ("inputIO1.hepmc") is opened relative to it, and kept as given in
 * every message, as the C++ opens it relative to the directory it runs in.
 *
 * <p>The directory belongs to the thread (and to the threads it starts), so
 * that a validation can run its programs in a directory of their own while
 * the console goes on in its own.
 */
public final class CFiles {

    private static final InheritableThreadLocal<Path> CWD = new InheritableThreadLocal<>();

    private CFiles() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** The file a name designates: relative names resolved in this thread's directory, if it has one. */
    public static Path path(String filename) {
        final Path p = Path.of(filename);
        final Path cwd = CWD.get();
        return cwd == null || p.isAbsolute() ? p : cwd.resolve(p);
    }

    /** This thread's directory, or null for the JVM's own. */
    public static Path cwd() {
        return CWD.get();
    }

    /** Runs code with relative names resolved in dir, then puts the previous directory back. */
    public static <T> T in(Path dir, Callable<T> body) throws Exception {
        final Path before = CWD.get();
        CWD.set(dir);
        try {
            return body.call();
        } finally {
            if (before == null) CWD.remove();
            else CWD.set(before);
        }
    }
}
