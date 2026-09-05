package com.sphere.core.fs;

import com.sphere.core.CommandRouter;
import com.sphere.utils.AppLogger;

import javax.swing.SwingWorker;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * :rm, with the guards a console needs. A directory requires -r, a directory
 * holding more than one file requires --confirm as well, and a system tree or
 * the user home is refused outright.
 */
public class RemovePlugin implements CommandRouter.CommandPlugin {

    private static final int CONFIRM_THRESHOLD = 1;

    private final CommandRouter router;

    public RemovePlugin(CommandRouter router) {
        this.router = router;
    }

    @Override
    public String getName() {
        return "rm";
    }

    @Override
    public boolean supports(String input) {
        if (input == null) return false;
        String t = input.trim();
        if (t.startsWith("::")) return false;
        return t.equals(":rm") || t.startsWith(":rm ");
    }

    @Override
    public void execute(String input) {
        List<String> tokens = FsSupport.tokenize(input.trim());

        boolean recursive = false;
        boolean confirmed = false;
        List<String> operands = new ArrayList<>();

        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("--help")) { usage(); return; }
            if (t.equals("--confirm")) { confirmed = true; continue; }
            if (t.startsWith("-") && t.length() > 1 && !t.startsWith("--")) {
                for (int k = 1; k < t.length(); k++) {
                    switch (t.charAt(k)) {
                        case 'r': case 'R': recursive = true; break;
                        default:
                            AppLogger.error("Unknown option: -" + t.charAt(k));
                            return;
                    }
                }
            } else {
                operands.add(t);
            }
        }

        if (operands.isEmpty()) { usage(); return; }

        for (String operand : operands) {
            removeOne(FsSupport.resolve(router, operand), recursive, confirmed);
        }
    }

    private void removeOne(Path target, boolean recursive, boolean confirmed) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            AppLogger.error("Not found: " + target);
            return;
        }

        String reason = FsSupport.protectedReason(target);
        if (reason != null) {
            AppLogger.error("Refusing to delete " + target + ": this is " + reason + ".");
            return;
        }

        // A symbolic link is removed as a link, never followed into its target
        if (Files.isSymbolicLink(target)) {
            try {
                Files.delete(target);
                AppLogger.success("Removed link " + target);
            } catch (IOException e) {
                AppLogger.error("Could not remove " + target + ": " + e.getMessage());
            }
            return;
        }

        if (!Files.isDirectory(target)) {
            try {
                long size = Files.size(target);
                Files.delete(target);
                AppLogger.success("Removed " + target + " (" + FsSupport.humanBytes(size) + ")");
            } catch (IOException e) {
                AppLogger.error("Could not remove " + target + ": " + e.getMessage());
            }
            return;
        }

        if (!recursive) {
            AppLogger.error(target.getFileName() + " is a directory. Use  :rm -r  to remove it.");
            return;
        }

        long[] tally = tally(target);
        if (tally[0] > CONFIRM_THRESHOLD && !confirmed) {
            AppLogger.raw("This deletes " + target);
            AppLogger.raw(tally[0] + " files in " + tally[1] + " folders, "
                          + FsSupport.humanBytes(tally[2]) + ", permanently.");
            AppLogger.raw("Add  --confirm  to go ahead.");
            return;
        }

        final Path root = target;
        final long count = tally[0];
        final long bytes = tally[2];

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    Files.walkFileTree(root, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            Files.delete(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException e)
                                throws IOException {
                            Files.delete(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                    return "";
                } catch (IOException e) {
                    return e.getMessage();
                }
            }

            @Override
            protected void done() {
                String failure;
                try { failure = get(); } catch (Exception e) { failure = e.getMessage(); }
                if (failure == null || failure.isEmpty()) {
                    AppLogger.success("Removed " + root + " -- " + count + " files, "
                                      + FsSupport.humanBytes(bytes) + " freed.");
                } else {
                    AppLogger.error("Could not remove " + root + ": " + failure);
                }
            }
        }.execute();
    }

    /** files, folders, bytes */
    private long[] tally(Path root) {
        long[] t = new long[3];
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    t[0]++;
                    t[2] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    t[1]++;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // A tree that cannot be walked is reported by the deletion itself
        }
        return t;
    }

    private void usage() {
        AppLogger.raw("Usage: :rm [-r] [--confirm] <path> [path...]");
        AppLogger.raw("Options:");
        AppLogger.raw("  -r            remove a directory and everything under it");
        AppLogger.raw("  --confirm     required past " + CONFIRM_THRESHOLD + " file");
        AppLogger.raw("  --help        show this help");
        AppLogger.raw("System directories and your home directory are refused.");
    }
}
