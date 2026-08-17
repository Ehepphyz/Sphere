package com.sphere.components.qeditorincludes;

/**
 * Enhanced Markdown-to-HTML renderer optimized for modern WebKit (WebView) and Swing components.
 * Uses clean HTML elements and CSS structural constraints for accurate side-by-side rendering.
 */
public class MarkdownRenderer {

    /**
     * Converts raw Markdown text into a stylized HTML string.
     * Aligns component geometry by grounding body offsets strictly to 0px.
     * @param text The raw Markdown content to process.
     * @return A formatted HTML string optimized for layout viewports.
     */
    public static String render(String text) {
        if (text == null) return "";
        
        StringBuilder html = new StringBuilder();
        
        // CSS3 Modern Dark Theme
        html.append("<!DOCTYPE html><html><head><style>");
        // BALANCING TRICK: We add a light 4px top margin to the body to drop the preview content 
        // by exactly one default JTextArea text row line height without freezing the layout tree.
        html.append("body { background-color: #1e1e1e; color: #d4d4d4; font-family: 'Segoe UI', sans-serif; margin: 0px 0px 0px 0px; padding: 0px; }");
        
        // RESTORED TITLES: Re-assigned prominent default font sizes while securing layout bounds
        html.append("h1 { color: #569cd6; border-bottom: 1px solid #333; padding-bottom: 5px; margin-top: 5px; margin-bottom: 5px; font-size: 2em; }");
        html.append("h2 { color: #4fc1ff; margin-top: 5px; margin-bottom: 5px; font-size: 1.5em; }");
        
        html.append("p { margin: 0px; padding: 0px; }");
        html.append("code { background-color: #2d2d2d; padding: 2px 5px; border-radius: 3px; color: #ce9178; }");
        html.append("pre { background-color: #121212; padding: 10px; border-radius: 4px; border: 1px solid #333; overflow-x: auto; margin: 5px 0px; }");
        html.append(".kwd { color: #569cd6; font-weight: bold; }");
        html.append(".typ { color: #4fc1ff; }");
        html.append(".str { color: #ce9178; }");
        html.append("</style></head><body>");

        // Logic processing
        String[] lines = text.split("\\R");
        boolean inCodeBlock = false;
        String currentLang = "";
        StringBuilder codeContent = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                if (!inCodeBlock) {
                    inCodeBlock = true;
                    currentLang = trimmed.substring(3).trim().toLowerCase();
                    codeContent = new StringBuilder();
                } else {
                    inCodeBlock = false;
                    html.append("<pre><code>").append(highlightSyntax(codeContent.toString(), currentLang)).append("</code></pre>");
                }
                continue;
            }

            if (inCodeBlock) {
                codeContent.append(line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append("<br>");
            } else {
                if (trimmed.startsWith("# ")) {
                    html.append("<h1>").append(trimmed.substring(2)).append("</h1>");
                } else if (trimmed.startsWith("## ")) {
                    html.append("<h2>").append(trimmed.substring(3)).append("</h2>");
                } else if (!trimmed.isEmpty()) {
                    html.append("<p>").append(trimmed).append("</p>");
                } else {
                    // Retain empty vertical tracking elements to preserve parallel layout line spacing
                    html.append("<p>&nbsp;</p>");
                }
            }
        }
        
        html.append("</body></html>");
        return html.toString();
    }

    /**
     * Performs lightweight regular expression keyword substitution for syntax highlighting.
     * @param code The target raw string segment.
     * @param language The source language identification key.
     * @return An HTML string wrapped in style tag class tokens.
     */
    private static String highlightSyntax(String code, String language) {
        if ("java".equals(language)) {
            return code.replaceAll("\\b(public|private|class|void|static|new|return)\\b", "<span class='kwd'>$1</span>")
                       .replaceAll("\\b(String|int|boolean)\\b", "<span class='typ'>$1</span>")
                       .replaceAll("(\".*?\")", "<span class='str'>$1</span>");
        }
        return code;
    }
}