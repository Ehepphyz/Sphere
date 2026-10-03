package com.sphere.components.rootview;

import com.sphere.components.imaging.ImageFileIO;
import com.sphere.components.imaging.ImagePreviewPane;
import com.sphere.components.imaging.ImagingTheme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * The Plots tab of the main window: the last plot, and the ones before it.
 *
 * Two kinds of plot arrive here. The ones Sphere draws itself come in as
 * numbers, and can be restyled afterwards. The ones a script draws come in as
 * a file, whatever language wrote it, and are shown as the picture they are.
 */
public final class RootPlotsPanel extends ViewSurface {

    private static RootPlotsPanel shared;

    private final RootPlot plot = new RootPlot();
    private final RootGallery gallery = new RootGallery();
    private final ImagePreviewPane picture = new ImagePreviewPane();
    private final CardLayout cards = new CardLayout();
    private final JPanel stage = new JPanel(cards);
    private final RootImageWatch watch = new RootImageWatch(this::imageAppeared);
    private final JButton openInEditor =
        ImagingTheme.textButton("Edit...", "Open this picture in the image editor");

    private final JToggleButton bars = toggle("Bars");
    private final JToggleButton steps = toggle("Steps");
    private final JToggleButton points = toggle("Points");
    private final JToggleButton line = toggle("Line");
    private final JToggleButton logX = toggle("Log X");
    private final JToggleButton logY = toggle("Log Y");
    private final JToggleButton grid = toggle("Grid");

    private Runnable reveal;
    /** Where pictures are written, when something has said where. */
    private Path outputFolder;
    /**
     * The plot drawn from numbers that is on screen, which has no file of its
     * own yet: "Edit..." writes one for it, as a double click does.
     */
    private RootGallery.Entry showing;
    /** The picture on screen, for Sphere's TBrowser to open live or in relief. */
    private File shownFile;

    /** The one panel the tab holds, which every drawer reaches for. */
    public static synchronized RootPlotsPanel instance() {
        if (shared == null) {
            shared = new RootPlotsPanel();
        }
        return shared;
    }

