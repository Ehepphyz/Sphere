package com.sphere.core.python.jupyterlab;

import com.sphere.core.python.jupyterlab.JupylabXeditor.Output;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a cell's new run changed in what it shows: the numbers that moved and by how
 * much ("chi2 1.234 -> 1.198, -2.9%"), the lines that differ, whether a plot changed.
 * After an edit, "the same output" is news too: the edit changed nothing visible.
 *
 * A run is kept as its text -- streams and results as the console shows them, after
 * carriage returns and colors -- and its rich outputs (plots, HTML) as fingerprints.
 * Two runs whose texts differ only in their numbers are compared number by number,
 * each named by the word before it on its line; otherwise line by line.
 */
final class OutputDelta {

    private OutputDelta() {
    }

    /**
     * One run's outputs: the code that made them, their text, their rich outputs' fingerprints.
     *
     * @param epoch which kernel made them (the editor counts kernels); -1: read from the saved notebook
     */
    record Snapshot(String source, String text, List<String> rich, int epoch) {
    }

    enum Kind { SAME, NUMBERS, TEXT }

    /** A number that moved: what names it, as it read before and after. */
    record Change(String label, String before, String after, double from, double to) {
        /** The change relative to the old value; infinite from zero. */
        double magnitude() {
            if (from == 0) {
                return to == 0 ? 0 : Double.POSITIVE_INFINITY;
            }
            return Math.abs((to - from) / from);
        }
    }

    /**
     * @param changes the numbers that moved, in the order they appear (NUMBERS)
     * @param added   lines only the new run shows (TEXT)
     * @param removed lines only the old run showed (TEXT)
     * @param diff    the line diff, each line prefixed with ' ', '+' or '-' (TEXT), possibly cut
     * @param richChanged a plot or another rich output differs
     */
    record Delta(Kind kind, List<Change> changes, int added, int removed, List<String> diff, boolean richChanged, int rich) {
        boolean nothing() {
            return kind == Kind.SAME && !richChanged;
        }
    }

    // ---- Snapshots ---------------------------------------------------------

    static Snapshot snapshot(String source, List<Output> outputs, int epoch) {
        StringBuilder text = new StringBuilder();
        List<String> rich = new ArrayList<>();
        for (Output o : outputs) {
            if (o == null || o.output_type == null) {
                continue;
            }
            switch (o.output_type) {
                case "stream" -> text.append(console(OutputRenderer.text(o.text).toString()));
                case "execute_result", "display_data" -> {
                    String image = null;
                    for (String key : List.of("image/png", "image/jpeg", "image/svg+xml", "image/gif")) {
                        if (o.data.get(key) != null) {
                            image = key;
                            break;
                        }
                    }
                    Object plain = o.data.get("text/plain");
                    if (image != null) {
                        rich.add(image + ":" + fingerprint(OutputRenderer.text(o.data.get(image)).toString()));
                    } else if (plain != null) {
                        String t = console(OutputRenderer.text(plain).toString());
                        text.append(t);
                        if (!t.endsWith("\n")) {
                            text.append('\n');
                        }
                    } else {
                        for (Map.Entry<String, Object> e : o.data.entrySet()) {
                            rich.add(e.getKey() + ":" + fingerprint(OutputRenderer.text(e.getValue()).toString()));
                        }
                    }
                }
                case "error" -> text.append(o.ename).append(": ").append(AnsiText.strip(o.evalue == null ? "" : o.evalue)).append('\n');
                default -> { }
            }
        }
        String t = text.toString();
        if (t.length() > 400_000) {
            t = t.substring(0, 200_000) + "\n…\n" + t.substring(t.length() - 200_000);
        }
        return new Snapshot(source, t, List.copyOf(rich), epoch);
    }

