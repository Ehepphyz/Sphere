package com.sphere.components.spdf;

import com.sphere.components.spdf.edit.ImageOps;
import com.sphere.components.spdf.render.Op;
import com.sphere.components.spdf.render.PageImage;
import com.sphere.components.spdf.text.Insights;
import com.sphere.components.spdf.text.PageText;
import com.sphere.components.spdf.text.SmartSearch;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;

/**
 * Commands said in words, understood with some sense. Typos are forgiven
 * ("rotat page 3 lefft"), synonyms known ("remove", "erase", "turn"), pages
 * named the ways people name them ("pages 2, 5 and 7-9", "odd pages", "the
 * last three pages", "this page", "page two"), phrases chained ("rotate all
 * pages right then save"), and "it" means what is selected. Besides pages,
 * text and the view, the phrases reach the pictures ("edit the picture",
 * "compress pictures to 150 ppi"), the structure ("go to figure 3", "go to
 * section results", "show reference 12") and the contents ("word count",
 * "count energy", "highlight all dates", "delete blank pages"). What a phrase
 * will do is shown before it runs.
 */
final class SpdfCommands {

    record Action(String description, Runnable run) {
    }

    private final SpdfPanel p;

    SpdfCommands(SpdfPanel p) {
        this.p = p;
    }

    /** Example phrases, offered as the user types. */
    static final String[] EXAMPLES = {
        "go to page 10", "next page", "previous page", "first page", "last page", "go back",
        "go to figure 1", "go to table 1", "go to section introduction", "show reference 1",
        "find energy", "count energy", "replace 2023 with 2024", "highlight plasma", "highlight all dates",
        "highlight all emails", "highlight all links", "word count",
        "redact emails", "redact phone numbers", "redact IBANs", "redact links",
        "delete page 3", "delete pages 2-4", "delete blank pages", "rotate page 1 right", "rotate all pages left",
        "rotate odd pages left", "duplicate page 2", "insert blank page after 5", "move page 4 to 1",
        "extract pages 1-3", "export pages as png", "merge a PDF",
        "edit the picture", "replace the picture", "save the picture", "insert a picture", "paste a picture",
        "rotate the picture right", "make the picture gray", "enhance the picture", "make the picture's white transparent",
        "delete the picture", "picture properties", "extract all pictures", "compress pictures to 150 ppi",
        "how many pictures", "summarize page 1", "summary", "key terms", "outline", "history", "thumbnails",
        "zoom 150", "fit width", "fit page", "night mode",
        "add text", "edit text", "highlighter", "draw", "rectangle", "ellipse", "arrow", "line", "note",
        "redaction tool", "export text", "export markdown", "print", "save", "save as", "compress",
        "properties", "undo", "undo 3 times", "redo", "open", "new document" };

    /** The words commands are made of: a word typed close to one of them is read as it. */
    private static final Set<String> VOCABULARY = new LinkedHashSet<>(Arrays.asList(
        "go", "goto", "jump", "next", "previous", "first", "last", "page", "pages", "back", "replace", "with",
        "find", "search", "look", "locate", "highlight", "redact", "anonymize", "delete", "remove", "erase", "rotate",
        "turn", "left", "right", "clockwise", "counterclockwise", "duplicate", "copy", "insert", "blank", "empty",
        "after", "before", "move", "extract", "export", "merge", "append", "combine", "zoom", "fit", "width", "night",
        "dark", "mode", "summary", "summarize", "terms", "outline", "history", "thumbnails", "text", "edit", "draw",
        "rectangle", "ellipse", "arrow", "line", "note", "select", "markdown", "print", "save", "compress", "properties",
        "undo", "redo", "open", "new", "document", "picture", "pictures", "image", "images", "photo", "photos", "figure",
        "table", "section", "chapter", "reference", "count", "words", "reading", "odd", "even", "all", "every", "this",
        "current", "gray", "grayscale", "enhance", "transparent", "shrink", "paste", "flip", "horizontally",
        "vertically", "statistics", "times", "twice", "white", "background", "shapes",
        // common words, known so that none is taken for a typo of another
        "word", "make", "show", "file", "from", "into", "info", "time", "long", "much", "many", "what", "where",
        "which", "that", "these", "those", "there", "about", "mirror", "stats", "length", "sign", "write", "light", "day",
        "height", "whole", "full", "half", "start", "end", "top", "bottom", "front", "cover", "final", "each", "both",
        "only", "also", "please", "could", "would", "your", "graphic", "graphics", "logo", "scan", "pdf", "add",
        "clean", "fix", "improve", "brighten", "color", "colors", "black", "paper", "describe", "inspect", "details",
        "heading", "ppi", "dpi", "png", "jpeg", "jpg", "plain", "spin", "upside", "down", "clone", "position", "swap",
        "retouch", "modify", "crop", "adjust", "bigger", "smaller", "enlarge", "larger", "contents", "bookmarks",
        "headings", "changes", "tool", "selection", "hand", "pan", "notes", "comment", "circle", "oval", "square",
        "frame", "box", "pen", "ink", "freehand", "sketch", "signature", "optimize", "reduce", "downsample", "emails",
        "links", "dates", "amounts", "quantities", "numbers", "phone", "ibans", "money", "prices", "units", "photograph",
        "photographs", "logos", "onto", "until", "through", "thru", "last", "page", "then"));

