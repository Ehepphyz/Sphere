package com.sphere.core;

import com.sphere.core.commandrouterincludes.*;
import com.sphere.core.commandrouterincludes.crdispatchers.*;
import com.sphere.core.commandrouterincludes.cmdscibackend.*;
import com.sphere.core.python.PythonBackend;
import com.sphere.core.cpp.CppBackend;
import com.sphere.core.rootbackend.RootBackend;
import com.sphere.utils.AppLogger;
import com.sphere.core.commandrouterincludes.HistoryManager;
import com.sphere.core.fs.LsPlugin;
import com.sphere.core.fs.CatPlugin;
import com.sphere.core.fs.MkdirPlugin;
import com.sphere.core.fs.SymlinkPlugin;
import com.sphere.core.fs.CopyMovePlugin;
import com.sphere.core.fs.RemovePlugin;
import com.sphere.core.fs.GrepPlugin;
import com.sphere.core.fs.FindPlugin;
import com.sphere.core.fs.HeadTailPlugin;
import com.sphere.core.fs.WcPlugin;
import com.sphere.core.fs.DiskUsagePlugin;
import com.sphere.core.fs.TreePlugin;
import com.sphere.core.fs.StatPlugin;
import com.sphere.core.fs.HashPlugin;
import com.sphere.core.fs.DiffPlugin;
import com.sphere.core.fs.TouchPlugin;
import com.sphere.core.fs.WhichPlugin;
import com.sphere.core.fs.WatchPlugin;
import com.sphere.core.fs.RootFilePlugin;
import com.sphere.utils.PythonEnvs;

import javax.swing.*;
import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.*;

/**
 * Orchestrates command routing, mode management, and sandboxed process execution.
 * Integrated with Directory Stack, History, Autocomplete, Plugin management, and Hybrid Parsing.
 */
public class CommandRouter {

    public interface CommandPlugin {
        String getName();
        boolean supports(String input);
        void execute(String input);
    }

    private static final Pattern ENGINE_PATTERN = Pattern.compile("^(?:::|:)([A-Za-z0-9._-]+)");
    private static final Pattern SNIPPET_PATTERN = Pattern.compile("\\[@\\s*(.*?)\\s*\\]");

    private final Map<String, Backend> backends = new HashMap<>();
    private final CommandRegistry registry = new CommandRegistry();
    private final HistoryManager history = new HistoryManager();

    private final Set<String> knownCommands = ConcurrentHashMap.newKeySet();
    private final List<CommandPlugin> plugins = new ArrayList<>();

    private final AtomicReference<String> currentMode = new AtomicReference<>(null);
    private final CommandContext ctx;

    private RootDispatcher rootDispatcher;

    private Consumer<String> statusBarUpdater;
    private Consumer<String> modeUpdater;

    private Path currentDirectory = Paths.get(System.getProperty("user.dir"));

    /**
     * Keeps com.sphere.core.fs.WorkingDirectory in step. It holds a second current
     * directory read by SmartDispatcher, and it never moved, so "open last file"
     * looked in the folder Sphere started in rather than the one the user is in.
     */
    private void setWorkingDirectory() {
        com.sphere.core.fs.WorkingDirectory.changeTo(currentDirectory.toString());
    }
    private final Deque<Path> dirStack = new ArrayDeque<>();
    private Path previousDirectory = null;

    private static final Set<String> PRESERVED_ENV_KEYS = Set.of(
        "PATH", "HOME", "LANG", "LC_ALL", "SHELL", "USER", "LOGNAME",
        "SystemRoot", "windir", "USERPROFILE", "APPDATA", "LOCALAPPDATA",
        "PROGRAMFILES", "PROGRAMFILES(X86)", "COMMONPROGRAMFILES",
        "SYSTEMDRIVE", "ALLUSERSPROFILE", "COMPUTERNAME", "PUBLIC",
        // Where a run writes down its variables. Stripped with the rest, the
        // wrapper would fall back to a folder nothing is watching.
        "SPHERE_VARS"
    );

    // Highly optimized object mapping to preserve token context and execution sequence
    public static class ParsedCommand {
        public enum RootType { MACRO, COMMAND }
        public RootType type;
        public String languageOrApp;
        public String filepath; // Extracted only if it matches file criteria
        public List<String> macroTokens = new ArrayList<>(); // Preserves exact user layout order
        public boolean hasSnippet;
        public List<String> snippetTokens = new ArrayList<>(); // Preserves exact snippet layout order
        public List<String> macroFlags = new ArrayList<>();
        public List<String> macroOptions = new ArrayList<>();
        public List<String> snippetFlags = new ArrayList<>();
        public List<String> snippetOptions = new ArrayList<>();
    }

    /**
     * The router this session is running, for a window that wants to type.
     *
     * Sphere builds exactly one. A panel that assembles commands should send
     * them the way the user would rather than reach past the router into the
     * backends, so that what it does and what the console does are the same
     * thing.
     */
    private static volatile CommandRouter active;

    public static CommandRouter active() {
        return active;
    }

