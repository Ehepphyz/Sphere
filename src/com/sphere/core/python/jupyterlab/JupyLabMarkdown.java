package com.sphere.core.python.jupyterlab;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sphere.utils.AppLogger;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;

/**
 * JupyLabMarkdown
 *
 * Streaming Markdown parser with inline math support and native HTML block pass-through.
 * - Processes math symbols inside explicitly declared $...$ blocks.
 * - Automatically hooks and formats free-standing equations (e.g., β = p_J / E_J) without $ delimiters.
 * - Renders fractions using robust inline script typography (sup/sub) instead of tables,
 * preventing Swing's ancient layout engine from breaking text into multi-line steps.
 * - Automatically catches E/e scientific notation (e.g., 1e-13) and converts it into standard clean 10⁻¹³ typography.
 * - Supports native customized HTML header blocks (<h1-6>) with inline styles without wrapping them in paragraph tags.
 */
public class JupyLabMarkdown {

    public static String toHtml(String md) {
        String sanitizedMd = sanitizeTreeStructures(md);

        if (sanitizedMd == null || sanitizedMd.trim().isEmpty()) {
            return "<html><body style='font-family:Inter, sans-serif; font-size:14px; color:#DFE1E5; background-color:#1E1F22; line-height:1.5; margin:12px;'></body></html>";
        }
        
        StringBuilder out = new StringBuilder();
        out.append("<html><body style='font-family:Inter, sans-serif; font-size:14px; color:#DFE1E5; background-color:#1E1F22; line-height:1.5; margin:12px;'>");
        
        MarkdownStreamParser parser = new MarkdownStreamParser(new StringReader(sanitizedMd), out);
        try {
            parser.parse();
        } catch (IOException e) {
            // Runtime parsing exception handled safely
        }
        
        out.append("</body></html>");
        return out.toString();
    }


    public static class MarkdownStreamParser {

        private final Reader in;
        private final Appendable out;

        private boolean inUnorderedList = false;
        private boolean inOrderedList = false;
        private boolean inMathBlock = false;
        private final StringBuilder mathBlockBuffer = new StringBuilder();
        private boolean inCodeBlock = false;
        private final StringBuilder codeBlockBuffer = new StringBuilder();

        private static final Pattern HTML_HEADER_LINE_PATTERN = Pattern.compile("^<(h[1-6])[^>]*>.*?</\\1>$", Pattern.CASE_INSENSITIVE);

        public MarkdownStreamParser(Reader in, Appendable out) {
            this.in = in;
            this.out = out;
        }

        public void parse() throws IOException {
            StringBuilder lineBuf = new StringBuilder();
            int ch;
            while ((ch = in.read()) != -1) {
                if (ch == '\n') {
                    processLine(lineBuf.toString());
                    lineBuf.setLength(0);
                } else {
                    lineBuf.append((char) ch);
                }
            }
            if (lineBuf.length() > 0) {
                processLine(lineBuf.toString());
            }
            closeOpenStructuresAtEnd();
        }

        private void processLine(String line) throws IOException {
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    write("<pre style='background:#a0a2a6; padding:8px; border-radius:4px; font-family:Inter, sans-serif; font-size:13px;'><code>");
                    write(HtmlEscaper.escape(codeBlockBuffer.toString()));
                    write("</code></pre>");
                    codeBlockBuffer.setLength(0);
                    inCodeBlock = false;
                } else {
                    closeListsIfOpen();
                    inCodeBlock = true;
                }
                return;
            }

            if (inCodeBlock) {
                if (codeBlockBuffer.length() > 0) codeBlockBuffer.append('\n');
                codeBlockBuffer.append(line);
                return;
            }

            if (trimmed.startsWith("$$")) {
                if (inMathBlock) {
                    write("<div>");
                    write(MathProcessor.processMathSymbols(mathBlockBuffer.toString()));
                    write("</div>");
                    mathBlockBuffer.setLength(0);
                    inMathBlock = false;
                } else {
                    closeListsIfOpen();
                    inMathBlock = true;
                }
                return;
            }

