package com.sphere.core.python.commands;

import com.sphere.core.python.PythonCommand;
import com.sphere.utils.AppLogger;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;

/* -------------------------------------------------------------------------
 * Executes a diagnostic check to retrieve the actual Python executable path
 * and the version directly from the interpreter's sys module.
 */
public class VersionCommand implements PythonCommand {
    private final String pythonExecutable;

    public VersionCommand(String pythonExecutable) {
        this.pythonExecutable = pythonExecutable;
    }

    @Override
    public void execute() {
        // Use a Python one-liner to query the interpreter for its own path and version
        String script = "import sys; print(f'Executable Path: {sys.executable}'); print(f'Version: {sys.version.split()[0]}')";
        
        try {
            Process process = new ProcessBuilder(pythonExecutable, "-c", script).start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                //AppLogger.success("Python Environment Diagnostics:");
                while ((line = reader.readLine()) != null) {
                    // Raw output displays the path and version cleanly
                    AppLogger.raw(line);
                }
            }
        } catch (IOException e) {
            AppLogger.error("Could not retrieve Python version information: " + e.getMessage());
        }
    }
}
