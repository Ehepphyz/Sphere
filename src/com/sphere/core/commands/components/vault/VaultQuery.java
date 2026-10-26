package com.sphere.components.vault;

import com.sphere.components.editor.FuzzyMatcher;
import com.sphere.components.editor.LanguageSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The search box's language.
 *
 * Plain words are matched fuzzily against the name, then against the path, the
 * description, the tags, the functions, what is imported or included, and last
 * the content itself, each worth less than the one before. Filters narrow:
 *
 *   lang:py   #plot   tag:mg5   uses:TH1F   fn:analyse   in:project   in:MG5Workflow
 *   is:macro  is:snippet  is:twin  is:issue  is:pinned  is:args  is:plot
 *
 * A leading minus turns any of them, or a word, into an exclusion. With no
 * words, the order is the user's own: pinned first, then what was run most
 * lately and most often, then what changed last.
 */
public final class VaultQuery {

    public enum Lens { ALL, SNIPPETS, MACROS }

    public enum Sort {
        SMART("Smart"), NAME("A-Z"), RECENT("Changed"), USAGE("Most run");

        public final String label;

        Sort(String label) {
            this.label = label;
        }
    }

    public record Hit(VaultItem item, int score, int[] positions) {
    }

    private record Filter(String key, String value, boolean negated) {
    }

    private final List<String> terms = new ArrayList<>();
    private final List<String> excluded = new ArrayList<>();
    private final List<Filter> filters = new ArrayList<>();
    private final String text;

    private VaultQuery(String text) {
        this.text = text == null ? "" : text.strip();
    }

    public static VaultQuery parse(String text) {
        final VaultQuery q = new VaultQuery(text);
        for (String raw : q.text.split("\\s+")) {
            if (raw.isEmpty()) continue;
            boolean negated = raw.length() > 1 && raw.startsWith("-");
            final String token = negated ? raw.substring(1) : raw;
            if (token.length() > 1 && token.startsWith("#")) {
                q.filters.add(new Filter("tag", token.substring(1).toLowerCase(Locale.ROOT), negated));
                continue;
            }
            final int colon = token.indexOf(':');
            if (colon > 0 && colon < token.length() - 1) {
                final String key = token.substring(0, colon).toLowerCase(Locale.ROOT);
                if (Set.of("lang", "tag", "uses", "use", "fn", "in", "is", "kind").contains(key)) {
                    q.filters.add(new Filter(key.equals("use") ? "uses" : key.equals("kind") ? "is" : key,
                        token.substring(colon + 1), negated));
                    continue;
                }
            }
            (negated ? q.excluded : q.terms).add(token.toLowerCase(Locale.ROOT));
        }
        return q;
    }

    public boolean hasWords() {
        return !terms.isEmpty();
    }

    public boolean isEmpty() {
        return terms.isEmpty() && excluded.isEmpty() && filters.isEmpty();
    }

    public String text() {
        return text;
    }

    /** The first plain word, which the list highlights in the names. */
    public String firstWord() {
        return terms.isEmpty() ? "" : terms.get(0);
    }

    // ---- running ------------------------------------------------------------

