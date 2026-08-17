package com.sphere.components.terminal;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import com.sphere.theme.ThemeManager;
import com.sphere.fonts.FontLoader;

public class TerminalView extends JPanel implements TerminalEngine.OutputListener {

    private final JTextPane output;
    private final SimpleAttributeSet currentAttrs = new SimpleAttributeSet();

    private final Map<String, Color> ansiColors = new HashMap<>();
    private boolean themeSupportsTrueColor = false;

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
    
    private void updateAttributesFromAnsi(String text) {
        if (!text.contains("\u001B[")) return;

        // 1. Isolate the ANSI escape part
        int start = text.indexOf("\u001B[");
        int end = text.indexOf("m", start);
        if (start == -1 || end == -1) return;

        String sequence = text.substring(start + 2, end); // Extract content between '[' and 'm'
        String[] codes = sequence.split(";");

        // 2. Iterate through all codes (e.g., "1;31")
        for (String code : codes) {
            // Trim to remove any hidden whitespace or newline characters
            String cleanCode = code.trim();

            if (ansiColors.containsKey(cleanCode)) {
                StyleConstants.setForeground(currentAttrs, ansiColors.get(cleanCode));
            } else if (cleanCode.equals("0")) {
                StyleConstants.setForeground(currentAttrs, output.getForeground());
                StyleConstants.setBold(currentAttrs, false);
            } else if (cleanCode.equals("1")) {
                StyleConstants.setBold(currentAttrs, true);
            }
        }
    }

    @Override
    public void onOutput(String text) {
        SwingUtilities.invokeLater(() -> {
            try {
                StyledDocument doc = output.getStyledDocument();

                // Update attributes based on the current ANSI sequence found in the text
                updateAttributesFromAnsi(text);

                // Remove ANSI escape codes for the displayed string
                String clean = text.replaceAll("\u001B\\[[;\\d]*m", "");

                if (!clean.isEmpty()) {
                    doc.insertString(doc.getLength(), clean, currentAttrs);
                    output.setCaretPosition(doc.getLength());
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * Load a theme from /themes/*.json
     */
    public void loadTheme(String themeName) {
        try {
            Path path = Paths.get("themes/" + themeName + ".json");
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
            e.printStackTrace();
        }
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
     * Test method to verify that colors are being applied from the JSON theme.
     */
    public void runThemeTest() {
        // These strings trigger the ANSI codes 31, 32, etc.
        // They match the keys in your JSON (e.g., "31")
        onOutput("\u001B[31mThis should be Red\u001B[0m\n");
        onOutput("\u001B[32mThis should be Green\u001B[0m\n");
        onOutput("\u001B[34mThis should be Blue\u001B[0m\n");
        onOutput("This should be the default foreground color\n");
    }
}
