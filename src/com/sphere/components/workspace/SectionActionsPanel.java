package com.sphere.components.workspace;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;

/**
 * Control action footer row housing global confirmation and cancellation 
 * operation targets for the configuration view management layouts.
 */
public class SectionActionsPanel extends JPanel {

    private final JButton btnApply;
    private final JButton btnCancel;

    public SectionActionsPanel() {
        setLayout(new FlowLayout(FlowLayout.RIGHT, 10, 6));
        setBorder(new EmptyBorder(4, 4, 4, 4));
        setOpaque(false);

        // Core component declarations
        btnApply = new JButton("Apply Changes");
        btnCancel = new JButton("Cancel");

        // UI polish: Set clear interactive hand cursors for immediate mouse click feedback
        Cursor handCursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        btnApply.setCursor(handCursor);
        btnCancel.setCursor(handCursor);

        // Remove annoying focus rings from standard button facades
        btnApply.setFocusPainted(false);
        btnCancel.setFocusPainted(false);

        add(btnCancel);
        add(btnApply);
    }

    /**
     * Attaches an execution listener to intercept Apply action events.
     * @param listener callback task to execute upon confirmation selection.
     */
    public void onApply(ActionListener listener) {
        if (listener != null) {
            btnApply.addActionListener(listener);
        }
    }

    /**
     * Attaches an execution listener to intercept Cancel action events.
     * @param listener callback task to execute upon cancellation selection.
     */
    public void onCancel(ActionListener listener) {
        if (listener != null) {
            btnCancel.addActionListener(listener);
        }
    }

    /**
     * Toggles the interaction permissions configuration array across footer action components simultaneously.
     * @param enabled true if actions are available, false if modifications should lock.
     */
    public void setActionsEnabled(boolean enabled) {
        btnApply.setEnabled(enabled);
        btnCancel.setEnabled(enabled);
    }

    /**
     * Sets the "Apply Changes" button as the default click action for the active window layout context.
     */
    public void assignDefaultWindowAction() {
        SwingUtilities.invokeLater(() -> {
            JRootPane rootPane = SwingUtilities.getRootPane(this);
            if (rootPane != null) {
                rootPane.setDefaultButton(btnApply);
            }
        });
    }

    // --- Encapsulation Safe Getters ---

    public JButton getApplyButton() {
        return btnApply;
    }

    public JButton getCancelButton() {
        return btnCancel;
    }
}