    private RootPlotsPanel() {
        super(new BorderLayout(), false);
        add(buildToolbar(), BorderLayout.NORTH);

        plot.setPreferredSize(new Dimension(360, 260));
        gallery.setPickListener(new RootGallery.PickListener() {
            @Override
            public void plotPicked(RootGallery.Entry entry) {
                showRemembered(entry);
            }

            @Override
            public void plotOpened(RootGallery.Entry entry) {
                openInEditor(entry);
            }
        });

        stage.add(plot, "plot");
        stage.add(picture, "picture");
        cards.show(stage, "plot");

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, stage, gallery);
        split.setResizeWeight(0.68);
        split.setDividerSize(4);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);

        grid.setSelected(true);
        bars.setSelected(true);
        plot.setShowGrid(true);
        plot.showMessage("Nothing drawn yet");

        // Whatever a script writes into the folder the console is in shows up
        // here on its own.
        watch.follow(com.sphere.core.fs.WorkingDirectory::get);
        watch.start();
    }

    /**
     * Where a plot is written when it is handed to the image editor.
     *
     * A picture dropped in the folder Sphere was launched from clutters it, so
     * they go into a plots folder, and into the active project's once one is
     * open. The folder is also watched, since a script writing there is drawing
     * in the same place.
     */
    public void setOutputFolder(Path folder) {
        if (outputFolder != null) {
            watch.remove(outputFolder);
        }
        outputFolder = folder == null ? null : folder.toAbsolutePath().normalize();
        if (outputFolder != null) {
            try {
                java.nio.file.Files.createDirectories(outputFolder);
                watch.add(outputFolder);
            } catch (java.io.IOException unwritable) {
                outputFolder = null;
            }
        }
    }

    /** The folder in use, made if it was not there. */
    public Path outputFolder() throws java.io.IOException {
        Path folder = outputFolder != null ? outputFolder
            : com.sphere.core.fs.WorkingDirectory.get().resolve("plots");
        java.nio.file.Files.createDirectories(folder);
        return folder;
    }

    /**
     * The same folder, for a process about to be started: engines are told it
     * (SPHERE_PLOTS) so what they draw lands where the tab looks. Asked without
     * building the tab when nothing has built it yet.
     */
    public static Path plotsFolder() {
        final RootPlotsPanel panel;
        synchronized (RootPlotsPanel.class) {
            panel = shared;
        }
        try {
            if (panel != null) {
                return panel.outputFolder();
            }
            final Path folder = com.sphere.core.fs.WorkingDirectory.get().resolve("plots");
            java.nio.file.Files.createDirectories(folder);
            return folder;
        } catch (java.io.IOException unwritable) {
            return com.sphere.core.fs.WorkingDirectory.get();
        }
    }

    /**
     * Shows a picture Sphere made in memory: a ROOT canvas, a notebook figure,
     * a console plot. It is written into the plots folder first, which is what
     * makes it a picture like any other, kept, reopened and editable; the same
     * name replaces the earlier picture of that name, so a canvas redrawn ten
     * times leaves one thumbnail.
     */
    public static File showRendered(java.awt.image.BufferedImage image, String name) {
        if (image == null) {
            return null;
        }
        try {
            final File target = plotsFolder().resolve(fileNameFor(name)).toFile();
            javax.imageio.ImageIO.write(image, "png", target);
            return shownWritten(target);
        } catch (java.io.IOException unwritable) {
            com.sphere.utils.AppLogger.error("Could not keep " + name + " for the Plots tab: "
                + unwritable.getMessage());
            return null;
        }
    }

    /** The same for a picture that arrives already encoded, a PNG from a notebook. */
    public static File showEncoded(byte[] bytes, String name) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            final File target = plotsFolder().resolve(fileNameFor(name)).toFile();
            java.nio.file.Files.write(target.toPath(), bytes);
            return shownWritten(target);
        } catch (java.io.IOException unwritable) {
            com.sphere.utils.AppLogger.error("Could not keep " + name + " for the Plots tab: "
                + unwritable.getMessage());
            return null;
        }
    }

    /** Shows a picture a process Sphere ran has written, such as a ROOT demo's canvas. */
    public static File showFile(File file) {
        return file == null || !file.isFile() ? null : shownWritten(file);
    }

    /** Shows a file Sphere has just written, without the watch announcing it a second time. */
    private static File shownWritten(File target) {
        final RootPlotsPanel panel = instance();
        panel.watch.ignore(target);
        panel.showImage(target);
        return target;
    }

    /** The folders being watched for pictures. */
    public List<Path> watched() {
        return watch.folders();
    }

    public void watch(Path directory) {
        watch.add(directory);
    }

    public boolean unwatch(Path directory) {
        return watch.remove(directory);
    }

    /** Exposed so a test can drive the watch without waiting for its thread. */
    RootImageWatch watcher() {
        return watch;
    }

    /** Called so the tab can bring itself forward when a plot lands in it. */
    public void setReveal(Runnable action) {
        this.reveal = action;
    }

    // ---- what other parts of Sphere draw here -------------------------------

    public void showHistogram(RootHistogram h, String caption) {
        onSwing(() -> {
            plot.showHistogram(h);
            plot.setStyle(currentStyle());
            keep(caption);
        });
    }

    public void showGraph(RootGraph g, String caption) {
        onSwing(() -> {
            plot.showGraph(g);
            points.setSelected(true);
            keep(caption);
        });
    }

    public void showGraph2D(RootGraph2D g, String caption) {
        onSwing(() -> {
            plot.showGraph2D(g);
            points.setSelected(true);
            keep(caption);
        });
    }

    /** Bins one branch read from a tree and draws the distribution. */
    public void showColumn(String branch, double[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        RootHistogram h = RootHistogram.fromValues(
            branch, branch + "  (" + values.length + " entries)", values, 100);
        onSwing(() -> {
            plot.showHistogram(h);
            steps.setSelected(true);
            plot.setStyle(RootPlot.Style.STEPS);
            keep(branch);
        });
    }

    /** Draws one branch against another, the y values ordered by entry. */
    public void showCurve(String xName, String yName, double[] xValues,
                          double[] yValues) {
        if (xValues == null || yValues == null
            || xValues.length == 0 || yValues.length == 0) {
            return;
        }
        RootGraph g = RootGraph.fromColumns(xName, yName, xValues, yValues);
        showGraph(g, g.name);
    }

    /**
     * Shows a picture a script wrote, and keeps it.
     *
     * PNG, JPEG, TIFF and SVG all arrive the same way; the reading, the vector
     * one and a 16-bit detector TIFF included, is what the image editor does.
     */
    public void showImage(File file) {
        if (file == null || !ImageFileIO.isImage(file)) {
            return;
        }
        // Decoded here, in arrival order, and only then handed to the event thread.
        DECODER.execute(() -> {
            final java.awt.image.BufferedImage thumbnail = RootGallery.thumbnailOf(file);
            onSwing(() -> {
                gallery.rememberImage(file, thumbnail);
                display(file);
                if (reveal != null) {
                    reveal.run();
                }
            });
        });
    }

    /** One thread, so pictures reach the tab in the order they were written. */
    private static final java.util.concurrent.ExecutorService DECODER =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "sphere-plot-decoder");
            t.setDaemon(true);
            return t;
        });

    public void clear() {
        onSwing(() -> {
            gallery.clear();
            picture.clear();
            showing = null;
            cards.show(stage, "plot");
            openInEditor.setEnabled(false);
            plot.showMessage("Nothing drawn yet");
        });
    }

    /** How many plots the tab is holding. */
    public int count() {
        return gallery.kept();
    }

    // ---- the tab's own doings ----------------------------------------------

    private void keep(String caption) {
        cards.show(stage, "plot");
        showing = gallery.remember(plot, caption);
        // A plot drawn from numbers is as editable as a picture: "Edit..."
        // writes it out and opens that, which used to take a double click.
        openInEditor.setEnabled(showing != null);
        if (reveal != null) {
            reveal.run();
        }
    }

    private void display(File file) {
        showing = null;
        shownFile = file;
        picture.show(file);
        cards.show(stage, "picture");
        openInEditor.setEnabled(true);
    }

    /** What "Edit..." opens: the picture on screen, or the drawn plot written out. */
    private void editShowing() {
        if (showing != null && showing.source == null) {
            openInEditor(showing);
        } else {
            picture.openInEditor();
        }
    }

    /** What is on screen, in Sphere's TBrowser: its numbers when it has them, else its file. */
    private void openInBrowser() {
        if (showing != null && showing.source == null) {
            if (showing.histogram != null) {
                com.sphere.components.spherebrowser.SphereBrowser.openHistogram(showing.histogram, showing.caption);
            } else if (showing.graph != null) {
                com.sphere.components.spherebrowser.SphereBrowser.openGraph(showing.graph, showing.caption);
            } else if (showing.surface != null) {
                com.sphere.components.spherebrowser.SphereBrowser.openGraph2D(showing.surface, showing.caption);
            }
            return;
        }
        final File file = showing != null && showing.source != null ? showing.source : shownFile;
        com.sphere.components.spherebrowser.SphereBrowser.open(
            file == null ? java.util.List.of() : java.util.List.of(file.toPath()));
    }

    private void imageAppeared(File file) {
        showImage(file);
    }

    /**
     * Hands a thumbnail to the image editor.
     *
     * A picture that came in as a file is opened where it lies. One Sphere drew
     * itself has no file yet, so it is written beside the console's own folder
     * first, which is also where the editor's Save will land.
     */
    private void openInEditor(RootGallery.Entry entry) {
        if (entry.source != null) {
            if (!entry.source.isFile()) {
                setMessage(entry.caption + " is no longer on disk.");
                return;
            }
            com.sphere.ui.ImageEditorFrame.show(entry.source);
            return;
        }
        try {
            File written = writePicture(entry);
            watch.ignore(written);
            gallery.rememberImage(written);
            com.sphere.ui.ImageEditorFrame.show(written);
        } catch (java.io.IOException unwritable) {
            setMessage("Could not write the picture: " + unwritable.getMessage());
        }
    }

    /** Draws a remembered plot large and saves it, without disturbing the tab. */
    private File writePicture(RootGallery.Entry entry) throws java.io.IOException {
        RootPlot offscreen = new RootPlot();
        offscreen.setShowGrid(grid.isSelected());
        offscreen.setLogX(logX.isSelected());
        offscreen.setLogY(logY.isSelected());
        if (entry.histogram != null) {
            offscreen.showHistogram(entry.histogram);
        } else if (entry.graph != null) {
            offscreen.showGraph(entry.graph);
        } else if (entry.surface != null) {
            offscreen.showGraph2D(entry.surface);
        }
        offscreen.setStyle(entry.style);

        Path folder = outputFolder();
        File target = folder.resolve(fileNameFor(entry.caption)).toFile();
        for (int copy = 2; target.exists(); copy++) {
            target = folder.resolve(
                fileNameFor(entry.caption + "-" + copy)).toFile();
        }
        javax.imageio.ImageIO.write(offscreen.snapshot(1200, 800), "png", target);
        return target;
    }

    /** A caption turned into something a filesystem accepts. */
    private static String fileNameFor(String caption) {
        final String cleaned = caption.replaceAll("[^A-Za-z0-9._-]+", "_")
                                      .replaceAll("^_+|_+$", "");
        return (cleaned.isEmpty() ? "plot" : cleaned) + ".png";
    }

    private void setMessage(String text) {
        plot.showMessage(text);
        cards.show(stage, "plot");
    }

    private void showRemembered(RootGallery.Entry entry) {
        if (entry.source != null) {
            display(entry.source);
            return;
        }
        cards.show(stage, "plot");
        showing = entry;
        openInEditor.setEnabled(true);
        if (entry.histogram != null) {
            plot.showHistogram(entry.histogram);
        } else if (entry.graph != null) {
            plot.showGraph(entry.graph);
        } else if (entry.surface != null) {
            plot.showGraph2D(entry.surface);
        } else {
            return;
        }
        plot.setStyle(entry.style);
        select(entry.style);
    }

    private void select(RootPlot.Style style) {
        bars.setSelected(style == RootPlot.Style.BARS);
        steps.setSelected(style == RootPlot.Style.STEPS);
        points.setSelected(style == RootPlot.Style.POINTS);
        line.setSelected(style == RootPlot.Style.LINE);
    }

    private RootPlot.Style currentStyle() {
        if (steps.isSelected()) {
            return RootPlot.Style.STEPS;
        }
        if (points.isSelected()) {
            return RootPlot.Style.POINTS;
        }
        if (line.isSelected()) {
            return RootPlot.Style.LINE;
        }
        return RootPlot.Style.BARS;
    }

    /** A drawer may be any thread, and Swing is not. */
    private static void onSwing(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    /**
     * Two rows rather than one: the tab is a narrow column, and a single row of
     * buttons loses its end off the right edge.
     */
    private JComponent buildToolbar() {
        ImagingTheme.Surface bar = ImagingTheme.stack(true);
        bar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ImagingTheme.border()),
            BorderFactory.createEmptyBorder(4, 6, 4, 6)));

        ImagingTheme.Surface styleRow = ImagingTheme.strip(true);
        ButtonGroup styles = new ButtonGroup();
        for (JToggleButton b : new JToggleButton[] {bars, steps, points, line}) {
            styles.add(b);
            b.addActionListener(e -> plot.setStyle(currentStyle()));
            styleRow.add(b);
            styleRow.add(Box.createHorizontalStrut(2));
        }
        styleRow.add(Box.createHorizontalGlue());

        ImagingTheme.Surface viewRow = ImagingTheme.strip(true);
        logX.addActionListener(e -> plot.setLogX(logX.isSelected()));
        logY.addActionListener(e -> plot.setLogY(logY.isSelected()));
        grid.addActionListener(e -> plot.setShowGrid(grid.isSelected()));
        for (JToggleButton b : new JToggleButton[] {logX, logY, grid}) {
            viewRow.add(b);
            viewRow.add(Box.createHorizontalStrut(2));
        }
        viewRow.add(Box.createHorizontalStrut(6));
        openInEditor.setEnabled(false);
        openInEditor.addActionListener(e -> editShowing());
        viewRow.add(openInEditor);
        viewRow.add(Box.createHorizontalStrut(2));
        JButton browser = ImagingTheme.textButton("TBrowser 3D",
            "Sphere's TBrowser: the plot live in its 3D space and its analysis; a picture in relief");
        browser.addActionListener(e -> openInBrowser());
        viewRow.add(browser);
        viewRow.add(Box.createHorizontalStrut(2));
        JButton drop = ImagingTheme.textButton("Clear", "Drop the plots kept here");
        drop.addActionListener(e -> clear());
        viewRow.add(drop);
        viewRow.add(Box.createHorizontalGlue());

        bar.add(styleRow);
        bar.add(Box.createVerticalStrut(4));
        bar.add(viewRow);
        return bar;
    }

    private static JToggleButton toggle(String label) {
        JToggleButton button = new JToggleButton(label);
        ImagingTheme.styleButton(button);
        return button;
    }
}
