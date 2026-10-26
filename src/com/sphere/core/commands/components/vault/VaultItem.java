package com.sphere.components.vault;

import java.nio.file.Path;

/**
 * A file of snippets/ or user_scripts/, as the index knows it: where it is,
 * which of the two folders and which layer it comes from, and what reading it
 * said about it.
 *
 * An item is replaced, never changed: the index makes a new one when the file
 * changes on disk, so a snapshot handed to the panel stays true while it is read.
 */
public final class VaultItem {

    public enum Kind {
        SNIPPET("snippet", "snippets"),
        MACRO("macro", "user_scripts");

        public final String word;
        public final String folder;

        Kind(String word, String folder) {
            this.word = word;
            this.folder = folder;
        }
    }

    public enum Layer { GLOBAL, PROJECT }

    public final Path path;
    /** The snippets/ or user_scripts/ folder the file lives under. */
    public final Path root;
    public final Kind kind;
    public final Layer layer;
    /** The path below the root, with forward slashes: "MG5Workflow/gen.py". */
    public final String relative;
    public final long size;
    public final long modified;
    public final VaultAnalysis info;

    VaultItem(Path path, Path root, Kind kind, Layer layer, long size, long modified,
              VaultAnalysis info) {
        this.path = path;
        this.root = root;
        this.kind = kind;
        this.layer = layer;
        this.relative = root.relativize(path).toString().replace('\\', '/');
        this.size = size;
        this.modified = modified;
        this.info = info;
    }

    /** What the state file and the twins know this item by, across restarts. */
    public String key() {
        return kind.word + ":" + layer.name().toLowerCase(java.util.Locale.ROOT) + ":" + relative;
    }

    public String name() {
        return path.getFileName().toString();
    }

    /** The name without its extension, which a ROOT macro's function must carry. */
    public String stem() {
        final String n = name();
        final int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    public String extension() {
        final String n = name();
        final int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(dot) : "";
    }

    /** The folders between the root and the file, "" at the top. */
    public String folder() {
        final int slash = relative.lastIndexOf('/');
        return slash < 0 ? "" : relative.substring(0, slash + 1);
    }

    /** A macro ROOT runs with .x: C++ in user_scripts/, or any .C file. */
    public boolean isRootMacro() {
        return info.language == com.sphere.components.editor.LanguageSpec.CPP
            && (kind == Kind.MACRO || name().endsWith(".C"))
            && !info.header;
    }

    @Override
    public String toString() {
        return key();
    }
}
