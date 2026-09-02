package com.sphere.components.editor;

import com.sphere.core.cpp.CppDiagnosticsEngine;
import com.sphere.core.cpp.CppDiagnosticsParser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the findings CppDiagnosticsEngine already collects into something the
 * editor can paint. The engine was only ever read by the console.
 */
public final class DiagnosticsBridge {

    private DiagnosticsBridge() {
    }

    public static List<EditorDiagnostic> forFile(CppDiagnosticsEngine engine, File file) {
        List<EditorDiagnostic> out = new ArrayList<>();
        if (engine == null || file == null) {
            return out;
        }
        List<CppDiagnosticsParser.Diagnostic> found =
            engine.getDiagnosticsForFile(file.getAbsolutePath());
        if (found == null || found.isEmpty()) {
            // Compilers report the path as it was typed, so try the plain name too.
            found = engine.getDiagnosticsForFile(file.getName());
        }
        if (found == null) {
            return out;
        }
        for (CppDiagnosticsParser.Diagnostic d : found) {
            out.add(convert(d));
        }
        return out;
    }

    public static EditorDiagnostic convert(CppDiagnosticsParser.Diagnostic d) {
        return new EditorDiagnostic(
                d.getLine(),
                d.getColumn(),
                EditorDiagnostic.severityOf(d.getSeverity()),
                d.getErrorCode(),
                d.getMessage());
    }

    public static List<EditorDiagnostic> convertAll(List<CppDiagnosticsParser.Diagnostic> list) {
        List<EditorDiagnostic> out = new ArrayList<>();
        if (list != null) {
            for (CppDiagnosticsParser.Diagnostic d : list) {
                out.add(convert(d));
            }
        }
        return out;
    }
}
