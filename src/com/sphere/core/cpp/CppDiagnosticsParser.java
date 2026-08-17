package com.sphere.core.cpp;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.sphere.utils.AppLogger;

/**
 * Intelligent C++ Compiler Diagnostics Parser Engine.
 * Supports pattern matching for GCC, Clang, and MSVC toolchains,
 * cross-platform absolute path resolution, and stateful multiline context nesting.
 */
public final class CppDiagnosticsParser {

    // Matches GNU/Clang style layouts safely with Windows drive letter support: path/file.cpp:12:34: error: message
    private static final Pattern GCC_CLANG_PATTERN = Pattern.compile(
            "^((?:[a-zA-Z]:)?[[^:]]+):(\\d+):(\\d+):\\s*(error|warning|note|fatal error):\\s*(.*)$", 
            Pattern.CASE_INSENSITIVE
    );

    // Matches Microsoft MSVC style layouts: C:\path\file.cpp(12): error C2065: message
    private static final Pattern MSVC_PATTERN = Pattern.compile(
            "^((?:[a-zA-Z]:)?[[^\\(]]+)\\((\\d+)\\):\\s*(error|warning)\\s+([A-Z0-9]+):\\s*(.*)$", 
            Pattern.CASE_INSENSITIVE
    );

    public static final class Diagnostic {
        private final String file;
        private final int line;
        private final int column;
        private final String severity;
        private final String errorCode;
        private final String message;
        private final List<String> contextualNotes = new ArrayList<>();

        public Diagnostic(String file, int line, int column, String severity, String errorCode, String message) {
            this.file = Objects.requireNonNull(file, "File source payload target context cannot be null.").trim();
            this.line = line;
            this.column = column;
            this.severity = severity != null ? severity.trim().toLowerCase() : "unknown";
            this.errorCode = errorCode != null ? errorCode.trim() : "";
            this.message = message != null ? message.trim() : "";
        }

        public String getFile() { return file; }
        public int getLine() { return line; }
        public int getColumn() { return column; }
        public String getSeverity() { return severity; }
        public String getErrorCode() { return errorCode; }
        public String getMessage() { return message; }
        public List<String> getContextualNotes() { return Collections.unmodifiableList(contextualNotes); }

        public void addContextualNote(String note) {
            if (note != null && !note.isBlank()) {
                contextualNotes.add(note.stripTrailing());
            }
        }
    }

    /**
     * Parses structured raw stream output tracking matrices produced by active compilation jobs.
     * Stateful execution wraps supplementary error info directly under parent headers.
     */
    public List<Diagnostic> parse(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return Collections.emptyList();
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        Diagnostic currentDiagnostic = null;

        for (String line : lines) {
            if (line == null || line.isBlank()) continue;

            Diagnostic newDiagnostic = parseLine(line);
            if (newDiagnostic != null) {
                // Determine if this is a standalone root issue or a secondary note tied to the active context
                if ("note".equals(newDiagnostic.getSeverity()) && currentDiagnostic != null) {
                    currentDiagnostic.addContextualNote("[Note] " + newDiagnostic.getMessage());
                } else {
                    currentDiagnostic = newDiagnostic;
                    diagnostics.add(currentDiagnostic);
                }
            } else if (currentDiagnostic != null) {
                // Stateful processing accumulation: append code blocks, macros or terminal carets (e.g. "  ^  ")
                currentDiagnostic.addContextualNote(line);
            }
        }
        return diagnostics;
    }

    private Diagnostic parseLine(String line) {
        // 1. Try resolving using GCC / LLVM Clang Regular Expressions Engine mapping
        Matcher gccMatcher = GCC_CLANG_PATTERN.matcher(line);
        if (gccMatcher.matches()) {
            try {
                String file = gccMatcher.group(1);
                int lineNum = Integer.parseInt(gccMatcher.group(2));
                int colNum = Integer.parseInt(gccMatcher.group(3));
                String severity = gccMatcher.group(4);
                String message = gccMatcher.group(5);
                
                return new Diagnostic(file, lineNum, colNum, severity, null, message);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        // 2. Try resolving using Microsoft Visual Studio Compiler (MSVC) Structural layout rules
        Matcher msvcMatcher = MSVC_PATTERN.matcher(line);
        if (msvcMatcher.matches()) {
            try {
                String file = msvcMatcher.group(1);
                int lineNum = Integer.parseInt(msvcMatcher.group(2));
                String severity = msvcMatcher.group(3);
                String errorCode = msvcMatcher.group(4);
                String message = msvcMatcher.group(5);
                
                // MSVC doesn't natively supply column numbers on standard line-level outputs
                return new Diagnostic(file, lineNum, 0, severity, errorCode, message);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return null;
    }
}