package com.sphere.components.fileexplorerincludes;

import com.sphere.utils.OSValidator;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;

/**
 * Dialog wrapper for the FlatFileChooserPanel component context.
 * Implements standard blocking modal behavior, platform-specific key bindings, and runtime theme syncing.
 */
public class FlatFileChooser extends JDialog {

    public static final int CANCEL_OPTION = 1;
    public static final int APPROVE_OPTION = 0;

    private final FlatFileChooserPanel panel;
    private int resultStatus = CANCEL_OPTION;

    public FlatFileChooser(Window parent, File initialDir) {
        super(parent, "Select File", ModalityType.APPLICATION_MODAL);
        
        setLayout(new BorderLayout());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        // Subtly enhance dialog configurations for modern window environments
        setResizable(true);

        this.panel = new FlatFileChooserPanel(initialDir, this);
        add(this.panel, BorderLayout.CENTER);

        // Modern proportional aspect ratio matching professional desktop software standards
        setSize(850, 400);
        setMinimumSize(new Dimension(640, 400));
        
        if (parent != null) {
            setLocationRelativeTo(parent);
        } else {
            setLocationRelativeTo(null);
        }

        setupKeyboardAccelerators();
    }

    /**
     * Confirms selection choice and releases the blocking modal state.
     */
    public void approveSelection() {
        this.resultStatus = APPROVE_OPTION;
        dispose();
    }

    /**
     * Cancels selection choice and releases the blocking modal state.
     */
    public void cancelSelection() {
        this.resultStatus = CANCEL_OPTION;
        dispose();
    }

    /**
     * Displays the dialog and blocks execution thread cycle until the window is hidden or disposed.
     * * @return The selection status outcome (APPROVE_OPTION or CANCEL_OPTION)
     */
    public int showDialog() {
        this.resultStatus = CANCEL_OPTION; // Reset tracking cycle safely
        setVisible(true); // Blocks execution thread here until window is hidden or disposed
        return resultStatus;
    }

    private void setupKeyboardAccelerators() {
        JRootPane root = getRootPane();
        InputMap inputMap = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = root.getActionMap();

        // 1. Map ESCAPE key to dismiss safely
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeChooser");
        actionMap.put("closeChooser", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cancelSelection();
            }
        });

        // 2. Cross-Platform Window Closure Shortcuts (Ctrl+W or Cmd+W)
        int platformModifier = OSValidator.getPlatformModifier();
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, platformModifier), "shortcutClose");
        actionMap.put("shortcutClose", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cancelSelection();
            }
        });

        // 3. Map ENTER key to execute or confirm context selection
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "approveChooser");
        actionMap.put("approveChooser", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                panel.performOpenAction();
            }
        });
    }

    public File getSelectedFile() {
        return panel.getSelectedFile();
    }

    public java.util.List<File> getSelectedFiles() {
        return panel.getSelectedFiles();
    }
    
    @Override
    public void setVisible(boolean b) {
        if (b && getContentPane() != null) {
            // Guarantee full look and feel synchronization before screen projection
            SwingUtilities.updateComponentTreeUI(this);
        }
        super.setVisible(b);
    }
}
