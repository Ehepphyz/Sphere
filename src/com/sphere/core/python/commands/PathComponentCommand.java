package com.sphere.core.python.commands;

import com.sphere.core.python.PythonCommand;
import com.sphere.utils.AppLogger;
import java.io.IOException;

public class PathComponentCommand implements PythonCommand {
    private final String pythonExecutable;
    public PathComponentCommand(String pythonExecutable) { this.pythonExecutable = pythonExecutable; }

    @Override
    public void execute() {
        String script = "import sys; print('\\n'.join(sys.path))";
        try {
            Process p = new ProcessBuilder(pythonExecutable, "-c", script).start();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) AppLogger.raw(line);
            }
        } catch (IOException e) { AppLogger.error("Failed to list path: " + e.getMessage()); }
    }
}
