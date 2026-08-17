package com.sphere.core.fs;

import com.sphere.core.CommandRouter;
import com.sphere.utils.AppLogger;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugin to create new directories.
 * Exclusively intercepts the internal ':mkdir' syntax to avoid OS terminal conflicts.
 */
public class MkdirPlugin implements CommandRouter.CommandPlugin {

    private final CommandRouter router;

    private static final Pattern ARG_SPLIT = Pattern.compile(
        "\"((?:\\\\\"|[^\"])+)\"|'((?:\\\\'|[^'])+)'|([^\\s]+)"
    );

    public MkdirPlugin(CommandRouter router) {
        this.router = router;
    }

    @Override
    public String getName() { return "mkdir"; }

    @Override
    public boolean supports(String input) {
        if (input == null) return false;
        String trimmed = input.trim();
        if (trimmed.startsWith("::")) return false;
        return trimmed.equals(":mkdir") || trimmed.startsWith(":mkdir ");
    }

    @Override
    public void execute(String input) {
        if (input == null) {
            AppLogger.error("Null input.");
            return;
        }

        String trimmed = input.trim();

        if (trimmed.startsWith("::")) {
            AppLogger.error("Hybrid syntax (::mkdir) is not allowed for directory creation.");
            return;
        }

        if (!trimmed.startsWith(":")) {
            AppLogger.error("Internal command must start with ':' prefix.");
            return;
        }
        String withoutColon = trimmed.substring(1).trim();

        List<String> tokens = splitArgs(withoutColon);
        if (tokens.isEmpty() || !tokens.get(0).equals("mkdir")) {
            AppLogger.error("Invalid mkdir command.");
            return;
        }

        if (tokens.size() == 1) {
            AppLogger.error("Usage: :mkdir <directory_path>");
            return;
        }

        StringBuilder sb = new StringBuilder(tokens.get(1));
        for (int i = 2; i < tokens.size(); i++) {
            sb.append(' ').append(tokens.get(i));
        }
        String rawPath = sb.toString().trim();
        if (rawPath.isEmpty()) {
            AppLogger.error("Usage: :mkdir <directory_path>");
            return;
        }

        rawPath = expandHome(rawPath);

        final Path targetPath;
        try {
            Path candidate = Paths.get(rawPath);
            targetPath = candidate.isAbsolute()
                ? candidate.toAbsolutePath().normalize()
                : router.getCurrentDirectory().resolve(candidate).toAbsolutePath().normalize();
        } catch (InvalidPathException ipe) {
            AppLogger.error("Invalid path: " + ipe.getMessage());
            return;
        }

        boolean isWin = isWindows();
        
        // FIX A: validateWindowsPath now correctly permits drive letters (C:\)
        if (isWin && !validateWindowsPath(targetPath.toString())) {
            AppLogger.error("Invalid characters detected in Windows path: " + targetPath);
            return;
        }

        if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(targetPath)) {
            AppLogger.error("A file exists at the target path: " + targetPath);
            return;
        }

        if (Files.exists(targetPath) && Files.isDirectory(targetPath)) {
            AppLogger.info("Directory already exists: " + targetPath);
            return;
        }

        // FIX C: Accurate and safer system path evaluation
        if (isSensitiveLocation(targetPath)) {
            AppLogger.error("Refusing to create directory in a sensitive system location: " + targetPath);
            return;
        }

        // Create directories asynchronously to avoid blocking UI
        new SwingWorker<Void, String>() {
            private String successMessage = null;
            private String errorMessage = null;

            @Override
            protected Void doInBackground() {
                try {
                    Files.createDirectories(targetPath);
                    successMessage = "Directory created: " + targetPath;
                } catch (AccessDeniedException ade) {
                    errorMessage = "Permission denied: " + ade.getMessage();
                } catch (FileAlreadyExistsException faee) {
                    errorMessage = "A file already exists at the target path: " + targetPath;
                } catch (IOException ioe) {
                    errorMessage = "Failed to create directory: " + ioe.getMessage();
                } catch (SecurityException se) {
                    errorMessage = "Security manager prevented directory creation.";
                }
                return null;
            }

            @Override
            protected void done() {
                // FIX B: UI updates and logs are strictly executed back on the EDT thread
                if (successMessage != null) {
                    AppLogger.raw(successMessage);
                } else if (errorMessage != null) {
                    AppLogger.error(errorMessage);
                }
            }
        }.execute();
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

    private boolean validateWindowsPath(String path) {
        if (path == null) return false;
        String pathWithoutDrive = path.replaceFirst("^[A-Za-z]:", "");
        return !pathWithoutDrive.matches(".*[<>:\"|?*].*");
    }

    private boolean isSensitiveLocation(Path path) {
        if (path == null) return false;
        String p = path.toAbsolutePath().toString().toLowerCase(Locale.ROOT);

        // Unix-like system folders with strict boundary matching
        if (p.equals("/") || p.equals("/etc") || p.startsWith("/etc/") 
            || p.equals("/bin") || p.startsWith("/bin/") 
            || p.equals("/sbin") || p.startsWith("/sbin/")
            || p.equals("/usr") || p.startsWith("/usr/") 
            || p.equals("/var") || p.startsWith("/var/") 
            || p.equals("/proc") || p.startsWith("/proc/") 
            || p.equals("/sys") || p.startsWith("/sys/")) {
            return true;
        }

        // Windows critical base directories with precise boundary matching
        if (isWindows()) {
            if (p.matches("^[a-z]:\\\\windows(\\\\|$)") 
                || p.matches("^[a-z]:\\\\program files(\\\\|$)")
                || p.matches("^[a-z]:\\\\program files \\(x86\\)(\\\\|$)") 
                || p.matches("^[a-z]:\\\\system32(\\\\|$)")) {
                return true;
            }
        }

        if (System.getenv("WSL_DISTRO_NAME") != null) {
            if (p.startsWith("/mnt/") && p.split("/").length <= 3) {
                return true;
            }
        }

        return false;
    }
}