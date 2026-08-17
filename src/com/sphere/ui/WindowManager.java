package com.sphere.ui;

import com.sphere.core.EnvBackend;
import com.sphere.utils.SettingsManager;
import java.awt.Window;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Centralized Window Lifecycle and Workspace Editor Registry for Sphere.
 * Handles dynamic frame lazy-allocation and maps active editor streams cleanly.
 */
public class WindowManager {

    // Cache to monitor open runtime environment dashboards
    private static final Map<EnvBackend, GenericEnvManagerDialog> windowCache = new HashMap<>();
    
    // Cache to prevent duplicate editor instances of the same configuration file in memory
    private static final Map<String, QuickCodeEditorFrame> editorCache = new HashMap<>();

    /**
     * Resolves, synchronizes, and displays both the requested environment dashboard 
     * and its configuration script target inside the internal editor subsystem.
     */
    public static void showEnvironment(Window parent, EnvBackend backend, SettingsManager settings) {
        if (backend == null) return;

        // 1. Core Routing: Synchronize and bring the target Environment Console to viewport focus
        GenericEnvManagerDialog dialog = windowCache.get(backend);
        if (dialog == null) {
            dialog = new GenericEnvManagerDialog(parent, backend, settings);
            windowCache.put(backend, dialog);
        }
        
        if (!dialog.isVisible()) {
            dialog.setVisible(true);
        }
        dialog.toFront();

        // 2. Editor Routing: Open the settings.conf file directly so the user can modify paths
        File configFile = new File(settings.getConfigFilePath()); // Point directly to settings.conf
        
        if (configFile.exists()) {
            showFileInEditor(configFile);
            
            // Optional Quality-of-Life: Auto-focus the search context for this backend
            QuickCodeEditorFrame editorFrame = editorCache.get(configFile.getAbsolutePath());
            if (editorFrame != null) {
                // Look up the exact text key inside settings.conf (e.g., "PYTHON_EXEC")
                String searchKey = backend.getConfigKey();
                javax.swing.JTextArea area = editorFrame.getEditor().getEditorArea();
                
                int index = area.getText().indexOf(searchKey);
                if (index >= 0) {
                    area.setCaretPosition(index);
                    area.select(index, index + searchKey.length());
                }
            }
        } else {
            com.sphere.utils.AppLogger.warn(
                "[" + backend.name() + "] Workspace editor failure: " + settings.getConfigFilePath() + " cannot be located."
            );
        }
    }

    /**
     * Dedicated operational channel to view or edit any system file safely inside 
     * a single-instance QuickCodeEditor frame.
     */
    public static void showFileInEditor(File file) {
        if (file == null) return;
        
        String absolutePath = file.getAbsolutePath();
        QuickCodeEditorFrame editorFrame = editorCache.get(absolutePath);

        // Lazy Loading: Build the code editor window only if it isn't currently tracking the file
        if (editorFrame == null || !editorFrame.isDisplayable()) {
            editorFrame = new QuickCodeEditorFrame(file);
            
            // Clean up memory cache context footprints when the physicist exits the frame manually
            final QuickCodeEditorFrame frameReference = editorFrame;
            editorFrame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    editorCache.remove(absolutePath);
                }
            });
            
            editorCache.put(absolutePath, editorFrame);
        }

        editorFrame.setVisible(true);
        editorFrame.toFront();
    }
}
