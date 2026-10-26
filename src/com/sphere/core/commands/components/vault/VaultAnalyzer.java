package com.sphere.components.vault;

import com.sphere.components.editor.LanguageSpec;
import com.sphere.components.vault.VaultAnalysis.Fix;
import com.sphere.components.vault.VaultAnalysis.Issue;
import com.sphere.components.vault.VaultAnalysis.Severity;
import com.sphere.components.vault.VaultAnalysis.Style;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a snippet or a macro the way a careful colleague would before running it.
 *
 * What the extension says is checked against what the content reads as, so a
 * Python script saved as .cc is caught before ::cpp tries to compile it. The
 * first comment block gives the description; @tags, @arg, @opts and @runner
 * lines in it are the metadata, so nothing has to be kept beside the file.
 * The arguments are read from the code itself: argparse calls, sys.argv
 * indexes, and for a ROOT macro the parameters of the function .x calls, whose
 * name must be the file's.
 */
final class VaultAnalyzer {

    /** Files larger than this are listed but not read. */
    static final int MAX_READ = 1 << 20;
    private static final int SNIFF_LINES = 400;
    private static final int HEADER_LINES = 80;
    private static final int SEARCH_CHARS = 64 * 1024;
    private static final int MAX_USES = 14;

    private VaultAnalyzer() {
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    static VaultAnalysis analyze(Path file, VaultItem.Kind kind, String text) {
        final VaultAnalysis.Builder b = new VaultAnalysis.Builder();
        final String name = file.getFileName().toString();
        final String stem = name.lastIndexOf('.') > 0 ? name.substring(0, name.lastIndexOf('.')) : name;
        final String ext = name.lastIndexOf('.') > 0 ? name.substring(name.lastIndexOf('.')) : "";

        b.declared = LanguageSpec.forFile(file.toFile());
        b.language = b.declared;
        b.header = b.declared == LanguageSpec.CPP
            && ext.toLowerCase(Locale.ROOT).matches("\\.(h|hh|hpp|hxx|ipp|tpp)");

        if (text == null) {
            b.issues.add(new Issue(Severity.INFO, "Too large to read: listed, not analyzed.", null));
            return b.build();
        }

        final String[] lines = text.split("\\R", -1);
        b.lines = text.isEmpty() ? 0 : lines.length;
        b.hash = contentHash(lines);
        b.text = (text.length() > SEARCH_CHARS ? text.substring(0, SEARCH_CHARS) : text)
            .toLowerCase(Locale.ROOT);

        if (text.isBlank()) {
            b.issues.add(new Issue(Severity.INFO, "Empty file.", null));
            return b.build();
        }

        // ---- what the content reads as ----------------------------------------
        final Sniff sniff = sniff(lines);
        if (sniff.confident()) {
            b.sniffed = sniff.spec();
        }
        if (b.sniffed != null && b.sniffed != b.declared && isCode(b.sniffed)) {
            final String better = stem + preferredExtension(b.sniffed, kind);
            if (b.declared == LanguageSpec.NONE) {
                b.language = b.sniffed;
                b.issues.add(new Issue(Severity.INFO,
                    "No known extension: read as " + b.sniffed.name + ".",
                    new Fix(Fix.Action.RENAME, "Rename to " + better, better)));
            } else if (isCode(b.declared)) {
                b.language = b.sniffed;
                b.issues.add(new Issue(Severity.WARNING,
                    "Reads as " + b.sniffed.name + ", but " + ext + " makes it "
                        + b.declared.name + consequence(b.declared, ext) + ".",
                    new Fix(Fix.Action.RENAME, "Rename to " + better, better)));
            }
        }

        // ---- header comments: description, tags, directives --------------------
        readHeader(lines, b);

        // ---- the language's own structure --------------------------------------
        final LanguageSpec lang = b.language;
        if (lang == LanguageSpec.PYTHON) {
            readPython(text, lines, b);
        } else if (lang == LanguageSpec.CPP) {
            readCpp(text, stem, name, ext, kind, b);
        } else if (lang == LanguageSpec.JULIA) {
            readJulia(text, lines, b);
        } else if (lang == LanguageSpec.FORTRAN) {
            readFortran(text, b);
        } else if (lang == LanguageSpec.JAVASCRIPT) {
            readJs(text, b);
        }

        if (!b.paramHelp.isEmpty()) {
            for (int i = 0; i < b.params.size(); i++) {
                final VaultParam p = b.params.get(i);
                final String help = b.paramHelp.get(p.name());
                if (help != null && p.help().isEmpty()) {
                    b.params.set(i, new VaultParam(p.name(), p.type(), p.flag(), p.defaultValue(),
                        help, p.choices(), p.required(), p.cppType()));
                }
            }
        }
        if (DRAWS.matcher(text).find()) {
            b.draws = true;
        }
        if (file.toString().indexOf(' ') >= 0) {
            b.issues.add(new Issue(Severity.WARNING,
                "The path holds a space: the console reads [@ ...] up to the first one.", null));
        }
        if (b.title == null || b.title.isBlank()) {
            b.title = "";
        }
        return b.build();
    }

    // =========================================================================
    // Language sniffing
    // =========================================================================

    record Sniff(LanguageSpec spec, int score, int second) {
        boolean confident() {
            return spec != null && score >= 6 && score >= 2 * second + 1;
        }
    }

    private static final Pattern PY_IMPORT = Pattern.compile(
        "^\\s*(import\\s+[A-Za-z_][\\w.]*(\\s+as\\s+\\w+)?(\\s*,\\s*[\\w.]+(\\s+as\\s+\\w+)?)*\\s*$|from\\s+[.\\w]+\\s+import\\s+\\S)");
    private static final Pattern PY_DEF = Pattern.compile(
        "^\\s*(async\\s+)?def\\s+\\w+\\s*\\(.*\\)\\s*(->[^:]+)?:\\s*(#.*)?$");
    private static final Pattern PY_CLASS = Pattern.compile("^\\s*class\\s+\\w+(\\(.*\\))?\\s*:\\s*(#.*)?$");
    private static final Pattern PY_MAIN = Pattern.compile("__name__\\s*==\\s*['\"]__main__['\"]");
    private static final Pattern PY_BLOCK = Pattern.compile(
        "^\\s*(if|elif|else|for|while|try|except|finally|with)\\b.*:\\s*(#.*)?$");
    private static final Pattern PY_MISC = Pattern.compile("\\bprint\\(|\\bself\\.|\\bNone\\b|\\bTrue\\b|\\bFalse\\b");

    private static final Pattern C_INCLUDE = Pattern.compile("^\\s*#\\s*include\\s*[<\"]");
    private static final Pattern C_SEMI = Pattern.compile(";\\s*(//.*)?$");
    private static final Pattern C_STD = Pattern.compile("\\bstd::|\\bnullptr\\b|->\\w");
    private static final Pattern C_FUNC = Pattern.compile(
        "^\\s*(static\\s+|inline\\s+)*(void|int|double|float|bool|auto|char|long|unsigned|[A-Z]\\w*_t|T[A-Z]\\w*\\s*\\*?)\\s+\\**\\w+\\s*\\([^;]*\\)\\s*(const)?\\s*\\{?\\s*$");
    private static final Pattern C_NEW = Pattern.compile("\\bnew\\s+[A-Za-z_]\\w*\\s*[(\\[]");
    private static final Pattern C_BRACE = Pattern.compile("^\\s*\\}\\s*;?\\s*$");

    private static final Pattern JL_USING = Pattern.compile("^\\s*using\\s+(?!namespace\\b)[A-Z]\\w*");
    private static final Pattern JL_END = Pattern.compile("^\\s*end\\s*$");
    private static final Pattern JL_FUNCTION = Pattern.compile("^\\s*function\\s+\\w[\\w!]*\\s*\\(.*\\)\\s*$");
    private static final Pattern JL_MISC = Pattern.compile("\\bprintln\\(|^\\s*module\\s+\\w+\\s*$|\\bstruct\\s+\\w+\\s*$");

    private static final Pattern F_PROGRAM = Pattern.compile(
        "(?i)^\\s*(program\\s+\\w+|implicit\\s+none)\\b");
    private static final Pattern F_END = Pattern.compile("(?i)^\\s*end\\s+(program|subroutine|function|module)\\b");
    private static final Pattern F_DECL = Pattern.compile(
        "(?i)^\\s*(integer|real|double\\s+precision|character|logical|complex)\\s*(\\(.*\\))?\\s*(,.*)?::");

    private static final Pattern SH_WORDS = Pattern.compile("^\\s*(echo|export|source)\\s");
    private static final Pattern SH_BLOCK = Pattern.compile("^\\s*(fi|done|esac|then|do)\\s*$");

    private static final Pattern JS_DECL = Pattern.compile("\\b(const|let|var)\\s+\\w+\\s*=");
    private static final Pattern JS_MISC = Pattern.compile("console\\.(log|error)|\\brequire\\(|\\bexport\\s+(default|function|const)");
    private static final Pattern JS_FUNCTION = Pattern.compile("\\bfunction\\s+\\w+\\s*\\(.*\\)\\s*\\{|=>\\s*\\{");

    static Sniff sniff(String[] lines) {
        int py = 0, cpp = 0, jl = 0, f90 = 0, sh = 0, js = 0;
        final String first = lines.length > 0 ? lines[0] : "";
        if (first.startsWith("#!")) {
            final String bang = first.toLowerCase(Locale.ROOT);
            if (bang.contains("python")) py += 20;
            else if (bang.contains("julia")) jl += 20;
            else if (bang.contains("node")) js += 20;
            else if (bang.matches(".*\\b(ba|z|k)?sh\\b.*")) sh += 20;
        }
        final int n = Math.min(lines.length, SNIFF_LINES);
        int semis = 0;
        for (int i = 0; i < n; i++) {
            final String line = lines[i];
            if (line.isBlank()) continue;
            if (PY_IMPORT.matcher(line).find()) py += 3;
            if (PY_DEF.matcher(line).find()) py += 3;
            if (PY_CLASS.matcher(line).find()) py += 2;
            if (PY_MAIN.matcher(line).find()) py += 5;
            if (PY_BLOCK.matcher(line).find()) py += 1;
            if (PY_MISC.matcher(line).find()) py += 1;

            if (C_INCLUDE.matcher(line).find()) cpp += 4;
            if (C_SEMI.matcher(line).find() && semis++ < 12) { cpp += 1; js += 1; }
            if (C_STD.matcher(line).find()) cpp += 2;
            if (C_FUNC.matcher(line).find()) cpp += 3;
            if (C_NEW.matcher(line).find()) { cpp += 2; js += 1; }
            if (C_BRACE.matcher(line).find()) { cpp += 1; js += 1; }

            if (JL_USING.matcher(line).find()) jl += 4;
            if (JL_END.matcher(line).find()) jl += 2;
            if (JL_FUNCTION.matcher(line).find()) jl += 2;
            if (JL_MISC.matcher(line).find()) jl += 2;

            if (F_PROGRAM.matcher(line).find()) f90 += 6;
            if (F_END.matcher(line).find()) f90 += 4;
            if (F_DECL.matcher(line).find()) f90 += 4;

            if (SH_WORDS.matcher(line).find()) sh += 2;
            if (SH_BLOCK.matcher(line).find()) sh += 3;

            if (JS_DECL.matcher(line).find()) js += 2;
            if (JS_MISC.matcher(line).find()) js += 4;
            if (JS_FUNCTION.matcher(line).find()) js += 3;
        }
        // A Fortran "end" closes as Julia's does; Fortran's own words win then.
        if (f90 >= 6) jl = Math.max(0, jl - f90 / 2);

        final LanguageSpec[] specs = {
            LanguageSpec.PYTHON, LanguageSpec.CPP, LanguageSpec.JULIA,
            LanguageSpec.FORTRAN, LanguageSpec.SHELL, LanguageSpec.JAVASCRIPT
        };
        final int[] scores = {py, cpp, jl, f90, sh, js};
        int best = -1, bestScore = 0, second = 0;
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > bestScore) {
                second = bestScore;
                bestScore = scores[i];
                best = i;
            } else if (scores[i] > second) {
                second = scores[i];
            }
        }
        return new Sniff(best < 0 ? null : specs[best], bestScore, second);
    }

    private static boolean isCode(LanguageSpec spec) {
        return spec == LanguageSpec.PYTHON || spec == LanguageSpec.CPP || spec == LanguageSpec.JULIA
            || spec == LanguageSpec.FORTRAN || spec == LanguageSpec.SHELL || spec == LanguageSpec.JAVASCRIPT;
    }

    static String preferredExtension(LanguageSpec spec, VaultItem.Kind kind) {
        if (spec == LanguageSpec.PYTHON) return ".py";
        if (spec == LanguageSpec.JULIA) return ".jl";
        if (spec == LanguageSpec.FORTRAN) return ".f90";
        if (spec == LanguageSpec.SHELL) return ".sh";
        if (spec == LanguageSpec.JAVASCRIPT) return ".js";
        if (spec == LanguageSpec.CPP) return kind == VaultItem.Kind.MACRO ? ".C" : ".cpp";
        return ".txt";
    }

    /** What running it under its extension would do, said in a few words. */
    private static String consequence(LanguageSpec declared, String ext) {
        if (declared == LanguageSpec.CPP) {
            return ext.equals(".C") ? ": ::root would interpret it" : ": ::cpp would compile it";
        }
        if (declared == LanguageSpec.PYTHON) return ": ::py would run it";
        if (declared == LanguageSpec.JULIA) return ": ::jul would run it";
        if (declared == LanguageSpec.FORTRAN) return ": ::fort would compile it";
        return "";
    }

    // =========================================================================
    // Header comments
    // =========================================================================

    private static final Pattern DIRECTIVE = Pattern.compile("^@(\\w+)\\b\\s*(.*)$");
    private static final Pattern HASHTAG = Pattern.compile("(?<![\\w&#/])#([A-Za-z][\\w+-]{1,30})");
    private static final Pattern RULER = Pattern.compile("^[\\s=\\-*#/~_+.]{3,}$");

    /**
     * Reads the comments of the file's head.
     *
     * The first contiguous block is the description. Directives are taken from
     * every comment of the head, so a @tags line after a license block counts.
     */
    private static void readHeader(String[] lines, VaultAnalysis.Builder b) {
        final LanguageSpec lang = b.language;
        final boolean hashComments = lang == LanguageSpec.PYTHON || lang == LanguageSpec.SHELL
            || lang == LanguageSpec.JULIA || lang == LanguageSpec.CMAKE || lang == LanguageSpec.CONFIG
            || lang == LanguageSpec.NONE || lang == LanguageSpec.POWERSHELL;
        final boolean slashComments = lang == LanguageSpec.CPP || lang == LanguageSpec.JAVASCRIPT
            || lang == LanguageSpec.JAVA || lang == LanguageSpec.NONE;
        final boolean bangComments = lang == LanguageSpec.FORTRAN;

        final List<String> block = new ArrayList<>();
        boolean blockOpen = true;      // still in the leading block
        boolean inBlockComment = false;
        boolean inDocstring = false;
        String docQuote = null;
        final List<String> docstring = new ArrayList<>();
        boolean docstringDone = false;
        boolean sawCode = false;

        final int n = Math.min(lines.length, HEADER_LINES);
        for (int i = 0; i < n; i++) {
            final String raw = lines[i];
            final String t = raw.strip();
            if (i == 0 && t.startsWith("#!")) continue;
            if (t.matches("#.*coding[:=].*")) continue;

            // Python and Julia docstrings at the top of the file
            if (inDocstring) {
                final int close = t.indexOf(docQuote);
                final String line = (close >= 0 ? t.substring(0, close) : t).strip();
                final Matcher directive = DIRECTIVE.matcher(line);
                if (directive.matches()) {
                    directive(directive.group(1).toLowerCase(Locale.ROOT), directive.group(2).strip(), b);
                } else {
                    docstring.add(line);
                }
                if (close >= 0) {
                    inDocstring = false;
                    docstringDone = true;
                }
                continue;
            }
            if (!sawCode && !docstringDone
                    && (lang == LanguageSpec.PYTHON || lang == LanguageSpec.JULIA)) {
                final Matcher doc = Pattern.compile("^[rRuUbB]?(\"\"\"|''')(.*)$").matcher(t);
                if (doc.matches()) {
                    docQuote = doc.group(1);
                    final String rest = doc.group(2);
                    final int close = rest.indexOf(docQuote);
                    if (close >= 0) {
                        docstring.add(rest.substring(0, close));
                        docstringDone = true;
                    } else {
                        docstring.add(rest);
                        inDocstring = true;
                    }
                    blockOpen = false;
                    continue;
                }
            }

            String content = null;
            if (inBlockComment) {
                final int close = t.indexOf("*/");
                content = (close >= 0 ? t.substring(0, close) : t).replaceFirst("^\\*+\\s?", "");
                if (close >= 0) inBlockComment = false;
            } else if (slashComments && t.startsWith("/*")) {
                final String rest = t.substring(2).replaceFirst("^\\*+", "");
                final int close = rest.indexOf("*/");
                content = (close >= 0 ? rest.substring(0, close) : rest).strip();
                if (close < 0) inBlockComment = true;
            } else if (slashComments && t.startsWith("//")) {
                content = t.replaceFirst("^/{2,}!?\\s?", "");
            } else if (hashComments && t.startsWith("#") && !t.startsWith("#=")) {
                content = t.replaceFirst("^#+\\s?", "");
            } else if (bangComments && t.startsWith("!")) {
                content = t.replaceFirst("^!+\\s?", "");
            }

            if (content == null) {
                if (t.isEmpty()) {
                    if (!block.isEmpty()) blockOpen = false;
                    continue;
                }
                sawCode = true;
                blockOpen = false;
                continue;
            }
            content = content.strip();
            final Matcher directive = DIRECTIVE.matcher(content);
            if (directive.matches()) {
                directive(directive.group(1).toLowerCase(Locale.ROOT), directive.group(2).strip(), b);
                continue;
            }
            if (blockOpen && !sawCode) {
                block.add(content);
            }
        }

        final List<String> source = !block.isEmpty() && hasWords(block) ? block : docstring;
        describe(source, b);
    }

    private static boolean hasWords(List<String> lines) {
        for (String line : lines) {
            if (!line.isBlank() && !RULER.matcher(line).matches()) return true;
        }
        return false;
    }

    /** The first paragraph, the title its first sentence, hashtags lifted out as tags. */
    private static void describe(List<String> lines, VaultAnalysis.Builder b) {
        final StringBuilder text = new StringBuilder();
        for (String line : lines) {
            final String t = line.strip();
            if (RULER.matcher(t).matches()) continue;
            if (t.isEmpty()) {
                if (text.length() > 0) break;
                continue;
            }
            if (text.length() > 0) text.append(' ');
            text.append(t);
            if (text.length() > 420) break;
        }
        String description = text.toString().strip();
        final Matcher tags = HASHTAG.matcher(description);
        while (tags.find()) {
            b.tags.add(tags.group(1).toLowerCase(Locale.ROOT));
        }
        // The tags are shown as tags: the sentence reads without them.
        description = HASHTAG.matcher(description).replaceAll("").replaceAll("\\s{2,}", " ").strip();
        if (description.length() > 420) {
            description = description.substring(0, 417).strip() + "...";
        }
        if (b.description == null || b.description.isBlank()) {
            b.description = description;
        }
        if (b.title == null || b.title.isBlank()) {
            final Matcher sentence = Pattern.compile("^(.{8,}?[.!?])(\\s|$)").matcher(description);
            String title = sentence.find() ? sentence.group(1) : description;
            if (title.length() > 90) title = title.substring(0, 87).strip() + "...";
            b.title = title;
        }
    }

    private static final Pattern ARG_DECL = Pattern.compile(
        "^(-{0,2}[A-Za-z_][\\w.-]*)(?::([\\w|.+-]+))?(?:=(\"[^\"]*\"|\\S+))?\\s*(.*)$");

    private static void directive(String word, String value, VaultAnalysis.Builder b) {
        switch (word) {
            case "desc", "description", "brief", "summary" -> {
                if (!value.isEmpty()) {
                    b.description = value;
                    b.title = value.length() > 90 ? value.substring(0, 87) + "..." : value;
                    final Matcher tags = HASHTAG.matcher(value);
                    while (tags.find()) b.tags.add(tags.group(1).toLowerCase(Locale.ROOT));
                }
            }
            case "tag", "tags", "keywords" -> {
                for (String tag : value.split("[,;\\s]+")) {
                    final String t = tag.replaceFirst("^#", "").strip().toLowerCase(Locale.ROOT);
                    if (!t.isEmpty()) b.tags.add(t);
                }
            }
            case "arg" -> {
                final Matcher m = ARG_DECL.matcher(value);
                if (m.matches()) {
                    final String raw = m.group(1);
                    final boolean option = raw.startsWith("-");
                    final String name = raw.replaceFirst("^-+", "").replace('-', '_');
                    final String type = m.group(2) == null ? "" : m.group(2);
                    String def = m.group(3) == null ? "" : m.group(3);
                    if (def.length() >= 2 && def.startsWith("\"") && def.endsWith("\"")) {
                        def = def.substring(1, def.length() - 1);
                    }
                    final List<String> choices = type.contains("|") ? List.of(type.split("\\|")) : List.of();
                    final VaultParam.Type kind = declaredType(type, name, choices);
                    b.params.add(new VaultParam(name, kind, option ? raw : null, def,
                        m.group(4), choices, !option && def.isEmpty(), ""));
                    b.style = Style.POSITIONAL;
                    if (option) b.style = Style.OPTIONS;
                }
            }
            case "param" -> {
                final String[] parts = value.replaceFirst("^\\[\\w+(,\\w+)?\\]\\s*", "").split("\\s+", 2);
                if (parts.length == 2) b.paramHelp.put(parts[0], parts[1].strip());
            }
            case "opts", "options", "flags" -> b.defaultOptions = value;
            case "runner", "run" -> {
                String r = value.strip();
                if (!r.isEmpty() && !r.startsWith(":")) r = "::" + r;
                b.runner = r.isEmpty() ? null : r;
            }
            default -> { }
        }
    }

    private static VaultParam.Type declaredType(String type, String name, List<String> choices) {
        if (!choices.isEmpty()) return VaultParam.Type.CHOICE;
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "int", "integer", "long", "n" -> VaultParam.Type.INT;
            case "float", "real", "double", "number" -> VaultParam.Type.REAL;
            case "bool", "flag", "switch" -> VaultParam.Type.BOOL;
            case "path", "file", "dir", "folder" -> VaultParam.Type.PATH;
            case "raw" -> VaultParam.Type.RAW;
            case "" -> VaultParam.looksLikePath(name) ? VaultParam.Type.PATH : VaultParam.Type.TEXT;
            default -> VaultParam.Type.TEXT;
        };
    }

    // =========================================================================
    // Python
    // =========================================================================

    private static final Pattern PY_IMPORTS = Pattern.compile(
        "(?m)^\\s*(?:from\\s+([.\\w]+)\\s+import\\b|import\\s+([\\w.]+(?:\\s+as\\s+\\w+)?(?:\\s*,\\s*[\\w.]+(?:\\s+as\\s+\\w+)?)*))");
    private static final Pattern PY_TOP_DEF = Pattern.compile("(?m)^(?:async\\s+)?def\\s+(\\w+)|^class\\s+(\\w+)");
    private static final Pattern PY_ARGV = Pattern.compile("\\bsys\\.argv\\[(\\d+)\\]");
    private static final Pattern DRAWS = Pattern.compile(
        "->Draw\\(|\\.Draw\\(|\\bTCanvas\\b|SaveAs\\(|plt\\.(show|savefig|plot|scatter|hist)\\(|\\bsavefig\\(|\\bPlots\\.|\\bheatmap\\(|\\bscatter\\(");

    private static void readPython(String text, String[] lines, VaultAnalysis.Builder b) {
        final Matcher imports = PY_IMPORTS.matcher(text);
        final Set<String> modules = new LinkedHashSet<>();
        while (imports.find()) {
            if (imports.group(1) != null) {
                final String module = imports.group(1).replaceFirst("^\\.+", "");
                if (!module.isEmpty()) modules.add(module.split("\\.")[0]);
            } else {
                for (String part : imports.group(2).split(",")) {
                    final String module = part.strip().split("\\s+")[0].split("\\.")[0];
                    if (!module.isEmpty()) modules.add(module);
                }
            }
        }
        modules.remove("__future__");
        addCapped(b.uses, modules);

        final Matcher defs = PY_TOP_DEF.matcher(text);
        while (defs.find()) {
            b.functions.add(defs.group(1) != null ? defs.group(1) : defs.group(2));
        }

        if (b.params.isEmpty()) {
            readArgparse(text, b);
        }
        if (b.params.isEmpty()) {
            int highest = 0;
            final Matcher argv = PY_ARGV.matcher(text);
            while (argv.find()) {
                highest = Math.max(highest, Integer.parseInt(argv.group(1)));
            }
            for (int i = 1; i <= Math.min(highest, 9); i++) {
                b.params.add(new VaultParam("argv[" + i + "]", VaultParam.Type.RAW, null, "",
                    "sys.argv[" + i + "]", List.of(), true, ""));
            }
            if (highest > 0) b.style = Style.POSITIONAL;
        }
    }

    /** Every add_argument call, read as Python would read its literals. */
    private static void readArgparse(String text, VaultAnalysis.Builder b) {
        int from = 0;
        while (true) {
            final int at = text.indexOf("add_argument(", from);
            if (at < 0) break;
            final int open = at + "add_argument".length();
            final int close = matchParen(text, open);
            if (close < 0) break;
            from = close + 1;
            final VaultParam param = argparseCall(text.substring(open + 1, close));
            if (param != null) {
                b.params.add(param);
            }
        }
        if (!b.params.isEmpty()) {
            b.style = Style.OPTIONS;
        }
    }

    private static VaultParam argparseCall(String inside) {
        final List<String> flags = new ArrayList<>();
        final Map<String, String> kw = new LinkedHashMap<>();
        for (String piece : splitTopLevel(inside, ',')) {
            final String p = piece.strip();
            if (p.isEmpty()) continue;
            final Matcher m = Pattern.compile("^(\\w+)\\s*=\\s*(.*)$", Pattern.DOTALL).matcher(p);
            if (m.matches() && !p.startsWith("\"") && !p.startsWith("'")) {
                kw.put(m.group(1), m.group(2).strip());
            } else {
                final String literal = pyString(p);
                if (literal != null) flags.add(literal);
            }
        }
        if (flags.isEmpty()) return null;

        String flag = null;
        for (String f : flags) {
            if (f.startsWith("--")) { flag = f; break; }
        }
        if (flag == null && flags.get(0).startsWith("-")) flag = flags.get(0);
        final boolean positional = flag == null;

        String name = kw.containsKey("dest") ? pyString(kw.get("dest")) : null;
        if (name == null) {
            name = (positional ? flags.get(0) : flag).replaceFirst("^-+", "").replace('-', '_');
        }

        final String action = kw.containsKey("action") ? pyString(kw.get("action")) : null;
        final String type = kw.getOrDefault("type", "").strip();
        String def = kw.containsKey("default") ? pyValue(kw.get("default")) : "";
        String help = kw.containsKey("help") ? pyString(kw.get("help")) : "";
        if (help == null) help = "";
        help = help.replace("%(default)s", def).replaceAll("\\s+", " ").strip();

        final List<String> choices = new ArrayList<>();
        if (kw.containsKey("choices")) {
            final String c = kw.get("choices").strip();
            if ((c.startsWith("[") && c.endsWith("]")) || (c.startsWith("(") && c.endsWith(")"))) {
                for (String one : splitTopLevel(c.substring(1, c.length() - 1), ',')) {
                    final String v = pyValue(one.strip());
                    if (!v.isEmpty()) choices.add(v);
                }
            }
        }

        final String nargs = kw.containsKey("nargs") ? pyValue(kw.get("nargs")) : "";
        final boolean optionalPositional = nargs.equals("?") || nargs.equals("*");
        final boolean required = positional ? !optionalPositional && def.isEmpty()
            : "True".equals(kw.getOrDefault("required", "").strip());

        VaultParam.Type kind;
        if ("store_true".equals(action)) {
            kind = VaultParam.Type.BOOL;
            def = "false";
        } else if ("store_false".equals(action)) {
            kind = VaultParam.Type.BOOL;
            def = "true";
        } else if ("count".equals(action)) {
            kind = VaultParam.Type.INT;
            if (def.isEmpty()) def = "0";
        } else if (!choices.isEmpty()) {
            kind = VaultParam.Type.CHOICE;
        } else if (type.equals("int")) {
            kind = VaultParam.Type.INT;
        } else if (type.equals("float")) {
            kind = VaultParam.Type.REAL;
        } else if (type.matches("(pathlib\\.)?Path|argparse\\.FileType\\(.*\\)|open")
                || VaultParam.looksLikePath(name)) {
            kind = VaultParam.Type.PATH;
        } else {
            kind = VaultParam.Type.TEXT;
        }
        if ("None".equals(def)) def = "";
        return new VaultParam(name, kind, flag, def, help, choices, required,
            "count".equals(action) ? "count" : "");
    }

    /** A Python string literal's value, or null when the text is not one. */
    private static String pyString(String text) {
        if (text == null) return null;
        String t = text.strip();
        final Matcher m = Pattern.compile("^[rRuUbBfF]{0,2}(\"\"\"|'''|\"|')(.*)\\1$", Pattern.DOTALL).matcher(t);
        if (!m.matches()) {
            // Implicit concatenation: "a" "b"
            final Matcher parts = Pattern.compile("[rRuUbBfF]{0,2}(\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')").matcher(t);
            final StringBuilder joined = new StringBuilder();
            int end = 0;
            while (parts.find()) {
                if (!t.substring(end, parts.start()).isBlank()) return null;
                final String one = parts.group(1);
                joined.append(one, 1, one.length() - 1);
                end = parts.end();
            }
            return end > 0 && t.substring(end).isBlank() ? joined.toString() : null;
        }
        return m.group(2).replace("\\n", " ").replace("\\'", "'").replace("\\\"", "\"");
    }

    /** A literal default as it would be typed back: strings unquoted, numbers as written. */
    private static String pyValue(String text) {
        final String s = pyString(text);
        if (s != null) return s;
        final String t = text.strip();
        if (t.equals("None")) return "";
        if (t.equals("True")) return "true";
        if (t.equals("False")) return "false";
        return t;
    }

    // =========================================================================
    // C++ and ROOT
    // =========================================================================

    record Fn(String name, boolean qualified, String returnType, String params) {
    }

    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*#\\s*include\\s*[<\"]([^>\"]+)[>\"]");
    private static final Pattern ROOT_CLASS = Pattern.compile(
        "\\b((?:T|Roo)[A-Z][A-Za-z0-9]+)\\b(?=\\s*(?:\\*|&|::|\\(|<|\\s+[A-Za-z_]))|\\bnew\\s+((?:T|Roo)[A-Z][A-Za-z0-9]+)");
    private static final Pattern ROOT_GLOBAL = Pattern.compile(
        "\\bg(ROOT|System|Style|Random|Pad|Directory|Benchmark|Env|Interpreter)\\b");
    private static final Pattern RDF = Pattern.compile("\\bROOT::(RDataFrame|RDF::\\w+|EnableImplicitMT|Math::\\w+|VecOps::\\w+)");
    private static final Pattern MAIN_ARGS = Pattern.compile("\\bmain\\s*\\(\\s*int\\s+\\w+\\s*,");
    private static final Set<String> NOT_FUNCTIONS = Set.of(
        "if", "for", "while", "switch", "return", "sizeof", "catch", "decltype", "alignof",
        "static_assert", "defined", "R__LOAD_LIBRARY", "R__ADD_INCLUDE_PATH", "R__ADD_LIBRARY_PATH",
        "ClassDef", "ClassImp", "ClassDefOverride");
    private static final Map<String, String> GLOBAL_HEADERS = Map.of(
        "ROOT", "TROOT.h", "System", "TSystem.h", "Style", "TStyle.h", "Random", "TRandom.h",
        "Pad", "TVirtualPad.h", "Directory", "TDirectory.h", "Benchmark", "TBenchmark.h",
        "Env", "TEnv.h", "Interpreter", "TInterpreter.h");

    private static void readCpp(String text, String stem, String name, String ext,
                                VaultItem.Kind kind, VaultAnalysis.Builder b) {
        final String blank = blankCpp(text);

        // ---- headers and ROOT classes ---------------------------------------
        final Set<String> included = new LinkedHashSet<>();
        final Matcher inc = INCLUDE.matcher(text);
        while (inc.find()) included.add(inc.group(1).strip());

        final Set<String> classes = new TreeSet<>();
        final Matcher used = ROOT_CLASS.matcher(blank);
        while (used.find()) {
            classes.add(used.group(1) != null ? used.group(1) : used.group(2));
        }
        final Set<String> globals = new TreeSet<>();
        final Matcher g = ROOT_GLOBAL.matcher(blank);
        while (g.find()) globals.add(g.group(1));
        final boolean rdf = RDF.matcher(blank).find() && blank.contains("RDataFrame");

        final Set<String> uses = new LinkedHashSet<>(classes);
        if (rdf) uses.add("RDataFrame");
        for (String header : included) {
            if (!header.startsWith("T") && !header.startsWith("Roo") && !header.startsWith("ROOT/")) {
                uses.add(header.replaceFirst("\\.(h|hpp|hxx|hh)$", ""));
            }
        }
        addCapped(b.uses, uses);

        // ---- functions --------------------------------------------------------
        final String first = blank.strip();
        b.unnamed = first.startsWith("{");
        final List<Fn> fns = functions(blank, text);
        final List<String> plain = new ArrayList<>();
        Fn entry = null;
        for (Fn fn : fns) {
            b.functions.add(fn.name());
            if (!fn.qualified()) {
                plain.add(fn.name());
                if (fn.name().equals(stem)) entry = fn;
            }
            if (fn.name().equals("main") && MAIN_ARGS.matcher(fn.name() + "(" + fn.params() + ")").find()) {
                b.style = Style.POSITIONAL;
            }
        }

        final boolean macroFile = !b.header && (kind == VaultItem.Kind.MACRO || name.endsWith(".C"));
        if (macroFile) {
            if (entry != null) {
                b.entry = entry.name();
                int index = 0;
                for (String piece : splitTopLevel(entry.params(), ',')) {
                    final VaultParam p = cppParam(stripComments(piece), index++);
                    if (p != null) b.params.add(p);
                }
                b.style = Style.CALL;
            } else if (!b.unnamed && !plain.isEmpty()) {
                final String candidate = plain.size() == 1 ? plain.get(0) : null;
                b.issues.add(new Issue(Severity.WARNING,
                    ".x " + name + " calls " + stem + "(), which is not defined here"
                        + " (found " + String.join("(), ", plain.subList(0, Math.min(4, plain.size()))) + "()).",
                    candidate == null ? null
                        : new Fix(Fix.Action.RENAME, "Rename to " + candidate + ext, candidate + ext)));
            }
        }

        // ---- braces -------------------------------------------------------------
        int depth = 0;
        int lowest = 0;
        for (int i = 0; i < blank.length(); i++) {
            final char c = blank.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                lowest = Math.min(lowest, depth);
            }
        }
        if (depth != 0 || lowest < 0) {
            b.issues.add(new Issue(Severity.ERROR, depth > 0
                ? depth + " brace" + (depth > 1 ? "s" : "") + " opened and never closed."
                : "A closing brace has no opening one.", null));
        }

        // ---- what ACLiC would need -------------------------------------------
        if (macroFile && (!classes.isEmpty() || !globals.isEmpty() || rdf)) {
            final Set<String> wanted = new LinkedHashSet<>();
            for (String cls : classes) wanted.add(cls + ".h");
            for (String global : globals) wanted.add(GLOBAL_HEADERS.getOrDefault(global, "T" + global + ".h"));
            if (rdf) wanted.add("ROOT/RDataFrame.hxx");
            final List<String> missing = new ArrayList<>();
            for (String header : wanted) {
                if (!included.contains(header)) missing.add(header);
            }
            if (!missing.isEmpty()) {
                b.issues.add(new Issue(Severity.INFO,
                    "Compiled with ACLiC (+), it needs " + summary(missing) + ".",
                    new Fix(Fix.Action.ADD_INCLUDES,
                        "Add " + missing.size() + " #include" + (missing.size() > 1 ? "s" : ""),
                        String.join("\n", missing))));
            }
        }
        if (b.header) {
            b.issues.add(new Issue(Severity.INFO, "A header: the macros that include it use it.", null));
        }
    }

    private static String summary(List<String> headers) {
        if (headers.size() <= 3) return String.join(", ", headers);
        return String.join(", ", headers.subList(0, 3)) + " and " + (headers.size() - 3) + " more";
    }

    /**
     * The text with comments, string and character literals and preprocessor
     * lines turned to blanks, the same length, newlines kept, so a position in
     * one is the same position in the other.
     */
    static String blankCpp(String text) {
        final char[] out = text.toCharArray();
        final int n = out.length;
        int i = 0;
        boolean lineStart = true;
        while (i < n) {
            final char c = out[i];
            if (lineStart && c == '#') {
                // A directive, continued by backslashes.
                while (i < n && out[i] != '\n') {
                    if (out[i] == '\\' && i + 1 < n && out[i + 1] == '\n') {
                        out[i] = ' ';
                        i += 2;
                        continue;
                    }
                    out[i++] = ' ';
                }
                continue;
            }
            if (c == '\n') {
                lineStart = true;
                i++;
                continue;
            }
            if (!Character.isWhitespace(c)) lineStart = false;
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') out[i++] = ' ';
                continue;
            }
            if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) { out[i++] = ' '; }
                if (i < n) { out[i++] = ' '; }
                continue;
            }
            if (c == 'R' && i + 1 < n && out[i + 1] == '"') {
                final int paren = text.indexOf('(', i + 2);
                if (paren > 0 && paren - i < 20) {
                    final String delim = ")" + text.substring(i + 2, paren) + "\"";
                    final int end = text.indexOf(delim, paren);
                    if (end > 0) {
                        for (int k = i; k < end + delim.length(); k++) if (out[k] != '\n') out[k] = ' ';
                        i = end + delim.length();
                        continue;
                    }
                }
            }
            if (c == '"' || c == '\'') {
                // A digit separator (1'000) is not a quote.
                if (c == '\'' && i > 0 && Character.isLetterOrDigit(out[i - 1])
                        && i + 1 < n && Character.isLetterOrDigit(out[i + 1])
                        && Character.isDigit(text.charAt(i - 1))) {
                    i++;
                    continue;
                }
                out[i++] = ' ';
                while (i < n && out[i] != c && out[i] != '\n') {
                    if (out[i] == '\\' && i + 1 < n) out[i++] = ' ';
                    out[i++] = ' ';
                }
                if (i < n && out[i] == c) out[i++] = ' ';
                continue;
            }
            i++;
        }
        return new String(out);
    }

    /** Functions defined at file scope, their parameters taken from the original text. */
    static List<Fn> functions(String blank, String original) {
        final List<Fn> found = new ArrayList<>();
        final int n = blank.length();
        int depth = 0;
        int statement = 0;
        for (int i = 0; i < n; i++) {
            final char c = blank.charAt(i);
            if (c == '{') { depth++; continue; }
            if (c == '}') {
                depth = Math.max(0, depth - 1);
                if (depth == 0) statement = i + 1;
                continue;
            }
            if (depth != 0) continue;
            if (c == ';') { statement = i + 1; continue; }
            if (c != '(') continue;

            int j = i - 1;
            while (j >= 0 && Character.isWhitespace(blank.charAt(j))) j--;
            final int end = j + 1;
            while (j >= 0 && (Character.isJavaIdentifierPart(blank.charAt(j)) || blank.charAt(j) == ':'
                    || blank.charAt(j) == '~')) j--;
            final String name = blank.substring(j + 1, end);
            final int close = matchParen(blank, i);
            if (close < 0) break;
            if (name.isEmpty() || NOT_FUNCTIONS.contains(name) || Character.isDigit(name.charAt(0))
                    || name.startsWith(":")) {
                i = close;
                continue;
            }
            int k = close + 1;
            while (true) {
                while (k < n && Character.isWhitespace(blank.charAt(k))) k++;
                final Matcher qualifier = Pattern.compile("^(const|noexcept|override|final|mutable|volatile|&&|&)")
                    .matcher(blank.substring(k, Math.min(n, k + 10)));
                if (qualifier.find()) {
                    k += qualifier.end();
                    continue;
                }
                if (k + 1 < n && blank.charAt(k) == '-' && blank.charAt(k + 1) == '>') {
                    while (k < n && blank.charAt(k) != '{' && blank.charAt(k) != ';') k++;
                }
                break;
            }
            if (k < n && blank.charAt(k) == '{') {
                final String returns = blank.substring(Math.min(statement, j + 1), j + 1)
                    .replaceAll("\\s+", " ").strip();
                if (!returns.isEmpty() && !returns.endsWith("=") && !returns.contains("=")) {
                    final boolean qualified = name.contains("::");
                    final String simple = qualified ? name.substring(name.lastIndexOf(':') + 1) : name;
                    if (!simple.isEmpty()) {
                        found.add(new Fn(qualified ? name : simple, qualified, returns,
                            original.substring(i + 1, close)));
                    }
                }
                i = k - 1;
            } else {
                i = close;
            }
        }
        return found;
    }

    private static final Pattern CPP_NAMED = Pattern.compile("^(.*?[\\s*&>])\\s*(\\w+)\\s*(\\[\\s*\\d*\\s*\\])?$", Pattern.DOTALL);

    private static VaultParam cppParam(String piece, int index) {
        final String p = piece.strip().replaceAll("\\s+", " ");
        if (p.isEmpty() || p.equals("void") || p.equals("...")) return null;
        String decl = p;
        String def = "";
        final int eq = topLevelIndexOf(p, '=');
        if (eq >= 0) {
            decl = p.substring(0, eq).strip();
            def = p.substring(eq + 1).strip();
        }
        String type;
        String name;
        final Matcher m = CPP_NAMED.matcher(decl);
        if (m.matches() && !m.group(1).isBlank()) {
            type = m.group(1).strip();
            name = m.group(2);
        } else {
            type = decl;
            name = "arg" + (index + 1);
        }
        final String t = type.replace(" *", "*").replace(" &", "&").strip();
        final String bare = t.replaceAll("\\b(const|volatile|struct)\\b", "").replace("&", "").strip();
        final boolean pointer = bare.contains("*");

        VaultParam.Type kind;
        if (bare.matches("bool|Bool_t")) {
            kind = VaultParam.Type.BOOL;
            if (def.equals("kTRUE")) def = "true";
            if (def.equals("kFALSE")) def = "false";
        } else if (!pointer && bare.matches("(unsigned |signed )?(int|long|short|long long|long int|size_t|std::size_t|"
                + "Int_t|UInt_t|Long_t|ULong_t|Long64_t|ULong64_t|Short_t|UShort_t|Int64_t|u?int\\d+_t|unsigned)")) {
            kind = VaultParam.Type.INT;
        } else if (!pointer && bare.matches("float|double|long double|Double_t|Float_t|Real_t|Double32_t")) {
            kind = VaultParam.Type.REAL;
        } else if (bare.matches("char\\s*\\*|Char_t\\s*\\*|TString|std::string|string|std::string_view|string_view")) {
            kind = VaultParam.looksLikePath(name) ? VaultParam.Type.PATH : VaultParam.Type.TEXT;
            if (def.length() >= 2 && def.startsWith("\"") && def.endsWith("\"")) {
                def = def.substring(1, def.length() - 1);
            } else if (def.equals("nullptr") || def.equals("0") || def.equals("NULL")) {
                def = "";
            }
        } else {
            kind = VaultParam.Type.RAW;
        }
        return new VaultParam(name, kind, null, def, "", List.of(), eq < 0, t);
    }

    // =========================================================================
    // Julia, Fortran, JavaScript
    // =========================================================================

    private static final Pattern JL_FN = Pattern.compile("(?m)^function\\s+(\\w[\\w!]*)|^(\\w[\\w!]*)\\(.*\\)\\s*=(?!=)");
    private static final Pattern JL_USES = Pattern.compile("(?m)^\\s*(?:using|import)\\s+([\\w.]+(?:\\s*,\\s*[\\w.]+)*)");
    private static final Pattern JL_ARGS = Pattern.compile("\\bARGS\\[(\\d+)\\]");

    private static void readJulia(String text, String[] lines, VaultAnalysis.Builder b) {
        final Matcher fn = JL_FN.matcher(text);
        while (fn.find()) b.functions.add(fn.group(1) != null ? fn.group(1) : fn.group(2));
        final Set<String> modules = new LinkedHashSet<>();
        final Matcher uses = JL_USES.matcher(text);
        while (uses.find()) {
            for (String m : uses.group(1).split(",")) modules.add(m.strip().split("\\.")[0]);
        }
        addCapped(b.uses, modules);
        if (b.params.isEmpty()) {
            int highest = 0;
            final Matcher args = JL_ARGS.matcher(text);
            while (args.find()) highest = Math.max(highest, Integer.parseInt(args.group(1)));
            for (int i = 1; i <= Math.min(highest, 9); i++) {
                b.params.add(new VaultParam("ARGS[" + i + "]", VaultParam.Type.RAW, null, "",
                    "ARGS[" + i + "]", List.of(), true, ""));
            }
            if (highest > 0) b.style = Style.POSITIONAL;
        }
    }

    private static final Pattern F_UNITS = Pattern.compile("(?im)^\\s*(?:program|subroutine|(?:\\w+\\s+)?function|module)\\s+(\\w+)");
    private static final Pattern F_USES = Pattern.compile("(?im)^\\s*use\\s+(\\w+)");
    private static final Pattern F_ARGS = Pattern.compile("(?i)get_command_argument\\s*\\(\\s*(\\d+)");

    private static void readFortran(String text, VaultAnalysis.Builder b) {
        final Matcher units = F_UNITS.matcher(text);
        while (units.find()) {
            if (!units.group(1).equalsIgnoreCase("procedure")) b.functions.add(units.group(1));
        }
        final Set<String> modules = new LinkedHashSet<>();
        final Matcher uses = F_USES.matcher(text);
        while (uses.find()) modules.add(uses.group(1));
        addCapped(b.uses, modules);
        if (b.params.isEmpty()) {
            int highest = 0;
            final Matcher args = F_ARGS.matcher(text);
            while (args.find()) highest = Math.max(highest, Integer.parseInt(args.group(1)));
            for (int i = 1; i <= Math.min(highest, 9); i++) {
                b.params.add(new VaultParam("arg" + i, VaultParam.Type.RAW, null, "",
                    "get_command_argument(" + i + ")", List.of(), true, ""));
            }
            if (highest > 0) b.style = Style.POSITIONAL;
        }
    }

    private static final Pattern JS_REQUIRES = Pattern.compile("require\\(\\s*['\"]([^'\"]+)['\"]|from\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern JS_FN = Pattern.compile("(?m)^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+(\\w+)");

    private static void readJs(String text, VaultAnalysis.Builder b) {
        final Matcher fn = JS_FN.matcher(text);
        while (fn.find()) b.functions.add(fn.group(1));
        final Set<String> modules = new LinkedHashSet<>();
        final Matcher req = JS_REQUIRES.matcher(text);
        while (req.find()) modules.add(req.group(1) != null ? req.group(1) : req.group(2));
        addCapped(b.uses, modules);
    }

    // =========================================================================
    // Small parsing helpers
    // =========================================================================

    /** The index of the parenthesis closing the one at open, skipping literals. */
    static int matchParen(String text, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') { i++; continue; }
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    /** Splits at a separator that sits outside brackets and literals. */
    static List<String> splitTopLevel(String text, char separator) {
        final List<String> parts = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') { i++; continue; }
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (c == '(' || c == '[' || c == '{' || c == '<') depth++;
            else if (c == ')' || c == ']' || c == '}' || c == '>') depth = Math.max(0, depth - 1);
            else if (c == separator && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static int topLevelIndexOf(String text, char wanted) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') { i++; continue; }
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (c == '(' || c == '[' || c == '{' || c == '<') depth++;
            else if (c == ')' || c == ']' || c == '}' || c == '>') depth = Math.max(0, depth - 1);
            else if (c == wanted && depth == 0) return i;
        }
        return -1;
    }

    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    private static void addCapped(Set<String> into, Set<String> from) {
        for (String one : from) {
            if (into.size() >= MAX_USES) return;
            if (!one.isBlank()) into.add(one);
        }
    }

    /** FNV-1a over the lines, line ends and trailing blanks evened out. */
    static long contentHash(String[] lines) {
        long h = 0xcbf29ce484222325L;
        boolean any = false;
        int last = lines.length - 1;
        while (last >= 0 && lines[last].isBlank()) last--;
        for (int i = 0; i <= last; i++) {
            final String line = lines[i].stripTrailing();
            for (int k = 0; k < line.length(); k++) {
                h ^= line.charAt(k);
                h *= 0x100000001b3L;
                any = true;
            }
            h ^= '\n';
            h *= 0x100000001b3L;
        }
        return any ? h : 0L;
    }

    /** Reads a file as text, or null when it is too large or binary. */
    static String read(Path path) {
        try {
            final File file = path.toFile();
            if (file.length() > MAX_READ) return null;
            final byte[] bytes = java.nio.file.Files.readAllBytes(path);
            final int probe = Math.min(bytes.length, 8000);
            for (int i = 0; i < probe; i++) {
                if (bytes[i] == 0) return null;
            }
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            return text;
        } catch (java.io.IOException | RuntimeException unreadable) {
            return null;
        }
    }
}
