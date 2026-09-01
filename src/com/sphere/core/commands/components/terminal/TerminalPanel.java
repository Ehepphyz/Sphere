package com.sphere.components.terminal;

import javax.swing.*;
import java.awt.*;
import com.sphere.theme.ThemeManager;
import com.sphere.fonts.FontLoader;

public class TerminalPanel extends JPanel {

    private final TerminalEngine engine;
    private final TerminalView view;
    private final JTextField input;

    public TerminalPanel(String shellCommand) {
        super(new BorderLayout());

        engine = new TerminalEngine(shellCommand);
        view   = new TerminalView(engine);

        // --- Input field ---
        input = new JTextField();
        input.setFont(FontLoader.getTerminalFont(Font.PLAIN, 12));
        input.addActionListener(e -> {
            String cmd = input.getText();
            engine.sendCommand(cmd);
            input.setText("");
        });

        add(view, BorderLayout.CENTER);
        add(input, BorderLayout.SOUTH);

        engine.start();
    }

    public TerminalEngine getEngine() {
        return engine;
    }

    public TerminalView getView() {
        return view;
    }

    public void disposeTerminal() {
        engine.stop();
    }
}

