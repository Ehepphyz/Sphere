package com.sphere.core.python.jupyterlab.kernel;

import com.sphere.utils.AppLogger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Sphere's own Jupyter kernel ("sphere-hep"), written from scratch in Python on
 * the standard library alone: the Jupyter protocol 5.4 over its own ZeroMQ
 * implementation, so it needs neither ipykernel, IPython, pyzmq nor tornado,
 * and runs in any Python 3.9+ -- the one settings.conf names, a bare venv, WSL.
 *
 * Beyond what a notebook expects (rich outputs, magics, completion that reads
 * the live objects, input(), display ids), it brings what Sphere's notebooks
 * need: an interrupt that works on Windows (by message), C and C++ output
 * captured into its cell, %%cpp to compile and run C++, the keys of ROOT and
 * uproot trees and awkward arrays completed, ROOT objects drawn as images, and
 * the provenance of every variable -- which cell made it, from what, and
 * whether a value it was computed from has changed since (stale).
 *
 * The package is carried in the jar (kernel/lib) and written out to config/,
 * beside sphere_kernel_tools and sphere_shm, refreshed whenever it differs from
 * the one this Sphere carries. "python -m sphere_kernel install" (from config/)
 * installs it for JupyterLab, Jupyter Notebook and VS Code as well.
 */
public final class SphereJupyKernel {

    /** The launcher, then the package, as they sit under kernel/lib in the sources and the jar. */
    static final List<String> FILES = List.of(
        "sphere_kernel_launcher.py",
        "sphere_kernel/__init__.py",
        "sphere_kernel/__main__.py",
        "sphere_kernel/completion.py",
        "sphere_kernel/display.py",
        "sphere_kernel/install.py",
        "sphere_kernel/kernel.py",
        "sphere_kernel/magics.py",
        "sphere_kernel/mpl_backend.py",
        "sphere_kernel/provenance.py",
        "sphere_kernel/session.py",
        "sphere_kernel/transform.py",
        "sphere_kernel/zmtp.py");

    private static volatile Path materialized;

    private SphereJupyKernel() {
    }

    /** Where the kernel is written: config/, so that sphere_kernel_tools and sphere_shm import beside it. */
    public static Path folder() {
        return Path.of("config").toAbsolutePath().normalize();
    }

    /**
     * Writes the kernel out, refreshing every file that differs from the one this
     * Sphere carries, and answers the launcher's path.
     */
    public static synchronized Path materialize() throws IOException {
        Path dir = folder();
        Path launcher = dir.resolve(FILES.get(0));
        if (materialized != null && Files.isRegularFile(launcher)) {
            return launcher;
        }
        Files.createDirectories(dir.resolve("sphere_kernel"));
        int written = 0;
        for (String name : FILES) {
            byte[] carried = carried(name);
            Path target = dir.resolve(name);
            if (!Files.isRegularFile(target) || !Arrays.equals(Files.readAllBytes(target), carried)) {
                Files.write(target, carried);
                written++;
            }
        }
        /*if (written > 0) {
            AppLogger.info("Sphere HEP kernel written to " + dir.resolve("sphere_kernel") + " (" + written + " files).");
        }*/
        materialized = launcher;
        return launcher;
    }

    /** A file of the kernel as this Sphere carries it: from the jar, or from the sources when run from them. */
    static byte[] carried(String name) throws IOException {
        try (InputStream in = SphereJupyKernel.class.getResourceAsStream("lib/" + name)) {
            if (in != null) {
                return in.readAllBytes();
            }
        }
        Path source = Path.of("src", "com", "sphere", "core", "python", "jupyterlab", "kernel", "lib").resolve(name);
        if (Files.isRegularFile(source)) {
            return Files.readAllBytes(source);
        }
        throw new IOException("The Sphere HEP kernel file " + name + " is missing from this build of Sphere.");
    }
}