    public List<Hit> run(List<VaultItem> items, Lens lens, Set<String> families, Sort sort, VaultIndex index) {
        final long now = System.currentTimeMillis();
        final VaultState state = index.state();
        final List<Hit> hits = new ArrayList<>();
        for (VaultItem item : items) {
            if (lens == Lens.SNIPPETS && item.kind != VaultItem.Kind.SNIPPET) continue;
            if (lens == Lens.MACROS && item.kind != VaultItem.Kind.MACRO) continue;
            if (families != null && !families.isEmpty() && !families.contains(family(item))) continue;
            if (!passes(item, index, state)) continue;

            int score = 0;
            boolean all = true;
            for (String term : terms) {
                final int s = score(item, term);
                if (s == FuzzyMatcher.NO_MATCH) {
                    all = false;
                    break;
                }
                score += s;
            }
            if (!all) continue;
            boolean veto = false;
            for (String term : excluded) {
                if (score(item, term) != FuzzyMatcher.NO_MATCH) {
                    veto = true;
                    break;
                }
            }
            if (veto) continue;

            final double use = state.frecency(item.key(), now);
            final boolean pinned = state.pinned(item.key());
            if (terms.isEmpty()) {
                // Nothing typed: the user's own order.
                score = (pinned ? 1_000_000 : 0) + (int) Math.round(use * 1000);
            } else {
                score += (int) Math.round(60 * Math.log1p(use)) + (pinned ? 40 : 0);
            }

            int[] positions = null;
            if (!terms.isEmpty()) {
                final String first = terms.get(0);
                final int[] p = new int[first.length()];
                if (FuzzyMatcher.score(first, item.name(), p) != FuzzyMatcher.NO_MATCH) positions = p;
            }
            hits.add(new Hit(item, score, positions));
        }

        Comparator<Hit> order = switch (sort) {
            case NAME -> Comparator.comparing((Hit h) -> h.item().relative.toLowerCase(Locale.ROOT));
            case RECENT -> Comparator.comparingLong((Hit h) -> -h.item().modified);
            case USAGE -> Comparator.comparingInt((Hit h) -> -state.runs(h.item().key()))
                .thenComparingLong(h -> -state.lastRun(h.item().key()));
            case SMART -> Comparator.comparingInt((Hit h) -> -h.score())
                .thenComparingLong(h -> -h.item().modified);
        };
        hits.sort(order.thenComparing(h -> h.item().relative.toLowerCase(Locale.ROOT)));
        return hits;
    }

    private boolean passes(VaultItem item, VaultIndex index, VaultState state) {
        for (Filter f : filters) {
            if (matches(item, f, index, state) == f.negated()) return false;
        }
        return true;
    }

    private static boolean matches(VaultItem item, Filter f, VaultIndex index, VaultState state) {
        final String v = f.value().toLowerCase(Locale.ROOT);
        final VaultAnalysis info = item.info;
        return switch (f.key()) {
            case "lang" -> languageMatches(item, v);
            case "tag" -> {
                for (String tag : info.tags) if (tag.startsWith(v)) yield true;
                yield false;
            }
            case "uses" -> {
                for (String use : info.uses) if (use.toLowerCase(Locale.ROOT).startsWith(v)) yield true;
                yield false;
            }
            case "fn" -> {
                for (String fn : info.functions) if (fn.toLowerCase(Locale.ROOT).contains(v)) yield true;
                yield false;
            }
            case "in" -> switch (v) {
                case "project" -> item.layer == VaultItem.Layer.PROJECT;
                case "global" -> item.layer == VaultItem.Layer.GLOBAL;
                default -> item.folder().toLowerCase(Locale.ROOT).contains(v);
            };
            case "is" -> switch (v) {
                case "macro", "macros" -> item.kind == VaultItem.Kind.MACRO;
                case "snippet", "snippets" -> item.kind == VaultItem.Kind.SNIPPET;
                case "twin", "twins", "dup", "duplicate" -> !index.twinsOf(item).isEmpty();
                case "issue", "issues", "broken", "warning" -> info.hasProblems();
                case "pinned", "pin", "star" -> state.pinned(item.key());
                case "args", "params" -> !info.params.isEmpty();
                case "plot", "plots", "draws" -> info.draws;
                case "runnable", "run" -> VaultRunner.runnable(item);
                case "root" -> item.isRootMacro();
                case "empty" -> info.lines == 0;
                default -> false;
            };
            default -> true;
        };
    }

