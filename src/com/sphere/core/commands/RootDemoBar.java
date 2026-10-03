package com.sphere.core.commands;

import com.sphere.components.imaging.ImagingTheme;
import com.sphere.core.rootbackend.RootDemos.Demo;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.util.List;

/**
 * ROOT's control bar of demos, as Sphere shows it: a column of buttons, each
 * running one demo in a ROOT of its own, whose canvases then appear in the
 * Plots tab. A button says while its demo runs, and afterwards whether it
 * drew; its tooltip keeps what went wrong.
 */
final class RootDemoBar {

    private static JDialog open;

    private RootDemoBar() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    static void show(List<Demo> demos) {
        SwingUtilities.invokeLater(() -> {
            if (open != null && open.isDisplayable()) {
                open.toFront();
                return;
            }
            final Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
            final JDialog dialog = new JDialog(owner, "ROOT demos", java.awt.Dialog.ModalityType.MODELESS);
            final JPanel column = ImagingTheme.column();
            column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
            column.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            for (Demo d : demos) {
                final JButton b = ImagingTheme.textButton(d.label(), d.description()
                    + (d.macro() == null ? "" : "  (" + d.macro() + ")"));
                b.setAlignmentX(Component.CENTER_ALIGNMENT);
                b.setMaximumSize(new Dimension(Integer.MAX_VALUE, b.getPreferredSize().height));
                b.addActionListener(e -> run(b, d));
                column.add(b);
                column.add(javax.swing.Box.createVerticalStrut(3));
            }
            final JButton all = ImagingTheme.textButton("Run all",
                "Every demo, as ':root demo all': report, contact sheet, changes since the last run");
            all.addActionListener(e -> {
                all.setEnabled(false);
                background(() -> RootDemoCommands.all(":root demo all", null), () -> all.setEnabled(true));
            });
            final JPanel south = ImagingTheme.panelOf(new BorderLayout());
            south.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
            south.add(all, BorderLayout.CENTER);

            final JScrollPane scroll = new JScrollPane(column);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            dialog.getContentPane().setLayout(new BorderLayout());
            dialog.getContentPane().add(scroll, BorderLayout.CENTER);
            dialog.getContentPane().add(south, BorderLayout.SOUTH);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.pack();
            dialog.setSize(new Dimension(220, Math.min(dialog.getHeight(), 720)));
            dialog.setLocationRelativeTo(owner);
            dialog.setVisible(true);
            open = dialog;
        });
    }

    private static void run(JButton button, Demo demo) {
        if (RootDemoCommands.isBrowser(demo)) {
            com.sphere.components.spherebrowser.SphereBrowser.open();
            return;
        }
        final String label = demo.label();
        final String tip = button.getToolTipText();
        button.setEnabled(false);
        button.setText(label + " ...");
        final com.sphere.core.rootbackend.RootDemos.Outcome[] outcome = new com.sphere.core.rootbackend.RootDemos.Outcome[1];
        background(() -> {
            try {
                final java.util.List<com.sphere.core.rootbackend.RootDemos.Outcome> done =
                    com.sphere.core.rootbackend.RootDemos.run(List.of(demo),
                        com.sphere.components.rootview.RootPlotsPanel.plotsFolder(), RootDemoCommands.workFolder(),
                        RootDemoCommands.demoFormats(false), 1, o -> {
                            RootDemoCommands.report(o, true);
                            for (java.nio.file.Path p : o.pictures()) {
                                com.sphere.components.rootview.RootPlotsPanel.showFile(p.toFile());
                            }
                            if (!o.scenes().isEmpty() || !o.pictures().isEmpty()) {
                                com.sphere.components.spherebrowser.SphereBrowser.showDemo(label, o.scenes(),
                                    o.pictures());
                            }
                        });
                outcome[0] = done.isEmpty() ? null : done.get(0);
            } catch (java.io.IOException e) {
                com.sphere.utils.AppLogger.error(e.getMessage());
            }
        }, () -> {
            final boolean ok = outcome[0] != null && outcome[0].ok();
            button.setText(label + (ok ? "  ✓" : "  ✗"));
            button.setToolTipText(ok || outcome[0] == null || outcome[0].errors().isEmpty() ? tip
                : "<html>" + escape(tip) + "<br><b>" + escape(outcome[0].errors().get(0)) + "</b></html>");
            button.setEnabled(true);
        });
    }

    /** Work off the event thread, then a last step back on it. */
    private static void background(Runnable work, Runnable then) {
        final Thread t = new Thread(() -> {
            try {
                work.run();
            } finally {
                SwingUtilities.invokeLater(then);
            }
        }, "sphere-root-demo-bar");
        t.setDaemon(true);
        t.start();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
