package com.sphere.components.editor;

/**
 * One compiler or language-server finding, positioned in the buffer. Kept free of
 * any backend type so the editor can display C++, Python or Julia findings
 * through the same path.
 */
public final class EditorDiagnostic {

    public enum Severity { ERROR, WARNING, INFO }

    /** Findings of the editor's own analysis, as opposed to a compiler's. */
    public static final String SOURCE_INSIGHT = "Sphere Insight";
    public static final String SOURCE_COMPILER = "Compiler";

    private final int line;      // 1-based, as compilers report it
    private final int column;    // 1-based, 0 when unknown
    private final int length;    // characters underlined, 0 when unknown
    private final Severity severity;
    private final String code;
    private final String message;
    private final String source;

    public EditorDiagnostic(int line, int column, Severity severity, String code, String message) {
        this(line, column, 0, severity, code, message, SOURCE_COMPILER);
    }

    public EditorDiagnostic(int line, int column, int length, Severity severity, String code,
                            String message, String source) {
        this.line = Math.max(1, line);
        this.column = Math.max(0, column);
        this.length = Math.max(0, length);
        this.severity = severity == null ? Severity.INFO : severity;
        this.code = code == null ? "" : code;
        this.message = message == null ? "" : message;
        this.source = source == null ? SOURCE_COMPILER : source;
    }

    public int getLine() { return line; }
    public int getColumn() { return column; }
    public int getLength() { return length; }
    public Severity getSeverity() { return severity; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public String getSource() { return source; }

    public boolean isError() { return severity == Severity.ERROR; }

    /** Maps the severity strings CppDiagnosticsParser produces. */
    public static Severity severityOf(String raw) {
        if (raw == null) {
            return Severity.INFO;
        }
        String s = raw.trim().toLowerCase();
        if (s.startsWith("err") || s.startsWith("fat")) {
            return Severity.ERROR;
        }
        if (s.startsWith("warn")) {
            return Severity.WARNING;
        }
        return Severity.INFO;
    }

    @Override
    public String toString() {
        return line + ":" + column + " " + severity + " " + message;
    }
}
