package com.sphere.components.workspace;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.Objects;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;

/**
 * Custom container layout providing fluid collapsible and expandable behavior.
 * Designed to cleanly organize workspace telemetry subsections and form control matrices.
 */
public class CollapsibleSectionPanel extends JPanel {

    private final JPanel contentWrapperPanel;
    private final JButton sectionToggleButton;
    private final String sectionTitleText;
    private boolean isPanelExpanded = true;

    private static final String ICON_EXPANDED = "▼";
    private static final String ICON_COLLAPSED = "►";

    /**
     * Initializes a collapsible container subsection.
     * @param title Immutable header label to display next to the expansion state arrow indicator.
     * @param content Sub-component or inner view hierarchy container to be managed.
     */
    public CollapsibleSectionPanel(String title, JComponent content) {

        ThemePalette palette = ThemeManager.getCurrentPalette();

        this.sectionTitleText = Objects.requireNonNull(title, "Section header title string text cannot be null.").trim();
        Objects.requireNonNull(content, "Inner section layout target view content node cannot be null.");

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(2, 2, 6, 2));

        // Uniform background for the entire section
        setOpaque(true);
        setBackground(palette.getBackgroundSurface());

        // 1. Configure Header Navigation Node Element
        sectionToggleButton = new JButton(String.format("%s   %s", ICON_EXPANDED, sectionTitleText));
        sectionToggleButton.setFont(FontLoader.getGlobalFont(Font.BOLD, 12));
        sectionToggleButton.setForeground(palette.getTextPrimary());
        sectionToggleButton.setHorizontalAlignment(SwingConstants.LEFT);

        // Flat styling
        sectionToggleButton.setBorderPainted(false);
        sectionToggleButton.setFocusPainted(false);
        sectionToggleButton.setContentAreaFilled(false);
        sectionToggleButton.setOpaque(false);
        sectionToggleButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        sectionToggleButton.addActionListener(this::handleToggleActionEvent);
        add(sectionToggleButton, BorderLayout.NORTH);

        // 2. Configure Wrapped Sub-Layout Content Node
        contentWrapperPanel = new JPanel(new BorderLayout());
        contentWrapperPanel.setOpaque(true);
        contentWrapperPanel.setBackground(palette.getBackgroundSurface());
        contentWrapperPanel.setBorder(new EmptyBorder(4, 18, 4, 4)); // Intentional structural tab offset gap
        contentWrapperPanel.add(content, BorderLayout.CENTER);

        add(contentWrapperPanel, BorderLayout.CENTER);
    }

    private void handleToggleActionEvent(ActionEvent event) {
        isPanelExpanded = !isPanelExpanded;

        if (isPanelExpanded) {
            sectionToggleButton.setText(String.format("%s   %s", ICON_EXPANDED, sectionTitleText));
            contentWrapperPanel.setVisible(true);
        } else {
            sectionToggleButton.setText(String.format("%s   %s", ICON_COLLAPSED, sectionTitleText));
            contentWrapperPanel.setVisible(false);
        }

        // Force parent layout re-evaluations to eliminate blank gaps or overlapping view ports
        if (getParent() instanceof JComponent parentContainer) {
            parentContainer.revalidate();
            parentContainer.repaint();
        } else {
            revalidate();
            repaint();
        }
    }

    /**
     * Accesses the active visual state of the collapsible panel structure.
     * @return true if the content component matrix is open and visible.
     */
    public boolean isExpanded() {
        return isPanelExpanded;
    }
}

