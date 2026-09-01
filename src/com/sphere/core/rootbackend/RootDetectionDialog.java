package com.sphere.core.rootbackend;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Graphical interface modal dialog responsible for locating, verifying, and 
 * synchronizing the installation layout and environment profile for ROOT.
 */
public class RootDetectionDialog implements RootDetectionCallback {

    private final Frame parentFrame;
    private static final String CONFIG_FILE_NAME = "settings.conf";

    public RootDetectionDialog(Frame parentFrame) {
        this.parentFrame = parentFrame;
    }

    @Override
    public boolean onRootDetected(String detectedPath, Map<String, String> environment) {
        if (noDialogCase(environment)) {
            return true;
        }

        final boolean[] saveAuthorized = {false};
        
        String verifiedPath = detectedPath;
        if (detectedPath != null && !detectedPath.isEmpty()) {
            File baseFile = new File(detectedPath);
            String osName = System.getProperty("os.name").toLowerCase();
            String binaryName = osName.contains("win") ? "root.exe" : "root";

            if (baseFile.isDirectory()) {
                File binaryTarget = new File(baseFile, "bin" + File.separator + binaryName);
                if (binaryTarget.exists()) {
                    verifiedPath = safeCanonical(binaryTarget);
                } else {
                    File directBinary = new File(baseFile, binaryName);
                    if (directBinary.exists()) {
                        verifiedPath = safeCanonical(directBinary);
                    }
                }
            } else if (baseFile.isFile()) {
                verifiedPath = safeCanonical(baseFile);
            }
        }
        
        final boolean isFailureFallback = (verifiedPath == null || verifiedPath.isEmpty() || !new File(verifiedPath).exists());
        
        if (!isFailureFallback && environment != null && environment.containsKey("ROOTSYS")) {
            return true;
        }

        final String cleanPath = isFailureFallback ? "No valid path found (Click Browse to fix)" : verifiedPath;
        final String finalVerifiedPath = verifiedPath;
        final String[] dynamicSelectedPath = { finalVerifiedPath };

        try {
            Runnable dialogTask = () -> {
                JDialog dialog = new JDialog(parentFrame, "ROOT Configuration Sync", true);
                dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                dialog.setLayout(new BorderLayout(10, 10));

                JPanel panel = new JPanel(new BorderLayout(10, 10));
                panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

                String headerText = isFailureFallback 
                    ? "<html><font color='red'><b>Sphere could not locate CERN ROOT on your system.</b></font><br>Please select the executable binary manually to configure the environment:</html>"
                    : "<html><b>Sphere detected a different ROOT layout.</b> Review the environment variables below:</html>";
                
                JLabel header = new JLabel(headerText);
                panel.add(header, BorderLayout.NORTH);

                JTextField pathField = createReadOnlyField(cleanPath);
                JTextField rootsysField = createReadOnlyField(environment != null ? environment.getOrDefault("ROOTSYS", "Not Found") : "Not Found");
                JTextField ldField = createReadOnlyField(environment != null ? environment.getOrDefault("LD_LIBRARY_PATH", "Not Found") : "Not Found");
                JTextField dyldField = createReadOnlyField(environment != null ? environment.getOrDefault("DYLD_LIBRARY_PATH", "Not Found") : "Not Found");
                JTextField pythonField = createReadOnlyField(environment != null ? environment.getOrDefault("PYTHONPATH", "Not Found") : "Not Found");

                JPanel grid = new JPanel(new GridLayout(5, 2, 5, 5));
                grid.add(new JLabel("ROOT Executable:"));
                
                JPanel pathPanel = new JPanel(new BorderLayout(5, 0));
                pathPanel.add(pathField, BorderLayout.CENTER);
                
                if (isFailureFallback) {
                    JButton browseButton = new JButton("Browse...");
                    browseButton.addActionListener(e -> {
                        JFileChooser chooser = new JFileChooser();
                        chooser.setDialogTitle("Select 'root' binary or 'thisroot.sh'");
                        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                        
                        chooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
                            @Override
                            public boolean accept(File f) {
                                return f.isDirectory() || f.getName().equals("thisroot.sh") || f.getName().startsWith("root");
                            }
                            @Override
                            public String getDescription() {
                                return "CERN ROOT Files (root, thisroot.sh)";
                            }
                        });

                        if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                            File selectedFile = chooser.getSelectedFile();
                            String selectedPath = safeCanonical(selectedFile);
                            pathField.setText(selectedPath);
                            dynamicSelectedPath[0] = selectedPath;
                            
                            File rootBinary = null;
                            if (selectedFile.getName().toLowerCase().contains("thisroot.sh")) {
                                File binDir = selectedFile.getParentFile();
                                if (binDir != null) {
                                    rootBinary = new File(binDir, System.getProperty("os.name").toLowerCase().contains("win") ? "root.exe" : "root");
                                    dynamicSelectedPath[0] = safeCanonical(rootBinary);
                                }
                            } else {
                                rootBinary = selectedFile;
                            }

                            if (rootBinary != null && rootBinary.exists()) {
                                File binDir = rootBinary.getParentFile();
                                File rootHomeDir = (binDir != null && binDir.getName().equals("bin")) ? binDir.getParentFile() : binDir;
                                
                                if (rootHomeDir != null) {
                                    String computedRootsys = safeCanonical(rootHomeDir);
                                    String computedLd = computedRootsys + File.separator + "lib";
                                    
                                    rootsysField.setText(computedRootsys);
                                    ldField.setText(computedLd);
                                    dyldField.setText(computedLd);
                                    pythonField.setText(computedLd);

                                    if (environment != null) {
                                        environment.put("ROOTSYS", computedRootsys);
                                        environment.put("LD_LIBRARY_PATH", computedLd);
                                        environment.put("DYLD_LIBRARY_PATH", computedLd);
                                        environment.put("PYTHONPATH", computedLd);
                                        environment.put("MANUAL_EXEC_PATH", rootBinary.getAbsolutePath());
                                    }

                                    try {
                                        File binDirLocal = rootBinary.getParentFile();
                                        File rootConfigLocal = new File(binDirLocal, "root-config");
                                        String cmd = rootConfigLocal.exists() ? rootConfigLocal.getAbsolutePath() : "root-config";

                                        ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
                                        if (environment != null) {
                                            pb.environment().putAll(environment);
                                        }
                                        pb.redirectErrorStream(true);
                                        Process p = pb.start();
                                        StringBuilder out = new StringBuilder();
                                        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                                            String line;
                                            while ((line = r.readLine()) != null) {
                                                out.append(line).append('\n');
                                            }
                                        }
                                        if (p.waitFor(3, TimeUnit.SECONDS)) {
                                            int exit = p.exitValue();
                                            String versionLine = out.toString().trim();
                                            if (exit == 0 && !versionLine.isEmpty()) {
                                                header.setText("<html><font color='green'><b>✔ CERN ROOT Validated (" + versionLine + ")</b></font><br>Environment layout successfully generated from selection.</html>");
                                            } else if (exit == 0) {
                                                header.setText("<html><font color='orange'><b>⚠ Warning: Execution returned a blank version string.</b></font> Missing library dependencies might block execution.</html>");
                                            } else {
                                                header.setText("<html><font color='red'><b>❌ Binary execution failure.</b></font> root-config --version exited with code " + exit + "</html>");
                                            }
                                        } else {
                                            p.destroyForcibly();
                                            header.setText("<html><font color='red'><b>❌ Binary execution timeout.</b></font> root-config --version did not respond in time.</html>");
                                        }
                                    } catch (Exception ex) {
                                        header.setText("<html><font color='red'><b>❌ Binary execution failure.</b></font> Unable to execute root-config --version (" + ex.getMessage() + ")</html>");
                                    }
                                }
                            } else {
                                header.setText("<html><font color='red'><b>❌ Invalid target location.</b></font> Target executable 'root' file is missing.</html>");
                            }
                        }
                    });
                    pathPanel.add(browseButton, BorderLayout.EAST);
                }

                grid.add(pathPanel);
                grid.add(new JLabel("ROOTSYS:"));
                grid.add(rootsysField);
                grid.add(new JLabel("LD_LIBRARY_PATH:"));
                grid.add(ldField);
                grid.add(new JLabel("DYLD_LIBRARY_PATH:"));
                grid.add(dyldField);
                grid.add(new JLabel("PYTHONPATH:"));
                grid.add(pythonField);
                panel.add(grid, BorderLayout.CENTER);

                JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
                JButton saveButton = new JButton(isFailureFallback ? "Apply & Save Configuration" : "Save to settings.conf");
                JButton cancelButton = new JButton("Cancel & Quit");

                saveButton.addActionListener(e -> {
                    String chosenPath = dynamicSelectedPath[0];
                    if (isFailureFallback && (chosenPath == null || chosenPath.isEmpty())) {
                        JOptionPane.showMessageDialog(dialog, "Please select a valid ROOT binary or thisroot.sh before saving.", "Path Missing", JOptionPane.ERROR_MESSAGE);
                        return;
                    }

                    String finalBinaryPath = (chosenPath != null) ? chosenPath : finalVerifiedPath;
                    String rootHomePath = "";
                    
                    if (finalBinaryPath != null && !finalBinaryPath.isEmpty()) {
                        File selectedFile = new File(finalBinaryPath);
                        if (selectedFile.exists()) {
                            File binDir = selectedFile.getParentFile();
                            File rootHomeDir = (binDir != null && binDir.getName().equals("bin")) ? binDir.getParentFile() : binDir;
                            if (rootHomeDir != null) {
                                rootHomePath = safeCanonical(rootHomeDir);
                            }
                        }
                    }

                    if (rootHomePath.isEmpty() && finalBinaryPath != null) {
                        rootHomePath = finalBinaryPath;
                    }

                    saveRootDirectoryToSettingsFile(rootHomePath, dialog);
                    saveAuthorized[0] = true;
                    dialog.dispose();
                });
                
                cancelButton.addActionListener(e -> {
                    saveAuthorized[0] = false;
                    dialog.dispose();
                });

                buttonPanel.add(saveButton);
                buttonPanel.add(cancelButton);
                panel.add(buttonPanel, BorderLayout.SOUTH);

                dialog.add(panel);
                dialog.pack();
                dialog.setLocationRelativeTo(parentFrame);
                dialog.setAlwaysOnTop(true);
                dialog.setVisible(true);

                if (saveAuthorized[0] && isFailureFallback && dynamicSelectedPath[0] != null && environment != null) {
                    environment.put("MANUAL_EXEC_PATH", dynamicSelectedPath[0]);
                }
            };

            if (SwingUtilities.isEventDispatchThread()) {
                dialogTask.run();
            } else {
                SwingUtilities.invokeAndWait(dialogTask);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return saveAuthorized[0];
    }

    private String safeCanonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }

    private void saveRootDirectoryToSettingsFile(String rootHomePath, Component parentComponent) {
        if (rootHomePath == null || rootHomePath.isEmpty()) return;

        File configFile = new File(CONFIG_FILE_NAME);
        List<String> outputLines = new ArrayList<>();
        boolean sectionGeneralFound = false;
        boolean rootDirUpdated = false;

        if (configFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    
                    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                        if (sectionGeneralFound && !rootDirUpdated) {
                            outputLines.add("ROOT_DIR=" + rootHomePath);
                            rootDirUpdated = true;
                        }
                        
                        if (trimmed.equalsIgnoreCase("[GENERAL]")) {
                            sectionGeneralFound = true;
                        } else {
                            sectionGeneralFound = false;
                        }
                        outputLines.add(line);
                        continue;
                    }

                    if (sectionGeneralFound && trimmed.startsWith("ROOT_DIR=")) {
                        outputLines.add("ROOT_DIR=" + rootHomePath);
                        rootDirUpdated = true;
                    } else {
                        outputLines.add(line);
                    }
                }
                
                if (sectionGeneralFound && !rootDirUpdated) {
                    outputLines.add("ROOT_DIR=" + rootHomePath);
                    rootDirUpdated = true;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (outputLines.isEmpty()) {
            outputLines.add("[SYSTEM_PATH]");
            outputLines.add("[GENERAL]");
            outputLines.add("ROOT_DIR=" + rootHomePath);
            outputLines.add("[TERMINAL_CONFIG]");
            outputLines.add("[CPP_TOOLCHAIN]");
            outputLines.add("[ENGINEERING]");
        }

        try (PrintWriter writer = new PrintWriter(new FileWriter(configFile, false))) {
            for (String outputLine : outputLines) {
                writer.println(outputLine);
            }
        } catch (IOException e) {
            e.printStackTrace();
            // CORRECT: Pass the actual integer constant
            JOptionPane.showMessageDialog(parentComponent, 
                "Failed to update parameter changes inside your configuration settings:\n" + e.getMessage(), 
                "I/O Storage Error", 
                JOptionPane.ERROR_MESSAGE);
        }
    }

    private File searchRootConfigRecursively(File dir, Set<String> visited) {
        try {
            String canon = dir.getCanonicalPath();
            if (!visited.add(canon)) {
                return null;
            }
        } catch (IOException e) {
        }

        File[] children = dir.listFiles();
        if (children == null) return null;

        for (File child : children) {
            if (child.isFile() && child.getName().equals("root-config") && child.canExecute()) {
                return child;
            }
        }

        for (File child : children) {
            if (child.isDirectory()) {
                File found = searchRootConfigRecursively(child, visited);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean noDialogCase(Map<String, String> environment) {
        
        File configFile = new File(CONFIG_FILE_NAME);
        if (!configFile.exists()) {
            return false;
        }

        String rootDir = null;

        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            boolean inGeneral = false;

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();

                if (trimmed.equalsIgnoreCase("[GENERAL]")) {
                    inGeneral = true;
                    continue;
                }
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    inGeneral = false;
                }

                if (inGeneral && trimmed.startsWith("ROOT_DIR=")) {
                    rootDir = trimmed.substring("ROOT_DIR=".length()).trim();
                    
                    // Strip surrounding quotes if present (e.g., ROOT_DIR="C:\root")
                    if (rootDir.startsWith("\"") && rootDir.endsWith("\"")) {
                        rootDir = rootDir.substring(1, rootDir.length() - 1).trim();
                    }
                    break;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (rootDir == null || rootDir.isEmpty()) {
            return false;
        }

        File rootBase = new File(rootDir);
        if (!rootBase.exists() || !rootBase.isDirectory()) {
            return false;
        }

        File rootConfig = new File(rootBase, "bin" + File.separator + "root-config");
        if (!rootConfig.exists() || !rootConfig.isFile() || !rootConfig.canExecute()) {
            String pathEnv = System.getenv("PATH");
            if (pathEnv != null) {
                for (String segment : pathEnv.split(File.pathSeparator)) {
                    File candidate = new File(segment, "root-config");
                    if (candidate.exists() && candidate.isFile() && candidate.canExecute()) {
                        rootConfig = candidate;
                        break;
                    }
                }
            }
        }

        if (!rootConfig.exists() || !rootConfig.isFile() || !rootConfig.canExecute()) {
            File found = searchRootConfigRecursively(rootBase, new HashSet<>());
            if (found != null) {
                rootConfig = found;
            }
        }

        if (!rootConfig.exists() || !rootConfig.isFile() || !rootConfig.canExecute()) {
            return false;
        }

        try {
            ProcessBuilder pbPrefix = new ProcessBuilder(rootConfig.getAbsolutePath(), "--prefix");
            pbPrefix.redirectErrorStream(true);
            Process pPrefix = pbPrefix.start();
            StringBuilder outPrefix = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(pPrefix.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    outPrefix.append(line).append('\n');
                }
            }
            if (!pPrefix.waitFor(3, TimeUnit.SECONDS)) {
                pPrefix.destroyForcibly();
                return false;
            }
            String prefix = outPrefix.toString().trim();
            if (prefix.isEmpty()) {
                return false;
            }

            String computedBin = prefix + File.separator + "bin";
            String computedLd = prefix + File.separator + "lib";

            ProcessBuilder pbVersion = new ProcessBuilder(rootConfig.getAbsolutePath(), "--version");
            pbVersion.redirectErrorStream(true);
            Map<String, String> procEnv = pbVersion.environment();
            procEnv.put("ROOTSYS", prefix);
            procEnv.put("LD_LIBRARY_PATH", computedLd);
            procEnv.put("DYLD_LIBRARY_PATH", computedLd);
            procEnv.put("SHLIB_PATH", computedLd);
            procEnv.put("LIBPATH", computedLd);
            String existingPath = procEnv.getOrDefault("PATH", "");
            procEnv.put("PATH", computedBin + File.pathSeparator + existingPath);

            Process pVersion = pbVersion.start();
            StringBuilder outVersion = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(pVersion.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    outVersion.append(line).append('\n');
                }
            }
            if (!pVersion.waitFor(3, TimeUnit.SECONDS)) {
                pVersion.destroyForcibly();
                return false;
            }
            String versionLine = outVersion.toString().trim();
            if (versionLine.isEmpty() || versionLine.toLowerCase().contains("error")) {
                return false;
            }

            String os = System.getProperty("os.name").toLowerCase();
            String binaryName = os.contains("win") ? "root.exe" : "root";
            File rootBinary = new File(computedBin, binaryName);

            if (environment != null) {
                environment.put("ROOTSYS", prefix);
                environment.put("LD_LIBRARY_PATH", computedLd);
                environment.put("DYLD_LIBRARY_PATH", computedLd);
                environment.put("PYTHONPATH", computedLd);
                if (rootBinary.exists()) {
                    environment.put("MANUAL_EXEC_PATH", rootBinary.getAbsolutePath());
                }
            }
            return true;
        } catch (Exception e) {
            // Left empty
        }
        return false;
    }

    private JTextField createReadOnlyField(String text) {
        JTextField field = new JTextField(text);
        field.setEditable(false);
        field.setBackground(Color.WHITE);
        field.setCaretPosition(0);
        return field;
    }
}