package com.sphere.core.python.jupyterlab;

import com.sphere.theme.Contrast;

import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import java.awt.Color;

/**
 * Terminal output as a terminal shows it, appended chunk by chunk to a console:
 * ANSI colors (ipykernel colors every traceback, tqdm and logging color their
 * lines), a carriage return that rewrites the current line (a progress bar
 * prints itself a thousand times over one line), backspace, and erase-line. The
 * 16 ANSI colors are made readable on the console's own background, so a
 * "black" escape stays visible on a dark theme.
 *
 * Only the last {@code maxLines} lines are kept: a training log of a million
 * lines shows its end, and says how many lines came before.
 */
public final class AnsiText {

    /** xterm's 16 colors, as VS Code's dark theme states them. */
    private static final int[] BASE16 = {
        0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
        0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF };

    private final ConsoleArea area;
    private final Color background;
    private final int maxLines;
    private final Color[] palette = new Color[16];

    /** The background of the current run (stderr is tinted), under any escape's own. */
    private Color baseBg;
    private Color fg;
    private Color bg;
    private ConsoleArea.Style style = ConsoleArea.Style.PLAIN;

    private final StringBuilder escape = new StringBuilder();
    private boolean inEscape;
    /** A '\r' that is not yet known to start "\r\n". */
    private boolean pendingReturn;
    private int hiddenLines;
    private int lines = 1;

    /** The console's document, held here so it can be filled while taken off its area. */
    private final Document document;

    public AnsiText(ConsoleArea area, Color foreground, Color background, int maxLines) {
        this.area = area;
        this.document = area.getDocument();
        this.background = background;
        this.maxLines = Math.max(50, maxLines);
        area.setForeground(foreground);
        for (int i = 0; i < 16; i++) {
            palette[i] = Contrast.readable(new Color(BASE16[i]), background, 3.0);
        }
    }

    private Document doc() {
        return document;
    }

    /** Lines dropped from the top to keep the last maxLines. */
    public int hiddenLines() {
        return hiddenLines;
    }

    /** Counts lines a caller cut before handing the text over. */
    public void addHidden(int count) {
        hiddenLines += Math.max(0, count);
    }

    /**
     * Starts the text of another stream (stdout after stderr): on a line of its
     * own, with its own carriage-return state, so a progress bar redrawing itself
     * on stderr cannot wipe out a line printed on stdout. {@code tint} colors the
     * background of the run (stderr), or null.
     */
    public void newRun(Color tint) {
        pendingReturn = false;
        baseBg = tint;
        fg = null;
        bg = null;
        restyle();
        try {
            Document d = doc();
            int len = d.getLength();
            if (len > 0 && !"\n".equals(d.getText(len - 1, 1))) {
                d.insertString(len, "\n", null);
                lines++;
            }
        } catch (BadLocationException ignored) {
            // empty document
        }
    }