    /** What a console shows of a stream: colors gone, a line rewritten after \r kept as its last state. */
    static String console(String raw) {
        String s = AnsiText.strip(raw).replace("\r\n", "\n");
        if (s.indexOf('\r') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        for (String line : s.split("\n", -1)) {
            int cr = line.lastIndexOf('\r');
            String shown = cr < 0 ? line : line.substring(cr + 1);
            if (shown.isEmpty() && cr >= 0) {
                shown = line.substring(0, cr).substring(Math.max(0, line.substring(0, cr).lastIndexOf('\r') + 1));
            }
            out.append(shown).append('\n');
        }
        out.setLength(Math.max(0, out.length() - 1));
        return out.toString();
    }

    /** FNV-1a over the characters, with the length: two different plots never look alike in practice. */
    static String fingerprint(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return Long.toHexString(h) + "/" + s.length();
    }

    // ---- Comparison --------------------------------------------------------

    private static final Pattern NUMBER =
        Pattern.compile("(?<![A-Za-z0-9_.])[-+]?(?:\\d+\\.\\d*|\\.\\d+|\\d+)(?:[eE][-+]?\\d+)?(?![A-Za-z0-9_])");
    private static final char HOLE = '\u0000';

    /** A text with its numbers taken out: what remains, the numbers, and where each stood. */
    record Parsed(String template, List<String> numbers, List<Integer> at) {
    }

    static Parsed parse(String text) {
        StringBuilder t = new StringBuilder(text.length());
        List<String> numbers = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        Matcher m = NUMBER.matcher(text);
        int last = 0;
        while (m.find()) {
            t.append(text, last, m.start());
            at.add(t.length());
            t.append(HOLE);
            numbers.add(m.group());
            last = m.end();
        }
        t.append(text, last, text.length());
        return new Parsed(t.toString(), numbers, at);
    }

    static Delta compare(Snapshot before, Snapshot after) {
        boolean richChanged = !before.rich().equals(after.rich());
        int rich = after.rich().size();
        if (before.text().equals(after.text())) {
            return new Delta(Kind.SAME, List.of(), 0, 0, List.of(), richChanged, rich);
        }
        Parsed a = parse(before.text());
        Parsed b = parse(after.text());
        if (a.template().equals(b.template()) && a.numbers().size() == b.numbers().size()) {
            List<Change> changes = new ArrayList<>();
            Map<Integer, String[]> labels = labels(b);
            for (int i = 0; i < b.numbers().size(); i++) {
                String x = a.numbers().get(i);
                String y = b.numbers().get(i);
                if (x.equals(y)) {
                    continue;
                }
                double from = value(x);
                double to = value(y);
                if (from == to) {
                    continue; // written another way, the same number
                }
                String[] l = labels.get(i);
                changes.add(new Change(l == null ? "value " + (i + 1) : l[0], x, y, from, to));
            }
            if (changes.isEmpty()) {
                return new Delta(Kind.SAME, List.of(), 0, 0, List.of(), richChanged, rich);
            }
            return new Delta(Kind.NUMBERS, List.copyOf(changes), 0, 0, List.of(), richChanged, rich);
        }
        LineDiff d = diff(before.text(), after.text());
        return new Delta(Kind.TEXT, List.of(), d.added(), d.removed(), d.lines(), richChanged, rich);
    }

    private static double value(String token) {
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * What names each number: the last word before it on its line ("chi2 = 1.23" -> chi2), indexed
     * when the word heads several numbers ("params [1.0 2.0]" -> params[0], params[1]), else the line.
     */
    static Map<Integer, String[]> labels(Parsed p) {
        String t = p.template();
        int n = p.numbers().size();
        String[] heads = new String[n];
        // the numbers grouped by the word heading them on their line (or by the line, when no word does)
        Map<Long, List<Integer>> groups = new HashMap<>();
        int line = 1;
        String word = null;
        long serial = 0;
        int idx = 0;
        for (int k = 0; k < t.length() && idx < n; k++) {
            char c = t.charAt(k);
            if (c == '\n') {
                line++;
                word = null;
            } else if (c == HOLE) {
                heads[idx] = word == null ? "line " + line : word;
                groups.computeIfAbsent(word == null ? -line : serial, g -> new ArrayList<>()).add(idx);
                idx++;
            } else if (Character.isLetter(c) || c == '_') {
                int s = k;
                while (k + 1 < t.length()) {
                    char d = t.charAt(k + 1);
                    boolean joins = (d == '.' || d == '/') && k + 2 < t.length()
                                    && (Character.isLetter(t.charAt(k + 2)) || t.charAt(k + 2) == '_');
                    if (Character.isLetterOrDigit(d) || d == '_' || joins) {
                        k++;
                    } else {
                        break;
                    }
                }
                word = t.substring(s, k + 1);
                serial++;
            }
        }
        Map<Integer, String[]> out = new HashMap<>();
        for (List<Integer> members : groups.values()) {
            for (int k = 0; k < members.size(); k++) {
                int i = members.get(k);
                out.put(i, new String[] { members.size() > 1 ? heads[i] + "[" + k + "]" : heads[i] });
            }
        }
        return out;
    }

    /** The lines only one side has, by longest common subsequence; counts alone when the texts are huge. */
    record LineDiff(int added, int removed, List<String> lines) {
    }

    static LineDiff diff(String before, String after) {
        String[] x = before.split("\n", -1);
        String[] y = after.split("\n", -1);
        int n = x.length;
        int m = y.length;
        if ((long) n * m > 1_000_000L) {
            Map<String, Integer> bag = new HashMap<>();
            for (String s : x) {
                bag.merge(s, 1, Integer::sum);
            }
            int added = 0;
            for (String s : y) {
                Integer k = bag.get(s);
                if (k == null || k == 0) {
                    added++;
                } else {
                    bag.put(s, k - 1);
                }
            }
            int removed = 0;
            for (int k : bag.values()) {
                removed += k;
            }
            return new LineDiff(added, removed, List.of());
        }
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = x[i].equals(y[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<String> lines = new ArrayList<>();
        int added = 0;
        int removed = 0;
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && x[i].equals(y[j])) {
                lines.add(" " + x[i]);
                i++;
                j++;
            } else if (j < m && (i >= n || lcs[i][j + 1] >= lcs[i + 1][j])) {
                lines.add("+" + y[j]);
                added++;
                j++;
            } else {
                lines.add("-" + x[i]);
                removed++;
                i++;
            }
        }
        return new LineDiff(added, removed, lines.size() > 3000 ? List.copyOf(lines.subList(0, 3000)) : List.copyOf(lines));
    }

    // ---- Words -------------------------------------------------------------

    /** "-2.9%", "+12%", "+0.003" from zero: how a change reads. */
    static String relative(Change c) {
        if (c.from() == 0 || Double.isNaN(c.from()) || Double.isNaN(c.to())) {
            double d = c.to() - c.from();
            return Double.isNaN(d) ? "" : (d >= 0 ? "+" : "") + compact(d);
        }
        double r = (c.to() - c.from()) / Math.abs(c.from()) * 100;
        if (Math.abs(r) < 0.01) {
            return r >= 0 ? "+<0.01%" : "-<0.01%";
        }
        String digits = Math.abs(r) >= 100 ? "%.0f" : Math.abs(r) >= 10 ? "%.1f" : "%.2f";
        return (r >= 0 ? "+" : "") + String.format(Locale.ROOT, digits, r) + "%";
    }

    static String compact(double v) {
        double a = Math.abs(v);
        if (a != 0 && (a < 1e-3 || a >= 1e6)) {
            return String.format(Locale.ROOT, "%.3g", v);
        }
        String s = String.format(Locale.ROOT, "%.4f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /** The changes worth naming first: the largest relative moves. */
    static List<Change> largest(List<Change> changes, int k) {
        List<Change> sorted = new ArrayList<>(changes);
        sorted.sort(Comparator.comparingDouble(Change::magnitude).reversed());
        return sorted.subList(0, Math.min(k, sorted.size()));
    }
}
