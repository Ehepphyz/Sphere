package com.sphere.ui.console;

import com.sphere.fonts.FontLoader;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.util.HashMap;
import java.util.Map;

/**
 * A window showing one citation (ROOT, FastJet, fjcontrib, LHAPDF) with its BibTeX, opened
 * from Citations in the console's context menu. The text goes nowhere else:
 * the console output stays free of banners.
 */
final class CitationDialog extends JDialog {

    /** One window per title: asking again brings it forward with fresh text. */
    private static final Map<String, CitationDialog> OPEN = new HashMap<>();

    private final JTextArea area = new JTextArea();

    static void show(Component invoker, String title, String text) {
        SwingUtilities.invokeLater(() -> {
            CitationDialog dialog = OPEN.get(title);
            if (dialog == null || !dialog.isDisplayable()) {
                final Window owner = invoker == null ? null : SwingUtilities.getWindowAncestor(invoker);
                dialog = new CitationDialog(owner, title);
                OPEN.put(title, dialog);
            }
            dialog.area.setText(text);
            dialog.area.setCaretPosition(0);
            dialog.setVisible(true);
            dialog.toFront();
        });
    }

    private CitationDialog(Window owner, String title) {
        super(owner, title, ModalityType.MODELESS);
        final ThemePalette p = ThemeManager.getCurrentPalette();

        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(FontLoader.getTerminalFont(Font.PLAIN, 12));
        area.setBackground(p.getTerminalBackground());
        area.setForeground(p.getTerminalForeground());
        area.setSelectionColor(p.getTerminalSelection());
        area.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        final JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createLineBorder(p.getPopupBorder(), 1));
        scroll.getViewport().setBackground(p.getTerminalBackground());

        final JButton copy = new JButton("Copy");
        final JButton close = new JButton("Close");
        copy.addActionListener(e -> {
            com.sphere.components.ClipboardBridge.write(area.getText());
            copy.setText("Copied");
            final Timer back = new Timer(1500, t -> copy.setText("Copy"));
            back.setRepeats(false);
            back.start();
        });
        close.addActionListener(e -> dispose());

        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(copy);
        buttons.add(close);

        final JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBackground(p.getBackgroundMain());
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(scroll, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(new Dimension(820, 560));
        setLocationRelativeTo(owner);
    }
}