    public CommandRouter() {
        active = this;
        // Register default file system command plugins
        this.registerPlugin(new LsPlugin(this));
        this.registerPlugin(new CatPlugin(this));
        this.registerPlugin(new MkdirPlugin(this));
        this.registerPlugin(new SymlinkPlugin(this));
        this.registerPlugin(new CopyMovePlugin(this));
        this.registerPlugin(new RemovePlugin(this));
        this.registerPlugin(new GrepPlugin(this));
        this.registerPlugin(new FindPlugin(this));
        this.registerPlugin(new HeadTailPlugin(this));
        this.registerPlugin(new WcPlugin(this));
        this.registerPlugin(new DiskUsagePlugin(this));
        this.registerPlugin(new TreePlugin(this));
        this.registerPlugin(new StatPlugin(this));
        this.registerPlugin(new HashPlugin(this));
        this.registerPlugin(new DiffPlugin(this));
        this.registerPlugin(new TouchPlugin(this));
        this.registerPlugin(new WhichPlugin(this));
        this.registerPlugin(new WatchPlugin(this));
        this.registerPlugin(new RootFilePlugin(this));

        // Register default execution backends
        backends.put("py", new PythonBackend());
        backends.put("cpp", new CppBackend());

        // Initialize application settings manager and ROOT backend
        com.sphere.utils.SettingsManager settingsManager = new com.sphere.utils.SettingsManager();
        backends.put("julia", new com.sphere.core.julia.JuliaBackend(settingsManager));
        String rootPath = settingsManager.getProperty("ROOT_DIR"); 
        
        try {
            com.sphere.core.Backend rootInstance = (com.sphere.core.Backend) new RootBackend(rootPath, settingsManager);
            backends.put("root", rootInstance);
        } catch (Exception e) {
            AppLogger.error("Failed to initialize RootBackend: " + e.getMessage());
        }

        // Initialize command execution context
        ctx = new CommandContext();
        ctx.router = this;
        ctx.backends = backends;
        ctx.history = history;
        ctx.tokenizer = Tokenizer.DEFAULT;

        // Register dispatchers with priority levels
        registry.addDispatcher(new InternalDispatcher(), 10);
        registry.addDispatcher(new OneShotDispatcher(), 10);
        registry.addDispatcher(new MadGraphDispatcher(), 9);
        
        this.rootDispatcher = new RootDispatcher();
        registry.addDispatcher(this.rootDispatcher, 9);
        
        registry.addDispatcher(new Geant4Dispatcher(), 9);
        registry.addDispatcher(new HerwigDispatcher(), 9);
        registry.addDispatcher(new PluginDispatcher(), 8);
        registry.addDispatcher(new SnippetDispatcher(), 2);
        registry.addDispatcher(new SmartDispatcher(), 1);

        // Populate known commands for auto-completion and validation
        com.sphere.core.commands.CommandDefinitions.all().keySet().forEach(knownCommands::add);

        knownCommands.addAll(List.of(
            "cd", "pwd", "pushd", "popd", "dirs",
            ":cd", ":pwd", ":pushd", ":popd", ":dirs",
            ":cpp", ":root", "::root",
            ":cp", ":mv", ":rm", ":grep", ":find", ":head", ":tail", ":tail-stop",
            ":wc", ":du", ":df", ":tree", ":stat", ":sha256", ":md5", ":diff",
            ":touch", ":which", ":env", ":watch", ":watch-stop"
        ));
    }

    public void loadPluginsFromDirectory(String directoryPath) {
        File dir = new File(directoryPath);
        if (!dir.exists() || !dir.isDirectory()) {
            AppLogger.error("Plugin directory not found: " + directoryPath);
            return;
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".jar"));
        if (files == null) return;

