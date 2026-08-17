package com.sphere.core.python;

import com.sphere.utils.JsonParser;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class PythonEnvService {

    private final String pythonPath;

    public PythonEnvService(String pythonPath) {
        this.pythonPath = pythonPath;
    }

    /* ---------------------------------------------------------
     * BASIC CHECKS
    */

    public boolean isPythonExecutable() {
        try {
            List<String> out = exec("-c", "import sys; print(sys.executable)");
            return !out.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public String getPythonVersion() {
        try {
            List<String> out = exec("--version");
            return out.isEmpty() ? "Unknown version" : out.get(0);
        } catch (Exception e) {
            return "Unknown version";
        }
    }

    /* ---------------------------------------------------------
     * ENVIRONMENT DETECTION
     */

    public EnvStatus detectEnvironment() {
        if (!isPythonExecutable()) return EnvStatus.NOT_PYTHON;

        if (detectEnvVar("CONDA_PREFIX")) return EnvStatus.CONDA;
        if (detectEnvVar("MAMBA_ROOT_PREFIX") || detectEnvVar("MAMBA_EXE")) return EnvStatus.MICROMAMBA;
        if (detectEnvVar("PYENV_ROOT")) return EnvStatus.PYENV;

        if (detectLegacyVirtualenv()) return EnvStatus.VIRTUALENV;
        if (detectVenv()) return EnvStatus.VENV;

        return EnvStatus.SYSTEM;
    }

    private boolean detectEnvVar(String var) {
        try {
            List<String> out = exec("-c", "import os; print('" + var + "' in os.environ)");
            return !out.isEmpty() && out.get(0).equals("True");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean detectLegacyVirtualenv() {
        try {
            List<String> out = exec("-c", "import sys; print(hasattr(sys, 'real_prefix'))");
            return !out.isEmpty() && out.get(0).equals("True");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean detectVenv() {
        try {
            List<String> out = exec("-c", "import sys; print(sys.prefix != sys.base_prefix)");
            return !out.isEmpty() && out.get(0).equals("True");
        } catch (Exception e) {
            return false;
        }
    }

    /* ---------------------------------------------------------
     * PIP OPERATIONS
     */

    public List<JsonParser.ModuleInfo> listModules() throws IOException, InterruptedException {
        return parseJson(exec("-m", "pip", "list", "--format=json"));
    }

    public List<JsonParser.ModuleInfo> listOutdated() throws IOException, InterruptedException {
        return parseJson(exec("-m", "pip", "list", "--outdated", "--format=json"));
    }

    public String checkDependencies() throws IOException, InterruptedException {
        return String.join("\n", exec("-m", "pip", "check"));
    }

    public List<String> upgradePip() throws IOException, InterruptedException {
        return exec("-m", "pip", "install", "--upgrade", "pip");
    }

    public List<String> upgradeAllOutdated() throws IOException, InterruptedException {
        List<String> log = new ArrayList<>();
        for (JsonParser.ModuleInfo m : listOutdated()) {
            log.add("Upgrading " + m.name + "...");
            log.addAll(runPipAction(PipAction.UPDATE, m.name));
        }
        return log;
    }

    public List<String> runPipAction(PipAction action, String moduleName)
            throws IOException, InterruptedException {

        List<String> cmd = new ArrayList<>();
        cmd.add("-m");
        cmd.add("pip");
        cmd.addAll(Arrays.asList(action.cmd.split(" ")));
        cmd.add(moduleName);

        return exec(cmd.toArray(new String[0]));
    }

    public List<String> purgeCache() throws IOException, InterruptedException {
        return exec("-m", "pip", "cache", "purge");
    }

    public String getPipCacheDirectory() throws IOException, InterruptedException {
        List<String> out = exec("-m", "pip", "cache", "dir");
        return out.isEmpty() ? null : out.get(0).trim();
    }

    public String getSitePackagesDirectory() throws IOException, InterruptedException {
        List<String> out = exec("-c", "import site; print(site.getsitepackages()[0])");
        return out.isEmpty() ? null : out.get(0).trim();
    }

    /* ---------------------------------------------------------
     * PROCESS EXECUTION
     */

    public List<String> exec(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(pythonPath);
        Collections.addAll(cmd, args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        Process p = pb.start();
        List<String> out = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null)
                out.add(line);
        }

        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new RuntimeException("Timeout executing: " + String.join(" ", cmd));
        }

        return out;
    }

    private List<JsonParser.ModuleInfo> parseJson(List<String> out) {
        String json = out.stream()
                .map(String::trim)
                .filter(s -> s.startsWith("[") || s.startsWith("{"))
                .collect(Collectors.joining());

        return JsonParser.parseModuleList(json);
    }

    public static String loadPythonExecFromConfig(String configPath) {
        Path file = Paths.get(configPath);

        if (!Files.exists(file)) {
            return "";
        }

        try {
            List<String> lines = Files.readAllLines(file);

            for (String line : lines) {
                line = line.trim();

                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[") && line.endsWith("]")) continue;

                if (line.startsWith("PYTHON_EXEC=")) {
                    String value = line.substring("PYTHON_EXEC=".length()).trim();
                    value = value.replace("\"", "");
                    if (value.contains(" ")) {
                        value = value.split("\\s+")[0];
                    }
                    return value;
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        return "";
    }

    public static void savePythonExecToConfig(String configPath, String newPath) {
        if (newPath == null) newPath = "";
        newPath = newPath.trim().replace("\"", "");

        // Remove arguments
        if (newPath.contains(" ")) {
            newPath = newPath.split("\\s+")[0];
        }

        Path file = Paths.get(configPath);

        try {
            List<String> lines = Files.exists(file)
                    ? Files.readAllLines(file)
                    : new ArrayList<>();

            boolean replaced = false;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();

                if (line.startsWith("PYTHON_EXEC=")) {
                    lines.set(i, "PYTHON_EXEC=" + newPath);
                    replaced = true;
                    break;
                }
            }

            if (!replaced) {
                lines.add("PYTHON_EXEC=" + newPath);
            }

            Files.write(file, lines);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getPythonExecutablePath() {
        return pythonPath;
    }
}

