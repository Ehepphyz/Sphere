package com.sphere.utils.settingsmanager;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Top-level window component managing the settings.conf runtime lifetime.
 * Offers operational action states including history-tracked undo/redo actions.
 */
public class SettingsEditorWindow extends JFrame {

    private final SettingsFile file;
    private final Path settingsPath;
    private final Deque<SettingsFile> undoStack = new ArrayDeque<>();
    private final Deque<SettingsFile> redoStack = new ArrayDeque<>();
    private boolean isDirty = false;
    private final SettingsEditorPanel panel;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    // Snapshot of SYSTEM_PATH data taken at window initialization
    private final String originalSystemPathSnapshot;

    public SettingsEditorWindow(Path settingsPath) throws Exception {
        this.settingsPath = settingsPath;
        this.file = SettingsParser.parse(settingsPath);
        
        // Take an initial structural string snapshot of the SYSTEM_PATH category keys mapping
        this.originalSystemPathSnapshot = getSystemPathDataSnapshot();

        setTitle("Configuration File Editor — settings.conf");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());

        panel = new SettingsEditorPanel(file, snapshot -> {
            undoStack.push(snapshot);
            redoStack.clear();
            isDirty = true;
        });
        add(panel, BorderLayout.CENTER);

        JButton saveButton   = new JButton("Save");
        JButton cancelButton = new JButton("Cancel");
        JButton undoButton   = new JButton("Undo");
        JButton redoButton   = new JButton("Redo");

        styleButton(saveButton);
        styleButton(cancelButton);
        styleButton(undoButton);
        styleButton(redoButton);

        saveButton.addActionListener(e -> {
            try {
                file.saveToFile(settingsPath);
                isDirty = false;
                undoStack.clear();
                redoStack.clear();
                
                // Check if the SYSTEM_PATH keys/values were altered during this runtime instance session
                boolean systemPathChanged = !originalSystemPathSnapshot.equals(getSystemPathDataSnapshot());
                
                if (systemPathChanged) {
                    // Inform user of successful save, warn about application shutdown/restart requirement
                    JOptionPane.showMessageDialog(this,
                            "Configuration saved successfully.\n\n" +
                            "Critical system paths have been modified. The application will now close.\n" +
                            "Please relaunch the software to generate fresh dynamic configurations.",
                            "System Changes Applied",
                            JOptionPane.WARNING_MESSAGE);
                    
                    // Wipe out everything inside the config/ folder recursively to force dynamic rebuilds
                    clearConfigDirectory(new File("config"));
                    
                    // Terminate the JVM to enforce fresh environmental setups on relaunch
                    System.exit(0);
                } else {
                    JOptionPane.showMessageDialog(this, 
                            "Configuration changes were saved successfully.", 
                            "Success", 
                            JOptionPane.INFORMATION_MESSAGE);
                    dispose();
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                        "Failed to persist configuration file changes:\n" + ex.getMessage(),
                        "Save Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        });

        cancelButton.addActionListener(e -> {
            if (isDirty) {
                int res = JOptionPane.showConfirmDialog(this,
                        "Are you sure you want to discard all unsaved changes?",
                        "Confirm Operation",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (res != JOptionPane.YES_OPTION) {
                    return;
                }
            }
            dispose();
        });

        undoButton.addActionListener(e -> performUndo());
        redoButton.addActionListener(e -> performRedo());

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        if (palette != null) {
            bottom.setBackground(palette.getBackgroundSurface());
        }

        bottom.add(undoButton);
        bottom.add(redoButton);
        bottom.add(cancelButton);
        bottom.add(saveButton);

        add(bottom, BorderLayout.SOUTH);

        setSize(950, 450);
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (isDirty) {
                    int res = JOptionPane.showConfirmDialog(SettingsEditorWindow.this,
                            "You have unsaved configuration updates active. Close window anyway?",
                            "Unsaved Changes Warning",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE);
                    if (res != JOptionPane.YES_OPTION) {
                        return;
                    }
                }
                dispose();
            }
        });
    }

    /**
     * Extracts and serializes the targeted SYSTEM_PATH category map to check for value updates.
     */
    private String getSystemPathDataSnapshot() {
        if (file == null) return "";
        StringBuilder sb = new StringBuilder();
        for (var entry : file.entries("SYSTEM_PATH")) {
            sb.append(entry.key()).append('=').append(entry.value()).append(';');
        }
        return sb.toString();
    }

    /**
     * Recursively purges contents of a targeted directory tree.
     */
    private void clearConfigDirectory(File directory) {
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        clearConfigDirectory(f);
                    }
                    f.delete();
                }
            }
        }
    }

    private void styleButton(JButton b) {
        if (palette == null) return;

        b.setBackground(palette.getButtonBase());
        b.setForeground(palette.getTextPrimary());
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createLineBorder(palette.getBorder()));
        b.setContentAreaFilled(true);
        b.setPreferredSize(new Dimension(95, 26));

        b.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { b.setBackground(palette.getButtonHover()); }
            @Override public void mouseExited(java.awt.event.MouseEvent e) { b.setBackground(palette.getButtonBase()); }
            @Override public void mousePressed(java.awt.event.MouseEvent e) { b.setBackground(palette.getButtonPressed()); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { b.setBackground(palette.getButtonHover()); }
        });
    }

    private void performUndo() {
        if (undoStack.isEmpty()) return;

        SettingsFile previous = undoStack.pop();
        redoStack.push(file.cloneSnapshot());
        file.copyFrom(previous);
        panel.refreshFromModel();
        isDirty = true;
    }

    private void performRedo() {
        if (redoStack.isEmpty()) return;

        SettingsFile next = redoStack.pop();
        undoStack.push(file.cloneSnapshot());
        file.copyFrom(next);
        panel.refreshFromModel();
        isDirty = true;
    }
}
