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
    private final Deque<Path> dirStack = new ArrayDeque<>();
    private Path previousDirectory = null;

    private static final Set<String> PRESERVED_ENV_KEYS = Set.of(
        "PATH", "HOME", "LANG", "LC_ALL", "SHELL", "USER", "LOGNAME",
        "SystemRoot", "windir", "USERPROFILE", "APPDATA", "LOCALAPPDATA",
        "PROGRAMFILES", "PROGRAMFILES(X86)", "COMMONPROGRAMFILES",
        "SYSTEMDRIVE", "ALLUSERSPROFILE", "COMPUTERNAME", "PUBLIC"
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

    public CommandRouter() {
        // Register default file system command plugins
        this.registerPlugin(new LsPlugin(this));
        this.registerPlugin(new CatPlugin(this));
        this.registerPlugin(new MkdirPlugin(this));
        this.registerPlugin(new SymlinkPlugin(this));

        // Register default execution backends
        backends.put("py", new PythonBackend());
        backends.put("cpp", new CppBackend());

        // Initialize application settings manager and ROOT backend
        com.sphere.utils.SettingsManager settingsManager = new com.sphere.utils.SettingsManager();
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
            "cd", "pwd", "pushd", "popd",
            ":cd", ":pwd", ":pushd", ":popd",
            ":cpp", ":root", "::root"
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

    private static boolean isFlag(String t) {
        return t.startsWith("--") || (t.startsWith("-") && !t.matches("-?\\d+(\\.\\d+)?"));
    }

    private static boolean isOption(String t) {
        return t.contains("=") && !t.startsWith("--");
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

    private static ParsedCommand parseCommandString(String rootPart, List<String> snippetBlocks) {
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

                // Route arguments and classify flags, options, and file paths
                for (int i = 1; i < rootTokens.size(); i++) {
                    String token = rootTokens.get(i);
                    if (isFlag(token)) {
                        pc.macroFlags.add(token);
                        pc.macroTokens.add(token);
                    } else if (isOption(token)) {
                        pc.macroOptions.add(token);
                        pc.macroTokens.add(token);
                    } else if (pc.filepath == null && isFilePath(token)) {
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

                    if (isFlag(token)) {
                        pc.snippetFlags.add(token);
                        pc.snippetTokens.add(token);
                    } else if (isOption(token)) {
                        pc.snippetOptions.add(token);
                        pc.snippetTokens.add(token);
                    } else {
                        pc.snippetTokens.add(token);
                    }
                }
            }
        }

        return pc;
    }

    public void processInput(String input) {
        if (input == null || input.isBlank()) return;

        String command = history.expandMacros(input.trim());
        command = com.sphere.core.snippets.TagInterpreter.resolve(command, null);
        history.add(command);

        String firstToken = command.split("\\s+")[0];
        if (knownCommands.contains(firstToken)) {
            knownCommands.add(firstToken);
        }

        if (handleCd(command) || handlePwd(command) || handlePushd(command) || handlePopd(command)) {
            return;
        }

        if (currentMode.get() != null && !command.startsWith(":")) {
            String base = command.trim();
            if (!isInternalSystemCommand(base)) {
                Backend backend = backends.get(currentMode.get());
                if (backend != null) {
                    backend.execute(command);
                    return;
                }
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
            || clean.equals("cd")
            || clean.startsWith("cd ")
            || clean.equals("pushd")
            || clean.startsWith("pushd ");
    }

    public void executeHybridAsync(String rawInput) {
        List<String> snippetBlocks = extractSnippetBlocks(rawInput);
        String rootPart = extractRootPart(rawInput);
        ParsedCommand pc = parseCommandString(rootPart, snippetBlocks);

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

                    // Append isolated components preserving structural runtime layouts
                    if (pc.filepath != null) {
                        cmd.add(pc.filepath);
                    }
                    cmd.addAll(pc.macroTokens);

                    if (pc.hasSnippet) {
                        cmd.addAll(pc.snippetTokens);
                    }
                } else {
                    // Fallback directly to native OS shell processing layer
                    cmd.addAll(isWin ? List.of("cmd.exe", "/c", rawInput)
                                     : List.of("/bin/bash", "-c", rawInput));
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
                                Process pathProc = pathPb.start();
                                try (BufferedReader r = new BufferedReader(
                                        new InputStreamReader(pathProc.getInputStream()))) {
                                    String pathsLine = r.readLine();
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
                                }
                                pathProc.waitFor();
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

                pb.redirectErrorStream(true);

                Process process = pb.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        publish(line);
                    }
                }

                process.waitFor();
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                chunks.forEach(AppLogger::raw);
            }
        }.execute();
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
                previousDirectory = temp;
                updateStatus();
            }
            return true;
        }

        Path target;
        if (path.equals("~")) {
            target = Paths.get(System.getProperty("user.home"));
        } else {
            Path p = Paths.get(path);
            target = p.isAbsolute() ? p : currentDirectory.resolve(p);
        }

        try {
            target = target.toRealPath();
            if (Files.isDirectory(target)) {
                previousDirectory = currentDirectory;
                currentDirectory = target;
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

        if (!clean.startsWith("pushd")) return false;

        String arg = clean.length() > 5 ? clean.substring(5).trim() : "";
        if (arg.isEmpty()) {
            AppLogger.error("pushd requires a target directory.");
            return true;
        }

        Path p = Paths.get(arg);
        Path target = p.isAbsolute() ? p : currentDirectory.resolve(p).normalize();
        if (Files.isDirectory(target)) {
            dirStack.push(currentDirectory);
            previousDirectory = currentDirectory;
            currentDirectory = target;
            updateStatus();
        } else {
            AppLogger.error("Directory not found.");
        }
        return true;
    }

    private boolean handlePopd(String cmd) {
        String clean = cmd.trim();
        if (clean.startsWith("::")) return false;
        if (clean.startsWith(":")) clean = clean.substring(1).trim();

        if (!clean.equalsIgnoreCase("popd")) return false;
        if (dirStack.isEmpty()) {
            AppLogger.error("Directory stack empty.");
        } else {
            previousDirectory = currentDirectory;
            currentDirectory = dirStack.pop();
            updateStatus();
        }
        return true;
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