            if (inMathBlock) {
                if (mathBlockBuffer.length() > 0) mathBlockBuffer.append(' ');
                mathBlockBuffer.append(trimmed);
                return;
            }

            if (trimmed.equals("---") || trimmed.equals("***") || trimmed.equals("___")) {
                closeListsIfOpen();
                write("<hr>");
                return;
            }

            if (line.startsWith("> ")) {
                closeListsIfOpen();
                write("<blockquote style='border-left:4px solid #4A90E2; padding-left:10px; margin:6px 0; color:#555;'>");
                write(InlineParser.processInlineStyles(line.substring(2)));
                write("</blockquote>");
                return;
            }

            if (line.startsWith("- ") || line.startsWith("* ")) {
                if (!inUnorderedList) {
                    closeOrderedListIfOpen();
                    write("<ul style='margin:4px 0; padding-left:20px;'>");
                    inUnorderedList = true;
                }
                write("<li style='margin:2px 0;'>");
                write(InlineParser.processInlineStyles(line.substring(2)));
                write("</li>");
                return;
            }

            if (isOrderedListItem(line)) {
                if (!inOrderedList) {
                    closeUnorderedListIfOpen();
                    write("<ol style='margin:4px 0; padding-left:20px;'>");
                    inOrderedList = true;
                }
                String content = line.replaceFirst("^\\s*\\d+\\.\\s*", "");
                write("<li style='margin:2px 0;'>");
                write(InlineParser.processInlineStyles(content));
                write("</li>");
                return;
            }

            closeListsIfOpen();

            if (HTML_HEADER_LINE_PATTERN.matcher(trimmed).matches()) {
                write(InlineParser.processInlineStyles(trimmed));
                return;
            }

            if (line.startsWith("#### ")) {
                write("<h4 style='font-size:14px; font-weight:600; margin:6px 0;'>");
                write(InlineParser.processInlineStyles(line.substring(5)));
                write("</h4>");
                return;
            }
            if (line.startsWith("### ")) {
                write("<h3 style='font-size:16px; font-weight:600; margin:8px 0 4px 0;'>");
                write(InlineParser.processInlineStyles(line.substring(4)));
                write("</h3>");
                return;
            }
            if (line.startsWith("## ")) {
                write("<h2 style='font-size:18px; font-weight:600; margin:10px 0 6px 0;'>");
                write(InlineParser.processInlineStyles(line.substring(3)));
                write("</h2>");
                return;
            }
            if (line.startsWith("# ")) {
                write("<h1 style='font-size:22px; font-weight:600; margin:12px 0 6px 0;'>");
                write(InlineParser.processInlineStyles(line.substring(2)));
                write("</h1>");
                return;
            }

            if (trimmed.matches("\\|.*\\|")) {
                String[] cols = trimmed.split("\\|");
                write("<table style='border-collapse:collapse; margin:6px 0;'>");
                write("<tr>");
                for (String c : cols) {
                    if (!c.trim().isEmpty()) {
                        write("<td style='border:1px solid #ccc; padding:4px 8px;'>" + InlineParser.processInlineStyles(c.trim()) + "</td>");
                    }
                }
                write("</tr>");
                write("</table>");
                return;
            }

