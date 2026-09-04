package com.sphere.components.terminal;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Runs a shell and carries its bytes both ways.
 *
 * The shell is started interactive and with the environment settings.conf
 * builds, so aliases, prompt and the PATH Sphere resolved are all there. Output
 * is read as it arrives rather than line by line, which is what makes a prompt
 * appear before the user has pressed anything.
 */
public class TerminalEngine {

    public interface OutputListener {
        void onOutput(String text);
    }

    /**
     * A shell started without a terminal device says so twice, every time. The
     * messages are exact and harmless; they are dropped rather than shown.
     */
    private static final String[] STARTUP_NOISE = {
        "cannot set terminal process group",
        "no job control in this shell"
    };

    /**
     * Tools hide their colours when their output is not a terminal, and here it
     * never is: ls, grep and the compilers all check that themselves, so -auto
     * yields nothing whatever TERM says. This asks them once to colour anyway.
     * Each form is probed before use, so a BSD or macOS shell keeps what it has.
     *
     * The cost is real: an aliased ls writes its colour codes into a redirection
     * too. A leading backslash bypasses the alias, as in \ls > list.txt.
     */
    private static final String COLOUR_PREAMBLE = String.join("; ",
        "if ls --color=always /dev/null >/dev/null 2>&1",
        "then alias ls='ls --color=always'",
        "else CLICOLOR_FORCE=1; export CLICOLOR_FORCE; alias ls='ls -G'; fi",
        "if echo x | grep --color=always -q x 2>/dev/null",
        "then alias grep='grep --color=always'; alias egrep='grep -E --color=always'; "
            + "alias fgrep='grep -F --color=always'; fi",
        "if diff --color=always /dev/null /dev/null >/dev/null 2>&1",
        "then alias diff='diff --color=always'; fi",
        "if command -v git >/dev/null 2>&1; then alias git='git -c color.ui=always'; fi",
        "for _c in gcc g++ cc clang clang++",
        "do command -v $_c >/dev/null 2>&1 && alias $_c=\"$_c -fdiagnostics-color=always\"; done",
        "unset _c; FORCE_COLOR=1; export FORCE_COLOR");

    /**
     * Reproduces what --login would have done. A login shell ignores --rcfile, so
     * Git Bash and MSYS2 are started without it and read their profile from here,
     * which is what lets the colour settings ride along without a typed line.
     */
    private static final String LOGIN_PROFILE = String.join("\n",
        "[ -r /etc/profile ] && . /etc/profile",
        "if [ -r \"$HOME/.bash_profile\" ]; then . \"$HOME/.bash_profile\";",
        "elif [ -r \"$HOME/.bash_login\" ]; then . \"$HOME/.bash_login\";",
        "elif [ -r \"$HOME/.profile\" ]; then . \"$HOME/.profile\"; fi");

    private static final String BASHRC_PROFILE =
        "[ -r \"$HOME/.bashrc\" ] && . \"$HOME/.bashrc\"";

    private static final String ZSHRC_PROFILE =
        "[ -r \"$HOME/.zshrc\" ] && . \"$HOME/.zshrc\"";

    private final ShellInfo shell;
    private final File workingDirectory;
    private final CopyOnWriteArrayList<OutputListener> listeners = new CopyOnWriteArrayList<>();

    private Process process;
    private BufferedWriter writer;
    private Thread readerThread;
    private volatile boolean starting;
    private boolean forceColour = true;

    public TerminalEngine(String shellCommand) {
        this(new ShellInfo(shellCommand, shellCommand,
                           ShellSelector.interactiveArguments(shellCommand), null), null);
    }

    public TerminalEngine(ShellInfo shell, File workingDirectory) {
        this.shell = shell;
        this.workingDirectory = workingDirectory != null && workingDirectory.isDirectory()
                              ? workingDirectory
                              : new File(System.getProperty("user.dir"));
    }

