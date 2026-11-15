package com.sphere.components.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Python names resolved the way the compiler resolves them, so that each one is
 * colored for what it is and for how it is used.
 *
 * The lexer reads one token at a time: it cannot tell a parameter from a global
 * of the same name, a variable bound twice from one bound once, an attribute
 * from a name. This pass reads the lexer's tokens as statements and builds the
 * scopes Python builds -- module, function, lambda, class, comprehension; global
 * and nonlocal declarations; a class body that the functions inside it do not
 * see -- then gives every occurrence of a name:
 *
 *   - a role: global variable, local, parameter, self, attribute, keyword
 *     argument, function, method, builtin, class, module, constant...;
 *   - modifiers: its definition (first binding), reassigned, never read, a
 *     builtin it hides, captured by a closure, changed in place; and, from a
 *     running kernel, live and stale (computed from a value changed since);
 *   - the family of the value the kernel holds under it: tensor, NumPy array,
 *     data frame, jagged array, histogram, ROOT object, model, figure...
 *
 * In a notebook a module-level name belongs to every cell: the {@link Context}
 * says what the other cells bind and read and what the kernel holds, so a
 * variable defined in one cell and read in another is neither unresolved in the
 * second nor unused in the first.
 *
 * Resolution also finds bugs a single token never shows: a builtin hidden by a
 * value and then called, a mutable default argument, a lambda created in a loop
 * that will only ever see the loop's last value, "is" against a literal.
 */
public final class PySemantics {

    /** Beyond this the text keeps the grammar's colors: the analysis is linear, but not free. */
    public static final int MAX_CHARS = 400_000;

    private PySemantics() {
    }

    // =======================================================================
    // What a name is
    // =======================================================================

    /** The role of a name, with the coarse kind the rest of the editor reads. */
    public enum Role {
        NONE("name", TokenKind.DEFAULT),
        VARIABLE("global variable", TokenKind.VARIABLE),
        LOCAL("local variable", TokenKind.VARIABLE),
        PARAMETER("parameter", TokenKind.VARIABLE),
        SELF("instance (self / cls)", TokenKind.VARIABLE),
        ATTRIBUTE("attribute", TokenKind.VARIABLE),
        FIELD("class attribute", TokenKind.VARIABLE),
        KWARG("keyword argument", TokenKind.VARIABLE),
        FUNCTION("function", TokenKind.FUNCTION),
        METHOD("method", TokenKind.FUNCTION),
        BUILTIN("builtin function", TokenKind.FUNCTION),
        CLASS("class", TokenKind.TYPE),
        BUILTIN_TYPE("builtin type", TokenKind.TYPE),
        EXCEPTION("exception class", TokenKind.TYPE),
        TYPE_PARAM("type parameter", TokenKind.TYPE),
        MODULE("module", TokenKind.NAMESPACE),
        CONSTANT("constant", TokenKind.CONSTANT);

        public final String label;
        public final TokenKind kind;

        Role(String label, TokenKind kind) {
            this.label = label;
            this.kind = kind;
        }

        /** Names that hold data, the ones a runtime family applies to. */
        public boolean isData() {
            return this == VARIABLE || this == LOCAL || this == PARAMETER || this == CONSTANT || this == FIELD;
        }
    }

    /** What a kernel's value is at run time, as the notebook's analysis names it. */
    public enum Family {
        NONE(""), SCALAR("scalar"), TEXT("text"), COLLECTION("collection"), ARRAY("NumPy array"),
        TENSOR("tensor"), FRAME("data frame"), JAGGED("jagged array"), HIST("histogram"),
        ROOT("ROOT / uproot object"), MODEL("model"), FIGURE("figure"), OBJECT("object");

        public final String label;

        Family(String label) {
            this.label = label;
        }

        /** The family a kernel names ("tensor", "array"...); NONE when unknown. */
        public static Family of(String name) {
            if (name == null || name.isEmpty()) {
                return NONE;
            }
            for (Family f : values()) {
                if (f.name().equalsIgnoreCase(name)) {
                    return f;
                }
            }
            return NONE;
        }

        /** The families heavy enough to be marked on every occurrence of their name. */
        public boolean isMarked() {
            return this == ARRAY || this == TENSOR || this == FRAME || this == JAGGED || this == HIST
                   || this == ROOT || this == MODEL || this == FIGURE;
        }
    }

    /** How a name gets bound. */
    public enum Binding {
        VAR, AUG, ANNOTATION, LOOP, WITH, EXCEPT, MATCH, WALRUS, PARAM, SELF, FUNC, CLASS,
        IMPORT_MODULE, IMPORT_NAME, TYPE_PARAM;

        /** A rebinding as "reassigned" counts it: an assignment, +=, :=, a def or a class. */
        public boolean assigns() {
            return this == VAR || this == AUG || this == WALRUS || this == FUNC || this == CLASS;
        }

        /** Binds a value rather than a definition or an import. */
        public boolean isData() {
            return this == VAR || this == AUG || this == ANNOTATION || this == LOOP || this == WITH
                   || this == EXCEPT || this == MATCH || this == WALRUS;
        }
    }

    public enum ScopeKind { MODULE, FUNCTION, LAMBDA, CLASS, COMPREHENSION }

    // ---- Modifiers ---------------------------------------------------------

    /** This occurrence is the name's definition: its first binding (in the notebook, in cell order). */
    public static final int DEF = 1;
    /** The name is bound more than once (in its scope; for a global, in the whole notebook). */
    public static final int REASSIGNED = 1 << 1;
    /** Bound and never read. */
    public static final int UNUSED = 1 << 2;
    /** Hides a builtin of the same name. */
    public static final int SHADOWS = 1 << 3;
    /** The kernel's value was computed from a value that changed since. */
    public static final int STALE = 1 << 4;
    /** The kernel holds a value under this name. */
    public static final int LIVE = 1 << 5;
    /** Read by a closure from an enclosing function. */
    public static final int CAPTURED = 1 << 6;
    /** The object is changed in place here: x[i] = ..., x.attr = ..., x.append(...). */
    public static final int MUTATED = 1 << 7;
    /** Named by a global or nonlocal declaration. */
    public static final int DECLARED = 1 << 8;
    /** Bound nowhere: no cell, no builtin, no kernel value. */
    public static final int UNRESOLVED = 1 << 9;
    /** Called here. */
    public static final int CALLED = 1 << 10;

    private static final int ROLE_BITS = 5;
    private static final int MOD_BITS = 12;
    private static final Role[] ROLES = Role.values();
    private static final Family[] FAMILIES = Family.values();

    public static int pack(Role role, int mods, Family family) {
        return role.ordinal() | (mods << ROLE_BITS) | (family.ordinal() << (ROLE_BITS + MOD_BITS));
    }

    public static Role role(int sem) {
        int r = sem & ((1 << ROLE_BITS) - 1);
        return r < ROLES.length ? ROLES[r] : Role.NONE;
    }

    public static int mods(int sem) {
        return (sem >>> ROLE_BITS) & ((1 << MOD_BITS) - 1);
    }

    public static Family family(int sem) {
        int f = (sem >>> (ROLE_BITS + MOD_BITS)) & 15;
        return f < FAMILIES.length ? FAMILIES[f] : Family.NONE;
    }

    public static boolean has(int sem, int modifier) {
        return (mods(sem) & modifier) != 0;
    }

    // =======================================================================
    // What the notebook knows
    // =======================================================================

    /** The notebook around the text: implemented by the notebook editor, per cell. */
    public interface Context {
        /** Module-level facts about a name, from everywhere but this text; null when nothing is known. */
        Global global(String name);

        /** True when some cell imports * or runs a script: names may come from where nobody can list them. */
        default boolean opaque() {
            return false;
        }

        /** True when a kernel already checks the names (and underlines the undefined ones itself). */
        default boolean checksNames() {
            return false;
        }
    }

    /**
     * What a kernel holds under a name: its kind (module, class, function, data),
     * runtime family, type and one-line summary, why it is stale (null when it is
     * not), and the execution count of the cell that made it (-1 when unknown).
     */
    public record Live(String kind, Family family, String type, String info, String stale, int made) {
    }

    /**
     * A module-level name as the rest of the notebook sees it.
     *
     * @param kind              how the first other cell binding it binds it, null when none does
     * @param bindsBefore       cells above that bind it
     * @param bindsAfter        cells below that bind it
     * @param assignsElsewhere  assignments in the other cells
     * @param readsElsewhere    reads in the other cells
     * @param definedIn         where it is defined first, for tooltips ("cell [3]")
     */
    public record Global(Binding kind, int bindsBefore, int bindsAfter, int assignsElsewhere, int readsElsewhere,
                         boolean calledElsewhere, boolean attrBaseElsewhere, String definedIn, Live live) {
    }

    // =======================================================================
    // What the analysis gives
    // =======================================================================

    /** A bug resolution shows; severity 0 info, 1 warning, 2 error. */
    public record Finding(int start, int end, int severity, String code, String message) {
    }

    /** A module-level statement binding these names, ending on this 1-based line: where a value lens goes. */
    public record Lens(int line, List<String> names) {
    }

    /** One module-level binding of the text (no offsets: typing above it must not change what other cells see). */
    public record Bind(Binding kind, int binds, int assigns, boolean called, boolean attrBase) {
    }

    /**
     * The text's module level as the rest of a notebook needs it; depends on the
     * text alone, never on the context, so the notebook can sum it over cells.
     *
     * {@code needs} are the module-level names the text reads as it runs before
     * binding them itself (x = x + 1 needs x; y = 1; z = y does not need y; a
     * function body reads only when called, so its names are not needed): what a
     * cell takes from the cells before it, the edges of the notebook's dataflow.
     *
     * {@code mutates} are the needed names the text changes in place as it runs
     * (x.append(1), x[i] = v, x.attr = v, del x[k], df.dropna(inplace=True)):
     * an object another cell made, which no binding shows has changed.
     */
    public record Summary(Map<String, Bind> bound, Map<String, Integer> reads, Set<String> called,
                          Set<String> attrBases, boolean opaque, Set<String> needs, Set<String> mutates) {
        public static final Summary EMPTY = new Summary(Map.of(), Map.of(), Set.of(), Set.of(), false, Set.of(), Set.of());
    }

    /** One occurrence, with what a tooltip says about it. */
    public record Occurrence(int start, int end, String name, int sem, String scope, int binds, int assigns,
                             int reads, int definedAt, boolean external) {
        public Role role() {
            return PySemantics.role(sem);
        }

        public Family family() {
            return PySemantics.family(sem);
        }

        public boolean has(int modifier) {
            return PySemantics.has(sem, modifier);
        }
    }

    public static final class Result {
        private final int count;
        private final int[] start;
        private final int[] end;
        private final int[] sem;
        private final String[] name;
        private final Symbol[] symbol;
        private final Summary summary;
        private final List<Finding> findings;
        private final List<Lens> lenses;
        private final String text;

        Result(String text, int count, int[] start, int[] end, int[] sem, String[] name, Symbol[] symbol,
               Summary summary, List<Finding> findings, List<Lens> lenses) {
            this.text = text;
            this.count = count;
            this.start = start;
            this.end = end;
            this.sem = sem;
            this.name = name;
            this.symbol = symbol;
            this.summary = summary;
            this.findings = List.copyOf(findings);
            this.lenses = List.copyOf(lenses);
        }

