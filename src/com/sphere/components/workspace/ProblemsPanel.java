package com.sphere.components.workspace;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Map;
import java.util.Objects;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;
import com.sphere.utils.IconManager;

/**
 * Diagnostic tracking sidebar view panel rendering ongoing validation anomalies,
 * experiment profile mismatches, or system rule exceptions in real time.
 */
public class ProblemsPanel extends JPanel {

    private final DefaultListModel<ProblemItem> diagnosticListModel;
    private final JList<ProblemItem> UIListContentNode;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    public ProblemsPanel() {
        setLayout(new BorderLayout());
        setBackground(palette.getBackgroundSurface());
        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createMatteBorder(1, 1, 1, 1, palette.getColFillBlue()), 
                "Validation Warnings", 
                javax.swing.border.TitledBorder.LEFT, 
                javax.swing.border.TitledBorder.TOP, 
                FontLoader.getGlobalFont(Font.PLAIN, 12)
        ));

        diagnosticListModel = new DefaultListModel<>();
        UIListContentNode = new JList<>(diagnosticListModel);

        UIListContentNode.setCellRenderer(new ProblemAdaptiveCellRenderer());
        UIListContentNode.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Enforce balanced proportional width requirements for scannability
        setPreferredSize(new Dimension(320, 0));
        setMinimumSize(new Dimension(240, 0));

        JScrollPane scrollPane = new JScrollPane(UIListContentNode);
        scrollPane.setBorder(null);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * Refreshes the active diagnostic workspace exception list.
     * @param validationIssues map housing rule keys tied to context descriptive issue strings.
     */
    public void update(Map<String, String> validationIssues) {
        diagnosticListModel.clear();

        if (validationIssues == null || validationIssues.isEmpty()) {
            diagnosticListModel.addElement(new ProblemItem(
                    "All diagnostic telemetry items conform fully.",
                    IconManager.getIcon("check.png")
            ));
            return;
        }

        for (Map.Entry<String, String> issueEntry : validationIssues.entrySet()) {
            String structuredText = String.format("[%s]: %s", issueEntry.getKey(), issueEntry.getValue());
            diagnosticListModel.addElement(new ProblemItem(
                    structuredText,
                    IconManager.getIcon("warning.png")
            ));
        }
    }

    /**
     * Internal metadata tuple carrier structure matching clean data serialization schemas.
     */
    private record ProblemItem(String text, Icon diagnosticIcon) {
        private ProblemItem {
            Objects.requireNonNull(text, "Problem text content details string cannot be null.");
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /**
     * Specialized multi-line text renderer ensuring wrapping metrics adjust properly 
     * inside the layout view matrix without clipping errors.
     */
    private static final class ProblemAdaptiveCellRenderer extends JPanel implements ListCellRenderer<ProblemItem> {

        private final JTextArea textLayoutArea;
        private final JLabel iconDisplayLabel;
        private final ThemePalette palette = ThemeManager.getCurrentPalette();

        public ProblemAdaptiveCellRenderer() {
            setLayout(new BorderLayout(8, 0));
            setBorder(new EmptyBorder(6, 8, 6, 8));
            setOpaque(true);

            iconDisplayLabel = new JLabel();
            iconDisplayLabel.setVerticalAlignment(SwingConstants.TOP);

            textLayoutArea = new JTextArea();
            textLayoutArea.setLineWrap(true);
            textLayoutArea.setWrapStyleWord(true);
            textLayoutArea.setEditable(false);
            textLayoutArea.setFocusable(false);
            textLayoutArea.setOpaque(false);
            textLayoutArea.setBackground(palette.getAccent());
            textLayoutArea.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));

            add(iconDisplayLabel, BorderLayout.WEST);
            add(textLayoutArea, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends ProblemItem> list,
                ProblemItem itemValue,
                int itemIndex,
                boolean isSelected,
                boolean cellHasFocus
        ) {
            textLayoutArea.setText(itemValue.text());
            iconDisplayLabel.setIcon(itemValue.diagnosticIcon());

            // Dynamically synchronize dimensions based on list host bounding rules
            int hostingWidth = list.getWidth() - getInsets().left - getInsets().right - 32;
            textLayoutArea.setSize(new Dimension(Math.max(hostingWidth, 100), 1));

            if (isSelected) {
                setBackground(palette.getBackgroundSurface());
                textLayoutArea.setForeground(list.getSelectionForeground());
            } else {
                setBackground(palette.getBackgroundSurface());
                textLayoutArea.setForeground(list.getForeground());
            }

            return this;
        }
    }
}
