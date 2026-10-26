package com.sphere.components.editor;

import com.sphere.fonts.FontLoader;
import com.sphere.utils.AppLogger;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A file too large for the editor (over EditorFileIO.MAX_OPEN_BYTES), shown read-only without being loaded.
 *
 * The editor holds a file whole, as the text of a Swing document with its highlighting, analysis and minimap:
 * a 165 MiB event file would take gigabytes and minutes, so it was refused, and the tab stayed empty with the
 * file's name on it, one save away from overwriting it. Here lines are read from the disk as they come into
 * view, through an index of where every 64th line starts that a background pass builds while the first screen
 * already shows: any size opens at once, in a few megabytes. Go to line, find (streamed through the file),
 * copy of the selected lines; nothing is ever written.
 */
public final class LargeFileViewer extends JFrame {

    private static final long serialVersionUID = 1L;

    /** One window per file, raised when the file is opened again. */
    private static final Map<String, LargeFileViewer> OPEN = new HashMap<>();

    /** Every STRIDE-th line's start is kept: an index of 1/64 of the lines, a line reached by reading at most 63. */
    private static final int STRIDE = 64;
    /** A line longer than this shows its beginning (a one-line JSON of 100 MB would not draw). */
    private static final int MAX_LINE_BYTES = 64 * 1024;
    private static final String TRUNCATED = "  …";
    /** Copying more than this would hold the window: the lines selected are refused past it. */
    private static final long MAX_COPY_BYTES = 32L << 20;
    private static final int TAB = 4;

