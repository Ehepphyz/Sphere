package com.sphere.core.python.env;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * An environment's time machine: before every change the manager writes
 * what is installed (name==version, as pip freeze), and any of these
 * snapshots can be compared with today and gone back to.
 */
public final class PySnapshots {

    private static final int KEEP = 40;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private PySnapshots() {
    }

    /** One snapshot: when, why, and the versions. */
    public record Snapshot(Path file, Instant time, String reason, Map<String, String> pins) {
        public String label() {
            return LocalDateTime.ofInstant(time, ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                + "  ·  " + pins.size() + " packages  ·  " + reason;
        }
    }

    /** What differs between a snapshot and now. */
    public record Diff(Map<String, String[]> changed, Map<String, String> added, Map<String, String> removed) {
        public boolean empty() {
            return changed.isEmpty() && added.isEmpty() && removed.isEmpty();
        }
    }

    /** The folder of an interpreter's snapshots. */
    static Path folder(PyEnv env) {
        final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(env.executable.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        return Path.of("config", "python-snapshots", String.format("%08x", crc.getValue())).toAbsolutePath();
    }

    /** Writes what is installed now. */
    public static Snapshot save(PyEnv env, String reason) throws IOException {
        final Path dir = folder(env);
        Files.createDirectories(dir);
        final Instant now = Instant.now();
        final Path file = dir.resolve(LocalDateTime.ofInstant(now, ZoneId.systemDefault()).format(STAMP) + ".txt");
        final StringBuilder b = new StringBuilder();
        b.append("# Sphere snapshot of a Python environment\n");
        b.append("# python: ").append(env.executable).append('\n');
        b.append("# version: ").append(env.version).append('\n');
        b.append("# time: ").append(now).append('\n');
        b.append("# reason: ").append(reason.replace('\n', ' ')).append('\n');
        final Map<String, String> pins = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (PyEnv.Pkg p : env.packages.values()) pins.put(p.name, p.version);
        for (Map.Entry<String, String> e : pins.entrySet()) b.append(e.getKey()).append("==").append(e.getValue()).append('\n');
        Files.writeString(file, b.toString(), StandardCharsets.UTF_8);
        prune(dir);
        return new Snapshot(file, now, reason, pins);
    }

    private static void prune(Path dir) throws IOException {
        final List<Path> files = files(dir);
        for (int i = KEEP; i < files.size(); i++) Files.deleteIfExists(files.get(i));
    }

    private static List<Path> files(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".txt"))
                .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString())).toList();
        }
    }

    /** The snapshots of an interpreter, newest first. */
    public static List<Snapshot> list(PyEnv env) {
        final List<Snapshot> out = new ArrayList<>();
        try {
            for (Path f : files(folder(env))) {
                final Snapshot s = read(f);
                if (s != null) out.add(s);
            }
        } catch (IOException ignored) {
            // none
        }
        return out;
    }

    /** A snapshot, or any requirements file of exact pins. */
    public static Snapshot read(Path f) {
        try {
            Instant time = Files.getLastModifiedTime(f).toInstant();
            String reason = f.getFileName().toString();
            final Map<String, String> pins = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                final String l = line.strip();
                if (l.startsWith("# time:")) {
                    try {
                        time = Instant.parse(l.substring(7).strip());
                    } catch (RuntimeException ignored) {
                        // the file's date
                    }
                } else if (l.startsWith("# reason:")) {
                    reason = l.substring(9).strip();
                } else if (!l.startsWith("#") && l.contains("==")) {
                    final String[] p = l.split(";", 2)[0].split("==", 2);
                    pins.put(p[0].strip(), p[1].strip());
                }
            }
            return new Snapshot(f, time, reason, pins);
        } catch (IOException e) {
            return null;
        }
    }

    /** What changed since a snapshot. */
    public static Diff diff(Snapshot s, PyEnv now) {
        final Map<String, String[]> changed = new LinkedHashMap<>();
        final Map<String, String> added = new LinkedHashMap<>();
        final Map<String, String> removed = new LinkedHashMap<>();
        final Map<String, String> then = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : s.pins().entrySet()) then.put(Pep440.normalize(e.getKey()), e.getKey());
        for (Map.Entry<String, String> e : s.pins().entrySet()) {
            final PyEnv.Pkg p = now.packages.get(Pep440.normalize(e.getKey()));
            if (p == null) removed.put(e.getKey(), e.getValue());
            else if (!p.version.equals(e.getValue())) changed.put(e.getKey(), new String[]{e.getValue(), p.version});
        }
        for (PyEnv.Pkg p : now.packages.values()) if (!then.containsKey(p.key)) added.put(p.name, p.version);
        return new Diff(changed, added, removed);
    }

    /** pip install arguments that bring back the snapshot's versions. */
    public static List<String> restoreInstall(Diff d) {
        final List<String> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : d.changed().entrySet()) out.add(e.getKey() + "==" + e.getValue()[0]);
        for (Map.Entry<String, String> e : d.removed().entrySet()) out.add(e.getKey() + "==" + e.getValue());
        return out;
    }

    /** What to uninstall to come back to the snapshot: what was added since, the tooling kept. */
    public static List<String> restoreUninstall(Diff d) {
        final List<String> out = new ArrayList<>();
        for (String name : d.added().keySet()) if (!PyEnv.TOOLING.contains(Pep440.normalize(name))) out.add(name);
        return out;
    }

    /**
     * requirements.txt of the environment: every package pinned, or only
     * those the user asked for (pip resolves the rest), which ages better.
     */
    public static String requirements(PyEnv env, boolean directOnly) {
        final StringBuilder b = new StringBuilder();
        b.append("# Python ").append(env.version).append(" — ").append(env.executable).append('\n');
        b.append(directOnly ? "# the packages installed on purpose; pip resolves their dependencies\n"
            : "# every package, pinned (pip freeze)\n");
        final Map<String, PyEnv.Pkg> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (PyEnv.Pkg p : env.packages.values()) sorted.put(p.name, p);
        for (PyEnv.Pkg p : sorted.values()) {
            if (PyEnv.TOOLING.contains(p.key)) continue;
            if (directOnly && !p.requested && !p.requiredBy.isEmpty()) continue;
            b.append(p.name).append(directOnly ? ">=" : "==").append(p.version).append('\n');
        }
        return b.toString();
    }
}
