package com.sphere.ui;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import com.sphere.components.SphereCoder;

/**
 * Lightweight container frame designed to expose SphereCoder context hooks
 * cleanly across the workbench ecosystem.
 */
public class SphereCoderFrame extends JFrame {

    private final SphereCoder editor;

    /**
     * Constructs a standalone code editor window wrapping a high-performance editor instance
     */
    public SphereCoderFrame(File targetFile) {
        this(targetFile, false);
    }

    /**
     * @param primary true for Sphere's main editor window, the one that
     *                remembers its split layout and open files from one
     *                session to the next
     */
    public SphereCoderFrame(File targetFile, boolean primary) {
        setTitle("SphereCoder - " + (targetFile != null ? targetFile.getName() : "Untitled"));
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        // Room for the breadcrumbs, the minimap and the panels, without outgrowing the screen.
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1280, (int) (screen.width * 0.85)), Math.min(840, (int) (screen.height * 0.85)));
        setMinimumSize(new Dimension(640, 420));
        setLocationRelativeTo(null);

        editor = new SphereCoder();
        setLayout(new BorderLayout());
        add(editor, BorderLayout.CENTER);

        // Inject the menu bar hooks using the internally exposed method
        editor.setupMenu(this);

        if (primary) {
            editor.enableSessionRestore();
            // Sphere builds this window at startup, hidden: the last session's
            // files come back when it first shows, with whatever opened it.
            addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowOpened(java.awt.event.WindowEvent e) {
                    editor.restoreSessionIfPending();
                }
            });
        }

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
        if (!acceptsText(file)) {
            return;
        }

        // Ask the tabs, not a remembered path: currentFile survives its tab being
        // closed, and trusting it here left the file impossible to reopen.
        if (editor.findTabForFile(file) != null) {
            if (!isVisible()) {
                setVisible(true);
            }
            toFront();
            editor.loadFile(file);
            editor.requestFocusInWindow();
            return;
        }

        // Load the new target resource content into the view pipeline
        editor.loadFile(file);
        setTitle("SphereCoder - " + file.getName());

        // Bring the window view forward to focus the freshly reloaded asset
        if (!isVisible()) {
            setVisible(true);
        }
        toFront();
        editor.requestFocusInWindow();
    }

    /**
     * Opens a file in the split group beside the active one (a new group on
     * the right when there is only one), keeping the active editor in view.
     */
    public void openFileToSide(File file) {
        if (!acceptsText(file)) {
            return;
        }
        if (!isVisible()) {
            setVisible(true);
        }
        toFront();
        editor.openFileToSide(file);
    }

    /**
     * Every explorer, console and command that opens a file lands here, so an
     * image or a .root file is turned away once rather than in each of them.
     */
    private static boolean acceptsText(File file) {
        if (file == null || !file.exists()) {
            return false;
        }
        if (com.sphere.components.imaging.ImageFileIO.isImage(file)) {
            com.sphere.ui.ImageEditorFrame.show(file);
            return false;
        }
        if (com.sphere.components.rootview.RootFile.isRootFile(file)) {
            com.sphere.ui.RootViewerFrame.show(file);
            return false;
        }
        if (file.length() > com.sphere.components.editor.EditorFileIO.MAX_OPEN_BYTES) {
            com.sphere.components.editor.LargeFileViewer.show(file);
            return false;
        }
        return true;
    }

    /**
     * Accesses the underlying editor panel instance
     */
    public SphereCoder getEditor() {
        return editor;
    }
}
