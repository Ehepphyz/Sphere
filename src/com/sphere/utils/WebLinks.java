package com.sphere.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Opens a web page in the user's browser from wherever Sphere runs.
 *
 * Under WSL the browser is Windows' own: xdg-open, when it exists at all,
 * reaches for a Linux browser that may not be there, or a text one that waits
 * on a terminal nobody sees. So WSL asks Windows first.
 */
public final class WebLinks {

    private WebLinks() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** Opens the page. False when no browser could be reached; the caller then shows the address. */
    public static boolean open(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            return false;
        }
        final String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return run(false, "rundll32", "url.dll,FileProtocolHandler", url);
        }
        if (os.contains("mac")) {
            return run(true, "open", url);
        }
        if (inWsl()) {
            // explorer.exe answers 1 even when it opened the page, so starting is enough.
            return run(true, "wslview", url) || run(false, "explorer.exe", url);
        }
        return run(true, "xdg-open", url);
    }

    /** Linux inside Windows' subsystem. */
    public static boolean inWsl() {
        if (System.getenv("WSL_DISTRO_NAME") != null) {
            return true;
        }
        try {
            return Files.readString(Path.of("/proc/version")).toLowerCase(Locale.ROOT).contains("microsoft");
        } catch (Exception notLinux) {
            return false;
        }
    }

    private static boolean run(boolean needsSuccess, String... command) {
        try {
            final Process p = new ProcessBuilder(List.of(command))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                // Still running: a browser that stays attached, which is an open page.
                return true;
            }
            return !needsSuccess || p.exitValue() == 0;
        } catch (Exception absent) {
            return false;
        }
    }
}