            if (!trimmed.isEmpty()) {
                write("<p style='margin:6px 0;'>");
                write(InlineParser.processInlineStyles(line));
                write("</p>");
            }
        }

        private void closeListsIfOpen() throws IOException {
            closeUnorderedListIfOpen();
            closeOrderedListIfOpen();
        }

        private void closeUnorderedListIfOpen() throws IOException {
            if (inUnorderedList) {
                write("</ul>");
                inUnorderedList = false;
            }
        }

        private void closeOrderedListIfOpen() throws IOException {
            if (inOrderedList) {
                write("</ol>");
                inOrderedList = false;
            }
        }

        private void closeOpenStructuresAtEnd() throws IOException {
            closeListsIfOpen();
            if (inMathBlock) {
                write("<div>");
                write(MathProcessor.processMathSymbols(mathBlockBuffer.toString()));
                write("</div>");
                inMathBlock = false;
            }
            if (inCodeBlock) {
                write("<pre style='background:#f5f5f5; padding:8px; border-radius:4px; font-family:\"Noto Sans Mono\", monospace; font-size:13px;'><code>");
                write(HtmlEscaper.escape(codeBlockBuffer.toString()));
                write("</code></pre>");
                inCodeBlock = false;
            }
        }

        private boolean isOrderedListItem(String line) {
            return line.matches("^\\s*\\d+\\.\\s+.*");
        }

        private void write(String s) throws IOException {
            out.append(s);
        }
    }

    public static class InlineParser {

        private static final Pattern INLINE_MATH_PATTERN = Pattern.compile("\\$([^\\$]+)\\$");
        private static final Pattern CODE_PATTERN = Pattern.compile("`([^`]+)`");
        private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*([^\\*]+)\\*\\*");
        private static final Pattern ITALIC_PATTERN = Pattern.compile("\\*([^\\*]+)\\*");
        private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\(([^\\)]+)\\)");
        private static final Pattern CUSTOM_HEADER_PATTERN = Pattern.compile("<(h[1-6])[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE);

        public static String processInlineStyles(String text) {
            if (text == null) return "";

            Matcher hMatcher = CUSTOM_HEADER_PATTERN.matcher(text);
            StringBuffer sbHeaders = new StringBuffer();
            List<String> headerHtmlList = new ArrayList<>();
            int hIndex = 0;
            while (hMatcher.find()) {
                String token = "HTMLHDRX" + hIndex;
                headerHtmlList.add(hMatcher.group());
                hMatcher.appendReplacement(sbHeaders, Matcher.quoteReplacement(token));
                hIndex++;
            }
            hMatcher.appendTail(sbHeaders);
            String trackingText = sbHeaders.toString();

            Matcher m = INLINE_MATH_PATTERN.matcher(trackingText);
            StringBuffer sbMath = new StringBuffer();
            List<String> mathHtmlList = new ArrayList<>();
            int placeholderIndex = 0;
            while (m.find()) {
                String rawMath = m.group(1);
                String mathHtml = MathProcessor.processMathSymbols(rawMath);

                String token = "MATHHDRX" + placeholderIndex;
                mathHtmlList.add(mathHtml);
                m.appendReplacement(sbMath, Matcher.quoteReplacement(token));
                placeholderIndex++;
            }
            m.appendTail(sbMath);
            String processedText = sbMath.toString();

            processedText = formatScientificNotation(processedText);
            processedText = formatFreeSubscripts(processedText);
            processedText = MathProcessor.processMathSymbols(processedText);

            processedText = RegexUtil.replaceWithRegex(processedText, CODE_PATTERN,
                    "<code>$1</code>");
            processedText = RegexUtil.replaceWithRegex(processedText, BOLD_PATTERN, "<b>$1</b>");
            processedText = RegexUtil.replaceWithRegex(processedText, ITALIC_PATTERN, "<i>$1</i>");
            processedText = RegexUtil.replaceWithRegex(processedText, LINK_PATTERN,
                    "<a href='$2'>$1</a>");

            processedText = processedText.replaceAll("!\\[([^\\]]*)\\]\\(([^\\)]+)\\)", "<img src='$2' alt='$1' style='max-width:100%; margin:6px 0;'/>");

            for (int i = 0; i < mathHtmlList.size(); i++) {
                String token = "MATHHDRX" + i;
                String mathSpan = mathHtmlList.get(i);
                processedText = processedText.replace(token, mathSpan);
            }

            for (int i = 0; i < headerHtmlList.size(); i++) {
                String token = "HTMLHDRX" + i;
                processedText = processedText.replace(token, headerHtmlList.get(i));
            }

            return processedText;
        }
    }

    public static class MathProcessor {

        private static final Pattern RM_PATTERN_BEFORE = Pattern.compile("\\{(?:\\\\rm|\\\\mathrm)\\s+([^\\}]+)\\}");
        private static final Pattern SQRT_PATTERN = Pattern.compile("\\\\sqrt\\{([^\\}]+)\\}");
        private static final Pattern FRAC_PATTERN = Pattern.compile("\\\\frac\\{([^\\}]+)\\}\\{([^\\}]+)\\}");
        private static final Pattern SUP_BRACED_PATTERN = Pattern.compile("\\^\\{([^\\}]+)\\}");
        private static final Pattern SUP_SIMPLE_PATTERN = Pattern.compile("\\^([a-zA-Z0-9]+)");
        private static final Pattern SUB_BRACED_PATTERN = Pattern.compile("_\\{([^\\}]+)\\}");
        private static final Pattern SUB_SIMPLE_PATTERN = Pattern.compile("_([a-zA-Z0-9]+)");
        private static final Pattern SCIENTIFIC_NOTATION_PATTERN = Pattern.compile("([0-9.]+)[eE]([+-]?[0-9]+)");

        public static String processMathSymbols(String math) {
            if (math == null) return "";
            String result = math.trim();

            result = result.replace("\\\\", "<br>").replace("~", "&nbsp;");
            result = result.replace("\\to", "→").replace("\\Rightarrow", "⇒").replace("\\Leftarrow", "⇐");
            result = result.replaceAll("\\\\text\\s*\\{([^}]+)\\}", "$1");
            result = applyRomanBefore(result);
            result = result.replace("\\rm ", "").replace("\\mathrm ", "");
            result = applySqrt(result);

            result = SUB_BRACED_PATTERN.matcher(result).replaceAll("<sub>$1</sub>");
            result = SUP_BRACED_PATTERN.matcher(result).replaceAll("<sup>$1</sup>");

            Matcher fracMatcher = FRAC_PATTERN.matcher(result);
            StringBuffer sbFrac = new StringBuffer();
            while (fracMatcher.find()) {
                String num = formatSubSupInline(fracMatcher.group(1));
                String den = formatSubSupInline(fracMatcher.group(2));
                fracMatcher.appendReplacement(sbFrac, Matcher.quoteReplacement(num + "/" + den));
            }
            fracMatcher.appendTail(sbFrac);
            result = sbFrac.toString();

            if (result.contains("/")) {
                Pattern targetFracPattern = Pattern.compile("([a-zA-Z0-9_\\^\\(\\)\\|&;√\\s-]+)/([a-zA-Z0-9_\\^\\(\\)\\|&;√\\s-]+)");
                Matcher m = targetFracPattern.matcher(result);
                StringBuffer sbAutoFrac = new StringBuffer();
                while (m.find()) {
                    String num = formatSubSupInline(m.group(1).trim());
                    String den = formatSubSupInline(m.group(2).trim());
                    String inlineFrac = num + "/" + den;
                    m.appendReplacement(sbAutoFrac, Matcher.quoteReplacement(inlineFrac));
                }
                m.appendTail(sbAutoFrac);
                result = sbAutoFrac.toString();
            }

            Matcher sciMatcher = SCIENTIFIC_NOTATION_PATTERN.matcher(result);
            StringBuffer sbSci = new StringBuffer();
            while (sciMatcher.find()) {
                String coefficient = sciMatcher.group(1);
                String exponent = sciMatcher.group(2);
                if (exponent.startsWith("+")) {
                    exponent = exponent.substring(1);
                }

                String formattedSci;
                double coeffVal = 0;
                try {
                    coeffVal = Double.parseDouble(coefficient);
                } catch (NumberFormatException e) {
                }

                if (coeffVal == 1.0) {
                    formattedSci = "10<sup>" + exponent + "</sup>";
                } else {
                    String cleanCoeff = (coeffVal == (long) coeffVal) ? String.valueOf((long) coeffVal) : String.valueOf(coeffVal);
                    formattedSci = cleanCoeff + "×10<sup>" + exponent + "</sup>";
                }
                sciMatcher.appendReplacement(sbSci, Matcher.quoteReplacement(formattedSci));
            }
            sciMatcher.appendTail(sbSci);
            result = sbSci.toString();

            result = formatSubSupInline(result);

            result = result.replaceAll("([a-zA-Z0-9]+)\\+ ", "<sub>$1</sub><sup>+</sup> ")
                        .replaceAll("([a-zA-Z0-9]+)\\- ", "<sub>$1</sub><sup>-</sup> ")
                        .replaceAll("([a-zA-Z0-9]+)\\+$", "<sub>$1</sub><sup>+</sup>")
                        .replaceAll("([a-zA-Z0-9]+)\\-$", "<sub>$1</sub><sup>-</sup>");

            result = result.replace("{", "").replace("}", "");

            result = result.replace("\\alpha", "α")
                        .replace("\\beta", "β")
                        .replace("\\gamma", "γ")
                        .replace("\\delta", "δ")
                        .replace("\\epsilon", "ε")
                        .replace("\\theta", "θ")
                        .replace("\\lambda", "λ")
                        .replace("\\mu", "μ")
                        .replace("\\pi", "π")
                        .replace("\\rho", "ρ")
                        .replace("\\sigma", "σ")
                        .replace("\\eta", "η")
                        .replace("\\tau", "τ")
                        .replace("\\chi", "χ")
                        .replace("\\phi", "φ")
                        .replace("\\psi", "ψ")
                        .replace("\\omega", "ω")
                        .replace("\\Delta", "Δ")
                        .replace("\\Omega", "Ω")
                        .replace("\\Phi", "Φ")
                        .replace("\\Psi", "Ψ")
                        .replace("\\Sigma", "∑");

            result = result.replaceAll("\\balpha\\b", "α")
                        .replaceAll("\\bbeta\\b", "β")
                        .replaceAll("\\bgamma\\b", "γ")
                        .replaceAll("\\bdelta\\b", "δ")
                        .replaceAll("\\bepsilon\\b", "ε")
                        .replaceAll("\\btheta\\b", "θ")
                        .replaceAll("\\blambda\\b", "λ")
                        .replaceAll("\\bmu\\b", "μ")
                        .replaceAll("\\bpi\\b", "π")
                        .replaceAll("\\brho\\b", "ρ")
                        .replaceAll("\\bsigma\\b", "σ")
                        .replaceAll("\\beta\\b", "η")
                        .replaceAll("\\btau\\b", "τ")
                        .replaceAll("\\bchi\\b", "χ")
                        .replaceAll("\\bphi\\b", "φ")
                        .replaceAll("\\bpsi\\b", "ψ")
                        .replaceAll("\\bomega\\b", "ω");

            result = result.replace("\\sum", "∑")
                        .replace("\\prod", "∏")
                        .replace("\\int", "∫")
                        .replace("\\infty", "∞")
                        .replace("\\times", "×")
                        .replace("\\div", "÷")
                        .replace("\\pm", "±")
                        .replace("\\mp", "∓")
                        .replace("\\neq", "≠")
                        .replace("\\leq", "≤")
                        .replace("\\geq", "≥")
                        .replace("\\approx", "≈")
                        .replace("\\partial", "∂");

            result = result.replace("$", "");

            if (result.contains("*")) {
                String[] lines = result.split("\n");
                StringBuilder sbList = new StringBuilder();
                int currentDepth = 0;

                for (String line : lines) {
                    String trimmed = line.trim();

                    if (trimmed.startsWith("*")) {
                        int spaces = line.indexOf("*");
                        int depth = (spaces > 0) ? (spaces / 2 + 1) : 1;
                        String content = trimmed.substring(1).trim();

                        while (currentDepth < depth) {
                            sbList.append("<ul style='margin-top:1px; margin-bottom:1px; padding-left:18px;'>");
                            currentDepth++;
                        }
                        while (currentDepth > depth) {
                            sbList.append("</ul>");
                            currentDepth--;
                        }
                        sbList.append("<li style='margin-top:1px; margin-bottom:1px; padding:0px;'>").append(content).append("</li>");
                    } else {
                        while (currentDepth > 0) {
                            sbList.append("</ul>");
                            currentDepth--;
                        }
                        if (!line.isEmpty()) {
                            sbList.append(line).append("<br>");
                        }
                    }
                }
                while (currentDepth > 0) {
                    sbList.append("</ul>");
                    currentDepth--;
                }
                result = sbList.toString().trim();

                if (result.endsWith("<br>")) {
                    result = result.substring(0, result.length() - 4);
                }
            } else {
                result = result.replace("\n", "<br>");
            }

            if (!result.toLowerCase().startsWith("<html>")) {
                result = "<html>" + result;
            }

            return result;
        }

        private static String formatSubSupInline(String input) {
            String res = RegexUtil.replaceWithRegex(input, SUP_SIMPLE_PATTERN,
                    "<sup>$1</sup>");
            return RegexUtil.replaceWithRegex(res, SUB_SIMPLE_PATTERN,
                    "<sub>$1</sub>");
        }

        private static String applyRomanBefore(String input) {
            Matcher m = RM_PATTERN_BEFORE.matcher(input);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String textContent = m.group(1);
                String html = textContent;
                m.appendReplacement(sb, Matcher.quoteReplacement(html));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        private static String applySqrt(String input) {
            Matcher m = SQRT_PATTERN.matcher(input);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String content = formatSubSupInline(m.group(1));
                String html = "&radic;(" + content + ")";
                m.appendReplacement(sb, Matcher.quoteReplacement(html));
            }
            m.appendTail(sb);
            return sb.toString();
        }
    }

    public static class HtmlEscaper {
        public static String escape(String s) {
            if (s == null) return "";
            StringBuilder sb = new StringBuilder(s.length() + 16);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '&': sb.append("&amp;"); break;
                    case '<': sb.append("&lt;"); break;
                    case '>': sb.append("&gt;"); break;
                    case '"': sb.append("&quot;"); break;
                    case '\'': sb.append("&#39;"); break;
                    default: sb.append(c);
                }
            }
            return sb.toString();
        }
    }

    public static class RegexUtil {
        public static String replaceWithRegex(String source, Pattern pattern, String replacement) {
            try {
                Matcher matcher = pattern.matcher(source);
                return matcher.replaceAll(replacement);
            } catch (Exception e) {
                return source;
            }
        }
    }

    private static String formatScientificNotation(String text) {
        if (text == null) return "";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("([0-9.]+)[eE]([+-]?[0-9]+)");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String coefficient = matcher.group(1);
            String exponent = matcher.group(2);

            if (exponent.startsWith("+")) {
                exponent = exponent.substring(1);
            }

            double coeffVal = 0;
            try {
                coeffVal = Double.parseDouble(coefficient);
            } catch (NumberFormatException e) {
            }

            String formattedSci;
            if (coeffVal == 1.0) {
                formattedSci = "10<sup>" + exponent + "</sup>";
            } else {
                String cleanCoeff = (coeffVal == (long) coeffVal) ? String.valueOf((long) coeffVal) : String.valueOf(coeffVal);
                formattedSci = cleanCoeff + "&times;10<sup>" + exponent + "</sup>";
            }
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(formattedSci));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String sanitizeTreeStructures(String input) {
        if (input == null) return "";
        return input.replace("├──", "|-- ")
                    .replace("└──", "`-- ")
                    .replace("│",   "|")
                    .replace("─",   "-");
    }

    private static String formatFreeSubscripts(String text) {
        if (text == null) return "";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("([a-zA-Z0-9])_([a-zA-Z0-9]+)");
        return pattern.matcher(text).replaceAll("$1<sub>$2</sub>");
    }
}
