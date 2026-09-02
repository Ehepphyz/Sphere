package com.sphere.utils;

/**
 * Cross-platform hardware profile detector for Sphere.
 */
public final class OSValidator {

    private static final String OS = System.getProperty("os.name").toLowerCase();

    /** The four environments Sphere runs in. WSL is a Linux that can reach C:. */
    public enum Platform { WINDOWS, WSL, LINUX, MACOS }

    private static final Platform CURRENT = detect();

    public static boolean isWindows() { return OS.contains("win"); }
    public static boolean isMac() { return OS.contains("mac"); }
    public static boolean isLinux() { return OS.contains("nix") || OS.contains("nux") || OS.contains("aix"); }

    /** True under the Windows Subsystem for Linux, where /mnt/<drive> reaches Windows. */
    public static boolean isWsl() { return CURRENT == Platform.WSL; }

    public static Platform current() { return CURRENT; }

    private static Platform detect() {
        if (OS.contains("win")) {
            return Platform.WINDOWS;
        }
        if (OS.contains("mac")) {
            return Platform.MACOS;
        }
        // The same probe lived in MkdirPlugin, CppToolchainDetector and
        // edSettingsFchooser; it belongs in one place.
        if (System.getenv("WSL_DISTRO_NAME") != null
                || System.getenv("WSL_INTEROP") != null) {
            return Platform.WSL;
        }
        try {
            java.nio.file.Path procVersion = java.nio.file.Paths.get("/proc/version");
            if (java.nio.file.Files.isReadable(procVersion)) {
                String banner = java.nio.file.Files.readString(procVersion).toLowerCase();
                if (banner.contains("microsoft") || banner.contains("wsl")) {
                    return Platform.WSL;
                }
            }
        } catch (Exception ignored) {
            // /proc unreadable: treat as plain Linux
        }
        return Platform.LINUX;
    }

    /**
     * Where WSL mounts the Windows drives. /mnt is only the default: /etc/wsl.conf
     * can move it, so assuming /mnt is wrong on a customised install.
     */
    public static String wslMountRoot() {
        return wslMountRoot(java.nio.file.Paths.get("/etc/wsl.conf"));
    }

    static String wslMountRoot(java.nio.file.Path wslConf) {
        try {
            if (java.nio.file.Files.isReadable(wslConf)) {
                boolean inAutomount = false;
                for (String line : java.nio.file.Files.readAllLines(wslConf)) {
                    String t = line.trim();
                    if (t.startsWith("#") || t.isEmpty()) {
                        continue;
                    }
                    if (t.startsWith("[")) {
                        inAutomount = t.equalsIgnoreCase("[automount]");
                        continue;
                    }
                    int eq = t.indexOf('=');
                    if (inAutomount && eq > 0
                            && t.substring(0, eq).trim().equalsIgnoreCase("root")) {
                        String root = t.substring(eq + 1).trim();
                        if (!root.isEmpty()) {
                            return root.endsWith("/") && root.length() > 1
                                 ? root.substring(0, root.length() - 1) : root;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // unreadable: the default below still applies
        }
        return "/mnt";
    }

    /** True when a drive letter is actually reachable from this WSL instance. */
    public static boolean isDriveMounted(String driveLetter) {
        if (driveLetter == null || driveLetter.isEmpty()) {
            return false;
        }
        java.io.File mount = new java.io.File(wslMountRoot(),
                                              driveLetter.toLowerCase().substring(0, 1));
        return mount.isDirectory();
    }

    /**
     * True when WSL can launch a Windows executable. Without binfmt interop a .exe
     * sitting on a mounted drive is visible but cannot be started.
     */
    public static boolean isWindowsInteropEnabled() {
        return new java.io.File("/proc/sys/fs/binfmt_misc/WSLInterop").exists()
            || new java.io.File("/proc/sys/fs/binfmt_misc/WSLInterop-late").exists();
    }

    /**
     * Resolves standard accelerator modifiers (Control Key vs Command Key).
     * @return ActionEvent.META_MASK for macOS, ActionEvent.CTRL_MASK for others.
     */
    public static int getPlatformModifier() {
        return isMac() ? java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()
                       : java.awt.event.InputEvent.CTRL_DOWN_MASK;
    }
}
