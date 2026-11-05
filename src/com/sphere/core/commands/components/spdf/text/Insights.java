package com.sphere.components.spdf.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a document is about, computed on this machine with nothing sent
 * anywhere: its language, length and reading time, its key terms (TF-IDF
 * with the headings weighted up, two-word terms included), a summary of its
 * most central sentences (TextRank), the things it names (e-mails, links,
 * DOIs, arXiv identifiers, dates, money, and physical quantities with their
 * units: 13 TeV, 3.2 fb⁻¹, 25 ns), its figures and tables, an outline of its
 * headings, and its bibliography, so a citation like [12] can be shown on hover.
 */
public final class Insights {

    public record Entity(String kind, String text, int page, int start, int end) {
    }

    public record Heading(int level, String text, int page, int start) {
    }

    public record Caption(String kind, String number, String text, int page, int start) {
    }

    public String language = "?";
    public int words;
    public int characters;
    public int minutes;
    public final List<String> keywords = new ArrayList<>();
    public final List<String[]> summary = new ArrayList<>();
    public final List<Entity> entities = new ArrayList<>();
    public final List<Heading> outline = new ArrayList<>();
    public final List<Caption> captions = new ArrayList<>();
    /** Bibliography entries by their label ("12", "Smith2020"). */
    public final Map<String, String> references = new LinkedHashMap<>();

    // ------------------------------------------------------------------
    // Foreign-language data. This is not interface text (everything Sphere
    // shows is in US English): it is the few words a document written in
    // French, German or Spanish needs to be read correctly, so its language
    // is named, its common words stay out of the key terms, and its dates,
    // table captions and reference section are found.
    // ------------------------------------------------------------------

    private static final String FR = "le la les un une des du de d l et ou mais donc or ni car que qui quoi dont ou "
        + "ce cet cette ces son sa ses leur leurs mon ma mes ton ta tes notre nos votre vos il elle ils elles on nous "
        + "vous je tu se sur sous dans par pour avec sans entre vers chez au aux en y ne pas plus moins tres tout tous "
        + "toute toutes est sont etre a ont avoir ete fait faire peut peuvent comme aussi ainsi alors si bien cela ceci "
        + "deux trois autre autres meme memes lors apres avant pendant selon dont lui eux ici la-bas quand comment car "
        + "afin cas ces non oui plusieurs chaque quelque quelques celle celui ceux celles entre egalement encore";
    private static final String DE = "der die das und ist nicht mit von zu den des im dem ein eine einer auf fur sich "
        + "auch als wie bei wird werden";
    private static final String ES = "el los las y es no con por para una un del se su como mas pero sus le ya o este "
        + "si porque esta entre cuando muy sin sobre tambien";
    /** A day-month-year date in a French document ("1er mars 2024"). */
    private static final String FR_DATE = "\\d{1,2}(?:er)? (?:janvier|f[ée]vrier|mars|avril|mai|juin|juillet|ao[uû]t|"
        + "septembre|octobre|novembre|d[ée]cembre) \\d{4}";
    /** What a French document calls a table, and its reference section. */
    private static final String FR_TABLE = "Tableau";
    private static final String FR_REFERENCES = "R[ée]f[ée]rences|Bibliographie";

    // ------------------------------------------------------------------

    private static final Set<String> STOP = new HashSet<>();
    private static final String EN = "the a an and or but if then else of to in on at by for with without from into "
        + "onto over under about as is are was were be been being have has had do does did this that these those it its "
        + "they them their there here we us our you your he she his her i me my not no nor so than too very can could "
        + "may might must shall should will would which who whom whose what when where why how all any both each few "
        + "more most other some such only own same also just via per using used use based between through during "
        + "within while however therefore thus et al fig figure table section eq one two three";
    private static final String WEB = "http https www com org net html htm php pdf edu gov site web page pages vol pp "
        + "doi isbn issn ed eds proc conf journal phys rev lett available online accessed retrieved";

    static {
        for (String s : (FR + " " + EN + " " + DE + " " + ES + " " + WEB).split("\\s+")) {
            STOP.add(s);
        }
    }

