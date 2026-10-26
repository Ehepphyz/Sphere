package com.sphere.components.vault;

import com.sphere.components.editor.LanguageSpec;

import java.util.List;

/**
 * What reading a file said about it, without running it.
 *
 * Everything here comes from the file itself: its extension, its first comment
 * block, the way it parses its arguments, the function a ROOT macro must define.
 * Nothing is kept beside it, so a snippet copied elsewhere carries its own
 * description, tags and arguments with it.
 */
public final class VaultAnalysis {

    public enum Severity { INFO, WARNING, ERROR }

    /** How the arguments are written back on the command line. */
    public enum Style {
        /** None that the source shows: only the free field. */
        NONE,
        /** "--name value" and flags, as argparse reads them. */
        OPTIONS,
        /** Values in order, as sys.argv, ARGS or argv read them. */
        POSITIONAL,
        /** The arguments of the macro's function, macro.C(3, "abc"). */
        CALL
    }

    /** Something to do about an issue, in one click. */
    public record Fix(Action action, String label, String value) {
        public enum Action { RENAME, ADD_INCLUDES }
    }

    public record Issue(Severity severity, String message, Fix fix) {
    }

    /** The language the extension names. */
    public final LanguageSpec declared;
    /** The language the panel works with: the declared one, or the content's when there is none. */
    public final LanguageSpec language;
    /** The language the content reads as, or null when it says nothing clear. */
    public final LanguageSpec sniffed;
    public final boolean header;

    public final String title;
    public final String description;
    public final List<String> tags;
    /** Modules imported, headers and ROOT classes used. */
    public final List<String> uses;
    public final List<String> functions;
    /** The function .x calls, when the file is a ROOT macro that defines it. */
    public final String entry;
    /** A ROOT macro made of one unnamed block, which .x runs as it is. */
    public final boolean unnamed;

    public final List<VaultParam> params;
    public final Style style;
    /** The options for the interpreter its header asks for (@opts). */
    public final String defaultOptions;
    /** The console prefix its header asks for (@runner), or null. */
    public final String runner;

    public final List<Issue> issues;
    /** Draws or saves a figure, so its result lands in the Plots tab. */
    public final boolean draws;
    public final int lines;
    /** Of the content with line ends and trailing blanks evened out, 0 when empty. */
    public final long hash;
    /** The content in lower case, capped, for the full-text search. */
    final String text;

    VaultAnalysis(Builder b) {
        this.declared = b.declared;
        this.language = b.language;
        this.sniffed = b.sniffed;
        this.header = b.header;
        this.title = b.title == null ? "" : b.title;
        this.description = b.description == null ? "" : b.description;
        this.tags = List.copyOf(b.tags);
        this.uses = List.copyOf(b.uses);
        this.functions = List.copyOf(b.functions);
        this.entry = b.entry;
        this.unnamed = b.unnamed;
        this.params = List.copyOf(b.params);
        this.style = b.style;
        this.defaultOptions = b.defaultOptions == null ? "" : b.defaultOptions;
        this.runner = b.runner;
        this.issues = List.copyOf(b.issues);
        this.draws = b.draws;
        this.lines = b.lines;
        this.hash = b.hash;
        this.text = b.text == null ? "" : b.text;
    }

    public Severity worst() {
        Severity worst = null;
        for (Issue issue : issues) {
            if (worst == null || issue.severity().ordinal() > worst.ordinal()) {
                worst = issue.severity();
            }
        }
        return worst;
    }

    public boolean hasProblems() {
        final Severity worst = worst();
        return worst == Severity.WARNING || worst == Severity.ERROR;
    }

    static final class Builder {
        LanguageSpec declared = LanguageSpec.NONE;
        LanguageSpec language = LanguageSpec.NONE;
        LanguageSpec sniffed;
        boolean header;
        String title;
        String description;
        final java.util.LinkedHashSet<String> tags = new java.util.LinkedHashSet<>();
        final java.util.LinkedHashSet<String> uses = new java.util.LinkedHashSet<>();
        final java.util.LinkedHashSet<String> functions = new java.util.LinkedHashSet<>();
        String entry;
        boolean unnamed;
        final java.util.ArrayList<VaultParam> params = new java.util.ArrayList<>();
        /** Doxygen's @param lines, by parameter name, laid on the parameters once they are read. */
        final java.util.Map<String, String> paramHelp = new java.util.HashMap<>();
        Style style = Style.NONE;
        String defaultOptions;
        String runner;
        final java.util.ArrayList<Issue> issues = new java.util.ArrayList<>();
        boolean draws;
        int lines;
        long hash;
        String text;

        VaultAnalysis build() {
            return new VaultAnalysis(this);
        }
    }
}
