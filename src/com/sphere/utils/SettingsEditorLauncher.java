package com.sphere.utils;

import com.sphere.utils.settingsmanager.SettingsEditorWindow;

import javax.swing.*;
import java.nio.file.Path;

public final class SettingsEditorLauncher {

    private SettingsEditorLauncher() {}

    public static void open(Path settingsPath) {
        SwingUtilities.invokeLater(() -> {
            try {
                new SettingsEditorWindow(settingsPath).setVisible(true);
            } catch (Exception e) {
                AppLogger.error("Failed to open settings editor: " + e.getMessage());
            }
        });
    }
}

