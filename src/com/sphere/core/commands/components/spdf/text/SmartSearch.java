package com.sphere.components.spdf.text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Search that finds what people mean. The smart mode ignores case, accents
 * ("naive" finds "naïve"), ligatures (ﬁ is fi), quotes and dash
 * variants, line breaks and words split by a hyphen at a line end
 * ("intelli-gent"). The fuzzy mode tolerates typos (one in five letters);
 * the regex mode takes a Java regular expression.
 */
public final class SmartSearch {

    private SmartSearch() {
    }

    public enum Mode { SMART, EXACT, FUZZY, REGEX }

    /** A match: its page, its range in that page's text, a snippet around it. */
    public record Hit(int page, int start, int end, String context, int errors) {
    }

    /** Text normalized for matching, with each normalized char's index in the original. */
    public record Folded(String text, int[] origin) {
    }

    public static Folded fold(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int[] map = new int[s.length() * 3 + 1];
        int n = 0;
        boolean lastSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // a hyphen ending a line, followed by a lowercase letter: the word goes on
            if ((c == '-' || c == '­') && i + 2 < s.length() && s.charAt(i + 1) == '\n'
                && Character.isLowerCase(s.charAt(i + 2))) {
                i++;
                continue;
            }
            String out = fold(c);
            for (int k = 0; k < out.length(); k++) {
                char o = out.charAt(k);
                if (Character.isWhitespace(o)) {
                    if (lastSpace) {
                        continue;
                    }
                    o = ' ';
                    lastSpace = true;
                } else {
                    lastSpace = false;
                }
                b.append(o);
                if (n >= map.length) {
                    map = java.util.Arrays.copyOf(map, map.length * 2);
                }
                map[n++] = i;
            }
        }
        return new Folded(b.toString(), java.util.Arrays.copyOf(map, n));
    }

    private static String fold(char c) {
        switch (c) {
            case 'ﬀ': return "ff";
            case 'ﬁ': return "fi";
            case 'ﬂ': return "fl";
            case 'ﬃ': return "ffi";
            case 'ﬄ': return "ffl";
            case '‘': case '’': case '‚': case '′': case '`': return "'";
            case '“': case '”': case '„': case '«': case '»': return "\"";
            case '‐': case '‑': case '‒': case '–': case '—': case '−': return "-";
            case ' ': case ' ': case ' ': case '\t': case '\n': case '\r': return " ";
            case 'œ': return "oe";
            case 'Œ': return "oe";
            case 'æ': case 'Æ': return "ae";
            case 'ß': return "ss";
            case 'µ': return "μ";
            default:
                break;
        }
        if (c < 128) {
            return String.valueOf(Character.toLowerCase(c));
        }
        String d = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
        StringBuilder b = new StringBuilder(2);
        for (int i = 0; i < d.length(); i++) {
            char x = d.charAt(i);
            if (Character.getType(x) != Character.NON_SPACING_MARK) {
                b.append(Character.toLowerCase(x));
            }
        }
        return b.length() == 0 ? String.valueOf(c) : b.toString();
    }

    /** Searches pages; texts are asked for one page at a time (they may be extracted as needed). */
    public static List<Hit> search(int pageCount, IntFunction<String> pageText, String query, Mode mode, int max) {
        List<Hit> hits = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return hits;
        }
        Pattern regex = null;
        if (mode == Mode.REGEX) {
            try {
                regex = Pattern.compile(query, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.MULTILINE);
            } catch (PatternSyntaxException e) {
                return hits;
            }
        }
        String fq = fold(query.trim()).text();
        for (int p = 0; p < pageCount && hits.size() < max; p++) {
            String t = pageText.apply(p);
            if (t == null || t.isEmpty()) {
                continue;
            }
            switch (mode) {
                case EXACT -> {
                    int i = 0;
                    while ((i = t.indexOf(query, i)) >= 0 && hits.size() < max) {
                        hits.add(new Hit(p, i, i + query.length(), context(t, i, i + query.length()), 0));
                        i += Math.max(1, query.length());
                    }
                }
                case REGEX -> {
                    Matcher m = regex.matcher(t);
                    while (m.find() && hits.size() < max) {
                        if (m.end() > m.start()) {
                            hits.add(new Hit(p, m.start(), m.end(), context(t, m.start(), m.end()), 0));
                        }
                    }
                }
                case SMART -> {
                    Folded f = fold(t);
                    int i = 0;
                    while ((i = f.text().indexOf(fq, i)) >= 0 && hits.size() < max) {
                        int s = f.origin()[i];
                        int e = f.origin()[i + fq.length() - 1] + 1;
                        hits.add(new Hit(p, s, e, context(t, s, e), 0));
                        i += Math.max(1, fq.length());
                    }
                }
                case FUZZY -> fuzzy(t, fq, p, hits, max);
                default -> { }
            }
        }
        return hits;
    }

    /** Approximate matching (Sellers): every place the query fits with at most k edits. */
    private static void fuzzy(String t, String fq, int page, List<Hit> hits, int max) {
        Folded f = fold(t);
        String s = f.text();
        int m = fq.length();
        if (m == 0) {
            return;
        }
        int k = Math.max(1, m / 5);
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        int[] startPrev = new int[m + 1];
        int[] startCur = new int[m + 1];
        for (int i = 0; i <= m; i++) {
            prev[i] = i;
        }
        int lastEnd = -10;
        for (int j = 1; j <= s.length(); j++) {
            cur[0] = 0;
            startCur[0] = j;
            char c = s.charAt(j - 1);
            for (int i = 1; i <= m; i++) {
                int sub = prev[i - 1] + (fq.charAt(i - 1) == c ? 0 : 1);
                int del = prev[i] + 1;
                int ins = cur[i - 1] + 1;
                int best = sub;
                int st = i == 1 ? j - 1 : startPrev[i - 1];
                if (del < best) {
                    best = del;
                    st = startPrev[i];
                }
                if (ins < best) {
                    best = ins;
                    st = startCur[i - 1];
                }
                cur[i] = best;
                startCur[i] = st;
            }
            if (cur[m] <= k) {
                // keep the best end of a run of matching ends
                boolean better = j + 1 > s.length() || true;
                if (j - lastEnd > m / 2 + 1 && better) {
                    int ns = Math.max(0, startCur[m]);
                    int ne = j;
                    if (ne > ns) {
                        int os = f.origin()[Math.min(ns, f.origin().length - 1)];
                        int oe = f.origin()[Math.min(ne - 1, f.origin().length - 1)] + 1;
                        hits.add(new Hit(page, os, oe, context(t, os, oe), cur[m]));
                        if (hits.size() >= max) {
                            return;
                        }
                    }
                    lastEnd = j;
                } else if (!hits.isEmpty() && hits.get(hits.size() - 1).page() == page
                           && cur[m] < hits.get(hits.size() - 1).errors()) {
                    int ns = Math.max(0, startCur[m]);
                    int os = f.origin()[Math.min(ns, f.origin().length - 1)];
                    int oe = f.origin()[Math.min(j - 1, f.origin().length - 1)] + 1;
                    hits.set(hits.size() - 1, new Hit(page, os, oe, context(t, os, oe), cur[m]));
                    lastEnd = j;
                }
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
            tmp = startPrev;
            startPrev = startCur;
            startCur = tmp;
        }
    }

    /** About sixty characters around a match, on one line. */
    public static String context(String t, int s, int e) {
        int a = Math.max(0, s - 30);
        int b = Math.min(t.length(), e + 30);
        String pre = t.substring(a, s).replace('\n', ' ');
        String mid = t.substring(s, e).replace('\n', ' ');
        String post = t.substring(e, b).replace('\n', ' ');
        return (a > 0 ? "…" : "") + pre + "\u0001" + mid + "\u0002" + post + (b < t.length() ? "…" : "");
    }
}
