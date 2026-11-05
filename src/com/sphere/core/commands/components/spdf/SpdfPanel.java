package com.sphere.components.spdf;

import com.sphere.components.spdf.cos.PdfCrypt;
import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfDocument;
import com.sphere.components.spdf.cos.PdfPage;
import com.sphere.components.spdf.cos.PdfString;
import com.sphere.components.spdf.edit.EditHistory;
import com.sphere.components.spdf.edit.FontResolver;
import com.sphere.components.spdf.edit.PageOps;
import com.sphere.components.spdf.edit.Redactor;
import com.sphere.components.spdf.edit.RichText;
import com.sphere.components.spdf.font.PdfFont;
import com.sphere.components.spdf.render.PageRenderer;
import com.sphere.components.spdf.render.TextGlyph;
import com.sphere.components.spdf.text.Insights;
import com.sphere.components.spdf.text.PageText;
import com.sphere.components.spdf.text.SmartSearch;
import com.sphere.components.spdf.text.TextBlock;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterJob;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * :spdf, Sphere's PDF reader and editor, as one panel (it lives in a
 * SphereCoder tab). Pages with their thumbnails, text edited in place with
 * any font, color and style, annotations, page operations, true redaction,
 * smart search and replace across the document, an analysis of what the
 * document says, and commands in plain words (Ctrl+K). Everything is pure
 * Java: the PDF engine underneath reads, draws and writes PDF by itself.
 */
public final class SpdfPanel extends JPanel implements com.sphere.components.CoderPanel {

    /** Opens a picture of the PDF in Sphere Image; SphereCoder opens it in a tab beside the PDF. */
    public interface ImageEditorHost {
        void open(java.awt.image.BufferedImage image, String name,
                  com.sphere.components.imaging.ImageEditorPanel.Link link);
    }

    private ImageEditorHost imageHost;
    /** The pictures of the document: selecting, editing in Sphere Image, replacing, inserting... */
    final ImageTools images = new ImageTools(this);

    public void setImageEditorHost(ImageEditorHost host) {
        imageHost = host;
    }

    /** Where pictures go to be edited: the host set by SphereCoder, else a Sphere Image window. */
    ImageEditorHost imageHost() {
        if (imageHost != null) {
            return imageHost;
        }
        return (image, name, link) -> {
            com.sphere.ui.ImageEditorFrame f = com.sphere.ui.ImageEditorFrame.show(null);
            f.getEditor().openLinked(image, name, link);
        };
    }

    @Override
    public String badge() {
        return "PDF";
    }

    @Override
    public Color badgeColor() {
        return new Color(0xE5, 0x39, 0x35);
    }

    SpdfDoc doc;
    private RenderService render;
    final PdfView view;
    final SidePanel side;
    final FormatBar format;
    private final JScrollPane scroll;
    private final JLabel status = new JLabel(" ");
    private final JLabel pageLabel = new JLabel(" ");
    private final JLabel zoomLabel = new JLabel(" ");
    private final JTextField pageField = new JTextField(3);
    private final Map<PdfView.Tool, JToggleButton> toolButtons = new LinkedHashMap<>();
    private final JToggleButton nightButton;
    private final CommandOverlay commands;
    private final JLayeredPane layers = new JLayeredPane();
    private final JPanel main = new JPanel(new BorderLayout());
    private final Deque<double[]> back = new ArrayDeque<>();
    private Insights insights;
    private boolean insightsRunning;
    private Runnable titleListener = () -> { };
    private final Timer statusClear;
    private boolean pendingFit;
    private String query = "";

    int highlightColor = 0xFFE066;
    int noteColor = 0xFFD54F;
    Color drawColor = new Color(0xD32F2F);
    float drawWidth = 2f;
    boolean fillShapes;
    RichText.Style lastStyle = new RichText.Style("Arial", false, false, false, false, 12, 0, 0, null);

