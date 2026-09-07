package com.sphere.components.imaging;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * The layer stack: order, visibility, opacity, and a thumbnail of each plane so
 * a stack of similar-looking layers can still be told apart.
 */
public final class LayersPanel extends JPanel {

    private static final int THUMB = 34;

    private final ImageCanvas canvas;
    private final DefaultListModel<ImageLayer> model = new DefaultListModel<>();
    private final JList<ImageLayer> list = new JList<>(model);
    private final JSlider opacity = new JSlider(0, 100, 100);
    private final JCheckBox visible = new JCheckBox("Visible");
    private final JLabel opacityValue = new JLabel("100%");

    private boolean updating;

    public LayersPanel(ImageCanvas canvas) {
        this.canvas = canvas;
        setLayout(new BorderLayout());
        setBackground(ImagingTheme.panel());
        setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        add(ImagingTheme.sectionLabel("Layers"), BorderLayout.NORTH);
        add(buildList(), BorderLayout.CENTER);
        add(buildControls(), BorderLayout.SOUTH);

        canvas.addChangeListener(this::reload);
        reload();
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

    private JScrollPane buildList() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(ImagingTheme.panel());
        list.setFixedCellHeight(THUMB + 12);
        list.setCellRenderer(new LayerCell());
        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || updating) {
                return;
            }
            final int index = list.getSelectedIndex();
            if (index >= 0 && canvas.getDocument() != null) {
                // The list shows the topmost layer first, the document keeps them
                // bottom-first, so the index has to be turned around.
                canvas.getDocument().setActiveIndex(model.getSize() - 1 - index);
                syncControls();
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, ImagingTheme.border()));
        scroll.setOpaque(true);
        scroll.setBackground(ImagingTheme.panel());
        scroll.getViewport().setOpaque(true);
        scroll.getViewport().setBackground(ImagingTheme.panel());
        scroll.setPreferredSize(new Dimension(230, 190));
        return scroll;
    }

    private JPanel buildControls() {
        ImagingTheme.Surface box = ImagingTheme.stack(false);
        box.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 8));

        ImagingTheme.Surface row = ImagingTheme.strip(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(button("+", "Add an empty layer", () -> {
            ImageDocument doc = canvas.getDocument();
            if (doc != null) {
                doc.addLayer(ImageLayer.raster("Layer " + (doc.getLayers().size() + 1),
                                               doc.getWidth(), doc.getHeight()));
                canvas.refresh();
            }
        }));
        row.add(button("Copy", "Duplicate the selected layer", () -> {
            if (canvas.getDocument() != null) {
                canvas.getDocument().duplicateActiveLayer();
                canvas.refresh();
            }
        }));
        row.add(button("Merge", "Merge into the layer below", () -> {
            if (canvas.getDocument() != null) {
                canvas.getDocument().mergeDown();
                canvas.refresh();
            }
        }));
        row.add(button("−", "Delete the selected layer", () -> {
            if (canvas.getDocument() != null) {
                canvas.getDocument().removeActiveLayer();
                canvas.refresh();
            }
        }));
        box.add(row);

        ImagingTheme.Surface order = ImagingTheme.strip(false);
        order.setAlignmentX(Component.LEFT_ALIGNMENT);
        order.add(button("↑", "Move up", () -> move(1)));
        order.add(button("↓", "Move down", () -> move(-1)));
        order.add(Box.createHorizontalGlue());
        visible.setBackground(ImagingTheme.panel());
        visible.setForeground(ImagingTheme.text());
        visible.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        visible.setFocusPainted(false);
        visible.addActionListener(e -> {
            ImageDocument doc = canvas.getDocument();
            if (doc != null && !updating) {
                doc.getActiveLayer().setVisible(visible.isSelected());
                canvas.repaint();
                list.repaint();
            }
        });
        order.add(visible);
        box.add(Box.createVerticalStrut(4));
        box.add(order);

        ImagingTheme.Surface opacityRow = ImagingTheme.panelOf(new BorderLayout(6, 0));
        opacityRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        opacityRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        JLabel caption = new JLabel("Opacity");
        caption.setForeground(ImagingTheme.subduedText());
        caption.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        opacityValue.setForeground(ImagingTheme.subduedText());
        opacityValue.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        opacityValue.setHorizontalAlignment(SwingConstants.RIGHT);
        opacityValue.setPreferredSize(new Dimension(38, 16));

        opacity.setBackground(ImagingTheme.panel());
        opacity.setFocusable(false);
        opacity.addChangeListener(e -> {
            opacityValue.setText(opacity.getValue() + "%");
            ImageDocument doc = canvas.getDocument();
            if (doc != null && !updating) {
                doc.getActiveLayer().setOpacity(opacity.getValue() / 100.0);
                canvas.repaint();
            }
        });

        opacityRow.add(caption, BorderLayout.WEST);
        opacityRow.add(opacity, BorderLayout.CENTER);
        opacityRow.add(opacityValue, BorderLayout.EAST);
        box.add(opacityRow);
        return box;
    }

    private void move(int delta) {
        ImageDocument doc = canvas.getDocument();
        if (doc != null) {
            doc.moveActiveLayer(delta);
            canvas.refresh();
        }
    }

    private JButton button(String label, String tip, Runnable action) {
        JButton button = ImagingTheme.textButton(label, tip);
        button.addActionListener(e -> action.run());
        button.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        return button;
    }

    public void reload() {
        ImageDocument doc = canvas.getDocument();
        updating = true;
        try {
            model.clear();
            if (doc != null) {
                for (int i = doc.getLayers().size() - 1; i >= 0; i--) {
                    model.addElement(doc.getLayers().get(i));
                }
                list.setSelectedIndex(model.getSize() - 1 - doc.getActiveIndex());
            }
        } finally {
            updating = false;
        }
        syncControls();
    }

    private void syncControls() {
        ImageDocument doc = canvas.getDocument();
        updating = true;
        try {
            if (doc == null) {
                opacity.setEnabled(false);
                visible.setEnabled(false);
                return;
            }
            ImageLayer layer = doc.getActiveLayer();
            opacity.setEnabled(true);
            visible.setEnabled(true);
            opacity.setValue((int) Math.round(layer.getOpacity() * 100));
            opacityValue.setText(opacity.getValue() + "%");
            visible.setSelected(layer.isVisible());
        } finally {
            updating = false;
        }
    }

    /** Draws one row: thumbnail, name, and what the layer holds. */
    private final class LayerCell extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> source, Object value,
                                                      int index, boolean selected,
                                                      boolean focused) {
            ImageLayer layer = (ImageLayer) value;
            JPanel row = new RowPanel(selected);
            row.setLayout(new BorderLayout(8, 0));
            row.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

            row.add(new ThumbnailIcon(layer), BorderLayout.WEST);

            JPanel names = new JPanel();
            names.setLayout(new BoxLayout(names, BoxLayout.Y_AXIS));
            names.setOpaque(false);

            JLabel name = new JLabel(layer.getName());
            name.setForeground(layer.isVisible()
                ? ImagingTheme.text() : ImagingTheme.subduedText());
            name.setFont(ImagingTheme.uiFont(Font.PLAIN, 12f));

            StringBuilder detail = new StringBuilder();
            if (layer.hasPixels()) {
                detail.append("pixels");
            }
            if (!layer.getAnnotations().isEmpty()) {
                if (detail.length() > 0) {
                    detail.append(" + ");
                }
                detail.append(layer.getAnnotations().size()).append(" marks");
            }
            if (layer.getOpacity() < 1.0) {
                detail.append("  ").append(Math.round(layer.getOpacity() * 100)).append('%');
            }
            JLabel sub = new JLabel(detail.length() == 0 ? "empty" : detail.toString());
            sub.setForeground(ImagingTheme.subduedText());
            sub.setFont(ImagingTheme.uiFont(Font.PLAIN, 10f));

            names.add(name);
            names.add(sub);
            row.add(names, BorderLayout.CENTER);

            if (!layer.isVisible()) {
                JLabel hidden = new JLabel("hidden");
                hidden.setForeground(ImagingTheme.subduedText());
                hidden.setFont(ImagingTheme.uiFont(Font.PLAIN, 10f));
                row.add(hidden, BorderLayout.EAST);
            }
            // A renderer never joins the component tree, so nothing lays it out on
            // its own; without this the row paints its background and no children.
            row.setSize(Math.max(source.getWidth(), 180), THUMB + 12);
            row.doLayout();
            for (Component child : row.getComponents()) {
                if (child instanceof java.awt.Container inner) {
                    inner.doLayout();
                }
            }
            return row;
        }
    }

    /** One row of the list, painted rather than left to the look and feel. */
    private static final class RowPanel extends JPanel {
        private final boolean selected;

        RowPanel(boolean selected) {
            this.selected = selected;
            setOpaque(true);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(selected
                    ? ImagingTheme.palette().getTabEditorSelectBg() : ImagingTheme.panel());
                g.fillRect(0, 0, getWidth(), getHeight());
                if (selected) {
                    g.setColor(ImagingTheme.accent());
                    g.fillRect(0, 0, 3, getHeight());
                }
            } finally {
                g.dispose();
            }
        }
    }

    /** A small preview of the layer, on a checkerboard so alpha is visible. */
    private static final class ThumbnailIcon extends JPanel {
        private final ImageLayer layer;

        ThumbnailIcon(ImageLayer layer) {
            this.layer = layer;
            setPreferredSize(new Dimension(THUMB, THUMB));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                ImageDocument.paintChecker(g, THUMB, THUMB, 6);
                BufferedImage image = layer.getImage();
                if (image != null) {
                    final double scale = Math.min((double) THUMB / image.getWidth(),
                                                  (double) THUMB / image.getHeight());
                    final int w = Math.max(1, (int) (image.getWidth() * scale));
                    final int h = Math.max(1, (int) (image.getHeight() * scale));
                    ImageDocument.applyQuality(g);
                    g.drawImage(image, (THUMB - w) / 2, (THUMB - h) / 2, w, h, null);
                }
                if (!layer.getAnnotations().isEmpty() && image == null) {
                    g.setColor(new Color(0xE11D48));
                    g.drawLine(6, THUMB - 8, THUMB - 6, 8);
                }
                g.setColor(ImagingTheme.border());
                g.drawRect(0, 0, THUMB - 1, THUMB - 1);
            } finally {
                g.dispose();
            }
        }
    }
}