    private static final Pattern WORD = Pattern.compile("[\\p{L}][\\p{L}\\p{Mn}'’\\-]*[\\p{L}]|[\\p{L}]{1,2}");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern URL = Pattern.compile("(https?://|www\\.)[^\\s<>\"\\)\\]]+[^\\s<>\"\\)\\].,;:]");
    private static final Pattern DOI = Pattern.compile("\\b10\\.\\d{4,9}/[-._;()/:A-Za-z0-9]+[A-Za-z0-9]");
    private static final Pattern ARXIV = Pattern.compile("(?i)\\barXiv:\\s?(\\d{4}\\.\\d{4,5}(v\\d+)?|[a-z\\-]+/\\d{7})");
    private static final String MONTHS = "(?:January|February|March|April|May|June|July|August|September|October|"
        + "November|December)";
    private static final Pattern DATE = Pattern.compile("\\b(\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}|\\d{4}-\\d{2}-\\d{2}|"
        + MONTHS + " \\d{1,2},? \\d{4}|\\d{1,2} " + MONTHS + " \\d{4}|" + FR_DATE + ")\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MONEY = Pattern.compile("([€$£]\\s?\\d[\\d\\s.,]*\\d|\\d[\\d\\s.,]*\\s?(€|EUR|USD|\\$|k€|M€))");
    private static final Pattern QUANTITY = Pattern.compile("(?<![\\w.])[-+]?\\d+(?:[.,]\\d+)?(?:\\s?[×x]\\s?10\\^?[-−]?\\d+)?"
        + "\\s?(?:[TGMkmµnp]?eV(?:/c\\^?2|/c)?|fb[-−]1|fb⁻¹|pb[-−]1|pb⁻¹|nb|mb|barn|[kMG]?Hz|[mµn]?s|ns|ps|[mckµn]?m|"
        + "[mk]?g|kg|K|°C|T|mT|Gy|Sv|Bq|[kMG]?W|[kMG]?V|mA|A|Pa|bar|mol|%|σ|sigma|rad|mrad|sr|cm[-−]2|cm²|m²|m³|L)"
        + "(?![\\w])");
    private static final Pattern CAPTION = Pattern.compile("(?m)^(Figure|Fig\\.|FIG\\.|Table|TABLE|" + FR_TABLE
        + "|Algorithm|Listing)\\s+(\\d+[a-z]?|[IVX]+)[.:]?\\s*(.{0,140})");
    /** A mention of a figure or a table in running text: "Fig. 3", "Figure 3", "Table 2". */
    public static final Pattern CAPTION_MENTION = Pattern.compile("(?i)(fig\\.?|figure|table|" + FR_TABLE + ")\\s*(\\d+)");
    private static final Pattern REF_SECTION = Pattern.compile("(?im)^\\s*(References|Bibliography|Literature Cited|"
        + "Works Cited|" + FR_REFERENCES + ")\\s*$");
    private static final Pattern REF_ENTRY = Pattern.compile("(?m)^\\s*\\[(\\d{1,3}|[A-Za-z]+\\d{2,4}[a-z]?)\\]\\s+(.+)");
    private static final Pattern REF_NUMBERED = Pattern.compile("(?m)^\\s*(\\d{1,3})\\.\\s+([A-Z].+)");

    /** Computes everything from the pages' texts (reading order). */
    public static Insights of(List<PageText> pages) {
        Insights in = new Insights();
        StringBuilder all = new StringBuilder();
        List<String> pageTexts = new ArrayList<>();
        for (PageText p : pages) {
            String t = p == null ? "" : p.text;
            pageTexts.add(t);
            all.append(t).append("\n\n");
        }
        String text = all.toString();
        in.characters = text.replaceAll("\\s+", "").length();
        Map<String, Integer> tf = new HashMap<>();
        Map<String, Integer> df = new HashMap<>();
        int fr = 0;
        int en = 0;
        int de = 0;
        int es = 0;
        Set<String> frSet = new HashSet<>(List.of(FR.split(" ")));
        Set<String> enSet = new HashSet<>(List.of(EN.split(" ")));
        Set<String> deSet = new HashSet<>(List.of(DE.split(" ")));
        Set<String> esSet = new HashSet<>(List.of(ES.split(" ")));
        for (String raw : pageTexts) {
            // links and addresses are named things, not the subject: they stay out of the key terms
            String t = EMAIL.matcher(URL.matcher(DOI.matcher(raw).replaceAll(" ")).replaceAll(" ")).replaceAll(" ");
            Set<String> seen = new HashSet<>();
            String prev = null;
            Matcher m = WORD.matcher(t);
            while (m.find()) {
                String w = m.group().toLowerCase(Locale.ROOT);
                String f = SmartSearch.fold(w).text();
                in.words++;
                if (frSet.contains(f)) {
                    fr++;
                }
                if (enSet.contains(f)) {
                    en++;
                }
                if (deSet.contains(f)) {
                    de++;
                }
                if (esSet.contains(f)) {
                    es++;
                }
                if (STOP.contains(f) || w.length() < 3) {
                    prev = null;
                    continue;
                }
                tf.merge(w, 1, Integer::sum);
                if (seen.add(w)) {
                    df.merge(w, 1, Integer::sum);
                }
                if (prev != null) {
                    String bi = prev + " " + w;
                    tf.merge(bi, 1, Integer::sum);
                    if (seen.add(bi)) {
                        df.merge(bi, 1, Integer::sum);
                    }
                }
                prev = w;
            }
        }
        int maxLang = Math.max(Math.max(fr, en), Math.max(de, es));
        in.language = maxLang == 0 ? "?" : maxLang == fr ? "French" : maxLang == en ? "English"
            : maxLang == de ? "German" : "Spanish";
        in.minutes = Math.max(1, Math.round(in.words / 230f));

        // headings, weighted into the key terms
        Map<String, Double> headingBoost = new HashMap<>();
        for (int p = 0; p < pages.size(); p++) {
            PageText pt = pages.get(p);
            if (pt == null) {
                continue;
            }
            for (TextBlock b : pt.blocks) {
                if (b.headingLevel > 0) {
                    String h = b.text().trim();
                    if (h.length() > 1 && h.length() < 160) {
                        int start = indexOfBlock(pt, b);
                        in.outline.add(new Heading(b.headingLevel, h, p, start));
                        Matcher m = WORD.matcher(h);
                        while (m.find()) {
                            headingBoost.merge(m.group().toLowerCase(Locale.ROOT), 1.0, Double::sum);
                        }
                    }
                }
            }
        }
        int nDocs = Math.max(1, pages.size());
        List<Map.Entry<String, Double>> scored = new ArrayList<>();
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            String w = e.getKey();
            int count = e.getValue();
            boolean bigram = w.indexOf(' ') > 0;
            if (count < (bigram ? 3 : 2)) {
                continue;
            }
            double idf = Math.log(1.0 + nDocs / (double) df.getOrDefault(w, 1));
            double s = Math.log(1 + count) * (0.5 + idf) * (bigram ? 1.6 : 1.0);
            s *= 1 + 0.5 * headingBoost.getOrDefault(w, 0.0);
            if (w.length() > 5) {
                s *= 1.1;
            }
            scored.add(Map.entry(w, s));
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Double> e : scored) {
            if (in.keywords.size() >= 16) {
                break;
            }
            String w = e.getKey();
            boolean covered = false;
            for (String k : in.keywords) {
                if (k.contains(w) || w.contains(k) && w.indexOf(' ') < 0) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                in.keywords.add(w);
            }
        }
        in.summary.addAll(summarize(pageTexts, 5));
        in.findEntities(pageTexts);
        in.findCaptions(pageTexts);
        in.findReferences(pageTexts);
        return in;
    }

