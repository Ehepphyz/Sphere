package com.sphere.components.terminal;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import com.sphere.theme.ThemeManager;
import com.sphere.fonts.FontLoader;

public class TerminalMirrorView extends JPanel {

    private final JTextPane mirror;

    public TerminalMirrorView(TerminalPanel source) {
        
        setLayout(new BorderLayout());

        mirror = new JTextPane();
        mirror.setEditable(false);
        mirror.setFont(FontLoader.getTerminalFont(Font.PLAIN, 12));
        mirror.putClientProperty("JTextPane.honorDisplayProperties", Boolean.TRUE);

        // Initial sync
        mirror.setText(source.getView().getText());

        // Live sync
        source.getView().getDocument().addDocumentListener(new DocumentListener() {
            private void sync() {
                mirror.setText(source.getView().getText());
                mirror.setCaretPosition(mirror.getDocument().getLength());
            }

            @Override public void insertUpdate(DocumentEvent e) { sync(); }
            @Override public void removeUpdate(DocumentEvent e) { sync(); }
            @Override public void changedUpdate(DocumentEvent e) { sync(); }
        });

        add(new JScrollPane(mirror), BorderLayout.CENTER);
    }
}

