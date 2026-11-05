package com.sphere.core.commands;

import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfDocument;
import com.sphere.components.spdf.cos.PdfPage;
import com.sphere.components.spdf.cos.PdfWriter;
import com.sphere.components.spdf.edit.EditHistory;
import com.sphere.components.spdf.edit.PageOps;
import com.sphere.components.spdf.font.PdfFont;
import com.sphere.components.spdf.render.PageRenderer;
import com.sphere.components.spdf.render.RenderCache;
import com.sphere.components.spdf.render.TextGlyph;
import com.sphere.components.spdf.text.Insights;
import com.sphere.components.spdf.text.LayoutAnalyzer;
import com.sphere.components.spdf.text.PageText;
import com.sphere.components.spdf.text.SmartSearch;
import com.sphere.utils.AppLogger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;

/**
 * :spdf -- Sphere's PDF reader and editor, in SphereCoder: pages and
 * thumbnails, text edited in place with any font, color and style,
 * annotations, page operations, redaction, smart search, an analysis of the
 * document, commands in plain words. The console commands read and write
 * PDFs with the same engine (pure Java, nothing to install).
 */
public final class PdfCommands {

    private PdfCommands() {
    }

    /** Arguments split on spaces, "quoted parts" kept whole. */
    private static List<String> words(String input, String command) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(Handlers.args(input, command));
        while (m.find()) {
            out.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        return out;
    }

    private static Path local(String spec) {
        try {
            return com.sphere.core.fs.WorkingDirectory.get().resolve(spec).normalize();
        } catch (RuntimeException e) {
            return Path.of(spec);
        }
    }

    private static PdfDocument read(String spec) {
        Path p = local(spec);
        if (!Files.isRegularFile(p)) {
            AppLogger.error("No file " + p);
            return null;
        }
        try {
            return PdfDocument.open(p, null);
        } catch (Exception e) {
            AppLogger.error("Cannot read " + p.getFileName() + ": " + e.getMessage());
            return null;
        }
    }

    /** :spdf [file.pdf] -- opens the PDF editor in SphereCoder. */
    public static void open(String input, CommandExecutionContext ctx) {
        List<String> w = words(input, ":spdf");
        Path p = w.isEmpty() ? null : local(String.join(" ", w));
        if (p != null && !Files.isRegularFile(p)) {
            AppLogger.error("No file " + p + ". :spdf opens a PDF, or an empty editor without argument.");
            return;
        }
        SwingUtilities.invokeLater(() -> {
            com.sphere.ui.SphereCoderFrame frame = com.sphere.Sphere.editorWindow();
            if (frame == null) {
                AppLogger.error("The SphereCoder window is not open yet.");
                return;
            }
            if ((frame.getExtendedState() & java.awt.Frame.ICONIFIED) != 0) {
                frame.setExtendedState(frame.getExtendedState() & ~java.awt.Frame.ICONIFIED);
            }
            frame.setVisible(true);
            frame.toFront();
            frame.getEditor().openPdf(p == null ? null : p.toFile());
        });
        AppLogger.info(":spdf " + (p == null ? "opened" : "opened " + p.getFileName())
                       + "  (Ctrl+K in the editor: commands in plain words)");
    }

    /** :spdf info <file> -- what the file is. */
    public static void info(String input, CommandExecutionContext ctx) {
        List<String> w = words(input, ":spdf info");
        if (w.isEmpty()) {
            Handlers.usage(":spdf info <file.pdf>");
            return;
        }
        PdfDocument d = read(String.join(" ", w));
        if (d == null) {
            return;
        }
        PdfDict info = d.info();
        AppLogger.raw("PDF " + d.version() + ", " + d.pageCount() + " page(s)"
                      + (d.isEncrypted() ? ", encrypted (opened with the empty user password)" : "")
                      + (d.wasRepaired() ? ", cross-reference damaged and rebuilt" : ""));
        for (String k : new String[] { "Title", "Author", "Subject", "Keywords", "Creator", "Producer", "CreationDate",
            "ModDate" }) {
            String v = info == null ? null : info.getText(k);
            if (v != null && !v.isBlank()) {
                AppLogger.raw(String.format("  %-13s %s", k, v.trim()));
            }
        }
        PdfPage p0 = d.pages().get(0);
        AppLogger.raw(String.format("  %-13s %.0f x %.0f pt (%.0f x %.0f mm)%s", "Page 1", p0.displayWidth(),
                                    p0.displayHeight(), p0.displayWidth() / 72 * 25.4, p0.displayHeight() / 72 * 25.4,
                                    p0.rotation() != 0 ? ", rotated " + p0.rotation() : ""));
        RenderCache rc = new RenderCache(d);
        PageRenderer r = new PageRenderer(rc);
        Map<String, String> fonts = new TreeMap<>();
        for (int i = 0; i < Math.min(d.pageCount(), 30); i++) {
            text(d, r, i);
        }
        for (PdfFont f : rc.loadedFonts()) {
            fonts.put(f.baseFont, f.getClass().getSimpleName().replace("Font", "") + (f.embedded ? f.subset
                ? ", embedded subset" : ", embedded" : ", not embedded"));
        }
        AppLogger.raw("  Fonts (first 30 pages):");
        for (Map.Entry<String, String> e : fonts.entrySet()) {
            AppLogger.raw("    " + e.getKey() + "  (" + e.getValue() + ")");
        }
    }

