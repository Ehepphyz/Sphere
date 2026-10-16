package com.sphere.components.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Completion that reads the code instead of only its words. After "h->" it
 * offers what a TH1F can do because it saw "TH1F* h = new TH1F(...)"; after
 * "np." it offers numpy because it saw "import numpy as np"; after "self." it
 * offers the attributes the class assigns. Otherwise it ranks this file's
 * symbols, the language's keywords and builtins, snippets, and the words of
 * every open file, by how well they match what is typed and how often the
 * user picked them before.
 */
public final class SmartCompletion implements CompletionProvider {

    private static final int MAX_ITEMS = 80;
    /** Picks of this session: label → order of the last pick. */
    private static final Map<String, Integer> RECENT = new ConcurrentHashMap<>();
    private static final AtomicInteger PICKS = new AtomicInteger();

    private final Supplier<LanguageSpec> spec;
    private final Supplier<CodeInsight.Result> insight;
    private final Supplier<List<CodeInsight.Result>> others;

    public SmartCompletion(Supplier<LanguageSpec> spec, Supplier<CodeInsight.Result> insight,
                           Supplier<List<CodeInsight.Result>> others) {
        this.spec = spec;
        this.insight = insight;
        this.others = others;
    }

    /** Remembers a pick, so it ranks higher next time. */
    public static void recordUse(String label) {
        RECENT.put(label, PICKS.incrementAndGet());
        if (RECENT.size() > 500) {
            int cutoff = PICKS.get() - 400;
            RECENT.values().removeIf(v -> v < cutoff);
        }
    }

    private static int recency(String label) {
        Integer at = RECENT.get(label);
        if (at == null) {
            return 0;
        }
        int age = PICKS.get() - at;
        return Math.max(0, 45 - age);
    }

    // ---- Context -----------------------------------------------------------

    /** What precedes the word being typed: "h->", "np.", "TMath::", "obj.Get()->". */
    static final class Receiver {
        final String name;
        /** Set when the receiver is a call: the receiver of that call. */
        final Receiver callOwner;
        final String operator;

        Receiver(String name, Receiver callOwner, String operator) {
            this.name = name;
            this.callOwner = callOwner;
            this.operator = operator;
        }
    }

    @Override
    public boolean isMemberContext(String text, int offset) {
        return receiverAt(text, offset) != null;
    }

    /** Parses the receiver ending right before {@code start}, or null. */
    static Receiver receiverAt(String text, int start) {
        int k = start;
        String op;
        if (k >= 1 && text.charAt(k - 1) == '.') {
            op = ".";
            k -= 1;
        } else if (k >= 2 && text.startsWith("->", k - 2)) {
            op = "->";
            k -= 2;
        } else if (k >= 2 && text.startsWith("::", k - 2)) {
            op = "::";
            k -= 2;
        } else {
            return null;
        }
        return expression(text, k, op, 0);
    }

