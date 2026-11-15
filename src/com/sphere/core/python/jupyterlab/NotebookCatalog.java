package com.sphere.core.python.jupyterlab;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The notebooks of a folder, and new ones, for :jlab. */
public final class NotebookCatalog {

    /** One notebook as :jlab ls lists it. */
    public record Entry(Path path, int codeCells, int markdownCells, int executed, String kernel,
                        Instant modified, boolean unreadable) {
    }

    private NotebookCatalog() {
    }

    /** The .ipynb files of a folder, the latest saved first. */
    public static List<Entry> list(Path folder) throws IOException {
        List<Entry> out = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.ipynb")) {
            for (Path p : files) {
                Instant modified = Files.getLastModifiedTime(p).toInstant();
                try {
                    JupylabXeditor.Notebook nb = NotebookIO.read(p);
                    int code = 0;
                    int text = 0;
                    int run = 0;
                    for (JupylabXeditor.Cell c : nb.cells) {
                        if ("code".equals(c.cell_type)) {
                            code++;
                            if (c.execution_count != null) {
                                run++;
                            }
                        } else if ("markdown".equals(c.cell_type)) {
                            text++;
                        }
                    }
                    Object ks = nb.metadata.get("kernelspec");
                    String kernel = ks instanceof Map<?, ?> m && m.get("name") != null ? String.valueOf(m.get("name")) : "";
                    out.add(new Entry(p, code, text, run, kernel, modified, false));
                } catch (IOException | RuntimeException e) {
                    out.add(new Entry(p, 0, 0, 0, "", modified, true));
                }
            }
        }
        out.sort(Comparator.comparing(Entry::modified).reversed());
        return out;
    }

    /** untitled.ipynb, or untitled-2.ipynb, untitled-3.ipynb... the first free in the folder. */
    public static Path freeName(Path folder, String stem) {
        Path p = folder.resolve(stem + ".ipynb");
        for (int i = 2; Files.exists(p); i++) {
            p = folder.resolve(stem + "-" + i + ".ipynb");
        }
        return p;
    }

    /** A new notebook: a title, and one empty code cell to start typing in. */
    public static void writeStarter(Path target) throws IOException {
        JupylabXeditor.Notebook nb = new JupylabXeditor.Notebook();
        String name = target.getFileName().toString();
        String title = name.toLowerCase(Locale.ROOT).endsWith(".ipynb") ? name.substring(0, name.length() - 6) : name;
        JupylabXeditor.Cell head = new JupylabXeditor.Cell();
        head.cell_type = "markdown";
        head.source = Arrays.asList("# " + title);
        JupylabXeditor.Cell code = new JupylabXeditor.Cell();
        code.cell_type = "code";
        code.source = Arrays.asList("");
        nb.cells.add(head);
        nb.cells.add(code);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        NotebookIO.write(target, nb);
    }
}
