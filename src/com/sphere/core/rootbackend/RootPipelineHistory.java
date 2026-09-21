package com.sphere.core.rootbackend;

import com.sphere.utils.AppLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The versions a pipeline has been through, and the way to send one away.
 *
 * Physics code is written by changing it until it is right, and the change that
 * breaks everything is only recognized afterwards. Each write keeps the version
 * it replaced, so going back is a decision rather than an archaeology. The same
 * folder is what makes a pipeline sendable: a pipeline is its source, and its
 * source is enough for someone else to rebuild it, so exporting is putting that
 * source and what belongs with it into one archive.
 */
public final class RootPipelineHistory {

    /** Inside includes/, and out of the way of everything that lists it. */
    public static final String HISTORY_DIR = ".history";

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /** How many versions of one pipeline are worth keeping. */
    private static final int KEEP = 20;

    /** One kept version. */
    public record Version(Path file, String stamp, long bytes) {
        /** The moment as it reads in a list. */
        public String when() {
            return stamp.replace('_', ' ').replace('-', ':')
                        .replaceFirst("^(\\d{4}):(\\d{2}):(\\d{2})", "$1-$2-$3");
        }

        @Override
        public String toString() {
            return when() + "   " + bytes + " bytes";
        }
    }

    private RootPipelineHistory() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** Where a pipeline's versions live. */
    public static Path folderFor(Path source) {
        return source.getParent().resolve(HISTORY_DIR).resolve(stem(source));
    }

    /**
     * Copies the current source aside before it is overwritten.
     *
     * Silent by design: keeping a version is a side effect of saving, and a
     * message about it every time would only teach the user to stop reading.
     */
    public static void keep(Path source) {
        if (source == null || !Files.isRegularFile(source)) {
            return;
        }
        try {
            Path folder = folderFor(source);
            Files.createDirectories(folder);
            Path kept = folder.resolve(LocalDateTime.now().format(STAMP) + ".cpp");
            if (Files.exists(kept)) {
                // Two saves inside one second are one save as far as this goes.
                return;
            }
            Files.copy(source, kept, StandardCopyOption.COPY_ATTRIBUTES);
            prune(folder);
        } catch (IOException cannotKeep) {
            AppLogger.warn("Could not keep a version of " + source.getFileName()
                + ": " + cannotKeep.getMessage());
        }
    }

    /** The versions of a pipeline, newest first. */
    public static List<Version> versionsOf(Path source) {
        List<Version> found = new ArrayList<>();
        if (source == null) {
            return found;
        }
        Path folder = folderFor(source);
        if (!Files.isDirectory(folder)) {
            return found;
        }
        try (var entries = Files.list(folder)) {
            entries.filter(Files::isRegularFile)
                   .filter(one -> one.getFileName().toString().endsWith(".cpp"))
                   .sorted(Comparator.reverseOrder())
                   .forEach(one -> found.add(new Version(one,
                       one.getFileName().toString().replace(".cpp", ""), sizeOf(one))));
        } catch (IOException unreadable) {
            return found;
        }
        return found;
    }