    private static PageText text(PdfDocument d, PageRenderer r, int i) {
        PdfPage p = d.pages().get(i);
        PageRenderer.Options o = new PageRenderer.Options();
        o.paint = false;
        o.annotations = false;
        List<TextGlyph> gs = new ArrayList<>();
        o.sink = gs::add;
        r.render(null, p, null, o);
        return LayoutAnalyzer.analyze(gs, p.displayWidth());
    }

    /** Pages "1-3,5" (1-based) as indices, within a count. */
    private static List<Integer> pages(String spec, int count) {
        List<Integer> out = new ArrayList<>();
        for (String part : spec.split(",")) {
            String[] ab = part.trim().split("-");
            try {
                int a = Integer.parseInt(ab[0].trim());
                int b = ab.length > 1 ? Integer.parseInt(ab[1].trim()) : a;
                for (int i = Math.max(1, a); i <= Math.min(count, b); i++) {
                    out.add(i - 1);
                }
            } catch (NumberFormatException e) {
                // skipped
            }
        }
        return out;
    }

    /** :spdf text <file> [pages] -- the text, in reading order. */
    public static void text(String input, CommandExecutionContext ctx) {
        List<String> w = words(input, ":spdf text");
        if (w.isEmpty()) {
            Handlers.usage(":spdf text <file.pdf> [pages, e.g. 1-3]");
            return;
        }
        PdfDocument d = read(w.get(0));
        if (d == null) {
            return;
        }
        List<Integer> ps = w.size() > 1 ? pages(w.get(1), d.pageCount()) : pages("1-" + d.pageCount(), d.pageCount());
        PageRenderer r = new PageRenderer(new RenderCache(d));
        for (int i : ps) {
            AppLogger.raw("── page " + (i + 1) + " ──");
            AppLogger.raw(text(d, r, i).text);
        }
    }

    /** :spdf summary <file> -- language, key terms, summary, named things. */
    public static void summary(String input, CommandExecutionContext ctx) {
        List<String> w = words(input, ":spdf summary");
        if (w.isEmpty()) {
            Handlers.usage(":spdf summary <file.pdf>");
            return;
        }
        PdfDocument d = read(String.join(" ", w));
        if (d == null) {
            return;
        }
        PageRenderer r = new PageRenderer(new RenderCache(d));
        List<PageText> pts = new ArrayList<>();
        for (int i = 0; i < d.pageCount(); i++) {
            pts.add(text(d, r, i));
        }
        Insights in = Insights.of(pts);
        AppLogger.raw(String.format("%s · %,d words · ~%d min · %d page(s)", in.language, in.words, in.minutes,
                                    d.pageCount()));
        AppLogger.raw("Key terms: " + String.join(", ", in.keywords));
        AppLogger.raw("Summary:");
        for (String[] s : in.summary) {
            AppLogger.raw("  • " + s[1] + "  (p. " + s[0] + ")");
        }
        Map<String, Integer> kinds = new TreeMap<>();
        for (Insights.Entity e : in.entities) {
            kinds.merge(e.kind(), 1, Integer::sum);
        }
        if (!kinds.isEmpty()) {
            AppLogger.raw("Found: " + kinds);
        }
        if (!in.captions.isEmpty()) {
            AppLogger.raw(in.captions.size() + " figure/table caption(s); " + in.references.size() + " reference(s)");
        }
    }