    public void addOutputListener(OutputListener l) {
        // Registered twice, a view writes everything twice.
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeOutputListener(OutputListener l) {
        listeners.remove(l);
    }

    private void fireOutput(String text) {
        for (OutputListener l : listeners) {
            l.onOutput(text);
        }
    }

    /**
     * The charset the shell writes in. Java reports the platform's own encoding
     * since 18, which is right for the modern Windows consoles; the OEM code page
     * stays as the fallback for a French console still running in 850.
     */
    private Charset getShellCharset() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            for (String property : new String[]{"stdout.encoding", "sun.stdout.encoding",
                                                "native.encoding"}) {
                String declared = System.getProperty(property);
                if (declared != null && !declared.isBlank()) {
                    try {
                        return Charset.forName(declared.trim());
                    } catch (Exception ignored) {
                        // an encoding name this JVM does not know
                    }
                }
            }
            return Charset.forName("IBM850");
        }
        return StandardCharsets.UTF_8;
    }

    public void start() {
        if (process != null) {
            return;
        }
        try {
            Map<String, String> extraEnvironment = new java.util.LinkedHashMap<>();
            List<String> command = new ArrayList<>();
            command.add(shell.command);
            command.addAll(launchArguments(extraEnvironment));

            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workingDirectory)
                    .redirectErrorStream(true);
            builder.environment().putAll(ConfigLoader.environment());
            builder.environment().putAll(extraEnvironment);
            starting = true;
            process = builder.start();

            Charset shellCharset = getShellCharset();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),
                                                               shellCharset));

            readerThread = new Thread(() -> read(shellCharset), "terminal-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            if (shell.initCommand != null && !shell.initCommand.isBlank()) {
                sendRaw(shell.initCommand);
            }
        } catch (IOException e) {
            fireOutput("[!] Failed to start shell: " + e.getMessage() + "\n");
        }
    }

    /**
     * Reads whatever is available instead of waiting for a newline: a prompt ends
     * without one, and a line-based reader held it back until the next command.
     */
    private void read(Charset charset) {
        char[] buffer = new char[4096];
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(process.getInputStream(), charset))) {
            int count;
            while ((count = in.read(buffer)) != -1) {
                String chunk = new String(buffer, 0, count);
                if (starting) {
                    chunk = withoutStartupNoise(chunk);
                }
                if (!chunk.isEmpty()) {
                    fireOutput(chunk);
                }
            }
        } catch (IOException e) {
            // the pipe closes when the shell exits, which is not a failure
        }
    }

    private String withoutStartupNoise(String chunk) {
        boolean noisy = false;
        for (String noise : STARTUP_NOISE) {
            if (chunk.contains(noise)) {
                noisy = true;
                break;
            }
        }
        if (!noisy) {
            return chunk;
        }
        StringBuilder kept = new StringBuilder();
        for (String line : chunk.split("\n", -1)) {
            boolean drop = false;
            for (String noise : STARTUP_NOISE) {
                if (line.contains(noise)) {
                    drop = true;
                    break;
                }
            }
            if (!drop) {
                if (kept.length() > 0) {
                    kept.append('\n');
                }
                kept.append(line);
            }
        }
        return kept.toString();
    }

    /**
     * Sends what the user typed. No local echo: an interactive shell prints the
     * line back itself, and writing it here as well showed every command twice.
     */
    public void sendCommand(String cmd) {
        starting = false;      // the first thing the user types ends the startup
        sendRaw(cmd);
    }

    /** Sends a line without showing it, for the activation of an environment. */
    public void sendRaw(String cmd) {
        if (cmd == null || writer == null) {
            return;
        }
        try {
            writer.write(cmd);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            fireOutput("[!] Failed to send command: " + e.getMessage() + "\n");
        }
    }

    /**
     * Stops the running command and keeps the shell, which is what Ctrl+C is for.
     * Without a terminal device no signal reaches the child, so its descendants
     * are ended directly; the shell itself is left alone and prompts again.
     */
    public void interrupt() {
        Process live = process;
        if (live == null || !live.isAlive()) {
            return;
        }
        List<ProcessHandle> running = live.toHandle().descendants().toList();
        if (running.isEmpty()) {
            sendRaw("");
            return;
        }
        running.forEach(ProcessHandle::destroy);
    }

    public void stop() {
        Process live = process;
        process = null;
        if (live == null) {
            return;
        }
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ignored) {
            // the shell is going away anyway
        }
        writer = null;
        live.toHandle().descendants().forEach(ProcessHandle::destroy);
        live.destroy();
        try {
            if (!live.waitFor(1200, TimeUnit.MILLISECONDS)) {
                live.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            live.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        fireOutput("\n[i] Terminal stopped.\n");
    }

    /** Turns the colour setup off. Must be set before start(). */
    public void setForceColour(boolean on) {
        forceColour = on;
    }

    /**
     * The arguments the shell is launched with, colour setup included.
     *
     * The settings go in through the shell's own startup file rather than being
     * typed in: a line sent to an interactive shell is printed back, so the
     * previous approach put its own source command on screen -- and under Windows
     * a path written D:\... is not a path any POSIX shell can open, so it printed
     * an error and no colour at all.
     */
    private List<String> launchArguments(Map<String, String> environment) {
        List<String> arguments = new ArrayList<>(shell.arguments);
        if (!forceColour) {
            return arguments;
        }
        String family = shellFamily();
        try {
            switch (family) {
                case "bash" -> {
                    // --rcfile is ignored by a login shell, so the login files are
                    // read from our own file instead of through --login.
                    boolean login = arguments.remove("--login") | arguments.remove("-l");
                    Path rc = write("terminal-colour.bashrc",
                        (login ? LOGIN_PROFILE : BASHRC_PROFILE) + "\n" + COLOUR_PREAMBLE);
                    arguments.add(0, forwardSlashes(rc));
                    arguments.add(0, "--rcfile");
                }
                case "zsh" -> {
                    Path folder = write("zdotdir/.zshrc",
                        ZSHRC_PROFILE + "\n" + COLOUR_PREAMBLE).getParent();
                    environment.put("ZDOTDIR", folder.toAbsolutePath().toString());
                }
                case "sh" -> {
                    // An interactive POSIX shell reads whatever $ENV points at.
                    Path rc = write("terminal-colour.env", COLOUR_PREAMBLE);
                    environment.put("ENV", forwardSlashes(rc));
                }
                case "pwsh" -> {
                    // PowerShell 7 drops its colours when its output is redirected,
                    // and here it always is. Untested from this side: no pwsh.
                    arguments.add("-NoExit");
                    arguments.add("-Command");
                    arguments.add("$PSStyle.OutputRendering = 'Ansi'");
                }
                case "wsl" -> {
                    // wsl.exe alone starts the distribution's shell without a
                    // terminal, so it is neither interactive nor coloured. The
                    // file is reached through /mnt, the only path both sides share.
                    Path rc = write("terminal-colour.bashrc",
                        BASHRC_PROFILE + "\n" + COLOUR_PREAMBLE);
                    String inside = mountPath(rc);
                    if (inside != null) {
                        arguments.add("--");
                        arguments.add("bash");
                        arguments.add("--rcfile");
                        arguments.add(inside);
                        arguments.add("-i");
                    }
                }
                default -> { }        // cmd has nothing to set
            }
        } catch (IOException ex) {
            return new ArrayList<>(shell.arguments);   // colours stay as they are
        }
        return arguments;
    }

    private static Path write(String name, String content) throws IOException {
        Path target = Path.of("config", name);
        Path folder = target.getParent();
        if (folder != null) {
            java.nio.file.Files.createDirectories(folder);
        }
        java.nio.file.Files.writeString(target, content + "\n", StandardCharsets.UTF_8);
        return target.toAbsolutePath();
    }

    /** MSYS2 and Git Bash read D:/a/b; they never read D:\a\b. */
    private static String forwardSlashes(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /** The same file seen from inside WSL: D:\a\b is /mnt/d/a/b there. */
    private static String mountPath(Path path) {
        String windows = path.toAbsolutePath().toString().replace('\\', '/');
        if (windows.length() < 3 || windows.charAt(1) != ':') {
            return null;
        }
        return "/mnt/" + Character.toLowerCase(windows.charAt(0)) + windows.substring(2);
    }

    private String shellFamily() {
        String name = shell.command.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        name = (slash >= 0 ? name.substring(slash + 1) : name)
               .toLowerCase(Locale.ROOT).replaceFirst("\\.exe$", "");
        if (name.startsWith("bash") || name.equals("rbash")) {
            return "bash";
        }
        if (name.startsWith("zsh")) {
            return "zsh";
        }
        if (name.equals("sh") || name.startsWith("dash") || name.endsWith("ksh")) {
            return "sh";
        }
        if (name.startsWith("pwsh")) {
            return "pwsh";
        }
        if (name.equals("wsl")) {
            return "wsl";
        }
        return name;
    }

    public boolean isRunning() {
        Process live = process;
        return live != null && live.isAlive();
    }

    public String getShellCommand() {
        return shell.command;
    }

    public ShellInfo getShell() {
        return shell;
    }

    public File getWorkingDirectory() {
        return workingDirectory;
    }

    /**
     * Where the shell stands now, which a cd moves and nothing in the protocol
     * reports. Linux publishes it under /proc; elsewhere the directory the shell
     * was started in is the honest answer.
     */
    public File currentDirectory() {
        Process live = process;
        if (live != null && live.isAlive()) {
            java.nio.file.Path link = java.nio.file.Path.of("/proc", String.valueOf(live.pid()), "cwd");
            try {
                if (java.nio.file.Files.isSymbolicLink(link)) {
                    return java.nio.file.Files.readSymbolicLink(link).toFile();
                }
            } catch (IOException | SecurityException ignored) {
                // not a Linux host, or the link is not ours to read
            }
        }
        return workingDirectory;
    }

    /** The environment the shell was given, for the panel to show. */
    public Map<String, String> getEnvironmentOverlay() {
        return ConfigLoader.environment();
    }
}