    private static int indexOfBlock(PageText pt, TextBlock b) {
        for (int i = 0; i < pt.charBlock.length; i++) {
            if (pt.blocks.get(pt.charBlock[i]) == b) {
                return i;
            }
        }
        return 0;
    }

    /* ------------------------------------------------------------- summary */

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[\\p{Lu}\"«(\\[])");

    /** The most central sentences (TextRank over word overlap), in document order: {page, sentence}. */
    static List<String[]> summarize(List<String> pages, int n) {
        List<String> sentences = new ArrayList<>();
        List<Integer> sentPage = new ArrayList<>();
        for (int p = 0; p < pages.size(); p++) {
            String t = pages.get(p).replaceAll("-\\n(?=\\p{Ll})", "").replaceAll("\\s+", " ");
            for (String s : SENTENCE_END.split(t)) {
                String c = s.trim();
                int words = c.split("\\s+").length;
                if (words >= 8 && words <= 60 && !c.matches(".*\\.{4,}.*") && c.matches(".*[.!?]$")) {
                    sentences.add(c);
                    sentPage.add(p);
                }
                if (sentences.size() >= 600) {
                    break;
                }
            }
        }
        int m = sentences.size();
        if (m == 0) {
            return List.of();
        }
        List<Map<String, Integer>> vec = new ArrayList<>();
        for (String s : sentences) {
            Map<String, Integer> v = new HashMap<>();
            Matcher mm = WORD.matcher(s.toLowerCase(Locale.ROOT));
            while (mm.find()) {
                String w = mm.group();
                if (!STOP.contains(SmartSearch.fold(w).text()) && w.length() > 2) {
                    v.merge(w, 1, Integer::sum);
                }
            }
            vec.add(v);
        }
        double[][] sim = new double[m][m];
        double[] norm = new double[m];
        for (int i = 0; i < m; i++) {
            double s = 0;
            for (int c : vec.get(i).values()) {
                s += c * c;
            }
            norm[i] = Math.sqrt(s);
        }
        for (int i = 0; i < m; i++) {
            for (int j = i + 1; j < m; j++) {
                Map<String, Integer> a = vec.get(i);
                Map<String, Integer> b = vec.get(j);
                if (a.size() > b.size()) {
                    Map<String, Integer> t = a;
                    a = b;
                    b = t;
                }
                double dot = 0;
                for (Map.Entry<String, Integer> e : a.entrySet()) {
                    Integer o = b.get(e.getKey());
                    if (o != null) {
                        dot += e.getValue() * o;
                    }
                }
                double s = norm[i] * norm[j] == 0 ? 0 : dot / (norm[i] * norm[j]);
                sim[i][j] = s;
                sim[j][i] = s;
            }
        }
        double[] rank = new double[m];
        java.util.Arrays.fill(rank, 1.0 / m);
        double[] out = new double[m];
        double[] rowSum = new double[m];
        for (int j = 0; j < m; j++) {
            for (int k = 0; k < m; k++) {
                rowSum[j] += sim[j][k];
            }
        }
        for (int it = 0; it < 30; it++) {
            for (int i = 0; i < m; i++) {
                double s = 0;
                for (int j = 0; j < m; j++) {
                    if (sim[j][i] > 0 && rowSum[j] > 0) {
                        s += sim[j][i] / rowSum[j] * rank[j];
                    }
                }
                out[i] = 0.15 / m + 0.85 * s;
            }
            System.arraycopy(out, 0, rank, 0, m);
        }
        // position prior: the first sentences of a text often state its point
        for (int i = 0; i < m; i++) {
            rank[i] *= 1 + 0.3 * Math.exp(-i / 8.0);
        }
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            idx.add(i);
        }
        final double[] r = rank;
        idx.sort((a, b) -> Double.compare(r[b], r[a]));
        List<Integer> chosen = new ArrayList<>(idx.subList(0, Math.min(n, m)));
        Collections.sort(chosen);
        List<String[]> res = new ArrayList<>();
        for (int i : chosen) {
            res.add(new String[] { String.valueOf(sentPage.get(i) + 1), sentences.get(i) });
        }
        return res;
    }

    /* ------------------------------------------------------------- entities */

    private void findEntities(List<String> pages) {
        Map<String, Integer> seen = new TreeMap<>();
        for (int p = 0; p < pages.size(); p++) {
            String t = pages.get(p);
            add(t, p, EMAIL, "Email", seen);
            add(t, p, URL, "Link", seen);
            add(t, p, DOI, "DOI", seen);
            add(t, p, ARXIV, "arXiv", seen);
            add(t, p, DATE, "Date", seen);
            add(t, p, MONEY, "Amount", seen);
            add(t, p, QUANTITY, "Quantity", seen);
            if (entities.size() > 600) {
                break;
            }
        }
    }

    private void add(String t, int page, Pattern pat, String kind, Map<String, Integer> seen) {
        Matcher m = pat.matcher(t);
        while (m.find()) {
            String s = m.group().trim();
            if (s.length() < 2) {
                continue;
            }
            String k = kind + "|" + s;
            if (seen.merge(k, 1, Integer::sum) > 1 && !kind.equals("Quantity")) {
                continue;
            }
            if (kind.equals("Quantity") && seen.get(k) > 1) {
                continue;
            }
            entities.add(new Entity(kind, s, page, m.start(), m.end()));
        }
    }

    private void findCaptions(List<String> pages) {
        for (int p = 0; p < pages.size(); p++) {
            Matcher m = CAPTION.matcher(pages.get(p));
            while (m.find()) {
                String kind = m.group(1).toLowerCase(Locale.ROOT).startsWith("tab") ? "Table" : m.group(1)
                    .toLowerCase(Locale.ROOT).startsWith("alg") ? "Algorithm" : m.group(1).toLowerCase(Locale.ROOT)
                    .startsWith("list") ? "Listing" : "Figure";
                captions.add(new Caption(kind, m.group(2), m.group(3).replace('\n', ' ').trim(), p, m.start()));
            }
        }
    }

    private void findReferences(List<String> pages) {
        boolean inRefs = false;
        for (String t : pages) {
            Matcher sec = REF_SECTION.matcher(t);
            int from = 0;
            if (!inRefs && sec.find()) {
                inRefs = true;
                from = sec.end();
            }
            if (!inRefs) {
                continue;
            }
            String part = t.substring(from);
            Matcher m = REF_ENTRY.matcher(part);
            boolean any = false;
            List<int[]> spans = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            while (m.find()) {
                spans.add(new int[] { m.start(), m.start(2) });
                labels.add(m.group(1));
                any = true;
            }
            if (!any) {
                m = REF_NUMBERED.matcher(part);
                while (m.find()) {
                    spans.add(new int[] { m.start(), m.start(2) });
                    labels.add(m.group(1));
                }
            }
            for (int i = 0; i < spans.size(); i++) {
                int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : Math.min(part.length(), spans.get(i)[1] + 600);
                String body = part.substring(spans.get(i)[1], end).replaceAll("-\\n(?=\\p{Ll})", "")
                                  .replaceAll("\\s+", " ").trim();
                references.putIfAbsent(labels.get(i), body);
            }
        }
    }

    /** The citation labels inside a "[...]" at a position, if any ("[3, 5-7]" gives 3, 5, 6, 7). */
    public static List<String> citationsAt(String text, int index) {
        List<String> out = new ArrayList<>();
        int open = text.lastIndexOf('[', index);
        int close = text.indexOf(']', index);
        if (open < 0 || close < 0 || close - open > 40 || text.lastIndexOf(']', index - 1) > open
            && text.lastIndexOf(']', index - 1) < index) {
            return out;
        }
        String inside = text.substring(open + 1, close);
        for (String part : inside.split("[,;]")) {
            String p = part.trim();
            Matcher r = Pattern.compile("(\\d+)\\s*[-–]\\s*(\\d+)").matcher(p);
            if (r.matches()) {
                int a = Integer.parseInt(r.group(1));
                int b = Integer.parseInt(r.group(2));
                for (int i = a; i <= b && i - a < 20; i++) {
                    out.add(String.valueOf(i));
                }
            } else if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }
}
