package com.sphere.components.workspace;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Map;

import com.sphere.utils.IconManager;

/**
 * Diagnostics health center dashboard tracking real-time layout validation issues,
 * flagging configuration anomalies, and routing user input focus on selection events.
 */
public class SectionHealthPanel extends JPanel {

    private final DefaultListModel<HealthItem> model;
    private final JList<HealthItem> list;
    private IssueClickListener listener;

    public interface IssueClickListener {
        void onIssueClicked(String issueKey);
    }

    public SectionHealthPanel() {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(8, 8, 8, 8));
        setOpaque(false);

        model = new DefaultListModel<>();
        list = new JList<>(model);

        list.setCellRenderer(new HealthRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(32); // Generous row height for scanning entries

        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && listener != null) {
                HealthItem item = list.getSelectedValue();
                if (item != null && item.key() != null) {
                    listener.onIssueClicked(item.key());
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(list);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(215, 220, 230)));
        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * Refreshes diagnostic items based on current validation errors.
     * @param issues data map pairing configuration properties with explicit error strings.
     */
    public void update(Map<String, String> issues) {
        // Clear list selection state entirely to protect callback loops before flushing indices
        list.clearSelection();
        model.clear();

        if (issues == null || issues.isEmpty()) {
            model.addElement(new HealthItem(
                    null, 
                    "No validation problems detected.", 
                    IconManager.getIcon("check.png"),
                    new Color(45, 140, 90)
            ));
            return;
        }

        for (Map.Entry<String, String> entry : issues.entrySet()) {
            model.addElement(new HealthItem(
                    entry.getKey(),
                    entry.getValue(), // Display clean validation descriptions directly
                    IconManager.getIcon("warning.png"),
                    new Color(185, 50, 50)
            ));
        }
    }

    public void setIssueClickListener(IssueClickListener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------------------
    // Internal Components & Renderers
    // ------------------------------------------------------------------------

    /**
     * Data record grouping validated target property metadata rules cleanly.
     */
    private record HealthItem(String key, String text, Icon icon, Color labelColor) {
        @Override
        public String toString() { 
            return text; 
        }
    }

    /**
     * Polished item cell renderer managing text padding, background rows, and contextual alerting states.
     */
    private static class HealthRenderer extends DefaultListCellRenderer {

        public HealthRenderer() {
            setOpaque(true);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
        ) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

            if (value instanceof HealthItem item) {
                setText(item.text());
                setIcon(item.icon());
                
                // Establish balanced horizontal inner-row boundaries
                setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

                if (isSelected) {
                    // Match native operational themes gracefully instead of hardcoding raw colors
                    setBackground(list.getSelectionBackground());
                    setForeground(list.getSelectionForeground());
                } else {
                    setBackground(index % 2 == 0 ? new Color(250, 252, 255) : Color.WHITE);
                    setForeground(item.labelColor());
                }
            }

            return this;
        }
    }
}
