package com.sphere.components.editor;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The editor's understanding of one buffer, rebuilt shortly after typing stops:
 * its outline, its problems, the words it uses, which variable holds which ROOT
 * or numpy type, and which lines changed since the file was saved. Everything
 * runs off the event thread on a snapshot of the text.
 */
public final class CodeInsight implements SyntaxHighlighter.Listener {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sphere-insight");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    public interface Listener {
        void insightReady(Result result);
    }

    /** What one analysis found. Immutable. */
    public static final class Result {
        public final long revision;
        public final LanguageSpec spec;
        public final String text;
        public final int[] lineStarts;
        public final List<DocSymbol> symbols;
        public final List<EditorDiagnostic> diagnostics;
        /** Identifier → occurrences in code. */
        public final Map<String, Integer> words;
        /** Receiver → names seen after "receiver." or "receiver->". */
        public final Map<String, Set<String>> members;
        /** Variable → declared type (TH1F, ndarray, Axes...). */
        public final Map<String, String> varTypes;
        /** Python alias → module (np → numpy). */
        public final Map<String, String> aliases;
        /** Per line: LineDiff marks against the saved text. */
        public final byte[] changes;

        Result(long revision, LanguageSpec spec, String text, int[] lineStarts, List<DocSymbol> symbols,
               List<EditorDiagnostic> diagnostics, Map<String, Integer> words,
               Map<String, Set<String>> members, Map<String, String> varTypes,
               Map<String, String> aliases, byte[] changes) {
            this.revision = revision;
            this.spec = spec;
            this.text = text;
            this.lineStarts = lineStarts;
            this.symbols = Collections.unmodifiableList(symbols);
            this.diagnostics = Collections.unmodifiableList(diagnostics);
            this.words = Collections.unmodifiableMap(words);
            this.members = Collections.unmodifiableMap(members);
            this.varTypes = Collections.unmodifiableMap(varTypes);
            this.aliases = Collections.unmodifiableMap(aliases);
            this.changes = changes;
        }

        static Result empty() {
            return new Result(-1, LanguageSpec.NONE, "", new int[] { 0 }, List.of(), List.of(), Map.of(),
                              Map.of(), Map.of(), Map.of(), new byte[1]);
        }

        /** The innermost symbols around a line, outermost first (breadcrumbs). */
        public List<DocSymbol> path(int line) {
            List<DocSymbol> out = new ArrayList<>();
            for (DocSymbol s : symbols) {
                if (s.contains(line) && (s.endLine > s.line || s.line == line)
                        && s.kind != DocSymbol.Kind.VARIABLE && s.kind != DocSymbol.Kind.CONSTANT
                        && s.kind != DocSymbol.Kind.PROPERTY && s.kind != DocSymbol.Kind.PACKAGE
                        && s.kind != DocSymbol.Kind.MACRO) {
                    out.add(s);
                }
            }
            out.sort((a, b) -> a.line != b.line ? Integer.compare(a.line, b.line)
                                                : Integer.compare(b.endLine, a.endLine));
            return out;
        }

        public DocSymbol find(String name) {
            DocSymbol best = null;
            for (DocSymbol s : symbols) {
                if (s.name.equals(name)) {
                    // A definition beats a mention: prefer types and callables.
                    if (best == null || (s.kind.isType() || s.kind.isCallable()) && !best.kind.isType()) {
                        best = s;
                    }
                }
            }
            return best;
        }

        public int errorCount() {
            int c = 0;
            for (EditorDiagnostic d : diagnostics) {
                if (d.getSeverity() == EditorDiagnostic.Severity.ERROR) {
                    c++;
                }
            }
            return c;
        }
    }

    private final SyntaxHighlighter highlighter;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Timer debounce;
    private final AtomicLong generation = new AtomicLong();

    private volatile SyntaxHighlighter.Snapshot pending;
    private volatile Result result = Result.empty();
    private volatile String baseline;
    private volatile File file;
    private boolean enabled = true;

    public CodeInsight(SyntaxHighlighter highlighter) {
        this.highlighter = highlighter;
        this.debounce = new Timer(260, e -> run());
        this.debounce.setRepeats(false);
        highlighter.addListener(this);
    }

    public void addListener(Listener l) {
        if (l != null) {
            listeners.add(l);
        }
    }

    public Result result() {
        return result;
    }

    public void setFile(File file) {
        this.file = file;
    }

    /** The text the change bars compare against: the file as loaded or saved. */
    public void setBaseline(String text) {
        this.baseline = text;
        refresh();
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
        refresh();
    }

    /** Re-reads the latest snapshot now rather than after the pause. */
    public void refresh() {
        if (pending == null) {
            pending = highlighter.snapshot();
        }
        debounce.stop();
        run();
    }

    @Override
    public void highlighted(SyntaxHighlighter.Snapshot snapshot) {
        pending = snapshot;
        debounce.setInitialDelay(snapshot.text.length() > 500_000 ? 700 : 260);
        debounce.restart();
    }

    private void run() {
        final SyntaxHighlighter.Snapshot s = pending;
        if (s == null) {
            return;
        }
        final long gen = generation.incrementAndGet();
        final String base = baseline;
        final File f = file;
        final boolean lint = enabled;
        EXEC.execute(() -> {
            Result r;
            try {
                r = analyze(s, base, f, lint);
            } catch (RuntimeException | StackOverflowError ex) {
                r = new Result(s.revision, s.spec, s.text, lineStarts(s.text), List.of(), List.of(),
                               Map.of(), Map.of(), Map.of(), Map.of(), new byte[lineStarts(s.text).length]);
            }
            final Result done = r;
            SwingUtilities.invokeLater(() -> {
                if (gen != generation.get()) {
                    return;
                }
                result = done;
                for (Listener l : listeners) {
                    try {
                        l.insightReady(done);
                    } catch (RuntimeException ex) {
                        // keep notifying the others
                    }
                }
            });
        });
    }

    // ---- Analysis ----------------------------------------------------------

    static Result analyze(SyntaxHighlighter.Snapshot s, String baseline, File file, boolean lint) {
        String text = s.text;
        int[] lineStarts = lineStarts(text);
        char[] masked = mask(s);
        int[] depthAtLine = depthAtLine(s, lineStarts);

        List<DocSymbol> symbols;
        LanguageSpec spec = s.spec;
        if (text.length() > 3_000_000) {
            symbols = List.of();
        } else if (spec == LanguageSpec.CPP || spec == LanguageSpec.JAVA || spec == LanguageSpec.JAVASCRIPT) {
            symbols = SymbolExtractor.braces(s, masked, lineStarts);
        } else if (spec == LanguageSpec.PYTHON) {
            symbols = SymbolExtractor.python(s, masked, lineStarts);
        } else if (spec == LanguageSpec.JULIA) {
            symbols = SymbolExtractor.julia(s, masked, lineStarts);
        } else if (spec == LanguageSpec.CMAKE) {
            symbols = SymbolExtractor.cmake(s, masked, lineStarts);
        } else if (spec == LanguageSpec.FORTRAN) {
            symbols = SymbolExtractor.fortran(s, masked, lineStarts);
        } else if (spec == LanguageSpec.MARKDOWN) {
            symbols = SymbolExtractor.markdown(s, lineStarts);
        } else if (spec == LanguageSpec.LATEX) {
            symbols = SymbolExtractor.latex(s, masked, lineStarts);
        } else if (spec == LanguageSpec.SHELL || spec == LanguageSpec.POWERSHELL) {
            symbols = SymbolExtractor.shell(s, masked, lineStarts);
        } else if (spec == LanguageSpec.CONFIG) {
            symbols = SymbolExtractor.config(s, lineStarts);
        } else if (spec == LanguageSpec.JSON) {
            symbols = SymbolExtractor.json(s, lineStarts);
        } else {
            symbols = List.of();
        }

        List<EditorDiagnostic> diagnostics = lint ? LiveLinter.lint(s, masked, lineStarts, depthAtLine, file)
                                                  : List.of();

        Map<String, Integer> words = words(s, masked);
        Map<String, Set<String>> members = new HashMap<>();
        Map<String, String> varTypes = new HashMap<>();
        Map<String, String> aliases = new HashMap<>();
        if (spec.isCode() && text.length() < 3_000_000) {
            members(masked, members);
            if (spec == LanguageSpec.CPP) {
                cppTypes(new String(masked), varTypes);
            } else if (spec == LanguageSpec.PYTHON) {
                pythonTypes(new String(masked), varTypes, aliases);
            }
        }
        byte[] changes = baseline == null ? new byte[lineStarts.length] : LineDiff.marks(baseline, text);
        return new Result(s.revision, spec, text, lineStarts, new ArrayList<>(symbols), diagnostics, words,
                          members, varTypes, aliases, changes);
    }

    static int[] lineStarts(String text) {
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        int[] starts = new int[count];
        int k = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts[k++] = i + 1;
            }
        }
        return starts;
    }

    /** The text with strings, comments and directives blanked, newlines kept. */
    static char[] mask(SyntaxHighlighter.Snapshot s) {
        char[] m = s.text.toCharArray();
        for (int i = 0; i < s.count; i++) {
            if (!s.kinds[i].isLiteralOrComment()) {
                continue;
            }
            int end = Math.min(s.ends[i], m.length);
            for (int k = s.starts[i]; k < end; k++) {
                if (m[k] != '\n') {
                    m[k] = ' ';
                }
            }
        }
        return m;
    }

    /** Brackets still open at the start of each line. */
    static int[] depthAtLine(SyntaxHighlighter.Snapshot s, int[] lineStarts) {
        int[] depth = new int[lineStarts.length];
        int b = 0;
        int d = 0;
        for (int line = 0; line < lineStarts.length; line++) {
            int start = lineStarts[line];
            while (b < s.bracketCount && s.bracketPos[b] < start) {
                char c = s.bracketChar[b];
                if (c == '(' || c == '[' || c == '{') {
                    d++;
                } else {
                    d = Math.max(0, d - 1);
                }
                b++;
            }
            depth[line] = d;
        }
        return depth;
    }

    private static Map<String, Integer> words(SyntaxHighlighter.Snapshot s, char[] masked) {
        Map<String, Integer> out = new HashMap<>();
        boolean prose = !s.spec.isCode();
        char[] src = prose ? s.text.toCharArray() : masked;
        int n = src.length;
        int i = 0;
        while (i < n && out.size() < 40_000) {
            char c = src[i];
            if (Character.isLetter(c) || c == '_') {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(src[j]) || src[j] == '_')) {
                    j++;
                }
                if (s.spec == LanguageSpec.JULIA && j < n && src[j] == '!') {
                    j++;
                }
                int len = j - i;
                if (len >= (prose ? 4 : 2) && len <= 64) {
                    out.merge(new String(src, i, len), 1, Integer::sum);
                }
                i = j;
                continue;
            }
            if (Character.isDigit(c)) {
                while (i < n && (Character.isLetterOrDigit(src[i]) || src[i] == '.')) {
                    i++;
                }
                continue;
            }
            i++;
        }
        return out;
    }

    /** "receiver.member", "p->member", "ns::member": a single pass over the code. */
    private static void members(char[] m, Map<String, Set<String>> out) {
        final int n = m.length;
        int i = 0;
        int found = 0;
        while (i < n && found < 50_000) {
            char c = m[i];
            if (!(Character.isLetter(c) || c == '_') || (i > 0 && isIdent(m[i - 1]))) {
                i++;
                continue;
            }
            int e = i + 1;
            while (e < n && isIdent(m[e])) {
                e++;
            }
            int k = e;
            while (k < n && (m[k] == ' ' || m[k] == '\t')) {
                k++;
            }
            int op = 0;
            if (k < n && m[k] == '.') {
                op = 1;
            } else if (k + 1 < n && ((m[k] == '-' && m[k + 1] == '>') || (m[k] == ':' && m[k + 1] == ':'))) {
                op = 2;
            }
            if (op == 0) {
                i = e;
                continue;
            }
            int s = k + op;
            while (s < n && (m[s] == ' ' || m[s] == '\t')) {
                s++;
            }
            if (s < n && (Character.isLetter(m[s]) || m[s] == '_')) {
                int se = s + 1;
                while (se < n && isIdent(m[se])) {
                    se++;
                }
                out.computeIfAbsent(new String(m, i, e - i), x -> new LinkedHashSet<>()).add(new String(m, s, se - s));
                found++;
                // Go on from the member: "a.b.c" also gives b -> c.
                i = s;
                continue;
            }
            i = e;
        }
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Offset of word as a whole word from {@code from}, or -1. */
    private static int wordIndex(String s, String word, int from) {
        int at = s.indexOf(word, from);
        while (at >= 0) {
            boolean before = at == 0 || !isIdent(s.charAt(at - 1));
            boolean after = at + word.length() >= s.length() || !isIdent(s.charAt(at + word.length()));
            if (before && after) {
                return at;
            }
            at = s.indexOf(word, at + word.length());
        }
        return -1;
    }

    private static final Pattern CPP_DECL = Pattern.compile(
            "\\b([A-Z]\\w*(?:::\\w+)*)(?:\\s*<[^;(){}]*>)?(?:\\s+|\\s*[*&]+\\s*)([a-z_]\\w*)\\s*(?:=|\\(|;|\\{|,|\\[)");
    private static final Pattern CPP_NEW_AT = Pattern.compile("new\\s+([A-Za-z_][\\w:]*)");
    private static final Pattern CPP_AUTO = Pattern.compile(
            "\\bauto\\s*[*&]*\\s*([A-Za-z_]\\w*)\\s*=\\s*(?:new\\s+)?([A-Za-z_][\\w:]*)\\s*(?:<[^;(){}]*>)?\\s*[({]");
    private static final Pattern CPP_SMART = Pattern.compile(
            "(?:std::)?(?:unique_ptr|shared_ptr|TTreeReaderValue|TTreeReaderArray)\\s*<\\s*([A-Za-z_][\\w:]*)\\s*>\\s*([A-Za-z_]\\w*)");
    private static final Pattern CPP_CHAIN = Pattern.compile(
            "\\bauto\\s*[*&]*\\s*([A-Za-z_]\\w*)\\s*=\\s*([A-Za-z_]\\w*)\\s*(?:\\.|->)\\s*([A-Za-z_]\\w*)\\s*\\(");

    static void cppTypes(String m, Map<String, String> out) {
        out.putAll(KnowledgeBase.ROOT_GLOBALS);
        final int n = m.length();
        // Each pattern is tried only where it can start, never at every offset.
        Matcher d = CPP_DECL.matcher(m);
        for (int i = 0; i < n; i++) {
            char c = m.charAt(i);
            if (c < 'A' || c > 'Z' || (i > 0 && isIdent(m.charAt(i - 1)))) {
                continue;
            }
            d.region(i, Math.min(n, i + 240));
            if (d.lookingAt()) {
                String type = d.group(1);
                if (!type.startsWith("k")) {
                    out.put(d.group(2), type);
                }
            }
        }
        Matcher nw = CPP_NEW_AT.matcher(m);
        for (int at = wordIndex(m, "new", 0); at >= 0; at = wordIndex(m, "new", at + 3)) {
            int eq = at - 1;
            while (eq >= 0 && Character.isWhitespace(m.charAt(eq))) {
                eq--;
            }
            if (eq < 1 || m.charAt(eq) != '=' || m.charAt(eq - 1) == '=') {
                continue;
            }
            int ve = eq - 1;
            while (ve >= 0 && Character.isWhitespace(m.charAt(ve))) {
                ve--;
            }
            int vs = ve;
            while (vs >= 0 && isIdent(m.charAt(vs))) {
                vs--;
            }
            nw.region(at, Math.min(n, at + 200));
            if (ve > vs && nw.lookingAt()) {
                out.put(m.substring(vs + 1, ve + 1), nw.group(1));
            }
        }
        Matcher sm = CPP_SMART.matcher(m);
        for (String head : new String[] { "unique_ptr", "shared_ptr", "TTreeReaderValue", "TTreeReaderArray" }) {
            for (int at = m.indexOf(head); at >= 0; at = m.indexOf(head, at + head.length())) {
                int from = at >= 5 && m.startsWith("std::", at - 5) ? at - 5 : at;
                sm.region(from, Math.min(n, from + 240));
                if (sm.lookingAt()) {
                    out.put(sm.group(2), sm.group(1));
                }
            }
        }
        Matcher au = CPP_AUTO.matcher(m);
        Matcher ch = CPP_CHAIN.matcher(m);
        List<Integer> autos = new ArrayList<>();
        for (int at = wordIndex(m, "auto", 0); at >= 0; at = wordIndex(m, "auto", at + 4)) {
            autos.add(at);
        }
        for (int at : autos) {
            au.region(at, Math.min(n, at + 240));
            if (!au.lookingAt()) {
                continue;
            }
            String q = au.group(2);
            int sep = q.lastIndexOf("::");
            if (sep > 0) {
                String cls = q.substring(0, sep);
                String method = q.substring(sep + 2);
                KnowledgeBase.Member mem = KnowledgeBase.member(cls, method);
                if (mem != null && !mem.returns.isEmpty()) {
                    out.put(au.group(1), mem.returns);
                    continue;
                }
                if (KnowledgeBase.knows(q)) {
                    out.put(au.group(1), q);
                    continue;
                }
                if (KnowledgeBase.knows(method)) {
                    out.put(au.group(1), method);
                    continue;
                }
            }
            out.put(au.group(1), q);
        }
        // auto x = h->GetXaxis(); takes the result type of the call.
        for (int at : autos) {
            ch.region(at, Math.min(n, at + 240));
            if (!ch.lookingAt()) {
                continue;
            }
            String receiverType = out.get(ch.group(2));
            if (receiverType != null) {
                KnowledgeBase.Member mem = KnowledgeBase.member(receiverType, ch.group(3));
                if (mem != null && !mem.returns.isEmpty()) {
                    out.put(ch.group(1), mem.returns);
                }
            }
        }
    }

    private static final Pattern PY_IMPORT = Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)(?:\\s+as\\s+(\\w+))?");
    private static final Pattern PY_FROM = Pattern.compile("(?m)^\\s*from\\s+([\\w.]+)\\s+import\\s+([^\\n]+)");
    private static final Pattern PY_CTOR = Pattern.compile(
            "\\b([A-Za-z_]\\w*)\\s*=\\s*(?:ROOT\\s*\\.\\s*)?(T[A-Z]\\w*|RDataFrame)\\s*\\(");
    private static final Pattern PY_STATIC = Pattern.compile(
            "\\b([A-Za-z_]\\w*)\\s*=\\s*(?:ROOT\\s*\\.\\s*)?(T[A-Z]\\w*)\\s*\\.\\s*([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern PY_CALL = Pattern.compile(
            "\\b([A-Za-z_]\\w*)\\s*=\\s*([A-Za-z_]\\w*)\\s*\\.\\s*([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern PY_SUBPLOTS = Pattern.compile(
            "\\b([A-Za-z_]\\w*)\\s*,\\s*([A-Za-z_]\\w*)\\s*=\\s*([A-Za-z_]\\w*)\\s*\\.\\s*subplots\\s*\\(");

    static void pythonTypes(String m, Map<String, String> types, Map<String, String> aliases) {
        Matcher im = PY_IMPORT.matcher(m);
        while (im.find()) {
            String module = im.group(1);
            String alias = im.group(2) != null ? im.group(2)
                         : module.contains(".") ? module.substring(0, module.indexOf('.')) : module;
            String key = KnowledgeBase.moduleKey(im.group(2) != null ? module
                                                                     : (module.contains(".") ? alias : module));
            if (key != null) {
                aliases.put(alias, key);
            }
        }
        Matcher fm = PY_FROM.matcher(m);
        while (fm.find()) {
            String module = fm.group(1);
            for (String part : fm.group(2).replace("(", "").replace(")", "").split(",")) {
                String p = part.trim();
                if (p.isEmpty() || p.equals("*")) {
                    continue;
                }
                String name = p.contains(" as ") ? p.substring(0, p.indexOf(" as ")).trim() : p;
                String alias = p.contains(" as ") ? p.substring(p.indexOf(" as ") + 4).trim() : p;
                String sub = KnowledgeBase.moduleKey(module + "." + name);
                if (sub != null) {
                    aliases.put(alias, sub);
                } else if (module.equals("ROOT") && KnowledgeBase.knows(name)) {
                    types.put(alias, "class:" + name);
                }
            }
        }
        // The assignment patterns run on assignment lines only, a line at a time.
        StringBuilder assigns = new StringBuilder();
        for (String line : m.split("\n")) {
            if (line.indexOf('=') >= 0) {
                assigns.append(line).append('\n');
            }
        }
        m = assigns.toString();
        Matcher ct = PY_CTOR.matcher(m);
        while (ct.find()) {
            types.put(ct.group(1), ct.group(2));
        }
        // f = ROOT.TFile.Open(...) takes what the static method returns.
        Matcher st = PY_STATIC.matcher(m);
        while (st.find()) {
            KnowledgeBase.Member mem = KnowledgeBase.member(st.group(2), st.group(3));
            if (mem != null && !mem.returns.isEmpty()) {
                types.put(st.group(1), mem.returns);
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            Matcher call = PY_CALL.matcher(m);
            while (call.find()) {
                String receiver = call.group(2);
                String owner = aliases.get(receiver);
                if (owner == null) {
                    owner = types.get(receiver);
                }
                if (owner == null && receiver.equals("ROOT")) {
                    owner = "ROOT";
                }
                if (owner == null) {
                    continue;
                }
                KnowledgeBase.Member mem = KnowledgeBase.member(owner, call.group(3));
                if (mem != null && !mem.returns.isEmpty()) {
                    types.put(call.group(1), mem.returns);
                }
            }
        }
        Matcher sp = PY_SUBPLOTS.matcher(m);
        while (sp.find()) {
            types.put(sp.group(1), "Figure");
            types.put(sp.group(2), "Axes");
        }
    }
}
