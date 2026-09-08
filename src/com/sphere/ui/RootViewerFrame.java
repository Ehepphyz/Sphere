package com.sphere.ui;

import com.sphere.components.rootview.RootViewerPanel;
import com.sphere.utils.IconManager;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;

/**
 * Standalone window around the .root viewer. One window is reused for every
 * file opened from the workbench, the way the image editor works.
 */
public final class RootViewerFrame extends JFrame {

    private static RootViewerFrame shared;

    private final RootViewerPanel viewer = new RootViewerPanel();

    public RootViewerFrame(File target) {
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setSize(1280, 820);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        add(viewer, BorderLayout.CENTER);
        setJMenuBar(buildMenu());

        viewer.setTitleListener(this::updateTitle);
        IconManager.applyAppIcon(this);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                viewer.close();
                if (shared == RootViewerFrame.this) {
                    shared = null;
                }
            }
        });

        if (target != null && target.isFile()) {
            viewer.open(target);
        }
        updateTitle();
    }

    /** Opens the file in the shared window, raising it if it already exists. */
    public static RootViewerFrame show(File target) {
        if (shared == null || !shared.isDisplayable()) {
            shared = new RootViewerFrame(target);
            shared.setVisible(true);
            return shared;
        }
        if (target != null && target.isFile()) {
            shared.viewer.open(target);
        }
        shared.setVisible(true);
        shared.toFront();
        shared.requestFocus();
        return shared;
    }

    public RootViewerPanel getViewer() {
        return viewer;
    }

    private void updateTitle() {
        setTitle("Sphere ROOT - " + viewer.documentTitle());
    }

    private JMenuBar buildMenu() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(item("Open...", "control O", this::openDialog));
        file.add(item("Reload", "F5", viewer::reload));
        file.addSeparator();
        file.add(item("Close", "control W", () -> dispatchEvent(
            new WindowEvent(this, WindowEvent.WINDOW_CLOSING))));
        bar.add(file);

        JMenu view = new JMenu("View");
        view.add(item("Log scale on Y", "control L",
                      () -> viewer.getPlot().setLogY(!viewer.getPlot().isLogY())));
        view.add(item("Log scale on X", "control shift L",
                      () -> viewer.getPlot().setLogX(!viewer.getPlot().isLogX())));
        view.add(item("Grid", "control G",
                      () -> viewer.getPlot().setShowGrid(!viewer.getPlot().isShowGrid())));
        view.add(item("Error bars", "control E",
                      () -> viewer.getPlot().setShowErrors(!viewer.getPlot().isShowErrors())));
        bar.add(view);

        return bar;
    }

    private JMenuItem item(String label, String accelerator, Runnable action) {
        JMenuItem menuItem = new JMenuItem(label);
        if (accelerator != null) {
            menuItem.setAccelerator(KeyStroke.getKeyStroke(accelerator));
        }
        menuItem.addActionListener(e -> action.run());
        return menuItem;
    }

    private void openDialog() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("ROOT files", "root"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            viewer.open(chooser.getSelectedFile());
        }
    }
}
