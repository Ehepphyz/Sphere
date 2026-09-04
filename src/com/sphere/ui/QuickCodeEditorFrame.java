package com.sphere.ui;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import com.sphere.components.QuickCodeEditor;

/**
 * Lightweight container frame designed to expose QuickCodeEditor context hooks 
 * cleanly across the workbench ecosystem.
 */
public class QuickCodeEditorFrame extends JFrame {

    private final QuickCodeEditor editor;

    /**
     * Constructs a standalone code editor window wrapping a high-performance editor instance
     */
    public QuickCodeEditorFrame(File targetFile) {
        setTitle("Sphere Editor - " + (targetFile != null ? targetFile.getName() : "Untitled"));
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(850, 600);
        setLocationRelativeTo(null);

        editor = new QuickCodeEditor();
        setLayout(new BorderLayout());
        add(editor, BorderLayout.CENTER);

        // Inject the menu bar hooks using the internally exposed method
        editor.setupMenu(this);

        if (targetFile != null && targetFile.exists()) {
            editor.loadFile(targetFile);
        }

        // A debug session outliving its window would leave gdb or the Python
        // driver running with nothing to report to.
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                editor.shutdownDebugger();
            }
        });
    }

    /**
     * Reuses the existing editor instance to load a new file internally,
     * avoiding window duplication and updating the frame title dynamically
     */
    public void openFileInternally(File file) {
        if (file == null || !file.exists()) {
            return;
        }

        // If the workspace is currently displaying the file, focus the window and abort execution
        if (editor.getCurrentFile() != null && editor.getCurrentFile().getAbsolutePath().equals(file.getAbsolutePath())) {
            if (!isVisible()) {
                setVisible(true);
            }
            toFront();
            editor.requestFocusInWindow();
            return;
        }

        // Load the new target resource content into the view pipeline
        editor.loadFile(file);
        setTitle("Sphere Editor - " + file.getName());

        // Bring the window view forward to focus the freshly reloaded asset
        if (!isVisible()) {
            setVisible(true);
        }
        toFront();
        editor.requestFocusInWindow();
    }

    /**
     * Accesses the underlying editor panel instance
     */
    public QuickCodeEditor getEditor() {
        return editor;
    }
}