    public SpdfPanel() {
        super(new BorderLayout());
        setBackground(SpdfTheme.chrome());
        view = new PdfView(this);
        side = new SidePanel(this);
        format = new FormatBar(this);
        scroll = new JScrollPane(view);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(SpdfTheme.canvas());
        scroll.setWheelScrollingEnabled(false);
        scroll.getVerticalScrollBar().setUnitIncrement(40);
        scroll.addMouseWheelListener(e -> {
            if ((e.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0) {
                double f = Math.pow(1.12, -e.getPreciseWheelRotation());
                Point p = SwingUtilities.convertPoint(scroll.getViewport(), e.getPoint(), view);
                view.setZoom(view.zoom * f, p);
            } else {
                javax.swing.JScrollBar bar = e.isShiftDown() ? scroll.getHorizontalScrollBar() : scroll.getVerticalScrollBar();
                bar.setValue(bar.getValue() + (int) Math.round(e.getPreciseWheelRotation() * 64));
            }
        });
        scroll.getViewport().addChangeListener(e -> updatePage());
        scroll.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                view.relayout();
                // the first fit waits until the tab has its real size
                if (pendingFit && doc != null && scroll.getViewport().getWidth() > 200) {
                    pendingFit = false;
                    view.fitWidth();
                    view.scrollToPage(0, 0);
                    updatePage();
                } else {
                    // a fit chosen stays a fit: a picture tab opening beside the PDF does not leave it cut
                    view.refit();
                }
            }
        });
        nightButton = Widgets.toggle("moon", "Night mode: dark pages for reading (the PDF itself is not changed)");
        nightButton.addActionListener(e -> toggleNight());

        JPanel top = new JPanel(new BorderLayout());
        top.add(toolbar(), BorderLayout.NORTH);
        top.add(format, BorderLayout.SOUTH);
        format.setVisible(false);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, side, scroll);
        split.setDividerSize(4);
        split.setDividerLocation(230);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);

        main.add(top, BorderLayout.NORTH);
        main.add(split, BorderLayout.CENTER);
        main.add(statusBar(), BorderLayout.SOUTH);

        commands = new CommandOverlay();
        layers.add(main, JLayeredPane.DEFAULT_LAYER);
        layers.add(commands, JLayeredPane.POPUP_LAYER);
        commands.setVisible(false);
        layers.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                main.setBounds(0, 0, layers.getWidth(), layers.getHeight());
                placeCommands();
                main.revalidate();
            }
        });
        add(layers, BorderLayout.CENTER);
        statusClear = new Timer(6000, e -> status.setText(" "));
        statusClear.setRepeats(false);
        bindKeys();
        setTransferHandler(new FileDrop());
        view.setTransferHandler(new FileDrop());
        setTool(PdfView.Tool.SELECT);
    }

    /* ================================================================= public API */

    /** Opens a PDF (in the background; asks for a password when one is needed). */
    public void open(Path file) {
        if (!confirmDiscard()) {
            return;
        }
        status("Opening " + file.getFileName() + "…");
        new SwingWorker<SpdfDoc, Void>() {
            @Override
            protected SpdfDoc doInBackground() throws Exception {
                try {
                    return SpdfDoc.open(file, null);
                } catch (PdfCrypt.PasswordException e) {
                    return null;
                }
            }

            @Override
            protected void done() {
                try {
                    SpdfDoc d = get();
                    if (d == null) {
                        d = askPassword(file);
                        if (d == null) {
                            return;
                        }
                    }
                    setDocument(d);
                    status(d.pdf.wasRepaired() ? "The file was damaged; it was repaired when opened"
                               : d.pageCount() + (d.pageCount() == 1 ? " page" : " pages"));
                } catch (Exception e) {
                    error("Could not open " + file.getFileName() + ": " + rootMessage(e), e);
                }
            }
        }.execute();
    }

    private SpdfDoc askPassword(Path file) {
        for (int attempt = 0; attempt < 3; attempt++) {
            JPasswordField f = new JPasswordField(20);
            int r = JOptionPane.showConfirmDialog(this, new Object[] { "This PDF is protected by a password:", f },
                                                  "Password", JOptionPane.OK_CANCEL_OPTION);
            if (r != JOptionPane.OK_OPTION) {
                return null;
            }
            try {
                return SpdfDoc.open(file, new String(f.getPassword()));
            } catch (PdfCrypt.PasswordException e) {
                status("Wrong password");
            } catch (IOException e) {
                error("Could not open the file: " + e.getMessage(), e);
                return null;
            }
        }
        return null;
    }

    public void newDocument() {
        if (confirmDiscard()) {
            setDocument(SpdfDoc.blank());
        }
    }

    private void setDocument(SpdfDoc d) {
        if (render != null) {
            render.shutdown();
        }
        doc = d;
        render = new RenderService(d);
        insights = null;
        insightsRunning = false;
        back.clear();
        d.history.addListener(this::historyChanged);
        view.setDocument(d, render);
        side.thumbnails.setDocument(d, render);
        side.setHistory(List.of());
        side.setResults(List.of(), "");
        side.setOutline(d.bookmarks(), null);
        side.setInsights(null, d);
        pendingFit = true;
        SwingUtilities.invokeLater(() -> {
            if (scroll.getViewport().getWidth() > 200) {
                pendingFit = false;
                view.fitWidth();
            }
            view.scrollToPage(0, 0);
            updatePage();
            focusView();
        });
        if (d.pdf.isEncrypted()) {
            status("Encrypted document opened; it will be saved without encryption");
        }
        ensureInsights();
        titleListener.run();
    }

    public Path file() {
        return doc == null ? null : doc.file;
    }

    public boolean isDirty() {
        return doc != null && doc.history.isDirty();
    }

    /** The tab title: the file name, with a dot while there are unsaved changes. */
    public String title() {
        String name = doc == null ? "PDF" : doc.file == null ? "Untitled.pdf" : doc.file.getFileName().toString();
        return (isDirty() ? "● " : "") + name;
    }

    public void setTitleListener(Runnable r) {
        titleListener = r == null ? () -> { } : r;
    }

    /** Gives the keyboard to the pages, so the shortcuts (Ctrl+S, Ctrl+Z...) reach this editor. */
    public void focusView() {
        view.requestFocusInWindow();
    }

    /** Asks to save unsaved changes; false when the user cancels. */
    public boolean confirmDiscard() {
        view.commitEdit();
        if (!isDirty()) {
            return true;
        }
        Object[] options = { "Save", "Don't Save", "Cancel" };
        int r = JOptionPane.showOptionDialog(this, "\"" + doc.title() + "\" has unsaved changes.\nSave them?",
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

    public void dispose() {
        images.closeAll();
        if (render != null) {
            render.shutdown();
        }
    }

    /* ================================================================= toolbar */

    private JComponent toolbar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(SpdfTheme.chrome());
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, SpdfTheme.border()));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 3));
        left.setOpaque(false);
        left.add(Widgets.button("open", "Open (Ctrl+O)", this::openDialog));
        left.add(Widgets.button("save", "Save (Ctrl+S)", this::save));
        left.add(Widgets.button("print", "Print (Ctrl+P)", this::print));
        left.add(Widgets.separator());
        left.add(Widgets.button("undo", "Undo (Ctrl+Z)", this::undo));
        left.add(Widgets.button("redo", "Redo (Ctrl+Y)", this::redo));
        left.add(Widgets.separator());
        ButtonGroup tools = new ButtonGroup();
        addTool(left, tools, PdfView.Tool.SELECT, "select", "Select and copy text, follow links (V)");
        addTool(left, tools, PdfView.Tool.EDIT, "edit", "Edit text: click a paragraph (E)");
        addTool(left, tools, PdfView.Tool.ADDTEXT, "addtext", "Add text anywhere (T)");
        addTool(left, tools, PdfView.Tool.HIGHLIGHT, "highlight", "Highlight: drag over the text (H)");
        addTool(left, tools, PdfView.Tool.NOTE, "note", "Note: click to place one (N)");
        addTool(left, tools, PdfView.Tool.INK, "ink", "Freehand drawing (D)");
        JToggleButton shapes = Widgets.toggle("shape", "Shapes: rectangle, ellipse, line, arrow (R)");
        tools.add(shapes);
        shapes.addActionListener(e -> shapeMenu(shapes));
        toolButtons.put(PdfView.Tool.RECT, shapes);
        left.add(shapes);
        addTool(left, tools, PdfView.Tool.REDACT, "redact", "Redact: really removes the text from the file (X)");
        left.add(Widgets.button("image", "Insert a picture (or paste one with Ctrl+V, or drop a file on the page)."
                                + " Click a picture to move or resize it; double-click to edit it in Sphere Image",
                                () -> images.insertFromFile(view.currentPage(), null)));
        addTool(left, tools, PdfView.Tool.HAND, "hand", "Pan the view (or drag with the middle button)");
        Widgets.ColorButton penColor = new Widgets.ColorButton(drawColor, "Color of drawings, shapes and highlights",
                                                               rgb -> {
                                                                   drawColor = new Color(rgb);
                                                                   highlightColor = rgb;
                                                               });
        penColor.documentColors = this::documentColors;
        left.add(penColor);
        left.add(Widgets.separator());
        left.add(Widgets.button("zoomout", "Zoom out (Ctrl+-)", () -> view.setZoom(view.zoom / 1.2, null)));
        zoomLabel.setFont(SpdfTheme.ui(Font.PLAIN, 12));
        zoomLabel.setForeground(SpdfTheme.fg());
        zoomLabel.setPreferredSize(new Dimension(44, 20));
        zoomLabel.setHorizontalAlignment(JLabel.CENTER);
        left.add(zoomLabel);
        left.add(Widgets.button("zoomin", "Zoom in (Ctrl++)", () -> view.setZoom(view.zoom * 1.2, null)));
        left.add(Widgets.button("fitwidth", "Fit width (Ctrl+0)", view::fitWidth));
        left.add(Widgets.button("fitpage", "Fit page", view::fitPage));
        left.add(nightButton);
        bar.add(left, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 3));
        right.setOpaque(false);
        JButton cmd = Widgets.textButton(" What do you want to do?  Ctrl+K", "Commands in plain words",
                                         this::showCommands);
        cmd.setIcon(SpdfTheme.icon("spark", 16));
        cmd.setForeground(SpdfTheme.muted());
        right.add(cmd);
        right.add(Widgets.button("search", "Search (Ctrl+F)", () -> side.show("search")));
        right.add(Widgets.button("info", "Document properties", this::properties));
        right.add(Widgets.button("export", "Export…", this::exportMenu));
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private void addTool(JPanel bar, ButtonGroup g, PdfView.Tool t, String icon, String tip) {
        JToggleButton b = Widgets.toggle(icon, tip);
        b.addActionListener(e -> setTool(t));
        g.add(b);
        toolButtons.put(t, b);
        bar.add(b);
    }

    private void shapeMenu(JToggleButton anchor) {
        JPopupMenu m = new JPopupMenu();
        m.add(menuItem("Rectangle", "rect", () -> setTool(PdfView.Tool.RECT)));
        m.add(menuItem("Ellipse", "ellipse", () -> setTool(PdfView.Tool.ELLIPSE)));
        m.add(menuItem("Line", "line", () -> setTool(PdfView.Tool.LINE)));
        m.add(menuItem("Arrow", "arrow", () -> setTool(PdfView.Tool.ARROW)));
        m.addSeparator();
        javax.swing.JCheckBoxMenuItem fill = new javax.swing.JCheckBoxMenuItem("Fill shapes", fillShapes);
        fill.addActionListener(e -> fillShapes = fill.isSelected());
        m.add(fill);
        javax.swing.JMenu w = new javax.swing.JMenu("Line width");
        for (float v : new float[] { 0.75f, 1f, 1.5f, 2f, 3f, 4f, 6f }) {
            w.add(menuItem(v + " pt", null, () -> drawWidth = v));
        }
        m.add(w);
        m.show(anchor, 0, anchor.getHeight());
    }

    static javax.swing.JMenuItem menuItem(String label, String icon, Runnable r) {
        javax.swing.JMenuItem i = icon == null ? new javax.swing.JMenuItem(label)
            : new javax.swing.JMenuItem(label, SpdfTheme.icon(icon, 16));
        i.addActionListener(e -> r.run());
        return i;
    }

    void setTool(PdfView.Tool t) {
        if (t != PdfView.Tool.EDIT && t != PdfView.Tool.ADDTEXT) {
            view.commitEdit();
        }
        view.flushInk();
        view.tool = t;
        JToggleButton b = toolButtons.get(t == PdfView.Tool.ELLIPSE || t == PdfView.Tool.LINE || t == PdfView.Tool.ARROW
                                          ? PdfView.Tool.RECT : t);
        if (b != null) {
            b.setSelected(true);
        }
        format.setVisible(t == PdfView.Tool.EDIT || t == PdfView.Tool.ADDTEXT);
        String hint = switch (t) {
            case EDIT -> "Click a paragraph to edit it in place; click elsewhere to add text";
            case ADDTEXT -> "Click where the text should start";
            case HIGHLIGHT -> "Drag over the text to highlight";
            case NOTE -> "Click to place a note";
            case INK -> "Draw; the drawing is written when you pause";
            case RECT, ELLIPSE, LINE, ARROW -> "Drag to draw (Shift: equal proportions)";
            case REDACT -> "Frame the area to redact: its text is removed from the file, not just covered";
            case HAND -> "Drag to pan the view";
            default -> " ";
        };
        status(hint);
        view.repaint();
        view.requestFocusInWindow();
        revalidate();
    }

    private JComponent statusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(SpdfTheme.chrome());
        bar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, SpdfTheme.border()),
                                                         BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        status.setFont(SpdfTheme.ui(Font.PLAIN, 12));
        status.setForeground(SpdfTheme.muted());
        bar.add(status, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        pageField.setFont(SpdfTheme.ui(Font.PLAIN, 12));
        pageField.setHorizontalAlignment(JTextField.CENTER);
        pageField.setToolTipText("<html>Go to page (Ctrl+G): the number printed on the page (\"23\", \"iv\"),"
                                 + "<br>or its place in the file in parentheses (\"(25)\")</html>");
        pageField.addActionListener(e -> {
            if (doc == null) {
                return;
            }
            String t = pageField.getText().trim();
            java.util.regex.Matcher place = java.util.regex.Pattern.compile("^\\((\\d{1,6})\\)$").matcher(t);
            int i = place.matches() ? Integer.parseInt(place.group(1)) - 1 : doc.pageNamed(t);
            if (i >= 0 && i < doc.pageCount()) {
                goToPage(i);
                view.requestFocusInWindow();
            } else {
                status("No page " + t + (doc.hasPageLabels() ? " (pages are numbered " + doc.pageName(0) + " to "
                                                              + doc.pageName(doc.pageCount() - 1) + ")" : ""));
                updatePage();
            }
        });
        pageLabel.setFont(SpdfTheme.ui(Font.PLAIN, 12));
        pageLabel.setForeground(SpdfTheme.muted());
        right.add(Widgets.label("Page"));
        right.add(pageField);
        right.add(pageLabel);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    void status(String s) {
        status.setText(s == null || s.isBlank() ? " " : s);
        statusClear.restart();
    }

    void error(String message, Throwable t) {
        status("⚠ " + message);
        JOptionPane.showMessageDialog(this, message, "spdf", JOptionPane.WARNING_MESSAGE);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    /* ================================================================= view state */

    void zoomChanged() {
        zoomLabel.setText(Math.round(view.zoom * 100) + "%");
    }

    private void updatePage() {
        if (doc == null) {
            pageLabel.setText(" ");
            return;
        }
        int cur = view.currentPage();
        // the page as the document numbers it ("23"), and, when that differs, its place in the file ("(25 of 33)")
        String name = doc.pageName(cur);
        if (!pageField.hasFocus()) {
            pageField.setText(name);
        }
        pageLabel.setText(name.equals(String.valueOf(cur + 1)) ? "of " + doc.pageCount()
            : "(" + (cur + 1) + " of " + doc.pageCount() + ")");
        side.thumbnails.setCurrent(cur);
        zoomChanged();
    }

    int pageCount() {
        return doc == null ? 0 : doc.pageCount();
    }

    void goToPage(int i) {
        if (doc == null) {
            return;
        }
        view.scrollToPage(Math.max(0, Math.min(doc.pageCount() - 1, i)), 0);
        updatePage();
    }

    /** Selects text on a page and brings it into view. */
    void goToText(int page, int start, int end) {
        PageText t = doc.text(page);
        view.selPage = page;
        view.selStart = start;
        view.selEnd = Math.max(start, end);
        List<java.awt.geom.Rectangle2D> r = t.rects(start, Math.max(start + 1, end));
        double y = r.isEmpty() ? 0 : r.get(0).getY();
        view.scrollToPage(page, y);
        view.repaint();
    }

    void pushPosition() {
        if (doc != null) {
            back.push(new double[] { view.currentPage(), 0 });
            if (back.size() > 50) {
                back.removeLast();
            }
        }
    }

    void goBack() {
        if (!back.isEmpty()) {
            double[] p = back.pop();
            view.scrollToPage((int) p[0], p[1]);
        }
    }

    void toggleNight() {
        view.night = !view.night;
        nightButton.setSelected(view.night);
        view.repaint();
        side.thumbnails.repaint();
        status(view.night ? "Night mode: pages are inverted on screen only" : "Day mode");
    }

    /* ================================================================= editing glue */

    void withEditor(Consumer<TextEditor> f) {
        if (view.editor != null) {
            f.accept(view.editor);
            view.requestFocusInWindow();
        }
    }

    void editorChanged(TextEditor e) {
        format.update(e);
        if (e != null && !format.isVisible()) {
            format.setVisible(true);
            revalidate();
        }
    }

    void selectionChanged() {
        String s = view.selectedText();
        if (!s.isEmpty()) {
            status(s.length() + " characters selected — Ctrl+C to copy, right-click for more");
        }
    }

    /** After any change of a page: thumbnails, texts, title, history. */
    void documentEdited(int page) {
        if (page >= 0) {
            side.thumbnails.edited.add(page);
        }
        view.repaint();
        side.thumbnails.repaint();
    }

    private void historyChanged() {
        if (doc == null) {
            return;
        }
        side.setHistory(doc.history.steps());
        EditHistory.Step last = doc.history.last();
        boolean structural = last != null && last.pages.isEmpty();
        if (structural) {
            render.bump();
            view.relayout();
            side.thumbnails.relayout();
            view.selPage = -1;
            view.hits = new ArrayList<>();
        }
        view.repaint();
        side.thumbnails.repaint();
        insights = null;
        titleListener.run();
    }

    /** The colors the document's text uses, most used first (the editor offers them first). */
    List<Integer> documentColors() {
        if (doc == null) {
            return List.of();
        }
        Map<Integer, Integer> count = new HashMap<>();
        int cur = view.currentPage();
        for (int p = Math.max(0, cur - 2); p <= Math.min(doc.pageCount() - 1, cur + 2); p++) {
            PageText t = doc.textIfReady(p);
            if (t == null) {
                continue;
            }
            for (TextGlyph g : t.glyphs) {
                count.merge(g.color & 0xffffff, 1, Integer::sum);
            }
        }
        List<Map.Entry<Integer, Integer>> e = new ArrayList<>(count.entrySet());
        e.sort((a, b) -> b.getValue() - a.getValue());
        List<Integer> out = new ArrayList<>();
        for (Map.Entry<Integer, Integer> x : e) {
            if (out.size() < 8) {
                out.add(x.getKey());
            }
        }
        return out;
    }

    /* ================================================================= history */

    public void undo() {
        if (view.editor != null) {
            view.cancelEdit();
            return;
        }
        if (doc != null && doc.history.canUndo()) {
            EditHistory.Step s = doc.history.undo();
            status("Undone: " + s.label);
        }
    }

    public void redo() {
        if (doc != null && doc.history.canRedo()) {
            EditHistory.Step s = doc.history.redo();
            status("Redone: " + s.label);
        }
    }

    /* ================================================================= files */

    void openDialog() {
        JFileChooser c = new JFileChooser(doc != null && doc.file != null ? doc.file.getParent().toFile() : null);
        c.setFileFilter(new FileNameExtensionFilter("PDF documents", "pdf"));
        if (c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            open(c.getSelectedFile().toPath());
        }
    }

    /** Saves the edits into the PDF's own file (asks for a name when it has none). False when it did not. */
    public boolean save() {
        if (doc == null) {
            return false;
        }
        view.commitEdit();
        if (doc.file == null) {
            return saveAs();
        }
        return saveTo(doc.file, false);
    }

    /** Saves under a name chosen in a dialog. False when canceled or failed. */
    public boolean saveAs() {
        if (doc == null) {
            return false;
        }
        view.commitEdit();
        JFileChooser c = new JFileChooser(doc.file == null ? null : doc.file.getParent().toFile());
        c.setDialogTitle("Save As");
        c.setFileFilter(new FileNameExtensionFilter("PDF documents", "pdf"));
        if (doc.file != null) {
            c.setSelectedFile(doc.file.toFile());
        }
        if (c.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return false;
        }
        Path p = c.getSelectedFile().toPath();
        if (!p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            p = p.resolveSibling(p.getFileName() + ".pdf");
        }
        boolean same = doc.file != null && p.toAbsolutePath().equals(doc.file.toAbsolutePath());
        if (!same && Files.exists(p)) {
            int r = JOptionPane.showConfirmDialog(this, p.getFileName() + " already exists.\nReplace it?",
                                                  "Confirm Save As", JOptionPane.YES_NO_OPTION,
                                                  JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) {
                return false;
            }
        }
        return saveTo(p, false);
    }

    void saveCompact() {
        if (doc == null) {
            return;
        }
        view.commitEdit();
        Path base = doc.file != null ? doc.file : Path.of("document.pdf");
        String n = base.getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
        JFileChooser c = new JFileChooser(base.getParent() == null ? null : base.getParent().toFile());
        c.setDialogTitle("Save a Compressed Copy");
        c.setSelectedFile(base.resolveSibling(n + "-compressed.pdf").toFile());
        if (c.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            long before = doc.file != null ? doc.file.toFile().length() : 0;
            if (saveTo(c.getSelectedFile().toPath(), true) && before > 0) {
                long after = c.getSelectedFile().length();
                status(String.format(Locale.US, "Saved: %,d → %,d bytes (%+.0f%%)", before, after,
                                     100.0 * (after - before) / before));
            }
        }
    }

    private boolean saveTo(Path p, boolean compact) {
        try {
            doc.save(p, compact);
            status("Saved " + p.getFileName() + " (" + String.format(Locale.US, "%,d", Files.size(p)) + " bytes)");
            titleListener.run();
            return true;
        } catch (SpdfDoc.LockedException e) {
            Object[] options = { "Save As…", "Cancel" };
            int r = JOptionPane.showOptionDialog(this, "Could not overwrite \"" + p.getFileName() + "\": another program "
                + "has it open and locked\n(a PDF viewer such as Adobe Reader or a browser tab, or a sync client).\n\n"
                + "Close the file there and save again, or save the edits under another name.",
                "File Locked", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
            status("⚠ Not saved: " + p.getFileName() + " is locked by another program");
            return r == 0 && saveAs();
        } catch (IOException | RuntimeException e) {
            error("Save failed: " + rootMessage(e), e);
            return false;
        }
    }

    void print() {
        if (doc == null) {
            return;
        }
        view.commitEdit();
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName(doc.title());
        job.setPrintable((Graphics g, PageFormat pf, int index) -> {
            if (index >= doc.pageCount()) {
                return Printable.NO_SUCH_PAGE;
            }
            PdfPage p = doc.page(index);
            Graphics2D g2 = (Graphics2D) g;
            double k = Math.min(pf.getImageableWidth() / p.displayWidth(), pf.getImageableHeight() / p.displayHeight());
            AffineTransform t = AffineTransform.getTranslateInstance(pf.getImageableX(), pf.getImageableY());
            t.scale(k, k);
            // vector output: the printer gets paths and glyph outlines, not a picture
            doc.renderer.render(g2, p, t, new PageRenderer.Options());
            return Printable.PAGE_EXISTS;
        });
        if (job.printDialog()) {
            try {
                job.print();
                status("Sent to the printer");
            } catch (Exception e) {
                error("Could not print: " + e.getMessage(), e);
            }
        }
    }

    private void exportMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(menuItem("Text (reading order)…", "text", () -> exportText(false)));
        m.add(menuItem("Markdown (headings detected)…", "outline", () -> exportText(true)));
        m.add(menuItem("Current page as PNG…", "export", () -> exportPng(List.of(view.currentPage()))));
        m.add(menuItem("Compressed copy…", "save", this::saveCompact));
        m.show(this, getWidth() - 220, 36);
    }

    void exportText(boolean markdown) {
        if (doc == null) {
            return;
        }
        JFileChooser c = new JFileChooser(doc.file == null ? null : doc.file.getParent().toFile());
        String base = doc.file == null ? "document" : doc.file.getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
        c.setSelectedFile(new File(base + (markdown ? ".md" : ".txt")));
        if (c.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path out = c.getSelectedFile().toPath();
        status("Exporting the text…");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < doc.pageCount(); i++) {
                    PageText t = doc.text(i);
                    for (TextBlock b : t.blocks) {
                        String s = b.text().trim();
                        if (s.isEmpty()) {
                            continue;
                        }
                        if (markdown && b.headingLevel > 0) {
                            sb.append("#".repeat(Math.min(6, b.headingLevel))).append(' ').append(s).append("\n\n");
                        } else {
                            sb.append(s).append("\n\n");
                        }
                    }
                    if (!markdown) {
                        sb.append("\f\n");
                    } else {
                        sb.append("<!-- page ").append(i + 2).append(" -->\n\n");
                    }
                }
                return sb.toString();
            }

            @Override
            protected void done() {
                try {
                    Files.writeString(out, get(), StandardCharsets.UTF_8);
                    status("Text exported: " + out.getFileName());
                } catch (Exception e) {
                    error("Could not export: " + rootMessage(e), e);
                }
            }
        }.execute();
    }

    void exportPng(List<Integer> pages) {
        if (doc == null || pages.isEmpty()) {
            return;
        }
        JFileChooser c = new JFileChooser(doc.file == null ? null : doc.file.getParent().toFile());
        c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        c.setDialogTitle("Folder for the images (150 dpi)");
        if (c.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path dir = c.getSelectedFile().toPath();
        String base = doc.file == null ? "page" : doc.file.getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                int n = 0;
                for (int p : pages) {
                    BufferedImage img = render.render(p, 150 / 72.0, null, false);
                    ImageIO.write(img, "png", dir.resolve(base + "-p" + (p + 1) + ".png").toFile());
                    n++;
                }
                return n;
            }

            @Override
            protected void done() {
                try {
                    int n = get();
                    status(n + (n == 1 ? " image" : " images") + " written to " + dir);
                } catch (Exception e) {
                    error("Could not export: " + rootMessage(e), e);
                }
            }
        }.execute();
    }

    void properties() {
        if (doc == null) {
            return;
        }
        PdfDict info = doc.pdf.info();
        JTextField title = new JTextField(info == null || info.getText("Title") == null ? "" : info.getText("Title"), 30);
        JTextField author = new JTextField(info == null || info.getText("Author") == null ? "" : info.getText("Author"), 30);
        JTextField subject = new JTextField(info == null || info.getText("Subject") == null ? "" : info.getText("Subject"), 30);
        JTextField keywords = new JTextField(info == null || info.getText("Keywords") == null ? "" : info.getText("Keywords"),
                                             30);
        PdfPage p0 = doc.page(view.currentPage());
        StringBuilder fonts = new StringBuilder();
        java.util.Set<String> seen = new java.util.TreeSet<>();
        for (PdfFont f : doc.cache.loadedFonts()) {
            seen.add(f.baseFont + (f.embedded ? (f.subset ? "  (embedded subset)" : "  (embedded)") : "  (not embedded)"));
        }
        for (String s : seen) {
            fonts.append(s).append('\n');
        }
        javax.swing.JTextArea fontArea = new javax.swing.JTextArea(fonts.toString(), 6, 40);
        fontArea.setEditable(false);
        long size = doc.file == null ? 0 : doc.file.toFile().length();
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        String pageSize = String.format(Locale.US, "%d  (%.2f × %.2f in, %.0f × %.0f mm)", doc.pageCount(),
                                        p0.displayWidth() / 72, p0.displayHeight() / 72, p0.displayWidth() / 72 * 25.4,
                                        p0.displayHeight() / 72 * 25.4);
        Object[][] rows = {
            { "Title", title }, { "Author", author }, { "Subject", subject }, { "Keywords", keywords },
            { "Created with", new JLabel(text(info, "Creator")) }, { "Producer", new JLabel(text(info, "Producer")) },
            { "PDF version", new JLabel(doc.pdf.version()) },
            { "Pages", new JLabel(pageSize) },
            { "File size", new JLabel(size > 0 ? String.format(Locale.US, "%,d bytes", size) : "—") },
            { "Encryption", new JLabel(doc.pdf.isEncrypted() ? "yes (removed when saved)" : "no") },
            { "Fonts seen", new JScrollPane(fontArea) } };
        for (int i = 0; i < rows.length; i++) {
            c.gridx = 0;
            c.gridy = i;
            c.weightx = 0;
            form.add(new JLabel((String) rows[i][0]), c);
            c.gridx = 1;
            c.weightx = 1;
            form.add((Component) rows[i][1], c);
        }
        int r = JOptionPane.showConfirmDialog(this, form, "Properties — " + doc.title(), JOptionPane.OK_CANCEL_OPTION,
                                              JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return;
        }
        doc.history.begin("Document properties");
        try {
            PdfDict i = doc.pdf.info();
            if (i == null) {
                i = new PdfDict();
                doc.pdf.trailer().put("Info", doc.history.add(i));
            } else if (doc.pdf.trailer().getRaw("Info") instanceof com.sphere.components.spdf.cos.PdfRef ref) {
                doc.history.touch(ref.num);
                i = (PdfDict) doc.pdf.getObject(ref.num);
            }
            setText(i, "Title", title.getText());
            setText(i, "Author", author.getText());
            setText(i, "Subject", subject.getText());
            setText(i, "Keywords", keywords.getText());
            doc.history.commit();
        } catch (RuntimeException e) {
            doc.history.rollback();
        }
        titleListener.run();
    }

    private static String text(PdfDict info, String key) {
        String s = info == null ? null : info.getText(key);
        return s == null || s.isBlank() ? "—" : s;
    }

    private static void setText(PdfDict d, String key, String v) {
        if (v == null || v.isBlank()) {
            d.remove(key);
        } else {
            d.put(key, PdfString.text(v.trim()));
        }
    }

    /* ================================================================= pages */

    private static String pagesWord(int n) {
        return n == 1 ? "1 page" : n + " pages";
    }

    void rotatePages(List<Integer> pages, int deg) {
        if (doc == null || pages.isEmpty()) {
            return;
        }
        view.commitEdit();
        PageOps.rotate(doc.history, doc.pdf, pages, deg);
        render.bump();
        view.relayout();
        side.thumbnails.relayout();
        status(pages.size() == 1 ? "Page " + (pages.get(0) + 1) + " rotated" : pages.size() + " pages rotated");
    }

    void deletePages(List<Integer> pages) {
        if (doc == null || pages.isEmpty()) {
            return;
        }
        view.commitEdit();
        try {
            PageOps.delete(doc.history, doc.pdf, pages);
            side.thumbnails.selected.clear();
            status(pagesWord(pages.size()) + " deleted — Ctrl+Z to undo");
        } catch (IllegalArgumentException e) {
            error(e.getMessage(), e);
        }
    }

    void movePages(List<Integer> pages, int target) {
        if (doc == null || pages.isEmpty()) {
            return;
        }
        view.commitEdit();
        PageOps.move(doc.history, doc.pdf, pages, target);
        side.thumbnails.selected.clear();
        status(pagesWord(pages.size()) + " moved");
    }

    void duplicatePages(List<Integer> pages) {
        if (doc == null || pages.isEmpty()) {
            return;
        }
        view.commitEdit();
        doc.history.begin("Duplicate pages");
        try {
            List<Integer> sorted = new ArrayList<>(pages);
            sorted.sort((a, b) -> b - a);
            for (int p : sorted) {
                PageOps.duplicate(doc.history, doc.pdf, p);
            }
            doc.history.commit();
        } catch (RuntimeException e) {
            doc.history.rollback();
            error("Could not duplicate: " + e.getMessage(), e);
        }
    }

    void insertBlank(int at) {
        if (doc == null) {
            return;
        }
        view.commitEdit();
        PdfPage ref = doc.page(Math.max(0, Math.min(doc.pageCount() - 1, at - 1)));
        java.awt.geom.Rectangle2D m = ref.mediaBox();
        PageOps.insertBlank(doc.history, doc.pdf, at, m.getWidth(), m.getHeight());
        goToPage(at);
    }

    void importPdf(int at) {
        if (doc == null) {
            return;
        }
        JFileChooser c = new JFileChooser(doc.file == null ? null : doc.file.getParent().toFile());
        c.setDialogTitle("Insert a PDF");
        c.setFileFilter(new FileNameExtensionFilter("PDF documents", "pdf"));
        if (c.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            PdfDocument other = PdfDocument.open(c.getSelectedFile().toPath(), null);
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < other.pageCount(); i++) {
                all.add(i);
            }
            view.commitEdit();
            PageOps.importPages(doc.history, doc.pdf, other, all, at);
            status(pagesWord(all.size()) + " inserted from " + c.getSelectedFile().getName());
            goToPage(at);
        } catch (Exception e) {
            error("Could not insert this PDF: " + rootMessage(e), e);
        }
    }

    void extractPages(List<Integer> pages) {
        if (doc == null || pages.isEmpty()) {
            return;
        }
        JFileChooser c = new JFileChooser(doc.file == null ? null : doc.file.getParent().toFile());
        c.setDialogTitle("Extract Pages");
        String base = doc.file == null ? "extract" : doc.file.getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
        c.setSelectedFile(new File(base + "-pages-" + (pages.get(0) + 1)
                                   + (pages.size() > 1 ? "-" + (pages.get(pages.size() - 1) + 1) : "") + ".pdf"));
        if (c.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            Files.write(c.getSelectedFile().toPath(), PageOps.extract(doc.pdf, pages));
            status(pagesWord(pages.size()) + " extracted to " + c.getSelectedFile().getName());
        } catch (Exception e) {
            error("Could not extract: " + rootMessage(e), e);
        }
    }

    /* ================================================================= search */

    void search(String q) {
        search(q, null);
    }

    /** Highlights ranges of text found otherwise (dates, e-mails... from the analysis), as one step. */
    void highlightRanges(List<SmartSearch.Hit> ranges, String what) {
        if (doc == null || ranges.isEmpty()) {
            status("No " + what + " found");
            return;
        }
        view.hits = new ArrayList<>(ranges);
        query = what;
        highlightAllHits();
    }

    /** Searches, then runs a step with the hits in hand (search runs in the background). */
    void search(String q, Runnable then) {
        if (doc == null) {
            return;
        }
        query = q == null ? "" : q;
        side.searchField.setText(query);
        if (!side.shown.equals("search")) {
            side.show("search");
        }
        SmartSearch.Mode mode = side.mode();
        status("Searching for \"" + q + "\"…");
        new SwingWorker<List<SmartSearch.Hit>, Void>() {
            @Override
            protected List<SmartSearch.Hit> doInBackground() {
                return SmartSearch.search(doc.pageCount(), i -> doc.text(i).text, query, mode, 5000);
            }

            @Override
            protected void done() {
                try {
                    List<SmartSearch.Hit> hits = get();
                    view.hits = hits;
                    view.currentHit = hits.isEmpty() ? -1 : 0;
                    side.setResults(hits, query);
                    Map<Integer, Integer> counts = new HashMap<>();
                    for (SmartSearch.Hit h : hits) {
                        counts.merge(h.page(), 1, Integer::sum);
                    }
                    side.thumbnails.hitCounts = counts;
                    side.thumbnails.repaint();
                    if (!hits.isEmpty()) {
                        showHit(firstHitFrom(view.currentPage()));
                    }
                    status(hits.isEmpty() ? "No match for \"" + query + "\""
                               : hits.size() + (hits.size() == 1 ? " match" : " matches") + " — F3 / Shift+F3 to step");
                    view.repaint();
                    if (then != null) {
                        then.run();
                    }
                } catch (Exception e) {
                    error("Search failed: " + rootMessage(e), e);
                }
            }
        }.execute();
    }

    private int firstHitFrom(int page) {
        for (int i = 0; i < view.hits.size(); i++) {
            if (view.hits.get(i).page() >= page) {
                return i;
            }
        }
        return 0;
    }

    void showHit(int i) {
        if (i < 0 || i >= view.hits.size()) {
            return;
        }
        view.currentHit = i;
        SmartSearch.Hit h = view.hits.get(i);
        PageText t = doc.text(h.page());
        List<java.awt.geom.Rectangle2D> r = t.rects(h.start(), h.end());
        double y = r.isEmpty() ? 0 : r.get(0).getY();
        java.awt.Rectangle vis = view.getVisibleRect();
        double top = view.pageRect(h.page()).getY() + y * view.zoom;
        if (top < vis.y + 30 || top > vis.y + vis.height - 60) {
            view.scrollToPage(h.page(), Math.max(0, y - 120 / view.zoom));
        }
        side.selectResult(i);
        view.repaint();
    }

    void nextHit(int dir) {
        if (view.hits.isEmpty()) {
            if (!query.isBlank()) {
                search(query);
            }
            return;
        }
        int n = view.hits.size();
        showHit(((view.currentHit + dir) % n + n) % n);
    }

    void highlightAllHits() {
        if (view.hits.isEmpty()) {
            return;
        }
        doc.history.begin("Highlight \"" + query + "\"");
        int count = 0;
        try {
            Map<Integer, List<SmartSearch.Hit>> byPage = new LinkedHashMap<>();
            for (SmartSearch.Hit h : view.hits) {
                byPage.computeIfAbsent(h.page(), k -> new ArrayList<>()).add(h);
            }
            for (Map.Entry<Integer, List<SmartSearch.Hit>> e : byPage.entrySet()) {
                for (SmartSearch.Hit h : e.getValue()) {
                    view.selPage = e.getKey();
                    view.selStart = h.start();
                    view.selEnd = h.end();
                    List<float[]> quads = view.selectionQuads();
                    if (!quads.isEmpty()) {
                        com.sphere.components.spdf.edit.Annotator.markup(doc.history, doc.page(e.getKey()), e.getKey(),
                                                                         "Highlight", quads, highlightColor, null);
                        count++;
                    }
                }
                side.thumbnails.edited.add(e.getKey());
            }
            doc.history.commit();
        } catch (RuntimeException e) {
            doc.history.rollback();
            error("Could not highlight: " + e.getMessage(), e);
        }
        view.clearSelection();
        status(count + (count == 1 ? " occurrence" : " occurrences") + " highlighted");
    }

    void replaceDialog(String find) {
        String with = (String) JOptionPane.showInputDialog(this, "Replace \"" + find + "\" with:", "Replace All",
                                                           JOptionPane.PLAIN_MESSAGE, null, null, find);
        if (with != null) {
            replaceAll(find, with);
        }
    }

    /** Replaces a phrase everywhere, each paragraph reflowed with its styles. */
    void replaceAll(String find, String with) {
        if (doc == null || find == null || find.isEmpty()) {
            return;
        }
        view.commitEdit();
        boolean regex = side.mode() == SmartSearch.Mode.REGEX;
        status("Replacing…");
        int total = 0;
        doc.history.begin("Replace \"" + find + "\"");
        try {
            for (int i = 0; i < doc.pageCount(); i++) {
                PageText t = doc.text(i);
                boolean has = regex ? Pattern.compile(find, Pattern.CASE_INSENSITIVE).matcher(t.text).find()
                    : SmartSearch.fold(t.text).text().contains(SmartSearch.fold(find).text());
                if (!has) {
                    continue;
                }
                FontResolver fr = new FontResolver(doc.cache, doc.embedder, doc.page(i));
                int n = Redactor.replace(doc.history, doc.pdf, doc.page(i), i, doc.renderer, fr, t, find, with, regex);
                if (n > 0) {
                    side.thumbnails.edited.add(i);
                }
                total += n;
            }
            doc.history.commit();
        } catch (RuntimeException e) {
            doc.history.rollback();
            error("Could not replace: " + rootMessage(e), e);
            return;
        }
        view.hits = new ArrayList<>();
        side.setResults(List.of(), "");
        status(total + (total == 1 ? " replacement" : " replacements") + " of \"" + find + "\" with \"" + with
               + "\" — Ctrl+Z to undo");
        view.repaint();
    }

    void redactPattern(String key) {
        Pattern p = Redactor.PATTERNS.get(key);
        if (doc == null || p == null) {
            return;
        }
        redactWith(p, key);
    }

    void redactText(String q) {
        if (doc != null && !q.isBlank()) {
            redactWith(Pattern.compile(Pattern.quote(q), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), "\"" + q + "\"");
        }
    }

    private void redactWith(Pattern p, String what) {
        view.commitEdit();
        int total = 0;
        doc.history.begin("Redact " + what);
        try {
            for (int i = 0; i < doc.pageCount(); i++) {
                PageText t = doc.text(i);
                int n = Redactor.redactPattern(doc.history, doc.pdf, doc.page(i), i, doc.renderer, t, p);
                if (n > 0) {
                    side.thumbnails.edited.add(i);
                }
                total += n;
            }
            doc.history.commit();
        } catch (RuntimeException e) {
            doc.history.rollback();
            error("Could not redact: " + rootMessage(e), e);
            return;
        }
        status(total + (total == 1 ? " occurrence" : " occurrences") + " of " + what + " redacted: removed from the file");
    }

    /* ================================================================= insights */

    Insights insightsIfReady() {
        return insights;
    }

    /** Extracts every page's text in the background, then analyzes the document. */
    void ensureInsights() {
        if (doc == null || insights != null || insightsRunning) {
            return;
        }
        insightsRunning = true;
        SpdfDoc d = doc;
        new SwingWorker<Insights, Integer>() {
            @Override
            protected Insights doInBackground() {
                List<PageText> pages = new ArrayList<>();
                int n = d.pageCount();
                for (int i = 0; i < n; i++) {
                    pages.add(d.text(i));
                    if (i % 5 == 4) {
                        publish(i + 1);
                    }
                }
                return Insights.of(pages);
            }

            @Override
            protected void process(List<Integer> chunks) {
                if (d == doc && !chunks.isEmpty()) {
                    status("Analyzing the document… " + chunks.get(chunks.size() - 1) + " of " + d.pageCount()
                           + " pages");
                }
            }

            @Override
            protected void done() {
                insightsRunning = false;
                if (d != doc) {
                    return;
                }
                try {
                    insights = get();
                    side.setInsights(insights, d);
                    side.setOutline(d.bookmarks(), insights);
                    status("Analysis ready: " + insights.keywords.size() + " key terms, " + insights.entities.size()
                           + " entities, " + insights.captions.size() + " figures and tables");
                } catch (Exception e) {
                    side.setInsights(null, d);
                }
            }
        }.execute();
    }

    /* ================================================================= keys */

    private void bindKeys() {
        bind("ctrl O", this::openDialog);
        bind("ctrl S", this::save);
        bind("ctrl shift S", this::saveAs);
        bind("ctrl P", this::print);
        bind("ctrl Z", () -> {
            if (view.editor == null) {
                undo();
            }
        });
        bind("ctrl Y", () -> {
            if (view.editor == null) {
                redo();
            }
        });
        bind("ctrl shift Z", () -> {
            if (view.editor == null) {
                redo();
            }
        });
        bind("ctrl F", () -> side.show("search"));
        bind("F3", () -> nextHit(1));
        bind("shift F3", () -> nextHit(-1));
        bind("ctrl K", this::showCommands);
        bind("ctrl G", () -> {
            pageField.requestFocusInWindow();
            pageField.selectAll();
        });
        bind("ctrl EQUALS", () -> view.setZoom(view.zoom * 1.2, null));
        bind("ctrl ADD", () -> view.setZoom(view.zoom * 1.2, null));
        bind("ctrl MINUS", () -> view.setZoom(view.zoom / 1.2, null));
        bind("ctrl SUBTRACT", () -> view.setZoom(view.zoom / 1.2, null));
        bind("ctrl 0", view::fitWidth);
        bind("alt LEFT", this::goBack);
        // single keys for the tools: on the page only, never while typing
        bindView("V", () -> setTool(PdfView.Tool.SELECT));
        bindView("E", () -> setTool(PdfView.Tool.EDIT));
        bindView("T", () -> setTool(PdfView.Tool.ADDTEXT));
        bindView("H", () -> setTool(PdfView.Tool.HIGHLIGHT));
        bindView("N", () -> setTool(PdfView.Tool.NOTE));
        bindView("D", () -> setTool(PdfView.Tool.INK));
        bindView("R", () -> setTool(PdfView.Tool.RECT));
        bindView("X", () -> setTool(PdfView.Tool.REDACT));
    }

    private void bind(String key, Runnable r) {
        String name = "spdf." + key;
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key), name);
        getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    private void bindView(String key, Runnable r) {
        String name = "spdf.view." + key;
        view.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name);
        view.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (view.editor == null) {
                    r.run();
                }
            }
        });
    }

    /* ================================================================= commands (Ctrl+K) */

    void showCommands() {
        placeCommands();
        commands.open();
    }

    private void placeCommands() {
        int w = Math.min(620, Math.max(360, layers.getWidth() - 80));
        commands.setBounds((layers.getWidth() - w) / 2, 52, w, commands.getPreferredSize().height);
    }

    /** The command palette: type in words, see what will happen, press Enter. */
    private final class CommandOverlay extends JPanel {
        private final JTextField field = new JTextField();
        private final JLabel meaning = new JLabel(" ");
        private final DefaultListModel<String> model = new DefaultListModel<>();
        private final JList<String> list = new JList<>(model);
        private final SpdfCommands interpreter = new SpdfCommands(SpdfPanel.this);

        CommandOverlay() {
            super(new BorderLayout(0, 4));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            field.setFont(SpdfTheme.ui(Font.PLAIN, 15));
            field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(SpdfTheme.accent()),
                                                               BorderFactory.createEmptyBorder(6, 8, 6, 8)));
            meaning.setFont(SpdfTheme.ui(Font.PLAIN, 12));
            meaning.setForeground(SpdfTheme.accent());
            list.setFont(SpdfTheme.ui(Font.PLAIN, 13));
            list.setBackground(SpdfTheme.popup());
            list.setForeground(SpdfTheme.fg());
            list.setVisibleRowCount(6);
            JPanel head = new JPanel(new BorderLayout(6, 4));
            head.setOpaque(false);
            head.add(new JLabel(SpdfTheme.icon("spark", 20)), BorderLayout.WEST);
            head.add(field, BorderLayout.CENTER);
            meaning.setBorder(BorderFactory.createEmptyBorder(2, 30, 2, 0));
            head.add(meaning, BorderLayout.SOUTH);
            add(head, BorderLayout.NORTH);
            JScrollPane sp = new JScrollPane(list);
            sp.setBorder(BorderFactory.createEmptyBorder());
            sp.getViewport().setBackground(SpdfTheme.popup());
            add(sp, BorderLayout.CENTER);
            field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    refresh();
                }

                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    refresh();
                }

                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    refresh();
                }
            });
            field.addActionListener(e -> run(field.getText()));
            field.addKeyListener(new java.awt.event.KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_ESCAPE -> close();
                        case KeyEvent.VK_DOWN -> {
                            list.setSelectedIndex(Math.min(model.size() - 1, list.getSelectedIndex() + 1));
                            e.consume();
                        }
                        case KeyEvent.VK_UP -> {
                            list.setSelectedIndex(Math.max(-1, list.getSelectedIndex() - 1));
                            e.consume();
                        }
                        case KeyEvent.VK_TAB -> {
                            String v = list.getSelectedValue() != null ? list.getSelectedValue()
                                : model.isEmpty() ? null : model.get(0);
                            if (v != null) {
                                field.setText(v);
                            }
                            e.consume();
                        }
                        default -> { }
                    }
                }
            });
            field.setFocusTraversalKeysEnabled(false);
            list.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    String v = list.getSelectedValue();
                    if (v != null) {
                        if (e.getClickCount() >= 2) {
                            run(v);
                        } else {
                            field.setText(v);
                            field.requestFocusInWindow();
                        }
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0, 0, 0, 60));
            g.fillRoundRect(3, 5, getWidth() - 4, getHeight() - 4, 14, 14);
            g.setColor(SpdfTheme.popup());
            g.fillRoundRect(0, 0, getWidth() - 4, getHeight() - 6, 14, 14);
            g.setColor(SpdfTheme.border());
            g.drawRoundRect(0, 0, getWidth() - 5, getHeight() - 7, 14, 14);
            g.dispose();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(600, 240);
        }

        void open() {
            field.setText("");
            refresh();
            setVisible(true);
            field.requestFocusInWindow();
        }

        void close() {
            setVisible(false);
            view.requestFocusInWindow();
        }

        private void refresh() {
            String t = field.getText();
            model.clear();
            List<String> suggestions = interpreter.suggest(t);
            for (String s : suggestions) {
                model.addElement(s);
            }
            SpdfCommands.Action a = t.isBlank() ? null : interpreter.interpret(t);
            meaning.setText(t.isBlank() ? "Type what you want to do: \"delete pages 2-4\", \"edit the picture\", "
                                          + "\"go to figure 3\", \"rotate odd pages left then save\""
                                : a == null ? (suggestions.isEmpty() ? "Not understood"
                                               : "Not understood — did you mean \"" + suggestions.get(0) + "\"? (Tab)")
                                : "↵  " + a.description());
        }

        private void run(String text) {
            SpdfCommands.Action a = interpreter.interpret(text);
            if (a == null) {
                java.awt.Toolkit.getDefaultToolkit().beep();
                return;
            }
            close();
            if (doc == null && !text.toLowerCase(Locale.ROOT).matches(".*\\b(open|new)\\b.*")) {
                status("Open a PDF first");
                return;
            }
            try {
                a.run().run();
            } catch (RuntimeException e) {
                error(a.description() + ": " + rootMessage(e), e);
            }
        }
    }

    /* ================================================================= drop */

    private final class FileDrop extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport s) {
            return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        public boolean importData(TransferSupport s) {
            try {
                @SuppressWarnings("unchecked")
                Collection<File> files = (Collection<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                for (File f : files) {
                    if (f.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                        open(f.toPath());
                        return true;
                    }
                }
                // a picture dropped on a page goes onto it, where it was dropped
                for (File f : files) {
                    if (doc != null && com.sphere.components.imaging.ImageFileIO.isImage(f)) {
                        java.awt.Point at = s.isDrop() ? s.getDropLocation().getDropPoint() : null;
                        if (at != null && s.getComponent() != view) {
                            at = SwingUtilities.convertPoint(s.getComponent(), at, view);
                        }
                        int page = at == null ? -1 : view.pageAt(at);
                        if (page < 0) {
                            page = view.currentPage();
                            at = null;
                        }
                        images.insertFile(page, f, at == null ? null : view.toDisplay(page, at));
                        return true;
                    }
                }
            } catch (Exception e) {
                status("Could not drop the file: " + e.getMessage());
            }
            return false;
        }
    }
}
