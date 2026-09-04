package com.sphere.components.terminal;

import com.sphere.utils.SettingsManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The shells this machine actually has. What settings.conf declares comes first
 * and keeps the user's own label; the rest is found where the system keeps it,
 * instead of the hard-coded list that offered a "usr/bin/bash" with no leading
 * slash and a "Bash Sup" that meant nothing.
 */
public final class ShellSelector {

    private ShellSelector() { }

    public static List<ShellInfo> detectShells() {
        return detectShells(null);
    }

    public static List<ShellInfo> detectShells(SettingsManager settings) {
        if (settings == null) {
            ConfigLoader.load();
        } else {
            ConfigLoader.load(settings);
        }

        List<ShellInfo> shells = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (Map.Entry<String, String> declared : ConfigLoader.declaredShells().entrySet()) {
            add(shells, seen, label(declared.getKey()), declared.getValue());
        }
        if (isWindows()) {
            discoverWindows(shells, seen);
        } else {
            discoverUnix(shells, seen);
        }
        discoverEnvironments(shells);
        return shells;
    }

    /** The shell to open when nothing else was asked for. */
    public static ShellInfo defaultShell(SettingsManager settings) {
        List<ShellInfo> shells = detectShells(settings);
        return shells.isEmpty()
             ? new ShellInfo(isWindows() ? "CMD" : "Sh",
                             isWindows() ? "cmd.exe" : "/bin/sh",
                             isWindows() ? List.of() : List.of("-i"), null)
             : shells.get(0);
    }

    // ---- discovery ---------------------------------------------------------

