package com.sphere.components.imaging;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * A quick look at an image without opening the editor: the picture on a
 * transparency ground, its size, and its weight on disk.
 *
 * Reading happens off the event thread, so hovering a folder full of large
 * files never freezes the explorer.
 */
public final class ImagePreviewPane extends JPanel {

    private static final int MAX_SIDE = 320;

    private final Thumbnail thumbnail = new Thumbnail();
    private final JLabel caption = new JLabel();
    private final JLabel detail = new JLabel();

    private File current;
    private SwingWorker<BufferedImage, Void> worker;

    public ImagePreviewPane() {
        setLayout(new BorderLayout(0, 6));
        setBackground(ImagingTheme.panel());
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        caption.setFont(ImagingTheme.uiFont(Font.BOLD, 11f));
        caption.setForeground(ImagingTheme.text());
        detail.setFont(ImagingTheme.uiFont(Font.PLAIN, 10f));
        detail.setForeground(ImagingTheme.subduedText());

        ImagingTheme.Surface labels = ImagingTheme.panelOf(new BorderLayout());
        labels.add(caption, BorderLayout.NORTH);
        labels.add(detail, BorderLayout.SOUTH);

        add(thumbnail, BorderLayout.CENTER);
        add(labels, BorderLayout.SOUTH);
        clear();
    }

    @Override
    protected void paintComponent(java.awt.Graphics graphics) {
        java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
        try {
            g.setColor(ImagingTheme.panel());
            g.fillRect(0, 0, getWidth(), getHeight());
        } finally {
            g.dispose();
        }
    }

    public void clear() {
        current = null;
        thumbnail.set(null);
        caption.setText("No image selected");
        detail.setText(" ");
        repaint();
    }

    /** Shows `file`, or clears the pane when it is not an image. */
    public void show(File file) {
        if (file == null || !ImageFileIO.isImage(file)) {
            clear();
            return;
        }
        if (file.equals(current)) {
            return;
        }
        current = file;
        caption.setText(file.getName());
        detail.setText("reading...");
        thumbnail.set(null);
        repaint();

        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
        worker = new SwingWorker<>() {
            @Override
            protected BufferedImage doInBackground() {
                return ImageFileIO.readPreview(file, MAX_SIDE);
            }

            @Override
            protected void done() {
                if (isCancelled() || !file.equals(current)) {
                    return;
                }
                BufferedImage image = null;
                try {
                    image = get();
                } catch (Exception ignored) {
                    // A file that cannot be decoded simply shows no preview.
                }
                thumbnail.set(image);
                detail.setText(describe(file, image));
                repaint();
            }
        };
        worker.execute();
    }

    private static String describe(File file, BufferedImage image) {
        StringBuilder sb = new StringBuilder();
        if (image != null) {
            sb.append(image.getWidth()).append(" x ").append(image.getHeight());
            if ("svg".equals(ImageFileIO.extension(file.getName()))) {
                sb.append(" preview, vector");
            }
            sb.append("   ");
        }
        final long bytes = file.length();
        if (bytes < 1024) {
            sb.append(bytes).append(" B");
        } else if (bytes < 1024 * 1024) {
            sb.append(Math.round(bytes / 1024.0)).append(" KB");
        } else {
            sb.append(String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0));
        }
        return sb.toString();
    }

    /** Opens the shown file in the full editor. */
    public void openInEditor() {
        if (current != null) {
            SwingUtilities.invokeLater(() -> com.sphere.ui.ImageEditorFrame.show(current));
        }
    }

    public File getFile() {
        return current;
    }

    private static final class Thumbnail extends JComponent {
        private BufferedImage image;

        Thumbnail() {
            setPreferredSize(new Dimension(MAX_SIDE, MAX_SIDE));
        }

        void set(BufferedImage image) {
            this.image = image;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                final int w = getWidth();
                final int h = getHeight();
                g.setColor(ImagingTheme.canvasGround());
                g.fillRect(0, 0, w, h);

                if (image != null) {
                    final double scale = Math.min((double) (w - 8) / image.getWidth(),
                                                  (double) (h - 8) / image.getHeight());
                    final int dw = Math.max(1, (int) Math.round(image.getWidth() * scale));
                    final int dh = Math.max(1, (int) Math.round(image.getHeight() * scale));
                    final int x = (w - dw) / 2;
                    final int y = (h - dh) / 2;

                    Graphics2D ground = (Graphics2D) g.create(x, y, dw, dh);
                    try {
                        ImageDocument.paintChecker(ground, dw, dh, 10);
                    } finally {
                        ground.dispose();
                    }
                    ImageDocument.applyQuality(g);
                    g.drawImage(image, x, y, dw, dh, null);
                    g.setColor(ImagingTheme.border());
                    g.drawRect(x, y, dw - 1, dh - 1);
                }
                g.setColor(ImagingTheme.border());
                g.drawRect(0, 0, w - 1, h - 1);
            } finally {
                g.dispose();
            }
        }
    }
}
