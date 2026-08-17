package com.sphere.core.cpp;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class CppDiagnosticsEngine {
    
    public static final class SourceDiagnostics {
        private final String sourceId;
        private final List<CppDiagnosticsParser.Diagnostic> diagnostics;

        public SourceDiagnostics(String sourceId, List<CppDiagnosticsParser.Diagnostic> diagnostics) {
            this.sourceId = sourceId;
            this.diagnostics = Collections.unmodifiableList(diagnostics);
        }

        public String getSourceId() { return sourceId; }
        public List<CppDiagnosticsParser.Diagnostic> getDiagnostics() { return diagnostics; }
    }

    // High performance tracking metrics bypassing linear loops
    private final AtomicInteger errorCount = new AtomicInteger(0);
    private final AtomicInteger warningCount = new AtomicInteger(0);

    // Synchronized wrapper list structure ensures thread-safe updates without copying arrays
    private final Map<String, List<CppDiagnosticsParser.Diagnostic>> byFile = new ConcurrentHashMap<>();
    private final List<CppDiagnosticsParser.Diagnostic> globalDiagnostics = Collections.synchronizedList(new ArrayList<>());
    private final CppDiagnosticsParser parser = new CppDiagnosticsParser();

    /**
     * Normalizes an incoming string key into an absolute platform-specific path signature.
     */
    private String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return "";
        try {
            return Paths.get(rawPath.trim()).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return rawPath.trim(); // Fallback if string cannot be cast into standard path structure
        }
    }

    private void updateMetrics(CppDiagnosticsParser.Diagnostic diagnostic) {
        String severity = diagnostic.getSeverity();
        if (severity == null) return;
        
        String lowerSeverity = severity.toLowerCase(Locale.ROOT);
        if (lowerSeverity.contains("error")) {
            errorCount.incrementAndGet();
        } else if (lowerSeverity.contains("warning")) {
            warningCount.incrementAndGet();
        }
    }

    public void ingestCompilerStderr(String fileHint, List<String> stderrLines) {
        if (stderrLines == null || stderrLines.isEmpty()) return;
        
        List<CppDiagnosticsParser.Diagnostic> parsed = parser.parse(stderrLines);
        if (parsed.isEmpty()) return;

        globalDiagnostics.addAll(parsed);
        String cleanHint = normalizePath(fileHint);

        if (!cleanHint.isEmpty()) {
            List<CppDiagnosticsParser.Diagnostic> list = byFile.computeIfAbsent(cleanHint, k -> Collections.synchronizedList(new ArrayList<>()));
            list.addAll(parsed);
            parsed.forEach(this::updateMetrics);
        } else {
            for (CppDiagnosticsParser.Diagnostic d : parsed) {
                String file = normalizePath(d.getFile());
                if (file.isEmpty()) continue;
                
                List<CppDiagnosticsParser.Diagnostic> list = byFile.computeIfAbsent(file, k -> Collections.synchronizedList(new ArrayList<>()));
                list.add(d);
                updateMetrics(d);
            }
        }
    }

    public void ingestLspDiagnostics(String file, List<CppDiagnosticsParser.Diagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return;

        globalDiagnostics.addAll(diagnostics);
        String cleanFile = normalizePath(file);

        if (!cleanFile.isEmpty()) {
            List<CppDiagnosticsParser.Diagnostic> list = byFile.computeIfAbsent(cleanFile, k -> Collections.synchronizedList(new ArrayList<>()));
            list.addAll(diagnostics);
            diagnostics.forEach(this::updateMetrics);
        } else {
            for (CppDiagnosticsParser.Diagnostic d : diagnostics) {
                String f = normalizePath(d.getFile());
                if (f.isEmpty()) continue;

                List<CppDiagnosticsParser.Diagnostic> list = byFile.computeIfAbsent(f, k -> Collections.synchronizedList(new ArrayList<>()));
                list.add(d);
                updateMetrics(d);
            }
        }
    }

    public List<CppDiagnosticsParser.Diagnostic> getDiagnosticsForFile(String file) {
        String cleanFile = normalizePath(file);
        if (cleanFile.isEmpty()) return Collections.emptyList();

        List<CppDiagnosticsParser.Diagnostic> list = byFile.get(cleanFile);
        if (list == null) return Collections.emptyList();

        // Thread-safe copy generation
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    public List<CppDiagnosticsParser.Diagnostic> getAllDiagnostics() {
        synchronized (globalDiagnostics) {
            return new ArrayList<>(globalDiagnostics);
        }
    }

    public List<SourceDiagnostics> getAllSources() {
        List<SourceDiagnostics> result = new ArrayList<>();
        byFile.forEach((file, list) -> {
            if (list != null) {
                synchronized (list) {
                    result.add(new SourceDiagnostics(file, new ArrayList<>(list)));
                }
            }
        });
        return result;
    }

    public int getErrorCount() {
        return errorCount.get();
    }

    public int getWarningCount() {
        return warningCount.get();
    }

    public void clearAll() {
        globalDiagnostics.clear();
        byFile.clear();
        errorCount.set(0);
        warningCount.set(0);
    }

    public void clearFile(String file) {
        String cleanFile = normalizePath(file);
        if (cleanFile.isEmpty()) return;

        List<CppDiagnosticsParser.Diagnostic> removed = byFile.remove(cleanFile);
        if (removed != null) {
            synchronized (removed) {
                for (CppDiagnosticsParser.Diagnostic d : removed) {
                    globalDiagnostics.remove(d);
                    
                    String severity = d.getSeverity();
                    if (severity == null) continue;
                    String lower = severity.toLowerCase(Locale.ROOT);
                    if (lower.contains("error")) errorCount.decrementAndGet();
                    else if (lower.contains("warning")) warningCount.decrementAndGet();
                }
            }
        }
    }
}