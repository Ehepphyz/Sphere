package com.sphere.core.python.env;

import com.sphere.components.rootview.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The imports of a folder of scripts and notebooks set against an
 * environment: which modules are the standard library's, which an installed
 * distribution provides, which are the project's own, and which are missing
 * with the PyPI distribution that provides them (cv2 is opencv-python,
 * sklearn scikit-learn...). Notebooks' own "!pip install" lines are read too.
 */
public final class ImportScanner {

    private ImportScanner() {
    }

    /** A module and what to do about it. */
    public record Use(String module, Set<String> files, String status, String distribution, String note) {
        public boolean missing() {
            return status.equals("missing");
        }
    }

    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+([A-Za-z_][\\w.]*(?:\\s+as\\s+\\w+)?(?:\\s*,\\s*[A-Za-z_][\\w.]*(?:\\s+as\\s+\\w+)?)*)");
    private static final Pattern FROM = Pattern.compile("^\\s*from\\s+([A-Za-z_][\\w.]*)\\s+import\\b");
    private static final Pattern PIP = Pattern.compile("^\\s*[!%]\\s*pip\\s+install\\s+(.+)$");
    private static final Set<String> SKIP = Set.of(".git", "__pycache__", "node_modules", "site-packages", ".venv", "venv",
        "env", ".tox", ".mypy_cache", ".ipynb_checkpoints", "build", "dist");

    /** The modules imported under root (or by one file), each with the files that import it; and the pip lines met. */
    public static Map<String, Set<String>> scan(Path root, int maxFiles, Set<String> pipHints) throws IOException {
        final Map<String, Set<String>> out = new TreeMap<>();
        final List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(root)) {
            files.add(root);
        } else {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    final String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(root) && (SKIP.contains(n) || Files.isRegularFile(dir.resolve("pyvenv.cfg")))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return files.size() >= maxFiles ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    final String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".py") || n.endsWith(".ipynb") || n.endsWith(".pyw")) files.add(file);
                    return files.size() >= maxFiles ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        for (Path f : files) {
            final String rel = Files.isRegularFile(root) ? f.getFileName().toString() : root.relativize(f).toString();
            for (String line : lines(f)) {
                final Matcher pip = PIP.matcher(line);
                if (pip.find() && pipHints != null) {
                    for (String w : pip.group(1).split("\\s+")) if (!w.startsWith("-") && !w.isBlank()) pipHints.add(w);
                    continue;
                }
                final Matcher from = FROM.matcher(line);
                if (from.find()) {
                    out.computeIfAbsent(from.group(1).split("\\.")[0], k -> new LinkedHashSet<>()).add(rel);
                    continue;
                }
                final Matcher imp = IMPORT.matcher(line);
                if (imp.find()) {
                    for (String part : imp.group(1).split(",")) {
                        final String name = part.strip().split("\\s+")[0].split("\\.")[0];
                        if (!name.isEmpty()) out.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(rel);
                    }
                }
            }
        }
        return out;
    }

    /** The lines of a script, or of a notebook's code cells. */
    static List<String> lines(Path f) {
        try {
            final String text = Files.readString(f, StandardCharsets.UTF_8);
            if (!f.toString().toLowerCase(Locale.ROOT).endsWith(".ipynb")) return text.lines().toList();
            final List<String> out = new ArrayList<>();
            for (Object cell : Json.list(Json.parse(text), "cells")) {
                if (!"code".equals(Json.text(cell, "cell_type", ""))) continue;
                final Object src = Json.get(cell, "source");
                if (src instanceof List<?> l) {
                    for (Object s : l) out.addAll(String.valueOf(s).lines().toList());
                } else if (src != null) {
                    out.addAll(String.valueOf(src).lines().toList());
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** Each module set against the environment. */
    public static List<Use> classify(Map<String, Set<String>> modules, PyEnv env, Path root) {
        final List<Use> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : modules.entrySet()) {
            final String m = e.getKey();
            if (m.equals("__future__")) continue;
            if (env.stdlib.contains(m)) {
                out.add(new Use(m, e.getValue(), "stdlib", "", ""));
                continue;
            }
            final List<String> dists = env.imports.get(m);
            if (dists != null && !dists.isEmpty()) {
                final PyEnv.Pkg p = env.packages.get(dists.get(0));
                out.add(new Use(m, e.getValue(), "installed", p == null ? dists.get(0) : p.name + " " + p.version, ""));
                continue;
            }
            if (local(root, m)) {
                out.add(new Use(m, e.getValue(), "local", "", "a module of the project"));
                continue;
            }
            final String not = PyAdvisor.NOT_ON_PIP.get(m);
            if (not != null) {
                out.add(new Use(m, e.getValue(), "not on pip", "", not));
                continue;
            }
            final String dist = PyAdvisor.IMPORT_ALIASES.getOrDefault(m, m);
            out.add(new Use(m, e.getValue(), "missing", dist, dist.equalsIgnoreCase(m) ? "" : "imported as " + m));
        }
        return out;
    }

    /** Whether the project itself has the module: a .py or a package folder somewhere under it (or beside the file). */
    static boolean local(Path root, String module) {
        final Path base = Files.isRegularFile(root) ? root.getParent() : root;
        if (base == null) return false;
        if (Files.isRegularFile(base.resolve(module + ".py")) || Files.isDirectory(base.resolve(module))) return true;
        try (var s = Files.walk(base, 4)) {
            return s.anyMatch(p -> {
                final String n = p.getFileName() == null ? "" : p.getFileName().toString();
                return n.equals(module + ".py") || n.equals(module) && Files.isDirectory(p);
            });
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
