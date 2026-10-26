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

            // pip's warnings go to stderr, which nothing read: once its pipe was
            // full pip waited on it and the freeze never ended. It is read aside.
            StringBuffer errors = new StringBuffer();
            Thread drain = new Thread(() -> {
                try (BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = err.readLine()) != null) {
                        errors.append(line).append(System.lineSeparator());
                    }
                } catch (IOException ignored) {
                }
            }, "pip-freeze-stderr");
            drain.setDaemon(true);
            drain.start();

            // Use a StringBuilder to capture the output for both console and file
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    AppLogger.raw(line);           // Display in console
                    output.append(line).append(System.lineSeparator()); // Store for file
                }
            }
            final int status = process.waitFor();
            drain.join(2000);

            // A pip that failed printed nothing, and that nothing replaced the
            // requirements.txt already there, announced as saved.
            if (status != 0) {
                AppLogger.error("pip freeze failed (exit " + status + "); " + REQUIREMENTS_FILE
                                + " left as it was." + (errors.length() > 0 ? System.lineSeparator()
                                + errors.toString().trim() : ""));
                return;
            }

            // Write to requirements.txt in the application root
            Files.write(Paths.get(REQUIREMENTS_FILE), output.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            AppLogger.result("Requirements saved to: " + new File(REQUIREMENTS_FILE).getAbsolutePath());

        } catch (IOException e) {
            AppLogger.error("Failed to execute or save pip freeze: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AppLogger.error("pip freeze was interrupted; " + REQUIREMENTS_FILE + " left as it was.");
        }
    }
}