        List<CommandPlugin> loaded = new ArrayList<>();
        try {
            URL[] urls = Arrays.stream(files).map(f -> {
                try { return f.toURI().toURL(); } catch (Exception e) { return null; }
            }).filter(Objects::nonNull).toArray(URL[]::new);

            URLClassLoader loader = new URLClassLoader(urls, this.getClass().getClassLoader());
            ServiceLoader<CommandPlugin> serviceLoader = ServiceLoader.load(CommandPlugin.class, loader);
            for (CommandPlugin plugin : serviceLoader) {
                loaded.add(plugin);
                AppLogger.info("Loaded plugin: " + plugin.getName());
            }

            plugins.clear();
            for (CommandPlugin plugin : loaded) {
                registerPlugin(plugin);
            }
        } catch (Exception e) {
            AppLogger.error("Failed to load plugins: " + e.getMessage());
        }
    }

    private static boolean isFilePath(String t) {
        if (t == null || t.isBlank()) return false;
        if (t.startsWith("-")) return false;
        if (t.contains("/") || t.contains("\\")) return true;
        return t.matches("[^\\s]+\\.[A-Za-z0-9]{1,8}");
    }

    // An option carries its value ("--seuil=3"), a flag is a bare switch ("-v").
    // The two used to be swapped: "--seuil=3" landed in the flag list and a bare
    // "seuil=3" in the option list.
    private static boolean isOption(String t) {
        return t != null && t.startsWith("-") && t.indexOf('=') > 0;
    }

    private static boolean isFlag(String t) {
        if (t == null || !t.startsWith("-")) return false;
        if (isOption(t)) return false;
        return !t.matches("-?\\d+(\\.\\d+)?"); // a negative number is a value
    }

    private static List<String> extractSnippetBlocks(String input) {
        List<String> blocks = new ArrayList<>();
        Matcher m = SNIPPET_PATTERN.matcher(input);
        while (m.find()) {
            String block = m.group(1);
            if (block != null && !block.isBlank()) {
                blocks.add(block.trim());
            }
        }
        return blocks;
    }

    private static String extractRootPart(String input) {
        return SNIPPET_PATTERN.matcher(input).replaceAll("").trim();
    }

    /**
     * Splits a line at the [@ ... ] block. What precedes it configures the
     * interpreter or the compiler, what follows it belongs to the snippet, so
     * both writings below mean the same thing:
     *   ::py -v [@ a.py -x --seuil=2]
     *   ::py -v [@ a.py] -x --seuil=2
     * Deleting the block instead of cutting at it erased that position, and
     * everything landed on the interpreter.
     * Returns { before, after }.
     */
    private static String[] splitAtSnippet(String input) {
        if (input == null) {
            return new String[] { "", "" };
        }
        Matcher m = SNIPPET_PATTERN.matcher(input);
        if (!m.find()) {
            return new String[] { input.trim(), "" };
        }
        String before = input.substring(0, m.start()).trim();
        String after = SNIPPET_PATTERN.matcher(input.substring(m.end()))
                                      .replaceAll("").trim();
        return new String[] { before, after };
    }

    private static ParsedCommand parseCommandString(String rootPart, List<String> snippetBlocks) {
        return parseCommandString(rootPart, snippetBlocks, "");
    }

    private static ParsedCommand parseCommandString(String rootPart,
                                                    List<String> snippetBlocks,
                                                    String trailingPart) {
        ParsedCommand pc = new ParsedCommand();
        Tokenizer tokenizer = Tokenizer.DEFAULT;

        // 1. Parse the Root/Macro context sequence using the tokenizer
        if (rootPart != null && !rootPart.isBlank()) {
            List<String> rootTokens = tokenizer.tokenize(rootPart.trim());

            if (!rootTokens.isEmpty()) {
                String first = rootTokens.get(0);
                if (first.startsWith("::")) {
                    pc.type = ParsedCommand.RootType.MACRO;
                    pc.languageOrApp = first.substring(2);
                } else if (first.startsWith(":")) {
                    pc.type = ParsedCommand.RootType.COMMAND;
                    pc.languageOrApp = first.substring(1);
                }

                // When a [@ ... ] block is present it carries the script, so a
                // path-looking token out here is an ordinary argument. Claiming it
                // as pc.filepath put it ahead of everything on the command line
                // and the interpreter ran it instead of the snippet.
                final boolean snippetCarriesScript =
                        snippetBlocks != null && !snippetBlocks.isEmpty();

                // Route arguments and classify flags, options, and file paths
                for (int i = 1; i < rootTokens.size(); i++) {
                    String token = rootTokens.get(i);
                    if (isOption(token)) {
                        pc.macroOptions.add(token);
                        pc.macroTokens.add(token);
                    } else if (isFlag(token)) {
                        pc.macroFlags.add(token);
                        pc.macroTokens.add(token);
                    } else if (!snippetCarriesScript && pc.filepath == null
                               && isFilePath(token)) {
                        pc.filepath = token;
                    } else {
                        pc.macroTokens.add(token);
                    }
                }
            }
        }

        // 2. Parse the encapsulated snippet context sequence safely
        if (snippetBlocks != null && !snippetBlocks.isEmpty()) {
            pc.hasSnippet = true;
            for (String snippetBlock : snippetBlocks) {
                if (snippetBlock == null || snippetBlock.isBlank()) {
                    continue;
                }

                // Tokenize the snippet block while preserving quoted strings
                List<String> snipTokens = tokenizer.tokenize(snippetBlock.trim());

                for (String rawToken : snipTokens) {
                    String token = rawToken;

                    // Strip the engine prefix indicator if present
                    if (token.startsWith("@") && token.length() > 1) {
                        token = token.substring(1);
                    } else if (token.equals("@")) {
                        continue;
                    }

                    if (isOption(token)) {
                        pc.snippetOptions.add(token);
                        pc.snippetTokens.add(token);
                    } else if (isFlag(token)) {
                        pc.snippetFlags.add(token);
                        pc.snippetTokens.add(token);
                    } else {
                        pc.snippetTokens.add(token);
                    }
                }
            }

            // 3. Whatever trailed the closing bracket belongs to the snippet too,
            // appended after its own arguments so the order the user typed holds.
            if (trailingPart != null && !trailingPart.isBlank()) {
                for (String token : tokenizer.tokenize(trailingPart.trim())) {
                    if (isOption(token)) {
                        pc.snippetOptions.add(token);
                    } else if (isFlag(token)) {
                        pc.snippetFlags.add(token);
                    }
                    pc.snippetTokens.add(token);
                }
            }
        }

        return pc;
    }

    public void processInput(String input) {
        if (input == null) return;
        if (input.isBlank()) {
            // A blank line is how a block is closed, so inside a mode it has to
            // reach the buffer instead of being dropped as an empty command.
            final String openMode = currentMode.get();
            if (com.sphere.core.exec.CodeBuffer.collects(openMode)
                && !com.sphere.core.exec.CodeBuffer.isEmpty(openMode)) {
                collectOrRun(openMode, "");
            }
            return;
        }

        String command = history.expandMacros(input.trim());
        // Passing null here skipped SnippetResolver's third lookup, so a snippet
        // living under WorkSpace/<project>/snippets/ never resolved to a path.
        command = com.sphere.core.snippets.TagInterpreter.resolve(command, ctx.getActiveProject());
        history.add(command);

        String firstToken = command.split("\\s+")[0];
        if (knownCommands.contains(firstToken)) {
            knownCommands.add(firstToken);
        }

        if (handleCd(command) || handlePwd(command) || handlePushd(command)
                || handlePopd(command) || handleDirs(command)) {
            return;
        }

        if (currentMode.get() != null && !command.startsWith(":")) {
            String base = command.trim();
            if (!isInternalSystemCommand(base)) {
                if (com.sphere.core.exec.CodeBuffer.collects(currentMode.get())) {
                    // Enter means run. The text typed is kept rather than the trimmed
                    // one: indentation is part of the code.
                    collectOrRun(currentMode.get(), input.stripTrailing());
                    return;
                }
                Backend backend = backends.get(currentMode.get());
                if (backend != null) {
                    backend.execute(command);
                    return;
                }
                // A mode with neither a buffer nor a backend has nowhere to send
                // the line. Falling through would hand it to the shell, which
                // runs it as a program instead of reporting the mode.
                AppLogger.error("No runner for mode '" + currentMode.get()
                    + "'; the line was not run.");
                return;
            }
        }

        if (command.startsWith("::") && command.contains("[@")) {
            executeHybridAsync(command);
            return;
        }

        if (command.equalsIgnoreCase(":py update pythonpath")) {
            AppLogger.info("Re-scanning host platform target installation layouts...");
            com.sphere.utils.PythonEnvs.updatePythonPathCacheFile(new com.sphere.utils.SettingsManager());
            return;
        }

        for (CommandPlugin plugin : plugins) {
            if (isInternalSystemCommand(command)) break;
            if (plugin.supports(command)) {
                plugin.execute(command);
                return;
            }
        }

        if (command.startsWith(":") || command.startsWith("::")) {
            ctx.currentMode = currentMode.get();
            registry.dispatch(command, ctx);
            currentMode.set(ctx.currentMode);
            if (modeUpdater != null) modeUpdater.accept(currentMode.get());
            return;
        }

        executeHybridAsync(command);
    }

    private boolean isInternalSystemCommand(String cmd) {
        String clean = cmd.trim();
        while (clean.startsWith(":")) {
            clean = clean.substring(1).trim();
        }
        return clean.equals("pwd")
            || clean.equals("popd")
            || clean.startsWith("popd ")
            || clean.equals("cd")
            || clean.startsWith("cd ")
            || clean.equals("pushd")
            || clean.startsWith("pushd ")
            || clean.equals("dirs")
            || clean.startsWith("dirs ");
    }

    /**
     * Rejects snippet invocations that would silently run the wrong thing.
     * Returns false once the reason has been reported.
     */
    private static boolean validateSnippetInvocation(ParsedCommand pc,
                                                     List<String> snippetBlocks) {
        if (pc == null || !pc.hasSnippet) {
            return true;
        }

        // Every block was flattened into one argument list, so a second snippet
        // silently became an argument of the first.
        if (snippetBlocks.size() > 1) {
            AppLogger.error("One snippet block per command: " + snippetBlocks.size()
                            + " were given.");
            return false;
        }

        if (pc.snippetTokens.isEmpty()) {
            AppLogger.error("Empty snippet block.");
            return false;
        }

        // TagInterpreter returns the name unchanged when it resolves nothing, and
        // the interpreter then answered with its own stack trace instead of us.
        String script = pc.snippetTokens.get(0);
        File scriptFile = new File(script);
        if (!scriptFile.isFile()) {
            AppLogger.error("Snippet not found: " + script);
            return false;
        }

        // The interpreter takes its first positional as the script, so a path
        // sitting outside the brackets shadows the snippet. It is only reported,
        // not refused: a bare word can legitimately be the value of the flag in
        // front of it, and each interpreter spells its own options differently.
        for (int i = 0; i < pc.macroTokens.size(); i++) {
            String token = pc.macroTokens.get(i);
            boolean followsFlag = i > 0 && pc.macroTokens.get(i - 1).startsWith("-");
            if (!token.startsWith("-") && !followsFlag && isFilePath(token)) {
                AppLogger.warn("'" + token + "' sits outside the brackets and will be"
                               + " run instead of " + scriptFile.getName()
                               + "; move it inside [@ ... ] to pass it to the snippet.");
            }
        }

        return true;
    }

    /**
     * What Enter does inside a permanent mode.
     *
     * A line that stands on its own reaches the interpreter at once, so its
     * variables appear straight away. One that opens a block is held until the
     * block closes, because half a loop is only a syntax error.
     */
    private void collectOrRun(String mode, String line) {
        final int before = com.sphere.core.exec.CodeBuffer.size(mode);
        com.sphere.core.exec.CodeBuffer.add(mode, line);

        if (!com.sphere.core.exec.LineGrammar.isComplete(mode,
                com.sphere.core.exec.CodeBuffer.lines(mode))) {
            markPending(true);
            return;
        }
        markPending(false);

        if ("fortran".equals(mode)) {
            // Fortran has no interpreter, so the program grows instead of running.
            // It is compiled at every step and a statement it refuses is taken back
            // out, which is what keeps the program buildable while it is written.
            checkFortran(before);
            return;
        }

        final String code = com.sphere.core.exec.CodeBuffer.text(mode);
        com.sphere.core.exec.CodeBuffer.clear(mode);
        runModeBlock(mode, code, true);
    }

    /** Drops the block being written, leaving what already ran untouched. */
    public void abandonPendingBlock() {
        final String mode = currentMode.get();
        if (com.sphere.core.exec.CodeBuffer.collects(mode)
            && !"fortran".equals(mode)
            && !com.sphere.core.exec.CodeBuffer.isEmpty(mode)) {
            com.sphere.core.exec.CodeBuffer.clear(mode);
            markPending(false);
        }
    }

    private static void markPending(boolean pending) {
        try {
            com.sphere.Sphere.assignPendingMarker(pending);
        } catch (Throwable noWindow) {
            // The console can run without the window that shows the marker.
        }
    }

    /** Compiles the Fortran program as it stands, undoing the last statement if it fails. */
    private void checkFortran(int before) {
        final String code = com.sphere.core.exec.CodeBuffer.text("fortran");
        final com.sphere.utils.SettingsManager settings = new com.sphere.utils.SettingsManager();
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return com.sphere.core.exec.ExecRunner.checkFortran(code, settings);
            }

            @Override
            protected void done() {
                try {
                    final String refused = get();
                    if (refused != null) {
                        final String statement = String.join("\n",
                            com.sphere.core.exec.CodeBuffer.lines("fortran")
                                .subList(before,
                                    com.sphere.core.exec.CodeBuffer.size("fortran")));
                        com.sphere.core.exec.CodeBuffer.delete("fortran", before + 1,
                            com.sphere.core.exec.CodeBuffer.size("fortran"));
                        AppLogger.error(refused + "\nRefused, and taken back out: "
                                        + statement);
                    }
                } catch (Exception lost) {
                    AppLogger.error("The Fortran check did not finish: " + lost.getMessage());
                }
            }
        }.execute();
    }

    /** Sends a finished block to the engine that belongs to its mode. */
    public void runModeBlock(String mode, String code, boolean run) {
        final com.sphere.utils.SettingsManager settings = new com.sphere.utils.SettingsManager();
        final Path here = currentDirectory;
        final Object rootObject = getRootBackend();
        final RootBackend root =
            rootObject instanceof RootBackend b ? b : null;
        final Object cppObject = backends.get("cpp");
        final CppBackend cpp = cppObject instanceof CppBackend b ? b : null;

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                switch (mode) {
                    case "py" -> com.sphere.core.exec.ExecRunner.python(code, settings);
                    case "julia" -> com.sphere.core.exec.ExecRunner.julia(code, settings);
                    case "fortran" ->
                        com.sphere.core.exec.ExecRunner.fortran(code, here, settings);
                    case "cpp" -> {
                        if (root == null) {
                            com.sphere.core.exec.ExecRunner.build(code, cpp);
                        } else {
                            com.sphere.core.exec.ExecRunner.cling(code, root);
                        }
                    }
                    default -> AppLogger.error("No runner for mode '" + mode + "'.");
                }
                return null;
            }
        }.execute();
    }

    /**
     * Compiles and runs a C++ snippet: what sat before the bracket configures the
     * compiler, what sat inside or after it reaches the produced binary.
     */
    private void runCppSnippet(ParsedCommand pc) {
        Backend backend = backends.get("cpp");
        if (!(backend instanceof com.sphere.core.cpp.CppBackend cppBackend)) {
            AppLogger.error("The C++ backend is not available.");
            return;
        }

        final String source = pc.snippetTokens.get(0);
        final List<String> compileFlags = new ArrayList<>(pc.macroTokens);
        final List<String> runtimeArgs =
            new ArrayList<>(pc.snippetTokens.subList(1, pc.snippetTokens.size()));

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                cppBackend.executeSource(source, compileFlags, runtimeArgs, false, null);
                // The run is over, so anything it wrote down is final.
                try {
                    com.sphere.components.variables.VariablesPanel.instance().reread();
                } catch (RuntimeException unreadable) {
                    AppLogger.error("Could not read the variables of this run: "
                                    + unreadable);
                }
                return null;
            }
        }.execute();
    }

    public void executeHybridAsync(String rawInput) {
        List<String> snippetBlocks = extractSnippetBlocks(rawInput);
        String[] halves = splitAtSnippet(rawInput);
        ParsedCommand pc = parseCommandString(halves[0], snippetBlocks, halves[1]);

        if (!validateSnippetInvocation(pc, snippetBlocks)) {
            return;
        }

        // A compiled language has no single command line carrying both scopes:
        // the flags configure the compiler and the arguments reach the binary it
        // produces, in two separate processes. Its backend owns that pipeline.
        if (pc.hasSnippet && "cpp".equalsIgnoreCase(pc.languageOrApp)) {
            runCppSnippet(pc);
            return;
        }

        // Julia keeps an interpreter of its own, so the file runs inside it and
        // what it defines is still there afterwards.
        if (pc.hasSnippet && isJuliaName(pc.languageOrApp)) {
            runJuliaSnippet(pc);
            return;
        }

        if (pc.hasSnippet && isFortranName(pc.languageOrApp)) {
            runFortranSnippet(pc);
            return;
        }

        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() throws Exception {
                List<String> cmd = new ArrayList<>();
                boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");

                // Ensure both explicit commands (:) and macros (::) route through environment initialization
                if (pc.languageOrApp != null && ("py".equalsIgnoreCase(pc.languageOrApp) || "js".equalsIgnoreCase(pc.languageOrApp))) {
                    if ("py".equalsIgnoreCase(pc.languageOrApp)) {
                        File venvPython = isWin ? new File("venv/Scripts/python.exe")
                                                : new File("venv/bin/python");
                        if (venvPython.exists()) cmd.add(venvPython.getAbsolutePath());
                        else cmd.add(isWin ? "python" : "python3");
                    } else if ("js".equalsIgnoreCase(pc.languageOrApp)) {
                        cmd.add("node");
                    }

                    // What sat before the bracket configures the interpreter; the
                    // script and what sat inside reach the program. Keeping the two
                    // apart is what lets "::py -O [@ run.py 3]" mean what it reads.
                    final List<String> interpreterOptions = new ArrayList<>(pc.macroTokens);
                    final List<String> program = new ArrayList<>();
                    if (pc.filepath != null) {
                        program.add(pc.filepath);
                    }
                    if (pc.hasSnippet) {
                        program.addAll(pc.snippetTokens);
                    }

                    cmd.addAll(interpreterOptions);

                    // A Python script runs inside Sphere's wrapper, which keeps its
                    // namespace and writes it down once the script is over. The
                    // script itself is untouched: same __main__, same arguments,
                    // same exit code. The wrapper goes after the interpreter's own
                    // options and before the script, which is where -c belongs.
                    if ("py".equalsIgnoreCase(pc.languageOrApp)
                        && com.sphere.components.variables.PythonProbe.isWrapping()
                        && !program.isEmpty()
                        && isScriptHere(program.get(0))
                        && !interpreterOptions.contains("-c")
                        && !interpreterOptions.contains("-m")) {
                        cmd.add("-c");
                        cmd.add(com.sphere.components.variables.PythonProbe.WRAPPER);
                    }
                    cmd.addAll(program);
                } else if (pc.hasSnippet) {
                    // The raw line used to be handed to the shell here, prefix and
                    // brackets included, so ::jul and any other language answered
                    // "command not found" rather than saying what was missing.
                    if (pc.languageOrApp == null || pc.languageOrApp.isBlank()) {
                        // A prefix on its own leaves no language to run, and the
                        // word after it becomes an argument instead of a name.
                        AppLogger.error(detachedPrefixHint(pc));
                    } else {
                        AppLogger.error("No runner for '" + pc.languageOrApp
                                        + "' snippets; supported: py, js, cpp, julia, fortran.");
                    }
                    return null;
                } else {
                    List<String> plainPython = plainPythonRun(rawInput);
                    if (plainPython != null) {
                        cmd.addAll(plainPython);
                    } else {
                        // Fallback directly to native OS shell processing layer
                        cmd.addAll(isWin ? List.of("cmd.exe", "/c", rawInput)
                                         : List.of("/bin/bash", "-c", rawInput));
                    }
                }

                if (cmd.isEmpty() || cmd.get(0).isBlank()) return null;

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(currentDirectory.toFile());

                Map<String, String> env = pb.environment();
                Map<String, String> currentSnapshot = new HashMap<>(env);

                env.keySet().removeIf(key -> !PRESERVED_ENV_KEYS.stream().anyMatch(k -> k.equalsIgnoreCase(key)));

                currentSnapshot.forEach((key, value) -> {
                    if (PRESERVED_ENV_KEYS.stream().anyMatch(k -> k.equalsIgnoreCase(key))) {
                        env.put(key, value);
                    }
                });

                if ("py".equalsIgnoreCase(pc.languageOrApp)) {
                    String existingPythonPath = currentSnapshot.get("PYTHONPATH");
                    StringBuilder customPythonPath = new StringBuilder();

                    if (existingPythonPath != null && !existingPythonPath.isBlank()) {
                        customPythonPath.append(existingPythonPath);
                    }

                    String preCachedPaths = PythonEnvs.getCachedPythonPath();
                    if (preCachedPaths != null && !preCachedPaths.isBlank()) {
                        if (customPythonPath.length() > 0) customPythonPath.append(File.pathSeparator);
                        customPythonPath.append(preCachedPaths);
                    } else {
                        try {
                            String interpreter = cmd.get(0);
                            if (interpreter.toLowerCase().contains("python")) {
                                ProcessBuilder pathPb = new ProcessBuilder(interpreter, "-c",
                                    "import sys; print(','.join(sys.path))");
                                pathPb.environment().putAll(System.getenv());
                                // The interpreter's own startup can write a great
                                // deal to its error stream -- a noisy sitecustomize,
                                // a conda deprecation notice, PYTHONWARNINGS set to
                                // all. Left unread it fills the pipe and the
                                // interpreter stops there, and this runs inside the
                                // worker that executes console commands, so the
                                // command would never finish and the catch below
                                // would say nothing about why.
                                pathPb.redirectErrorStream(true);
                                Process pathProc = pathPb.start();
                                String pathsLine = null;
                                try (BufferedReader r = new BufferedReader(
                                        new InputStreamReader(pathProc.getInputStream(),
                                                java.nio.charset.StandardCharsets.UTF_8))) {
                                    String line;
                                    while ((line = r.readLine()) != null) {
                                        // The paths are the line holding separators;
                                        // anything before it is the interpreter talking.
                                        if (line.contains(",") || line.contains("site-packages")) {
                                            pathsLine = line;
                                        }
                                    }
                                }
                                if (pathsLine != null && !pathsLine.isBlank()) {
                                    for (String p : pathsLine.split(",")) {
                                        if (p.contains("site-packages") || p.contains("Lib")) {
                                            if (customPythonPath.length() > 0) {
                                                customPythonPath.append(File.pathSeparator);
                                            }
                                            customPythonPath.append(p.trim());
                                        }
                                    }
                                }
                                if (!pathProc.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                                    pathProc.destroyForcibly();
                                }
                            }
                        } catch (Exception ignored) {
                            String userProf = currentSnapshot.get("USERPROFILE");
                            if (userProf != null) {
                                String commonRoamingPath = userProf + File.separator + "AppData"
                                    + File.separator + "Roaming" + File.separator + "Python";
                                File roamingFolder = new File(commonRoamingPath);
                                if (roamingFolder.exists() && roamingFolder.isDirectory()) {
                                    File[] versions = roamingFolder.listFiles();
                                    if (versions != null) {
                                        for (File v : versions) {
                                            File sitePackages = new File(v, "site-packages");
                                            if (sitePackages.exists()) {
                                                if (customPythonPath.length() > 0) {
                                                    customPythonPath.append(File.pathSeparator);
                                                }
                                                customPythonPath.append(sitePackages.getAbsolutePath());
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (customPythonPath.length() > 0) {
                        env.put("PYTHONPATH", customPythonPath.toString());
                    }

                }

                // Every program launched from here is told where to leave its
                // variables, whatever language it is: a shell line is as likely
                // to be a Fortran binary as a Python script.
                try {
                    final String variables =
                        com.sphere.components.variables.PythonProbe.folder();
                    if (variables != null) {
                        env.put(com.sphere.components.variables.PythonProbe
                                    .FOLDER_VARIABLE, variables);
                    }
                } catch (RuntimeException unreachable) {
                    AppLogger.error("Variables folder unreachable: " + unreachable);
                }

                pb.redirectErrorStream(true);

                final long started = System.nanoTime();
                Process process = pb.start();
                com.sphere.core.telemetry.ProcessMemory.Watcher memory =
                    com.sphere.core.telemetry.ProcessMemory.watch(process);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        publish(line);
                    }
                }

                final int status = process.waitFor();
                memory.close();
                // What a script cost, kept beside the compilations so that both can
                // be read on the same page.
                com.sphere.core.telemetry.RunLog.add(
                    com.sphere.core.telemetry.RunRecord.run(
                        pc.languageOrApp == null || pc.languageOrApp.isBlank()
                            ? "shell" : pc.languageOrApp.toLowerCase(java.util.Locale.ROOT),
                        com.sphere.core.telemetry.CommandFacts.sourceOf(cmd),
                        com.sphere.core.telemetry.CommandFacts.toolOf(cmd),
                        com.sphere.core.telemetry.CommandFacts.flagsOf(cmd),
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - started),
                        status, memory.peakKilobytes(), false));

                try {
                    com.sphere.components.variables.VariablesPanel.instance().reread();
                } catch (RuntimeException unreadable) {
                    AppLogger.error("Could not read the variables of this run: "
                                    + unreadable);
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                // The output of a program Sphere launched, not text Sphere wrote:
                // stream is what carries its own errors and warnings into color.
                chunks.forEach(AppLogger::stream);
            }
        }.execute();
    }

    /**
     * A line that is nothing but an interpreter and a script, and its arguments.
     *
     * Such a line is handed to the shell, which leaves Sphere no way in. Building
     * the command here instead lets the script run inside the wrapper that
     * collects its variables. Anything the shell would have to interpret, a pipe,
     * a redirection or a variable, is left to the shell untouched.
     *
     * Returns null when the line is anything else.
     */
    private static List<String> plainPythonRun(String rawInput) {
        if (rawInput == null || !com.sphere.components.variables.PythonProbe.isWrapping()) {
            return null;
        }
        for (char c : new char[] {'|', '&', ';', '<', '>', '$', '`', '*', '?', '\n'}) {
            if (rawInput.indexOf(c) >= 0) {
                return null;
            }
        }
        List<String> tokens = Tokenizer.DEFAULT.tokenize(rawInput.trim());
        if (tokens.size() < 2 || !isPythonInterpreter(tokens.get(0))) {
            return null;
        }
        int script = -1;
        for (int i = 1; i < tokens.size(); i++) {
            final String token = tokens.get(i);
            if (token.startsWith("-")) {
                return null;        // an option changes what the interpreter does
            }
            if (token.toLowerCase().endsWith(".py")) {
                script = i;
                break;
            }
        }
        if (script < 0 || !new File(tokens.get(script)).isFile()) {
            return null;
        }

        List<String> command = new ArrayList<>();
        command.add(tokens.get(0));
        command.add("-c");
        command.add(com.sphere.components.variables.PythonProbe.WRAPPER);
        command.addAll(tokens.subList(script, tokens.size()));
        return command;
    }

    /**
     * What to say when ":: name" was typed instead of "::name". The prefix and
     * the language are one token, so a space between them leaves no language to
     * run and turns the name into an ordinary argument.
     */
    private static String detachedPrefixHint(ParsedCommand pc) {
        final String prefix =
            pc.type == ParsedCommand.RootType.COMMAND ? ":" : "::";
        final String language = pc.macroTokens.isEmpty() ? "py" : pc.macroTokens.get(0);
        return "No language after '" + prefix + "': write \"" + prefix + language
             + "\" without a space, not \"" + prefix + " " + language + "\".";
    }

    /** The names ::jul and ::julia are spelled with. */
    private static boolean isJuliaName(String language) {
        return "jul".equalsIgnoreCase(language) || "julia".equalsIgnoreCase(language);
    }

    /** The names ::fort, ::fortran and ::f90 are spelled with. */
    private static boolean isFortranName(String language) {
        return "fort".equalsIgnoreCase(language) || "fortran".equalsIgnoreCase(language)
            || "f90".equalsIgnoreCase(language) || "fot".equalsIgnoreCase(language);
    }

    /**
     * Runs a Julia snippet in the session rather than in a process of its own.
     *
     * A process would take its variables with it when it ended. The session keeps
     * them, which is the whole reason it exists.
     */
    private void runJuliaSnippet(ParsedCommand pc) {
        final File script = new File(pc.snippetTokens.get(0));
        final List<String> runtimeArgs =
            new ArrayList<>(pc.snippetTokens.subList(1, pc.snippetTokens.size()));

        if (!pc.macroTokens.isEmpty()) {
            // The interpreter is already up and holding the session's variables;
            // restarting it to honor an option would throw them away.
            AppLogger.warn("The Julia session is already running, so "
                           + String.join(" ", pc.macroTokens)
                           + " cannot configure it. Put arguments inside the brackets.");
        }

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                com.sphere.core.julia.JuliaSession session =
                    com.sphere.core.julia.JuliaSession.instance(
                        new com.sphere.utils.SettingsManager());
                try {
                    session.start();
                } catch (IOException unavailable) {
                    AppLogger.error(unavailable.getMessage());
                    return null;
                }
                session.runFile(script, runtimeArgs);
                return null;
            }
        }.execute();
    }

    /**
     * Compiles and runs a Fortran snippet: what sat before the bracket configures
     * the compiler, what sat inside or after it reaches the produced binary.
     */
    private void runFortranSnippet(ParsedCommand pc) {
        final File source = new File(pc.snippetTokens.get(0));
        final List<String> compileFlags = new ArrayList<>(pc.macroTokens);
        final List<String> runtimeArgs =
            new ArrayList<>(pc.snippetTokens.subList(1, pc.snippetTokens.size()));
        final Path here = currentDirectory;

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                com.sphere.core.fortran.FortranRunner.run(source, compileFlags,
                    runtimeArgs, here, new com.sphere.utils.SettingsManager());
                return null;
            }
        }.execute();
    }

    /** A .py file that is really there, looked for where the run will happen. */
    private boolean isScriptHere(String token) {
        if (token == null || !token.toLowerCase().endsWith(".py")) {
            return false;
        }
        File direct = new File(token);
        return direct.isFile()
            || new File(currentDirectory.toFile(), token).isFile();
    }

    /** python, python3, python3.12, and the same with a path or a .exe. */
    private static boolean isPythonInterpreter(String token) {
        String name = new File(token).getName().toLowerCase();
        if (name.endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.equals("python") || name.matches("python\\d(\\.\\d+)?");
    }

    private boolean handleCd(String cmd) {
        String clean = cmd.trim();
        if (clean.startsWith("::")) return false;
        if (clean.startsWith(":")) clean = clean.substring(1).trim();

        if (!clean.equals("cd") && !clean.startsWith("cd ")) return false;

        String path = clean.length() == 2 ? "" : clean.substring(3).trim();
        if (path.isEmpty()) {
            Path target = Paths.get(System.getProperty("user.home"));
            try {
                target = target.toRealPath();
                if (Files.isDirectory(target)) {
                    previousDirectory = currentDirectory;
                    currentDirectory = target;
                    setWorkingDirectory();
                    updateStatus();
                } else {
                    AppLogger.error("Invalid directory.");
                }
            } catch (IOException e) {
                AppLogger.error("Access error: " + e.getMessage());
            }
            return true;
        }

        if (path.equals("-")) {
            if (previousDirectory == null) {
                AppLogger.error("No previous directory.");
            } else {
                Path temp = currentDirectory;
                currentDirectory = previousDirectory;
                setWorkingDirectory();
                previousDirectory = temp;
                updateStatus();
            }
            return true;
        }

        Path target = resolveUserPath(path);

        try {
            target = target.toRealPath();
            if (Files.isDirectory(target)) {
                previousDirectory = currentDirectory;
                currentDirectory = target;
                setWorkingDirectory();
                updateStatus();
            } else {
                AppLogger.error("Invalid directory.");
            }
        } catch (IOException e) {
            AppLogger.error("Access error: " + e.getMessage());
        }
        return true;
    }

    private boolean handlePwd(String cmd) {
        String clean = cmd.trim();
        if (clean.startsWith("::")) return false;
        if (clean.startsWith(":")) clean = clean.substring(1).trim();

        if (!clean.equalsIgnoreCase("pwd")) return false;
        AppLogger.raw(currentDirectory.toString());
        updateStatus();
        return true;
    }

    private boolean handlePushd(String cmd) {
        String clean = cmd.trim();
        if (clean.startsWith("::")) return false;
        if (clean.startsWith(":")) clean = clean.substring(1).trim();

        // Exact match only: "pushdfoo" used to be accepted as "pushd foo"
        if (!clean.equals("pushd") && !clean.startsWith("pushd ")) return false;

        String arg = clean.length() > 5 ? clean.substring(5).trim() : "";

        // Bare pushd swaps the top of the stack with the current directory
        if (arg.isEmpty()) {
            if (dirStack.isEmpty()) {
                AppLogger.error("Directory stack empty. Use  pushd <directory>");
                return true;
            }
            Path top = dirStack.pop();
            dirStack.push(currentDirectory);
            previousDirectory = currentDirectory;
            currentDirectory = top;
            setWorkingDirectory();
            updateStatus();
            printStack();
            return true;
        }

        Path target = resolveUserPath(arg);
        if (Files.isDirectory(target)) {
            dirStack.push(currentDirectory);
            previousDirectory = currentDirectory;
            currentDirectory = target;
            setWorkingDirectory();
            updateStatus();
            printStack();
        } else {
            AppLogger.error("Directory not found: " + target);
        }
        return true;
    }

    private boolean handlePopd(String cmd) {
        String clean = cmd.trim();
        if (clean.startsWith("::")) return false;
        if (clean.startsWith(":")) clean = clean.substring(1).trim();

        if (!clean.equalsIgnoreCase("popd") && !clean.toLowerCase(Locale.ROOT).startsWith("popd ")) {
            return false;
        }

        String arg = clean.length() > 4 ? clean.substring(4).trim() : "";

        if (dirStack.isEmpty()) {
            AppLogger.error("Directory stack empty.");
            return true;
        }

        // popd +N drops the Nth entry without moving, exactly like a shell.
        // Anything else is answered here rather than leaking to the host shell,
        // which would report on a stack of its own.
        if (!arg.isEmpty()) {
            int index;
            try {
                index = Integer.parseInt(arg.startsWith("+") ? arg.substring(1) : arg);
            } catch (NumberFormatException nfe) {
                AppLogger.error("Usage: popd [+N]");
                return true;
            }
            if (index < 0 || index > dirStack.size()) {
                AppLogger.error("popd: +" + index + ": directory stack index out of range");
                return true;
            }
            if (index > 0) {
                java.util.List<Path> entries = new ArrayList<>(dirStack);
                entries.remove(index - 1);
                dirStack.clear();
                for (int i = entries.size() - 1; i >= 0; i--) dirStack.push(entries.get(i));
                printStack();
                return true;
            }
        }

        previousDirectory = currentDirectory;
        currentDirectory = dirStack.pop();
        setWorkingDirectory();
        updateStatus();
        printStack();
        return true;
    }

    /**
     * dirs, the third member of the family. Without it the stack can be pushed
     * and popped but never seen, and the name falls through to the host shell,
     * which answers about its own stack -- or, under cmd.exe, not at all.
     */
    private boolean handleDirs(String cmd) {
        String clean = cmd.trim();
        if (clean.startsWith("::")) return false;
        if (clean.startsWith(":")) clean = clean.substring(1).trim();

        if (!clean.equals("dirs") && !clean.startsWith("dirs ")) return false;

        String arg = clean.length() > 4 ? clean.substring(4).trim() : "";
        switch (arg) {
            case "":
                printStack();
                return true;
            case "-c":
                dirStack.clear();
                printStack();
                return true;
            case "-v": {
                int index = 0;
                AppLogger.raw(String.format("%2d  %s", index++, abbreviate(currentDirectory)));
                for (Path p : dirStack) {
                    AppLogger.raw(String.format("%2d  %s", index++, abbreviate(p)));
                }
                return true;
            }
            default:
                AppLogger.error("Usage: dirs [-v | -c]");
                return true;
        }
    }

    /** The stack on one line, current directory first, as a shell prints it. */
    private void printStack() {
        StringBuilder line = new StringBuilder(abbreviate(currentDirectory));
        for (Path p : dirStack) {
            line.append("  ").append(abbreviate(p));
        }
        AppLogger.raw(line.toString());
    }

    /** Shortens the user home to ~, so the stack stays readable on one line. */
    private static String abbreviate(Path path) {
        String home = System.getProperty("user.home");
        String text = path.toString();
        if (home != null && !home.isBlank() && text.startsWith(home)) {
            return "~" + text.substring(home.length());
        }
        return text;
    }

    /**
     * Resolves a directory argument the way the fs plugins already do: a bare ~,
     * ~/sub and ~\sub on every platform, then relative against the current
     * directory, then normalized so ".." never reaches the prompt.
     */
    private Path resolveUserPath(String path) {
        String cleaned = path.trim();
        if (cleaned.length() > 1
                && ((cleaned.charAt(0) == '"' && cleaned.endsWith("\""))
                 || (cleaned.charAt(0) == '\'' && cleaned.endsWith("'")))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }

        Path target;
        if (cleaned.equals("~")) {
            target = Paths.get(System.getProperty("user.home"));
        } else if (cleaned.startsWith("~/") || cleaned.startsWith("~\\")) {
            target = Paths.get(System.getProperty("user.home")).resolve(cleaned.substring(2));
        } else {
            Path p = Paths.get(cleaned);
            target = p.isAbsolute() ? p : currentDirectory.resolve(p);
        }
        return target.toAbsolutePath().normalize();
    }

    private void updateStatus() {
        if (statusBarUpdater != null) statusBarUpdater.accept(currentDirectory.toString());
    }

    public String autoComplete(String prefix) {
        if (prefix == null || prefix.isEmpty()) return "";
        if (prefix.contains("/") || prefix.contains("\\") || prefix.startsWith("~") || prefix.startsWith(".")) {
            return autoCompletePath(prefix);
        }
        return knownCommands.stream()
            .filter(cmd -> cmd.startsWith(prefix))
            .findFirst()
            .orElse("");
    }

    private String autoCompletePath(String prefix) {
        if (prefix.startsWith("~")) {
            String home = System.getProperty("user.home");
            if (prefix.equals("~")) {
                prefix = home;
            } else if (prefix.startsWith("~/")) {
                prefix = home + prefix.substring(1);
            }
        }
        Path typed = Paths.get(prefix);
        Path base = typed.isAbsolute()
            ? typed.getParent()
            : currentDirectory.resolve(typed).getParent();
        if (base == null || !Files.exists(base)) return prefix;

        String lastPart = typed.getFileName() != null ? typed.getFileName().toString() : "";
        File[] matches = base.toFile().listFiles(f -> f.getName().startsWith(lastPart));
        return (matches != null && matches.length > 0)
            ? base.resolve(matches[0].getName()).toString()
            : prefix;
    }

    public void registerPlugin(CommandPlugin plugin) {
        plugins.add(plugin);
        if (plugin.getName() != null && !plugin.getName().isBlank()) {
            knownCommands.add(":" + plugin.getName());
        }
    }

    // --- REINTEGRATION OF PREVIOUSLY OMITTED BOILERPLATE METHODS ---

    public String getMode() {
        return this.currentMode.get();
    }

    public void setMode(String mode) {
        this.currentMode.set(mode);
        if (this.modeUpdater != null) {
            this.modeUpdater.accept(mode);
        }
    }

    public CommandRegistry getRegistry() {
        return this.registry;
    }

    public CommandContext getCommandContext() {
        return this.ctx;
    }

    public Path getCurrentDirectory() { return this.currentDirectory; }
    public Object getCppBackend() { return this.backends.get("cpp"); }
    public com.sphere.core.rootbackend.RootBackend getRootBackend() {
        return this.rootDispatcher != null ? this.rootDispatcher.getActiveBackend(this.ctx) : null;
    }
    public List<CommandPlugin> getPlugins() { return Collections.unmodifiableList(plugins); }
    public HistoryManager getHistory() { return history; }
    public void setModeUpdater(Consumer<String> updater) { this.modeUpdater = updater; }
    public void setStatusBarUpdater(Consumer<String> updater) { this.statusBarUpdater = updater; }
}
