package com.sphere.core.python.jupyterlab;

import com.sphere.core.python.jupyterlab.JupylabXeditor.Cell;
import com.sphere.core.python.jupyterlab.JupylabXeditor.Notebook;
import com.sphere.core.python.jupyterlab.JupylabXeditor.Output;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads and writes .ipynb the way the format defines it, so a notebook survives a
 * trip through Sphere and still opens in JupyterLab.
 *
 * The previous writer emitted every field of its own model on every output, which
 * gave a stream output a null data and an empty traceback, put outputs and an
 * execution count on markdown cells, and dropped the cell ids that nbformat 4.5
 * requires. It also wrote the whole file on one line, so each save was a full diff.
 */
public final class NotebookIO {

    /** Fields the format defines for each output type; anything else is refused. */
    private static final Map<String, Set<String>> OUTPUT_FIELDS = Map.of(
        "stream",         Set.of("name", "text"),
        "display_data",   Set.of("data", "metadata"),
        "execute_result", Set.of("data", "metadata", "execution_count"),
        "error",          Set.of("ename", "evalue", "traceback"));

    private NotebookIO() {
    }

    // ---- Reading -----------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Notebook read(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        Object root = new JupylabXedParser(text).parse();
        if (!(root instanceof Map)) {
            throw new IOException("Not a notebook: the file does not hold a JSON object.");
        }
        Map<String, Object> map = (Map<String, Object>) root;

        Notebook notebook = new Notebook();
        notebook.nbformat = intOf(map.get("nbformat"), 4);
        notebook.nbformat_minor = intOf(map.get("nbformat_minor"), 5);
        notebook.metadata = mapOf(map.get("metadata"));
        // Anything the format gained since is carried through untouched rather
        // than silently dropped on the next save.
        notebook.extra = new LinkedHashMap<>(map);
        notebook.extra.keySet().removeAll(Set.of("nbformat", "nbformat_minor",
                                                 "metadata", "cells"));

