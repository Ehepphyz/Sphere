package com.sphere.components.imaging;

import com.sphere.components.CoderPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

/**
 * Sphere Image in a SphereCoder tab: the image editor, opened beside the
 * document a picture comes from (a PDF), or on a picture file of its own.
 */
public final class ImageTab extends JPanel implements CoderPanel {

    private final ImageEditorPanel editor = new ImageEditorPanel();

    public ImageTab() {
        super(new BorderLayout());
        add(editor, BorderLayout.CENTER);
    }

    public ImageEditorPanel editor() {
        return editor;
    }

    /** Opens a picture that belongs to another document (see {@link ImageEditorPanel#openLinked}). */
    public boolean openLinked(BufferedImage image, String name, ImageEditorPanel.Link link) {
        return editor.openLinked(image, name, link);
    }

    /** True when this tab edits the picture a link points to. */
    public boolean linkedTo(ImageEditorPanel.Link link) {
        ImageEditorPanel.Link mine = editor.getLink();
        return mine != null && link != null && mine.key().equals(link.key());
    }

    @Override
    public String title() {
        return editor.documentTitle();
    }

    @Override
    public Path file() {
        ImageDocument d = editor.getDocument();
        return d == null || d.getFile() == null || editor.getLink() != null ? null : d.getFile().toPath();
    }

    @Override
    public boolean isDirty() {
        ImageDocument d = editor.getDocument();
        return d != null && d.isDirty();
    }

    @Override
    public boolean save() {
        editor.save();
        return !isDirty();
    }

    @Override
    public boolean saveAs() {
        editor.saveAs();
        return true;
    }

    @Override
    public boolean confirmDiscard() {
        if (!isDirty()) {
            return true;
        }
        if (editor.getLink() != null) {
            return editor.releaseLink(true);
        }
        Object[] options = { "Save", "Don't Save", "Cancel" };
        int r = JOptionPane.showOptionDialog(this, "\"" + editor.documentTitle() + "\" has unsaved changes.\nSave them?",
                                             "Unsaved Changes", JOptionPane.YES_NO_CANCEL_OPTION,
                                             JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (r == 2 || r == JOptionPane.CLOSED_OPTION) {
            return false;
        }
        if (r == 0) {
            return save();
        }
        return true;
    }

    @Override
    public void dispose() {
        editor.releaseLink(false);
    }

    @Override
    public void focusView() {
        editor.getCanvas().requestFocusInWindow();
    }

    @Override
    public void setTitleListener(Runnable r) {
        editor.setTitleListener(r);
    }

    @Override
    public void undo() {
        editor.undo();
    }

    @Override
    public void redo() {
        editor.redo();
    }

    @Override
    public String badge() {
        return "IMG";
    }

    @Override
    public Color badgeColor() {
        return new Color(0x8E, 0x44, 0xAD);
    }
}
