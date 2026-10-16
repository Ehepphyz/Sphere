package com.sphere.components.editor;

/**
 * A named thing in a file: a class, a function, a CMake target, a heading.
 * Lines are 0-based; endLine is the last line the symbol covers.
 */
public final class DocSymbol {

    public enum Kind {
        NAMESPACE("N", "namespace"),
        MODULE("M", "module"),
        CLASS("C", "class"),
        STRUCT("S", "struct"),
        ENUM("E", "enum"),
        INTERFACE("I", "interface"),
        FUNCTION("ƒ", "function"),
        METHOD("m", "method"),
        CONSTRUCTOR("c", "constructor"),
        FIELD("f", "field"),
        VARIABLE("v", "variable"),
        CONSTANT("K", "constant"),
        MACRO("#", "macro"),
        TARGET("T", "target"),
        PACKAGE("P", "package"),
        HEADING("H", "heading"),
        SECTION("§", "section"),
        PROPERTY("p", "property");

        public final String glyph;
        public final String label;

        Kind(String glyph, String label) {
            this.glyph = glyph;
            this.label = label;
        }

        public boolean isType() {
            return this == CLASS || this == STRUCT || this == ENUM || this == INTERFACE;
        }

        public boolean isCallable() {
            return this == FUNCTION || this == METHOD || this == CONSTRUCTOR || this == MACRO;
        }
    }

    public final String name;
    public final Kind kind;
    /** Signature or what the symbol is (executable, option...). */
    public final String detail;
    public final int line;
    public final int endLine;
    /** Offset of the name in the buffer. */
    public final int offset;
    public final int depth;
    /** Name of the symbol that holds this one, or null at file level. */
    public final String container;
    /** The comment written above it, or its docstring, or null. */
    public final String doc;

    public DocSymbol(String name, Kind kind, String detail, int line, int endLine, int offset,
                     int depth, String container, String doc) {
        this.name = name;
        this.kind = kind;
        this.detail = detail == null ? "" : detail;
        this.line = line;
        this.endLine = Math.max(line, endLine);
        this.offset = offset;
        this.depth = depth;
        this.container = container;
        this.doc = doc;
    }

    public boolean contains(int lineIndex) {
        return lineIndex >= line && lineIndex <= endLine;
    }

    /** What the outline and the palette show after the name. */
    public String describe() {
        if (kind.isCallable() && detail.startsWith("(")) {
            return name + detail;
        }
        return name;
    }

    @Override
    public String toString() {
        return kind.label + " " + name + " [" + (line + 1) + "-" + (endLine + 1) + "]";
    }
}
