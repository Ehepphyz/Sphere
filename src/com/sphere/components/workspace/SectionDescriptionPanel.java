package com.sphere.components.workspace;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicTextAreaUI;
import java.awt.*;

/**
 * Interactive input configuration layout capturing, measuring, and summarizing 
 * abstract scientific profiles or high-level descriptive project outlines.
 */
public class SectionDescriptionPanel extends JPanel {

    private final JTextArea txtDescription;
    private final JLabel lblWordCount;
    private final JButton btnAutoSummary;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    public SectionDescriptionPanel(ProjectManifest manifest) {
        setLayout(new BorderLayout(0, 6));
        setBorder(new EmptyBorder(8, 8, 8, 8));
        setOpaque(false);

        // Core text panel initialization
        txtDescription = new JTextArea(
                manifest != null && manifest.description != null ? manifest.description : "",
                2, 24
        );
        txtDescription.setLineWrap(true);
        txtDescription.setWrapStyleWord(true);
        txtDescription.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        txtDescription.setForeground(palette.getTextPrimary());
        txtDescription.setCaretColor(palette.getAccent());
        txtDescription.setOpaque(false); // Let the custom UI paint the background
        txtDescription.putClientProperty("JTextArea.placeholderText", "Enter a concise description detailing your core research workflow parameters...");

        // Inject custom UI layer to replicate the rounded flat style of your fields
        txtDescription.setUI(new BasicTextAreaUI() {
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
                int arc = 8; // Match your global field roundings

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

        // Safe inner padding so text doesn't overflow over the rounded corners
        txtDescription.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

        JScrollPane scrollPane = new JScrollPane(txtDescription);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);

        // Footer telemetry layout block
        lblWordCount = new JLabel("Words: 0");
        lblWordCount.setFont(FontLoader.getGlobalFont(Font.PLAIN, 11));
        lblWordCount.setForeground(palette.getTextSecondary());

        btnAutoSummary = new JButton("Generate Abstract Summary");
        btnAutoSummary.setFont(FontLoader.getGlobalFont(Font.PLAIN, 11));
        btnAutoSummary.setForeground(palette.getTextPrimary());
        btnAutoSummary.setBackground(palette.getButtonHover());
        btnAutoSummary.setFocusPainted(false);
        btnAutoSummary.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnAutoSummary.addActionListener(e -> autoSummarize());

        JPanel footerPanel = new JPanel(new BorderLayout());
        footerPanel.setOpaque(false);
        footerPanel.add(lblWordCount, BorderLayout.WEST);
        footerPanel.add(btnAutoSummary, BorderLayout.EAST);

        add(scrollPane, BorderLayout.CENTER);
        add(footerPanel, BorderLayout.SOUTH);

        // Bind reactive document listeners for real-time telemetry metrics updates
        txtDescription.getDocument().addDocumentListener(new SimpleDocumentListener(this::updateWordCount));
        updateWordCount();
    }

    private void updateWordCount() {
        String text = txtDescription.getText().trim();
        int count = text.isEmpty() ? 0 : text.split("\\s+").length;
        lblWordCount.setText("Words: " + count);
    }

    /**
     * Extracts the first coherent structural sentence to create an isolated executive summary block.
     */
    private void autoSummarize() {
        String text = txtDescription.getText().trim();
        if (text.isEmpty()) return;

        // Smart punctuation boundary regex mapping that preserves inner decimal/version values (e.g., v1.0)
        String[] sentences = text.split("(?<=[.!?])\\s+(?=[A-Z])|(?<=[.!?])$");
        
        if (sentences.length > 0 && !sentences[0].isBlank()) {
            String summary = sentences[0].trim();
            // Secure terminal punctuation character formatting safety locks
            if (!summary.endsWith(".") && !summary.endsWith("!") && !summary.endsWith("?")) {
                summary += ".";
            }
            txtDescription.setText(summary);
            updateWordCount();
        }
    }

    public void setText(String text) {
        txtDescription.setText(text != null ? text.trim() : "");
        updateWordCount();
    }

    /**
     * Commits layout form updates back into the specified manifest reference structure.
     * @param manifest the metadata target context to update.
     */
    public void apply(ProjectManifest manifest) {
        if (manifest != null) {
            manifest.description = txtDescription.getText().trim();
        }
    }

    /**
     * Attaches reactive change verification hooks to intercept typing updates instantly.
     */
    public void addRealtimeValidation(Runnable callback) {
        if (callback != null) {
            txtDescription.getDocument().addDocumentListener(new SimpleDocumentListener(callback));
        }
    }

    // --- Encapsulation Safe Getters ---

    public String getDescriptionInput() {
        return txtDescription.getText().trim();
    }
}