        Object cells = map.get("cells");
        if (cells instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map) {
                    notebook.cells.add(readCell((Map<String, Object>) entry));
                }
            }
        }
        return notebook;
    }

    @SuppressWarnings("unchecked")
    private static Cell readCell(Map<String, Object> map) {
        Cell cell = new Cell();
        cell.cell_type = map.get("cell_type") == null ? "code" : map.get("cell_type").toString();
        cell.id = map.get("id") == null ? null : map.get("id").toString();
        cell.metadata = mapOf(map.get("metadata"));
        cell.source = map.get("source");
        Object count = map.get("execution_count");
        cell.execution_count = count instanceof Number n ? n.intValue() : null;
        cell.extra = new LinkedHashMap<>(map);
        cell.extra.keySet().removeAll(Set.of("cell_type", "id", "metadata", "source",
                                             "execution_count", "outputs"));

        Object outputs = map.get("outputs");
        if (outputs instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map) {
                    cell.outputs.add(readOutput((Map<String, Object>) entry));
                }
            }
        }
        return cell;
    }

    private static Output readOutput(Map<String, Object> map) {
        Output output = new Output();
        output.output_type = map.get("output_type") == null ? "stream"
                                                            : map.get("output_type").toString();
        output.name = map.get("name") == null ? null : map.get("name").toString();
        output.text = map.get("text");
        output.data = mapOf(map.get("data"));
        output.metadata = mapOf(map.get("metadata"));
        output.ename = map.get("ename") == null ? null : map.get("ename").toString();
        output.evalue = map.get("evalue") == null ? null : map.get("evalue").toString();
        Object traceback = map.get("traceback");
        if (traceback instanceof List<?> list) {
            for (Object line : list) {
                output.traceback.add(String.valueOf(line));
            }
        }
        Object count = map.get("execution_count");
        output.execution_count = count instanceof Number n ? n.intValue() : null;
        return output;
    }

    // ---- Writing -----------------------------------------------------------

    public static void write(Path path, Notebook notebook) throws IOException {
        Files.writeString(path, toJson(notebook), StandardCharsets.UTF_8);
    }

    static String toJson(Notebook notebook) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> cells = new ArrayList<>();
        boolean needsId = notebook.nbformat > 4
                       || (notebook.nbformat == 4 && notebook.nbformat_minor >= 5);
        for (Cell cell : notebook.cells) {
            cells.add(writeCell(cell, needsId));
        }
        root.put("cells", cells);
        root.putAll(notebook.extra);
        root.put("metadata", notebook.metadata == null ? new LinkedHashMap<>() : notebook.metadata);
        root.put("nbformat", notebook.nbformat);
        root.put("nbformat_minor", notebook.nbformat_minor);

        StringBuilder sb = new StringBuilder();
        encode(root, sb, 0);
        // Jupyter indents by one space and ends the file with a newline; matching it
        // keeps diffs between the two tools down to the lines that really changed.
        return sb.append('\n').toString();
    }

    private static Map<String, Object> writeCell(Cell cell, boolean needsId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cell_type", cell.cell_type);
        if (needsId) {
            if (cell.id == null || cell.id.isBlank()) {
                cell.id = UUID.randomUUID().toString().substring(0, 8);
            }
            map.put("id", cell.id);
        }
        map.put("metadata", cell.metadata == null ? new LinkedHashMap<>() : cell.metadata);
        if (cell.extra != null) {
            map.putAll(cell.extra);
        }
        map.put("source", asLines(cell.source));

        // Only a code cell may carry an execution count and outputs.
        if ("code".equals(cell.cell_type)) {
            map.put("execution_count", cell.execution_count);
            List<Object> outputs = new ArrayList<>();
            for (Output output : cell.outputs) {
                outputs.add(writeOutput(output));
            }
            map.put("outputs", outputs);
        }
        return map;
    }

    private static Map<String, Object> writeOutput(Output output) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("output_type", output.output_type);
        Set<String> allowed = OUTPUT_FIELDS.getOrDefault(output.output_type, Set.of());
        if (allowed.contains("name") && output.name != null) {
            map.put("name", output.name);
        }
        if (allowed.contains("text")) {
            map.put("text", asLines(output.text));
        }
        if (allowed.contains("data")) {
            map.put("data", output.data == null ? new LinkedHashMap<>() : output.data);
            map.put("metadata", output.metadata == null ? new LinkedHashMap<>() : output.metadata);
        }
        if (allowed.contains("execution_count")) {
            map.put("execution_count", output.execution_count);
        }
        if (allowed.contains("ename")) {
            map.put("ename", output.ename == null ? "" : output.ename);
            map.put("evalue", output.evalue == null ? "" : output.evalue);
            map.put("traceback", output.traceback == null ? List.of() : output.traceback);
        }
        return map;
    }

    /** Source and text are stored as a list of lines, each keeping its newline. */
    static List<String> asLines(Object value) {
        List<String> lines = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object line : list) {
                lines.add(String.valueOf(line));
            }
            return lines;
        }
        String text = value == null ? "" : value.toString();
        if (text.isEmpty()) {
            return lines;
        }
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    /** Joins a source or text field back into one string for the editor. */
    public static String joinLines(Object value) {
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object line : list) {
                sb.append(line);
            }
            return sb.toString();
        }
        return value == null ? "" : value.toString();
    }

    // ---- Encoder -----------------------------------------------------------

    private static void encode(Object value, StringBuilder sb, int depth) {
        String pad = " ".repeat(depth + 1);
        String padEnd = " ".repeat(depth);
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append('"').append(escape(s)).append('"');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append(pad).append('"').append(escape(entry.getKey().toString()))
                  .append("\": ");
                encode(entry.getValue(), sb, depth + 1);
            }
            sb.append('\n').append(padEnd).append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append(pad);
                encode(item, sb, depth + 1);
            }
            sb.append('\n').append(padEnd).append(']');
        } else {
            sb.append('"').append(escape(value.toString())).append('"');
        }
    }

    private static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static int intOf(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map ? new LinkedHashMap<>((Map<String, Object>) value)
                                    : new LinkedHashMap<>();
    }
}
