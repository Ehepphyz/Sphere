package com.sphere.core.python.jupyterlab;

import com.sphere.core.python.jupyterlab.JupylabXeditor.Output;
import com.sphere.fonts.FontLoader;
import com.sphere.theme.Contrast;
import com.sphere.theme.ThemePalette;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.text.PlainDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The outputs of one code cell, kept up to date as they stream in.
 *
 * The former panel was rebuilt from the whole cell for every chunk a kernel
 * sent: a loop printing 3,000 lines kept the event thread busy for 205 s, and a
 * saved notebook with 107,944 outputs (a training run's progress bars) did not
 * open at all. Here the outputs of a cell are read as a terminal would:
 *
 *   - every stream output, stdout and stderr, goes into one console of the cell,
 *     each run of a stream on lines of its own and stderr tinted, so 107,944
 *     outputs make one component, not 107,944;
 *   - streamed text is appended where the console ends, never re-rendered;
 *   - a saved log has its carriage returns resolved on the string first, and only
 *     its last lines are drawn, with a count of what came before;
 *   - images scale down to the width of the cell instead of running past it;
 *   - Markdown, LaTeX and HTML displays are rendered, ANSI tracebacks colored.
 */
final class OutputRenderer {

    /** Lines a console keeps; the rest are counted and can be brought back. */
    static final int MAX_LINES = 5000;
    /** Above this many lines at once, a console's document is filled off its pane. */
    private static final int BULK_LINES = 200;

    private final ThemePalette palette;
    private final JPanel panel = new JPanel();
    private final Font mono = FontLoader.getTerminalFont(Font.PLAIN, 12);
    private final Color background;
    private final Color foreground;
    private final Color stderrTint;

    /** The outputs drawn so far, and the version each had: an update in place redraws. */
    private final List<Output> drawn = new ArrayList<>();
    private final List<Integer> drawnVersions = new ArrayList<>();
    private Console console;
    private int maxLines = MAX_LINES;

    OutputRenderer(ThemePalette palette) {
        this.palette = palette;
        this.background = palette.getTerminalBackground();
        this.foreground = Contrast.readable(palette.getTerminalForeground(), background, Contrast.AA);
        Color error = palette.getError();
        this.stderrTint = mix(error, background, Contrast.luminance(background) < 0.2 ? 0.16f : 0.10f);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(background);
        panel.setOpaque(true);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, mix(foreground, background, 0.18f)),
            BorderFactory.createEmptyBorder(8, 14, 8, 14)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    JComponent component() {
        return panel;
    }

    boolean isEmpty() {
        return drawn.isEmpty();
    }

    /**
     * Brings the panel up to date with the cell's outputs: what was appended is
     * appended, and only a change to what was already drawn (a clear_output, an
     * update_display) redraws the whole.
     */
    void render(List<Output> outputs) {
        boolean prefix = outputs.size() >= drawn.size();
        for (int i = 0; prefix && i < drawn.size(); i++) {
            Output o = outputs.get(i);
            prefix = o == drawn.get(i) && o.version == drawnVersions.get(i);
        }
        if (!prefix) {
            rebuild(outputs);
            return;
        }
        if (console != null) {
            console.catchUp();
        }
        for (int i = drawn.size(); i < outputs.size(); i++) {
            add(outputs.get(i), false);
        }
    }

    private void rebuild(List<Output> outputs) {
        panel.removeAll();
        drawn.clear();
        drawnVersions.clear();
        console = null;
        // A saved cell's streams are joined per run and resolved on the string,
        // so a hundred thousand redraws never reach a document one by one.
        int i = 0;
        while (i < outputs.size()) {
            Output o = outputs.get(i);
            if ("stream".equals(o.output_type)) {
                int j = i;
                StringBuilder run = new StringBuilder();
                while (j < outputs.size() && "stream".equals(outputs.get(j).output_type)
                       && java.util.Objects.equals(outputs.get(j).name, o.name)) {
                    run.append(text(outputs.get(j).text));
                    j++;
                }
                if (console == null) {
                    console = new Console();
                    panel.add(console.box);
                }
                console.addStatic(o.name, AnsiText.collapse(run), outputs.subList(i, j));
                for (int k = i; k < j; k++) {
                    drawn.add(outputs.get(k));
                    drawnVersions.add(outputs.get(k).version);
                }
                i = j;
                continue;
            }
            add(o, true);
            i++;
        }
        panel.revalidate();
        panel.repaint();
    }

    private void add(Output o, boolean rebuilding) {
        drawn.add(o);
        drawnVersions.add(o.version);
        if ("stream".equals(o.output_type)) {
            if (console == null) {
                console = new Console();
                panel.add(console.box);
            }
            console.addStreaming(o);
            return;
        }
        console = null; // a display between two prints starts a new console after it
        JComponent block = switch (String.valueOf(o.output_type)) {
            case "error" -> errorBlock(o);
            case "execute_result", "display_data" -> richBlock(o.data);
            default -> null;
        };
        if (block != null) {
            block.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(block);
        }
        if (!rebuilding) {
            panel.revalidate();
            panel.repaint();
        }
    }

    // ---- Console -----------------------------------------------------------

    /** Every stream output of a stretch of the cell, one styled text. */
    private final class Console {
        final JPanel box = new JPanel(new BorderLayout());
        final JLabel hidden = new JLabel();
        final ConsoleArea pane = consoleArea();
        final javax.swing.text.Document doc = pane.getDocument();
        final AnsiText ansi;
        String runName;
        Output last;
        int consumed;
        final List<Output> sources = new ArrayList<>();

        Console() {
            ansi = new AnsiText(pane, foreground, background, maxLines);
            box.setOpaque(false);
            hidden.setFont(mono.deriveFont(Font.ITALIC, 11f));
            hidden.setForeground(mix(foreground, background, 0.55f));
            hidden.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            hidden.setVisible(false);
            hidden.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    showAll();
                }
            });
            box.add(hidden, BorderLayout.NORTH);
            box.add(pane, BorderLayout.CENTER);
            box.setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        void run(String name) {
            if (!java.util.Objects.equals(name, runName)) {
                ansi.newRun("stderr".equals(name) ? stderrTint : null);
                runName = name;
            }
        }

        void addStatic(String name, String text, List<Output> outs) {
            run(name);
            int lines = 0;
            for (int k = 0; k < text.length(); k++) {
                if (text.charAt(k) == '\n') {
                    lines++;
                }
            }
            if (lines > maxLines) {
                int cut = 0;
                for (int skip = lines - maxLines; skip > 0 && cut < text.length(); cut++) {
                    if (text.charAt(cut) == '\n') {
                        skip--;
                    }
                }
                ansi.addHidden(lines - maxLines);
                text = text.substring(cut);
            }
            appendDetached(text);
            sources.addAll(outs);
            last = outs.isEmpty() ? null : outs.get(outs.size() - 1);
            consumed = last == null ? 0 : text(last.text).length();
            updateHidden();
        }

        void addStreaming(Output o) {
            run(o.name);
            sources.add(o);
            last = o;
            consumed = 0;
            catchUp();
        }

        /** Appends what the last stream output gained since the previous refresh. */
        void catchUp() {
            if (last == null) {
                return;
            }
            CharSequence t = text(last.text);
            if (t.length() > consumed) {
                CharSequence chunk = t.subSequence(consumed, t.length());
                int newlines = 0;
                for (int k = 0; k < chunk.length() && newlines <= BULK_LINES; k++) {
                    if (chunk.charAt(k) == '\n') {
                        newlines++;
                    }
                }
                if (newlines > BULK_LINES) {
                    appendDetached(chunk);
                } else {
                    ansi.append(chunk);
                }
                consumed = t.length();
                updateHidden();
            }
        }

        /**
         * A large block goes in with the document taken off its pane: a text pane
         * updates its views at every insertion, and 2,000 lines arriving at once
         * (ipykernel batches its stream) cost the event thread a second that way,
         * against a few tens of milliseconds for building the views once.
         */
        void appendDetached(CharSequence chunk) {
            pane.setDocument(new PlainDocument());
            try {
                ansi.append(chunk);
            } finally {
                pane.setDocument(doc);
            }
        }

        void updateHidden() {
            int n = ansi.hiddenLines();
            hidden.setVisible(n > 0);
            if (n > 0) {
                hidden.setText(String.format(Locale.ROOT, "%,d earlier lines not shown — click to show them all", n));
            }
        }

        void showAll() {
            maxLines = Integer.MAX_VALUE / 4;
            List<Output> all = new ArrayList<>(drawn);
            rebuild(all);
        }
    }

    // ---- Rich outputs ------------------------------------------------------

    private JComponent errorBlock(Output o) {
        ConsoleArea pane = consoleArea();
        AnsiText ansi = new AnsiText(pane, foreground, background, maxLines);
        StringBuilder sb = new StringBuilder();
        if (o.traceback == null || o.traceback.isEmpty()) {
            sb.append("\u001b[31m").append(o.ename).append("\u001b[0m: ").append(o.evalue);
        } else {
            for (String line : o.traceback) {
                sb.append(line).append('\n');
            }
        }
        ansi.append(sb);
        return pane;
    }

    private JComponent richBlock(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        for (String mime : new String[] { "image/png", "image/jpeg", "image/jpg", "image/gif" }) {
            if (data.containsKey(mime)) {
                JComponent image = imageBlock(text(data.get(mime)).toString());
                if (image != null) {
                    return image;
                }
            }
        }
        if (data.containsKey("text/html")) {
            return htmlPane(stripActive(text(data.get("text/html")).toString()));
        }
        if (data.containsKey("text/markdown")) {
            return htmlPane(JupyLabMarkdown.body(text(data.get("text/markdown")).toString()));
        }
        if (data.containsKey("text/latex")) {
            return htmlPane(JupyLabMarkdown.body(text(data.get("text/latex")).toString()));
        }
        if (data.containsKey("text/plain")) {
            ConsoleArea pane = consoleArea();
            new AnsiText(pane, foreground, background, maxLines).append(text(data.get("text/plain")));
            return pane;
        }
        return null;
    }

    private JComponent imageBlock(String base64) {
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(base64.trim());
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return null;
            }
            JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(false);
            row.add(new ScaledImage(image), BorderLayout.WEST);
            row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
            return row;
        } catch (IllegalArgumentException | IOException undecodable) {
            return null;
        }
    }

    /** An image at its own size, or narrower when the cell is: never past its edge. */
    private static final class ScaledImage extends JComponent {
        private static final long serialVersionUID = 1L;
        private final transient BufferedImage image;

        ScaledImage(BufferedImage image) {
            this.image = image;
        }

        private Dimension fitted() {
            int available = getParent() == null || getParent().getParent() == null ? image.getWidth()
                          : Math.max(120, getParent().getParent().getWidth() - 40);
            int w = Math.min(image.getWidth(), available);
            int h = (int) Math.round(image.getHeight() * (w / (double) image.getWidth()));
            return new Dimension(w, h);
        }

        @Override
        public Dimension getPreferredSize() {
            return fitted();
        }

        @Override
        public Dimension getMaximumSize() {
            return fitted();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            Dimension d = fitted();
            g2.drawImage(image, 0, 0, d.width, d.height, null);
            g2.dispose();
        }
    }

    private JEditorPane htmlPane(String body) {
        JEditorPane pane = new JEditorPane();
        pane.setContentType("text/html");
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        pane.setText("<html><body style=\"margin:0;color:" + hex(foreground) + ";font-family:sans-serif;font-size:11px\">"
                     + body + "</body></html>");
        pane.setAlignmentX(Component.LEFT_ALIGNMENT);
        return pane;
    }

    private ConsoleArea consoleArea() {
        ConsoleArea area = new ConsoleArea();
        area.setFont(mono);
        area.setForeground(foreground);
        area.setSelectionColor(mix(foreground, background, 0.30f));
        area.setSelectedTextColor(foreground);
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        return area;
    }

    /** Swing renders HTML 3.2 and prints a style or script element as text. */
    static String stripActive(String html) {
        return html.replaceAll("(?is)<style[^>]*>.*?</style>", "").replaceAll("(?is)<script[^>]*>.*?</script>", "");
    }

    /** source, text and data are a string or a list of lines; streaming keeps a builder. */
    static CharSequence text(Object value) {
        if (value instanceof CharSequence cs) {
            return cs;
        }
        return NotebookIO.joinLines(value);
    }

    static Color mix(Color a, Color b, float ratio) {
        return new Color(Math.round(a.getRed() * ratio + b.getRed() * (1 - ratio)),
                         Math.round(a.getGreen() * ratio + b.getGreen() * (1 - ratio)),
                         Math.round(a.getBlue() * ratio + b.getBlue() * (1 - ratio)));
    }

    static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