        public int count() {
            return count;
        }

        public Summary summary() {
            return summary;
        }

        public List<Finding> findings() {
            return findings;
        }

        public List<Lens> lenses() {
            return lenses;
        }

        /** The occurrence covering offset, or null. */
        public Occurrence occurrence(int offset) {
            int lo = 0;
            int hi = count - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (end[mid] <= offset) {
                    lo = mid + 1;
                } else if (start[mid] > offset) {
                    hi = mid - 1;
                } else {
                    Symbol s = symbol[mid];
                    if (s == null) {
                        return new Occurrence(start[mid], end[mid], name[mid], sem[mid], "", 0, 0, 0, -1, true);
                    }
                    return new Occurrence(start[mid], end[mid], name[mid], sem[mid], s.scope.label, s.binds,
                                          s.assigns, s.reads, s.firstOffset == Integer.MAX_VALUE ? -1
                                              : lineOf(text, s.firstOffset) + 1, false);
                }
            }
            return null;
        }

        /** Offsets of every occurrence resolving to the same symbol as the one at offset (or the same global). */
        public List<int[]> sameSymbol(int offset) {
            List<int[]> out = new ArrayList<>();
            int lo = 0;
            int hi = count - 1;
            int at = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (end[mid] <= offset) {
                    lo = mid + 1;
                } else if (start[mid] > offset) {
                    hi = mid - 1;
                } else {
                    at = mid;
                    break;
                }
            }
            if (at < 0) {
                return out;
            }
            Role r = role(sem[at]);
            if (r == Role.ATTRIBUTE || r == Role.KWARG) {
                return out;
            }
            for (int k = 0; k < count; k++) {
                boolean same = symbol[at] != null ? symbol[k] == symbol[at]
                             : symbol[k] == null && name[k].equals(name[at]) && role(sem[k]) == r;
                if (same) {
                    out.add(new int[] { start[k], end[k], has(sem[k], DEF) || (symbol[k] != null && symbol[k].isBindingAt(start[k])) ? 1 : 0 });
                }
            }
            return out;
        }

        /**
         * Every occurrence of a module-level name in the text (the name itself, not
         * an attribute or a keyword argument of the same spelling, nor a local that
         * hides it), as {start, end, 1 when it binds the name else 0}: what the
         * notebook highlights in the other cells when the caret is on that name.
         */
        public List<int[]> globalOccurrences(String n) {
            List<int[]> out = new ArrayList<>();
            for (int k = 0; k < count; k++) {
                if (!name[k].equals(n)) {
                    continue;
                }
                Role r = role(sem[k]);
                if (r == Role.ATTRIBUTE || r == Role.KWARG) {
                    continue;
                }
                Symbol s = symbol[k];
                if (s != null && s.scope.kind != ScopeKind.MODULE) {
                    continue;
                }
                out.add(new int[] { start[k], end[k], s != null && s.isBindingAt(start[k]) ? 1 : 0 });
            }
            return out;
        }

        /** Where the symbol at offset is first bound in this text, or -1 (a name bound elsewhere, a builtin). */
        public int definitionOf(int offset) {
            for (int k = 0; k < count; k++) {
                if (start[k] <= offset && offset < end[k]) {
                    Symbol s = symbol[k];
                    return s == null || s.firstOffset == Integer.MAX_VALUE ? -1 : s.firstOffset;
                }
            }
            return -1;
        }

        /** Where the module-level name is first bound in this text, or -1. */
        public int definitionOfGlobal(String n) {
            for (int k = 0; k < count; k++) {
                Symbol s = symbol[k];
                if (s != null && s.scope.kind == ScopeKind.MODULE && s.name.equals(n) && s.firstOffset != Integer.MAX_VALUE) {
                    return s.firstOffset;
                }
            }
            return -1;
        }

        /** Replaces the lexer's spans of the names in out (from base on) with the resolved ones. */
        public void applyTo(List<SyntaxTokenizer.Span> out, int base) {
            List<SyntaxTokenizer.Span> merged = new ArrayList<>(out.size() - base + count);
            int k = 0;
            for (int i = base; i < out.size(); i++) {
                SyntaxTokenizer.Span s = out.get(i);
                while (k < count && end[k] <= s.start) {
                    merged.add(span(k++));
                }
                while (k < count && start[k] < s.end && end[k] > s.start && !(start[k] == s.start && end[k] == s.end)) {
                    k++; // overlaps a span of another kind: the grammar's reading wins
                }
                if (k < count && start[k] == s.start && end[k] == s.end && s.kind != TokenKind.KEYWORD) {
                    merged.add(span(k++));
                    continue;
                }
                merged.add(s);
            }
            while (k < count) {
                merged.add(span(k++));
            }
            out.subList(base, out.size()).clear();
            out.addAll(merged);
        }

        private SyntaxTokenizer.Span span(int k) {
            Role r = role(sem[k]);
            TokenKind kind = r.kind == TokenKind.DEFAULT ? TokenKind.VARIABLE : r.kind;
            return new SyntaxTokenizer.Span(start[k], end[k], kind, false, sem[k]);
        }
    }

    /** One name of one scope. */
    static final class Symbol {
        final String name;
        final Scope scope;
        int binds;
        int assigns;
        int reads;
        boolean called;
        boolean attrBase;
        boolean starred;
        boolean isFinal;
        int firstOffset = Integer.MAX_VALUE;
        /** Where the first binding takes effect: the end of its statement (x = f(x) reads x first). */
        int firstEffective = Integer.MAX_VALUE;
        Binding firstKind;
        private int[] bindAt = new int[2];
        private Binding[] bindKind = new Binding[2];
        private int nb;

        Symbol(String name, Scope scope) {
            this.name = name;
            this.scope = scope;
        }

        void bind(int offset, Binding kind) {
            binds++;
            if (kind.assigns()) {
                assigns++;
            }
            if (nb == bindAt.length) {
                bindAt = Arrays.copyOf(bindAt, nb * 2);
                bindKind = Arrays.copyOf(bindKind, nb * 2);
            }
            int at = nb;
            while (at > 0 && bindAt[at - 1] > offset) {
                bindAt[at] = bindAt[at - 1];
                bindKind[at] = bindKind[at - 1];
                at--;
            }
            bindAt[at] = offset;
            bindKind[at] = kind;
            nb++;
            if (offset < firstOffset) {
                firstOffset = offset;
                firstKind = kind;
            }
        }

        /** How the name is bound where offset reads it: the last binding at or before it, else the first. */
        Binding kindAt(int offset) {
            Binding k = nb > 0 ? bindKind[0] : null;
            for (int i = 0; i < nb && bindAt[i] <= offset; i++) {
                k = bindKind[i];
            }
            return k;
        }

        boolean isBindingAt(int offset) {
            for (int i = 0; i < nb; i++) {
                if (bindAt[i] == offset) {
                    return true;
                }
            }
            return false;
        }
    }

    static final class Scope {
        final ScopeKind kind;
        final String label;
        final Scope parent;
        final Map<String, Symbol> symbols = new HashMap<>();
        final Set<String> globals = new HashSet<>();
        final Set<String> nonlocals = new HashSet<>();
        /** Loops whose targets a closure defined here would only see at their last value. */
        List<LoopFrame> escapingLoops = List.of();

        Scope(ScopeKind kind, String label, Scope parent) {
            this.kind = kind;
            this.label = label;
            this.parent = parent;
        }
    }

    record LoopFrame(int indent, Scope scope, Set<String> targets) {
    }

    private record Frame(int indent, Scope scope) {
    }

    // =======================================================================
    // Entry point
    // =======================================================================

    /**
     * Resolves the names of {@code text}, whose lexer spans are {@code spans} from
     * {@code base} on. Returns null when the text defeats the analysis, which then
     * leaves the grammar's colors as they are.
     */
    public static Result analyze(String text, List<SyntaxTokenizer.Span> spans, int base,
                                 Function<String, TokenKind> names, Context context) {
        if (text == null || text.isEmpty() || text.length() > MAX_CHARS) {
            return null;
        }
        try {
            Analyzer a = new Analyzer(text, spans, base, names, context);
            a.tokenize();
            a.lines();
            a.parse();
            return a.resolve();
        } catch (RuntimeException defeated) {
            return null;
        }
    }

    /** Statements that can never continue an expression left open on the line above. */
    private static final Set<String> STATEMENT_KEYWORDS = Set.of(
        "def", "class", "import", "from", "return", "raise", "del", "global", "nonlocal", "pass", "break",
        "continue", "try", "while", "with", "except", "finally", "elif", "assert");

    /** Columns of indentation of the line holding offset (tabs to the next multiple of 8). */
    static int indentAt(String text, int offset) {
        int ls = offset <= 0 ? 0 : text.lastIndexOf('\n', offset - 1) + 1;
        int col = 0;
        for (int k = ls; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == ' ') {
                col++;
            } else if (c == '\t') {
                col = (col / 8 + 1) * 8;
            } else if (c != '\f') {
                break;
            }
        }
        return col;
    }

    /**
     * Whether the next line of code after {@code from} starts a new statement although
     * a bracket opened on a line indented {@code openerIndent} is still open: the
     * state of a line being typed ("x = f(" with the code below untouched). A line
     * indented less than that line, or as much but starting with a statement keyword
     * or an assignment ("name = ..."), cannot continue the expression; anything else
     * is taken as its continuation, as Python would.
     */
    static boolean statementFollows(String text, int from, int openerIndent) {
        final int n = text.length();
        int k = from;
        while (k < n) {
            int e = text.indexOf('\n', k);
            if (e < 0) {
                e = n;
            }
            int p = k;
            int col = 0;
            while (p < e && (text.charAt(p) == ' ' || text.charAt(p) == '\t')) {
                col = text.charAt(p) == '\t' ? (col / 8 + 1) * 8 : col + 1;
                p++;
            }
            if (p >= e || text.charAt(p) == '#' || text.charAt(p) == '\r') {
                k = e + 1;
                continue;
            }
            char c = text.charAt(p);
            if (")]}".indexOf(c) >= 0) {
                return false;
            }
            if (col < openerIndent) {
                return true;
            }
            if (col > openerIndent) {
                return false;
            }
            if (c == '@' || c == '%' || c == '!') {
                return true;
            }
            if (!PythonLexer.isIdentifierStart(c)) {
                return false;
            }
            int q = p;
            while (q < e && PythonLexer.isIdentifierPart(text.charAt(q))) {
                q++;
            }
            if (STATEMENT_KEYWORDS.contains(text.substring(p, q))) {
                return true;
            }
            int r = q;
            while (r < e && (text.charAt(r) == '.' || PythonLexer.isIdentifierPart(text.charAt(r)))) {
                r++;
            }
            if (r >= e || text.charAt(r) != ' ') {
                return false; // x=1 is as likely a keyword argument as an assignment
            }
            while (r < e && text.charAt(r) == ' ') {
                r++;
            }
            if (r >= e) {
                return false;
            }
            String rest = text.substring(r, Math.min(e, r + 4));
            return rest.startsWith("=") && !rest.startsWith("==")
                   || rest.length() >= 2 && "+-*/%&|^@".indexOf(rest.charAt(0)) >= 0 && rest.charAt(1) == '='
                   || rest.startsWith("//=") || rest.startsWith("**=") || rest.startsWith(">>=") || rest.startsWith("<<=")
                   || rest.startsWith(": ");
        }
        return false;
    }

    /** 0-based line of an offset. */
    static int lineOf(String text, int offset) {
        int line = 0;
        int end = Math.min(offset, text.length());
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    // ---- Names that are not the user's ------------------------------------

    /** Hiding these is harmless: nobody calls them in code. */
    private static final Set<String> HARMLESS = Set.of(
        "exit", "quit", "copyright", "credits", "license", "help", "display", "get_ipython",
        "__build_class__", "__import__", "breakpoint");

    /** What IPython, and Sphere's kernels, put in every notebook's namespace. */
    private static final Set<String> NOTEBOOK_NAMES = Set.of("display", "get_ipython", "In", "Out", "exit", "quit");

    /** Methods that change their object in place. */
    private static final Set<String> MUTATORS = Set.of(
        "append", "extend", "insert", "remove", "pop", "clear", "sort", "reverse", "update", "setdefault",
        "add", "discard", "popitem", "fill", "resize", "put", "push", "appendleft", "extendleft", "popleft",
        "rotate", "difference_update", "intersection_update", "symmetric_difference_update", "itemset",
        "set_", "copy_", "zero_", "fill_", "add_", "sub_", "mul_", "div_", "clamp_", "requires_grad_",
        "Fill", "Add", "Scale", "Reset", "SetBinContent", "Sumw2");

    /** Calls through which a function stored outlives the statement that made it. */
    private static final Set<String> STORES = Set.of(
        "append", "extend", "insert", "add", "appendleft", "setdefault", "connect", "register", "put",
        "submit", "call_later", "call_soon", "after", "bind", "on", "subscribe", "add_callback",
        "add_done_callback", "register_hook", "register_forward_hook", "observe");

    private static final Set<String> MUTABLE_FACTORIES = Set.of(
        "list", "dict", "set", "defaultdict", "OrderedDict", "deque", "bytearray", "Counter");

    private static final Set<String> KEYWORD_ATOMS = Set.of("True", "False", "None", "Ellipsis", "NotImplemented", "__debug__");

    static boolean isBuiltinName(String name) {
        return PythonLexer.BUILTIN_FUNCTIONS.contains(name) || PythonLexer.BUILTIN_TYPES.contains(name);
    }

    static boolean isException(String name) {
        return PythonLexer.BUILTIN_TYPES.contains(name)
               && (name.endsWith("Error") || name.endsWith("Exception") || name.endsWith("Warning")
                   || name.equals("KeyboardInterrupt") || name.equals("SystemExit") || name.equals("StopIteration")
                   || name.equals("StopAsyncIteration") || name.equals("GeneratorExit")
                   || name.equals("BaseExceptionGroup") || name.equals("ExceptionGroup"));
    }

    // =======================================================================
    // The analysis
    // =======================================================================

    private static final class Analyzer {

        static final int T_NAME = 1;
        static final int T_KW = 2;
        static final int T_OP = 3;
        static final int T_OPEN = 4;
        static final int T_CLOSE = 5;
        static final int T_ATOM = 6;
        static final int T_NL = 7;
        static final int T_DECO = 8;

        static final int F_BIND = 1;
        static final int F_READ = 1 << 1;
        static final int F_CALL = 1 << 2;
        static final int F_ATTR_BASE = 1 << 3;
        static final int F_SUB = 1 << 4;
        static final int F_MUTATED = 1 << 5;
        static final int F_WEAK = 1 << 6;
        static final int F_LINKED = 1 << 7;
        static final int F_DECL = 1 << 8;
        static final int F_DEL = 1 << 9;

        private static final String[] OPS3 = { "**=", "//=", ">>=", "<<=" };
        private static final String[] OPS2 = { "->", ":=", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%=",
                                               "&=", "|=", "^=", "@=", "**", "//", "<<", ">>" };

        final String text;
        final int n;
        final List<SyntaxTokenizer.Span> spans;
        final int base;
        final Function<String, TokenKind> names;
        final Context ctx;

        // tokens
        int nt;
        int[] tType = new int[256];
        int[] tStart = new int[256];
        int[] tEnd = new int[256];
        String[] tText = new String[256];
        int[] partner;
        int[] encl;

        // logical lines
        int nl;
        int[] lFrom = new int[64];
        int[] lTo = new int[64];
        int[] lIndent = new int[64];

        // occurrences
        int no;
        int[] oStart = new int[128];
        int[] oEnd = new int[128];
        int[] oFlags = new int[128];
        String[] oName = new String[128];
        Scope[] oScope = new Scope[128];
        Symbol[] oSym = new Symbol[128];
        Role[] oFixed = new Role[128];
        boolean[] oCaptured = new boolean[128];

        final Scope module = new Scope(ScopeKind.MODULE, "module", null);
        final Deque<Frame> blocks = new ArrayDeque<>();
        final Deque<LoopFrame> loops = new ArrayDeque<>();
        final Deque<LoopFrame> compLoops = new ArrayDeque<>();
        final List<String> decorators = new ArrayList<>();
        final List<Finding> findings = new ArrayList<>();
        final List<Lens> lenses = new ArrayList<>();
        final List<String> weak = new ArrayList<>();
        boolean opaque;
        int valueStart = -1;
        boolean valueEscapes;
        /** End offset of the statement being read, for where an assignment takes effect. */
        int statementEnd = -1;
        private final Map<String, Global> globalCache = new HashMap<>();

        Analyzer(String text, List<SyntaxTokenizer.Span> spans, int base, Function<String, TokenKind> names, Context ctx) {
            this.text = text;
            this.n = text.length();
            this.spans = spans;
            this.base = base;
            this.names = names;
            this.ctx = ctx;
        }

        // ---- Tokens --------------------------------------------------------

        void add(int type, int start, int end, String t) {
            if (nt == tType.length) {
                int grow = nt * 2;
                tType = Arrays.copyOf(tType, grow);
                tStart = Arrays.copyOf(tStart, grow);
                tEnd = Arrays.copyOf(tEnd, grow);
                tText = Arrays.copyOf(tText, grow);
            }
            tType[nt] = type;
            tStart[nt] = start;
            tEnd[nt] = end;
            tText[nt] = t;
            nt++;
        }

        /**
         * The code between the lexer's literals: strings, numbers and magics become
         * atoms, comments vanish, keywords are taken as the lexer read them (match,
         * case and type are keywords only where it said so), names and operators
         * are read here.
         */
        void tokenize() {
            int si = base;
            final int se = spans.size();
            int i = 0;
            int depth = 0;
            int openerIndent = 0;
            boolean lineHasTokens = false;
            while (i < n) {
                while (si < se && spans.get(si).end <= i) {
                    si++;
                }
                if (si < se && spans.get(si).start <= i) {
                    SyntaxTokenizer.Span s = spans.get(si);
                    TokenKind k = s.kind;
                    if (k == TokenKind.STRING || k == TokenKind.CHARACTER || k == TokenKind.NUMBER) {
                        add(T_ATOM, s.start, s.end, null);
                        lineHasTokens = true;
                        i = s.end;
                        continue;
                    }
                    if (k == TokenKind.COMMENT) {
                        i = s.end;
                        continue;
                    }
                    if (k == TokenKind.PREPROCESSOR) {
                        magicWords(s.start, s.end);
                        add(T_ATOM, s.start, s.end, null);
                        lineHasTokens = true;
                        i = s.end;
                        continue;
                    }
                    if (k == TokenKind.DECORATOR) {
                        add(T_DECO, s.start, s.end, text.substring(s.start, s.end));
                        lineHasTokens = true;
                        i = s.end;
                        continue;
                    }
                    if (k == TokenKind.KEYWORD) {
                        add(T_KW, s.start, s.end, text.substring(s.start, s.end));
                        lineHasTokens = true;
                        i = s.end;
                        continue;
                    }
                    if (k == TokenKind.CONSTANT && KEYWORD_ATOMS.contains(text.substring(s.start, s.end))) {
                        add(T_ATOM, s.start, s.end, null);
                        lineHasTokens = true;
                        i = s.end;
                        continue;
                    }
                    // names and operators: read below
                }
                char c = text.charAt(i);
                if (c == '\n') {
                    if (depth > 0 && statementFollows(text, i + 1, openerIndent)) {
                        depth = 0; // a bracket left open while typing: the code below is still code
                    }
                    if (depth == 0 && lineHasTokens) {
                        add(T_NL, i, i, null);
                        lineHasTokens = false;
                    }
                    i++;
                    continue;
                }
                if (c == ' ' || c == '\t' || c == '\r' || c == '\f') {
                    i++;
                    continue;
                }
                if (c == '\\') {
                    int k = i + 1;
                    if (k < n && text.charAt(k) == '\r') {
                        k++;
                    }
                    i = k < n && text.charAt(k) == '\n' ? k + 1 : i + 1; // a continued line is one logical line
                    continue;
                }
                if (PythonLexer.isIdentifierStart(c)) {
                    int j = i + 1;
                    while (j < n && PythonLexer.isIdentifierPart(text.charAt(j))) {
                        j++;
                    }
                    String w = text.substring(i, j);
                    if (nt > 0 && tType[nt - 1] == T_OP && "!".equals(tText[nt - 1]) && tEnd[nt - 1] == i
                            && w.length() == 1 && "rsa".indexOf(w.charAt(0)) >= 0) {
                        i = j; // the conversion of an f-string field: f"{x!r}"
                        continue;
                    }
                    add(PythonLexer.KEYWORDS.contains(w) ? T_KW : T_NAME, i, j, w);
                    lineHasTokens = true;
                    i = j;
                    continue;
                }
                if (c >= '0' && c <= '9') {
                    int j = i + 1;
                    while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_' || text.charAt(j) == '.')) {
                        j++;
                    }
                    add(T_ATOM, i, j, null);
                    lineHasTokens = true;
                    i = j;
                    continue;
                }
                if (c == '(' || c == '[' || c == '{') {
                    add(T_OPEN, i, i + 1, String.valueOf(c));
                    if (depth == 0) {
                        openerIndent = indentAt(text, i);
                    }
                    depth++;
                    lineHasTokens = true;
                    i++;
                    continue;
                }
                if (c == ')' || c == ']' || c == '}') {
                    add(T_CLOSE, i, i + 1, String.valueOf(c));
                    depth = Math.max(0, depth - 1);
                    lineHasTokens = true;
                    i++;
                    continue;
                }
                if (c == '.' && text.startsWith("...", i)) {
                    add(T_ATOM, i, i + 3, null);
                    lineHasTokens = true;
                    i += 3;
                    continue;
                }
                String op = operator(i);
                if (op != null) {
                    add(T_OP, i, i + op.length(), op);
                    lineHasTokens = true;
                    i += op.length();
                    continue;
                }
                i++;
            }
            if (lineHasTokens) {
                add(T_NL, n, n, null);
            }
            partner = new int[nt];
            encl = new int[nt];
            int[] stack = new int[Math.max(8, nt)];
            int sp = 0;
            for (int t = 0; t < nt; t++) {
                partner[t] = -1;
                if (tType[t] == T_NL) {
                    sp = 0;
                    encl[t] = -1;
                    continue;
                }
                encl[t] = sp > 0 ? stack[sp - 1] : -1;
                if (tType[t] == T_OPEN) {
                    stack[sp++] = t;
                } else if (tType[t] == T_CLOSE && sp > 0 && pairs(tText[stack[sp - 1]].charAt(0), tText[t].charAt(0))) {
                    int o = stack[--sp];
                    partner[o] = t;
                    partner[t] = o;
                    encl[t] = encl[o];
                }
            }
        }

        private static boolean pairs(char open, char close) {
            return open == '(' && close == ')' || open == '[' && close == ']' || open == '{' && close == '}';
        }

        private String operator(int i) {
            for (String op : OPS3) {
                if (text.startsWith(op, i)) {
                    return op;
                }
            }
            for (String op : OPS2) {
                if (text.startsWith(op, i)) {
                    return op;
                }
            }
            char c = text.charAt(i);
            return "+-*/%@&|^~<>=!:.,;".indexOf(c) >= 0 ? String.valueOf(c) : null;
        }

        /** Names in a magic or shell line (%timeit f(x), !echo {x}) are read, though not as Python. */
        private void magicWords(int start, int end) {
            int i = start;
            while (i < end) {
                char c = text.charAt(i);
                if (PythonLexer.isIdentifierStart(c) && (i == start || !PythonLexer.isIdentifierPart(text.charAt(i - 1)))) {
                    int j = i + 1;
                    while (j < end && PythonLexer.isIdentifierPart(text.charAt(j))) {
                        j++;
                    }
                    weak.add(text.substring(i, j));
                    i = j;
                    continue;
                }
                i++;
            }
        }

        // ---- Logical lines -------------------------------------------------

        void lines() {
            int from = 0;
            for (int t = 0; t < nt; t++) {
                if (tType[t] == T_NL) {
                    if (t > from) {
                        addLine(from, t);
                    }
                    from = t + 1;
                }
            }
            if (from < nt) {
                addLine(from, nt);
            }
        }

        private void addLine(int from, int to) {
            if (nl == lFrom.length) {
                int grow = nl * 2;
                lFrom = Arrays.copyOf(lFrom, grow);
                lTo = Arrays.copyOf(lTo, grow);
                lIndent = Arrays.copyOf(lIndent, grow);
            }
            lFrom[nl] = from;
            lTo[nl] = to;
            lIndent[nl] = indentOf(tStart[from]);
            nl++;
        }

        private int indentOf(int offset) {
            int ls = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
            if (offset == 0) {
                ls = 0;
            }
            int col = 0;
            for (int k = ls; k < offset; k++) {
                char c = text.charAt(k);
                if (c == ' ') {
                    col++;
                } else if (c == '\t') {
                    col = (col / 8 + 1) * 8;
                } else if (c != '\f' && c != '\r') {
                    break;
                }
            }
            return col;
        }

        // ---- Token helpers -------------------------------------------------

        boolean isOp(int i, String op) {
            return i >= 0 && i < nt && tType[i] == T_OP && op.equals(tText[i]);
        }

        boolean isKw(int i, String kw) {
            return i >= 0 && i < nt && tType[i] == T_KW && kw.equals(tText[i]);
        }

        boolean isOpen(int i, String bracket) {
            return i >= 0 && i < nt && tType[i] == T_OPEN && bracket.equals(tText[i]);
        }

        String kw(int i) {
            return i >= 0 && i < nt && tType[i] == T_KW ? tText[i] : null;
        }

        int close(int open, int b) {
            int c = partner[open];
            return c > open && c < b ? c : b;
        }

        private static boolean isAugmented(String op) {
            return op.length() >= 2 && op.endsWith("=") && !op.equals("==") && !op.equals("!=")
                   && !op.equals("<=") && !op.equals(">=") && !op.equals(":=");
        }

        Scope scope(ScopeKind kind, String label, Scope parent) {
            return new Scope(kind, label, parent);
        }

        // ---- Occurrences ---------------------------------------------------

        int occ(int t, Scope s, int flags, Symbol sym, Role fixed) {
            if (no == oStart.length) {
                int grow = no * 2;
                oStart = Arrays.copyOf(oStart, grow);
                oEnd = Arrays.copyOf(oEnd, grow);
                oFlags = Arrays.copyOf(oFlags, grow);
                oName = Arrays.copyOf(oName, grow);
                oScope = Arrays.copyOf(oScope, grow);
                oSym = Arrays.copyOf(oSym, grow);
                oFixed = Arrays.copyOf(oFixed, grow);
                oCaptured = Arrays.copyOf(oCaptured, grow);
            }
            oStart[no] = tStart[t];
            oEnd[no] = tEnd[t];
            oFlags[no] = flags;
            oName[no] = tText[t];
            oScope[no] = s;
            oSym[no] = sym;
            oFixed[no] = fixed;
            oCaptured[no] = false;
            return no++;
        }

        Symbol bind(int t, Scope s, Binding kind) {
            String name = tText[t];
            Scope target = bindingScope(name, s, kind);
            Symbol sym = target.symbols.get(name);
            if (sym == null) {
                sym = new Symbol(name, target);
                target.symbols.put(name, sym);
            }
            sym.bind(tStart[t], kind);
            // an assignment binds once its value has been computed: at the end of its statement
            int effective = statementEnd > tStart[t] && (kind == Binding.VAR || kind == Binding.AUG
                            || kind == Binding.ANNOTATION) ? statementEnd : tStart[t];
            sym.firstEffective = Math.min(sym.firstEffective, effective);
            int o = occ(t, s, F_BIND | (kind == Binding.AUG ? F_READ : 0), sym, null);
            // nonlocal n; n += 1: the binding reaches into the enclosing function's cell
            oCaptured[o] = target != s && target != module && kind != Binding.WALRUS;
            return sym;
        }

        private Scope bindingScope(String name, Scope s, Binding kind) {
            Scope target = s;
            if (kind == Binding.WALRUS) {
                while (target.kind == ScopeKind.COMPREHENSION && target.parent != null) {
                    target = target.parent;
                }
            }
            if (target.kind != ScopeKind.MODULE && target.globals.contains(name)) {
                return module;
            }
            if (target.nonlocals.contains(name)) {
                for (Scope p = target.parent; p != null && p != module; p = p.parent) {
                    if (p.kind != ScopeKind.CLASS && p.symbols.containsKey(name)) {
                        return p;
                    }
                }
                for (Scope p = target.parent; p != null && p != module; p = p.parent) {
                    if (p.kind == ScopeKind.FUNCTION || p.kind == ScopeKind.LAMBDA) {
                        return p;
                    }
                }
            }
            return target;
        }

        void use(int t, Scope s, int flags) {
            occ(t, s, flags, null, null);
        }

        void fixed(int t, Scope s, Role role) {
            int f = t + 1 < nt && isOpen(t + 1, "(") ? F_CALL : 0;
            occ(t, s, f, null, role);
        }

        void attribute(int t, Scope s) {
            boolean call = t + 1 < nt && isOpen(t + 1, "(");
            occ(t, s, call ? F_CALL : 0, null, call ? Role.METHOD : Role.ATTRIBUTE);
        }

        // ---- Statements ----------------------------------------------------

        void parse() {
            for (int line = 0; line < nl; line++) {
                int ind = lIndent[line];
                while (!blocks.isEmpty() && ind <= blocks.peek().indent()) {
                    blocks.pop();
                }
                while (!loops.isEmpty() && ind <= loops.peek().indent()) {
                    loops.pop();
                }
                Scope s = blocks.isEmpty() ? module : blocks.peek().scope();
                line(line, s);
            }
        }

        void line(int line, Scope s) {
            int a = lFrom[line];
            int b = lTo[line];
            if (tType[a] == T_DECO) {
                decorator(a, s);
                expr(a + 1, b, s, 0);
                return;
            }
            int t = a;
            String k = kw(t);
            if ("async".equals(k) && t + 1 < b) {
                t++;
                k = kw(t);
            }
            if (k == null) {
                statements(a, b, s);
                decorators.clear();
                return;
            }
            switch (k) {
                case "def":
                    function(line, t, b, s);
                    break;
                case "class":
                    klass(line, t, b, s);
                    break;
                case "for":
                    forLoop(line, t, b, s);
                    break;
                case "while":
                case "if":
                case "elif":
                case "match": {
                    int c = headerColon(t + 1, b);
                    expr(t + 1, c, s, 0);
                    suite(c + 1, b, s);
                    break;
                }
                case "else":
                case "try":
                case "finally": {
                    int c = headerColon(t + 1, b);
                    suite(c + 1, b, s);
                    break;
                }
                case "with":
                    with(t, b, s);
                    break;
                case "except":
                    except(t, b, s);
                    break;
                case "case":
                    caseClause(t, b, s);
                    break;
                default:
                    statements(a, b, s);
            }
            decorators.clear();
        }

        int headerColon(int from, int b) {
            for (int i = from; i < b; i++) {
                if (encl[i] != -1) {
                    continue;
                }
                if (isKw(i, "lambda")) {
                    i = lambdaColon(i, b);
                    continue;
                }
                if (isOp(i, ":")) {
                    return i;
                }
            }
            return b;
        }

        int lambdaColon(int k, int b) {
            int level = encl[k];
            for (int i = k + 1; i < b; i++) {
                if (encl[i] == level && isOp(i, ":")) {
                    return i;
                }
            }
            return b;
        }

        void suite(int from, int b, Scope s) {
            if (from < b) {
                statements(from, b, s);
            }
        }

        void statements(int a, int b, Scope s) {
            int start = a;
            for (int i = a; i < b; i++) {
                if (encl[i] == -1 && isOp(i, ";")) {
                    if (i > start) {
                        statement(start, i, s);
                    }
                    start = i + 1;
                }
            }
            if (start < b) {
                statement(start, b, s);
            }
        }

        void statement(int a, int b, Scope s) {
            statementEnd = b > a && b - 1 < nt ? tEnd[b - 1] : -1;
            String k = kw(a);
            if (k != null) {
                switch (k) {
                    case "import":
                        lens(b, s, importNames(a + 1, b, s));
                        return;
                    case "from":
                        lens(b, s, fromImport(a + 1, b, s));
                        return;
                    case "global":
                    case "nonlocal":
                        declare(a + 1, b, s, k.equals("global"));
                        return;
                    case "del":
                        expr(a + 1, b, s, F_DEL);
                        return;
                    case "type":
                        typeAlias(a + 1, b, s);
                        return;
                    case "return":
                    case "yield":
                    case "raise":
                    case "assert":
                    case "await":
                    case "pass":
                    case "break":
                    case "continue":
                        expr(a + 1, b, s, 0);
                        return;
                    default:
                        break;
                }
            }
            int aug = -1;
            int annot = -1;
            List<Integer> eqs = new ArrayList<>(2);
            for (int i = a; i < b; i++) {
                if (encl[i] != -1) {
                    continue;
                }
                if (isKw(i, "lambda")) {
                    i = lambdaColon(i, b);
                    continue;
                }
                if (tType[i] != T_OP) {
                    continue;
                }
                String op = tText[i];
                if (op.equals("=")) {
                    eqs.add(i);
                } else if (aug < 0 && eqs.isEmpty() && annot < 0 && isAugmented(op)) {
                    aug = i;
                    break;
                } else if (op.equals(":") && annot < 0 && eqs.isEmpty()) {
                    annot = i;
                }
            }
            if (aug >= 0) {
                expr(aug + 1, b, s, 0);
                lens(b, s, target(a, aug, s, Binding.AUG));
                return;
            }
            if (!eqs.isEmpty()) {
                int last = eqs.get(eqs.size() - 1);
                boolean container = false;
                int segStart = a;
                for (int e : eqs) {
                    int segEnd = annot >= 0 && segStart == a && annot < e ? annot : e;
                    if (!simpleTarget(segStart, segEnd)) {
                        container = true;
                    }
                    segStart = e + 1;
                }
                valueStart = last + 1;
                valueEscapes = container;
                expr(last + 1, b, s, 0);
                valueStart = -1;
                valueEscapes = false;
                List<String> bound = new ArrayList<>();
                segStart = a;
                for (int e : eqs) {
                    if (annot >= 0 && segStart == a && annot < e) {
                        expr(annot + 1, e, s, 0);
                        List<String> names = target(a, annot, s, Binding.VAR);
                        if (mentions(annot + 1, e, "Final")) {
                            for (String nm : names) {
                                Symbol sym = bindingScope(nm, s, Binding.VAR).symbols.get(nm);
                                if (sym != null) {
                                    sym.isFinal = true;
                                }
                            }
                        }
                        bound.addAll(names);
                    } else {
                        bound.addAll(target(segStart, e, s, Binding.VAR));
                    }
                    segStart = e + 1;
                }
                lens(b, s, bound);
                return;
            }
            if (annot >= 0) {
                expr(annot + 1, b, s, 0);
                List<String> names = target(a, annot, s, Binding.ANNOTATION);
                if (mentions(annot + 1, b, "Final")) {
                    for (String nm : names) {
                        Symbol sym = bindingScope(nm, s, Binding.VAR).symbols.get(nm);
                        if (sym != null) {
                            sym.isFinal = true;
                        }
                    }
                }
                return;
            }
            expr(a, b, s, 0);
        }

        private boolean mentions(int a, int b, String word) {
            for (int i = a; i < b; i++) {
                if (tType[i] == T_NAME && word.equals(tText[i])) {
                    return true;
                }
            }
            return false;
        }

        /** Names only, possibly unpacked: no attribute, no subscript. */
        private boolean simpleTarget(int a, int b) {
            for (int i = a; i < b; i++) {
                if (isOp(i, ".") || isOpen(i, "[") && i > a && (tType[i - 1] == T_NAME || tType[i - 1] == T_CLOSE)) {
                    return false;
                }
            }
            return true;
        }

        private void lens(int b, Scope s, List<String> bound) {
            if (s != module || bound == null || bound.isEmpty() || b <= 0) {
                return;
            }
            int last = Math.min(b, nt) - 1;
            lenses.add(new Lens(lineOf(text, tStart[last]) + 1, List.copyOf(bound)));
        }

        // ---- Binding targets -----------------------------------------------

        List<String> target(int a, int b, Scope s, Binding kind) {
            List<String> bound = new ArrayList<>();
            if (a >= b) {
                return bound;
            }
            int level = encl[a];
            int start = a;
            for (int i = a; i <= b; i++) {
                if (i == b || isOp(i, ",") && encl[i] == level) {
                    element(start, i, s, kind, bound);
                    start = i + 1;
                }
            }
            return bound;
        }

        private void element(int a, int b, Scope s, Binding kind, List<String> bound) {
            if (a >= b) {
                return;
            }
            int p = a;
            boolean starred = false;
            if (isOp(p, "*") || isOp(p, "**")) {
                p++;
                starred = true;
            }
            if (p >= b) {
                return;
            }
            if (b - p == 1 && tType[p] == T_NAME) {
                Symbol sym = bind(p, s, kind);
                sym.starred |= starred && kind == Binding.PARAM;
                bound.add(tText[p]);
                return;
            }
            if (tType[p] == T_OPEN && partner[p] == b - 1 && (tText[p].equals("(") || tText[p].equals("["))) {
                bound.addAll(target(p + 1, b - 1, s, kind));
                return;
            }
            // a.b = ..., a[i] = ...: the object is read and changed in place; nothing is bound
            expr(p, b, s, F_MUTATED);
        }

        // ---- Expressions ---------------------------------------------------

        void expr(int a, int b, Scope s, int extra) {
            boolean first = true;
            for (int i = a; i < b; i++) {
                int ty = tType[i];
                if (ty == T_KW) {
                    String k = tText[i];
                    if (k.equals("lambda")) {
                        i = lambda(i, b, s) - 1;
                    } else if (k.equals("is")) {
                        isLiteral(i, b);
                    }
                    first = false;
                    continue;
                }
                if (ty == T_OPEN) {
                    // a bracket still open (being typed) holds everything to the end of the statement
                    int close = partner[i] >= 0 ? partner[i] : b;
                    if (close > i && close <= b && comprehensionFor(i, close) >= 0) {
                        comprehension(i, close, s);
                        i = close;
                        first = false;
                    }
                    continue;
                }
                if (ty == T_DECO) {
                    decorator(i, s);
                    continue;
                }
                if (ty != T_NAME) {
                    if (ty != T_CLOSE) {
                        first = false;
                    }
                    continue;
                }
                if (isOp(i - 1, ".")) {
                    attribute(i, s);
                    first = false;
                    continue;
                }
                if (i + 1 < nt && isOp(i + 1, "=") && keywordArgument(i)) {
                    fixed(i, s, Role.KWARG);
                    first = false;
                    continue;
                }
                if (i + 1 < nt && isOp(i + 1, ":=")) {
                    bind(i, s, Binding.WALRUS);
                    first = false;
                    continue;
                }
                int f = F_READ | (extra & F_DEL);
                if (i + 1 < nt) {
                    if (isOpen(i + 1, "(")) {
                        f |= F_CALL;
                    } else if (isOpen(i + 1, "[")) {
                        f |= F_SUB;
                    } else if (isOp(i + 1, ".")) {
                        f |= F_ATTR_BASE;
                        if (i + 3 < nt && tType[i + 2] == T_NAME && isOpen(i + 3, "(")
                            && (MUTATORS.contains(tText[i + 2]) || inplace(i + 3))) {
                            f |= F_MUTATED;
                        }
                    }
                }
                if ((extra & F_MUTATED) != 0 && first && (f & (F_SUB | F_ATTR_BASE)) != 0) {
                    f |= F_MUTATED;
                }
                use(i, s, f);
                first = false;
            }
        }

        /** pandas' x.method(..., inplace=True): the call changes x rather than return a changed copy. */
        boolean inplace(int open) {
            int close = partner[open] >= 0 ? partner[open] : nt;
            for (int k = open + 1; k + 2 < close; k++) {
                if (encl[k] == open && tType[k] == T_NAME && "inplace".equals(tText[k]) && isOp(k + 1, "=")
                    && "True".equals(text.substring(tStart[k + 2], tEnd[k + 2]))) {
                    return true; // True is an atom: its text is the source's
                }
            }
            return false;
        }

        /** name= inside the parentheses of a call: an argument's name, not a variable. */
        boolean keywordArgument(int i) {
            int o = encl[i];
            if (o < 0 || !"(".equals(tText[o])) {
                return false;
            }
            int before = i - 1;
            if (!(before == o || isOp(before, ",") && encl[before] == o)) {
                return false;
            }
            int p = o - 1;
            return p >= 0 && (tType[p] == T_NAME || tType[p] == T_CLOSE || tType[p] == T_ATOM || tType[p] == T_DECO);
        }

        int lambda(int k, int b, Scope s) {
            Scope l = scope(ScopeKind.LAMBDA, "lambda", s);
            l.escapingLoops = escapingLoops(k, s);
            int colon = lambdaColon(k, b);
            params(k + 1, Math.min(colon, b), l, s, false, true);
            if (colon >= b) {
                return b;
            }
            int end = lambdaEnd(colon + 1, b, encl[k]);
            expr(colon + 1, end, l, 0);
            return Math.max(end, k + 1);
        }

        int lambdaEnd(int from, int b, int level) {
            for (int i = from; i < b; i++) {
                if (tType[i] == T_CLOSE && partner[i] >= 0 && partner[i] == level) {
                    return i;
                }
                if (encl[i] != level) {
                    continue;
                }
                if (isOp(i, ",") || isKw(i, "for") || isKw(i, "async") && isKw(i + 1, "for")) {
                    return i;
                }
            }
            return b;
        }

        /** The loops whose targets a lambda made at k would see too late: it is collected or stored. */
        private List<LoopFrame> escapingLoops(int k, Scope s) {
            List<LoopFrame> out = new ArrayList<>();
            for (LoopFrame f : compLoops) {
                if (f.scope() == s) {
                    out.add(f);
                }
            }
            if (!loops.isEmpty() && storedAway(k)) {
                for (LoopFrame f : loops) {
                    if (f.scope() == s) {
                        out.add(f);
                    }
                }
            }
            return out;
        }

        private boolean storedAway(int k) {
            if (k == valueStart && valueEscapes) {
                return true;
            }
            int o = encl[k];
            return o >= 2 && "(".equals(tText[o]) && (k == o + 1 || isOp(k - 1, ",") && encl[k - 1] == o)
                   && tType[o - 1] == T_NAME && isOp(o - 2, ".") && STORES.contains(tText[o - 1]);
        }

        int comprehensionFor(int open, int close) {
            for (int i = open + 1; i < close && i < nt; i++) {
                if (encl[i] == open && isKw(i, "for")) {
                    return i;
                }
            }
            return -1;
        }

        void comprehension(int open, int close, Scope s) {
            Scope c = scope(ScopeKind.COMPREHENSION, "comprehension", s);
            int firstFor = comprehensionFor(open, close);
            int elementEnd = firstFor > open + 1 && isKw(firstFor - 1, "async") && encl[firstFor - 1] == open
                             ? firstFor - 1 : firstFor;
            Set<String> targets = new HashSet<>();
            int f = firstFor;
            boolean firstClause = true;
            while (f >= 0 && f < close) {
                int next = close;
                for (int i = f + 1; i < close; i++) {
                    if (encl[i] == open && isKw(i, "for")) {
                        next = i;
                        break;
                    }
                }
                int in = -1;
                for (int i = f + 1; i < next; i++) {
                    if (encl[i] == open && isKw(i, "in")) {
                        in = i;
                        break;
                    }
                }
                if (in < 0) {
                    expr(f + 1, next, c, 0);
                } else {
                    targets.addAll(target(f + 1, in, c, Binding.LOOP));
                    int iterEnd = next;
                    for (int i = in + 1; i < next; i++) {
                        if (encl[i] == open && isKw(i, "if")) {
                            iterEnd = i;
                            break;
                        }
                    }
                    expr(in + 1, iterEnd, firstClause ? s : c, 0);
                    int k = iterEnd;
                    while (k < next) {
                        int e = next;
                        for (int i = k + 1; i < next; i++) {
                            if (encl[i] == open && isKw(i, "if")) {
                                e = i;
                                break;
                            }
                        }
                        expr(k + 1, e, c, 0);
                        k = e;
                    }
                }
                firstClause = false;
                f = next < close ? next : -1;
            }
            compLoops.push(new LoopFrame(-1, c, targets));
            expr(open + 1, elementEnd, c, 0);
            compLoops.pop();
        }

        void isLiteral(int i, int b) {
            int j = i + 1;
            if (isKw(j, "not")) {
                j++;
            }
            int lit = -1;
            if (j < b && tType[j] == T_ATOM && isLiteralToken(j)) {
                lit = j;
            } else if (i > 0 && tType[i - 1] == T_ATOM && isLiteralToken(i - 1)) {
                lit = i - 1;
            }
            if (lit >= 0) {
                findings.add(new Finding(tStart[Math.min(i, lit)], tEnd[Math.max(i, lit)], 1, "is-literal",
                    "'is' compares identities: against a literal it depends on how Python caches values "
                    + "(Python warns about it). Compare values with == (or != for 'is not')."));
            }
        }

        private boolean isLiteralToken(int t) {
            String lit = text.substring(tStart[t], tEnd[t]);
            if (lit.isEmpty() || lit.equals("...") || KEYWORD_ATOMS.contains(lit)) {
                return false;
            }
            char c = lit.charAt(0);
            return Character.isDigit(c) || c == '"' || c == '\'' || c == '.'
                   || lit.length() > 1 && Character.isLetter(c) && (lit.indexOf('"') > 0 && lit.indexOf('"') <= 2
                                                                     || lit.indexOf('\'') > 0 && lit.indexOf('\'') <= 2);
        }

        // ---- Functions, classes, parameters --------------------------------

        void function(int line, int t, int b, Scope s) {
            int i = t + 1;
            if (i >= b || tType[i] != T_NAME) {
                expr(i, b, s, 0);
                return;
            }
            String name = tText[i];
            bind(i, s, Binding.FUNC);
            boolean method = s.kind == ScopeKind.CLASS;
            Scope f = scope(ScopeKind.FUNCTION, (method ? "method " : "function ") + name, s);
            boolean staticMethod = decorators.contains("staticmethod");
            int j = i + 1;
            if (j < b && isOpen(j, "[")) {
                int c = close(j, b);
                typeParams(j + 1, c, f);
                j = c + 1;
            }
            if (j < b && isOpen(j, "(")) {
                int c = close(j, b);
                params(j + 1, c, f, s, method && !staticMethod, false);
                j = c + 1;
            }
            int colon = headerColon(Math.min(j, b), b);
            if (j < colon && isOp(j, "->")) {
                expr(j + 1, colon, s, 0);
            }
            if (colon + 1 < b) {
                statements(colon + 1, b, f);
            } else {
                blocks.push(new Frame(lIndent[line], f));
            }
        }

        void klass(int line, int t, int b, Scope s) {
            int i = t + 1;
            if (i >= b || tType[i] != T_NAME) {
                expr(i, b, s, 0);
                return;
            }
            String name = tText[i];
            bind(i, s, Binding.CLASS);
            Scope c = scope(ScopeKind.CLASS, "class " + name, s);
            int j = i + 1;
            if (j < b && isOpen(j, "[")) {
                int cl = close(j, b);
                typeParams(j + 1, cl, c);
                j = cl + 1;
            }
            if (j < b && isOpen(j, "(")) {
                int cl = close(j, b);
                expr(j + 1, cl, s, 0);
                j = cl + 1;
            }
            int colon = headerColon(Math.min(j, b), b);
            if (colon + 1 < b) {
                statements(colon + 1, b, c);
            } else {
                blocks.push(new Frame(lIndent[line], c));
            }
        }

        void typeParams(int a, int b, Scope s) {
            if (a >= b) {
                return;
            }
            int level = encl[a];
            int start = a;
            for (int i = a; i <= b; i++) {
                if (i == b || isOp(i, ",") && encl[i] == level) {
                    int p = start;
                    while (p < i && (isOp(p, "*") || isOp(p, "**"))) {
                        p++;
                    }
                    if (p < i && tType[p] == T_NAME) {
                        bind(p, s, Binding.TYPE_PARAM);
                        if (p + 1 < i) {
                            expr(p + 2, i, s, 0); // its bound or default
                        }
                    }
                    start = i + 1;
                }
            }
        }

        void params(int a, int b, Scope f, Scope outer, boolean method, boolean isLambda) {
            if (a >= b) {
                return;
            }
            int level = encl[a];
            int start = a;
            boolean selfSlot = method;
            for (int i = a; i <= b; i++) {
                if (i == b || isOp(i, ",") && encl[i] == level) {
                    if (param(start, i, f, outer, selfSlot, isLambda)) {
                        selfSlot = false;
                    }
                    start = i + 1;
                }
            }
        }

        /** One parameter; false for the bare * and / markers. */
        boolean param(int a, int b, Scope f, Scope outer, boolean selfSlot, boolean isLambda) {
            if (a >= b) {
                return false;
            }
            int p = a;
            boolean starred = false;
            if (isOp(p, "*") || isOp(p, "**")) {
                starred = true;
                p++;
            }
            if (p >= b || tType[p] != T_NAME) {
                return false;
            }
            String name = tText[p];
            boolean self = !starred && (selfSlot || name.equals("self") || name.equals("cls"));
            Symbol sym = bind(p, f, self ? Binding.SELF : Binding.PARAM);
            sym.starred |= starred;
            int eq = -1;
            int colon = -1;
            for (int i = p + 1; i < b; i++) {
                if (encl[i] != encl[p]) {
                    continue;
                }
                if (colon < 0 && !isLambda && isOp(i, ":")) {
                    colon = i;
                }
                if (isOp(i, "=")) {
                    eq = i;
                    break;
                }
            }
            if (colon >= 0) {
                expr(colon + 1, eq >= 0 ? eq : b, outer, 0); // an annotation is read where the function is made
            }
            if (eq >= 0) {
                mutableDefault(eq + 1, b, name);
                expr(eq + 1, b, outer, 0);
            }
            return true;
        }

        private void mutableDefault(int a, int b, String param) {
            if (a >= b) {
                return;
            }
            boolean display = tType[a] == T_OPEN && (tText[a].equals("[") || tText[a].equals("{"));
            boolean factory = tType[a] == T_NAME && MUTABLE_FACTORIES.contains(tText[a]) && isOpen(a + 1, "(");
            if (display || factory) {
                findings.add(new Finding(tStart[a], tEnd[b - 1], 1, "mutable-default",
                    "The default of '" + param + "' is made once, when the function is defined, and shared by every "
                    + "call: what one call adds to it, the next one sees. Default to None and create it inside."));
            }
        }

        void decorator(int t, Scope s) {
            String d = tText[t];
            int i = 1;
            while (i < d.length() && Character.isWhitespace(d.charAt(i))) {
                i++;
            }
            int j = i;
            while (j < d.length() && PythonLexer.isIdentifierPart(d.charAt(j))) {
                j++;
            }
            if (j > i) {
                weakUse(d.substring(i, j), s);
            }
            int last = d.lastIndexOf('.');
            decorators.add(last >= 0 ? d.substring(last + 1).strip() : d.substring(i).strip());
        }

        private final List<Object[]> weakScoped = new ArrayList<>();

        private void weakUse(String name, Scope s) {
            weakScoped.add(new Object[] { name, s });
        }

        // ---- Compound statements -------------------------------------------

        void forLoop(int line, int t, int b, Scope s) {
            int in = -1;
            for (int i = t + 1; i < b; i++) {
                if (encl[i] == -1 && isKw(i, "in")) {
                    in = i;
                    break;
                }
            }
            if (in < 0) {
                expr(t + 1, b, s, 0);
                return;
            }
            int colon = headerColon(in + 1, b);
            expr(in + 1, colon, s, 0);
            List<String> targets = target(t + 1, in, s, Binding.LOOP);
            LoopFrame lf = new LoopFrame(lIndent[line], s, new HashSet<>(targets));
            if (colon + 1 < b) {
                loops.push(lf);
                statements(colon + 1, b, s);
                loops.pop();
            } else {
                loops.push(lf);
            }
        }

        void with(int t, int b, Scope s) {
            int colon = headerColon(t + 1, b);
            int segStart = t + 1;
            for (int i = t + 1; i < colon; i++) {
                if (isKw(i, "as")) {
                    expr(segStart, i, s, 0);
                    int level = encl[i];
                    int e = i + 1;
                    while (e < colon && !(isOp(e, ",") && encl[e] == level
                                          || tType[e] == T_CLOSE && partner[e] >= 0 && partner[e] == level)) {
                        e++;
                    }
                    target(i + 1, e, s, Binding.WITH);
                    i = e;
                    segStart = e;
                }
            }
            expr(segStart, colon, s, 0);
            suite(colon + 1, b, s);
        }

        void except(int t, int b, Scope s) {
            int colon = headerColon(t + 1, b);
            int as = -1;
            for (int i = t + 1; i < colon; i++) {
                if (encl[i] == -1 && isKw(i, "as")) {
                    as = i;
                    break;
                }
            }
            int from = t + 1;
            if (from < colon && isOp(from, "*")) {
                from++;
            }
            expr(from, as >= 0 ? as : colon, s, 0);
            if (as >= 0) {
                target(as + 1, colon, s, Binding.EXCEPT);
            }
            suite(colon + 1, b, s);
        }

        void caseClause(int t, int b, Scope s) {
            int colon = headerColon(t + 1, b);
            int guard = -1;
            for (int i = t + 1; i < colon; i++) {
                if (encl[i] == -1 && isKw(i, "if")) {
                    guard = i;
                    break;
                }
            }
            pattern(t + 1, guard >= 0 ? guard : colon, s);
            if (guard >= 0) {
                expr(guard + 1, colon, s, 0);
            }
            suite(colon + 1, b, s);
        }

        void pattern(int a, int b, Scope s) {
            for (int i = a; i < b; i++) {
                if (tType[i] != T_NAME) {
                    continue;
                }
                if (isOp(i - 1, ".")) {
                    attribute(i, s); // Color.RED
                    continue;
                }
                boolean beforeDot = i + 1 < b && isOp(i + 1, ".");
                boolean beforeCall = i + 1 < b && isOpen(i + 1, "(");
                if (i + 1 < b && isOp(i + 1, "=")) {
                    fixed(i, s, Role.ATTRIBUTE); // Point(x=0): the attribute matched
                    continue;
                }
                if (beforeDot || beforeCall) {
                    use(i, s, F_READ | (beforeCall ? F_CALL : F_ATTR_BASE));
                    continue;
                }
                if (tText[i].equals("_")) {
                    continue;
                }
                bind(i, s, Binding.MATCH); // a capture pattern
            }
        }

        // ---- Imports and declarations --------------------------------------

        List<String> importNames(int a, int b, Scope s) {
            List<String> bound = new ArrayList<>();
            int start = a;
            for (int i = a; i <= b; i++) {
                if (i == b || isOp(i, ",") && encl[i] == -1) {
                    importOne(start, i, s, bound);
                    start = i + 1;
                }
            }
            return bound;
        }

        private void importOne(int a, int b, Scope s, List<String> bound) {
            int as = -1;
            for (int i = a; i < b; i++) {
                if (isKw(i, "as")) {
                    as = i;
                    break;
                }
            }
            int end = as >= 0 ? as : b;
            List<Integer> parts = new ArrayList<>();
            for (int i = a; i < end; i++) {
                if (tType[i] == T_NAME) {
                    parts.add(i);
                }
            }
            if (parts.isEmpty()) {
                return;
            }
            if (as >= 0 && as + 1 < b && tType[as + 1] == T_NAME) {
                for (int p : parts) {
                    fixed(p, s, Role.MODULE);
                }
                bind(as + 1, s, Binding.IMPORT_MODULE);
                bound.add(tText[as + 1]);
            } else {
                bind(parts.get(0), s, Binding.IMPORT_MODULE);
                bound.add(tText[parts.get(0)]);
                for (int k = 1; k < parts.size(); k++) {
                    fixed(parts.get(k), s, Role.MODULE);
                }
            }
        }

        List<String> fromImport(int a, int b, Scope s) {
            List<String> bound = new ArrayList<>();
            int imp = -1;
            for (int i = a; i < b; i++) {
                if (isKw(i, "import")) {
                    imp = i;
                    break;
                }
            }
            int pathEnd = imp >= 0 ? imp : b;
            for (int i = a; i < pathEnd; i++) {
                if (tType[i] == T_NAME) {
                    fixed(i, s, Role.MODULE);
                }
            }
            if (imp < 0) {
                return bound;
            }
            if (imp + 1 < b && isOp(imp + 1, "*")) {
                opaque = true;
                return bound;
            }
            int start = imp + 1;
            for (int i = imp + 1; i <= b; i++) {
                if (i == b || isOp(i, ",")) {
                    fromOne(start, i, s, bound);
                    start = i + 1;
                }
            }
            return bound;
        }

        private void fromOne(int a, int b, Scope s, List<String> bound) {
            int as = -1;
            int orig = -1;
            int alias = -1;
            for (int i = a; i < b; i++) {
                if (isKw(i, "as")) {
                    as = i;
                } else if (tType[i] == T_NAME) {
                    if (as < 0 && orig < 0) {
                        orig = i;
                    } else if (as >= 0 && alias < 0) {
                        alias = i;
                    }
                }
            }
            if (orig < 0) {
                return;
            }
            if (alias >= 0) {
                Symbol sym = bind(alias, s, Binding.IMPORT_NAME);
                occ(orig, s, F_LINKED, sym, null);
                bound.add(tText[alias]);
            } else {
                bind(orig, s, Binding.IMPORT_NAME);
                bound.add(tText[orig]);
            }
        }

        void declare(int a, int b, Scope s, boolean global) {
            for (int i = a; i < b; i++) {
                if (tType[i] != T_NAME) {
                    continue;
                }
                if (s != module) {
                    (global ? s.globals : s.nonlocals).add(tText[i]);
                }
                occ(i, s, F_DECL, null, null);
            }
        }

        void typeAlias(int a, int b, Scope s) {
            if (a >= b || tType[a] != T_NAME) {
                expr(a, b, s, 0);
                return;
            }
            bind(a, s, Binding.CLASS);
            Scope ann = scope(ScopeKind.FUNCTION, "type alias " + tText[a], s);
            int j = a + 1;
            if (j < b && isOpen(j, "[")) {
                int c = close(j, b);
                typeParams(j + 1, c, ann);
                j = c + 1;
            }
            if (j < b && isOp(j, "=")) {
                expr(j + 1, b, ann, 0);
            }
        }

        // ---- Resolution ----------------------------------------------------

        Symbol lookup(String name, Scope scope, int o) {
            Scope sc = scope;
            if (sc.kind != ScopeKind.MODULE) {
                if (sc.globals.contains(name)) {
                    return module.symbols.get(name);
                }
                if (sc.nonlocals.contains(name)) {
                    for (Scope p = sc.parent; p != null && p != module; p = p.parent) {
                        if (p.kind != ScopeKind.CLASS && p.symbols.containsKey(name)) {
                            oCaptured[o] = true;
                            return p.symbols.get(name);
                        }
                    }
                    return null;
                }
                Symbol own = sc.symbols.get(name);
                if (own != null) {
                    return own;
                }
                for (Scope p = sc.parent; p != null && p != module; p = p.parent) {
                    if (p.kind == ScopeKind.CLASS) {
                        continue; // a class body is invisible to the scopes inside it
                    }
                    if (p.globals.contains(name)) {
                        break;
                    }
                    Symbol x = p.symbols.get(name);
                    if (x != null) {
                        oCaptured[o] = true;
                        return x;
                    }
                }
            }
            return module.symbols.get(name);
        }

        /** True when code in this scope runs as the text runs: no function or lambda body in between. */
        private boolean immediate(Scope scope) {
            for (Scope s = scope; s != null && s != module; s = s.parent) {
                if (s.kind == ScopeKind.FUNCTION || s.kind == ScopeKind.LAMBDA) {
                    return false;
                }
            }
            return true;
        }

        Global global(String name) {
            if (ctx == null) {
                return null;
            }
            if (globalCache.containsKey(name)) {
                return globalCache.get(name);
            }
            Global g;
            try {
                g = ctx.global(name);
            } catch (RuntimeException e) {
                g = null;
            }
            globalCache.put(name, g);
            return g;
        }

        Result resolve() {
            Map<String, Integer> extReads = new HashMap<>();
            Set<String> extCalled = new HashSet<>();
            Set<String> extAttr = new HashSet<>();
            for (int o = 0; o < no; o++) {
                if (oFixed[o] != null) {
                    continue;
                }
                int f = oFlags[o];
                if (oSym[o] == null) {
                    oSym[o] = lookup(oName[o], oScope[o], o);
                }
                if ((f & (F_LINKED | F_DECL)) != 0 || (f & F_READ) == 0) {
                    continue;
                }
                Symbol sym = oSym[o];
                if (sym != null) {
                    sym.reads++;
                    sym.called |= (f & F_CALL) != 0;
                    sym.attrBase |= (f & F_ATTR_BASE) != 0;
                } else {
                    extReads.merge(oName[o], 1, Integer::sum);
                    if ((f & F_CALL) != 0) {
                        extCalled.add(oName[o]);
                    }
                    if ((f & F_ATTR_BASE) != 0) {
                        extAttr.add(oName[o]);
                    }
                }
            }
            for (String w : weak) {
                Symbol sym = module.symbols.get(w);
                if (sym != null) {
                    sym.reads++;
                } else {
                    extReads.merge(w, 1, Integer::sum);
                }
            }
            for (Object[] w : weakScoped) {
                String name = (String) w[0];
                Scope sc = (Scope) w[1];
                Symbol sym = sc.symbols.get(name);
                if (sym == null) {
                    sym = module.symbols.get(name);
                }
                if (sym != null) {
                    sym.reads++;
                } else {
                    extReads.merge(name, 1, Integer::sum);
                }
            }

            // The module level, for the rest of the notebook.
            Map<String, Bind> bound = new LinkedHashMap<>();
            Map<String, Integer> reads = new HashMap<>(extReads);
            Set<String> called = new TreeSet<>(extCalled);
            Set<String> attrBases = new TreeSet<>(extAttr);
            for (Symbol sym : module.symbols.values()) {
                if (sym.firstKind != null) {
                    bound.put(sym.name, new Bind(sym.firstKind, sym.binds, sym.assigns, sym.called, sym.attrBase));
                }
                if (sym.reads > 0) {
                    reads.merge(sym.name, sym.reads, Integer::sum);
                }
                if (sym.called) {
                    called.add(sym.name);
                }
                if (sym.attrBase) {
                    attrBases.add(sym.name);
                }
            }
            // What the text takes from before it: read as it runs, at module level, before it binds them.
            Set<String> needs = new TreeSet<>();
            Set<String> mutates = new TreeSet<>();
            for (int o = 0; o < no; o++) {
                int f = oFlags[o];
                if (oFixed[o] != null || (f & F_READ) == 0 || (f & (F_LINKED | F_DECL)) != 0 || !immediate(oScope[o])) {
                    continue;
                }
                Symbol sym = oSym[o];
                if (sym != null && (sym.scope != module || oStart[o] >= sym.firstEffective)) {
                    continue; // a local of a class body or comprehension, or a name this text bound above
                }
                needs.add(oName[o]);
                // changed in place: x.append(...), x[i] = v, x.a = v, del x[i]
                if ((f & F_MUTATED) != 0 || (f & F_DEL) != 0 && (f & (F_SUB | F_ATTR_BASE)) != 0) {
                    mutates.add(oName[o]);
                }
            }
            for (String w : weak) {
                if (!module.symbols.containsKey(w)) {
                    needs.add(w); // %timeit f(x) runs f(x)
                }
            }
            Summary summary = new Summary(Map.copyOf(bound), Map.copyOf(reads), Set.copyOf(called), Set.copyOf(attrBases),
                                          opaque, Set.copyOf(needs), Set.copyOf(mutates));

            // Roles and modifiers.
            int[] sem = new int[no];
            for (int o = 0; o < no; o++) {
                sem[o] = classify(o);
            }
            bugs(sem);

            // In text order, one per token, the weak ones left out.
            Integer[] order = new Integer[no];
            for (int o = 0; o < no; o++) {
                order[o] = o;
            }
            Arrays.sort(order, (x, y) -> Integer.compare(oStart[x], oStart[y]));
            int[] rs = new int[no];
            int[] re = new int[no];
            int[] rsem = new int[no];
            String[] rn = new String[no];
            Symbol[] rsym = new Symbol[no];
            int count = 0;
            int lastStart = -1;
            for (Integer idx : order) {
                int o = idx;
                if ((oFlags[o] & F_WEAK) != 0 || oStart[o] == lastStart || oEnd[o] <= oStart[o]) {
                    continue;
                }
                lastStart = oStart[o];
                rs[count] = oStart[o];
                re[count] = oEnd[o];
                rsem[count] = sem[o];
                rn[count] = oName[o];
                rsym[count] = oFixed[o] != null ? null : oSym[o];
                count++;
            }
            findings.sort((x, y) -> Integer.compare(x.start(), y.start()));
            return new Result(text, count, rs, re, rsem, rn, rsym, summary, findings, lenses);
        }

        private int classify(int o) {
            String name = oName[o];
            int f = oFlags[o];
            int mods = 0;
            if ((f & F_MUTATED) != 0) {
                mods |= MUTATED;
            }
            if ((f & F_CALL) != 0) {
                mods |= CALLED;
            }
            if ((f & F_DECL) != 0) {
                mods |= DECLARED;
            }
            if (oFixed[o] != null) {
                return pack(oFixed[o], mods, Family.NONE);
            }
            Symbol sym = oSym[o];
            Family fam = Family.NONE;
            Role role;
            boolean bindOcc = (f & F_BIND) != 0;
            if (sym != null) {
                boolean atModule = sym.scope == module;
                Global g = atModule ? global(name) : null;
                role = roleOf(sym, oStart[o], g, f);
                if (bindOcc && oStart[o] == sym.firstOffset && (g == null || g.bindsBefore() == 0)) {
                    mods |= DEF;
                }
                if (sym.assigns + (g == null ? 0 : g.assignsElsewhere()) >= 2) {
                    mods |= REASSIGNED;
                }
                if (bindOcc && (f & F_READ) == 0 && unusedCandidate(sym, atModule)
                        && sym.reads + (g == null ? 0 : g.readsElsewhere()) == 0) {
                    mods |= UNUSED;
                }
                if (shadows(sym)) {
                    mods |= SHADOWS;
                }
                if (g != null && g.live() != null) {
                    mods |= LIVE;
                    if (g.live().stale() != null && !g.live().stale().isEmpty()) {
                        mods |= STALE;
                    }
                    fam = role.isData() ? g.live().family() : Family.NONE;
                }
                if (oCaptured[o]) {
                    mods |= CAPTURED;
                }
            } else {
                Global g = global(name);
                boolean[] unresolved = new boolean[1];
                role = externalRole(name, f, g, unresolved);
                if (g != null) {
                    if (g.assignsElsewhere() >= 2) {
                        mods |= REASSIGNED;
                    }
                    if (g.kind() != null && isBuiltinName(name) && !HARMLESS.contains(name) && g.kind().isData()) {
                        mods |= SHADOWS;
                    }
                    if (g.live() != null) {
                        mods |= LIVE;
                        if (g.live().stale() != null && !g.live().stale().isEmpty()) {
                            mods |= STALE;
                        }
                        fam = role.isData() ? g.live().family() : Family.NONE;
                    }
                } else if (unresolved[0] && ctx != null && !opaque && !ctx.opaque() && !ctx.checksNames()) {
                    mods |= UNRESOLVED;
                }
            }
            return pack(role, mods, fam == null ? Family.NONE : fam);
        }

        private Role roleOf(Symbol sym, int offset, Global g, int flags) {
            Binding k = sym.kindAt(offset);
            if (k == null) {
                return Role.VARIABLE;
            }
            Scope sc = sym.scope;
            Role r = switch (k) {
                case PARAM -> Role.PARAMETER;
                case SELF -> Role.SELF;
                case FUNC -> sc.kind == ScopeKind.CLASS ? Role.METHOD : Role.FUNCTION;
                case CLASS -> Role.CLASS;
                case TYPE_PARAM -> Role.TYPE_PARAM;
                case IMPORT_MODULE -> Role.MODULE;
                case IMPORT_NAME -> imported(sym.name, sym.called || g != null && g.calledElsewhere(),
                                             sym.attrBase || g != null && g.attrBaseElsewhere());
                default -> sc.kind == ScopeKind.MODULE
                           ? (PythonLexer.isAllCaps(sym.name) || sym.isFinal ? Role.CONSTANT : Role.VARIABLE)
                           : sc.kind == ScopeKind.CLASS ? Role.FIELD : Role.LOCAL;
            };
            return sc.kind == ScopeKind.MODULE ? live(r, g == null ? null : g.live(), sym.name) : r;
        }

        /** What an imported name is, from how the notebook uses it. */
        private static Role imported(String name, boolean called, boolean attrBase) {
            if (PythonLexer.isAllCaps(name)) {
                return Role.CONSTANT;
            }
            if (PythonLexer.isCamelCase(name)) {
                return Role.CLASS;
            }
            if (called) {
                return Role.FUNCTION;
            }
            if (attrBase) {
                return Role.MODULE;
            }
            return Role.VARIABLE;
        }

        /** The kernel knows better than the text: f = np.sin is a function, m = np a module. */
        private Role live(Role r, Live live, String name) {
            if (live == null) {
                if (names != null) {
                    TokenKind tk = names.apply(name);
                    if (tk != null) {
                        return fromKind(tk, r);
                    }
                }
                return r;
            }
            switch (live.kind() == null ? "" : live.kind()) {
                case "module":
                    return Role.MODULE;
                case "class":
                    return r == Role.EXCEPTION ? r : Role.CLASS;
                case "function":
                    return r == Role.METHOD || r == Role.BUILTIN ? r : Role.FUNCTION;
                case "data":
                    return r == Role.MODULE || r == Role.CLASS || r == Role.FUNCTION || r == Role.BUILTIN
                           || r == Role.BUILTIN_TYPE || r == Role.EXCEPTION
                           ? (PythonLexer.isAllCaps(name) ? Role.CONSTANT : Role.VARIABLE) : r;
                default:
                    return r;
            }
        }

        private static Role fromKind(TokenKind tk, Role r) {
            return switch (tk) {
                case NAMESPACE -> Role.MODULE;
                case TYPE -> r == Role.EXCEPTION ? r : Role.CLASS;
                case FUNCTION -> r == Role.METHOD || r == Role.BUILTIN ? r : Role.FUNCTION;
                case VARIABLE -> r.isData() ? r : Role.VARIABLE;
                case CONSTANT -> Role.CONSTANT;
                default -> r;
            };
        }

        private Role externalRole(String name, int flags, Global g, boolean[] unresolved) {
            if (g != null && g.kind() != null) {
                Role r = switch (g.kind()) {
                    case FUNC -> Role.FUNCTION;
                    case CLASS -> Role.CLASS;
                    case IMPORT_MODULE -> Role.MODULE;
                    case IMPORT_NAME -> imported(name, g.calledElsewhere() || (flags & F_CALL) != 0,
                                                 g.attrBaseElsewhere() || (flags & F_ATTR_BASE) != 0);
                    default -> PythonLexer.isAllCaps(name) ? Role.CONSTANT : Role.VARIABLE;
                };
                return live(r, g.live(), name);
            }
            if (g != null && g.live() != null) {
                return live(PythonLexer.isAllCaps(name) ? Role.CONSTANT : Role.VARIABLE, g.live(), name);
            }
            if (names != null) {
                TokenKind tk = names.apply(name);
                if (tk != null && tk != TokenKind.DEFAULT) {
                    return fromKind(tk, Role.VARIABLE);
                }
            }
            if (PythonLexer.CONSTANTS.contains(name)) {
                return Role.CONSTANT;
            }
            if (PythonLexer.BUILTIN_TYPES.contains(name)) {
                return isException(name) ? Role.EXCEPTION : Role.BUILTIN_TYPE;
            }
            if (PythonLexer.BUILTIN_FUNCTIONS.contains(name) || NOTEBOOK_NAMES.contains(name)) {
                return Role.BUILTIN;
            }
            unresolved[0] = true;
            if (PythonLexer.isAllCaps(name)) {
                return Role.CONSTANT;
            }
            if (PythonLexer.isCamelCase(name)) {
                return Role.CLASS;
            }
            return (flags & F_CALL) != 0 ? Role.FUNCTION : Role.VARIABLE;
        }

        private boolean unusedCandidate(Symbol sym, boolean atModule) {
            String nm = sym.name;
            if (nm.startsWith("_") || sym.starred || sym.firstKind == null) {
                return false;
            }
            switch (sym.firstKind) {
                case SELF:
                case FUNC:
                case CLASS:
                case TYPE_PARAM:
                case ANNOTATION:
                    return false;
                default:
                    break;
            }
            if (sym.scope.kind == ScopeKind.CLASS) {
                return false;
            }
            if (atModule && ctx == null && sym.firstKind != Binding.IMPORT_MODULE && sym.firstKind != Binding.IMPORT_NAME) {
                return false; // a module's names are what it offers to whoever imports it
            }
            return !(sym.firstKind == Binding.PARAM && (nm.equals("args") || nm.equals("kwargs")));
        }

        private boolean shadows(Symbol sym) {
            return isBuiltinName(sym.name) && !HARMLESS.contains(sym.name) && sym.scope.kind != ScopeKind.CLASS
                   && sym.firstKind != Binding.PARAM && sym.firstKind != Binding.SELF;
        }

        /** Findings resolution makes possible: builtins hidden then called, loop variables captured late. */
        private void bugs(int[] sem) {
            Set<String> told = new HashSet<>();
            for (int o = 0; o < no; o++) {
                if (oFixed[o] != null) {
                    continue;
                }
                int f = oFlags[o];
                String name = oName[o];
                Symbol sym = oSym[o];
                // A builtin hidden by a value, then called: the call reaches the value.
                if ((f & F_CALL) != 0 && has(sem[o], SHADOWS)) {
                    Binding k = sym != null ? sym.kindAt(oStart[o]) : global(name) != null ? global(name).kind() : null;
                    if (k != null && k.isData()) {
                        String where = sym != null && sym.firstOffset != Integer.MAX_VALUE
                                       ? "line " + (lineOf(text, sym.firstOffset) + 1)
                                       : global(name) != null && global(name).definedIn() != null ? global(name).definedIn()
                                       : "another cell";
                        findings.add(new Finding(oStart[o], oEnd[o], 1, "shadowed-builtin-call",
                            "'" + name + "(...)' calls the value bound to '" + name + "' (" + where
                            + "), not the builtin " + name + "(). Rename that variable."));
                    }
                } else if ((f & F_BIND) != 0 && has(sem[o], SHADOWS) && has(sem[o], DEF) && sym != null
                           && sym.firstKind != null && sym.firstKind.isData() && told.add("shadow:" + name)) {
                    findings.add(new Finding(oStart[o], oEnd[o], 0, "shadows-builtin",
                        "'" + name + "' now names a value: the builtin " + name + "() is hidden "
                        + (sym.scope == module ? "everywhere after this line" : "in this function") + "."));
                }
                // A lambda made in a loop and kept: it reads the loop variable when it runs, after the loop moved
                // on -- through a closure cell in a function, through the globals at module level.
                if (sym != null && oScope[o] != sym.scope && (f & F_READ) != 0) {
                    for (Scope l = oScope[o]; l != null && l != sym.scope; l = l.parent) {
                        boolean late = false;
                        for (LoopFrame lf : l.escapingLoops) {
                            if (lf.scope() == sym.scope && lf.targets().contains(name)) {
                                late = true;
                                break;
                            }
                        }
                        if (late && told.add("late:" + System.identityHashCode(l) + ":" + name)) {
                            findings.add(new Finding(oStart[o], oEnd[o], 1, "late-binding",
                                "'" + name + "' is looked up when the " + (l.kind == ScopeKind.LAMBDA ? "lambda" : "function")
                                + " runs, not when it is made: every one made in this loop sees the last '" + name
                                + "'. Bind it now with a default: lambda " + name + "=" + name + ": ..."));
                            break;
                        }
                    }
                }
            }
        }
    }

    /** A short description of a packed role, for tooltips and the legend: "parameter · reassigned". */
    public static String describe(int sem) {
        StringBuilder sb = new StringBuilder(role(sem).label);
        int m = mods(sem);
        if ((m & DEF) != 0) {
            sb.append(" · definition");
        }
        if ((m & REASSIGNED) != 0) {
            sb.append(" · reassigned");
        }
        if ((m & UNUSED) != 0) {
            sb.append(" · never read");
        }
        if ((m & SHADOWS) != 0) {
            sb.append(" · hides a builtin");
        }
        if ((m & CAPTURED) != 0) {
            sb.append(" · captured by a closure");
        }
        if ((m & MUTATED) != 0) {
            sb.append(" · changed in place");
        }
        if ((m & STALE) != 0) {
            sb.append(" · stale");
        }
        if ((m & UNRESOLVED) != 0) {
            sb.append(" · not defined anywhere yet");
        }
        Family f = family(sem);
        if (f != Family.NONE) {
            sb.append(" · ").append(f.label);
        }
        return sb.toString();
    }
}
