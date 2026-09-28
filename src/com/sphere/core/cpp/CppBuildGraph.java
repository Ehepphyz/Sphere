package com.sphere.core.cpp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.sphere.utils.AppLogger;

/**
 * Intelligent C++ Compilation Dependency Graph Engine.
 * Supports automated regex-based source inclusion parsing, robust circular 
 * reference interceptors, and deep dirtiness validation for optimized incremental compilation.
 */
public final class CppBuildGraph {
    
    // Matches local header inclusions: #include "filename.h" or #include "path/to/file.hpp"
    private static final Pattern INCLUDE_PATTERN = Pattern.compile("^\\s*#\\s*include\\s*\"([^\"]+)\"");

    public static final class Node {
        private final String name;
        private final File file;
        private final Set<Node> dependencies = new LinkedHashSet<>();
        private long lastKnownModified = -1;

        public Node(String name, File file) {
            this.name = Objects.requireNonNull(name, "Node name cannot be null.");
            this.file = Objects.requireNonNull(file, "Source target file payload cannot be null.");
            this.lastKnownModified = file.exists() ? file.lastModified() : -1;
        }

        public String getName() { return name; }
        public File getFile() { return file; }

        public Set<Node> getDependencies() {
            return Collections.unmodifiableSet(dependencies);
        }

        public void addDependency(Node node) {
            if (node != this) {
                dependencies.add(node);
            }
        }

        /**
         * Recursively computes whether this node or any of its structural dependencies 
         * has changed relative to a provided binary timestamp.
         */
        public boolean isDirty(long binaryTimestamp, Set<Node> evaluated) {
            if (evaluated.contains(this)) return false;
            evaluated.add(this);

            if (!file.exists()) return true;
            if (file.lastModified() > binaryTimestamp || file.lastModified() != lastKnownModified) {
                return true;
            }

            for (Node dependency : dependencies) {
                if (dependency.isDirty(binaryTimestamp, evaluated)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The file in this subtree that is actually newer, or null.
         *
         * isDirty answers yes or no, which made a report name the header that
         * was included rather than the one that was edited: touching units.h
         * was reported against geometry.h, because geometry.h is what the
         * source includes. This walks down to the file that moved.
         */
        public Node firstChanged(long binaryTimestamp, Set<Node> evaluated) {
            if (!evaluated.add(this)) return null;
            if (!file.exists() || file.lastModified() > binaryTimestamp) {
                return this;
            }
            for (Node dependency : dependencies) {
                Node changed = dependency.firstChanged(binaryTimestamp, evaluated);
                if (changed != null) {
                    return changed;
                }
            }
            return null;
        }

        public void synchronizedTimestamp() {
            if (file.exists()) {
                this.lastKnownModified = file.lastModified();
            }
        }
    }

    private final Map<String, Node> nodes = new LinkedHashMap<>();

    public synchronized Node getOrCreateNode(String name, File file) {
        return nodes.computeIfAbsent(name, k -> new Node(k, file));
    }

    public synchronized Collection<Node> getNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    /**
     * Dynamically parses local C++ include hierarchies to automatically discover and map node links.
     * * @param rootNode The starting source node file to inspect.
     * @param searchDirectories Multi-path lookup arrays to resolve matching files inside the workspace tree.
     */
    public synchronized void discoverDependencies(Node rootNode, List<File> searchDirectories) {
        Set<File> processedFiles = new HashSet<>();
        parseSourceFileInternal(rootNode, searchDirectories, processedFiles);
    }

    private void parseSourceFileInternal(Node currentNode, List<File> searchDirectories, Set<File> processedFiles) {
        File targetFile = currentNode.getFile();
        if (!targetFile.exists() || !processedFiles.add(targetFile)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(targetFile.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = INCLUDE_PATTERN.matcher(line);
                if (matcher.find()) {
                    String includePath = matcher.group(1);
                    File resolvedFile = resolveHeaderFile(includePath, targetFile.getParentFile(), searchDirectories);
                    
                    if (resolvedFile != null) {
                        String uniqueKey = resolvedFile.getCanonicalPath();
                        Node dependencyNode = getOrCreateNode(uniqueKey, resolvedFile);
                        currentNode.addDependency(dependencyNode);
                        
                        // Deep parse nested header graphs sequentially
                        parseSourceFileInternal(dependencyNode, searchDirectories, processedFiles);
                    }
                }
            }
        } catch (IOException e) {
            AppLogger.error("Failed to dynamically orchestrate dependency mapping discovery: " + e.getMessage());
        }
    }

    private File resolveHeaderFile(String includePath, File currentDir, List<File> searchDirectories) {
        // 1. Resolve relative to the parent directory of the working source file frame
        File localFile = new File(currentDir, includePath);
        if (localFile.exists()) return localFile;

        // 2. Fall back to designated include toolchain target parameter maps
        for (File dir : searchDirectories) {
            File includeTarget = new File(dir, includePath);
            if (includeTarget.exists()) return includeTarget;
        }
        return null;
    }

    /**
     * Dependencies before the files that include them.
     *
     * A cycle here is not an error to report but a shape to walk around: two
     * headers that include each other behind their include guards are ordinary
     * C++, and refusing to order them refused the whole project. The cycle is
     * simply cut at the edge that closes it, and cyclesFound() says how often.
     */
    public synchronized List<Node> topologicalOrder() {
        List<Node> result = new ArrayList<>();
        Set<Node> visited = new HashSet<>();
        Set<Node> callStack = new LinkedHashSet<>();
        cycles = 0;

        for (Node n : nodes.values()) {
            if (!visited.contains(n)) {
                dfs(n, visited, callStack, result);
            }
        }
        return result;
    }

    /** How many edges the last ordering had to cut. */
    public synchronized int cyclesFound() {
        return cycles;
    }

    private int cycles = 0;

    private void dfs(Node n, Set<Node> visited, Set<Node> callStack, List<Node> result) {
        if (callStack.contains(n)) {
            cycles++;
            return;
        }
        callStack.add(n);
        for (Node dep : n.getDependencies()) {
            if (!visited.contains(dep)) {
                dfs(dep, visited, callStack, result);
            }
        }
        callStack.remove(n);
        visited.add(n);
        result.add(n);
    }
}