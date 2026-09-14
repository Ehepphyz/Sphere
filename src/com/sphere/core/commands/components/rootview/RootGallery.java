package com.sphere.components.rootview;

import com.sphere.components.imaging.ImagingTheme;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/**
 * The plots drawn so far, each kept as a thumbnail.
 *
 * Drawing one plot replaces the last, so what was on screen a moment ago is
 * gone. Keeping the drawn object beside its picture is what lets a click put it
 * back without reading the file or the backend again.
 */
public final class RootGallery extends ViewSurface {

    /** Told which plot was picked, so the viewer can draw it again. */
    public interface PickListener {
        void plotPicked(Entry entry);

        /** A double click asks for the picture itself, in the image editor. */
        void plotOpened(Entry entry);
    }

    /** One remembered plot: its picture, and whatever it was made of. */
    public static final class Entry {
        public final String caption;
        public final BufferedImage thumbnail;
        public final RootHistogram histogram;
        public final RootGraph graph;
        public final RootGraph2D surface;
        public final RootPlot.Style style;
        /** Set when the plot came in as a file rather than as decoded numbers. */
        public final java.io.File source;

        Entry(String caption, BufferedImage thumbnail, RootHistogram histogram,
              RootGraph graph, RootGraph2D surface, RootPlot.Style style,
              java.io.File source) {
            this.caption = caption;
            this.thumbnail = thumbnail;
            this.histogram = histogram;
            this.graph = graph;
            this.surface = surface;
            this.style = style;
            this.source = source;
        }
    }

    private static final int THUMB_WIDTH = 168;
    private static final int THUMB_HEIGHT = 112;
    /** Older plots drop off rather than filling the tab without end. */
    private static final int KEEP = 40;

    private final DefaultListModel<Entry> model = new DefaultListModel<>();
    private final JList<Entry> thumbnails = new JList<>(model);
    private final JLabel empty = new JLabel("Plots you draw are kept here",
                                            SwingConstants.CENTER);

    private PickListener pickListener;

