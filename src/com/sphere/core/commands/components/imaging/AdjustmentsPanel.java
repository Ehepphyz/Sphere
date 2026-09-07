package com.sphere.components.imaging;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Tone controls with a live histogram.
 *
 * The sliders preview against a downscaled copy so dragging stays responsive on
 * a large image; the full-size pass runs once, when the change is applied.
 */
public final class AdjustmentsPanel extends JPanel {

    private final ImageCanvas canvas;
    private final ImageAdjustments settings = new ImageAdjustments();
    private final Histogram histogram = new Histogram();

    private final JSlider brightness = slider(-100, 100, 0);
    private final JSlider contrast = slider(-100, 100, 0);
    private final JSlider gamma = slider(10, 400, 100);
    private final JSlider saturation = slider(-100, 100, 0);
    private final JSlider black = slider(0, 254, 0);
    private final JSlider white = slider(1, 255, 255);
    private final JCheckBox grayscale = check("Grayscale");
    private final JCheckBox invert = check("Invert");

    private BufferedImage original;
    private BufferedImage preview;
    private boolean updating;

    public AdjustmentsPanel(ImageCanvas canvas) {
        this.canvas = canvas;
        setLayout(new BorderLayout());
        setBackground(ImagingTheme.panel());

        add(ImagingTheme.sectionLabel("Adjustments"), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);

        canvas.addChangeListener(this::captureSource);
        captureSource();
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

    private JComponent buildBody() {
        ImagingTheme.Surface body = ImagingTheme.stack(false);
        body.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        histogram.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(histogram);
        body.add(Box.createVerticalStrut(8));

        body.add(labelled("Brightness", brightness));
        body.add(labelled("Contrast", contrast));
        body.add(labelled("Gamma", gamma));
        body.add(labelled("Saturation", saturation));
        body.add(labelled("Black point", black));
        body.add(labelled("White point", white));

        ImagingTheme.Surface flags = ImagingTheme.strip(false);
        flags.setAlignmentX(Component.LEFT_ALIGNMENT);
        flags.add(grayscale);
        flags.add(Box.createHorizontalStrut(8));
        flags.add(invert);
        flags.add(Box.createHorizontalGlue());
        body.add(Box.createVerticalStrut(4));
        body.add(flags);

        ImagingTheme.Surface actions = ImagingTheme.strip(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        JButton auto = ImagingTheme.textButton("Auto", "Set the levels from the histogram");
        auto.addActionListener(e -> autoLevels());
        JButton reset = ImagingTheme.textButton("Reset", "Back to the untouched image");
        reset.addActionListener(e -> reset());
        JButton apply = ImagingTheme.textButton("Apply", "Write the change into the layer");
        apply.addActionListener(e -> apply());

        actions.add(auto);
        actions.add(Box.createHorizontalStrut(4));
        actions.add(reset);
        actions.add(Box.createHorizontalGlue());
        actions.add(apply);
        body.add(actions);
        body.add(Box.createVerticalGlue());
        return body;
    }

    private JComponent labelled(String name, JSlider slider) {
        ImagingTheme.Surface row = ImagingTheme.panelOf(new BorderLayout(6, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        JLabel caption = new JLabel(name);
        caption.setForeground(ImagingTheme.subduedText());
        caption.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        caption.setPreferredSize(new Dimension(74, 16));

        JLabel value = new JLabel();
        value.setForeground(ImagingTheme.subduedText());
        value.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        value.setPreferredSize(new Dimension(38, 16));
        value.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);

        slider.addChangeListener(e -> {
            value.setText(slider == gamma
                ? String.format(java.util.Locale.ROOT, "%.2f", slider.getValue() / 100.0)
                : String.valueOf(slider.getValue()));
            if (!updating) {
                readSettings();
                updatePreview();
            }
        });
        value.setText(slider == gamma ? "1.00" : String.valueOf(slider.getValue()));

        row.add(caption, BorderLayout.WEST);
        row.add(slider, BorderLayout.CENTER);
        row.add(value, BorderLayout.EAST);
        return row;
    }

    private JSlider slider(int min, int max, int value) {
        JSlider s = new JSlider(min, max, value);
        s.setBackground(ImagingTheme.panel());
        s.setFocusable(false);
        return s;
    }

    private JCheckBox check(String label) {
        JCheckBox box = new JCheckBox(label);
        box.setBackground(ImagingTheme.panel());
        box.setForeground(ImagingTheme.text());
        box.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        box.setFocusPainted(false);
        box.addActionListener(e -> {
            readSettings();
            updatePreview();
        });
        return box;
    }

    // ---- behavior ----------------------------------------------------------

    private void captureSource() {
        ImageDocument doc = canvas.getDocument();
        original = (doc == null || !doc.getActiveLayer().hasPixels())
            ? null : doc.getActiveLayer().getImage();
        // A working copy small enough that a slider drag repaints without lag.
        preview = original == null ? null : ImageFileIO.scaleToFit(original, 480);
        histogram.setSource(preview);
        histogram.repaint();
    }

    private void readSettings() {
        settings.brightness = brightness.getValue();
        settings.contrast = contrast.getValue();
        settings.gamma = gamma.getValue() / 100.0;
        settings.saturation = saturation.getValue();
        settings.blackPoint = Math.min(black.getValue(), white.getValue() - 1);
        settings.whitePoint = Math.max(white.getValue(), black.getValue() + 1);
        settings.grayscale = grayscale.isSelected();
        settings.invert = invert.isSelected();
    }

    private void updatePreview() {
        histogram.setCurve(settings.isIdentity() ? null : settings.buildCurve());
        if (preview != null) {
            histogram.setSource(settings.isIdentity() ? preview : settings.apply(preview));
        }
        histogram.repaint();
    }

    private void autoLevels() {
        if (preview == null) {
            return;
        }
        int[] points = ImageAdjustments.autoLevels(preview, 0.005);
        updating = true;
        try {
            black.setValue(points[0]);
            white.setValue(points[1]);
        } finally {
            updating = false;
        }
        readSettings();
        updatePreview();
    }

    private void reset() {
        updating = true;
        try {
            brightness.setValue(0);
            contrast.setValue(0);
            gamma.setValue(100);
            saturation.setValue(0);
            black.setValue(0);
            white.setValue(255);
            grayscale.setSelected(false);
            invert.setSelected(false);
        } finally {
            updating = false;
        }
        settings.reset();
        captureSource();
        updatePreview();
    }

    private void apply() {
        ImageDocument doc = canvas.getDocument();
        if (doc == null || settings.isIdentity()) {
            return;
        }
        ImageLayer layer = doc.getActiveLayer();
        if (layer.isLocked()) {
            return;
        }
        // Marks on this layer become pixels first, otherwise an adjustment would
        // silently leave them untouched while everything else changed.
        layer.flattenAnnotations(doc.getWidth(), doc.getHeight());
        if (!layer.hasPixels()) {
            return;
        }
        doc.pushHistory();
        layer.setImage(settings.apply(layer.getImage()));
        canvas.refresh();
        reset();
    }

    /** Red, green, blue and luma counts, plus the tone curve on top. */
    private static final class Histogram extends JComponent {
        private int[][] bins = new int[4][256];
        private int[] curve;

        Histogram() {
            setPreferredSize(new Dimension(214, 92));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 92));
        }

        void setSource(BufferedImage image) {
            bins = ImageAdjustments.histogram(image);
        }

        void setCurve(int[] curve) {
            this.curve = curve;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                   RenderingHints.VALUE_ANTIALIAS_ON);
                final int w = getWidth();
                final int h = getHeight();
                g.setColor(ImagingTheme.canvasGround());
                g.fillRect(0, 0, w, h);

                int peak = 1;
                for (int i = 0; i < 256; i++) {
                    peak = Math.max(peak, bins[3][i]);
                }

                final Color[] tint = {
                    new Color(0x80EF4444, true),
                    new Color(0x8022C55E, true),
                    new Color(0x803B82F6, true)
                };
                for (int channel = 0; channel < 3; channel++) {
                    g.setColor(tint[channel]);
                    for (int i = 0; i < 256; i++) {
                        final int value = Math.min(bins[channel][i], peak);
                        final int bar = (int) Math.round((double) value / peak * (h - 4));
                        final int x = (int) Math.round(i / 255.0 * (w - 1));
                        g.drawLine(x, h - bar, x, h);
                    }
                }

                if (curve != null) {
                    g.setColor(ImagingTheme.accent());
                    g.setStroke(new java.awt.BasicStroke(1.4f));
                    int px = 0;
                    int py = h - 1 - (int) Math.round(curve[0] / 255.0 * (h - 2));
                    for (int i = 1; i < 256; i++) {
                        final int x = (int) Math.round(i / 255.0 * (w - 1));
                        final int y = h - 1 - (int) Math.round(curve[i] / 255.0 * (h - 2));
                        g.drawLine(px, py, x, y);
                        px = x;
                        py = y;
                    }
                }

                g.setColor(ImagingTheme.border());
                g.drawRect(0, 0, w - 1, h - 1);
            } finally {
                g.dispose();
            }
        }
    }
}