    /**
     * Puts a kept version back, keeping what it replaces.
     *
     * The current source is kept first, so reverting is itself undoable: a
     * revert to the wrong version must not be the end of the work.
     */
    public static boolean revertTo(Path source, Version version) {
        if (source == null || version == null || !Files.isRegularFile(version.file())) {
            return false;
        }
        keep(source);
        try {
            Files.copy(version.file(), source, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException cannotWrite) {
            AppLogger.error("Could not go back to " + version.when()
                + ": " + cannotWrite.getMessage());
            return false;
        }
    }

    /** How far back this pipeline can go. */
    public static int depth(Path source) {
        return versionsOf(source).size();
    }

    /* ------------------------------------------------------------------ */
    /* Sending one away, and taking one in                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Puts a pipeline into one archive: its source, and what belongs with it.
     *
     * The built library is left out on purpose. It was compiled for one ROOT,
     * one compiler and possibly one processor, so shipping it would hand the
     * next person a file that loads on nobody's machine but the sender's. The
     * source rebuilds everywhere, and the manifest inside it is what makes the
     * rebuild need no explanation.
     */
    public static Path export(Path source, Path into) {
        if (source == null || !Files.isRegularFile(source)) {
            return null;
        }
        final String name = stem(source);
        Path archive = into.resolve(name + "-pipeline.zip");
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(archive), StandardCharsets.UTF_8)) {

            put(zip, name + ".cpp", source);

            // A test macro and a chain of the same name travel with it.
            Path layer = source.getParent() == null ? null : source.getParent().getParent();
            if (layer != null) {
                Path scripts = layer.resolve(RootUserPipeline.SCRIPTS_DIR);
                for (String companion : new String[]{name + "_test.C", name + ".C",
                                                     name + ".chain"}) {
                    Path one = scripts.resolve(companion);
                    if (Files.isRegularFile(one)) {
                        put(zip, companion, one);
                    }
                }
            }
            return archive;
        } catch (IOException cannotWrite) {
            AppLogger.error("Could not export " + name + ": " + cannotWrite.getMessage());
            return null;
        }
    }

    /** What an import did, so the window can say it in one line. */
    public record Taken(List<String> written, List<String> refused) { }

    /**
     * Reads an archive into a layer's folders.
     *
     * A file that is already there is not overwritten. Someone else's pipeline
     * arriving on top of a day of work would be a poor way to learn that two
     * people chose the same name.
     */
    public static Taken importInto(Path archive, Path layerRoot) {
        List<String> written = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        if (archive == null || !Files.isRegularFile(archive) || layerRoot == null) {
            refused.add("There is nothing to read.");
            return new Taken(written, refused);
        }
        RootUserPipeline.ensureLayout(layerRoot);

        try (ZipInputStream zip = new ZipInputStream(
                Files.newInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                final String file = Path.of(entry.getName()).getFileName().toString();
                // Only the two kinds of file a pipeline is made of are taken,
                // and only by their bare name, so an archive cannot write
                // anywhere but into the two folders it is meant for.
                final Path target = file.endsWith(".cpp")
                    ? layerRoot.resolve(RootUserPipeline.INCLUDES_DIR).resolve(file)
                    : (file.endsWith(".C") || file.endsWith(".chain")
                       ? layerRoot.resolve(RootUserPipeline.SCRIPTS_DIR).resolve(file)
                       : null);
                if (target == null) {
                    refused.add(file + " is not part of a pipeline");
                    continue;
                }
                if (Files.exists(target)) {
                    refused.add(file + " is already there");
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    copy(zip, out);
                }
                written.add(file);
            }
        } catch (IOException cannotRead) {
            refused.add("The archive could not be read: " + cannotRead.getMessage());
        }
        return new Taken(written, refused);
    }

    /* ------------------------------------------------------------------ */

    /** Drops the oldest versions once there are more than are useful. */
    private static void prune(Path folder) {
        List<Path> kept = new ArrayList<>();
        try (var entries = Files.list(folder)) {
            entries.filter(Files::isRegularFile).sorted().forEach(kept::add);
        } catch (IOException leaveThem) {
            return;
        }
        for (int i = 0; i < kept.size() - KEEP; i++) {
            try {
                Files.deleteIfExists(kept.get(i));
            } catch (IOException leaveIt) {
                // A version that will not go is not worth stopping a save over.
            }
        }
    }

    private static void put(ZipOutputStream zip, String named, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(named));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private static void copy(InputStream from, OutputStream to) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = from.read(buffer)) > 0) {
            to.write(buffer, 0, read);
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException unknown) {
            return 0L;
        }
    }

    private static String stem(Path file) {
        final String name = file.getFileName().toString();
        final int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
