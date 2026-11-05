package com.sphere.components.imaging;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;

/**
 * The editor: toolbar, canvas with rulers, side panels and status bar.
 *
 * Everything the user does goes through the canvas, which owns the document; the
 * panels around it only read and command.
 */
public final class ImageEditorPanel extends JPanel {

    private final ImageCanvas canvas = new ImageCanvas();
    private final LayersPanel layers;
    private final AdjustmentsPanel adjustments;
    private final DigitizerPanel digitizer;

    private final JLabel fileLabel = new JLabel();
    private final JLabel sizeLabel = new JLabel();
    private final JLabel pointerLabel = new JLabel();
    private final JLabel colorLabel = new JLabel();
    private final JLabel zoomLabel = new JLabel();
    private final ColorWell strokeWell = new ColorWell(true);
    private final ColorWell fillWell = new ColorWell(false);

    private Runnable titleListener;

    /**
     * Where a picture opened from another document goes back: a picture of a
     * PDF page, say. Saving puts it back; the document shows the edits while
     * they are made.
     */
    public interface Link {
        /** Where the picture comes from, as a phrase: "picture on page 3 of paper.pdf". */
        String label();

        /** Puts the edited picture back. */
        void apply(BufferedImage image);

        /** Shows the picture as it is being edited (a moment after each change). */
        default void preview(BufferedImage image) {
        }

        /** The editor let go of the picture (closed, or another one opened). */
        default void closed() {
        }

        /** Two links to the same picture have the same key (its tab is then reused). */
        default Object key() {
            return this;
        }
    }

    private Link link;
    private String linkedName;
    private final JLabel linkLabel = new JLabel();
    private JComponent linkBar;
    private final javax.swing.Timer previewTimer = new javax.swing.Timer(350, e -> runPreview());
    private long previewSignature;

