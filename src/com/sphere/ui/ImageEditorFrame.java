package com.sphere.ui;

import com.sphere.components.imaging.ImageDocument;
import com.sphere.components.imaging.ImageEditorPanel;
import com.sphere.components.imaging.ImageFileIO;
import com.sphere.utils.IconManager;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;

/**
 * Standalone window around the image editor. One window is reused for every
 * image opened from the workbench, so a session of viewing files does not leave
 * a trail of windows behind.
 */
public final class ImageEditorFrame extends JFrame {

    private static ImageEditorFrame shared;

    private final ImageEditorPanel editor = new ImageEditorPanel();

    public ImageEditorFrame(File target) {
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setSize(1180, 780);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        add(editor, BorderLayout.CENTER);
        setJMenuBar(buildMenu());

        editor.setTitleListener(this::updateTitle);
        IconManager.applyAppIcon(this);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (!confirmDiscard()) {
                    setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                } else {
                    setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                    if (shared == ImageEditorFrame.this) {
                        shared = null;
                    }
                }
            }
        });

        if (target != null && target.isFile()) {
            editor.open(target);
        } else {
            editor.openBlank(1200, 800);
        }
        updateTitle();
    }

    /** Opens the file in the shared window, raising it if it already exists. */
    public static ImageEditorFrame show(File target) {
        if (shared == null || !shared.isDisplayable()) {
            shared = new ImageEditorFrame(target);
            shared.setVisible(true);
            return shared;
        }
        if (target != null && target.isFile()) {
            if (!shared.confirmDiscard()) {
                return shared;
            }
            shared.editor.open(target);
        }
        shared.setVisible(true);
        shared.toFront();
        shared.requestFocus();
        return shared;
    }

    public ImageEditorPanel getEditor() {
        return editor;
    }

    private void updateTitle() {
        setTitle("Sphere Image - " + editor.documentTitle());
    }

    private boolean confirmDiscard() {
        ImageDocument doc = editor.getDocument();
        if (doc == null || !doc.isDirty()) {
            return true;
        }
        final int answer = JOptionPane.showConfirmDialog(this,
            "This image has unsaved changes. Save it?",
            "Sphere Image", JOptionPane.YES_NO_CANCEL_OPTION);
        if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) {
            return false;
        }
        if (answer == JOptionPane.YES_OPTION) {
            editor.save();
            return doc.isDirty() ? false : true;
        }
        return true;
    }

    private JMenuBar buildMenu() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(item("Open...", "control O", this::openDialog));
        file.add(item("Save", "control S", editor::save));
        file.add(item("Save as...", "control shift S", editor::saveAs));
        file.addSeparator();
        file.add(item("Close", "control W", () -> dispatchEvent(
            new WindowEvent(this, WindowEvent.WINDOW_CLOSING))));
        bar.add(file);

        JMenu view = new JMenu("View");
        view.add(item("Fit to window", "control 0", () -> editor.getCanvas().zoomToFit()));
        view.add(item("Actual size", "control 1", () -> editor.getCanvas().zoomToActual()));
        bar.add(view);

        JMenu image = new JMenu("Image");
        image.add(item("Rotate a quarter turn", null, () -> {
            if (editor.getDocument() != null) {
                editor.getDocument().rotate(1);
                editor.getCanvas().refresh();
            }
        }));
        image.add(item("Flip horizontally", null, () -> {
            if (editor.getDocument() != null) {
                editor.getDocument().flip(true);
                editor.getCanvas().refresh();
            }
        }));
        image.add(item("Flip vertically", null, () -> {
            if (editor.getDocument() != null) {
                editor.getDocument().flip(false);
                editor.getCanvas().refresh();
            }
        }));
        image.addSeparator();
        image.add(item("Flatten layers", null, () -> {
            if (editor.getDocument() != null) {
                editor.getDocument().flatten();
                editor.getCanvas().refresh();
            }
        }));
        bar.add(image);

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
        chooser.setFileFilter(new FileNameExtensionFilter(
            "Images", ImageFileIO.READABLE));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION
            && confirmDiscard()) {
            editor.open(chooser.getSelectedFile());
        }
    }
}
