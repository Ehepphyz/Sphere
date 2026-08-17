package com.sphere.theme;

import javax.swing.*;
import java.awt.*;

/**
 * Slim VSCode-style loading animation.
 * Three thin vertical bars that pulse with a phase shift.
 */
public class AnimLoader extends JComponent {

    private int t = 0;
    private final Timer timer;

    public AnimLoader() {
        timer = new Timer(80, e -> {
            t++;
            repaint();
        });
        timer.start();

        // Smaller default size for table cells
        setPreferredSize(new Dimension(40, 18));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        // Much thinner bars
        int barWidth = 6;       // was 18
        int spacing = 6;        // was 14

        int totalWidth = barWidth * 3 + spacing * 2;
        int startX = (w - totalWidth) / 2;

        for (int i = 0; i < 3; i++) {
            // Slimmer animation amplitude
            float scale = (float) (0.4 + 0.6 * Math.sin((t + i * 4) * 0.35));
            int barHeight = (int) (h * scale * 0.8);

            int x = startX + i * (barWidth + spacing);
            int y = (h - barHeight) / 2;

            g2.setColor(new Color(0, 120, 215)); // VSCode blue
            g2.fillRoundRect(x, y, barWidth, barHeight, 4, 4);
        }
    }
}

