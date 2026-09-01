package com.sphere.components.workspace;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicTextAreaUI;
import java.awt.*;

/**
 * Editorial text container panel used to capture, review, and persist markdown summaries, 
 * operational logging goals, or research notes for the active project.
 */
public class SectionNotesPanel extends JPanel {

    private final JTextArea txtNotes;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    public SectionNotesPanel(ProjectManifest manifest) {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(8, 8, 8, 8));
        setOpaque(false);

        // Core text canvas configuration
        txtNotes = new JTextArea(
                manifest != null && manifest.notes != null ? manifest.notes : "",
                2, 24
        );
        txtNotes.setLineWrap(true);
        txtNotes.setWrapStyleWord(true);
        txtNotes.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        txtNotes.setForeground(palette.getTextPrimary());
        txtNotes.setCaretColor(palette.getAccent());
        txtNotes.setOpaque(false); // Allowed our custom UI background track to draw safely
        
        // Native flat placeholder indicator (Supported by look-and-feels like FlatLaf)
        txtNotes.putClientProperty("JTextArea.placeholderText", "Enter project logs, research abstracts, or documentation notes here...");

        // Inject custom UI layer to replicate the rounded flat style across all fields
        txtNotes.setUI(new BasicTextAreaUI() {
            private boolean isFocused = false;

            @Override
            protected void installListeners() {
                super.installListeners();
                getComponent().addFocusListener(new java.awt.event.FocusListener() {
                    @Override 
                    public void focusGained(java.awt.event.FocusEvent e) { 
                        isFocused = true; 
                        getComponent().repaint(); 
                    }
                    @Override 
                    public void focusLost(java.awt.event.FocusEvent e) { 
                        isFocused = false; 
                        getComponent().repaint(); 
                    }
                });
            }

            @Override
            public void update(Graphics g, JComponent c) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int width = c.getWidth();
                int height = c.getHeight();
                int arc = 8; // Matches your global field specifications perfectly

                // Fill custom track background
                g2.setColor(palette.getBackgroundTrack());
                g2.fillRoundRect(0, 0, width, height, arc, arc);

                // Draw context-aware focus borders
                if (isFocused) {
                    g2.setColor(palette.getAccent());
                    g2.setStroke(new BasicStroke(1.5f));
                } else {
                    g2.setColor(palette.getBorder());
                    g2.setStroke(new BasicStroke(1.0f));
                }
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc);
                g2.dispose();

                // Paint the underlying text layer over our geometry
                paint(g, c);
            }
        });

        // Safe inner padding so the typing cursor doesn't clip with the rounded framing
        txtNotes.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

        // Scrollpane wrapper with flat custom framing
        JScrollPane scrollPane = new JScrollPane(txtNotes);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        
        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * Commits the edited documentation text notes back into the specified manifest instance structure.
     * @param manifest the metadata configuration blueprint context to update.
     */
    public void apply(ProjectManifest manifest) {
        if (manifest != null) {
            manifest.notes = txtNotes.getText().trim();
        }
    }

    /**
     * Attaches reactive change verification hooks to intercept typing updates instantly.
     */
    public void addRealtimeValidation(Runnable callback) {
        if (callback != null) {
            txtNotes.getDocument().addDocumentListener(new SimpleDocumentListener(callback));
        }
    }

    // --- Encapsulation Safe Getters ---

    public String getNotesInput() {
        return txtNotes.getText().trim();
    }
}