    public ImageEditorPanel() {
        setLayout(new BorderLayout());
        setBackground(ImagingTheme.panel());

        canvas.setBackground(ImagingTheme.canvasGround());
        layers = new LayersPanel(canvas);
        adjustments = new AdjustmentsPanel(canvas);
        digitizer = new DigitizerPanel(canvas);

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(buildToolbar(), BorderLayout.NORTH);
        linkBar = buildLinkBar();
        linkBar.setVisible(false);
        north.add(linkBar, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        canvas.setPointerListener(this::showPointer);
        canvas.addChangeListener(this::refreshStatus);
        canvas.addChangeListener(() -> {
            if (link != null) {
                previewTimer.restart();
            }
        });
        previewTimer.setRepeats(false);
        installShortcuts();
        refreshStatus();
    }

    /* ---- linked pictures ----------------------------------------------------- */

    /** The strip shown while the picture belongs to another document: where it goes, and the way back. */
    private JComponent buildLinkBar() {
        // the way back stays in sight however narrow the tab: the words give way, the buttons do not
        ImagingTheme.Surface bar = new ImagingTheme.Surface(new BorderLayout(8, 0), true);
        bar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ImagingTheme.border()),
            BorderFactory.createEmptyBorder(4, 10, 4, 8)));
        linkLabel.setFont(ImagingTheme.uiFont(Font.PLAIN, 12f));
        linkLabel.setForeground(ImagingTheme.text());
        linkLabel.setMinimumSize(new Dimension(40, 10));
        bar.add(linkLabel, BorderLayout.CENTER);
        JButton back = ImagingTheme.textButton("Put Back", "Write the edited picture into the document it came from (Ctrl+S)");
        back.addActionListener(e -> save());
        JButton copy = ImagingTheme.textButton("Save a Copy…", "Write the picture to a file of its own");
        copy.addActionListener(e -> saveAs());
        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(back);
        buttons.add(Box.createHorizontalStrut(4));
        buttons.add(copy);
        bar.add(buttons, BorderLayout.EAST);
        return bar;
    }

    /**
     * Opens a picture that belongs to another document: saving puts it back
     * there through the link. False when the user kept the picture open now.
     */
    public boolean openLinked(BufferedImage image, String name, Link newLink) {
        if (!releaseLink(true)) {
            return false;
        }
        ImageDocument doc = new ImageDocument(image, null, "png");
        canvas.setDocument(doc);
        layers.reload();
        link = newLink;
        linkedName = name;
        previewSignature = signature(doc);
        linkLabel.setText("From the " + newLink.label() + " · shown live · Ctrl+S puts it back");
        linkLabel.setToolTipText("<html>This picture belongs to the " + newLink.label() + ".<br>The page shows your edits as "
            + "you make them; Ctrl+S (or Put Back) writes them into it,<br>as one step its Undo can take back.</html>");
        linkBar.setVisible(true);
        refreshStatus();
        SwingUtilities.invokeLater(canvas::zoomToFit);
        return true;
    }

    public Link getLink() {
        return link;
    }

    /**
     * Lets go of the linked picture (the document shows its own again). With
     * ask, unsaved edits are offered to be put back first; false when the
     * user cancels.
     */
    public boolean releaseLink(boolean ask) {
        if (link == null) {
            return true;
        }
        ImageDocument doc = canvas.getDocument();
        if (ask && doc != null && doc.isDirty()) {
            Object[] options = { "Put Back", "Discard", "Cancel" };
            int r = JOptionPane.showOptionDialog(this, "Put the edited picture back into the " + link.label() + "?",
                "Sphere Image", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
            if (r == 2 || r == JOptionPane.CLOSED_OPTION) {
                return false;
            }
            if (r == 0) {
                applyLink();
            }
        }
        previewTimer.stop();
        Link l = link;
        link = null;
        linkedName = null;
        linkBar.setVisible(false);
        l.closed();
        return true;
    }

    private void applyLink() {
        ImageDocument doc = canvas.getDocument();
        if (doc == null || link == null) {
            return;
        }
        try {
            link.apply(doc.render(false));
            doc.markClean();
            previewSignature = signature(doc);
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(this, "Could not put the picture back: " + e.getMessage(), "Sphere Image",
                JOptionPane.ERROR_MESSAGE);
        }
        refreshStatus();
    }

    /** Hands the picture as it stands to the link, when it changed since the last time. */
    private void runPreview() {
        ImageDocument doc = canvas.getDocument();
        if (link == null || doc == null) {
            return;
        }
        long sig = signature(doc);
        if (sig == previewSignature) {
            return;
        }
        previewSignature = sig;
        link.preview(doc.render(false));
    }

    /**
     * A cheap fingerprint of what the picture looks like: its layers, their
     * state, and a few hundred pixels of each. A zoom changes nothing of it.
     */
    private static long signature(ImageDocument doc) {
        long h = doc.getWidth() * 31L + doc.getHeight();
        for (ImageLayer layer : doc.getLayers()) {
            h = h * 31 + System.identityHashCode(layer.getImage());
            h = h * 31 + (layer.isVisible() ? 1 : 0);
            h = h * 31 + Double.hashCode(layer.getOpacity());
            h = h * 31 + layer.getAnnotations().size();
            for (Annotation a : layer.getAnnotations()) {
                h = h * 31 + java.util.Objects.hash(a.kind, a.x1, a.y1, a.x2, a.y2, a.color, a.fillColor, a.strokeWidth,
                                                     a.text, a.fontSize, a.bold, a.freehand.getBounds2D());
            }
            BufferedImage img = layer.getImage();
            if (img != null) {
                int w = img.getWidth();
                int ht = img.getHeight();
                for (int k = 0; k < 512; k++) {
                    int x = (int) ((k * 7919L) % w);
                    int y = (int) ((k * 104729L) % ht);
                    h = h * 31 + img.getRGB(x, y);
                }
            }
        }
        return h;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(ImagingTheme.panel());
            g.fillRect(0, 0, getWidth(), getHeight());
        } finally {
            g.dispose();
        }
    }

    public ImageCanvas getCanvas() {
        return canvas;
    }

    public void setTitleListener(Runnable listener) {
        this.titleListener = listener;
    }

    // ---- document ----------------------------------------------------------

    public void open(File file) {
        if (!releaseLink(true)) {
            return;
        }
        try {
            ImageDocument doc = ImageFileIO.open(file);
            canvas.setDocument(doc);
            layers.reload();
            refreshStatus();
            if (titleListener != null) {
                titleListener.run();
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                "Could not open " + file.getName() + ": " + e.getMessage(),
                "Open", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void openBlank(int width, int height) {
        if (!releaseLink(true)) {
            return;
        }
        canvas.setDocument(new ImageDocument(width, height));
        layers.reload();
        refreshStatus();
    }

    public ImageDocument getDocument() {
        return canvas.getDocument();
    }

    public String documentTitle() {
        ImageDocument doc = canvas.getDocument();
        if (doc == null) {
            return "Image";
        }
        final String name = link != null && linkedName != null ? linkedName
            : doc.getFile() == null ? "Untitled" : doc.getFile().getName();
        return doc.isDirty() ? name + " *" : name;
    }

    public void save() {
        ImageDocument doc = canvas.getDocument();
        if (doc == null) {
            return;
        }
        if (link != null) {
            // a picture of another document goes back there
            applyLink();
            return;
        }
        // An SVG has no writer here, and neither has an untitled document, so both
        // go through the dialog rather than failing at the last step. Nor does a
        // 16-bit or multi-page TIFF: the editor holds an 8-bit copy of one page,
        // and writing that over the original would throw the rest away.
        if (doc.getFile() == null || "svg".equals(doc.getFormat()) || doc.getSourceNote() != null) {
            if (doc.getSourceNote() != null) {
                JOptionPane.showMessageDialog(this, doc.getFile().getName() + " holds " + doc.getSourceNote()
                    + ".\nThe editor works on an 8-bit copy, so the original is kept: choose a new name.",
                    "Save", JOptionPane.INFORMATION_MESSAGE);
            }
            saveAs();
            return;
        }
        write(doc.getFile(), doc.getFormat());
    }

    /** The formats a picture can be written in, the chosen one first. */
    private static void formatFilters(JFileChooser chooser) {
        chooser.setFileFilter(new FileNameExtensionFilter("PNG image", "png"));
        chooser.addChoosableFileFilter(new FileNameExtensionFilter(
            "TIFF image (LZW, " + ImageFileIO.TIFF_DPI + " dpi)", "tif", "tiff"));
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("JPEG image", "jpg", "jpeg"));
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("BMP image", "bmp"));
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("GIF image", "gif"));
    }

    /**
     * The file the dialog chose, with the extension of the filter it was
     * chosen under when the name carries none: picking "TIFF" and typing "fig"
     * means fig.tif, not fig.png.
     */
    private static File withExtension(JFileChooser chooser) {
        File target = chooser.getSelectedFile();
        if (ImageFileIO.extension(target.getName()).isEmpty()) {
            String ext = "png";
            if (chooser.getFileFilter() instanceof FileNameExtensionFilter filter) {
                ext = filter.getExtensions()[0];
            }
            target = new File(target.getParentFile(), target.getName() + "." + ext);
        }
        return target;
    }

    public void saveAs() {
        ImageDocument doc = canvas.getDocument();
        if (doc == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        formatFilters(chooser);
        if (doc.getFile() != null) {
            String base = doc.getFile().getName().replaceFirst("\\.[^.]+$", "");
            // A TIFF edited stays a TIFF unless told otherwise; its 8-bit copy
            // gets a name of its own so the original is not proposed.
            final boolean tiff = "tiff".equals(doc.getFormat()) || "tif".equals(doc.getFormat());
            chooser.setSelectedFile(new File(doc.getFile().getParentFile(),
                base + (doc.getSourceNote() != null ? "_edited" : "") + (tiff ? ".tif" : ".png")));
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = withExtension(chooser);
        write(target, ImageFileIO.extension(target.getName()));
    }

    private void write(File target, String format) {
        try {
            ImageFileIO.save(canvas.getDocument(), target, format, 0.92f);
            refreshStatus();
            if (titleListener != null) {
                titleListener.run();
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                "Could not save: " + e.getMessage(), "Save", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportSized() {
        ImageDocument doc = canvas.getDocument();
        if (doc == null) {
            return;
        }
        JSpinner width = new JSpinner(
            new SpinnerNumberModel(doc.getWidth(), 1, 30000, 1));
        JSpinner height = new JSpinner(
            new SpinnerNumberModel(doc.getHeight(), 1, 30000, 1));
        // What a TIFF states as its resolution: the pixels are not changed, the
        // print size is, and a journal reads the figure's size from it.
        JSpinner dpi = new JSpinner(new SpinnerNumberModel(ImageFileIO.TIFF_DPI, 72, 2400, 50));

        JPanel form = new JPanel(new java.awt.GridLayout(0, 2, 6, 6));
        form.add(new JLabel("Width"));
        form.add(width);
        form.add(new JLabel("Height"));
        form.add(height);
        form.add(new JLabel("Resolution, TIFF (dpi)"));
        form.add(dpi);
        if (doc.isVector()) {
            JLabel note = new JLabel("Redrawn from the vector, so any size stays sharp.");
            note.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
            form.add(note);
            form.add(new JLabel());
        }

        if (JOptionPane.showConfirmDialog(this, form, "Export",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
            != JOptionPane.OK_OPTION) {
            return;
        }

        JFileChooser chooser = new JFileChooser();
        formatFilters(chooser);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = withExtension(chooser);
        String format = ImageFileIO.extension(target.getName());
        try {
            ImageFileIO.export(canvas.getDocument(), target, format,
                               (Integer) width.getValue(), (Integer) height.getValue(), 0.92f,
                               (Integer) dpi.getValue());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                "Could not export: " + e.getMessage(), "Export", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---- toolbar -----------------------------------------------------------

    private JComponent buildToolbar() {
        ImagingTheme.Surface bar = ImagingTheme.strip(true);
        bar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ImagingTheme.border()),
            BorderFactory.createEmptyBorder(5, 6, 5, 6)));

        ButtonGroup tools = new ButtonGroup();
        bar.add(tool(tools, "select", ImageCanvas.Tool.SELECT, "Select and move", true));
        bar.add(tool(tools, "pan", ImageCanvas.Tool.PAN, "Pan the view", false));
        bar.add(tool(tools, "crop", ImageCanvas.Tool.CROP, "Crop", false));
        bar.add(gap());
        bar.add(tool(tools, "arrow", ImageCanvas.Tool.ARROW, "Arrow", false));
        bar.add(tool(tools, "line", ImageCanvas.Tool.LINE, "Line", false));
        bar.add(tool(tools, "rectangle", ImageCanvas.Tool.RECTANGLE, "Rectangle", false));
        bar.add(tool(tools, "ellipse", ImageCanvas.Tool.ELLIPSE, "Ellipse", false));
        bar.add(tool(tools, "freehand", ImageCanvas.Tool.FREEHAND, "Freehand", false));
        bar.add(tool(tools, "text", ImageCanvas.Tool.TEXT, "Text", false));
        bar.add(tool(tools, "measure", ImageCanvas.Tool.MEASURE, "Measure", false));
        bar.add(gap());
        bar.add(tool(tools, "digitize", ImageCanvas.Tool.DIGITIZE,
                     "Read data points off a plot", false));
        bar.add(gap());

        bar.add(strokeWell);
        bar.add(Box.createHorizontalStrut(3));
        bar.add(fillWell);
        bar.add(Box.createHorizontalStrut(6));

        JSpinner width = new JSpinner(new SpinnerNumberModel(2.0, 0.2, 60.0, 0.5));
        width.setMaximumSize(new Dimension(58, 26));
        width.setToolTipText("Stroke width");
        width.addChangeListener(e ->
            canvas.setStrokeWidth(((Number) width.getValue()).doubleValue()));
        bar.add(width);
        bar.add(Box.createHorizontalStrut(4));

        JSpinner fontSize = new JSpinner(new SpinnerNumberModel(16, 4, 400, 1));
        fontSize.setMaximumSize(new Dimension(58, 26));
        fontSize.setToolTipText("Text size");
        fontSize.addChangeListener(e ->
            canvas.setFontSize(((Number) fontSize.getValue()).intValue()));
        bar.add(fontSize);

        bar.add(gap());
        bar.add(action("crop", "Apply the crop", () -> canvas.applyCrop()));
        bar.add(action("undo", "Undo", this::undo));
        bar.add(action("redo", "Redo", this::redo));
        bar.add(gap());
        bar.add(action("zoom-out", "Zoom out", () -> canvas.setZoom(canvas.getZoom() / 1.25)));
        bar.add(action("zoom-in", "Zoom in", () -> canvas.setZoom(canvas.getZoom() * 1.25)));
        bar.add(action("fit", "Fit to the window", canvas::zoomToFit));

        JButton actual = ImagingTheme.textButton("1:1", "Show at actual size");
        actual.addActionListener(e -> canvas.zoomToActual());
        bar.add(actual);

        bar.add(Box.createHorizontalGlue());

        JButton rotate = ImagingTheme.textButton("Rotate", "Turn a quarter clockwise");
        rotate.addActionListener(e -> {
            if (canvas.getDocument() != null) {
                canvas.getDocument().rotate(1);
                canvas.refresh();
            }
        });
        JButton flipH = ImagingTheme.textButton("Flip H", "Mirror left to right");
        flipH.addActionListener(e -> {
            if (canvas.getDocument() != null) {
                canvas.getDocument().flip(true);
                canvas.refresh();
            }
        });
        JButton flipV = ImagingTheme.textButton("Flip V", "Mirror top to bottom");
        flipV.addActionListener(e -> {
            if (canvas.getDocument() != null) {
                canvas.getDocument().flip(false);
                canvas.refresh();
            }
        });
        JButton resize = ImagingTheme.textButton("Resize", "Change the image size");
        resize.addActionListener(e -> resize());
        JButton export = ImagingTheme.textButton("Export", "Write at a chosen size");
        export.addActionListener(e -> exportSized());

        bar.add(rotate);
        bar.add(flipH);
        bar.add(flipV);
        bar.add(resize);
        bar.add(gap());
        bar.add(export);
        return bar;
    }

    private Component gap() {
        JPanel line = ImagingTheme.separator();
        ImagingTheme.Surface holder = ImagingTheme.strip(true);
        holder.add(Box.createHorizontalStrut(5));
        holder.add(line);
        holder.add(Box.createHorizontalStrut(5));
        holder.setMaximumSize(new Dimension(11, 26));
        return holder;
    }

    private JToggleButton tool(ButtonGroup group, String icon,
                               ImageCanvas.Tool tool, String tip, boolean initial) {
        JToggleButton button = new JToggleButton(
            ImagingTheme.icon(icon, 17, ImagingTheme.text()));
        button.setToolTipText(tip);
        ImagingTheme.styleButton(button);
        button.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        button.setSelected(initial);
        button.addActionListener(e -> {
            canvas.setTool(tool);
            if (tool == ImageCanvas.Tool.DIGITIZE) {
                selectSidePanel(2);
            }
        });
        group.add(button);
        return button;
    }

    private JButton action(String icon, String tip, Runnable run) {
        JButton button = new JButton(ImagingTheme.icon(icon, 17, ImagingTheme.text()));
        button.setToolTipText(tip);
        ImagingTheme.styleButton(button);
        button.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        button.addActionListener(e -> run.run());
        return button;
    }

    // ---- center ------------------------------------------------------------

    private ImagingTheme.Tabs sidePanels;

    private JComponent buildCenter() {
        JScrollPane scroll = new JScrollPane(canvas);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(true);
        scroll.setBackground(ImagingTheme.canvasGround());
        scroll.getViewport().setOpaque(true);
        scroll.getViewport().setBackground(ImagingTheme.canvasGround());
        scroll.setRowHeaderView(new Ruler(canvas, true));
        scroll.setColumnHeaderView(new Ruler(canvas, false));
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getHorizontalScrollBar().setUnitIncrement(24);

        sidePanels = new ImagingTheme.Tabs();
        sidePanels.addTab("Layers", layers);
        sidePanels.addTab("Tone", adjustments);
        sidePanels.addTab("Digitize", digitizer);
        sidePanels.setPreferredSize(new Dimension(252, 500));
        sidePanels.setSelectedIndex(0);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll, sidePanels);
        split.setResizeWeight(1.0);
        split.setDividerSize(4);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setBackground(ImagingTheme.border());
        return split;
    }

    private void selectSidePanel(int index) {
        if (sidePanels != null && index < sidePanels.getTabCount()) {
            sidePanels.setSelectedIndex(index);
        }
    }

    // ---- status bar --------------------------------------------------------

    private JComponent buildStatusBar() {
        ImagingTheme.Surface bar = ImagingTheme.strip(true);
        bar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, ImagingTheme.border()),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)));

        for (JLabel label : new JLabel[] {fileLabel, sizeLabel, pointerLabel,
                                          colorLabel, zoomLabel}) {
            label.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
            label.setForeground(ImagingTheme.subduedText());
        }
        fileLabel.setForeground(ImagingTheme.text());

        bar.add(fileLabel);
        bar.add(Box.createHorizontalStrut(14));
        bar.add(sizeLabel);
        bar.add(Box.createHorizontalGlue());
        bar.add(pointerLabel);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(colorLabel);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(zoomLabel);
        return bar;
    }

    private void showPointer(Point2D image) {
        ImageDocument doc = canvas.getDocument();
        if (doc == null) {
            return;
        }
        final int x = (int) Math.floor(image.getX());
        final int y = (int) Math.floor(image.getY());
        pointerLabel.setText(x + ", " + y + " px");

        // The digitizer turns the same position into the plot's own units, which
        // is what the user is actually reading while picking points.
        if (canvas.getTool() == ImageCanvas.Tool.DIGITIZE
            && canvas.getDigitizer().isCalibrated()) {
            Point2D.Double data = canvas.getDigitizer().toData(image.getX(), image.getY());
            pointerLabel.setText(String.format(Locale.ROOT, "x %.5g   y %.5g", data.x, data.y));
        }

        BufferedImage flat = null;
        if (x >= 0 && y >= 0 && x < doc.getWidth() && y < doc.getHeight()) {
            ImageLayer layer = doc.getActiveLayer();
            flat = layer.hasPixels() ? layer.getImage() : null;
        }
        if (flat != null && x < flat.getWidth() && y < flat.getHeight()) {
            final int argb = flat.getRGB(x, y);
            colorLabel.setText(String.format("#%06X  a%d",
                argb & 0xFFFFFF, (argb >>> 24) & 0xFF));
        } else {
            colorLabel.setText("");
        }
        canvas.repaint();
    }

    private void refreshStatus() {
        ImageDocument doc = canvas.getDocument();
        if (doc == null) {
            fileLabel.setText("No image");
            sizeLabel.setText("");
            zoomLabel.setText("");
            return;
        }
        fileLabel.setText(documentTitle());
        sizeLabel.setText(doc.getWidth() + " x " + doc.getHeight() + " px"
            + (doc.isVector() ? "   vector" : "")
            + "   " + doc.getLayers().size() + " layer"
            + (doc.getLayers().size() == 1 ? "" : "s")
            + (doc.getSourceNote() != null ? "   file: " + doc.getSourceNote() + ", shown stretched to 8 bits" : ""));
        zoomLabel.setText(Math.round(canvas.getZoom() * 100) + "%");
        layers.reload();
        if (titleListener != null) {
            titleListener.run();
        }
    }

    // ---- commands ----------------------------------------------------------

    public void undo() {
        if (canvas.getDocument() != null) {
            canvas.getDocument().undo();
            canvas.refresh();
        }
    }

    public void redo() {
        if (canvas.getDocument() != null) {
            canvas.getDocument().redo();
            canvas.refresh();
        }
    }

    private void resize() {
        ImageDocument doc = canvas.getDocument();
        if (doc == null) {
            return;
        }
        JSpinner width = new JSpinner(new SpinnerNumberModel(doc.getWidth(), 1, 30000, 1));
        JSpinner height = new JSpinner(new SpinnerNumberModel(doc.getHeight(), 1, 30000, 1));
        javax.swing.JCheckBox keep = new javax.swing.JCheckBox("Keep the proportions", true);

        final double ratio = (double) doc.getWidth() / doc.getHeight();
        width.addChangeListener(e -> {
            if (keep.isSelected()) {
                height.setValue((int) Math.max(1,
                    Math.round(((Number) width.getValue()).intValue() / ratio)));
            }
        });

        JPanel form = new JPanel(new java.awt.GridLayout(0, 2, 6, 6));
        form.add(new JLabel("Width"));
        form.add(width);
        form.add(new JLabel("Height"));
        form.add(height);
        form.add(keep);
        form.add(new JLabel());

        if (JOptionPane.showConfirmDialog(this, form, "Resize",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
            == JOptionPane.OK_OPTION) {
            doc.resize(((Number) width.getValue()).intValue(),
                       ((Number) height.getValue()).intValue(), true);
            canvas.refresh();
        }
    }

    private void installShortcuts() {
        bind("control Z", this::undo);
        bind("control Y", this::redo);
        bind("control shift Z", this::redo);
        bind("control S", this::save);
        bind("control shift S", this::saveAs);
        bind("control E", this::exportSized);
        bind("control 0", canvas::zoomToFit);
        bind("control 1", canvas::zoomToActual);
        bind("control PLUS", () -> canvas.setZoom(canvas.getZoom() * 1.25));
        bind("control EQUALS", () -> canvas.setZoom(canvas.getZoom() * 1.25));
        bind("control MINUS", () -> canvas.setZoom(canvas.getZoom() / 1.25));
        bind("DELETE", canvas::deleteSelected);
        bind("ENTER", canvas::applyCrop);
        bind("ESCAPE", () -> {
            canvas.clearCrop();
            canvas.cancelReference();
        });
    }

    private void bind(String stroke, Runnable action) {
        final String name = "imaging:" + stroke;
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(stroke), name);
        getActionMap().put(name, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    // ---- color wells -------------------------------------------------------

    /** A swatch that opens the color chooser. Fill can also be turned off. */
    private final class ColorWell extends JButton {
        private final boolean strokeSlot;

        ColorWell(boolean strokeSlot) {
            this.strokeSlot = strokeSlot;
            setPreferredSize(new Dimension(26, 24));
            setMaximumSize(new Dimension(26, 24));
            setToolTipText(strokeSlot
                ? "Stroke color"
                : "Fill color, right click to turn the fill off");
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));

            addActionListener(e -> {
                Color chosen = JColorChooser.showDialog(ImageEditorPanel.this,
                    strokeSlot ? "Stroke color" : "Fill color", current());
                if (chosen != null) {
                    if (strokeSlot) {
                        canvas.setStrokeColor(chosen);
                    } else {
                        canvas.setFillColor(chosen);
                    }
                    repaint();
                }
            });
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                    if (!strokeSlot && SwingUtilities.isRightMouseButton(e)) {
                        canvas.setFillColor(null);
                        repaint();
                    }
                }
            });
        }

        private Color current() {
            Color color = strokeSlot ? canvas.getStrokeColor() : canvas.getFillColor();
            return color == null ? Color.WHITE : color;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                                   java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                Color color = strokeSlot ? canvas.getStrokeColor() : canvas.getFillColor();
                if (color == null) {
                    ImageDocument.paintChecker(g, getWidth(), getHeight(), 5);
                    g.setColor(new Color(0xE11D48));
                    g.drawLine(4, getHeight() - 5, getWidth() - 5, 4);
                } else {
                    g.setColor(color);
                    g.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, 5, 5);
                }
                g.setColor(ImagingTheme.border());
                g.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, 5, 5);
            } finally {
                g.dispose();
            }
        }
    }

    /** A ruler graduated in image pixels, whose ticks follow the zoom. */
    private static final class Ruler extends JComponent {
        private static final int THICKNESS = 20;
        private final ImageCanvas canvas;
        private final boolean vertical;

        Ruler(ImageCanvas canvas, boolean vertical) {
            this.canvas = canvas;
            this.vertical = vertical;
            setPreferredSize(vertical
                ? new Dimension(THICKNESS, 100) : new Dimension(100, THICKNESS));
            canvas.addChangeListener(this::repaint);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension parent = canvas.getPreferredSize();
            return vertical ? new Dimension(THICKNESS, parent.height)
                            : new Dimension(parent.width, THICKNESS);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(ImagingTheme.surface());
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(ImagingTheme.border());
                if (vertical) {
                    g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
                } else {
                    g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
                }

                final double zoom = canvas.getZoom();
                if (zoom <= 0) {
                    return;
                }
                // The step is chosen so labels never collide, whatever the zoom.
                double step = 10;
                while (step * zoom < 48) {
                    step *= step % 25 == 0 ? 4 : 5;
                }

                g.setFont(ImagingTheme.uiFont(Font.PLAIN, 9f));
                g.setColor(ImagingTheme.subduedText());
                final int span = vertical ? getHeight() : getWidth();
                for (double v = 0; v * zoom < span; v += step) {
                    final int p = (int) Math.round(v * zoom);
                    if (vertical) {
                        g.drawLine(getWidth() - 6, p, getWidth() - 1, p);
                        g.drawString(String.valueOf((int) v), 2, p + 9);
                    } else {
                        g.drawLine(p, getHeight() - 6, p, getHeight() - 1);
                        g.drawString(String.valueOf((int) v), p + 3, 11);
                    }
                }
            } finally {
                g.dispose();
            }
        }
    }
}