    /** After these, the words are the user's own (a search, a replacement, a heading): left as typed. */
    private static final Set<String> ARGUMENT = Set.of("find", "search", "look", "locate", "highlight", "replace",
        "redact", "anonymize", "count", "section", "chapter", "heading", "reference", "ref", "for");

    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
        Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5),
        Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10),
        Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("thirteen", 13), Map.entry("fourteen", 14),
        Map.entry("fifteen", 15), Map.entry("sixteen", 16), Map.entry("seventeen", 17), Map.entry("eighteen", 18),
        Map.entry("nineteen", 19), Map.entry("twenty", 20), Map.entry("second", 2), Map.entry("third", 3),
        Map.entry("fourth", 4), Map.entry("fifth", 5), Map.entry("sixth", 6), Map.entry("seventh", 7),
        Map.entry("eighth", 8), Map.entry("ninth", 9), Map.entry("tenth", 10));

    static String norm(String s) {
        return SmartSearch.fold(s.trim()).text().replaceAll("\\s+", " ");
    }

    private static String unquote(String s) {
        return s.trim().replaceAll("^[\"'“‘]|[\"'”’]$", "");
    }

    /* ================================================================= reading a phrase */

    /** What a phrase (or a chain of them) will do, or null when it is not understood. */
    Action interpret(String raw) {
        String[] clauses = raw.trim().split("\\s*(?:;|,?\\s+and then\\s+|,?\\s+then\\s+)\\s*");
        if (clauses.length > 1) {
            List<Action> steps = new ArrayList<>();
            for (String c : clauses) {
                if (c.isBlank()) {
                    continue;
                }
                Action a = one(c);
                if (a == null) {
                    return null;
                }
                steps.add(a);
            }
            StringBuilder d = new StringBuilder();
            for (Action a : steps) {
                d.append(d.length() == 0 ? "" : ", then ").append(a.description());
            }
            return new Action(d.toString(), () -> {
                for (Action a : steps) {
                    a.run().run();
                }
            });
        }
        return one(raw);
    }

    /**
     * The phrase with its command words set right: typos corrected, number
     * words made digits. The user's own words (what follows "find",
     * "replace", "section"...) are left as typed.
     */
    static String corrected(String raw) {
        String[] tokens = raw.trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        boolean argument = false;
        for (String t : tokens) {
            String w = t;
            if (!argument && !t.startsWith("\"") && !t.startsWith("'")) {
                String lw = norm(t).toLowerCase(Locale.ROOT);
                Integer n = NUMBER_WORDS.get(lw);
                if (n != null && !lw.equals("second")) {
                    w = String.valueOf(n);
                } else if (lw.length() >= 4 && !lw.matches(".*\\d.*") && !VOCABULARY.contains(lw)) {
                    String fix = closest(lw);
                    if (fix != null) {
                        w = fix;
                    }
                }
                if (ARGUMENT.contains(norm(w).toLowerCase(Locale.ROOT))) {
                    argument = true;
                }
            }
            out.append(out.length() == 0 ? "" : " ").append(w);
        }
        return out.toString();
    }

    /** The vocabulary word a typo means, when one alone is that close. */
    private static String closest(String w) {
        int max = w.length() >= 7 ? 2 : 1;
        String best = null;
        int bestD = max + 1;
        boolean tie = false;
        for (String v : VOCABULARY) {
            if (Math.abs(v.length() - w.length()) > max) {
                continue;
            }
            int d = distance(w, v);
            if (d < bestD) {
                bestD = d;
                best = v;
                tie = false;
            } else if (d == bestD) {
                tie = true;
            }
        }
        return bestD <= max && !tie ? best : null;
    }

    /** Edit distance with transpositions (optimal string alignment). */
    static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    private static final String PICTURE = "(?:pictures?|images?|photos?|photographs?|graphics?|logos?)";
    private static final String PICTURES = "(?:pictures|images|photos|photographs|graphics|logos)";

    /** A page number named but not in the document (0 when none), noted while reading a phrase. */
    private int outOfRange;

    private Action one(String raw0) {
        outOfRange = 0;
        Action a = rules(raw0);
        if (a == null && outOfRange > 0) {
            return noSuchPage(outOfRange);
        }
        return a;
    }

    private Action rules(String raw0) {
        String r = corrected(raw0);
        String s = norm(r).toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        int pages = p.pageCount();
        int cur = p.view.currentPage();
        Matcher m;
        boolean pictureSelected = p.view.selImage != null;

        // ---------------------------------------------------------------- undo, redo
        if ((m = find(s, "^undo(?:\\s+(?:(\\d+)\\s+times|(twice)|(\\d+)))?$")) != null) {
            int n = m.group(1) != null ? Integer.parseInt(m.group(1)) : m.group(2) != null ? 2
                : m.group(3) != null ? Integer.parseInt(m.group(3)) : 1;
            int k = Math.max(1, Math.min(100, n));
            return new Action(k == 1 ? "Undo" : "Undo the last " + k + " changes", () -> {
                for (int i = 0; i < k; i++) {
                    p.undo();
                }
            });
        }
        if ((m = find(s, "^redo(?:\\s+(?:(\\d+)\\s+times|(twice)))?$")) != null) {
            int k = m.group(1) != null ? Math.min(100, Integer.parseInt(m.group(1))) : m.group(2) != null ? 2 : 1;
            return new Action(k == 1 ? "Redo" : "Redo " + k + " changes", () -> {
                for (int i = 0; i < k; i++) {
                    p.redo();
                }
            });
        }

        // ---------------------------------------------------------------- pictures
        Action pic = pictures(s, r);
        if (pic != null) {
            return pic;
        }

        // ---------------------------------------------------------------- statistics, counts, summaries
        if (s.matches("(word count|count words|count the words|how many words.*|statistics|stats|reading time|"
                      + "how long is (this|it|the document).*|length)")) {
            return new Action("Count the words and the reading time", this::statistics);
        }
        if (s.matches("(how many pages.*|page count|count (the )?pages)")) {
            return new Action("Count the pages", () -> p.status(pages + (pages == 1 ? " page" : " pages")));
        }
        if ((m = find(r, "(?i)^(?:count|how many times (?:does |is )?|how many|how often (?:does |is )?)\\s*(?:the\\s+)?(?:word\\s+)?"
                         + "(.+?)(?:\\s+(?:appear|appears|occur|occurs|come up|comes up|are there|is there|used))?\\s*\\??$"))
            != null) {
            String q = unquote(m.group(1));
            return new Action("Count the occurrences of \"" + q + "\"", () -> p.search(q, () -> {
                Set<Integer> on = new TreeSet<>();
                for (SmartSearch.Hit h : p.view.hits) {
                    on.add(h.page());
                }
                int n = p.view.hits.size();
                p.status("\"" + q + "\": " + (n == 0 ? "not found" : n + (n == 1 ? " time" : " times") + " on " + on.size()
                                                                         + (on.size() == 1 ? " page" : " pages")));
            }));
        }
        if ((m = find(s, "^(?:summarize|summary of|sum up)\\s+(?:the\\s+)?(.+)$")) != null) {
            List<Integer> rg = pageList(m.group(1));
            if (rg != null) {
                return new Action("Summarize " + describe(rg), () -> summarize(rg));
            }
        }

        // ---------------------------------------------------------------- navigation
        if ((m = find(s, "^(?:go to|goto|go|jump to|jump|take me to|navigate to|scroll to|show me|open)\\s+(?:the\\s+)?(.+)$"))
            != null) {
            Action a = goTo(m.group(1), r);
            if (a != null) {
                return a;
            }
        }
        if (find(s, "^(?:(?:page|p\\.?)\\s*[a-z0-9.-]+|\\(\\d+\\)|(?:file|physical|pdf) page \\d+)$") != null) {
            Action a = pageAction(s);
            if (a != null) {
                return a;
            }
        }
        if ((m = find(s, "^(?:fig(?:ure)?\\.?|table|tab\\.?)\\s*(\\d+)$")) != null) {
            return goTo(s, r);
        }
        if (s.matches("(next|next page|page down)")) {
            return new Action("Next page", () -> p.goToPage(cur + 1));
        }
        if (s.matches("(previous|prev|previous page|prev page|page up)")) {
            return new Action("Previous page", () -> p.goToPage(cur - 1));
        }
        if (s.matches("(first page|first|start|beginning|top)")) {
            return new Action("First page", () -> p.goToPage(0));
        }
        if (s.matches("(last page|last|end|bottom)")) {
            return new Action("Last page", () -> p.goToPage(pages - 1));
        }
        if (s.matches("(back|go back|return|where was i)")) {
            return new Action("Back to where you were", p::goBack);
        }
        if ((m = find(s, "^(?:show|open|what is)\\s+(?:the\\s+)?(?:ref(?:erence)?\\.?\\s*|citation\\s*)\\[?([a-z]*\\d+[a-z]?)\\]?$"))
            != null) {
            return reference(m.group(1));
        }

        // ---------------------------------------------------------------- replace, search, highlight, redact
        if ((m = find(r, "(?i)^replace\\s+(?:all\\s+)?(.+?)\\s+(?:with|by)\\s+(.*)$")) != null) {
            String a = unquote(m.group(1));
            String b = unquote(m.group(2));
            return new Action("Replace \"" + a + "\" with \"" + b + "\" throughout the document", () -> p.replaceAll(a, b));
        }
        if ((m = find(r, "(?i)^(?:find|search for|search|look for|locate|where is|where are)\\s+(.+?)\\??$")) != null) {
            String q = unquote(m.group(1));
            return new Action("Search for \"" + q + "\"", () -> p.search(q));
        }
        if ((m = find(s, "^highlight\\s+(?:all\\s+(?:of\\s+)?)?(?:the\\s+)?(dates|e-?mails|mail addresses|links|urls|web addresses|"
                         + "amounts|money|prices|quantities|physical quantities|units|dois|arxiv(?: ids)?)$")) != null) {
            return highlightEntities(m.group(1));
        }
        if (s.matches("highlight (it|this|that|the selection|selection|selected text)") && p.view.selStart != p.view.selEnd) {
            return new Action("Highlight the selected text", () -> p.view.markupSelection("Highlight", p.highlightColor));
        }
        if ((m = find(r, "(?i)^highlight\\s+(?:all\\s+(?:of\\s+)?)?(?:the\\s+)?(?:word\\s+)?(.+)$")) != null) {
            String q = unquote(m.group(1));
            return new Action("Highlight every occurrence of \"" + q + "\"", () -> p.search(q, () -> {
                if (!p.view.hits.isEmpty()) {
                    p.highlightAllHits();
                }
            }));
        }
        if (s.matches("(redact|black out|blackout) (it|this|that|the selection|selection|selected text)")
            && p.view.selStart != p.view.selEnd) {
            return new Action("Redact the selected text (removed from the file)", p.view::redactSelection);
        }
        if ((m = find(s, "^(?:redact|anonymize|black out|blackout)\\s+(?:all\\s+)?(?:the\\s+)?(.+)$")) != null) {
            String what = m.group(1);
            String key = what.startsWith("e-mail") || what.startsWith("email") || what.startsWith("mail") ? "emails"
                : what.startsWith("phone") || what.startsWith("tel") ? "phone numbers"
                : what.startsWith("iban") ? "IBANs"
                : what.startsWith("number") ? "numbers"
                : what.startsWith("link") || what.startsWith("url") ? "links" : null;
            if (key != null) {
                String k = key;
                return new Action("Redact all " + key + " in the document (removed from the file)", () -> p.redactPattern(k));
            }
            String q = unquote(r.replaceFirst("(?i)^\\S+(\\s+out)?\\s+(?:all\\s+)?(?:the\\s+)?", ""));
            return new Action("Redact every occurrence of \"" + q + "\"", () -> p.redactText(q));
        }

        // ---------------------------------------------------------------- pages
        if (s.matches("(delete|remove|erase|drop) (all )?(the )?(blank|empty|white) pages?")) {
            return new Action("Delete the blank pages (you confirm the list first)", this::deleteBlankPages);
        }
        if ((m = find(s, "^(?:delete|remove|erase|drop|discard)\\s+(?:the\\s+)?(.+)$")) != null) {
            List<Integer> rg = pageList(m.group(1));
            if (rg == null && m.group(1).matches("(it|this|that)") ) {
                rg = List.of(cur);
            }
            if (rg != null) {
                if (rg.size() >= pages) {
                    return new Action("A PDF keeps at least one page: not all " + pages + " can go",
                                      () -> p.status("A PDF keeps at least one page"));
                }
                List<Integer> rr = rg;
                return new Action("Delete " + describe(rg), () -> p.deletePages(rr));
            }
        }
        if ((m = find(s, "^(?:rotate|turn|spin)\\s+(?:the\\s+)?(.*?)\\s*(?:(?:to the\\s+|by\\s+)?(left|right|180|180 degrees|"
                         + "clockwise|counterclockwise|counter-clockwise|upside down|90|90 degrees|-90))?$")) != null) {
            String what = m.group(1).trim();
            List<Integer> rg = what.isEmpty() ? List.of(cur) : pageList(what.replaceFirst("\\s*(by|to the)$", ""));
            if (rg != null) {
                String dir = m.group(2) == null ? "right" : m.group(2);
                int deg = dir.equals("left") || dir.startsWith("counter") || dir.equals("-90") ? -90
                    : dir.startsWith("180") || dir.equals("upside down") ? 180 : 90;
                List<Integer> rr = rg;
                return new Action("Rotate " + describe(rg) + (deg == 180 ? " by 180°" : deg < 0 ? " left" : " right"),
                                  () -> p.rotatePages(rr, deg));
            }
        }
        if ((m = find(s, "^(?:duplicate|copy|clone)\\s+(?:the\\s+)?(.+)$")) != null) {
            List<Integer> rg = pageList(m.group(1));
            if (rg != null) {
                List<Integer> rr = rg;
                return new Action("Duplicate " + describe(rg), () -> p.duplicatePages(rr));
            }
        }
        if ((m = find(s, "^(?:insert|add)\\s+(?:a\\s+|an\\s+)?(?:new\\s+)?(?:blank|empty)?\\s*page\\s*(?:(after|before)\\s+(.+))?$"))
            != null) {
            List<Integer> at = m.group(2) == null ? List.of(cur) : pageList(m.group(2));
            if (at != null && !at.isEmpty()) {
                boolean before = "before".equals(m.group(1));
                int ref = before ? at.get(0) : at.get(at.size() - 1);
                int k = before ? ref : ref + 1;
                return new Action("Insert a blank page " + (before ? "before " : "after ") + describe(List.of(ref)),
                                  () -> p.insertBlank(k));
            }
        }
        if ((m = find(s, "^move\\s+(?:the\\s+)?(.+?)\\s+(to|before|after)\\s+(?:the\\s+)?(?:position\\s+|page\\s+)?(\\d+|start|"
                         + "beginning|front|end|back)$")) != null) {
            List<Integer> rg = pageList(m.group(1));
            if (rg != null && !rg.isEmpty()) {
                String rel = m.group(2);
                String where = m.group(3);
                // the index, in the order the pages have now, the moved pages go in front of
                int target;
                String said;
                if (where.matches("\\d+") && !rel.equals("to")) {
                    // before or after a page: the page printed with that number
                    int at = p.doc.pageNamed(where);
                    if (at < 0) {
                        return noSuchPage(Integer.parseInt(where));
                    }
                    target = rel.equals("before") ? at : at + 1;
                    said = " " + rel + " " + describe(List.of(at));
                } else if (where.matches("\\d+")) {
                    // to a position: a place in the file
                    int n = clamp(Integer.parseInt(where), pages);
                    target = n > rg.get(0) + 1 ? n : n - 1;
                    said = " to position " + n;
                } else {
                    boolean start = where.matches("start|beginning|front");
                    target = start ? 0 : pages;
                    said = start ? " to the start" : " to the end";
                }
                List<Integer> rr = rg;
                int t = target;
                return new Action("Move " + describe(rg) + said, () -> p.movePages(rr, t));
            }
        }
        if ((m = find(s, "^extract\\s+(?:the\\s+)?(.+)$")) != null) {
            List<Integer> rg = pageList(m.group(1));
            if (rg != null) {
                List<Integer> rr = rg;
                return new Action("Extract " + describe(rg) + " into a new PDF", () -> p.extractPages(rr));
            }
        }
        if ((m = find(s, "^(?:export|save)\\s+(?:the\\s+)?(.*?)\\s*(?:as|to|in)\\s+(?:png|pngs|images|pictures)$")) != null) {
            List<Integer> rg = m.group(1).isEmpty() || m.group(1).equals("pages") ? allPages() : pageList(m.group(1));
            if (rg != null) {
                List<Integer> rr = rg;
                return new Action("Export " + describe(rg) + " as PNG pictures", () -> p.exportPng(rr));
            }
        }
        if (s.matches("(merge|append|combine|insert a pdf|add a pdf|merge a pdf|merge pdf|append a pdf)( .*)?")) {
            return new Action("Append another PDF at the end", () -> p.importPdf(pages));
        }

        // ---------------------------------------------------------------- view and panels
        if ((m = find(s, "^zoom\\s*(?:to\\s*|at\\s*)?(\\d+)\\s*%?$")) != null) {
            int z = Integer.parseInt(m.group(1));
            return new Action("Zoom to " + z + "%", () -> p.view.setZoom(z / 100.0, null));
        }
        if (s.matches("(zoom ?\\+|zoom in|bigger|enlarge|larger)")) {
            return new Action("Zoom in", () -> p.view.setZoom(p.view.zoom * 1.25, null));
        }
        if (s.matches("(zoom ?-|zoom out|smaller)")) {
            return new Action("Zoom out", () -> p.view.setZoom(p.view.zoom / 1.25, null));
        }
        if (s.matches(".*\\b(fit width|page width|full width)\\b.*")) {
            return new Action("Fit width", p.view::fitWidth);
        }
        if (s.matches(".*\\b(fit page|whole page|full page)\\b.*")) {
            return new Action("Fit page", p.view::fitPage);
        }
        if (s.matches(".*\\b(night mode|dark mode|night|dark|day mode|light mode)\\b.*")) {
            return new Action("Toggle night mode (reading)", p::toggleNight);
        }
        if (s.matches("(summary|summarize|insights|analyze|analysis|what is (this|it) about.*|tl;?dr|show (the )?summary)")) {
            return new Action("Show the summary and analysis of the document", () -> p.side.show("insights"));
        }
        if (s.matches("((show )?(the )?)(key ?words|key terms|topics|themes)")) {
            return new Action("Show the key terms", () -> p.side.show("insights"));
        }
        if (s.matches("((show )?(the )?)(outline|toc|table of contents|contents|bookmarks|headings)")) {
            return new Action("Show the outline", () -> p.side.show("outline"));
        }
        if (s.matches("((show )?(the )?)(history|edit history|changes)")) {
            return new Action("Show the edit history", () -> p.side.show("history"));
        }
        if (s.matches("((show )?(the )?)(thumbnails|pages|page list)")) {
            return new Action("Show the thumbnails", () -> p.side.show("pages"));
        }

        // ---------------------------------------------------------------- tools
        if (s.matches("(add text|text box|new text|type text|write)")) {
            return new Action("Tool: add text", () -> p.setTool(PdfView.Tool.ADDTEXT));
        }
        if (s.matches("(edit text|edit|change text|modify text)")) {
            return new Action("Tool: edit text", () -> p.setTool(PdfView.Tool.EDIT));
        }
        if (s.matches("(highlighter|highlight tool|highlight)")) {
            return new Action("Tool: highlighter", () -> p.setTool(PdfView.Tool.HIGHLIGHT));
        }
        if (s.matches("(draw|drawing|pen|ink|freehand|sketch|sign|signature)")) {
            return new Action("Tool: freehand drawing", () -> p.setTool(PdfView.Tool.INK));
        }
        if (s.matches("(rectangle|box|square|frame)")) {
            return new Action("Tool: rectangle", () -> p.setTool(PdfView.Tool.RECT));
        }
        if (s.matches("(ellipse|circle|oval)")) {
            return new Action("Tool: ellipse", () -> p.setTool(PdfView.Tool.ELLIPSE));
        }
        if (s.matches("(arrow)")) {
            return new Action("Tool: arrow", () -> p.setTool(PdfView.Tool.ARROW));
        }
        if (s.matches("(line)")) {
            return new Action("Tool: line", () -> p.setTool(PdfView.Tool.LINE));
        }
        if (s.matches("(redaction|redaction tool|redact tool|redact)")) {
            return new Action("Tool: redaction", () -> p.setTool(PdfView.Tool.REDACT));
        }
        if (s.matches("(note|notes|comment|sticky note)")) {
            return new Action("Tool: note", () -> p.setTool(PdfView.Tool.NOTE));
        }
        if (s.matches("(select|selection|select text)")) {
            return new Action("Tool: select", () -> p.setTool(PdfView.Tool.SELECT));
        }
        if (s.matches("(hand|pan|move view)")) {
            return new Action("Tool: pan", () -> p.setTool(PdfView.Tool.HAND));
        }

        // ---------------------------------------------------------------- files
        if (s.matches("(export|save)( the)? (text|txt|plain text)")) {
            return new Action("Export the text (reading order)", () -> p.exportText(false));
        }
        if (s.matches("(export|save)( as| to)? (markdown|md)")) {
            return new Action("Export as Markdown (headings detected)", () -> p.exportText(true));
        }
        if (s.matches("(print|print it|printing)")) {
            return new Action("Print", p::print);
        }
        if (s.matches("(save as|save a copy|save copy|make a copy)")) {
            return new Action("Save As…", p::saveAs);
        }
        if (s.matches("(save|save it|save changes|save the document)")) {
            return new Action("Save", p::save);
        }
        if (s.matches("(compress|optimize|shrink|reduce size|make (it )?smaller|smaller file)")) {
            return new Action("Save a compressed copy (object streams)", p::saveCompact);
        }
        if (s.matches("(properties|info|information|metadata|document info|document properties)")) {
            return new Action("Document properties", p::properties);
        }
        if (s.matches("(open|open a pdf|open file)")) {
            return new Action("Open a PDF", p::openDialog);
        }
        if (s.matches("(new|new document|new pdf|blank document)")) {
            return new Action("New blank document", p::newDocument);
        }
        // a lone word: searching it is the best guess
        if (s.length() >= 2 && !s.contains(" ") || r.startsWith("\"")) {
            String q = unquote(r);
            return new Action("Search for \"" + q + "\"", () -> p.search(q));
        }
        return null;
    }

    /* ================================================================= pictures */

    private Action pictures(String s, String r) {
        boolean selected = p.view.selImage != null;
        String it = "(?:the |this |that )?(?:" + PICTURE + "|" + (selected ? "it|this|that|selection" : "\\Z\\A") + ")";
        String on = "(?:\\s+(?:on|of|from|in)\\s+(?:the\\s+)?(.+))?";
        Matcher m;
        if (s.matches("(extract|save|export) (all |every )?(the )?" + PICTURES + "( of the (pdf|document))?")
            || s.matches("(extract|save|export) (all|every) (the )?" + PICTURE + ".*")) {
            return new Action("Save every picture of the document into a folder", p.images::extractAll);
        }
        if ((m = find(s, "^(?:compress|shrink|downsample|reduce|optimize)\\s+(?:all\\s+)?(?:the\\s+)?" + PICTURES
                         + "(?:\\s+(?:to|at)\\s+(\\d+)\\s*(?:ppi|dpi)?)?$")) != null) {
            double ppi = m.group(1) == null ? 150 : Math.max(36, Integer.parseInt(m.group(1)));
            return new Action("Shrink every picture above " + Math.round(ppi) + " ppi to " + Math.round(ppi)
                              + " ppi (photographs as JPEG)", () -> p.images.compressAll(ppi));
        }
        if (s.matches("(how many|count( the)?|list( the)?) " + PICTURE + ".*")) {
            return new Action("Count the pictures of the document", p.images::report);
        }
        if (s.matches("(insert|add|place|put)( a| an| the)? (new )?" + PICTURE + ".*")) {
            return new Action("Insert a picture on page " + p.doc.pageName(p.view.currentPage()),
                              () -> p.images.insertFromFile(p.view.currentPage(), null));
        }
        if (s.matches("paste( a| the)? " + PICTURE + ".*")) {
            return new Action("Paste the picture of the clipboard on page " + p.doc.pageName(p.view.currentPage()),
                              () -> {
                                  if (!p.images.paste(p.view.currentPage(), null)) {
                                      p.status("The clipboard holds no picture");
                                  }
                              });
        }
        if ((m = find(s, "^(?:delete|remove|erase)\\s+(?:all\\s+)?(?:the\\s+)?" + PICTURE + "\\s+(?:on|of|from)\\s+(?:the\\s+)?(.+)$"))
            != null && s.matches(".*(pictures|images|photos|all).*")) {
            List<Integer> rg = pageList(m.group(1));
            if (rg != null && rg.size() == 1) {
                int pg = rg.get(0);
                return new Action("Delete every picture of " + describe(List.of(pg)), () -> p.images.deleteAll(pg));
            }
        }
        // {verb, what may follow the picture, what it does, which action}
        String[][] verbs = {
            { "edit|retouch|touch up|open|modify|crop|adjust", "(?:\\s+in sphere image)?",
              "Edit %s in Sphere Image (the page shows the edits live)", "0" },
            { "replace|swap", "(?:\\s+with (?:a |another )?(?:file|picture|image))?", "Replace %s with a file", "1" },
            { "save|export|extract|download", "(?:\\s+as (?:a )?(?:file|png|jpe?g))?", "Save %s as a file", "2" },
            { "copy", "", "Copy %s", "3" },
            { "delete|remove|erase", "", "Delete %s", "4" },
            { "rotate|turn", "\\s+(?:to the\\s+)?(?:left|counterclockwise)", "Rotate %s left", "6" },
            { "rotate|turn", "(?:\\s+(?:to the\\s+)?(?:right|clockwise))?", "Rotate %s right", "5" },
            { "flip|mirror", "\\s+(?:vertically|upside down|top to bottom)", "Flip %s vertically", "8" },
            { "flip|mirror", "(?:\\s+(?:horizontally|left to right))?", "Flip %s horizontally", "7" },
            { "make|turn|convert", "\\s+(?:gray|grayscale|black and white|monochrome)", "Make %s gray", "9" },
            { "grayscale|desaturate", "", "Make %s gray", "9" },
            { "enhance|improve|fix|brighten|auto enhance|auto-enhance", "", "Enhance %s (levels stretched)", "10" },
            { "make", "\\s+(?:white|background|paper)\\s+transparent", "Make the white of %s transparent", "11" },
            { "remove the background of|remove the white of|clean", "", "Make the white of %s transparent", "11" },
            { "shrink|downsample|compress|reduce", "(?:\\s+to\\s+\\d+\\s*ppi)?", "Shrink %s to 150 ppi", "12" },
            { "show|describe|inspect|info about|properties of", "(?:\\s+(?:properties|info|details))?",
              "Show the properties of %s", "13" } };
        if (s.matches("(?:the |this )?" + PICTURE + "\\s+(?:properties|info|information|details)")) {
            s = "show the picture";
        }
        for (String[] v : verbs) {
            Pattern pat = Pattern.compile("^(?:" + v[0] + ")\\s+" + it + "(?:'s)?" + v[1] + on + "$");
            m = pat.matcher(s);
            if (!m.find()) {
                continue;
            }
            int k = Integer.parseInt(v[3]);
            List<Integer> rg = m.group(1) == null ? null : pageList(m.group(1));
            Object[] t = p.doc == null ? null : p.images.target(rg != null && rg.size() == 1 ? rg.get(0) : -1);
            if (t == null) {
                int pg = rg != null && rg.size() == 1 ? rg.get(0) : p.view.currentPage();
                return new Action("Page " + p.doc.pageName(pg) + " has no picture", () -> p.status("Page " + p.doc.pageName(pg)
                                                                                           + " has no picture"));
            }
            int pg = (Integer) t[0];
            PageImage im = (PageImage) t[1];
            String what = (p.view.selImage != null && im.sameAs(p.view.selImage) ? "the selected picture" : "the picture")
                          + " on page " + p.doc.pageName(pg);
            String desc = String.format(v[2], what);
            ImageTools tools = p.images;
            Runnable run = switch (k) {
                case 0 -> () -> tools.edit(pg, im);
                case 1 -> () -> tools.replaceWithFile(pg, im);
                case 2 -> () -> tools.saveAs(pg, im);
                case 3 -> () -> tools.copy(pg, im);
                case 4 -> () -> tools.delete(pg, im);
                case 5 -> () -> tools.turn(pg, im, 1);
                case 6 -> () -> tools.turn(pg, im, -1);
                case 7 -> () -> tools.mirror(pg, im, true);
                case 8 -> () -> tools.mirror(pg, im, false);
                case 9 -> () -> tools.pixelOp(pg, im, "Make picture gray", ImageOps::grayscale);
                case 10 -> () -> tools.pixelOp(pg, im, "Enhance picture", ImageOps::autoEnhance);
                case 11 -> () -> tools.pixelOp(pg, im, "Make the picture's white transparent", ImageOps::removeWhite);
                case 12 -> () -> tools.shrink(pg, im, 150);
                default -> () -> tools.properties(pg, im);
            };
            return new Action(desc, () -> {
                p.view.selectImage(pg, im);
                run.run();
            });
        }
        return null;
    }

    /* ================================================================= navigation */

    private Action goTo(String what, String raw) {
        int pages = p.pageCount();
        Matcher m;
        if (what.matches("(?:a |another )?(?:pdf|file|document)|(?:the )?(?:summary|outline|history|thumbnails|key terms)")) {
            // "open a pdf", "show me the summary": not a place in the document
            return null;
        }
        Action page = pageAction(what);
        if (page != null) {
            return page;
        }
        if (what.matches("(start|beginning|top|first page)")) {
            return new Action("First page", () -> p.goToPage(0));
        }
        if (what.matches("(end|bottom|last page)")) {
            return new Action("Last page", () -> p.goToPage(pages - 1));
        }
        if (what.matches("(back|previous position|where i was)")) {
            return new Action("Back to where you were", p::goBack);
        }
        if ((m = find(what, "^(fig(?:ure)?\\.?|table|tab\\.?)\\s*(\\d+[a-z]?)$")) != null) {
            String kind = m.group(1).startsWith("tab") ? "Table" : "Figure";
            String num = m.group(2);
            Insights in = p.insightsIfReady();
            if (in == null) {
                p.ensureInsights();
                return new Action("Go to " + kind.toLowerCase(Locale.ROOT) + " " + num + " (the analysis is still running)",
                                  () -> p.status("The document is still being analyzed: try again in a moment"));
            }
            for (Insights.Caption c : in.captions) {
                if (c.kind().equals(kind) && c.number().equalsIgnoreCase(num)) {
                    return new Action("Go to " + kind.toLowerCase(Locale.ROOT) + " " + num + " (page " + p.doc.pageName(c.page()) + ")",
                                      () -> {
                                          p.pushPosition();
                                          p.goToText(c.page(), c.start(), c.start() + kind.length() + num.length() + 1);
                                      });
                }
            }
            return new Action("No " + kind.toLowerCase(Locale.ROOT) + " " + num + " in this document",
                              () -> p.status("No " + kind.toLowerCase(Locale.ROOT) + " " + num + " found"));
        }
        if ((m = find(what, "^(?:ref(?:erence)?\\.?|citation)\\s*\\[?([a-z]*\\d+[a-z]?)\\]?$")) != null) {
            return reference(m.group(1));
        }
        // a heading, by its words
        String q = what.replaceFirst("^(?:section|chapter|heading|part|the section|the chapter)\\s+", "");
        if (q.matches("\\d+(\\.\\d+)*")) {
            q = q + " ";
        }
        return heading(q.trim());
    }

    /**
     * A page, named the way the user reads it: "page 23" is the page
     * printed 23 (its label), which in a document numbering its front matter
     * apart (i, ii, then 1, 2...) is not the 23rd of the file. The 23rd of
     * the file is "the 23rd page", "file page 23" or "(23)". Null when the
     * words name no page.
     */
    private Action pageAction(String what) {
        if (p.doc == null) {
            return null;
        }
        Matcher m;
        if ((m = find(what, "^(?:(?:file|physical|pdf)\\s+page\\s+(\\d+)|(\\d+)(?:st|nd|rd|th)\\s+page(?:\\s+of the (?:file|pdf|document))?"
                         + "|page\\s+(\\d+)\\s+of the (?:file|pdf|document)|\\((\\d+)\\))$")) != null) {
            String g = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
            int n = Integer.parseInt(g);
            if (n < 1 || n > p.pageCount()) {
                return noSuchPage(n);
            }
            String label = p.doc.pageName(n - 1);
            return new Action("Go to the " + ordinal(n) + " page of the file"
                              + (label.equals(String.valueOf(n)) ? "" : " (numbered " + label + ")"), () -> p.goToPage(n - 1));
        }
        if ((m = find(what, "^(?:page|p\\.?)?\\s*(\\d+)$")) != null) {
            String name = m.group(1);
            int i = p.doc.pageNamed(name);
            if (i < 0) {
                return noSuchPage(Integer.parseInt(name));
            }
            return new Action(goingTo(name, i), () -> p.goToPage(i));
        }
        // a label that is not a plain number: "page iv", "page A-3"
        if ((m = find(what, "^(?:page|p\\.?)\\s*([a-z][a-z0-9.-]*|\\d+[a-z.-][a-z0-9.-]*)$")) != null) {
            int i = p.doc.pageNamed(m.group(1));
            if (i >= 0) {
                return new Action(goingTo(p.doc.pageName(i), i), () -> p.goToPage(i));
            }
        }
        return null;
    }

    /** "Go to page 23 (the 25th page of the file)": the page as the document numbers it, and its place when they differ. */
    private String goingTo(String name, int i) {
        String label = p.doc.pageName(i);
        String place = String.valueOf(i + 1);
        if (label.equalsIgnoreCase(name)) {
            return "Go to page " + label + (label.equals(place) ? "" : " (the " + ordinal(i + 1) + " page of the file)");
        }
        // no page is numbered so: the page at that place
        return "Go to the " + ordinal(i + 1) + " page of the file (numbered " + label + "; none is numbered " + name + ")";
    }

    static String ordinal(int n) {
        int t = n % 100;
        String suffix = t >= 11 && t <= 13 ? "th" : n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th";
        return n + suffix;
    }

    /** The heading (bookmark or detected) that best matches some words. */
    private Action heading(String q) {
        if (q.isEmpty() || p.doc == null) {
            return null;
        }
        String fq = norm(q).toLowerCase(Locale.ROOT);
        Set<String> qw = new LinkedHashSet<>(Arrays.asList(fq.split("[^\\p{L}\\p{N}.]+")));
        qw.remove("");
        double best = 0;
        String title = null;
        int page = -1;
        double y = -1;
        int start = -1;
        for (SpdfDoc.Bookmark b : p.doc.bookmarks()) {
            double sc = match(qw, fq, b.title());
            if (sc > best && b.page() >= 0) {
                best = sc;
                title = b.title();
                page = b.page();
                y = b.y();
                start = -1;
            }
        }
        Insights in = p.insightsIfReady();
        if (in != null) {
            for (Insights.Heading h : in.outline) {
                double sc = match(qw, fq, h.text()) * 0.98;
                if (sc > best) {
                    best = sc;
                    title = h.text();
                    page = h.page();
                    start = h.start();
                    y = -1;
                }
            }
        }
        if (best < 0.34 || title == null) {
            return headingInText(fq);
        }
        String t = title.length() > 60 ? title.substring(0, 60) + "…" : title;
        int pg = page;
        double yy = y;
        int st = start;
        String tt = title;
        return new Action("Go to the section \"" + t + "\" (page " + p.doc.pageName(pg) + ")", () -> {
            p.pushPosition();
            if (st >= 0) {
                p.goToText(pg, st, st + Math.min(tt.length(), 200));
            } else {
                p.view.scrollToPage(pg, yy);
            }
        });
    }

    /**
     * A heading neither bookmarked nor detected: the first short paragraph
     * (a line or two, as headings are) holding the words, in the pages read
     * so far.
     */
    private Action headingInText(String fq) {
        if (fq.length() < 3) {
            return null;
        }
        for (int i = 0; i < p.pageCount(); i++) {
            PageText t = p.doc.textIfReady(i);
            if (t == null) {
                continue;
            }
            for (int bi = 0; bi < t.blocks.size(); bi++) {
                String bt = t.blocks.get(bi).text();
                String fb = norm(bt).toLowerCase(Locale.ROOT);
                if (bt.split("\\s+").length > 12 || !fb.matches(".*\\b" + Pattern.quote(fq) + ".*")) {
                    continue;
                }
                int start = -1;
                for (int c = 0; c < t.charBlock.length; c++) {
                    if (t.charBlock[c] == bi) {
                        start = c;
                        break;
                    }
                }
                if (start < 0) {
                    continue;
                }
                int pg = i;
                int st = start;
                int len = bt.length();
                String shown = bt.length() > 60 ? bt.substring(0, 60) + "…" : bt;
                return new Action("Go to \"" + shown.trim() + "\" (page " + p.doc.pageName(pg) + ")", () -> {
                    p.pushPosition();
                    p.goToText(pg, st, st + len);
                });
            }
        }
        return null;
    }

    /** How well a title matches the words asked for, 0 to 1 (a number like "3.2" counts double). */
    private static double match(Set<String> qw, String fq, String title) {
        String ft = norm(title).toLowerCase(Locale.ROOT);
        if (ft.equals(fq)) {
            return 1;
        }
        if (ft.startsWith(fq + " ") || ft.endsWith(" " + fq)) {
            return 0.95;
        }
        Set<String> tw = new LinkedHashSet<>(Arrays.asList(ft.split("[^\\p{L}\\p{N}.]+")));
        tw.remove("");
        if (qw.isEmpty() || tw.isEmpty()) {
            return 0;
        }
        double hit = 0;
        for (String w : qw) {
            double h = 0;
            for (String x : tw) {
                if (x.equals(w)) {
                    h = Math.max(h, w.matches("[\\d.]+") ? 2 : 1);
                } else if (w.length() >= 4 && (x.startsWith(w) || distance(w, x) <= (w.length() >= 7 ? 2 : 1))) {
                    h = Math.max(h, 0.8);
                }
            }
            hit += h;
        }
        return Math.min(1, hit / Math.max(qw.size(), Math.min(tw.size(), qw.size() + 2)));
    }

    private Action reference(String label) {
        Insights in = p.insightsIfReady();
        if (in == null) {
            p.ensureInsights();
            return new Action("Show reference [" + label + "] (the analysis is still running)",
                              () -> p.status("The document is still being analyzed: try again in a moment"));
        }
        String text = in.references.get(label);
        if (text == null) {
            for (Map.Entry<String, String> e : in.references.entrySet()) {
                if (e.getKey().equalsIgnoreCase(label)) {
                    text = e.getValue();
                }
            }
        }
        if (text == null) {
            return new Action("No reference [" + label + "] in the bibliography",
                              () -> p.status("No reference [" + label + "] found"));
        }
        String t = text;
        return new Action("Show reference [" + label + "]", () -> JOptionPane.showMessageDialog(p,
            "<html><body style='width:420px'><b>[" + label + "]</b> " + escape(t) + "</body></html>", "Reference",
            JOptionPane.INFORMATION_MESSAGE));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private Action noSuchPage(int n) {
        int pages = p.pageCount();
        return new Action("There is no page " + n + ": this PDF has " + pages + (pages == 1 ? " page" : " pages"),
                          () -> p.status("There is no page " + n + " (" + pages + " pages)"));
    }

    /* ================================================================= contents */

    private Action highlightEntities(String what) {
        String kind = what.startsWith("date") ? "Date"
            : what.matches("e-?mails|mail addresses") ? "Email"
            : what.matches("links|urls|web addresses") ? "Link"
            : what.matches("amounts|money|prices") ? "Amount"
            : what.matches("dois") ? "DOI"
            : what.startsWith("arxiv") ? "arXiv" : "Quantity";
        String label = what.replace("e-mails", "emails");
        return new Action("Highlight all " + label + " in the document", () -> {
            Insights in = p.insightsIfReady();
            if (in == null) {
                p.ensureInsights();
                p.status("The document is still being analyzed: try again in a moment");
                return;
            }
            List<SmartSearch.Hit> hits = new ArrayList<>();
            for (Insights.Entity e : in.entities) {
                if (e.kind().equals(kind)) {
                    hits.add(new SmartSearch.Hit(e.page(), e.start(), e.end(), e.text(), 0));
                }
            }
            p.highlightRanges(hits, label);
        });
    }

    private void statistics() {
        Insights in = p.insightsIfReady();
        if (in == null) {
            p.ensureInsights();
            p.status("The document is being analyzed: the count comes in a moment");
            return;
        }
        int pages = p.pageCount();
        p.status(String.format(Locale.US, "%,d words, %,d characters, %d %s, about %d min of reading%s", in.words,
                               in.characters, pages, pages == 1 ? "page" : "pages", in.minutes,
                               "?".equals(in.language) ? "" : ", in " + in.language));
    }

    /** The automatic summary of some pages, in a dialog. */
    private void summarize(List<Integer> pages) {
        List<PageText> texts = new ArrayList<>();
        for (int i : pages) {
            texts.add(p.doc.text(i));
        }
        Insights in = Insights.of(texts);
        StringBuilder b = new StringBuilder("<html><body style='width:460px'>");
        if (in.summary.isEmpty()) {
            b.append("Not enough text to summarize.");
        } else {
            for (String[] sentence : in.summary) {
                b.append("<p style='margin-bottom:6px'>• ").append(escape(sentence[0])).append("</p>");
            }
        }
        if (!in.keywords.isEmpty()) {
            b.append("<p><b>Key terms</b>: ").append(escape(String.join(", ", in.keywords.subList(0,
                Math.min(10, in.keywords.size()))))).append("</p>");
        }
        b.append("</body></html>");
        JOptionPane.showMessageDialog(p, b.toString(), "Summary of " + describe(pages), JOptionPane.INFORMATION_MESSAGE);
    }

    /** Finds the pages with nothing on them (in the background), then asks before deleting them. */
    private void deleteBlankPages() {
        SpdfDoc d = p.doc;
        p.status("Looking for blank pages…");
        new SwingWorker<List<Integer>, Void>() {
            @Override
            protected List<Integer> doInBackground() {
                List<Integer> blank = new ArrayList<>();
                for (int i = 0; i < d.pageCount(); i++) {
                    PageText t = d.text(i);
                    if (!t.text.isBlank() || !t.images.isEmpty() || !d.page(i).annotations().isEmpty()) {
                        continue;
                    }
                    boolean paints = false;
                    for (Op op : d.renderer.pageOps(d.page(i))) {
                        if (op.name.matches("f\\*?|F|B\\*?|b\\*?|S|s|sh|Do|BI")) {
                            paints = true;
                            break;
                        }
                    }
                    if (!paints) {
                        blank.add(i);
                    }
                }
                return blank;
            }

            @Override
            protected void done() {
                try {
                    List<Integer> blank = get();
                    if (p.doc != d) {
                        return;
                    }
                    if (blank.isEmpty()) {
                        p.status("No blank page in this document");
                        return;
                    }
                    if (blank.size() >= d.pageCount()) {
                        p.status("Every page is blank: a PDF keeps at least one");
                        return;
                    }
                    int r = JOptionPane.showConfirmDialog(p, "Delete " + describe(blank) + " (blank)?", "Blank Pages",
                                                          JOptionPane.OK_CANCEL_OPTION);
                    if (r == JOptionPane.OK_OPTION) {
                        p.deletePages(blank);
                    }
                } catch (Exception e) {
                    p.error("Could not look for blank pages: " + e.getMessage(), e);
                }
            }
        }.execute();
    }

    /* ================================================================= pages, as people name them */

    private List<Integer> allPages() {
        List<Integer> r = new ArrayList<>();
        for (int i = 0; i < p.pageCount(); i++) {
            r.add(i);
        }
        return r;
    }

    /**
     * The pages some words name (0-based, in order, distinct), or null when
     * they name no page or one that does not exist: "page 3", "pages 2-4, 7
     * and 9", "p3", "all pages", "odd pages", "this page", "the last two
     * pages", "pages 5 to the end"...
     */
    List<Integer> pageList(String words) {
        int n = p.pageCount();
        int cur = p.view.currentPage();
        String s = words.trim().toLowerCase(Locale.ROOT).replaceAll("^(?:the|of)\\s+", "").replaceAll("\\s+", " ");
        List<Integer> r = new ArrayList<>();
        if (s.matches("(all|all pages|all the pages|every page|each page|everything|whole document|the whole document|"
                      + "entire document|document)")) {
            return allPages();
        }
        if (s.matches("(odd|odd pages|odd-numbered pages|every odd page)")) {
            for (int i = 0; i < n; i += 2) {
                r.add(i);
            }
            return r;
        }
        if (s.matches("(even|even pages|even-numbered pages|every even page)")) {
            for (int i = 1; i < n; i += 2) {
                r.add(i);
            }
            return r;
        }
        if (s.matches("(this|this page|current|current page|here|the current page|this one|page)")) {
            return List.of(cur);
        }
        if (s.matches("(first|first page|front page|cover|cover page)")) {
            return List.of(0);
        }
        if (s.matches("(last|last page|final page|back page)")) {
            return List.of(n - 1);
        }
        if (s.matches("(next|next page)")) {
            return List.of(Math.min(n - 1, cur + 1));
        }
        if (s.matches("(previous|prev|previous page)")) {
            return List.of(Math.max(0, cur - 1));
        }
        Matcher m;
        if ((m = find(s, "^first (\\d+) pages?$")) != null) {
            int k = Math.min(n, Integer.parseInt(m.group(1)));
            for (int i = 0; i < k; i++) {
                r.add(i);
            }
            return r.isEmpty() ? null : r;
        }
        if ((m = find(s, "^last (\\d+) pages?$")) != null) {
            int k = Math.min(n, Integer.parseInt(m.group(1)));
            for (int i = n - k; i < n; i++) {
                r.add(i);
            }
            return r.isEmpty() ? null : r;
        }
        if ((m = find(s, "^(?:from\\s+)?(?:pages?\\s+)?(\\d+)\\s+(?:to the end|to end|onward|and after|and following|on|-end|- end)$"))
            != null) {
            // a page number is the one printed on the page (its label), as everywhere in the phrases
            int k = p.doc == null ? -1 : p.doc.pageNamed(m.group(1));
            if (k < 0) {
                outOfRange = Integer.parseInt(m.group(1));
                return null;
            }
            for (int i = k; i < n; i++) {
                r.add(i);
            }
            return r;
        }
        String list = s.replaceFirst("^(?:pages?|pp?\\.?)\\s*", "");
        if (list.isEmpty()) {
            return null;
        }
        Set<Integer> set = new TreeSet<>();
        for (String part : list.split("\\s*(?:,|;|&|\\band\\b|\\bplus\\b)\\s*")) {
            if (part.isBlank()) {
                continue;
            }
            String pt = part.trim();
            // a label that is not a plain number, said whole: "ii", "A-3"
            int whole = p.doc == null || pt.matches("\\d+") ? -1 : p.doc.pageNamed(pt);
            if (whole >= 0) {
                set.add(whole);
                continue;
            }
            Matcher pm = Pattern.compile("^(\\d+|[ivxlcdm]+)(?:\\s*(?:-|–|to|through|thru|until)\\s*(\\d+|[ivxlcdm]+|end|last))?$")
                .matcher(pt);
            if (!pm.matches()) {
                return null;
            }
            // the numbers printed on the pages (their labels), else their places
            int a = p.doc == null ? -1 : p.doc.pageNamed(pm.group(1));
            int b = pm.group(2) == null ? a : pm.group(2).matches("end|last") ? n - 1
                : p.doc == null ? -1 : p.doc.pageNamed(pm.group(2));
            if (a < 0 || b < 0) {
                String bad = a < 0 ? pm.group(1) : pm.group(2);
                if (bad.matches("\\d+")) {
                    outOfRange = Integer.parseInt(bad);
                }
                return null;
            }
            for (int i = Math.min(a, b); i <= Math.max(a, b); i++) {
                set.add(i);
            }
        }
        return set.isEmpty() ? null : new ArrayList<>(set);
    }

    private static Matcher find(String s, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(s);
        return m.find() ? m : null;
    }

    private static int clamp(int n, int pages) {
        return Math.max(1, Math.min(pages, n));
    }

    /**
     * "page 3", "pages 2–4", "pages 1, 3 and 5–7", by the numbers printed on
     * the pages; their places in the file follow when those differ ("page 23
     * (the 25th page of the file)").
     */
    String describe(List<Integer> r) {
        java.util.function.IntFunction<String> place = i -> String.valueOf(i + 1);
        if (p.doc == null || !p.doc.hasPageLabels()) {
            return ranges(r, place);
        }
        String named = ranges(r, p.doc::pageName);
        String placed = ranges(r, place);
        if (named.equals(placed)) {
            return named;
        }
        return named + " (" + (r.size() == 1 ? "the " + ordinal(r.get(0) + 1) + " page of the file"
            : placed.replaceFirst("^pages ", "the file's pages ")) + ")";
    }

    private static String ranges(List<Integer> r, java.util.function.IntFunction<String> name) {
        if (r.size() == 1) {
            return "page " + name.apply(r.get(0));
        }
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < r.size()) {
            int j = i;
            while (j + 1 < r.size() && r.get(j + 1) == r.get(j) + 1) {
                j++;
            }
            parts.add(j > i ? name.apply(r.get(i)) + "–" + name.apply(r.get(j)) : name.apply(r.get(i)));
            i = j + 1;
        }
        if (parts.size() > 6) {
            return r.size() + " pages";
        }
        String last = parts.remove(parts.size() - 1);
        return "pages " + (parts.isEmpty() ? last : String.join(", ", parts) + " and " + last);
    }

    /* ================================================================= suggestions */

    /** Phrases that fit the document and the moment (the current page, the selected picture, its figures...). */
    private List<String> contextual() {
        List<String> out = new ArrayList<>();
        if (p.doc == null) {
            out.add("open");
            out.add("new document");
            return out;
        }
        int at = p.view.currentPage();
        // the current page as the phrases name pages: by the number printed on it
        String cur = p.doc.pageName(at);
        if (p.view.selImage != null) {
            out.addAll(List.of("edit the picture", "replace the picture", "rotate the picture right", "delete the picture",
                               "make the picture gray", "enhance the picture", "save the picture", "picture properties"));
        } else if (!p.doc.imagesIfReady(at).isEmpty()) {
            out.add("edit the picture on page " + cur);
        }
        if (p.view.selStart != p.view.selEnd) {
            out.add("highlight it");
            out.add("redact it");
        }
        out.addAll(List.of("rotate page " + cur + " right", "delete page " + cur, "duplicate page " + cur,
                           "insert blank page after " + cur, "extract page " + cur, "summarize page " + cur));
        Insights in = p.insightsIfReady();
        if (in != null) {
            int k = 0;
            for (Insights.Caption c : in.captions) {
                if (k++ < 8) {
                    out.add("go to " + c.kind().toLowerCase(Locale.ROOT) + " " + c.number());
                }
            }
            k = 0;
            for (Insights.Heading h : in.outline) {
                if (k++ < 10 && h.text().length() < 50) {
                    out.add("go to section " + h.text().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    /** Phrases matching what was typed so far, best first: word prefixes, typos and numbers forgiven. */
    List<String> suggest(String typed) {
        String t = norm(corrected(typed)).toLowerCase(Locale.ROOT).trim();
        List<String> pool = new ArrayList<>(contextual());
        pool.addAll(Arrays.asList(EXAMPLES));
        List<String> out = new ArrayList<>();
        if (t.isEmpty()) {
            for (String c : pool) {
                if (!out.contains(c)) {
                    out.add(c);
                }
                if (out.size() >= 8) {
                    break;
                }
            }
            return out;
        }
        if (t.matches("\\d+")) {
            // a number alone: what is done to a page
            return List.of("go to page " + t, "rotate page " + t + " right", "delete page " + t, "duplicate page " + t,
                           "extract page " + t, "summarize page " + t, "zoom " + t);
        }
        String[] typedWords = t.split(" ");
        String number = null;
        for (String w : typedWords) {
            if (w.matches("\\d+")) {
                number = w;
            }
        }
        List<Object[]> scored = new ArrayList<>();
        for (int idx = 0; idx < pool.size(); idx++) {
            String c = pool.get(idx);
            String[] cw = norm(c).toLowerCase(Locale.ROOT).split(" ");
            double score = 0;
            boolean all = true;
            for (int i = 0; i < typedWords.length; i++) {
                String w = typedWords[i];
                if (w.isEmpty() || w.matches("\\d+") || w.matches("the|a|an|of|on|to")) {
                    continue;
                }
                double best = 0;
                for (String x : cw) {
                    if (x.equals(w)) {
                        best = Math.max(best, 4);
                    } else if (x.startsWith(w)) {
                        best = Math.max(best, 3);
                    } else if (w.length() >= 3 && x.contains(w)) {
                        best = Math.max(best, 1.5);
                    } else if (w.length() >= 4 && distance(w, x.substring(0, Math.min(x.length(), w.length()))) <= 1) {
                        best = Math.max(best, 1.2);
                    }
                }
                if (best == 0) {
                    all = false;
                    break;
                }
                score += best + (i == 0 && cw[0].startsWith(w) ? 2 : 0);
            }
            if (!all || score == 0) {
                continue;
            }
            String shown = c;
            if (number != null && c.matches("[^\\d]*\\d+[^\\d]*")) {
                // the number typed goes in the phrase ("rotate 7" proposes "rotate page 7 right")
                shown = c.replaceFirst("\\d+", number);
            }
            scored.add(new Object[] { shown, score - idx * 0.001 });
        }
        scored.sort((a, b) -> Double.compare((Double) b[1], (Double) a[1]));
        for (Object[] o : scored) {
            String c = (String) o[0];
            if (!out.contains(c)) {
                out.add(c);
            }
            if (out.size() >= 8) {
                break;
            }
        }
        return out;
    }
}