    private static final ExecutorService SEARCH = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "sphere-largefile-search");
        t.setDaemon(true);
        return t;
    });

    private final File file;
    private final FileChannel channel;
    private final long size;
    private final Charset charset;
    private final long start;   // past a UTF-8 byte order mark
    private volatile boolean closed;

    // The index, written by its own thread: marks is replaced before markCount says it holds more
    private volatile long[] marks = new long[1024];
    private volatile int markCount;
    private volatile long lineCount;
    private volatile long indexedBytes;
    private volatile boolean indexed;
    private volatile String indexError;

    // The view
    private final Lines view = new Lines();
    private final JScrollBar vbar = new JScrollBar(JScrollBar.VERTICAL);
    private final JScrollBar hbar = new JScrollBar(JScrollBar.HORIZONTAL);
    private final JLabel status = new JLabel(" ");
    private long firstLine;
    private int xOffset;
    private long caretLine, anchorLine;
    private int maxColumns = 80;
    /** Lines per scroll bar unit: 1 until a file has more lines than an int counts. */
    private long scale = 1;
    // The lines last read, for repaints that show the same ones
    private long cacheFirst = -1;
    private List<String> cache = List.of();
    private final LineReader screenReader = new LineReader();

    // Find
    private final JPanel findBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
    private final JTextField findField = new JTextField(28);
    private final JCheckBox matchCase = new JCheckBox("Match case");
    private final JLabel findStatus = new JLabel(" ");
    private long matchLine = -1;
    private volatile int searchGeneration;

    private LargeFileViewer(File file) throws IOException {
        this.file = file;
        this.channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
        this.size = channel.size();
        final byte[] head = new byte[(int) Math.min(size, 4 << 20)];
        channel.read(ByteBuffer.wrap(head), 0);
        final boolean bom = head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB
            && (head[2] & 0xFF) == 0xBF;
        this.start = bom ? 3 : 0;
        this.charset = bom || isUtf8(head, head.length == size) ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
        marks[0] = start;
        markCount = 1;

        setTitle(file.getName() + " - Sphere (read-only)");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        try {
            com.sphere.utils.IconManager.applyAppIcon(this);
        } catch (Throwable ignored) {
            // no icon
        }
        buildUi();

        final Thread indexer = new Thread(this::index, "sphere-largefile-index");
        indexer.setDaemon(true);
        indexer.start();
        // the scroll range follows the index while it grows, the status line its progress
        final Timer progress = new Timer(150, null);
        progress.addActionListener(e -> {
            updateScrollRange();
            updateStatus();
            view.repaint();
            if (indexed || closed) {
                progress.stop();
            }
        });
        progress.start();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                closed = true;
                searchGeneration++;
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
                OPEN.remove(file.getAbsolutePath(), LargeFileViewer.this);
            }
        });
    }

    /** Shows a file in its viewer, opened for it or raised. */
    public static void show(File file) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> show(file));
            return;
        }
        final String key = file.getAbsolutePath();
        LargeFileViewer v = OPEN.get(key);
        if (v == null || !v.isDisplayable()) {
            try {
                v = new LargeFileViewer(file);
            } catch (IOException e) {
                AppLogger.error("Could not open " + file + ": " + e.getMessage());
                return;
            }
            OPEN.put(key, v);
            v.setVisible(true);
            AppLogger.info(file.getName() + " is " + mib(v.size) + ", over the " + mib(EditorFileIO.MAX_OPEN_BYTES)
                + " the editor holds: shown read-only, read from the disk as it scrolls.");
        }
        v.toFront();
        v.view.requestFocusInWindow();
    }

    /** Strict UTF-8 over the first bytes; a sequence cut by the end of what was read is not an error. */
    private static boolean isUtf8(byte[] bytes, boolean whole) {
        final CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        return !d.decode(ByteBuffer.wrap(bytes), CharBuffer.allocate(bytes.length), whole).isError();
    }

    // ------------------------------------------------------------------
    // Index
    // ------------------------------------------------------------------

    private void index() {
        final byte[] a = new byte[4 << 20];
        final ByteBuffer buf = ByteBuffer.wrap(a);
        long pos = start, lines = 0;
        long[] m = marks;
        int mc = 1;
        boolean endsWithNewline = true;
        try {
            while (pos < size && !closed) {
                buf.clear();
                final int n = channel.read(buf, pos);
                if (n <= 0) {
                    break;
                }
                for (int i = 0; i < n; i++) {
                    if (a[i] == '\n') {
                        lines++;
                        if (lines % STRIDE == 0) {
                            if (mc == m.length) {
                                m = Arrays.copyOf(m, m.length * 2);
                                marks = m;
                            }
                            m[mc++] = pos + i + 1;
                            markCount = mc;
                        }
                    }
                }
                endsWithNewline = a[n - 1] == '\n';
                pos += n;
                lineCount = lines;
                indexedBytes = pos;
            }
        } catch (IOException e) {
            indexError = e.getMessage();
        }
        // a last line without its newline is a line too
        lineCount = lines + (size > start && !endsWithNewline ? 1 : 0);
        indexedBytes = size;
        indexed = true;
    }

    /** The offset where a line starts, the line known to the index. */
    private long offsetOf(long line, LineReader r) throws IOException {
        final int k = (int) Math.min(line / STRIDE, markCount - 1);
        r.seek(marks[k]);
        for (long l = (long) k * STRIDE; l < line; l++) {
            if (!r.skip()) {
                break;
            }
        }
        return r.position();
    }

    /** Sequential reading of lines from an offset, through a buffer of its own. */
    private final class LineReader {
        private final byte[] a = new byte[1 << 18];
        private final ByteBuffer buf = ByteBuffer.wrap(a);
        private byte[] line = new byte[4096];
        private long base;
        private int at, len;

        void seek(long offset) {
            base = offset;
            at = 0;
            len = 0;
        }

        long position() {
            return base + at;
        }

        private boolean more() throws IOException {
            base += len;
            at = 0;
            len = 0;
            if (base >= size) {
                return false;
            }
            buf.clear();
            final int n = channel.read(buf, base);
            len = Math.max(0, n);
            return n > 0;
        }

        /** The next line, its first MAX_LINE_BYTES kept and marked when there is more; null at the end. */
        String next() throws IOException {
            if (at >= len && !more()) {
                return null;
            }
            int kept = 0;
            long total = 0;
            while (true) {
                if (at >= len && !more()) {
                    break;
                }
                int i = at;
                while (i < len && a[i] != '\n') {
                    i++;
                }
                final int take = (int) Math.min(i - at, MAX_LINE_BYTES - kept);
                if (take > 0) {
                    if (kept + take > line.length) {
                        line = Arrays.copyOf(line, Math.max(kept + take, line.length * 2));
                    }
                    System.arraycopy(a, at, line, kept, take);
                    kept += take;
                }
                total += i - at;
                if (i < len) {
                    at = i + 1;
                    break;
                }
                at = len;
            }
            int n = kept;
            if (total == kept && n > 0 && line[n - 1] == '\r') {
                n--;
            }
            final String s = new String(line, 0, n, charset);
            return total > kept ? s + TRUNCATED : s;
        }

        /** Past the next line; false at the end. */
        boolean skip() throws IOException {
            if (at >= len && !more()) {
                return false;
            }
            while (true) {
                int i = at;
                while (i < len && a[i] != '\n') {
                    i++;
                }
                if (i < len) {
                    at = i + 1;
                    return true;
                }
                at = len;
                if (!more()) {
                    return true;
                }
            }
        }
    }

    /** Lines [first, first + n), fewer at the end of what the index knows so far. */
    private List<String> read(long first, int n) {
        final long known = lineCount;
        if (first >= known || n <= 0) {
            return List.of();
        }
        if (cacheFirst >= 0 && first >= cacheFirst && first + n <= cacheFirst + cache.size()) {
            final int o = (int) (first - cacheFirst);
            return cache.subList(o, o + n);
        }
        // a screen above and below too: scrolling a line at a time reads the disk once per screen
        final long from = Math.max(0, first - n);
        final int count = (int) Math.min(3L * n, known - from);
        final List<String> out = new ArrayList<>(count);
        try {
            offsetOf(from, screenReader);
            for (int i = 0; i < count; i++) {
                final String s = screenReader.next();
                if (s == null) {
                    break;
                }
                out.add(s);
                maxColumns = Math.max(maxColumns, s.length());
            }
        } catch (IOException e) {
            status.setText("Read failed: " + e.getMessage());
        }
        cacheFirst = from;
        cache = out;
        final int o = (int) (first - from);
        return o >= out.size() ? List.of() : out.subList(o, Math.min(out.size(), o + n));
    }

    // ------------------------------------------------------------------
    // Window
    // ------------------------------------------------------------------

    private void buildUi() {
        final Font font = FontLoader.getTerminalFont(Font.PLAIN, EditorState.fontSize());
        view.setFont(font);
        view.setFocusable(true);
        vbar.addAdjustmentListener(e -> {
            firstLine = Math.min((long) e.getValue() * scale, Math.max(0, lineCount - 1));
            view.repaint();
        });
        hbar.addAdjustmentListener(e -> {
            xOffset = e.getValue();
            view.repaint();
        });
        vbar.setUnitIncrement(1);

        final JPanel center = new JPanel(new BorderLayout());
        center.add(view, BorderLayout.CENTER);
        center.add(vbar, BorderLayout.EAST);
        center.add(hbar, BorderLayout.SOUTH);

        findBar.add(new JLabel("Find:"));
        findBar.add(findField);
        findBar.add(matchCase);
        final JButton next = new JButton("Next");
        next.addActionListener(e -> find(true));
        final JButton prev = new JButton("Previous");
        prev.addActionListener(e -> find(false));
        findBar.add(next);
        findBar.add(prev);
        findBar.add(findStatus);
        findBar.setVisible(false);
        findField.addActionListener(e -> find((e.getModifiers() & ActionEvent.SHIFT_MASK) == 0));
        bind(findField, "ESCAPE", "closeFind", this::closeFind);
        bind(findField, "shift ENTER", "findPrev", () -> find(false));
        matchCase.addActionListener(e -> view.repaint());

        final JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        bottom.add(status, BorderLayout.CENTER);
        status.setToolTipText(file.getAbsolutePath());
        final JButton goTo = new JButton("Go to line...");
        goTo.addActionListener(e -> goToLine());
        bottom.add(goTo, BorderLayout.EAST);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(findBar, BorderLayout.NORTH);
        getContentPane().add(center, BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);

        final JComponent root = getRootPane();
        bindWindow(root, "ctrl F", "find", this::openFind);
        bindWindow(root, "ctrl G", "goto", this::goToLine);
        bindWindow(root, "F3", "next", () -> find(true));
        bindWindow(root, "shift F3", "prev", () -> find(false));
        bindWindow(root, "ctrl C", "copy", this::copy);
        bindWindow(root, "F5", "reload", this::reload);

        bind(view, "UP", "up", () -> moveCaret(-1, false));
        bind(view, "DOWN", "down", () -> moveCaret(1, false));
        bind(view, "shift UP", "sup", () -> moveCaret(-1, true));
        bind(view, "shift DOWN", "sdown", () -> moveCaret(1, true));
        bind(view, "PAGE_UP", "pup", () -> moveCaret(-view.rows() + 1, false));
        bind(view, "PAGE_DOWN", "pdown", () -> moveCaret(view.rows() - 1, false));
        bind(view, "shift PAGE_UP", "spup", () -> moveCaret(-view.rows() + 1, true));
        bind(view, "shift PAGE_DOWN", "spdown", () -> moveCaret(view.rows() - 1, true));
        bind(view, "ctrl HOME", "top", () -> moveCaret(Long.MIN_VALUE / 2, false));
        bind(view, "ctrl END", "bottom", () -> moveCaret(Long.MAX_VALUE / 2, false));
        bind(view, "HOME", "home", () -> hbar.setValue(0));
        bind(view, "LEFT", "left", () -> hbar.setValue(hbar.getValue() - view.charWidth() * 4));
        bind(view, "RIGHT", "right", () -> hbar.setValue(hbar.getValue() + view.charWidth() * 4));
    }

    private static void bind(JComponent c, String key, String name, Runnable r) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name);
        c.getActionMap().put(name, action(r));
    }

    private static void bindWindow(JComponent c, String key, String name, Runnable r) {
        c.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), name);
        c.getActionMap().put(name, action(r));
    }

    private static AbstractAction action(Runnable r) {
        return new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        };
    }

    private void updateScrollRange() {
        final long lines = Math.max(1, lineCount);
        scale = Math.max(1, (lines + Integer.MAX_VALUE / 2) / (Integer.MAX_VALUE / 2));
        final int rows = Math.max(1, view.rows());
        final int max = (int) ((lines + rows - 1) / scale) + 1;
        final int extent = (int) Math.max(1, rows / scale);
        final int value = (int) Math.min(firstLine / scale, Math.max(0, max - extent));
        if (vbar.getMaximum() != max || vbar.getVisibleAmount() != extent || vbar.getValue() != value) {
            vbar.setValues(value, extent, 0, max);
        }
        vbar.setBlockIncrement(extent);
        final int textWidth = Math.max(1, view.getWidth() - view.gutterWidth());
        final int width = (maxColumns + 4) * view.charWidth();
        hbar.setValues(Math.min(xOffset, Math.max(0, width - textWidth)), textWidth, 0, Math.max(width, textWidth));
        hbar.setBlockIncrement(textWidth);
        hbar.setUnitIncrement(view.charWidth() * 4);
    }

    private void updateStatus() {
        final StringBuilder b = new StringBuilder();
        // the name: a full path pushed the line count off the window; the path is the tooltip
        b.append(file.getName()).append("   ").append(mib(size)).append("   ");
        b.append(String.format(Locale.ROOT, "%,d lines", lineCount));
        if (!indexed) {
            b.append(String.format(Locale.ROOT, " (indexing %d %%)", size == 0 ? 100 : (int) (100 * indexedBytes / size)));
        }
        b.append("   ").append(charset.name());
        b.append(String.format(Locale.ROOT, "   Ln %,d", caretLine + 1));
        if (caretLine != anchorLine) {
            b.append(String.format(Locale.ROOT, " (%,d selected)", Math.abs(caretLine - anchorLine) + 1));
        }
        b.append("   read-only: over the ").append(mib(EditorFileIO.MAX_OPEN_BYTES)).append(" editor limit");
        if (indexError != null) {
            b.append("   index stopped: ").append(indexError);
        }
        status.setText(b.toString());
    }

    private static String mib(long bytes) {
        return bytes >= 1L << 30 ? String.format(Locale.ROOT, "%.2f GiB", bytes / (double) (1L << 30))
            : String.format(Locale.ROOT, "%.1f MiB", bytes / (double) (1L << 20));
    }

    // ------------------------------------------------------------------
    // Moving
    // ------------------------------------------------------------------

    private void moveCaret(long delta, boolean extend) {
        final long last = Math.max(0, lineCount - 1);
        caretLine = Math.max(0, Math.min(last, caretLine + delta));
        if (!extend) {
            anchorLine = caretLine;
        }
        reveal(caretLine, false);
    }

    /** Scrolls so a line shows: at the edge it went past, or in the middle. */
    private void reveal(long line, boolean center) {
        final int rows = Math.max(1, view.rows() - 1);
        if (center) {
            firstLine = Math.max(0, line - rows / 2);
        } else if (line < firstLine) {
            firstLine = line;
        } else if (line >= firstLine + rows) {
            firstLine = line - rows + 1;
        }
        updateScrollRange();
        updateStatus();
        view.repaint();
    }

    private void goToLine() {
        final String s = JOptionPane.showInputDialog(this, String.format(Locale.ROOT, "Line (1 - %,d%s):", lineCount,
            indexed ? "" : ", still indexing"), "Go to line", JOptionPane.PLAIN_MESSAGE);
        if (s == null || s.isBlank()) {
            return;
        }
        try {
            final long n = Long.parseLong(s.replaceAll("[\\s,_']", ""));
            caretLine = anchorLine = Math.max(0, Math.min(Math.max(0, lineCount - 1), n - 1));
            reveal(caretLine, true);
            view.requestFocusInWindow();
        } catch (NumberFormatException e) {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    /** Copies the selected lines, refused past MAX_COPY_BYTES. */
    private void copy() {
        if (findField.isFocusOwner() && findField.getSelectedText() != null) {
            findField.copy();
            return;
        }
        final long a = Math.min(caretLine, anchorLine), b = Math.max(caretLine, anchorLine);
        if (lineCount == 0) {
            return;
        }
        final StringBuilder out = new StringBuilder();
        final LineReader r = new LineReader();
        try {
            offsetOf(a, r);
            for (long l = a; l <= b; l++) {
                final String s = r.next();
                if (s == null) {
                    break;
                }
                if (out.length() + s.length() > MAX_COPY_BYTES) {
                    status.setText("Selection over " + mib(MAX_COPY_BYTES) + ": not copied. Select fewer lines.");
                    return;
                }
                out.append(s).append('\n');
            }
        } catch (IOException e) {
            status.setText("Read failed: " + e.getMessage());
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(out.toString()), null);
        status.setText(String.format(Locale.ROOT, "Copied %,d line%s.", b - a + 1, b > a ? "s" : ""));
    }

    /** Opens the file again, as it is on the disk now, at the same line. */
    private void reload() {
        final long line = firstLine, caret = caretLine;
        dispose();
        show(file);
        final LargeFileViewer v = OPEN.get(file.getAbsolutePath());
        if (v != null) {
            v.firstLine = line;
            v.caretLine = v.anchorLine = caret;
        }
    }

    // ------------------------------------------------------------------
    // Find
    // ------------------------------------------------------------------

    private void openFind() {
        findBar.setVisible(true);
        revalidate();
        findField.selectAll();
        findField.requestFocusInWindow();
    }

    private void closeFind() {
        findBar.setVisible(false);
        searchGeneration++;
        matchLine = -1;
        revalidate();
        view.repaint();
        view.requestFocusInWindow();
    }

    /**
     * The next (or previous) line holding the text, from the caret, wrapping at the ends: the bytes are scanned
     * aside, newlines counted as they pass, so the file is never decoded whole.
     */
    private void find(boolean forward) {
        if (!findBar.isVisible()) {
            openFind();
        }
        final String text = findField.getText();
        if (text.isEmpty()) {
            return;
        }
        final boolean cs = matchCase.isSelected();
        byte[] pat = text.getBytes(charset);
        if (!cs) {
            pat = lower(pat);
        }
        final byte[] pattern = pat;
        final long from = forward ? (matchLine == caretLine ? caretLine + 1 : caretLine) : caretLine;
        final int gen = ++searchGeneration;
        findStatus.setText("Searching...");
        SEARCH.execute(() -> {
            long found;
            boolean wrapped = false;
            try {
                if (forward) {
                    found = scan(from, Long.MAX_VALUE, pattern, cs, true, gen);
                    if (found == -1) {
                        wrapped = true;
                        found = scan(0, from, pattern, cs, true, gen);
                    }
                } else {
                    found = scan(0, from, pattern, cs, false, gen);
                    if (found == -1) {
                        wrapped = true;
                        found = scan(from, Long.MAX_VALUE, pattern, cs, false, gen);
                    }
                }
            } catch (IOException e) {
                final String msg = e.getMessage();
                SwingUtilities.invokeLater(() -> findStatus.setText("Read failed: " + msg));
                return;
            }
            final long line = found;
            final boolean w = wrapped;
            SwingUtilities.invokeLater(() -> {
                if (gen != searchGeneration) {
                    return;
                }
                if (line < 0) {
                    findStatus.setText(line == -1 ? "Not found" : " ");
                    Toolkit.getDefaultToolkit().beep();
                    return;
                }
                matchLine = line;
                caretLine = anchorLine = line;
                findStatus.setText(String.format(Locale.ROOT, "Line %,d%s", line + 1,
                    w ? (forward ? ", from the top" : ", from the bottom") : ""));
                reveal(line, true);
            });
        });
    }

    private static byte[] lower(byte[] b) {
        final byte[] out = b.clone();
        for (int i = 0; i < out.length; i++) {
            out[i] = lower(out[i]);
        }
        return out;
    }

    private static byte lower(byte x) {
        return x >= 'A' && x <= 'Z' ? (byte) (x + 32) : x;
    }

    /**
     * The first (or last) line in [fromLine, toLine) whose bytes hold the pattern, ASCII letters folded unless
     * cs; -1 when none, -2 when a newer search took over.
     */
    private long scan(long fromLine, long toLine, byte[] pat, boolean cs, boolean first, int gen) throws IOException {
        if (fromLine >= toLine || fromLine >= Math.max(1, lineCount)) {
            return -1;
        }
        final LineReader r = new LineReader();
        long pos = offsetOf(fromLine, r);
        long line = fromLine, found = -1;
        final int m = pat.length;
        final byte[] a = new byte[4 << 20];
        final ByteBuffer buf = ByteBuffer.wrap(a);
        while (pos < size && line < toLine) {
            if (gen != searchGeneration || closed) {
                return -2;
            }
            buf.clear();
            final int n = channel.read(buf, pos);
            if (n <= 0) {
                break;
            }
            // a match may start up to m - 1 bytes before the end of the chunk: those wait for the next one
            final boolean last = pos + n >= size || n < m;
            final int limit = last ? n : n - m + 1;
            for (int i = 0; i < limit; i++) {
                final byte x = a[i];
                if ((cs ? x : lower(x)) == pat[0] && matches(a, i, n, pat, cs)) {
                    if (first) {
                        return line;
                    }
                    found = line;
                }
                if (x == '\n' && ++line >= toLine) {
                    return found;
                }
            }
            pos += limit;
            if ((pos & ((1 << 26) - 1)) < limit) {
                final int pct = (int) (100 * pos / Math.max(1, size));
                SwingUtilities.invokeLater(() -> {
                    if (gen == searchGeneration) {
                        findStatus.setText("Searching... " + pct + " %");
                    }
                });
            }
        }
        return found;
    }

    private static boolean matches(byte[] a, int i, int n, byte[] pat, boolean cs) {
        if (i + pat.length > n) {
            return false;
        }
        for (int j = 1; j < pat.length; j++) {
            final byte x = a[i + j];
            if ((cs ? x : lower(x)) != pat[j]) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // The lines on screen
    // ------------------------------------------------------------------

    private final class Lines extends JComponent {
        private static final long serialVersionUID = 1L;

        Lines() {
            final MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    final long l = lineAt(e.getY());
                    caretLine = l;
                    if ((e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) == 0) {
                        anchorLine = l;
                    }
                    updateStatus();
                    repaint();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    caretLine = lineAt(e.getY());
                    reveal(caretLine, false);
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    if (e.isShiftDown()) {
                        hbar.setValue(hbar.getValue() + e.getWheelRotation() * charWidth() * 6);
                        return;
                    }
                    final long last = Math.max(0, lineCount - rows() + 1);
                    firstLine = Math.max(0, Math.min(last, firstLine + 3L * e.getWheelRotation()));
                    updateScrollRange();
                    repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    updateScrollRange();
                }
            });
        }

        private long lineAt(int y) {
            final long l = firstLine + Math.max(0, y) / Math.max(1, lineHeight());
            return Math.max(0, Math.min(Math.max(0, lineCount - 1), l));
        }

        int lineHeight() {
            return getFontMetrics(getFont()).getHeight();
        }

        int charWidth() {
            return Math.max(1, getFontMetrics(getFont()).charWidth('m'));
        }

        int rows() {
            return Math.max(1, getHeight() / Math.max(1, lineHeight()));
        }

        int gutterWidth() {
            final int digits = Long.toString(Math.max(1, lineCount)).length();
            return (Math.max(4, digits) + 2) * charWidth();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            final Graphics2D g = (Graphics2D) g0.create();
            final Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
            if (hints instanceof Map<?, ?> map) {
                g.addRenderingHints(map);
            } else {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            }
            final FontMetrics fm = g.getFontMetrics(getFont());
            g.setFont(getFont());
            final int lh = fm.getHeight(), asc = fm.getAscent(), cw = charWidth();
            final int gw = gutterWidth(), w = getWidth(), h = getHeight();
            g.setColor(EditorTheme.background());
            g.fillRect(0, 0, w, h);
            final int rows = h / lh + 1;
            final List<String> lines = read(firstLine, rows);
            final long selA = Math.min(caretLine, anchorLine), selB = Math.max(caretLine, anchorLine);
            final String needle = findBar.isVisible() ? findField.getText() : "";
            final boolean cs = matchCase.isSelected();
            final Color matchColor = new Color(EditorTheme.accent().getRed(), EditorTheme.accent().getGreen(),
                EditorTheme.accent().getBlue(), 90);
            final int firstCol = xOffset / cw;
            final int cols = (w - gw) / cw + 3;
            for (int r = 0; r < lines.size(); r++) {
                final long ln = firstLine + r;
                final int y = r * lh;
                if (ln >= selA && ln <= selB && selA != selB) {
                    g.setColor(EditorTheme.selection());
                    g.fillRect(gw, y, w - gw, lh);
                } else if (ln == caretLine) {
                    g.setColor(EditorTheme.currentLine());
                    g.fillRect(gw, y, w - gw, lh);
                }
                final String s = expandTabs(lines.get(r));
                final int x0 = gw + 4 - xOffset;
                if (!needle.isEmpty()) {
                    g.setColor(ln == matchLine ? EditorTheme.accent() : matchColor);
                    for (int i = indexOf(s, needle, 0, cs); i >= 0; i = indexOf(s, needle, i + 1, cs)) {
                        if (ln == matchLine) {
                            g.drawRect(x0 + i * cw - 1, y, needle.length() * cw + 1, lh - 1);
                        } else {
                            g.fillRect(x0 + i * cw, y, needle.length() * cw, lh);
                        }
                    }
                }
                if (firstCol < s.length()) {
                    g.setColor(EditorTheme.foreground());
                    final Graphics2D t = (Graphics2D) g.create(gw, 0, w - gw, h);
                    t.drawString(s.substring(firstCol, Math.min(s.length(), firstCol + cols)),
                        x0 - gw + firstCol * cw, y + asc);
                    t.dispose();
                }
            }
            g.setColor(EditorTheme.gutterBackground());
            g.fillRect(0, 0, gw, h);
            for (int r = 0; r < lines.size(); r++) {
                final long ln = firstLine + r;
                final String num = Long.toString(ln + 1);
                g.setColor(ln == caretLine ? EditorTheme.gutterCurrentForeground() : EditorTheme.gutterForeground());
                g.drawString(num, gw - cw - fm.stringWidth(num), r * lh + asc);
            }
            if (lines.isEmpty() && !indexed) {
                g.setColor(EditorTheme.gutterForeground());
                g.drawString("Indexing...", gw + 8, asc + 4);
            }
            g.dispose();
        }
    }

    private static int indexOf(String s, String needle, int from, boolean cs) {
        if (cs) {
            return s.indexOf(needle, from);
        }
        for (int i = from, last = s.length() - needle.length(); i <= last; i++) {
            if (s.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    private static String expandTabs(String s) {
        if (s.indexOf('\t') < 0) {
            return s;
        }
        final StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\t') {
                do {
                    b.append(' ');
                } while (b.length() % TAB != 0);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