    private static Receiver expression(String text, int end, String op, int depth) {
        if (depth > 4 || end <= 0) {
            return null;
        }
        int k = end;
        if (text.charAt(k - 1) == ')') {
            // A call: find its '(' and the callee before it.
            int level = 0;
            int i = k - 1;
            for (; i >= 0 && k - i < 2000; i--) {
                char c = text.charAt(i);
                if (c == ')') {
                    level++;
                } else if (c == '(') {
                    level--;
                    if (level == 0) {
                        break;
                    }
                }
            }
            if (i <= 0) {
                return null;
            }
            int nameEnd = i;
            int nameStart = nameEnd;
            while (nameStart > 0 && isIdent(text.charAt(nameStart - 1))) {
                nameStart--;
            }
            if (nameStart == nameEnd) {
                return null;
            }
            String callee = text.substring(nameStart, nameEnd);
            Receiver owner = null;
            String ownerOp = null;
            if (nameStart >= 1 && text.charAt(nameStart - 1) == '.') {
                ownerOp = ".";
            } else if (nameStart >= 2 && text.startsWith("->", nameStart - 2)) {
                ownerOp = "->";
            } else if (nameStart >= 2 && text.startsWith("::", nameStart - 2)) {
                ownerOp = "::";
            }
            if (ownerOp != null) {
                owner = expression(text, nameStart - ownerOp.length(), ownerOp, depth + 1);
            }
            return new Receiver(callee, owner == null ? new Receiver("", null, "") : owner, op);
        }
        // Indexing a[0].x reads as a.
        if (text.charAt(k - 1) == ']') {
            int level = 0;
            int i = k - 1;
            for (; i >= 0 && k - i < 500; i--) {
                char c = text.charAt(i);
                if (c == ']') {
                    level++;
                } else if (c == '[') {
                    level--;
                    if (level == 0) {
                        break;
                    }
                }
            }
            if (i <= 0) {
                return null;
            }
            k = i;
        }
        int s = k;
        while (s > 0 && isIdent(text.charAt(s - 1))) {
            s--;
        }
        if (s == k) {
            return null;
        }
        String name = text.substring(s, k);
        // A qualified receiver: ROOT::Math:: reads as its last part with the rest known.
        if (s >= 2 && text.startsWith("::", s - 2) && op.equals("::")) {
            Receiver outer = expression(text, s - 2, "::", depth + 1);
            if (outer != null && outer.callOwner == null) {
                return new Receiver(outer.name + "::" + name, null, op);
            }
        }
        if (s >= 1 && text.charAt(s - 1) == '.' && op.equals(".")) {
            // os.path. → "os.path" when it is a known module path.
            Receiver outer = expression(text, s - 1, ".", depth + 1);
            if (outer != null && outer.callOwner == null && KnowledgeBase.knows(outer.name + "." + name)) {
                return new Receiver(outer.name + "." + name, null, op);
            }
        }
        return new Receiver(name, null, op);
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** The knowledge-base type a receiver stands for, or null. */
    static String typeOf(Receiver r, CodeInsight.Result res, int depth) {
        if (r == null || depth > 5) {
            return null;
        }
        if (r.callOwner != null) {
            if (r.callOwner.name.isEmpty()) {
                // A plain call: a constructor TH1F(...) or a known function.
                return KnowledgeBase.knows(r.name) ? r.name : null;
            }
            String ownerType = typeOf(r.callOwner, res, depth + 1);
            if (ownerType == null) {
                return null;
            }
            KnowledgeBase.Member m = KnowledgeBase.member(ownerType, r.name);
            return m == null || m.returns.isEmpty() ? null : KnowledgeBase.normalize(m.returns);
        }
        String name = r.name;
        if (res != null) {
            String alias = res.aliases.get(name);
            if (alias != null) {
                return alias;
            }
            String t = res.varTypes.get(name);
            if (t != null) {
                if (t.startsWith("class:")) {
                    t = t.substring(6);
                }
                String n = KnowledgeBase.normalize(t);
                if (KnowledgeBase.knows(n)) {
                    return n;
                }
            }
        }
        String global = KnowledgeBase.ROOT_GLOBALS.get(name);
        if (global != null) {
            return global;
        }
        if (KnowledgeBase.knows(name)) {
            return name;
        }
        if (name.startsWith("ROOT::") && KnowledgeBase.knows(name.substring(6))) {
            return name.substring(6);
        }
        return null;
    }

    // ---- Completion --------------------------------------------------------

    @Override
    public List<Item> complete(String text, int offset, String prefix) {
        LanguageSpec lang = spec.get();
        CodeInsight.Result res = insight.get();
        int start = offset - prefix.length();
        Receiver receiver = receiverAt(text, start);
        Map<String, Item> out = new LinkedHashMap<>();

        if (receiver != null && lang.dialect == LanguageSpec.Dialect.CODE) {
            members(receiver, res, lang, prefix, out);
        } else {
            general(lang, res, prefix, out);
        }

        List<Item> items = new ArrayList<>(out.values());
        items.sort((a, b) -> b.score != a.score ? Integer.compare(b.score, a.score)
                                                : a.label.compareToIgnoreCase(b.label));
        return items.size() > MAX_ITEMS ? new ArrayList<>(items.subList(0, MAX_ITEMS)) : items;
    }

    private void members(Receiver receiver, CodeInsight.Result res, LanguageSpec lang, String prefix,
                         Map<String, Item> out) {
        String type = typeOf(receiver, res, 0);
        if (type != null) {
            int order = 0;
            for (KnowledgeBase.Member m : KnowledgeBase.members(type)) {
                int[] pos = new int[prefix.length()];
                int fuzzy = FuzzyMatcher.score(prefix, m.name, pos);
                if (fuzzy == FuzzyMatcher.NO_MATCH) {
                    continue;
                }
                boolean callable = m.isCallable();
                boolean noArgs = m.params.equals("()");
                String insert = callable ? m.name + "()" : m.name;
                String detail = (callable ? m.params : "") + (m.returns.isEmpty() ? "" : "  → " + m.returns);
                String sep = lang == LanguageSpec.CPP || receiver.operator.equals("::") ? "::" : ".";
                String doc = m.owner + sep + m.signature()
                             + (m.doc.isEmpty() ? "" : "\n" + m.doc);
                EditorIcons.Kind kind = callable ? EditorIcons.Kind.METHOD
                        : m.returns.equals("module") || m.returns.equals("namespace") ? EditorIcons.Kind.MODULE
                        : m.returns.equals("class") ? EditorIcons.Kind.CLASS : EditorIcons.Kind.FIELD;
                Item item = new Item(insert, m.name, detail, kind, doc, callable && !noArgs ? 1 : 0, null);
                item.score = fuzzy + 80 - Math.min(20, order / 4) + recency(m.name)
                             + (m.owner.equals(type) ? 6 : 0);
                item.matches = pos;
                out.putIfAbsent(m.name, item);
                order++;
            }
        }
        // What the buffer itself does with this receiver: self.x, cfg.path...
        if (res != null && receiver.callOwner == null) {
            Set<String> seen = res.members.get(receiver.name);
            if (seen != null) {
                for (String name : seen) {
                    addWord(out, prefix, name, EditorIcons.Kind.PROPERTY, "used on " + receiver.name, 62,
                            res.words.getOrDefault(name, 1));
                }
            }
            // Methods of a class when the receiver is self or the class itself.
            for (DocSymbol s : res.symbols) {
                if (s.container != null && (receiver.name.equals("self") || receiver.name.equals("this")
                        || receiver.name.equals(s.container)) && (s.kind == DocSymbol.Kind.METHOD
                        || s.kind == DocSymbol.Kind.FIELD || s.kind == DocSymbol.Kind.CONSTRUCTOR)) {
                    addSymbol(out, prefix, s, 66);
                }
            }
        }
        // Anything ever written after a dot, as a last resort for an unknown receiver.
        if (type == null && res != null && out.size() < 10) {
            int added = 0;
            for (Set<String> names : res.members.values()) {
                for (String name : names) {
                    if (added++ > 400) {
                        break;
                    }
                    addWord(out, prefix, name, EditorIcons.Kind.WORD, "member", 18,
                            res.words.getOrDefault(name, 1));
                }
            }
        }
    }

    private void general(LanguageSpec lang, CodeInsight.Result res, String prefix, Map<String, Item> out) {
        boolean ci = lang.caseInsensitive;
        String typed = ci ? prefix.toLowerCase(Locale.ROOT) : prefix;

        // Snippets first in their own slots: "for" the snippet and "for" the keyword both show.
        for (Snippets.Snippet sn : Snippets.forLanguage(lang)) {
            int[] pos = new int[typed.length()];
            int fuzzy = FuzzyMatcher.score(typed, sn.trigger, pos);
            if (fuzzy == FuzzyMatcher.NO_MATCH || (typed.isEmpty())) {
                continue;
            }
            Item item = new Item(sn.trigger, sn.trigger, sn.description, EditorIcons.Kind.SNIPPET,
                                 sn.preview(), 0, sn);
            item.score = fuzzy + 48 + (sn.trigger.startsWith(typed) ? 12 : 0) + recency("snippet:" + sn.trigger);
            item.matches = pos;
            out.put("snippet:" + sn.trigger, item);
        }

        if (res != null) {
            for (DocSymbol s : res.symbols) {
                if (s.kind == DocSymbol.Kind.HEADING || s.kind == DocSymbol.Kind.SECTION) {
                    continue;
                }
                addSymbol(out, prefix, s, 60);
            }
        }
        for (String k : lang.keywords) {
            addFixed(out, typed, k, EditorIcons.Kind.KEYWORD, "keyword", 40, null);
        }
        for (String t : lang.types) {
            KnowledgeBase.Member ctor = KnowledgeBase.member("ROOT", t);
            String doc = ctor != null ? ctor.signature() + "\n" + ctor.doc : null;
            addFixed(out, prefix, t, EditorIcons.Kind.TYPE, KnowledgeBase.knows(t) ? "class" : "type", 38, doc);
        }
        for (String c : lang.constants) {
            addFixed(out, prefix, c, EditorIcons.Kind.CONSTANT, "constant", 34, null);
        }
        for (String b : lang.builtins) {
            boolean command = lang == LanguageSpec.CMAKE;
            Item item = scored(typed, b, command ? b + "()" : b, EditorIcons.Kind.FUNCTION,
                               command ? "command" : "builtin", 44, command ? 1 : 0, null);
            if (item != null) {
                out.putIfAbsent(b, item);
            }
        }
        // Python modules imported under an alias: np, plt.
        if (res != null) {
            for (Map.Entry<String, String> a : res.aliases.entrySet()) {
                addFixed(out, prefix, a.getKey(), EditorIcons.Kind.MODULE, a.getValue(), 50, null);
            }
        }
        // Words of this file, then of the other open files.
        if (res != null && !prefix.isEmpty()) {
            for (Map.Entry<String, Integer> w : res.words.entrySet()) {
                String word = w.getKey();
                int count = w.getValue();
                if (word.equals(prefix) && count <= 1) {
                    continue; // the word being typed
                }
                addWord(out, prefix, word, guessKind(word, res), "", 22, count);
            }
        }
        if (!prefix.isEmpty() && prefix.length() >= 2 && others != null) {
            for (CodeInsight.Result other : others.get()) {
                if (other == res) {
                    continue;
                }
                int added = 0;
                for (Map.Entry<String, Integer> w : other.words.entrySet()) {
                    if (added > 2000) {
                        break;
                    }
                    if (addWord(out, prefix, w.getKey(), EditorIcons.Kind.WORD, "other file", 8, w.getValue())) {
                        added++;
                    }
                }
                for (DocSymbol s : other.symbols) {
                    if (s.kind.isType() || s.kind.isCallable()) {
                        addSymbol(out, prefix, s, 30);
                    }
                }
            }
        }
    }

    private static EditorIcons.Kind guessKind(String word, CodeInsight.Result res) {
        if (res.varTypes.containsKey(word)) {
            return EditorIcons.Kind.VARIABLE;
        }
        return EditorIcons.Kind.WORD;
    }

    private void addSymbol(Map<String, Item> out, String prefix, DocSymbol s, int base) {
        int[] pos = new int[prefix.length()];
        int fuzzy = FuzzyMatcher.score(prefix, s.name, pos);
        if (fuzzy == FuzzyMatcher.NO_MATCH || (s.name.equals(prefix))) {
            return;
        }
        boolean callable = s.kind.isCallable();
        boolean noArgs = s.detail.equals("()");
        String insert = s.name;
        int back = 0;
        if (callable && s.detail.startsWith("(") && spec.get().isCode()) {
            insert = s.name + "()";
            back = noArgs ? 0 : 1;
        }
        String detail = (s.kind.isCallable() ? s.detail : s.kind.label) + "  · line " + (s.line + 1);
        String doc = s.kind.label + " " + s.describe() + (s.container != null ? "  in " + s.container : "")
                     + (s.doc != null ? "\n" + s.doc : "");
        Item item = new Item(insert, s.name, detail, EditorIcons.Kind.of(s.kind), doc, back, null);
        item.score = fuzzy + base + recency(s.name);
        item.matches = pos;
        Item present = out.get(s.name);
        if (present == null || present.score < item.score) {
            out.put(s.name, item);
        }
    }

    private void addFixed(Map<String, Item> out, String prefix, String word, EditorIcons.Kind kind, String detail,
                          int base, String doc) {
        Item item = scored(prefix, word, word, kind, detail, base, 0, doc);
        if (item != null) {
            Item present = out.get(word);
            if (present == null || present.score < item.score) {
                out.put(word, item);
            }
        }
    }

    private Item scored(String prefix, String label, String insert, EditorIcons.Kind kind, String detail,
                        int base, int back, String doc) {
        if (prefix.isEmpty() && kind != EditorIcons.Kind.KEYWORD && kind != EditorIcons.Kind.FUNCTION) {
            // An empty prefix lists keywords and builtins; types and constants would drown them.
            if (kind != EditorIcons.Kind.MODULE) {
                return null;
            }
        }
        int[] pos = new int[prefix.length()];
        int fuzzy = FuzzyMatcher.score(prefix, label, pos);
        if (fuzzy == FuzzyMatcher.NO_MATCH || label.equals(prefix)) {
            return null;
        }
        Item item = new Item(insert, label, detail, kind, doc, back, null);
        item.score = fuzzy + base + recency(label) - Math.min(12, label.length() / 4);
        item.matches = pos;
        return item;
    }

    /** True when the word was added. */
    private boolean addWord(Map<String, Item> out, String prefix, String word, EditorIcons.Kind kind, String detail,
                            int base, int count) {
        if (out.containsKey(word)) {
            return false;
        }
        int[] pos = new int[prefix.length()];
        int fuzzy = FuzzyMatcher.score(prefix, word, pos);
        if (fuzzy == FuzzyMatcher.NO_MATCH || word.equals(prefix)) {
            return false;
        }
        // Scattered matches on plain words are noise; keep them for symbols only.
        if (fuzzy < 40 && prefix.length() > 1 && kind == EditorIcons.Kind.WORD) {
            return false;
        }
        Item item = new Item(word, word, detail, kind, null, 0, null);
        item.score = fuzzy + base + Math.min(24, (int) (Math.log(count + 1) / Math.log(2) * 5)) + recency(word);
        item.matches = pos;
        out.put(word, item);
        return true;
    }
}