    public RootGallery() {
        super(new BorderLayout(), false);

        thumbnails.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        thumbnails.setVisibleRowCount(-1);
        thumbnails.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        thumbnails.setBackground(ImagingTheme.panel());
        thumbnails.setForeground(ImagingTheme.text());
        thumbnails.setFixedCellWidth(THUMB_WIDTH + 16);
        thumbnails.setFixedCellHeight(THUMB_HEIGHT + 30);
        thumbnails.setCellRenderer(new ThumbnailRenderer());
        thumbnails.setToolTipText("Click to draw it again, double click to edit the picture");
        thumbnails.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                final int index = thumbnails.locationToIndex(event.getPoint());
                if (index < 0 || pickListener == null
                    || !thumbnails.getCellBounds(index, index).contains(event.getPoint())) {
                    return;
                }
                if (event.getClickCount() >= 2) {
                    pickListener.plotOpened(model.get(index));
                } else {
                    pickListener.plotPicked(model.get(index));
                }
            }
        });

        empty.setForeground(ImagingTheme.subduedText());
        empty.setFont(ImagingTheme.uiFont(Font.PLAIN, 12f));
        add(empty, BorderLayout.CENTER);
    }

    public void setPickListener(PickListener listener) {
        this.pickListener = listener;
    }

    /**
     * Keeps what the plot is showing right now.
     *
     * The same caption replaces its entry instead of piling up, so redrawing
     * one object leaves one thumbnail rather than a row of identical ones.
     */
    public void remember(RootPlot plot, String caption) {
        if (plot == null || caption == null || !plot.hasContent()) {
            return;
        }
        put(new Entry(caption, shrink(plot), plot.getHistogram(), plot.getGraph(),
                      plot.getGraph2D(), plot.getStyle(), null));
    }

    /**
     * Keeps a picture that arrived as a file.
     *
     * The same file replaces its entry, so a script rerun leaves one thumbnail
     * showing the latest drawing rather than a row of older ones.
     */
    public void rememberImage(java.io.File file) {
        if (file == null) {
            return;
        }
        java.awt.image.BufferedImage preview =
            com.sphere.components.imaging.ImageFileIO.readPreview(file, THUMB_WIDTH);
        if (preview == null) {
            return;
        }
        put(new Entry(file.getName(), fit(preview), null, null, null,
                      RootPlot.Style.POINTS, file));
    }

    private void put(Entry entry) {
        final String caption = entry.caption;
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).caption.equals(caption)) {
                model.set(i, entry);
                layOut();
                return;
            }
        }
        model.addElement(entry);
        while (model.size() > KEEP) {
            model.remove(0);
        }
        layOut();
    }

    public void clear() {
        model.clear();
        layOut();
    }

    /** How many plots are kept. */
    public int kept() {
        return model.size();
    }

    /** The plots kept, oldest first. */
    public Entry at(int index) {
        return (index >= 0 && index < model.size()) ? model.get(index) : null;
    }

    /** Centers a preview of any shape on a thumbnail of the usual size. */
    private static BufferedImage fit(BufferedImage preview) {
        BufferedImage small = new BufferedImage(THUMB_WIDTH, THUMB_HEIGHT,
                                                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        try {
            g.setColor(ImagingTheme.canvasGround());
            g.fillRect(0, 0, THUMB_WIDTH, THUMB_HEIGHT);
            final double scale = Math.min(
                (double) THUMB_WIDTH / preview.getWidth(),
                (double) THUMB_HEIGHT / preview.getHeight());
            final int w = Math.max(1, (int) Math.round(preview.getWidth() * scale));
            final int h = Math.max(1, (int) Math.round(preview.getHeight() * scale));
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                               java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(preview, (THUMB_WIDTH - w) / 2, (THUMB_HEIGHT - h) / 2,
                        w, h, null);
        } finally {
            g.dispose();
        }
        return small;
    }

    /** Drawn large, then scaled down, so the thumbnail keeps its lines. */
    private static BufferedImage shrink(RootPlot plot) {
        BufferedImage full = plot.snapshot(THUMB_WIDTH * 3, THUMB_HEIGHT * 3);
        BufferedImage small = new BufferedImage(THUMB_WIDTH, THUMB_HEIGHT,
                                                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                               java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(full.getScaledInstance(THUMB_WIDTH, THUMB_HEIGHT,
                                               Image.SCALE_SMOOTH), 0, 0, null);
        } finally {
            g.dispose();
        }
        return small;
    }

    /** The list only takes the place of the notice once there is something in it. */
    private void layOut() {
        removeAll();
        if (model.isEmpty()) {
            add(empty, BorderLayout.CENTER);
        } else {
            JScrollPane scroll = new JScrollPane(thumbnails);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getViewport().setBackground(ImagingTheme.panel());
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            add(scroll, BorderLayout.CENTER);
        }
        revalidate();
        repaint();
    }

    private static final class ThumbnailRenderer extends JLabel
            implements ListCellRenderer<Entry> {

        ThumbnailRenderer() {
            setOpaque(true);
            setHorizontalAlignment(SwingConstants.CENTER);
            setHorizontalTextPosition(SwingConstants.CENTER);
            setVerticalTextPosition(SwingConstants.BOTTOM);
            setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Entry> list,
                                                      Entry value, int index,
                                                      boolean selected,
                                                      boolean focused) {
            setIcon(new ImageIcon(value.thumbnail));
            setText(value.caption.length() > 24
                    ? value.caption.substring(0, 23) + "…" : value.caption);
            setToolTipText(value.caption);
            setBackground(selected ? ImagingTheme.surface() : ImagingTheme.panel());
            setForeground(selected ? ImagingTheme.accent() : ImagingTheme.text());
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(4, 4, 4, 4),
                BorderFactory.createLineBorder(
                    selected ? ImagingTheme.accent() : ImagingTheme.border(), 1)));
            return this;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(THUMB_WIDTH + 16, THUMB_HEIGHT + 30);
        }
    }
}
