package com.sphere.components.editor;

/**
 * One compiler or language-server finding, positioned in the buffer. Kept free of
 * any backend type so the editor can display C++, Python or Julia findings
 * through the same path.
 */
public final class EditorDiagnostic {

    public enum Severity { ERROR, WARNING, INFO }

    private final int line;      // 1-based, as compilers report it
    private final int column;    // 1-based, 0 when unknown
    private final Severity severity;
    private final String code;
    private final String message;

    public EditorDiagnostic(int line, int column, Severity severity, String code, String message) {
        this.line = Math.max(1, line);
        this.column = Math.max(0, column);
        this.severity = severity == null ? Severity.INFO : severity;
        this.code = code == null ? "" : code;
        this.message = message == null ? "" : message;
    }

    public int getLine() { return line; }
    public int getColumn() { return column; }
    public Severity getSeverity() { return severity; }
    public String getCode() { return code; }
    public String getMessage() { return message; }

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
