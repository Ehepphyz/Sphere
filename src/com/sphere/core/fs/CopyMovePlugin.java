package com.sphere.core.fs;

import com.sphere.core.CommandRouter;
import com.sphere.utils.AppLogger;

import javax.swing.SwingWorker;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/**
 * :cp and :mv. Neither exists under cmd.exe with these names, and copy there
 * cannot recurse without xcopy. A destination directory receives the source
 * under its own name, as the Unix commands do.
 */
public class CopyMovePlugin implements CommandRouter.CommandPlugin {

    private final CommandRouter router;

    public CopyMovePlugin(CommandRouter router) {
        this.router = router;
    }

    @Override
    public String getName() {
        return "cp";
    }

    @Override
    public boolean supports(String input) {
        if (input == null) return false;
        String t = input.trim();
        if (t.startsWith("::")) return false;
        return t.equals(":cp") || t.startsWith(":cp ")
            || t.equals(":mv") || t.startsWith(":mv ");
    }

    @Override
    public void execute(String input) {
        List<String> tokens = FsSupport.tokenize(input.trim());
        boolean moving = tokens.get(0).equals(":mv");
        String name = moving ? ":mv" : ":cp";

        boolean recursive = false;
        boolean force = false;
        List<String> operands = new java.util.ArrayList<>();

        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("--help")) { usage(name, moving); return; }
            if (t.startsWith("-") && t.length() > 1 && !Files.exists(Paths.get(t))) {
                for (int k = 1; k < t.length(); k++) {
                    switch (t.charAt(k)) {
                        case 'r': case 'R': recursive = true; break;
                        case 'f': force = true; break;
                        default:
                            AppLogger.error("Unknown option: -" + t.charAt(k));
                            return;
                    }
                }
            } else {
                operands.add(t);
            }
        }

        if (operands.size() != 2) {
            usage(name, moving);
            return;
        }

        Path source = FsSupport.resolve(router, operands.get(0));
        Path destination = FsSupport.resolve(router, operands.get(1));

        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            AppLogger.error("Source not found: " + source);
            return;
        }
        if (Files.isDirectory(source) && !recursive && !moving) {
            AppLogger.error(source.getFileName() + " is a directory. Use  :cp -r  to copy it.");
            return;
        }

        // A directory destination receives the source under its own name
        Path target = Files.isDirectory(destination)
                ? destination.resolve(source.getFileName())
                : destination;

        if (target.equals(source)) {
            AppLogger.error("Source and destination are the same file.");
            return;
        }
        if (Files.isDirectory(source) && target.startsWith(source)) {
            AppLogger.error("Cannot copy a directory into itself.");
            return;
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !force) {
            AppLogger.error(target + " already exists. Use  -f  to overwrite.");
            return;
        }

        final Path finalSource = source;
        final Path finalTarget = target;
        final boolean finalMoving = moving;

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    if (finalMoving) {
                        return moveTree(finalSource, finalTarget);
                    }
                    return copyTree(finalSource, finalTarget);
                } catch (IOException e) {
                    return "!" + e.getMessage();
                }
            }

            @Override
            protected void done() {
                String result;
                try { result = get(); } catch (Exception e) { result = "!" + e.getMessage(); }
                if (result.startsWith("!")) {
                    AppLogger.error((finalMoving ? "Move" : "Copy") + " failed: " + result.substring(1));
                } else {
                    AppLogger.success(result);
                }
            }
        }.execute();
    }

    private String moveTree(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | DirectoryNotEmptyException crossDevice) {
            // Different volumes, or a non empty directory: copy then delete
            copyTree(source, target);
            deleteTree(source);
        }
        return "Moved to " + target;
    }

    private String copyTree(Path source, Path target) throws IOException {
        long[] tally = new long[2];
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    Files.createDirectories(target.resolve(source.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.copy(file, target.resolve(source.relativize(file)),
                               StandardCopyOption.REPLACE_EXISTING,
                               StandardCopyOption.COPY_ATTRIBUTES);
                    tally[0]++;
                    tally[1] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    AppLogger.error("Skipped " + file + ": " + e.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
            return "Copied " + tally[0] + " files, " + FsSupport.humanBytes(tally[1])
                 + ", to " + target;
        }

        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING,
                   StandardCopyOption.COPY_ATTRIBUTES);
        return "Copied to " + target + " (" + FsSupport.humanBytes(Files.size(target)) + ")";
    }

    private void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void usage(String name, boolean moving) {
        AppLogger.raw("Usage: " + name + " [options] <source> <destination>");
        AppLogger.raw("Options:");
        if (!moving) AppLogger.raw("  -r            copy a directory and everything under it");
        AppLogger.raw("  -f            overwrite an existing destination");
        AppLogger.raw("  --help        show this help");
        AppLogger.raw("A destination that is a directory receives the source under its own name.");
    }
}