    /** :spdf search <file> <words> -- smart search (accents, case, ligatures, hyphenation ignored). */
    public static void search(String input, CommandExecutionContext ctx) {
        List<String> w = words(input, ":spdf search");
        if (w.size() < 2) {
            Handlers.usage(":spdf search <file.pdf> <words>");
            return;
        }
        PdfDocument d = read(w.get(0));
        if (d == null) {
            return;
        }
        String q = String.join(" ", w.subList(1, w.size()));
        PageRenderer r = new PageRenderer(new RenderCache(d));
        List<SmartSearch.Hit> hits = SmartSearch.search(d.pageCount(), i -> text(d, r, i).text, q, SmartSearch.Mode.SMART,
                                                        500);
        for (SmartSearch.Hit h : hits) {
            AppLogger.raw("p." + (h.page() + 1) + "  " + h.context().replace("\u0001", "«").replace("\u0002", "»"));
        }
        AppLogger.info(hits.size() + " match(es) for \"" + q + "\"");
    }

    /** :spdf compress <in> [out] -- rewrites with object streams; only reachable objects kept. */
    public static void compress(String input, CommandExecutionContext ctx) {
        List<String> w = words(input, ":spdf compress");
        if (w.isEmpty()) {
            Handlers.usage(":spdf compress <in.pdf> [out.pdf]");
            return;
        }
        PdfDocument d = read(w.get(0));
        if (d == null) {
            return;
        }
        Path in = local(w.get(0));
        Path out = w.size() > 1 ? local(w.get(1))
            : in.resolveSibling(in.getFileName().toString().replaceFirst("(?i)\\.pdf$", "") + "-compressed.pdf");
        try {
            byte[] b = PdfWriter.write(d, true);
            Files.write(out, b);
            long before = Files.size(in);
            AppLogger.success(String.format("%s: %,d → %,d bytes (%+.1f %%)", out.getFileName(), before, b.length,
                                            100.0 * (b.length - before) / before));
        } catch (Exception e) {
            AppLogger.error("Cannot write " + out + ": " + e.getMessage());
        }
    }

    /** :spdf merge <out> <in1> <in2> ... -- one PDF from several. */
    public static void merge(String input, CommandExecutionContext ctx) {
        List<String> w = words(input, ":spdf merge");
        if (w.size() < 3) {
            Handlers.usage(":spdf merge <out.pdf> <in1.pdf> <in2.pdf> [...]");
            return;
        }
        PdfDocument out = PdfDocument.create();
        EditHistory h = new EditHistory(out);
        int total = 0;
        for (String s : w.subList(1, w.size())) {
            PdfDocument d = read(s);
            if (d == null) {
                return;
            }
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < d.pageCount(); i++) {
                all.add(i);
            }
            PageOps.importPages(h, out, d, all, out.pageCount());
            total += all.size();
        }
        try {
            Files.write(local(w.get(0)), PdfWriter.write(out, true));
            AppLogger.success(local(w.get(0)).getFileName() + ": " + total + " page(s) from " + (w.size() - 1) + " file(s)");
        } catch (Exception e) {
            AppLogger.error("Cannot write " + w.get(0) + ": " + e.getMessage());
        }
    }

    /** :spdf extract <in> <pages> <out> -- some pages as a new PDF. */
    public static void extract(String input, CommandExecutionContext ctx) {
        List<String> w = words(input, ":spdf extract");
        if (w.size() < 3) {
            Handlers.usage(":spdf extract <in.pdf> <pages, e.g. 1-3,7> <out.pdf>");
            return;
        }
        PdfDocument d = read(w.get(0));
        if (d == null) {
            return;
        }
        List<Integer> ps = pages(w.get(1), d.pageCount());
        if (ps.isEmpty()) {
            AppLogger.error("No page in " + w.get(1) + " (the file has " + d.pageCount() + ")");
            return;
        }
        try {
            Files.write(local(w.get(2)), PageOps.extract(d, ps));
            AppLogger.success(local(w.get(2)).getFileName() + ": " + ps.size() + " page(s)");
        } catch (Exception e) {
            AppLogger.error("Cannot write " + w.get(2) + ": " + e.getMessage());
        }
    }
}
