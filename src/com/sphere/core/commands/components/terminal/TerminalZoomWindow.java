package com.sphere.components.terminal;

import javax.swing.*;
import java.awt.*;

public class TerminalZoomWindow extends JFrame {

    public TerminalZoomWindow(TerminalPanel source) {
        setTitle("Terminal Window");
        setSize(900, 600);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        add(new TerminalMirrorView(source), BorderLayout.CENTER);
    }
}

