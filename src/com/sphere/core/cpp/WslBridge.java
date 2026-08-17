package com.sphere.utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Intelligent cross-platform utility to bridge Windows host environments with WSL targets.
 */
public final class WslBridge {

    /**
     * Translates a standard absolute Windows file path to a valid WSL path format.
     * Example: "C:\projects\main.cpp" -> "/mnt/c/projects/main.cpp"
     */
    public static String toWslPath(String windowsPath) {
        if (windowsPath == null) return "";
        String clean = windowsPath.replace("\\", "/");
        if (clean.matches("^[a-zA-Z]:.*")) {
            String drive = clean.substring(0, 1).toLowerCase();
            String remainder = clean.substring(2);
            return "/mnt/" + drive + remainder;
        }
        return clean;
    }

    /**
     * Inspects if a command requires a WSL wrapper and adapts the entire argument list.
     */
    public static List<String> wrapCommand(List<String> command, boolean isWslToolchain) {
        if (!isWslToolchain) {
            return command; // Non-WSL command remains completely unchanged
        }

        List<String> wslCmd = new ArrayList<>();
        wslCmd.add("wsl");
        
        // When routing via 'wsl', it's safer to pass arguments directly to the Linux subsystem bash shell
        for (String arg : command) {
            // Automatically convert potential Windows absolute paths within arguments to WSL paths
            if (arg.contains(":\\") || arg.contains(":/")) {
                wslCmd.add(toWslPath(arg));
            } else {
                wslCmd.add(arg);
            }
        }
        return wslCmd;
    }
}