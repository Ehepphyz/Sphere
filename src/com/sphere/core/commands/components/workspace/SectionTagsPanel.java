package com.sphere.components.workspace;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.*;
import java.util.List;

import com.sphere.utils.IconManager;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;

/**
 * Interactive tag editor panel with pill-shaped tag chips
 * and non-blocking auto-complete suggestions.
 */
public class SectionTagsPanel extends JPanel {

    private final JPanel tagContainer;
    private final JTextField inputField;
    private final Set<String> currentTags = new LinkedHashSet<>();
    private final JPopupMenu suggestionsPopup;

    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    private static final List<String> SUGGESTED_TAGS = List.of(
            "analysis", "ntuples", "simulation", "geant4", "root", "madgraph",
            "cms", "atlas", "lhcb", "belle2", "ml", "ai", "tracking", "calibration",
            "detector", "reconstruction", "visualization", "workflow"
    );

    public SectionTagsPanel(ProjectManifest manifest) {

        // Main panel styling
        setLayout(new BorderLayout(0, 8));
        setBorder(new EmptyBorder(8, 8, 8, 8));
        setOpaque(false);

        // Tag container panel
        tagContainer = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        tagContainer.setOpaque(true);
        tagContainer.setBackground(palette.getBackgroundSurface());

        // Scroll wrapper for large tag lists
        JScrollPane scrollPane = new JScrollPane(tagContainer);
        scrollPane.setBorder(null);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

        scrollPane.getViewport().setOpaque(true);
        scrollPane.getViewport().setBackground(palette.getBackgroundSurface());
        scrollPane.setOpaque(false);

        // Fixed height for tag area
        Dimension scrollSize = new Dimension(200, 75);
        scrollPane.setPreferredSize(scrollSize);
        scrollPane.setMaximumSize(scrollSize);

        // Input field for new tags
        inputField = new JTextField();
        inputField.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        inputField.putClientProperty("JTextField.placeholderText", "Type a tag and press Enter...");

        // Apply theme styling matching global flat rounded field geometries
        inputField.setOpaque(true);
        inputField.setBackground(palette.getBackgroundTrack());
        inputField.setForeground(palette.getTextPrimary());
        inputField.setCaretColor(palette.getAccent());
        inputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(palette.getBorder(), 1, true),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));

        // Selection colors
        inputField.setSelectionColor(palette.getAccent());
        inputField.setSelectedTextColor(palette.getTextPrimary());
        inputField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        // Load existing tags
        if (manifest != null && manifest.tags != null) {
            currentTags.addAll(manifest.tags);
        }

        refreshTags();

        // Auto-complete popup configuration
        suggestionsPopup = new JPopupMenu();
        suggestionsPopup.setFocusable(false);

        inputField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            suggestionsPopup.removeAll();
            String query = inputField.getText().trim().toLowerCase();

            if (query.isEmpty()) {
                suggestionsPopup.setVisible(false);
                return;
            }

            int matches = 0;
            for (String suggestion : SUGGESTED_TAGS) {
                if (suggestion.startsWith(query) && !currentTags.contains(suggestion)) {
                    JMenuItem item = new JMenuItem(suggestion);
                    item.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
                    item.addActionListener(e -> {
                        addTag(suggestion);
                        inputField.setText("");
                        suggestionsPopup.setVisible(false);
                    });
                    suggestionsPopup.add(item);
                    matches++;
                }
            }

            if (matches > 0 && inputField.isShowing()) {
                suggestionsPopup.show(inputField, 0, inputField.getHeight());
                inputField.requestFocusInWindow();
            } else {
                suggestionsPopup.setVisible(false);
            }
        }));

        // Add custom tag on Enter
        inputField.addActionListener(e -> {
            String clean = inputField.getText().trim().toLowerCase();
            if (!clean.isEmpty()) {
                addTag(clean);
                inputField.setText("");
                suggestionsPopup.setVisible(false);
            }
        });

        // Label + input field wrapper
        JPanel inputWrapper = new JPanel();
        inputWrapper.setLayout(new BoxLayout(inputWrapper, BoxLayout.Y_AXIS));
        inputWrapper.setOpaque(true);
        inputWrapper.setBackground(palette.getBackgroundSurface());

        JLabel inputLabel = new JLabel("Add Personal Tag:");
        inputLabel.setFont(FontLoader.getGlobalFont(Font.PLAIN, 11));
        inputLabel.setForeground(palette.getTextPrimary());
        inputLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        inputField.setAlignmentX(Component.LEFT_ALIGNMENT);

        inputWrapper.add(inputLabel);
        inputWrapper.add(Box.createVerticalStrut(4));
        inputWrapper.add(inputField);

        // Add components to main layout
        add(scrollPane, BorderLayout.CENTER);
        add(inputWrapper, BorderLayout.SOUTH);

        refreshTags();
    }

    private void refreshTags() {
        tagContainer.removeAll();
        for (String tag : currentTags) {
            tagContainer.add(createTagChip(tag));
        }
        tagContainer.revalidate();
        tagContainer.repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        refreshTags();
    }

    /**
     * Creates a pill-shaped tag chip using theme definitions.
     */
    private JPanel createTagChip(String tag) {
        JPanel chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                g2.setColor(palette.getTagsCellBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);

                g2.setColor(palette.getTagsCellBorder());
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);

                g2.dispose();
            }
        };

        chip.setOpaque(false);
        chip.setBorder(new EmptyBorder(4, 8, 4, 4));

        JLabel label = new JLabel(tag);
        label.setFont(FontLoader.getGlobalFont(Font.PLAIN, 11));
        label.setForeground(palette.getTagsCellText());

        JButton close = new JButton(IconManager.getIcon("close.png"));
        close.setBorderPainted(false);
        close.setContentAreaFilled(false);
        close.setFocusPainted(false);
        close.setOpaque(false);
        close.setPreferredSize(new Dimension(16, 16));
        close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        close.addActionListener(e -> {
            currentTags.remove(tag);
            refreshTags();
        });

        chip.add(label);
        chip.add(close);
        return chip;
    }

    public void apply(ProjectManifest manifest) {
        if (manifest != null) {
            manifest.tags = new ArrayList<>(currentTags);
        }
    }

    public void clear() {
        currentTags.clear();
        refreshTags();
    }

    public void setTags(List<String> list) {
        currentTags.clear();
        if (list != null) {
            currentTags.addAll(list);
        }
        refreshTags();
    }

    public void addTag(String tag) {
        if (tag != null && !tag.strip().isEmpty()) {
            currentTags.add(tag.strip());
            refreshTags();
        }
    }

    public void addTags(Collection<String> tags) {
        if (tags != null) {
            currentTags.addAll(tags);
            refreshTags();
        }
    }

    public void addRealtimeValidation(Runnable callback) {
        inputField.getDocument().addDocumentListener(new SimpleDocumentListener(callback));
    }
}
