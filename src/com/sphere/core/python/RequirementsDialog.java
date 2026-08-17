package com.sphere.core.python;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.util.List;
import com.sphere.ui.PyEnvManagerDialog;

public class RequirementsDialog extends JDialog {
    private JTextField pathField = new JTextField(20);
    private JTextArea logArea = new JTextArea(8, 40);

    public RequirementsDialog(PyEnvManagerDialog owner) {
        super(owner, "Install Requirements", true);
        setLayout(new BorderLayout(10, 10));
        
        PythonEnvService service = owner.getEnvService();

        // Header
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBorder(new EmptyBorder(10, 10, 0, 10));
        headerPanel.add(new JLabel("<html><b>Install from requirements.txt</b><br/>" +
                "Select a requirements file to install all dependencies at once.</html>"), BorderLayout.NORTH);
        
        // Content
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // File Selection
        JPanel filePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        filePanel.add(new JLabel("File Path:"));
        filePanel.add(pathField);
        JButton browseBtn = new JButton("Browse");
        browseBtn.addActionListener(e -> selectFile());
        filePanel.add(browseBtn);
        contentPanel.add(filePanel);

        // Environment Information
        JPanel infoPanel = new JPanel(new GridLayout(3, 1, 5, 5));
        infoPanel.setBorder(BorderFactory.createTitledBorder("Environment Configuration"));
        
        // Safely retrieve data with exception handling
        String pyVer = "N/A";
        String pyPath = "N/A";
        String pySite = "N/A";

        if (service != null) {
            try {
                pyVer = service.getPythonVersion();
                pyPath = service.getPythonExecutablePath();
                pySite = service.getSitePackagesDirectory();
            } catch (Exception e) {
                pyVer = "Error loading info";
            }
        }

        infoPanel.add(new JLabel("Python Version: " + pyVer));
        infoPanel.add(new JLabel("Executable: " + pyPath));
        infoPanel.add(new JLabel("Install Location (site-packages): " + pySite));
        
        contentPanel.add(Box.createVerticalStrut(10));
        contentPanel.add(infoPanel);
        
        // Log Area
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Installation Log"));
        contentPanel.add(Box.createVerticalStrut(10));
        contentPanel.add(scrollPane);

        // Buttons
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton installBtn = new JButton("Install");
        JButton editBtn = new JButton("Edit");
        JButton cancelBtn = new JButton("Cancel");

        installBtn.addActionListener(e -> executeRequirementsInstall());
        editBtn.addActionListener(e -> openInEditor());
        cancelBtn.addActionListener(e -> dispose());

        btnPanel.add(installBtn);
        btnPanel.add(editBtn);
        btnPanel.add(cancelBtn);

        add(headerPanel, BorderLayout.NORTH);
        add(contentPanel, BorderLayout.CENTER);
        add(btnPanel, BorderLayout.SOUTH);

        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
        setVisible(true);
    }

    private void selectFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            pathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void openInEditor() {
        File file = new File(pathField.getText());
        if (file.exists()) {
            try {
                Desktop.getDesktop().edit(file);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Could not open file: " + ex.getMessage());
            }
        } else {
            JOptionPane.showMessageDialog(this, "File not found. Please select a valid .txt file.");
        }
    }

    private void executeRequirementsInstall() {
        String filePath = pathField.getText();
        File file = new File(filePath);
        if (!file.exists()) {
            JOptionPane.showMessageDialog(this, "Please select a valid requirements.txt file.");
            return;
        }

        logArea.setText("Starting installation...\n");

        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() throws Exception {
                String pythonPath = ((PyEnvManagerDialog) getOwner()).getEnvService().getPythonExecutablePath();
                ProcessBuilder pb = new ProcessBuilder(pythonPath, "-m", "pip", "install", "-r", filePath);
                pb.redirectErrorStream(true);
                Process process = pb.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
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
                for (String line : chunks) {
                    logArea.append(line + "\n");
                }
            }

            @Override
            protected void done() {
                logArea.append("Installation process completed.\n");
            }
        }.execute();
    }
}
