package com.sphere.theme;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Central theme manager for Sphere.
 * Handles real-time runtime theme selection and UI synchronization.
 */
public final class ThemeManager {

    private ThemeManager() {}

    public enum ThemeVariant {
        DARK,
        LIGHT
    }

    private static ThemePalette currentPalette = ThemePaletteDark.INSTANCE;

    public static ThemePalette getCurrentPalette() {
        return currentPalette;
    }

    public static void applyDarkTheme() {
        applyTheme(ThemeVariant.DARK);
    }

    public static void applyLightTheme() {
        applyTheme(ThemeVariant.LIGHT);
    }

    public static void applyTheme(ThemeVariant variant) {
        try {
            LookAndFeel currentLaf = UIManager.getLookAndFeel();
            if (currentLaf == null || !"Nimbus".equals(currentLaf.getName())) {
                for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                    if ("Nimbus".equals(info.getName())) {
                        UIManager.setLookAndFeel(info.getClassName());
                        break;
                    }
                }
            }

            currentPalette = (variant == ThemeVariant.DARK)
                    ? ThemePaletteDark.INSTANCE
                    : ThemePaletteLight.INSTANCE;

            ThemeDefaults.apply(currentPalette);

            // --- HYBRID SYSTEM LIVE PROPAGATION ---
            for (Window window : Window.getWindows()) {
                // 1. Force the structural components to pull new properties
                SwingUtilities.updateComponentTreeUI(window);
                
                // 2. CRITICAL HARD-LOCK: Scan and visually enforce button states manually
                enforceButtonStyles(window, currentPalette);
                
                window.validate();
                window.repaint();
            }

        } catch (Exception ignored) {
            try {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                for (Window window : Window.getWindows()) {
                    SwingUtilities.updateComponentTreeUI(window);
                }
            } catch (Exception ignored2) {}
        }
    }

    /**
     * Deep-traverses the entire structural window container tree to target and fix 
     * non-responsive buttons or native dialog leftovers that ignore ThemeDefaults.
     */
    private static void enforceButtonStyles(Container container, ThemePalette palette) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof AbstractButton) {
                AbstractButton btn = (AbstractButton) comp;
                
                // Reset basic properties
                btn.setOpaque(false);
                btn.setContentAreaFilled(false);
                btn.setFocusPainted(false);
                btn.setRolloverEnabled(true);
                btn.setForeground(palette.getTextPrimary());
                
                // Force an explicit IntelliJ visible border outline container
                btn.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(palette.getBorder(), 1),
                        BorderFactory.createEmptyBorder(4, 12, 4, 12)
                ));

                // Clear out existing dynamic background painters that might block transparency overrides
                if (btn instanceof JButton) {
                    btn.setBackground(palette.getButtonBase());
                }

                // Remove old mouse listeners to prevent stacking triggers on theme swap
                for (java.awt.event.MouseListener ml : btn.getMouseListeners()) {
                    if (ml.getClass().getName().contains("SPButtonHook")) {
                        btn.removeMouseListener(ml);
                    }
                }

                // Add an atomic hover state listener that manual forces the UI to paint the right background
                btn.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) { btn.repaint(); }
                    @Override
                    public void mouseExited(MouseEvent e) { btn.repaint(); }
                    @Override
                    public void mousePressed(MouseEvent e) { btn.repaint(); }
                    @Override
                    public void mouseReleased(MouseEvent e) { btn.repaint(); }
                });

            } else if (comp instanceof Container) {
                // Recursively pass container downwards to sweep deep nested subpanels
                enforceButtonStyles((Container) comp, palette);
            }
        }
    }
}
