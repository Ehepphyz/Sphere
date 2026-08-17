package com.sphere.core.python.commands;

import com.sphere.core.python.PythonCommand;
import com.sphere.utils.AppLogger;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;

public class FreezeCommand implements PythonCommand {
    private final String pythonExecutable;
    private static final String REQUIREMENTS_FILE = "requirements.txt";

    public FreezeCommand(String pythonExecutable) {
        this.pythonExecutable = pythonExecutable;
    }

    @Override
    public void execute() {
        //AppLogger.success("Environment state (pip freeze):");
        
        try {
            // Start the process
            Process process = new ProcessBuilder(pythonExecutable, "-m", "pip", "freeze").start();
            
            // Use a StringBuilder to capture the output for both console and file
            StringBuilder output = new StringBuilder();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    AppLogger.raw(line);           // Display in console
                    output.append(line).append(System.lineSeparator()); // Store for file
                }
            }

            // Write to requirements.txt in the application root
            Files.write(Paths.get(REQUIREMENTS_FILE), output.toString().getBytes());
            AppLogger.info("Requirements saved to: " + new File(REQUIREMENTS_FILE).getAbsolutePath());

        } catch (IOException e) {
            AppLogger.error("Failed to execute or save pip freeze: " + e.getMessage());
        }
    }
}