    private static boolean languageMatches(VaultItem item, String v) {
        final LanguageSpec spec = item.info.language;
        return switch (v) {
            case "py", "python" -> spec == LanguageSpec.PYTHON;
            case "root", "macro", "aclic" -> item.isRootMacro();
            case "c", "cpp", "c++", "cxx", "cc" -> spec == LanguageSpec.CPP && !item.isRootMacro();
            case "jl", "julia" -> spec == LanguageSpec.JULIA;
            case "f", "f90", "fortran" -> spec == LanguageSpec.FORTRAN;
            case "js", "javascript", "node" -> spec == LanguageSpec.JAVASCRIPT;
            case "sh", "bash", "shell" -> spec == LanguageSpec.SHELL;
            case "md", "markdown" -> spec == LanguageSpec.MARKDOWN;
            default -> spec.id.equalsIgnoreCase(v) || spec.tag.equalsIgnoreCase(v)
                || family(item).equalsIgnoreCase(v);
        };
    }

    /**
     * How well one word matches, NO_MATCH when it does not. The name is worth
     * most, the content least: a hit there says the word is used, not that the
     * file is about it.
     */
    static int score(VaultItem item, String term) {
        int best = FuzzyMatcher.NO_MATCH;
        final int name = FuzzyMatcher.score(term, item.name(), null);
        if (name != FuzzyMatcher.NO_MATCH) best = name + 220;
        final int path = FuzzyMatcher.score(term, item.relative, null);
        if (path != FuzzyMatcher.NO_MATCH) best = Math.max(best, (int) (path * 0.8) + 120);

        final VaultAnalysis info = item.info;
        for (String tag : info.tags) {
            if (tag.equals(term)) best = Math.max(best, 330);
            else if (tag.startsWith(term)) best = Math.max(best, 270);
        }
        if (info.title.toLowerCase(Locale.ROOT).contains(term)) best = Math.max(best, 260);
        else if (info.description.toLowerCase(Locale.ROOT).contains(term)) best = Math.max(best, 210);
        for (String fn : info.functions) {
            if (fn.toLowerCase(Locale.ROOT).contains(term)) {
                best = Math.max(best, fn.equalsIgnoreCase(term) ? 300 : 230);
            }
        }
        for (String use : info.uses) {
            if (use.toLowerCase(Locale.ROOT).startsWith(term)) best = Math.max(best, 220);
        }
        for (VaultParam p : info.params) {
            if (p.name().toLowerCase(Locale.ROOT).contains(term)) best = Math.max(best, 160);
        }
        if (best == FuzzyMatcher.NO_MATCH && term.length() >= 2 && info.text.contains(term)) {
            best = 110;
        }
        return best;
    }

    // ---- families -------------------------------------------------------------

    /**
     * The short name the badge and the language chips use. A ROOT macro is ROOT
     * rather than C++: it runs in the interpreter, through .x, and that is what
     * matters when choosing it.
     */
    public static String family(VaultItem item) {
        if (item.isRootMacro()) return "ROOT";
        final LanguageSpec spec = item.info.language;
        if (spec == LanguageSpec.CPP && item.info.header) return "H";
        if (spec == LanguageSpec.NONE) return "TXT";
        return spec.tag;
    }

    /** The families present, most common first. */
    public static List<String> families(List<VaultItem> items, Lens lens) {
        final java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (VaultItem item : items) {
            if (lens == Lens.SNIPPETS && item.kind != VaultItem.Kind.SNIPPET) continue;
            if (lens == Lens.MACROS && item.kind != VaultItem.Kind.MACRO) continue;
            counts.merge(family(item), 1, Integer::sum);
        }
        final List<String> list = new ArrayList<>(counts.keySet());
        list.sort(Comparator.comparingInt((String f) -> -counts.get(f)).thenComparing(f -> f));
        return list;
    }

    public static int count(List<VaultItem> items, Lens lens, String family) {
        int n = 0;
        for (VaultItem item : items) {
            if (lens == Lens.SNIPPETS && item.kind != VaultItem.Kind.SNIPPET) continue;
            if (lens == Lens.MACROS && item.kind != VaultItem.Kind.MACRO) continue;
            if (family == null || family.equals(family(item))) n++;
        }
        return n;
    }

    /** Every tag in use, for completion in the search box. */
    public static Set<String> allTags(List<VaultItem> items) {
        final Set<String> tags = new LinkedHashSet<>();
        for (VaultItem item : items) tags.addAll(item.info.tags);
        return tags;
    }
}
