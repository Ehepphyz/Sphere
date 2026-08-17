package com.sphere.components.terminal;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ShellSelector {

    public static List<ShellInfo> detectShells() {
        ConfigLoader.load();
        List<ShellInfo> shells = new ArrayList<>();
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            addRequired(shells, "CMD", "WIN_CMD", "C:\\Windows\\System32\\cmd.exe");
            addRequired(shells, "PowerShell", "WIN_POWERSHELL", "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe");
            addRequired(shells, "PowerShell Core", "WIN_PWSH", null);
            addRequired(shells, "Git Bash", "WIN_GIT_BASH", "C:\\Program Files\\Git\\bin\\bash.exe");
            addRequired(shells, "MSYS2", "WIN_MSYS2", null);
            addRequired(shells, "WSL", "WIN_WSL", "C:\\Windows\\System32\\wsl.exe");
        } else {
            // Linux & MacOS default fallback paths
            addRequired(shells, "Bash", "UNIX_BASH", "usr/bin/bash");
            addRequired(shells, "Bash Sup", "UNIX_BASH_SUP", "/usr/bin/bash");
            addRequired(shells, "Zsh", "UNIX_ZSH", "/bin/zsh");
            addRequired(shells, "Sh", "UNIX_SH", "/bin/sh");
        }

        detectConda(shells);
        detectPyenv(shells);

        // Warning for debugging purposes if no shells were found
        if (shells.isEmpty()) {
            System.err.println("Warning: No shells were configured in settings.conf or found on the system.");
        }

        return shells;
    }

    /**
     * Adds a shell using the configured path, falling back to a default path if not defined.
     */
    private static void addRequired(List<ShellInfo> shells, String label, String configKey, String defaultPath) {
        String path = ConfigLoader.getPath(configKey);
        
        // If not found in config, use the hardcoded standard fallback path
        if (path == null || path.trim().isEmpty()) {
            path = defaultPath;
        }

        if (path != null) {
            addIfExists(shells, label, path);
        }
    }

    private static void addIfExists(List<ShellInfo> shells, String name, String path) {
        if (path != null && new File(path).exists()) {
            // To avoid guessing ShellInfo's internal field name, we track paths manually
            for (ShellInfo shell : shells) {
                // If it's a duplicate path under the exact same label, skip it
                if (shell.toString().contains(path)) {
                    return;
                }
            }
            shells.add(new ShellInfo(name, path));
        }
    }

    private static void detectConda(List<ShellInfo> shells) {
        String conda = findExecutable("conda");
        if (conda != null) shells.add(new ShellInfo("Conda (auto-init)", conda));
    }

    private static void detectPyenv(List<ShellInfo> shells) {
        String pyenv = findExecutable("pyenv");
        if (pyenv != null) shells.add(new ShellInfo("Pyenv (auto-init)", pyenv));
    }

    private static String findExecutable(String exe) {
        try {
            String command = System.getProperty("os.name").toLowerCase().contains("win") ? "where" : "which";
            ProcessBuilder pb = new ProcessBuilder(command, exe);
            Process p = pb.start();
            
            // Wait for the process to complete
            return (p.waitFor() == 0) ? exe : null;
            
        } catch (Exception ignored) {
            return null;
        }
    }
}