    private static void discoverUnix(List<ShellInfo> shells, Set<String> seen) {
        // The login shell first: it is the one the user already lives in.
        String login = System.getenv("SHELL");
        if (login != null && !login.isBlank()) {
            add(shells, seen, prettyName(login), login);
        }
        try {
            Path list = Path.of("/etc/shells");
            if (Files.isReadable(list)) {
                for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
                    String entry = line.trim();
                    if (!entry.isEmpty() && entry.charAt(0) == '/') {
                        add(shells, seen, prettyName(entry), entry);
                    }
                }
            }
        } catch (IOException ignored) {
            // no /etc/shells: the PATH sweep below still finds what is installed
        }
        for (String name : new String[]{"bash", "zsh", "fish", "ksh", "dash", "sh", "tcsh"}) {
            String found = findOnPath(name);
            if (found != null) {
                add(shells, seen, prettyName(found), found);
            }
        }
    }

    private static void discoverWindows(List<ShellInfo> shells, Set<String> seen) {
        String system = env("SystemRoot", "C:\\Windows");
        String programs = env("ProgramFiles", "C:\\Program Files");
        String programsX86 = env("ProgramFiles(x86)", "C:\\Program Files (x86)");
        String localApps = env("LOCALAPPDATA", System.getProperty("user.home") + "\\AppData\\Local");

        add(shells, seen, "PowerShell 7", programs + "\\PowerShell\\7\\pwsh.exe");
        add(shells, seen, "PowerShell 7", findOnPath("pwsh.exe"));
        add(shells, seen, "PowerShell",
            system + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe");
        add(shells, seen, "CMD", system + "\\System32\\cmd.exe");
        add(shells, seen, "Git Bash", programs + "\\Git\\bin\\bash.exe");
        add(shells, seen, "Git Bash", programsX86 + "\\Git\\bin\\bash.exe");
        add(shells, seen, "Git Bash", localApps + "\\Programs\\Git\\bin\\bash.exe");
        add(shells, seen, "MSYS2", "C:\\msys64\\usr\\bin\\bash.exe");
        add(shells, seen, "MSYS2 UCRT64", "C:\\msys64\\ucrt64.exe");
        add(shells, seen, "WSL", system + "\\System32\\wsl.exe");
    }

    /**
     * Conda and pyenv are not shells. Launching them as one started a process that
     * printed its usage and died, which is what an empty terminal tab was. They
     * belong on top of a shell, as the line that activates them.
     */
    private static void discoverEnvironments(List<ShellInfo> shells) {
        ShellInfo host = shells.stream()
            .filter(s -> baseName(s.command).startsWith("bash")
                      || baseName(s.command).startsWith("zsh"))
            .findFirst().orElse(null);
        if (host == null) {
            return;
        }
        String conda = findOnPath("conda");
        if (conda != null) {
            File hook = new File(new File(conda).getParentFile().getParentFile(),
                                 "etc/profile.d/conda.sh");
            if (hook.isFile()) {
                shells.add(new ShellInfo(host.name + " + conda", host.command, host.arguments,
                    ". \"" + hook.getAbsolutePath() + "\" && conda activate base"));
            }
        }
        String pyenv = findOnPath("pyenv");
        if (pyenv != null) {
            shells.add(new ShellInfo(host.name + " + pyenv", host.command, host.arguments,
                "eval \"$(\"" + pyenv + "\" init -)\""));
        }
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * Adds a shell once. The key is the canonical path, so the same bash reached
     * through two names is one entry; the previous check compared ShellInfo's
     * default toString, which never contained a path and never matched.
     */
    private static void add(List<ShellInfo> shells, Set<String> seen, String name, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        File file = new File(path);
        if (!file.isFile() || !file.canExecute()) {
            return;
        }
        String key;
        try {
            key = file.getCanonicalPath();
        } catch (IOException ex) {
            key = file.getAbsolutePath();
        }
        if (!seen.add(key)) {
            return;
        }
        shells.add(new ShellInfo(name, file.getAbsolutePath(), interactiveArguments(path), null));
    }

    /**
     * The arguments that make the shell read its own rc file. Without them the
     * shell starts non-interactive: no prompt, no aliases, no user configuration.
     */
    public static List<String> interactiveArguments(String path) {
        String name = baseName(path);
        String lower = path.toLowerCase(Locale.ROOT);
        if (name.startsWith("bash")) {
            // A Git Bash or MSYS2 shell needs its login profile to build its PATH.
            return lower.contains("msys") || lower.contains("git")
                 ? List.of("--login", "-i")
                 : List.of("-i");
        }
        if (name.startsWith("zsh") || name.startsWith("fish") || name.startsWith("ksh")
                || name.startsWith("dash") || name.startsWith("tcsh") || name.equals("sh")) {
            return List.of("-i");
        }
        if (name.startsWith("pwsh") || name.startsWith("powershell")) {
            return List.of("-NoLogo");
        }
        return List.of();
    }

    private static String label(String configKey) {
        String bare = configKey.replaceFirst("^(WIN_|UNIX_)", "");
        return switch (bare) {
            case "CMD" -> "CMD";
            case "POWERSHELL" -> "PowerShell";
            case "PWSH" -> "PowerShell 7";
            case "GIT_BASH" -> "Git Bash";
            case "MSYS2" -> "MSYS2";
            case "WSL" -> "WSL";
            default -> {
                String[] words = bare.toLowerCase(Locale.ROOT).split("_");
                StringBuilder out = new StringBuilder();
                for (String word : words) {
                    if (word.isEmpty()) {
                        continue;
                    }
                    if (out.length() > 0) {
                        out.append(' ');
                    }
                    out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
                }
                yield out.toString();
            }
        };
    }

    private static String prettyName(String path) {
        String name = baseName(path);
        return name.isEmpty() ? path
             : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String baseName(String path) {
        String value = path.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        String name = slash >= 0 ? value.substring(slash + 1) : value;
        return name.toLowerCase(Locale.ROOT).replaceFirst("\\.exe$", "");
    }

    /** Looks along PATH in Java rather than spawning where or which for each name. */
    private static String findOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null || executable == null) {
            return null;
        }
        boolean windows = isWindows();
        String[] suffixes = windows && executable.indexOf('.') < 0
                          ? new String[]{".exe", ".cmd", ".bat"}
                          : new String[]{""};
        for (String folder : path.split(File.pathSeparator)) {
            if (folder.isBlank()) {
                continue;
            }
            for (String suffix : suffixes) {
                File candidate = new File(folder, executable + suffix);
                if (candidate.isFile() && candidate.canExecute()) {
                    return candidate.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }
}
