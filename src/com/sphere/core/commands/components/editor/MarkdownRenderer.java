package com.sphere.components.editor;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown to HTML for the editor preview.
 *
 * The target is not a browser: JEditorPane implements HTML 3.2 and a slice of
 * CSS1, so rounded corners, flexbox and overflow are ignored and tables need
 * their border attribute. Everything below stays inside what Swing draws.
 *
 * Colors come from EditorTheme, so the preview follows the editor instead of
 * being dark whatever the user chose.
 */
public class MarkdownRenderer {

    private MarkdownRenderer() { }

    /* Placeholders keep code and math out of the inline pass, which would
     * otherwise turn an asterisk inside a formula into italics. */
    private static final char HOLD_OPEN = '\u0001';
    private static final char HOLD_CLOSE = '\u0002';
    /** Marks a hard line break so escaping cannot turn the tag into text. */
    private static final String BREAK = "\u0004";

    private static final Pattern ATX =
        Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern BULLET =
        Pattern.compile("^(\\s*)([-*+])\\s+(.*)$");
    private static final Pattern ORDERED =
        Pattern.compile("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$");
    private static final Pattern TASK =
        Pattern.compile("^\\[([ xX])\\]\\s+(.*)$");
    private static final Pattern RULE =
        Pattern.compile("^\\s*(?:(?:-\\s*){3,}|(?:\\*\\s*){3,}|(?:_\\s*){3,})$");
    private static final Pattern TABLE_DIVIDER =
        Pattern.compile("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$");
    private static final Pattern FENCE =
        Pattern.compile("^\\s*(```+|~~~+)\\s*([A-Za-z0-9_+#.-]*)\\s*$");

    /**
     * Converts Markdown into HTML the editor preview can display.
     *
     * @param text raw Markdown, or null
     * @return a complete HTML document
     */
    public static String render(String text) {
        StringBuilder html = new StringBuilder(1024);
        html.append("<html><head><style>").append(css()).append("</style></head><body>");
        if (text != null && !text.isEmpty()) {
            new Blocks(html).run(text);
        }
        html.append("</body></html>");
        return html.toString();
    }

    /* ------------------------------------------------------------------ */
    /* Block level                                                         */
    /* ------------------------------------------------------------------ */

    private static final class Blocks {

        private final StringBuilder out;
        private final List<String> openLists = new ArrayList<>();
        private final List<Integer> listIndents = new ArrayList<>();
        private int quoteDepth = 0;

        Blocks(StringBuilder out) { this.out = out; }

