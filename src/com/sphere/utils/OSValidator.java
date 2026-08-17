package com.sphere.utils;

/**
 * Cross-platform hardware profile detector for Sphere.
 */
public final class OSValidator {

    private static final String OS = System.getProperty("os.name").toLowerCase();

    public static boolean isWindows() { return OS.contains("win"); }
    public static boolean isMac() { return OS.contains("mac"); }
    public static boolean isLinux() { return OS.contains("nix") || OS.contains("nux") || OS.contains("aix"); }

    /**
     * Resolves standard accelerator modifiers (Control Key vs Command Key).
     * @return ActionEvent.META_MASK for macOS, ActionEvent.CTRL_MASK for others.
     */
    public static int getPlatformModifier() {
        return isMac() ? java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() 
                       : java.awt.event.InputEvent.CTRL_DOWN_MASK;
    }
}
