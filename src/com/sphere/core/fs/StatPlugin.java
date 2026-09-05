package com.sphere.core.fs;

import com.sphere.core.CommandRouter;
import com.sphere.utils.AppLogger;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.util.List;

/** :stat -- everything the filesystem knows about one entry. */
public class StatPlugin implements CommandRouter.CommandPlugin {

    private final CommandRouter router;

    public StatPlugin(CommandRouter router) {
        this.router = router;
    }

    @Override
    public String getName() {
        return "stat";
    }

    @Override
    public boolean supports(String input) {
        if (input == null) return false;
        String t = input.trim();
        if (t.startsWith("::")) return false;
        return t.equals(":stat") || t.startsWith(":stat ");
    }

    @Override
    public void execute(String input) {
        List<String> tokens = FsSupport.tokenize(input.trim());
        if (tokens.size() < 2 || tokens.get(1).equals("--help")) {
            AppLogger.raw("Usage: :stat <path>");
            return;
        }

        Path path = FsSupport.resolve(router, String.join(" ", tokens.subList(1, tokens.size())));
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            AppLogger.error("Not found: " + path);
            return;
        }

        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

            AppLogger.raw("  path        " + path);
            AppLogger.raw("  kind        " + kind(path, attributes));

            if (attributes.isRegularFile()) {
                AppLogger.raw("  size        " + attributes.size() + " bytes ("
                              + FsSupport.humanBytes(attributes.size()) + ")");
            }

            AppLogger.raw("  modified    " + FsSupport.STAMP.format(attributes.lastModifiedTime().toInstant()));
            AppLogger.raw("  accessed    " + FsSupport.STAMP.format(attributes.lastAccessTime().toInstant()));
            AppLogger.raw("  created     " + FsSupport.STAMP.format(attributes.creationTime().toInstant()));
            AppLogger.raw("  permissions " + FsSupport.permissions(path));

            try {
                FileOwnerAttributeView owner =
                        Files.getFileAttributeView(path, FileOwnerAttributeView.class);
                if (owner != null) AppLogger.raw("  owner       " + owner.getOwner().getName());
            } catch (IOException | UnsupportedOperationException noOwner) {
                AppLogger.raw("  owner       not available on this platform");
            }

            if (Files.isSymbolicLink(path)) {
                Path target = Files.readSymbolicLink(path);
                AppLogger.raw("  link to     " + target);
                Path resolved = path.getParent() == null ? target : path.getParent().resolve(target);
                AppLogger.raw("  resolves to " + resolved.normalize()
                              + (Files.exists(resolved) ? "" : "   (dangling)"));
            }

            if (attributes.isRegularFile()) {
                AppLogger.raw("  content     " + (FsSupport.looksBinary(path) ? "binary" : "text"));
            }

            try {
                FileStore store = Files.getFileStore(path);
                AppLogger.raw("  volume      " + store.name() + "  (" + store.type() + ")");
            } catch (IOException noStore) {
                AppLogger.raw("  volume      not available");
            }

        } catch (IOException e) {
            AppLogger.error("Could not read " + path + ": " + e.getMessage());
        }
    }

    private static String kind(Path path, BasicFileAttributes a) {
        if (a.isSymbolicLink() || Files.isSymbolicLink(path)) return "symbolic link";
        if (a.isDirectory()) return "directory";
        if (a.isRegularFile()) return "regular file";
        return "other";
    }
}