    /**
     * What a terminal would leave of a finished text, computed on the string:
     * "\r" followed by text drops the line so far, as JupyterLab does it. A saved
     * training log of a hundred thousand progress redraws shrinks to its final
     * lines before any of it reaches a document.
     */
    public static String collapse(CharSequence text) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), 1 << 20));
        int lineStart = 0;
        boolean pending = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (pending && c != '\n') {
                out.setLength(lineStart);
            }
            pending = false;
            if (c == '\r') {
                pending = true;
                continue;
            }
            out.append(c);
            if (c == '\n') {
                lineStart = out.length();
            }
        }
        return out.toString();
    }

    /** Interprets and appends one chunk; state (colors, a pending \r) carries over to the next. */
    public void append(CharSequence text) {
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inEscape) {
                escape.append(c);
                if (escape.length() == 1 && c != '[' && c != ']') {
                    inEscape = false; // a lone ESC x: dropped
                    escape.setLength(0);
                } else if (escape.charAt(0) == ']') {
                    // OSC (a window title, a hyperlink): ends with BEL or ESC \
                    if (c == 7 || c == '\\') {
                        inEscape = false;
                        escape.setLength(0);
                    }
                } else if (c >= '@' && c <= '~' && escape.length() > 1) {
                    flush(run);
                    csi(escape.substring(1, escape.length() - 1), c);
                    inEscape = false;
                    escape.setLength(0);
                }
                continue;
            }
            if (pendingReturn) {
                pendingReturn = false;
                if (c != '\n') {
                    flush(run);
                    clearCurrentLine(); // "\r" then text: the text replaces the line
                }
            }
            switch (c) {
                case 27 -> {
                    flush(run);
                    inEscape = true;
                    escape.setLength(0);
                }
                case '\r' -> pendingReturn = true;
                case '\b' -> {
                    if (run.length() > 0) {
                        run.setLength(run.length() - 1);
                    } else {
                        removeLastCharOfLine();
                    }
                }
                case '\n' -> {
                    run.append('\n');
                    lines++;
                }
                default -> {
                    if (c >= ' ' || c == '\t') {
                        run.append(c);
                    }
                }
            }
        }
        flush(run);
        trim();
    }

    private void flush(StringBuilder run) {
        if (run.length() == 0) {
            return;
        }
        try {
            Document d = doc();
            int at = d.getLength();
            d.insertString(at, run.toString(), null);
            area.runs().add(at, at + run.length(), style);
        } catch (BadLocationException ignored) {
            // appended at the end, which always exists
        }
        run.setLength(0);
    }

    /** Empties the last line, found through the line elements: a redraw costs the line, not the log. */
    private void clearCurrentLine() {
        try {
            Document d = doc();
            Element root = d.getDefaultRootElement();
            int start = root.getElement(root.getElementCount() - 1).getStartOffset();
            int end = d.getLength();
            if (end > start) {
                d.remove(start, end - start);
                area.runs().truncate(start);
            }
        } catch (BadLocationException ignored) {
            // nothing to clear
        }
    }

    private void removeLastCharOfLine() {
        try {
            Document d = doc();
            int len = d.getLength();
            if (len > 0 && !"\n".equals(d.getText(len - 1, 1))) {
                d.remove(len - 1, 1);
                area.runs().truncate(len - 1);
            }
        } catch (BadLocationException ignored) {
            // empty document
        }
    }

    /** Keeps the last maxLines lines; the first lines go, a block at a time. */
    private void trim() {
        if (lines <= maxLines + maxLines / 4) {
            return;
        }
        Document d = doc();
        Element root = d.getDefaultRootElement();
        int drop = root.getElementCount() - maxLines;
        if (drop <= 0) {
            lines = root.getElementCount();
            return;
        }
        try {
            int end = Math.min(root.getElement(drop - 1).getEndOffset(), d.getLength());
            d.remove(0, end);
            area.runs().dropHead(end);
            hiddenLines += drop;
        } catch (BadLocationException ignored) {
            // the document changed under us; the next chunk trims again
        }
        lines = d.getDefaultRootElement().getElementCount();
    }

    private void restyle() {
        Color b = bg != null ? bg : baseBg;
        style = fg == null && b == null ? ConsoleArea.Style.PLAIN : new ConsoleArea.Style(fg, b);
    }

    /** CSI sequences: SGR colors, and erase-line, which tqdm sends before each redraw. */
    private void csi(String params, char command) {
        if (command == 'K') {
            clearCurrentLine();
            return;
        }
        if (command != 'm') {
            return; // cursor movement and the rest: a console is not a screen
        }
        String[] parts = params.isEmpty() ? new String[] { "0" } : params.split(";");
        for (int i = 0; i < parts.length; i++) {
            int code;
            try {
                code = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (code == 0) {
                fg = null;
                bg = null;
            } else if (code >= 30 && code <= 37) {
                fg = palette[code - 30];
            } else if (code >= 90 && code <= 97) {
                fg = palette[code - 90 + 8];
            } else if (code == 39) {
                fg = null;
            } else if (code >= 40 && code <= 47) {
                bg = tint(palette[code - 40]);
            } else if (code >= 100 && code <= 107) {
                bg = tint(palette[code - 100 + 8]);
            } else if (code == 49) {
                bg = null;
            } else if ((code == 38 || code == 48) && i + 1 < parts.length) {
                Color c = null;
                if ("5".equals(parts[i + 1]) && i + 2 < parts.length) {
                    c = xterm256(parse(parts[i + 2]));
                    i += 2;
                } else if ("2".equals(parts[i + 1]) && i + 4 < parts.length) {
                    c = new Color(clamp(parse(parts[i + 2])), clamp(parse(parts[i + 3])), clamp(parse(parts[i + 4])));
                    i += 4;
                }
                if (c != null) {
                    if (code == 38) {
                        fg = Contrast.readable(c, background, 3.0);
                    } else {
                        bg = tint(c);
                    }
                }
            }
            // bold, italic and underline are left out: the console keeps one font,
            // so the caret and the selection stay on the glyphs they cover
        }
        restyle();
    }

    /** A background escape, toned down so the text over it still reads. */
    private Color tint(Color c) {
        float k = 0.35f;
        return new Color(Math.round(c.getRed() * k + background.getRed() * (1 - k)),
                         Math.round(c.getGreen() * k + background.getGreen() * (1 - k)),
                         Math.round(c.getBlue() * k + background.getBlue() * (1 - k)));
    }

    private Color xterm256(int n) {
        if (n < 16) {
            return palette[Math.max(0, n)];
        }
        if (n < 232) {
            int v = n - 16;
            int[] steps = { 0, 95, 135, 175, 215, 255 };
            return new Color(steps[(v / 36) % 6], steps[(v / 6) % 6], steps[v % 6]);
        }
        int g = 8 + (Math.min(n, 255) - 232) * 10;
        return new Color(g, g, g);
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** The text without its escapes, for a search or a tooltip. */
    public static String strip(String text) {
        return text == null ? "" : text.replaceAll("\u001B\\[[0-?]*[ -/]*[@-~]", "").replaceAll("\u001B\\][^\u0007]*\u0007", "");
    }
}
