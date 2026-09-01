package com.sphere.components;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.undo.*;
import java.awt.event.*;
import java.awt.Toolkit;

/**
 * Utility helper structure to inject cross-platform shortcut interaction patterns to working text components.
 */
public class UndoRedoUtility {

    // Flag to bypass history recording during programmatic operations (e.g., initial load)
    public static boolean isAutomatedUpdate = false;

    /**
     * Binds native history tracking mechanics (Ctrl+Z/Cmd+Z and Ctrl+Y/Cmd+Y) to a target text component document.
     * @param textComponent the active UI text model reference instance to listen to.
     */
    public static void setupUndoRedo(JTextComponent textComponent) {
        UndoManager undoManager = new UndoManager();
        
        // Listen for history additions, filtering out system-driven operations
        textComponent.getDocument().addUndoableEditListener(e -> {
            if (!isAutomatedUpdate) {
                undoManager.addEdit(e.getEdit());
            }
        });

        // Retrieve the platform-specific modifier mask (Ctrl on Windows/Linux, Command on macOS)
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        // Bind Platform Undo action sequence (Ctrl + Z or Cmd + Z)
        textComponent.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask), "Undo");
        textComponent.getActionMap().put("Undo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canUndo()) {
                    undoManager.undo();
                }
            }
        });

        // Bind Platform Redo action sequence (Ctrl + Y or Cmd + Y)
        textComponent.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, menuMask), "Redo");
        textComponent.getActionMap().put("Redo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canRedo()) {
                    undoManager.redo();
                }
            }
        });
    }
}