        void run(String text) {
            String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            int i = 0;

            // A YAML front matter block belongs to the file, not to the reader
            if (lines.length > 0 && lines[0].trim().equals("---")) {
                int end = -1;
                for (int k = 1; k < lines.length; k++) {
                    String t = lines[k].trim();
                    if (t.equals("---") || t.equals("...")) { end = k; break; }
                }
                if (end > 0) i = end + 1;
            }

            while (i < lines.length) {
                String line = lines[i];
                String trimmed = line.trim();

                Matcher fence = FENCE.matcher(line);
                if (fence.matches()) {
                    String marker = fence.group(1).substring(0, 3);
                    String language = fence.group(2).toLowerCase(Locale.ROOT);
                    StringBuilder code = new StringBuilder();
                    int k = i + 1;
                    boolean closed = false;
                    while (k < lines.length) {
                        if (lines[k].trim().startsWith(marker)
                                && lines[k].trim().chars().allMatch(c -> c == marker.charAt(0))) {
                            closed = true;
                            break;
                        }
                        code.append(lines[k]).append('\n');
                        k++;
                    }
                    closeAll();
                    emitCode(code.toString(), language);
                    // An unclosed fence used to swallow the rest of the document;
                    // the text after it is prose and is rendered as such.
                    i = closed ? k + 1 : i + 1;
                    continue;
                }

                if (trimmed.isEmpty()) {
                    closeLists();
                    closeQuote();
                    i++;
                    continue;
                }

                if (RULE.matcher(line).matches()) {
                    closeAll();
                    out.append("<hr>");
                    i++;
                    continue;
                }

                // An indented block is code only outside a list, where four spaces
                // mean continuation instead
                if (openLists.isEmpty() && (line.startsWith("    ") || line.startsWith("\t"))) {
                    StringBuilder code = new StringBuilder();
                    int k = i;
                    while (k < lines.length
                           && (lines[k].startsWith("    ") || lines[k].startsWith("\t")
                               || lines[k].trim().isEmpty())) {
                        if (lines[k].trim().isEmpty() && !hasMoreIndented(lines, k)) break;
                        code.append(lines[k].startsWith("\t") ? lines[k].substring(1)
                                                              : stripUpTo(lines[k], 4))
                            .append('\n');
                        k++;
                    }
                    closeAll();
                    emitCode(code.toString(), "");
                    i = k;
                    continue;
                }

                if (trimmed.startsWith(">")) {
                    openQuote();
                    String inner = trimmed.substring(1);
                    if (inner.startsWith(" ")) inner = inner.substring(1);
                    if (inner.isBlank()) { i++; continue; }
                    out.append("<p>").append(Inline.apply(inner)).append("</p>");
                    i++;
                    continue;
                }
                closeQuote();

                Matcher atx = ATX.matcher(line);
                if (atx.matches()) {
                    closeLists();
                    int level = atx.group(1).length();
                    out.append("<h").append(level).append('>')
                       .append(Inline.apply(atx.group(2)))
                       .append("</h").append(level).append('>');
                    i++;
                    continue;
                }

                // Setext: a line underlined with = or - is a heading
                if (i + 1 < lines.length && !trimmed.isEmpty()) {
                    String next = lines[i + 1].trim();
                    if (!next.isEmpty() && next.chars().allMatch(c -> c == '=')) {
                        closeLists();
                        out.append("<h1>").append(Inline.apply(trimmed)).append("</h1>");
                        i += 2;
                        continue;
                    }
                    if (!next.isEmpty() && next.chars().allMatch(c -> c == '-')
                            && !BULLET.matcher(lines[i + 1]).matches()) {
                        closeLists();
                        out.append("<h2>").append(Inline.apply(trimmed)).append("</h2>");
                        i += 2;
                        continue;
                    }
                }

                // A pipe table needs its divider row on the line below
                if (line.contains("|") && i + 1 < lines.length
                        && TABLE_DIVIDER.matcher(lines[i + 1]).matches()
                        && lines[i + 1].contains("-")) {
                    closeAll();
                    i = emitTable(lines, i);
                    continue;
                }

                Matcher bullet = BULLET.matcher(line);
                Matcher ordered = ORDERED.matcher(line);
                if (bullet.matches() || ordered.matches()) {
                    boolean numbered = !bullet.matches();
                    Matcher m = numbered ? ordered : bullet;
                    int indent = m.group(1).replace("\t", "    ").length();
                    String item = m.group(3);
                    adjustLists(numbered ? "ol" : "ul", indent);

                    Matcher task = TASK.matcher(item);
                    if (task.matches()) {
                        boolean done = !task.group(1).equals(" ");
                        out.append("<li>")
                           .append(done ? "<b>[x]</b> " : "[&nbsp;] ")
                           .append(Inline.apply(task.group(2)))
                           .append("</li>");
                    } else {
                        out.append("<li>").append(Inline.apply(item)).append("</li>");
                    }
                    i++;
                    continue;
                }
                closeLists();

                // A paragraph runs until a blank line or the start of another block
                StringBuilder paragraph = new StringBuilder();
                int k = i;
                while (k < lines.length && !lines[k].trim().isEmpty()
                       && !startsBlock(lines, k)) {
                    if (paragraph.length() > 0) {
                        // Two trailing spaces are Markdown's hard line break
                        paragraph.append(lines[k - 1].endsWith("  ") ? BREAK : " ");
                    }
                    paragraph.append(lines[k].trim());
                    k++;
                }
                if (paragraph.length() > 0) {
                    out.append("<p>").append(Inline.apply(paragraph.toString())).append("</p>");
                }
                i = Math.max(k, i + 1);
            }
            closeAll();
        }

