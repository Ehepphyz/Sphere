package com.sphere.components.fileexplorerincludes;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.utils.IconManager;
import com.sphere.fonts.FontLoader;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * Custom grid-based cell renderer featuring rounded highlight selections,
 * integrated hover tracking feedback, and vertical icon-on-top layout architecture.
 * Fully isolated from default Look and Feel background clearing side effects.
 */
public class FlatFileRenderer extends JPanel implements ListCellRenderer<File> {

    private final JLabel iconLabel;
    private final JLabel textLabel;

    private Color bgNormal;
    private Color bgSelected;
    private Color bgHover;
    private Color focusRingColor;

    private boolean isHovered;
    private boolean isFocused;
    private boolean isSelected;

    /**
     * Constructs a new flat file renderer container configured for grid display layouts.
     */
    public FlatFileRenderer() {
        // Vertical layout to place the file icon squarely above the text label
        setLayout(new BorderLayout(0, 6));
        
        // MUST be false to allow custom background painting with transparency
        setOpaque(false); 

        // Balanced padding for grid cells matching modern desktop standards
        setBorder(BorderFactory.createEmptyBorder(10, 8, 10, 8));

        iconLabel = new JLabel();
        textLabel = new JLabel();

        iconLabel.setOpaque(false);
        textLabel.setOpaque(false);

        // Center align contents inside the tile grid wrapper
        iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
        textLabel.setHorizontalAlignment(SwingConstants.CENTER);

        add(iconLabel, BorderLayout.CENTER);
        add(textLabel, BorderLayout.SOUTH);

        // Enforce safe default sizing bounds for grid-wrapping cells
        setPreferredSize(new Dimension(110, 85));
    }

    @Override
    public Component getListCellRendererComponent(
            JList<? extends File> list, File file, int index,
            boolean isSelected, boolean cellHasFocus) {

        ThemePalette palette = ThemeManager.getCurrentPalette();

        this.isSelected = isSelected;
        this.isFocused = cellHasFocus;

        /* Extract the active mouse-over state safely from the component instance properties */
        Object hoverProp = list.getClientProperty("hoverIndex");
        this.isHovered = (hoverProp instanceof Integer && (Integer) hoverProp == index);

        /* * Resolve background colors. 
         * bgNormal is kept null to prevent stacking opaque blocks that cause dark/black halos.
         */
        bgNormal = null; 
        bgSelected = palette.getButtonPressed();
        bgHover = palette.getMouseHover();
        focusRingColor = palette.getAccent();

        /* Process metadata text and file-type icons */
        if (file != null) {
            String name = file.getName();
            textLabel.setText(name);
            textLabel.setFont(FontLoader.getGlobalFont(Font.PLAIN, 11));
            textLabel.setToolTipText(name); // Fallback full display tooltip

            // Using single-argument methods to strictly match the valid IconManager signatures
            iconLabel.setIcon(
                file.isDirectory()
                    ? IconManager.getIcon("folder.png")
                    : IconManager.getIconForFile(file.getName())
            );
        }

        /* Dynamically alter typography colors to maintain accessible contrast */
        if (isSelected) {
            textLabel.setForeground(palette.getTextWhite());
        } else {
            textLabel.setForeground(palette.getTextPrimary());
        }

        // ABSOLUTE OVERRIDE: Maintain explicit margins across dynamic layout cycles
        setBorder(BorderFactory.createEmptyBorder(10, 8, 10, 8));

        return this;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();

        /* Enable anti-aliasing for professional, smooth rounded shapes */
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        /* Calculate current structural state color target */
        Color bg = bgNormal;
        if (isSelected) {
            bg = bgSelected;
        } else if (isHovered) {
            bg = bgHover;
        }

        int arc = 8; // Modern standard curvature index radius

        /* Paint rounded backplate container track */
        if (bg != null) {
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
        }

        /* * Paint high-fidelity focus indicator ring matching IntelliJ workflows.
         * Only render the focus ring on components that are actively highlighted or selected 
         * to avoid drawing loose ghost boxes/dark outlines around default cells.
         */
        if (isFocused && isSelected && focusRingColor != null) {
            g2.setColor(focusRingColor);
            g2.setStroke(new BasicStroke(1.5f));
            // Inset path slightly by 1 pixel to eliminate clipping on anti-aliased viewport edges
            g2.drawRoundRect(1, 1, getWidth() - 2, getHeight() - 2, arc, arc);
        }

        g2.dispose();

        /* * IMPORTANT: Do NOT invoke super.paintComponent(g).
         * Doing so clears the background structure and completely erases custom highlight transparency.
         */
        paintChildren(g);
    }
}