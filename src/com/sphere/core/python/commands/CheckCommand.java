package com.sphere.core.python.commands;

import com.sphere.core.python.PythonCommand;
import com.sphere.utils.AppLogger;
import java.io.IOException;

public class CheckCommand implements PythonCommand {
    private final String pythonExecutable;
    public CheckCommand(String pythonExecutable) { this.pythonExecutable = pythonExecutable; }

    @Override
    public void execute() {
        //AppLogger.info("Checking environment dependencies...");
        runCommand(new String[]{pythonExecutable, "-m", "pip", "check"});
    }
    
    private void runCommand(String[] cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line; while ((line = br.readLine()) != null) AppLogger.raw(line);
            }
        } catch (IOException e) { AppLogger.error("Check failed: " + e.getMessage()); }
    }
}
