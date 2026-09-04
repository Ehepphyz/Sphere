package com.sphere.components.terminal;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import com.sphere.theme.ThemeManager;
import com.sphere.fonts.FontLoader;
import com.sphere.utils.AppLogger;

public class TerminalView extends JPanel implements TerminalEngine.OutputListener {

    private final JTextPane output;
    private final SimpleAttributeSet currentAttrs = new SimpleAttributeSet();

    private final Map<String, Color> ansiColors = new HashMap<>();
    private boolean themeSupportsTrueColor = false;
    private static final char ESCAPE = '\u001B';
    private static final char BELL = '\u0007';
    /** An escape sequence cut between two blocks waits here. */
    private final StringBuilder carry = new StringBuilder();
    /** The theme actually read, or null when none was found. */
    private Path themeFile;

    private static class NoWrapTextPane extends JTextPane {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            
            Container parent = getParent();
            if (parent instanceof JViewport) {
                return parent.getWidth() > getUI().getPreferredSize(this).width;
            }
            return false;
        }
    }

    public TerminalView(TerminalEngine engine) {
        super(new BorderLayout());

        output = new NoWrapTextPane();
        output.setEditable(false);
        output.setBackground(Color.BLACK);
        output.setFont(FontLoader.getTerminalFont(Font.PLAIN, 12));
        output.putClientProperty("JTextPane.honorDisplayProperties", Boolean.TRUE);

        // Default style: white, not bold
        StyleConstants.setForeground(currentAttrs, Color.WHITE);
        StyleConstants.setBold(currentAttrs, false);

        JScrollPane scroll = new JScrollPane(output);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setMinimumSize(new Dimension(200, 100));

        add(scroll, BorderLayout.CENTER);

        engine.addOutputListener(this);

        // Load theme
        loadTheme("vscode-dark");
        runThemeTest();
    }

    /**
     * Updates text attributes based on ANSI escape sequences.
     * Uses the colors loaded from the theme JSON.
     */
    
    private void updateAttributesFromAnsi(String sequence) {
        // The parameters between '[' and 'm', isolated by the caller: a single ls
        // sends dozens of sequences in one block, and reading only the first one
        // painted the whole block in a single colour.
        String[] codes = sequence.isEmpty() ? new String[]{"0"} : sequence.split(";");

        for (int i = 0; i < codes.length; i++) {
            // Trim to remove any hidden whitespace or newline characters
            String cleanCode = codes[i].trim();

            // ls writes 01;34, not 1;34, so the code is read as a number.
            int value;
            try {
                value = Integer.parseInt(cleanCode);
            } catch (NumberFormatException ex) {
                continue;
            }

            if (value == 38 || value == 48) {
                i = applyExtendedColor(codes, i);
            } else if (value == 0) {
                StyleConstants.setForeground(currentAttrs, output.getForeground());
                StyleConstants.setBold(currentAttrs, false);
                StyleConstants.setItalic(currentAttrs, false);
                StyleConstants.setUnderline(currentAttrs, false);
                currentAttrs.removeAttribute(StyleConstants.Background);
            } else if (value == 1) {
                StyleConstants.setBold(currentAttrs, true);
            } else if (value == 3) {
                StyleConstants.setItalic(currentAttrs, true);
            } else if (value == 4) {
                StyleConstants.setUnderline(currentAttrs, true);
            } else if (value == 22) {
                StyleConstants.setBold(currentAttrs, false);
            } else if (value == 23) {
                StyleConstants.setItalic(currentAttrs, false);
            } else if (value == 24) {
                StyleConstants.setUnderline(currentAttrs, false);
            } else if (value == 39) {
                StyleConstants.setForeground(currentAttrs, output.getForeground());
            } else if (value == 49) {
                currentAttrs.removeAttribute(StyleConstants.Background);
            } else if (value >= 30 && value <= 37) {
                StyleConstants.setForeground(currentAttrs, ansiColor(value, value - 30));
            } else if (value >= 90 && value <= 97) {
                StyleConstants.setForeground(currentAttrs, ansiColor(value, value - 90 + 8));
            } else if (value >= 40 && value <= 47) {
                StyleConstants.setBackground(currentAttrs, ansiColor(value, value - 40));
            } else if (value >= 100 && value <= 107) {
                StyleConstants.setBackground(currentAttrs, ansiColor(value, value - 100 + 8));
            }
        }
    }

    /** The theme's colour for this code, or the standard one when it defines none. */
    private Color ansiColor(int code, int index) {
        Color themed = ansiColors.get(String.valueOf(code));
        return themed != null ? themed : XTERM[index];
    }

    /** 38;5;n and 38;2;r;g;b; returns the index of the last code consumed. */
    private int applyExtendedColor(String[] codes, int index) {
        if (index + 1 >= codes.length) {
            return index;
        }
        String mode = codes[index + 1].trim();
        try {
            if (mode.equals("5") && index + 2 < codes.length) {
                Color picked = xterm256(Integer.parseInt(codes[index + 2].trim()));
                if (codes[index].trim().equals("38")) {
                    StyleConstants.setForeground(currentAttrs, picked);
                } else {
                    StyleConstants.setBackground(currentAttrs, picked);
                }
                return index + 2;
            }
            if (mode.equals("2") && index + 4 < codes.length) {
                // The 24-bit form applyTrueColor was already written for.
                applyTrueColor(codes[index].trim() + ";2;" + codes[index + 2].trim()
                               + ";" + codes[index + 3].trim() + ";" + codes[index + 4].trim());
                return index + 4;
            }
        } catch (NumberFormatException ex) {
            return index + 1;
        }
        return index + 1;
    }

    /** The 8 standard and 8 bright colours, for the codes a theme leaves out. */
    private static final Color[] XTERM = {
        new Color(0, 0, 0), new Color(205, 49, 49), new Color(13, 188, 121),
        new Color(229, 229, 16), new Color(36, 114, 200), new Color(188, 63, 188),
        new Color(17, 168, 205), new Color(229, 229, 229),
        new Color(102, 102, 102), new Color(241, 76, 76), new Color(35, 209, 139),
        new Color(245, 245, 67), new Color(59, 142, 234), new Color(214, 112, 214),
        new Color(41, 184, 219), new Color(255, 255, 255)
    };

    /** The 256-colour cube: 16 basic, a 6x6x6 cube, then 24 greys. */
    private static Color xterm256(int value) {
        if (value < 16) {
            return XTERM[value];
        }
        if (value < 232) {
            int[] levels = {0, 95, 135, 175, 215, 255};
            int n = value - 16;
            return new Color(levels[n / 36], levels[(n / 6) % 6], levels[n % 6]);
        }
        int grey = 8 + (value - 232) * 10;
        return new Color(grey, grey, grey);
    }

    @Override
    public void onOutput(String text) {
        SwingUtilities.invokeLater(() -> {
            try {
                write(text);
            } catch (Exception e) {
                AppLogger.error("Terminal output could not be written: " + e.getMessage());
            }
        });
    }

    /**
     * Walks the block sequence by sequence: each colour change applies to the
     * text that follows it, and everything that is not a colour is dropped.
     * A sequence cut between two blocks is held over to the next one.
     */
    private void write(String chunk) throws BadLocationException {
        carry.append(chunk);
        String text = carry.toString();
        carry.setLength(0);

        StyledDocument doc = output.getStyledDocument();
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != ESCAPE) {
                plain.append(c);
                i++;
                continue;
            }
            if (i + 1 >= text.length()) {
                carry.append(text, i, text.length());
                break;
            }
            char next = text.charAt(i + 1);
            if (next == '[') {
                int end = i + 2;
                while (end < text.length() && !isFinalByte(text.charAt(end))) {
                    end++;
                }
                if (end >= text.length()) {
                    carry.append(text, i, text.length());
                    break;
                }
                flush(doc, plain);
                if (text.charAt(end) == 'm') {
                    updateAttributesFromAnsi(text.substring(i + 2, end));
                }
                // Anything else moves or clears the cursor: nothing to do in a
                // view that only appends, and printing it showed [?2004h.
                i = end + 1;
            } else if (next == ']') {
                int end = i + 2;
                while (end < text.length() && text.charAt(end) != BELL
                        && text.charAt(end) != ESCAPE) {
                    end++;
                }
                if (end >= text.length()) {
                    carry.append(text, i, text.length());
                    break;
                }
                i = end + (text.charAt(end) == ESCAPE ? 2 : 1);   // a window title
            } else {
                i += 2;
            }
        }
        flush(doc, plain);
    }

    private void flush(StyledDocument doc, StringBuilder plain) throws BadLocationException {
        if (plain.length() == 0) {
            return;
        }
        doc.insertString(doc.getLength(), plain.toString(), currentAttrs);
        plain.setLength(0);
        output.setCaretPosition(doc.getLength());
    }

    private static boolean isFinalByte(char c) {
        return c >= '@' && c <= '~';
    }

    /**
     * Load a theme from /themes/*.json
     */
    public void loadTheme(String themeName) {
        // The colours below come only from this file: without it ansiColors stays
        // empty and every terminal is white on black. One relative path found
        // nothing whenever Sphere was started from anywhere but its own folder.
        Path path = findTheme(themeName);
        if (path == null) {
            AppLogger.error("Terminal theme " + themeName + ".json not found in themes/ "
                            + "or theme/, beside the working directory ("
                            + Paths.get("").toAbsolutePath() + ") or beside the "
                            + "application. The terminal will stay white on black.");
            return;
        }
        themeFile = path;
        try {
            String text = Files.readString(path);

            Map<String, Object> json = ThemeJsonParser.parse(text);

            Color bg = Color.decode((String) json.get("background"));
            Color fg = Color.decode((String) json.get("foreground"));
            Color cursor = Color.decode((String) json.get("cursor"));

            output.setBackground(bg);
            output.setForeground(fg);
            output.setCaretColor(cursor);

            // --- DYNAMIC FONT FALLBACK HANDLING ---
            String fontName = (String) json.get("font");
            int fontSize = ((Number) json.get("fontSize")).intValue();

            if (fontName == null || fontName.trim().isEmpty()) {
                // Fallback to custom FontLoader if JSON font value is missing or blank ""
                output.setFont(FontLoader.getTerminalFont(Font.PLAIN, fontSize));
            } else {
                // Apply the font family and size loaded from the JSON theme
                output.setFont(new Font(fontName, Font.PLAIN, fontSize));
            }

            Object ansiObj = json.get("ansi");
            if (ansiObj instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ansi = (Map<String, Object>) ansiObj;
                ansiColors.clear();
                for (String key : ansi.keySet()) {
                    Object colorVal = ansi.get(key);
                    if (colorVal instanceof String) {
                        ansiColors.put(key, Color.decode((String) colorVal));
                    }
                }
            }

            themeSupportsTrueColor = Boolean.TRUE.equals(json.get("truecolor"));

        } catch (Exception e) {
            AppLogger.error("Terminal theme " + path + " could not be read: " + e.getMessage());
        }
    }

    /** themes/ and theme/, beside the working directory and beside Sphere itself. */
    private static Path findTheme(String themeName) {
        String file = themeName + ".json";
        java.util.List<Path> roots = new java.util.ArrayList<>();
        roots.add(Paths.get("").toAbsolutePath());
        try {
            Path here = Paths.get(TerminalView.class.getProtectionDomain()
                                  .getCodeSource().getLocation().toURI());
            Path beside = Files.isDirectory(here) ? here : here.getParent();
            if (beside != null) {
                roots.add(beside);
                if (beside.getParent() != null) {
                    roots.add(beside.getParent());   // a jar kept in bin/ or lib/
                }
            }
        } catch (Exception ignored) {
            // running from somewhere with no readable code source
        }
        for (Path root : roots) {
            for (String folder : new String[]{"themes", "theme"}) {
                Path candidate = root.resolve(folder).resolve(file);
                if (Files.isReadable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** The theme in use, for the diagnostics; null when none was found. */
    public Path getThemeFile() {
        return themeFile;
    }

    /** The pane itself, so a second view can share this document instead of copying it. */
    public JTextPane getTextPane() {
        return output;
    }

    /**
     * Apply 24-bit TrueColor ANSI sequence.
     */
    private void applyTrueColor(String seq) {
        String[] p = seq.split(";");
        if (p.length == 5 && p[1].equals("2")) {
            int r = Integer.parseInt(p[2]);
            int g = Integer.parseInt(p[3]);
            int b = Integer.parseInt(p[4]);
            Color c = new Color(r, g, b);

            if (p[0].equals("38")) {
                StyleConstants.setForeground(currentAttrs, c);
            } else if (p[0].equals("48")) {
                StyleConstants.setBackground(currentAttrs, c);
            }
        }
    }

    public String getText() {
        return output.getText();
    }

    public Document getDocument() {
        return output.getDocument();
    }

    public void clear() {
        output.setText("");
    }

    /**
     * Shows the whole palette on request, from the terminal's context menu.
     * The first line names the theme actually loaded, which is what tells a
     * missing file apart from a display that is not applying colours.
     */
    public void printColourTest() {
        String esc = "\u001B[";
        StringBuilder out = new StringBuilder("\n");
        out.append("  theme      ")
           .append(themeFile == null ? "aucun fichier trouve, palette de secours"
                                     : themeFile.toString())
           .append("\n  standard   ");
        for (int code = 30; code <= 37; code++) {
            out.append(esc).append(code).append("m  ##");
        }
        out.append(esc).append("0m\n  vives      ");
        for (int code = 90; code <= 97; code++) {
            out.append(esc).append(code).append("m  ##");
        }
        out.append(esc).append("0m\n  fonds      ");
        for (int code = 40; code <= 47; code++) {
            out.append(esc).append(code).append("m    ");
        }
        out.append(esc).append("0m\n  styles     ")
           .append(esc).append("1mgras").append(esc).append("0m  ")
           .append(esc).append("3mitalique").append(esc).append("0m  ")
           .append(esc).append("4msouligne").append(esc).append("0m\n  256        ");
        for (int value = 16; value < 232; value += 6) {
            out.append(esc).append("38;5;").append(value).append("m#");
        }
        out.append(esc).append("0m\n  truecolor  ");
        for (int step = 0; step < 36; step++) {
            out.append(esc).append("38;2;").append(255 - step * 7).append(';')
               .append(step * 7).append(";128m#");
        }
        out.append(esc).append("0m\n\n");
        onOutput(out.toString());
    }

    /**
     * Test method to verify that colors are being applied from the JSON theme.
     */
    public void runThemeTest() {
        // These strings trigger the ANSI codes 31, 32, etc.
        // They match the keys in your JSON (e.g., "31")
        onOutput("");
        /**onOutput("\u001B[31mThis should be Red\u001B[0m\n");
        onOutput("\u001B[32mThis should be Green\u001B[0m\n");
        onOutput("\u001B[34mThis should be Blue\u001B[0m\n");
        onOutput("This should be the default foreground color\n");
        **/
    }
}
