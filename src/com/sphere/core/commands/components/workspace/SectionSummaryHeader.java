package com.sphere.components.workspace;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import com.sphere.utils.AppLogger;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;

/**
 * Header dashboard UI panel summarizing project configurations,
 * active research scopes, and explicit timeline metadata records.
 */
public class SectionSummaryHeader extends JPanel {

    private final JLabel titleLabel;
    private final JLabel subtitleLabel;
    private final JLabel timestampLabel;

    // Localized U.S. English pattern matching standard workspace conventions: e.g., 06/11/2026 8:54 PM
    private static final DateTimeFormatter US_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yyyy h:mm a");
    private static final String DEFAULT_FALLBACK = "unknown";

    public SectionSummaryHeader(ProjectManifest manifest) {

        ThemePalette palette = ThemeManager.getCurrentPalette();

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new EmptyBorder(12, 12, 12, 12));

        // Uniform background for the entire header
        setOpaque(true);
        setBackground(palette.getBackgroundSurface());

        // Core visual component declarations
        titleLabel = new JLabel();
        titleLabel.setForeground(palette.getAccent());
        titleLabel.setFont(FontLoader.getGlobalFont(Font.BOLD, 20));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        subtitleLabel = new JLabel();
        subtitleLabel.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        subtitleLabel.setForeground(palette.getTextPrimary());
        subtitleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        timestampLabel = new JLabel();
        timestampLabel.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        timestampLabel.setForeground(palette.getTextSecondary());
        timestampLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Construct cleaner visual layout distribution
        add(titleLabel);
        add(Box.createVerticalStrut(4));
        add(subtitleLabel);
        add(Box.createVerticalStrut(8));

        // Polished flat horizontal divider rule line separator
        JSeparator divider = new JSeparator();
        divider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        divider.setForeground(palette.getBorder());
        divider.setBackground(palette.getBorder());
        divider.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(divider);

        add(Box.createVerticalStrut(6));
        add(timestampLabel);

        update(manifest);
    }

    private String safe(String input) {
        return (input == null || input.isBlank()) ? DEFAULT_FALLBACK : input.strip();
    }

    private String formatDate(String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.isBlank()) {
            return DEFAULT_FALLBACK;
        }

        try {
            Instant instant = Instant.parse(isoTimestamp);
            ZonedDateTime zdt = instant.atZone(ZoneId.systemDefault());
            return zdt.format(US_FORMATTER);
        } catch (Exception e) {
            return isoTimestamp; // Maintain raw string fallback if parsing fails
        }
    }

    /**
     * Re-hydrates layout contents with updated metadata parameters from the active manifest.
     * @param manifest the revised project configuration manifest instance.
     */
    public void update(ProjectManifest manifest) {
        if (manifest == null) {
            titleLabel.setText("No Active Project Selected");
            subtitleLabel.setText("");
            timestampLabel.setText("");
            return;
        }

        titleLabel.setText(safe(manifest.projectName));

        String experiment = safe(manifest.experiment);
        String preset = safe(manifest.presetVersion);
        String type = safe(manifest.projectType);

        subtitleLabel.setText(String.format("%s — Preset %s — %s", experiment, preset, type));

        String createdDate = formatDate(manifest.createdAt);
        String modifiedDate = formatDate(manifest.modifiedAt);

        timestampLabel.setText(String.format("Created: %s   |   Modified: %s", createdDate, modifiedDate));
    }
}
