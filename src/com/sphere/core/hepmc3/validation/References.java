package com.sphere.core.hepmc3.validation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The input files of HepMC3's tests and what the C++ tests gave: for each
 * run (refs/testIO1/...) the exit code (.exit), the text on stdout and stderr
 * (.stdout, .stderr) and every file it wrote; and the inputs (inputs/...).
 *
 * <p>They are kept in the jar as one zip, refdata/hepmc3-tests.zip, holding
 * a manifest ("path sha1" lines) and the distinct contents under blobs/, so
 * that the many identical files (outputs equal to inputs, the same event
 * written by several tests) are stored once. A directory with the refs/ and
 * inputs/ layout can be used instead (the system property hepmc3.refdir).
 *
 * <p>The references were made with HepMC3 3.03.01 built with MinGW-w64
 * (g++ 15, UCRT): rand() is Microsoft's, line ends were written as "\r\n"
 * and are kept here as "\n".
 */
public final class References {

    private static final String RESOURCE = "/com/sphere/core/hepmc3/validation/refdata/hepmc3-tests.zip";

    private static volatile References loaded;

    private final Map<String, byte[]> files;

    private References(Map<String, byte[]> files) {
        this.files = files;
    }

    /** The references, from hepmc3.refdir if set, else from the jar. */
    public static References get() throws IOException {
        References r = loaded;
        if (r != null) return r;
        synchronized (References.class) {
            if (loaded != null) return loaded;
            final String dir = System.getProperty("hepmc3.refdir");
            loaded = dir != null ? fromDirectory(Path.of(dir)) : fromJar();
            return loaded;
        }
    }

    public static boolean available() {
        try {
            get();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static References fromDirectory(Path root) throws IOException {
        final Map<String, byte[]> m = new TreeMap<>();
        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) continue;
                m.put(root.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return new References(m);
    }

    private static References fromJar() throws IOException {
        try (InputStream s = References.class.getResourceAsStream(RESOURCE)) {
            if (s == null) throw new IOException("the HepMC3 test references are not in the jar (and hepmc3.refdir is not set)");
            final Map<String, byte[]> entries = new HashMap<>();
            try (ZipInputStream z = new ZipInputStream(s)) {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    final ByteArrayOutputStream out = new ByteArrayOutputStream();
                    z.transferTo(out);
                    entries.put(e.getName(), out.toByteArray());
                }
            }
            final byte[] manifest = entries.get("manifest.txt");
            if (manifest == null) throw new IOException("hepmc3-tests.zip has no manifest");
            final Map<String, byte[]> m = new TreeMap<>();
            for (String line : new String(manifest, StandardCharsets.UTF_8).split("\n")) {
                final String t = line.strip();
                if (t.isEmpty()) continue;
                final int sp = t.lastIndexOf(' ');
                final byte[] blob = entries.get("blobs/" + t.substring(sp + 1));
                if (blob == null) throw new IOException("hepmc3-tests.zip: no content for " + t);
                m.put(t.substring(0, sp), blob);
            }
            return new References(m);
        }
    }

    /** A file, or null. */
    public byte[] file(String path) {
        return files.get(path);
    }

    public String text(String path) {
        final byte[] b = files.get(path);
        return b == null ? null : new String(b, StandardCharsets.ISO_8859_1);
    }

    /** Whether the C++ output of this run is known. */
    public boolean hasRun(String id) {
        return files.containsKey("refs/" + id + "/.exit");
    }

    /** The names of the files the C++ run wrote. */
    public List<String> outputs(String id) {
        final String prefix = "refs/" + id + "/";
        final List<String> out = new ArrayList<>();
        for (String k : files.keySet()) {
            if (!k.startsWith(prefix)) continue;
            final String name = k.substring(prefix.length());
            if (!name.startsWith(".") && !name.contains("/")) out.add(name);
        }
        return out;
    }

    /** The input of that name, or null. */
    public byte[] input(String name) {
        return files.get("inputs/" + name);
    }

    public List<String> inputs() {
        final List<String> out = new ArrayList<>();
        for (String k : files.keySet()) if (k.startsWith("inputs/")) out.add(k.substring("inputs/".length()));
        return out;
    }
}
