package com.sphere.core.fs;

import com.sphere.core.CommandRouter;
import com.sphere.utils.AppLogger;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugin to create symbolic links.
 * Exclusively intercepts the internal ':symlink' syntax to avoid OS terminal conflicts.
 */
public class SymlinkPlugin implements CommandRouter.CommandPlugin {

    private final CommandRouter router;

    private static final Pattern ARG_SPLIT = Pattern.compile(
        "\"((?:\\\\\"|[^\"])+)\"|'((?:\\\\'|[^'])+)'|([^\\s]+)"
    );

    public SymlinkPlugin(CommandRouter router) {
        this.router = router;
    }

    @Override
    public String getName() { return "symlink"; }

    @Override
    public boolean supports(String input) {
        if (input == null) return false;
        String trimmed = input.trim();
        if (trimmed.startsWith("::")) return false;
        return trimmed.equals(":symlink") || trimmed.startsWith(":symlink ");
    }

    @Override
    public void execute(String input) {
        if (input == null) {
            AppLogger.error("Null input.");
            return;
        }

        String trimmed = input.trim();

        if (trimmed.startsWith("::")) {
            AppLogger.error("Hybrid syntax (::symlink) is not allowed for symbolic link creation.");
            return;
        }

        if (!trimmed.startsWith(":")) {
            AppLogger.error("Internal command must start with ':' prefix.");
            return;
        }
        String withoutColon = trimmed.substring(1).trim();
        List<String> args = splitArgs(withoutColon);

        if (args.isEmpty() || !args.get(0).equals("symlink")) {
            AppLogger.error("Invalid symlink command.");
            return;
        }

        if (args.size() < 3) {
            AppLogger.error("Usage: :symlink <target_path> <link_name>");
            return;
        }

        String targetRaw = expandHome(args.get(1));
        
        // FIX B: Support spaces in link name without quotes by joining remaining arguments
        StringBuilder linkNameBuilder = new StringBuilder(args.get(2));
        for (int i = 3; i < args.size(); i++) {
            linkNameBuilder.append(" ").append(args.get(i));
        }
        String linkRaw = expandHome(linkNameBuilder.toString().trim());

        Path targetPath;
        try {
            targetPath = Paths.get(targetRaw);
        } catch (InvalidPathException ipe) {
            AppLogger.error("Invalid target path: " + ipe.getMessage());
            return;
        }

        Path linkPath;
        try {
            Path linkCandidate = Paths.get(linkRaw);
            linkPath = linkCandidate.isAbsolute()
                ? linkCandidate.toAbsolutePath().normalize()
                : router.getCurrentDirectory().resolve(linkCandidate).toAbsolutePath().normalize();
        } catch (InvalidPathException ipe) {
            AppLogger.error("Invalid link path: " + ipe.getMessage());
            return;
        }

        Path targetResolved = targetPath.isAbsolute()
            ? targetPath.toAbsolutePath().normalize()
            : router.getCurrentDirectory().resolve(targetPath).toAbsolutePath().normalize();

        boolean isWin = isWindows();

        // FIX A: validateWindowsPath no longer breaks on absolute drive paths (C:\...)
        if (isWin && !validateWindowsPath(linkPath.toString())) {
            AppLogger.error("Invalid characters detected in Windows path for link: " + linkPath);
            return;
        }

        if (Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS)) {
            AppLogger.error("Destination already exists: " + linkPath);
            return;
        }

        try {
            if (Files.exists(targetResolved, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(targetResolved, linkPath)) {
                AppLogger.error("Target and link refer to the same file (circular).");
                return;
            }
        } catch (IOException | SecurityException ignored) {}

        if (!Files.exists(targetResolved, LinkOption.NOFOLLOW_LINKS)) {
            if (isWin) {
                AppLogger.error("Target does not exist. On Windows, symbolic links typically require an existing target.");
                return;
            } else {
                AppLogger.info("Warning: target does not exist; creating a dangling symbolic link.");
            }
        }

        if (isWin && !isWindowsSymlinkSupported()) {
            AppLogger.error("Windows symbolic links require Developer Mode or Administrator privileges. Aborting.");
            return;
        }

        try {
            Files.createSymbolicLink(linkPath, targetResolved);
            AppLogger.raw("Symbolic link created: " + linkPath + " -> " + targetResolved);
        } catch (UnsupportedOperationException uoe) {
            AppLogger.error("Filesystem does not support symbolic links on this mount or platform.");
        } catch (FileAlreadyExistsException faee) {
            AppLogger.error("Destination already exists: " + linkPath);
        } catch (IOException ioe) {
            AppLogger.error("Failed to create symbolic link: " + ioe.getMessage());
        } catch (SecurityException se) {
            AppLogger.error("Security manager prevented creating the symbolic link.");
        }
    }

    private List<String> splitArgs(String input) {
        List<String> parts = new ArrayList<>();
        Matcher m = ARG_SPLIT.matcher(input);
        while (m.find()) {
            if (m.group(1) != null) parts.add(m.group(1).replace("\\\"", "\""));
            else if (m.group(2) != null) parts.add(m.group(2).replace("\\'", "'"));
            else if (m.group(3) != null) parts.add(m.group(3));
        }
        return parts;
    }

    private String expandHome(String path) {
        if (path == null || path.isEmpty()) return path;
        if (path.equals("~")) return System.getProperty("user.home");
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase(Locale.ROOT).contains("win");
    }

    /**
          * FIX A: Safely validates forbidden Windows characters while allowing the drive letter colon (C:\).
          */
    private boolean validateWindowsPath(String path) {
        if (path == null) return false;
        String pathWithoutDrive = path.replaceFirst("^[A-Za-z]:", "");
        return !pathWithoutDrive.matches(".*[<>:\"|?*].*");
    }

    private boolean isWindowsSymlinkSupported() {
        if (!isWindows()) return true;
        Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
        Path testTarget = tmpDir.resolve("symlink_test_target_" + System.nanoTime());
        Path testLink = tmpDir.resolve("symlink_test_link_" + System.nanoTime());
        try {
            Files.write(testTarget, new byte[]{0}, StandardOpenOption.CREATE_NEW);
            try {
                Files.createSymbolicLink(testLink, testTarget);
                return true;
            } catch (Exception e) {
                return false;
            } finally {
                try { Files.deleteIfExists(testLink); } catch (IOException ignored) {}
                try { Files.deleteIfExists(testTarget); } catch (IOException ignored) {}
            }
        } catch (IOException ioe) {
            return false;
        }
    }
}