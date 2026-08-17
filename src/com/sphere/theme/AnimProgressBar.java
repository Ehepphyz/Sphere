package com.sphere.theme;

import javax.swing.*;
import java.awt.*;

/**
 * Progress bar
 * Works in determinate and indeterminate modes.
 */
public class AnimProgressBar extends JComponent {

    private int progress = 0;          // 0–100
    private boolean indeterminate = false;
    private int animOffset = 0;

    private final Timer timer;

    public AnimProgressBar() {
        // Thin, elegant height
        setPreferredSize(new Dimension(200, 4));

        timer = new Timer(16, e -> {
            if (indeterminate) {
                animOffset = (animOffset + 4) % Math.max(1, getWidth());
                repaint();
            }
        });
        timer.start();
    }

    public void setProgress(int value) {
        this.progress = Math.max(0, Math.min(100, value));
        repaint();
    }

    public void setIndeterminate(boolean value) {
        this.indeterminate = value;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g;

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        // Subtle dark background
        g2.setColor(new Color(60, 60, 60));
        g2.fillRoundRect(0, 0, w, h, h, h);

        // Accent color (VSCode blue)
        Color accent = new Color(0, 120, 215);

        if (!indeterminate) {
            int pw = (int) (w * (progress / 100.0));
            g2.setColor(accent);
            g2.fillRoundRect(0, 0, pw, h, h, h);
        } else {
            int barWidth = w / 3;
            g2.setColor(accent);
            g2.fillRoundRect(animOffset - barWidth, 0, barWidth, h, h, h);
        }
    }
}

