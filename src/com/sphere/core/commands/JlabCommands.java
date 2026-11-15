package com.sphere.core.commands;

import com.sphere.core.python.jupyterlab.JupylabXeditor;
import com.sphere.core.python.jupyterlab.NotebookCatalog;
import com.sphere.core.python.jupyterlab.kernel.KernelSpecs;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * :jlab -- Jupylab, Sphere's notebook, from the console.
 *
 *   :jlab [notebook.ipynb]   opens it (a window already showing it comes to the
 *                            front); a name not yet on disk is a new notebook
 *                            saved there; without a name, a new one
 *   :jlab new [name.ipynb]   writes a new notebook in the current folder and opens it
 *   :jlab ls [folder]        the notebooks of a folder
 *   :jlab kernels            the kernels Jupylab can start
 */
public final class JlabCommands {

    private JlabCommands() {
    }

    private static Path local(String spec) {
        String s = spec.strip();
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1);
        }
        try {
            return com.sphere.core.fs.WorkingDirectory.get().resolve(s).normalize();
        } catch (RuntimeException e) {
            return Path.of(s).toAbsolutePath().normalize();
        }
    }

    private static Path notebookPath(String spec) {
        Path p = local(spec);
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(".ipynb") ? p : p.resolveSibling(name + ".ipynb");
    }

    /** :jlab [notebook] */
    public static void open(String input, CommandExecutionContext ctx) {
        String arg = Handlers.args(input, ":jlab");
        Path target = null;
        if (!arg.isBlank()) {
            Path given = local(arg);
            if (Files.isDirectory(given)) {
                AppLogger.error(given + " is a folder. :jlab ls " + arg.strip() + " lists its notebooks; :jlab <file.ipynb> opens one.");
                return;
            }
            target = notebookPath(arg);
        }
        JupylabXeditor.open(target); // the window is the answer: nothing to print
    }

    /** :jlab new [name] */
    public static void newNotebook(String input, CommandExecutionContext ctx) {
        String arg = Handlers.args(input, ":jlab new");
        Path target;
        try {
            target = arg.isBlank() ? NotebookCatalog.freeName(com.sphere.core.fs.WorkingDirectory.get(), "untitled")
                                   : notebookPath(arg);
        } catch (RuntimeException e) {
            target = notebookPath(arg.isBlank() ? "untitled" : arg);
        }
        if (Files.exists(target)) {
            AppLogger.error(target.getFileName() + " already exists: :jlab " + target.getFileName() + " opens it.");
            return;
        }
        try {
            NotebookCatalog.writeStarter(target);
        } catch (IOException e) {
            AppLogger.error("Could not write " + target + ": " + e.getMessage());
            return;
        }
        JupylabXeditor.open(target);
    }

    /** :jlab ls [folder] */
    public static void list(String input, CommandExecutionContext ctx) {
        String arg = Handlers.args(input, ":jlab ls");
        Path folder;
        try {
            folder = arg.isBlank() ? com.sphere.core.fs.WorkingDirectory.get() : local(arg);
        } catch (RuntimeException e) {
            folder = Path.of(arg.isBlank() ? "." : arg).toAbsolutePath();
        }
        if (!Files.isDirectory(folder)) {
            AppLogger.error("No folder " + folder + ".");
            return;
        }
        List<NotebookCatalog.Entry> entries;
        try {
            entries = NotebookCatalog.list(folder);
        } catch (IOException e) {
            AppLogger.error("Could not read " + folder + ": " + e.getMessage());
            return;
        }
        if (entries.isEmpty()) {
            AppLogger.info("No notebook in " + folder + ".");
            return;
        }
        AppLogger.raw("Notebooks in " + folder + ":");
        int width = 8;
        for (NotebookCatalog.Entry e : entries) {
            width = Math.max(width, e.path().getFileName().toString().length());
        }
        Instant now = Instant.now();
        for (NotebookCatalog.Entry e : entries) {
            String cells = e.unreadable() ? "unreadable"
                : e.codeCells() + " code, " + e.markdownCells() + " text, " + e.executed() + " run";
            AppLogger.raw(String.format(Locale.ROOT, "  %-" + width + "s   %-30s %-18s %s",
                e.path().getFileName(), cells, e.kernel().isEmpty() ? "-" : e.kernel(), ago(Duration.between(e.modified(), now))));
        }
    }

    /** :jlab kernels */
    public static void kernels(String input, CommandExecutionContext ctx) {
        List<KernelSpecs.Spec> specs = KernelSpecs.discover(new SettingsManager());
        KernelSpecs.Spec chosen = KernelSpecs.choose(specs, null);
        AppLogger.raw("Kernels Jupylab can start (" + specs.size() + "):");
        for (KernelSpecs.Spec s : specs) {
            String mark = s.equals(chosen) ? "  default" : "";
            String what = s.isHomemade() ? "Sphere's own: the Jupyter protocol on Python's standard library"
                        : s.isSphereDriver() ? "stdio driver, no Jupyter protocol"
                        : s.isIpykernel() ? "ipykernel" : s.language();
            AppLogger.raw(String.format(Locale.ROOT, "  %-42s %-22s %s%s", s.displayName(), "[" + s.name() + "]", what, mark));
        }
    }

    private static String ago(Duration d) {
        long s = Math.max(0, d.getSeconds());
        if (s < 90) {
            return "just now";
        }
        if (s < 3600) {
            return (s / 60) + " min ago";
        }
        if (s < 86_400) {
            return (s / 3600) + " h ago";
        }
        return (s / 86_400) + " days ago";
    }
}
