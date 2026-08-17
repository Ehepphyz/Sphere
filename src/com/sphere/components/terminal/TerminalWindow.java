package com.sphere.components.terminal;

import javax.swing.*;
import java.awt.*;

public class TerminalWindow extends JFrame {

    public TerminalWindow(String shellCommand) {
        super("Terminal Window");

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(900, 600);
        setLocationRelativeTo(null);

        // CRITICAL: ensure full stretch
        setLayout(new BorderLayout());

        TerminalPanel panel = new TerminalPanel(shellCommand);
        add(panel, BorderLayout.CENTER);

        // Clean shutdown
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                panel.getEngine().stop();
            }
        });
    }
}

