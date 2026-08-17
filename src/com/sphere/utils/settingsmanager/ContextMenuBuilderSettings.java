package com.sphere.utils.settingsmanager;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;

import javax.swing.*;
import java.awt.*;
import java.util.function.BiFunction;

public final class ContextMenuBuilderSettings {

    private ContextMenuBuilderSettings() {}

    public static JPopupMenu create(SettingsEditorPanel panel, SettingsTreeNode node) {

        JPopupMenu menu = new JPopupMenu() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                ThemePalette palette = ThemeManager.getCurrentPalette();
                g2.setColor(palette.getBackgroundSurface().brighter());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);

                g2.setColor(palette.getPopupBorder());
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);

                g2.dispose();
            }
        };

        menu.setOpaque(false);
        menu.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        BiFunction<String, Runnable, JMenuItem> makeItem = (label, action) -> {
            JMenuItem it = new JMenuItem(label) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                    ThemePalette palette = ThemeManager.getCurrentPalette();

                    // Only paint the custom rounded selection background
                    if (getModel().isArmed() || getModel().isSelected()) {
                        g2.setColor(palette.getButtonPressed());
                        g2.fillRoundRect(4, 2, getWidth() - 8, getHeight() - 4, 6, 6);
                    }
                    g2.dispose();
                    
                    // Let Swing handle text rendering over our custom shape natively
                    super.paintComponent(g);
                }
            };

            ThemePalette palette = ThemeManager.getCurrentPalette();
            it.setOpaque(false);
            it.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
            it.setForeground(palette.getTextPrimary());
            it.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16)); 
            it.addActionListener(e -> action.run());
            return it;
        };

        // Context-aware menu building translated into U.S. English
        switch (node.getType()) {
            case ROOT -> menu.add(makeItem.apply("Add Category", panel::addCategory));
            case CATEGORY -> {
                menu.add(makeItem.apply("Add Key", () -> panel.addKey(node)));
                menu.add(makeItem.apply("Rename Category", () -> panel.renameCategory(node)));
                menu.add(makeItem.apply("Delete Category", () -> panel.deleteCategory(node)));
            }
            case KEY -> {
                menu.add(makeItem.apply("Rename Key", () -> panel.renameKey(node)));
                menu.add(makeItem.apply("Delete Key", () -> panel.deleteKey(node)));
            }
        }

        return menu;
    }
}
