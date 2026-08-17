package com.sphere.core.python.commands;

import com.sphere.core.python.PythonCommand;
import com.sphere.utils.AppLogger;
import java.io.IOException;

public class ReinstallCommand implements PythonCommand {
    private final String pythonExecutable;
    public ReinstallCommand(String pythonExecutable) { this.pythonExecutable = pythonExecutable; }

    @Override
    public void execute() {
        AppLogger.info("Reinstalling requirements from requirements.txt...");
        try {
            Process p = new ProcessBuilder(pythonExecutable, "-m", "pip", "install", "-r", "requirements.txt").inheritIO().start();
            p.waitFor();
            AppLogger.success("Reinstallation complete.");
        } catch (IOException | InterruptedException e) { AppLogger.error("Reinstall failed: " + e.getMessage()); }
    }
}