        private boolean startsBlock(String[] lines, int k) {
            if (k == 0) return false;
            String line = lines[k];
            String trimmed = line.trim();
            return FENCE.matcher(line).matches()
                || ATX.matcher(line).matches()
                || RULE.matcher(line).matches()
                || trimmed.startsWith(">")
                || BULLET.matcher(line).matches()
                || ORDERED.matcher(line).matches();
        }

        private boolean hasMoreIndented(String[] lines, int from) {
            for (int k = from + 1; k < lines.length; k++) {
                if (lines[k].trim().isEmpty()) continue;
                return lines[k].startsWith("    ") || lines[k].startsWith("\t");
            }
            return false;
        }

        private static String stripUpTo(String line, int count) {
            int i = 0;
            while (i < count && i < line.length() && line.charAt(i) == ' ') i++;
            return line.substring(i);
        }

        private void adjustLists(String tag, int indent) {
            while (!openLists.isEmpty()
                   && listIndents.get(listIndents.size() - 1) > indent) {
                out.append("</").append(openLists.remove(openLists.size() - 1)).append('>');
                listIndents.remove(listIndents.size() - 1);
            }
            boolean sameLevel = !openLists.isEmpty()
                && listIndents.get(listIndents.size() - 1) == indent;
            if (sameLevel && !openLists.get(openLists.size() - 1).equals(tag)) {
                out.append("</").append(openLists.remove(openLists.size() - 1)).append('>');
                listIndents.remove(listIndents.size() - 1);
                sameLevel = false;
            }
            if (!sameLevel) {
                out.append('<').append(tag).append('>');
                openLists.add(tag);
                listIndents.add(indent);
            }
        }

        private void closeLists() {
            while (!openLists.isEmpty()) {
                out.append("</").append(openLists.remove(openLists.size() - 1)).append('>');
                listIndents.remove(listIndents.size() - 1);
            }
        }

        private void openQuote() {
            if (quoteDepth == 0) {
                closeLists();
                out.append("<blockquote>");
                quoteDepth = 1;
            }
        }

        private void closeQuote() {
            if (quoteDepth > 0) {
                out.append("</blockquote>");
                quoteDepth = 0;
            }
        }

        private void closeAll() {
            closeLists();
            closeQuote();
        }

        private void emitCode(String code, String language) {
            String body = code.endsWith("\n") ? code.substring(0, code.length() - 1) : code;
            // No <br> here: a pre element already keeps its newlines, and adding
            // them doubled every blank line in the block.
            out.append("<pre>").append(highlightSyntax(body, language)).append("</pre>");
        }

        private int emitTable(String[] lines, int start) {
            String[] alignments = splitRow(lines[start + 1]);
            String[] header = splitRow(lines[start]);

            out.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\">");
            out.append("<tr>");
            for (int c = 0; c < header.length; c++) {
                out.append("<th").append(align(alignments, c)).append('>')
                   .append(Inline.apply(header[c])).append("</th>");
            }
            out.append("</tr>");

            int i = start + 2;
            while (i < lines.length && lines[i].contains("|") && !lines[i].trim().isEmpty()) {
                String[] cells = splitRow(lines[i]);
                out.append("<tr>");
                for (int c = 0; c < header.length; c++) {
                    out.append("<td").append(align(alignments, c)).append('>')
                       .append(c < cells.length ? Inline.apply(cells[c]) : "")
                       .append("</td>");
                }
                out.append("</tr>");
                i++;
            }
            out.append("</table>");
            return i;
        }

        private static String align(String[] alignments, int column) {
            if (column >= alignments.length) return "";
            String spec = alignments[column].trim();
            if (spec.startsWith(":") && spec.endsWith(":")) return " align=\"center\"";
            if (spec.endsWith(":")) return " align=\"right\"";
            return "";
        }

        private static String[] splitRow(String line) {
            String row = line.trim();
            if (row.startsWith("|")) row = row.substring(1);
            if (row.endsWith("|")) row = row.substring(0, row.length() - 1);
            String[] cells = row.split("\\|", -1);
            for (int i = 0; i < cells.length; i++) cells[i] = cells[i].trim();
            return cells;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Inline level                                                        */
    /* ------------------------------------------------------------------ */

    private static final class Inline {

        static String apply(String raw) {
            if (raw == null || raw.isEmpty()) return "";

            List<String> held = new ArrayList<>();
            String text = raw;

            // Code spans and math come out first, so no emphasis rule can reach
            // inside them. A formula full of asterisks stays a formula.
            text = hold(text, Pattern.compile("``(.+?)``", Pattern.DOTALL), held, "code");
            text = hold(text, Pattern.compile("`([^`\\n]+?)`"), held, "code");
            text = hold(text, Pattern.compile("\\$\\$(.+?)\\$\\$", Pattern.DOTALL), held, "math");
            text = hold(text, Pattern.compile("\\$([^$\\n]+?)\\$"), held, "math");
            text = hold(text, Pattern.compile("\\\\([\\\\`*_{}\\[\\]()#+\\-.!~>|])"),
                        held, "lit");

            text = escape(text);
            text = text.replace(BREAK, "<br>");

            text = text.replaceAll("!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+&quot;[^&]*&quot;)?\\)",
                                   "<img src=\"$2\" alt=\"$1\">");
            text = text.replaceAll("\\[([^\\]]+)\\]\\(([^)\\s]+)(?:\\s+[^)]*)?\\)",
                                   "<a href=\"$2\">$1</a>");
            text = text.replaceAll("&lt;(https?://[^&\\s]+)&gt;", "<a href=\"$1\">$1</a>");

            text = text.replaceAll("\\*\\*\\*(.+?)\\*\\*\\*", "<b><i>$1</i></b>");
            text = text.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
            text = text.replaceAll("__(.+?)__", "<b>$1</b>");
            text = text.replaceAll("(?<![*\\w])\\*([^*\\n]+?)\\*(?!\\*)", "<i>$1</i>");
            text = text.replaceAll("(?<![_\\w])_([^_\\n]+?)_(?![_\\w])", "<i>$1</i>");
            text = text.replaceAll("~~(.+?)~~", "<strike>$1</strike>");

            return release(text, held);
        }

        private static String hold(String text, Pattern pattern, List<String> held, String kind) {
            Matcher m = pattern.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                held.add(kind + '\u0003' + m.group(1));
                m.appendReplacement(sb,
                        Matcher.quoteReplacement(HOLD_OPEN + Integer.toString(held.size() - 1)
                                                 + HOLD_CLOSE));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        private static String release(String text, List<String> held) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c != HOLD_OPEN) { sb.append(c); continue; }
                int end = text.indexOf(HOLD_CLOSE, i);
                if (end < 0) { sb.append(c); continue; }
                int index;
                try { index = Integer.parseInt(text.substring(i + 1, end)); }
                catch (NumberFormatException bad) { sb.append(c); continue; }
                if (index < 0 || index >= held.size()) { sb.append(c); continue; }

                String entry = held.get(index);
                int split = entry.indexOf('\u0003');
                String kind = entry.substring(0, split);
                String body = escape(entry.substring(split + 1));
                if (kind.equals("lit")) {
                    // A backslash escape: the character itself, no markup
                    sb.append(body);
                } else if (kind.equals("math")) {
                    // Swing cannot typeset LaTeX. Showing the source, marked as
                    // a formula, beats mangling it into italics.
                    sb.append("<code class=\"math\">").append(body).append("</code>");
                } else {
                    sb.append("<code>").append(body).append("</code>");
                }
                i = end;
            }
            return sb.toString();
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    /* ------------------------------------------------------------------ */
    /* Code coloring                                                       */
    /* ------------------------------------------------------------------ */

    private static final String CPP_KEYWORDS =
        "alignas|alignof|and|asm|auto|bool|break|case|catch|char|class|const|constexpr|"
      + "const_cast|continue|decltype|default|delete|do|double|dynamic_cast|else|enum|"
      + "explicit|export|extern|false|float|for|friend|goto|if|inline|int|long|mutable|"
      + "namespace|new|noexcept|nullptr|operator|private|protected|public|register|"
      + "reinterpret_cast|return|short|signed|sizeof|static|static_cast|struct|switch|"
      + "template|this|throw|true|try|typedef|typeid|typename|union|unsigned|using|"
      + "virtual|void|volatile|while";
    private static final String JAVA_KEYWORDS =
        "abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|"
      + "do|double|else|enum|extends|final|finally|float|for|if|implements|import|"
      + "instanceof|int|interface|long|native|new|package|private|protected|public|"
      + "record|return|short|static|super|switch|synchronized|this|throw|throws|"
      + "transient|try|var|void|volatile|while|true|false|null";
    private static final String PYTHON_KEYWORDS =
        "and|as|assert|async|await|break|class|continue|def|del|elif|else|except|False|"
      + "finally|for|from|global|if|import|in|is|lambda|None|nonlocal|not|or|pass|raise|"
      + "return|True|try|while|with|yield";
    private static final String SHELL_KEYWORDS =
        "if|then|else|elif|fi|for|while|do|done|case|esac|function|return|export|local|"
      + "source|alias|echo|cd|exit";

    /**
     * Colors a code block. One pass over the text classifies comments, strings,
     * numbers and keywords together, so a keyword inside a string is left alone.
     *
     * @param code escaped or raw source
     * @param language the fence's language tag, possibly empty
     */
    private static String highlightSyntax(String code, String language) {
        String keywords = keywordsFor(language);
        if (keywords == null) return escape(code);

        boolean hash = isHashComment(language);
        Pattern token = Pattern.compile(
              "(?<comment>" + (hash ? "#[^\\n]*" : "//[^\\n]*|/\\*.*?\\*/") + ")"
            + "|(?<pre>^[ \\t]*#[A-Za-z_]+[^\\n]*)"
            + "|(?<string>\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*')"
            + "|(?<number>\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[fFuUlL]*\\b)"
            + "|(?<keyword>\\b(?:" + keywords + ")\\b)",
            Pattern.DOTALL | Pattern.MULTILINE);

        Matcher m = token.matcher(code);
        StringBuilder out = new StringBuilder(code.length() + 64);
        int last = 0;
        while (m.find()) {
            out.append(escape(code.substring(last, m.start())));
            String cls = m.group("comment") != null ? "cmt"
                       : m.group("pre") != null && !hash ? "pre"
                       : m.group("string") != null ? "str"
                       : m.group("number") != null ? "num"
                       : "kwd";
            out.append("<span class=\"").append(cls).append("\">")
               .append(escape(m.group())).append("</span>");
            last = m.end();
        }
        out.append(escape(code.substring(last)));
        return out.toString();
    }

    private static String keywordsFor(String language) {
        switch (language == null ? "" : language.toLowerCase(Locale.ROOT)) {
            case "cpp": case "c++": case "cxx": case "cc": case "c": case "h": case "hpp":
                return CPP_KEYWORDS;
            case "java":
                return JAVA_KEYWORDS;
            case "python": case "py":
                return PYTHON_KEYWORDS;
            case "bash": case "sh": case "shell": case "zsh":
                return SHELL_KEYWORDS;
            case "json": case "js": case "javascript":
                return "true|false|null|function|var|let|const|return|if|else|for|while|class";
            default:
                return null;
        }
    }

    private static boolean isHashComment(String language) {
        String l = language == null ? "" : language.toLowerCase(Locale.ROOT);
        return l.equals("python") || l.equals("py") || l.equals("bash")
            || l.equals("sh") || l.equals("shell") || l.equals("zsh");
    }

    /* ------------------------------------------------------------------ */
    /* Style                                                               */
    /* ------------------------------------------------------------------ */

    private static String css() {
        Color background = EditorTheme.background();
        Color foreground = EditorTheme.foreground();
        Color accent = EditorTheme.accent();
        boolean dark = luminance(background) < 0.5;

        Color heading = accent;
        Color rule = EditorTheme.mix(foreground, background, 0.25f);
        Color codeBackground = dark ? EditorTheme.shift(background, 0.06f)
                                    : EditorTheme.shift(background, -0.05f);
        Color quote = EditorTheme.mix(foreground, background, 0.6f);

        StringBuilder css = new StringBuilder(768);
        css.append("body { background-color: ").append(hex(background))
           .append("; color: ").append(hex(foreground))
           .append("; font-family: sans-serif; font-size: 12pt;")
           .append(" margin: 0px; padding: 8px; }");

        css.append("h1 { color: ").append(hex(heading))
           .append("; font-size: 20pt; margin: 10px 0px 6px 0px;")
           .append(" border-bottom: 1px solid ").append(hex(rule)).append("; }");
        css.append("h2 { color: ").append(hex(heading))
           .append("; font-size: 17pt; margin: 10px 0px 5px 0px;")
           .append(" border-bottom: 1px solid ").append(hex(rule)).append("; }");
        css.append("h3 { color: ").append(hex(heading))
           .append("; font-size: 14pt; margin: 9px 0px 4px 0px; }");
        css.append("h4 { color: ").append(hex(heading))
           .append("; font-size: 12pt; margin: 8px 0px 4px 0px; }");
        css.append("h5, h6 { color: ").append(hex(quote))
           .append("; font-size: 11pt; margin: 8px 0px 4px 0px; }");

        css.append("p { margin: 5px 0px 5px 0px; }");
        css.append("ul, ol { margin: 4px 0px 4px 0px; }");
        css.append("li { margin: 2px 0px 2px 0px; }");
        css.append("a { color: ").append(hex(accent)).append("; }");
        css.append("blockquote { color: ").append(hex(quote))
           .append("; margin: 6px 0px 6px 12px; }");
        css.append("hr { color: ").append(hex(rule)).append("; }");

        css.append("code { font-family: monospace; color: ")
           .append(hex(EditorTheme.token(TokenKind.STRING)))
           .append("; background-color: ").append(hex(codeBackground)).append("; }");
        css.append("code.math { color: ").append(hex(accent)).append("; }");
        css.append("pre { font-family: monospace; font-size: 11pt; background-color: ")
           .append(hex(codeBackground))
           .append("; color: ").append(hex(foreground))
           .append("; padding: 6px; margin: 6px 0px 6px 0px;")
           .append(" border: 1px solid ").append(hex(rule)).append("; }");

        css.append("table { margin: 6px 0px 6px 0px; }");
        css.append("th { background-color: ").append(hex(codeBackground))
           .append("; color: ").append(hex(foreground)).append("; text-align: left; }");
        css.append("td { color: ").append(hex(foreground)).append("; }");

        // The token colors are the editor's own, so a block reads the same as the source
        css.append(".kwd { color: ").append(hex(EditorTheme.token(TokenKind.KEYWORD)))
           .append("; font-weight: bold; }");
        css.append(".typ { color: ").append(hex(EditorTheme.token(TokenKind.TYPE))).append("; }");
        css.append(".str { color: ").append(hex(EditorTheme.token(TokenKind.STRING))).append("; }");
        css.append(".cmt { color: ").append(hex(EditorTheme.token(TokenKind.COMMENT)))
           .append("; font-style: italic; }");
        css.append(".num { color: ").append(hex(EditorTheme.token(TokenKind.NUMBER))).append("; }");
        css.append(".pre { color: ").append(hex(EditorTheme.token(TokenKind.PREPROCESSOR)))
           .append("; }");
        return css.toString();
    }

    private static double luminance(Color c) {
        return (0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue()) / 255.0;
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
