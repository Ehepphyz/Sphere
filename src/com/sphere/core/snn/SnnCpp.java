package com.sphere.core.snn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Sphere's C++ mode and the Sphere Neural Network. A program that uses the network's modules (ROOT, Geant4, FastJet,
 * HepMC3, Pythia8, LHAPDF, libtorch...) is built by the network, where those modules are -- WSL's, seen from Windows --
 * with the installations settings.conf [SNN] chose, and healed when it does not build: a header given by its bare
 * name, a class or a namespace used without its header, a symbol the link missed. A program built the usual way that
 * fails on something the network knows is handed to it as well, and only then: the network never retries blindly.
 *
 * <p>What a source uses is read with the cortex's own rules (bridge/snn/rules.tsv) and what the network can heal with
 * its API index (bridge/snn/&lt;side&gt;/knows.tsv): both written by the cortex, read here without starting it.
 */
public final class SnnCpp {

    private SnnCpp() {
    }

    private record Rule(String kind, Pattern pattern, String neuron) {
    }

    private static final Pattern INCLUDE = Pattern.compile("^\\s*#\\s*include\\s*[<\"]([^>\"]+)[>\"]", Pattern.MULTILINE);
    private static final Pattern BLOCK = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE = Pattern.compile("//[^\n]*");
    /** gcc writes its quotes as the locale has them: '' in C, curly ones in UTF-8; clang its own. */
    private static final String Q = "['‘’`]";
    private static final Pattern[] HEADER = {
        Pattern.compile("fatal error: ([^\\s:'‘’`]+): No such file or directory"),
        Pattern.compile("fatal error: " + Q + "([^'’]+)" + Q + " file not found")};
    private static final Pattern[] NAME = {
        Pattern.compile("error: " + Q + "([A-Za-z_]\\w*)" + Q + " (?:was not declared in this scope|does not name a type|has not been declared)"),
        Pattern.compile("error: (?:unknown type name|use of undeclared identifier) " + Q + "([A-Za-z_]\\w*)" + Q),
        Pattern.compile("error: " + Q + "([A-Za-z_]\\w*)" + Q + " is not a member of " + Q + "([\\w:]+)" + Q),
        Pattern.compile("incomplete type " + Q + "(?:class |struct )?(?:[\\w:]+::)?([A-Za-z_]\\w*)" + Q)};
    private static final Pattern SYMBOL = Pattern.compile("undefined reference to " + Q + "([^'’]+)" + Q);

    private static List<Rule> rules = List.of();
    private static long rulesStamp = -1;
    private static final Map<String, Map<String, String>> KNOWS = new HashMap<>();
    private static long knowsStamp = -1;

    // ------------------------------------------------------------------
    // What the network knows
    // ------------------------------------------------------------------

    private static synchronized List<Rule> rules() {
        final Path f = Snn.folder().resolve("rules.tsv");
        try {
            final long stamp = Files.getLastModifiedTime(f).toMillis();
            if (stamp != rulesStamp) {
                final List<Rule> out = new ArrayList<>();
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    final String[] r = line.split("\t");
                    if (line.startsWith("#") || r.length != 3) {
                        continue;
                    }
                    try {
                        out.add(new Rule(r[0], Pattern.compile(r[1]), r[2]));
                    } catch (RuntimeException unreadable) {
                        // a pattern of Python's this Java cannot read: the others are enough
                    }
                }
                rules = out;
                rulesStamp = stamp;
            }
        } catch (IOException e) {
            rules = List.of();
        }
        return rules;
    }

    /** kind (header, base, class, ns) -> name -> neuron, from every side's knows.tsv. */
    private static synchronized Map<String, Map<String, String>> knows() {
        final List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(Snn.folder())) {
            s.map(p -> p.resolve("knows.tsv")).filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            return Map.of();
        }
        long stamp = files.size();
        for (Path f : files) {
            try {
                stamp = stamp * 31 + Files.getLastModifiedTime(f).toMillis();
            } catch (IOException e) {
                // gone meanwhile
            }
        }
        if (stamp != knowsStamp) {
            KNOWS.clear();
            for (Path f : files) {
                try {
                    for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                        final String[] r = line.split("\t");
                        if (!line.startsWith("#") && r.length == 3) {
                            KNOWS.computeIfAbsent(r[0], k -> new HashMap<>()).putIfAbsent(r[1], r[2]);
                        }
                    }
                } catch (IOException e) {
                    // the others are enough
                }
            }
            knowsStamp = stamp;
        }
        return KNOWS;
    }

    private static String known(String kind, String name) {
        return knows().getOrDefault(kind, Map.of()).get(name);
    }

    /** The network has been seen, and holds C++ modules somewhere: it can build a C++ program. */
    public static boolean reachable() {
        return !rules().isEmpty() && !knows().getOrDefault("header", Map.of()).isEmpty();
    }

    // ------------------------------------------------------------------
    // What a source uses, and what a failed build missed
    // ------------------------------------------------------------------

    /** The neurons a C++ source uses, with what named each: its #include lines, and the names a macro uses bare. */
    public static Map<String, String> needs(String source) {
        final Map<String, String> found = new LinkedHashMap<>();
        final String code = LINE.matcher(BLOCK.matcher(source).replaceAll(" ")).replaceAll("");
        final List<Rule> all = rules();
        final Matcher inc = INCLUDE.matcher(code);
        while (inc.find()) {
            final String h = inc.group(1);
            String n = null;
            for (Rule r : all) {
                if (r.kind().equals("include") && r.pattern().matcher(h).find()) {
                    n = r.neuron();
                    break;
                }
            }
            if (n == null) {
                n = known("header", h);
            }
            if (n != null && !n.equals("sphere-nn") && !n.equals("mlink")) {
                found.putIfAbsent(n, "#include <" + h + ">");
            }
        }
        for (Rule r : all) {
            if (r.kind().equals("code")) {
                final Matcher m = r.pattern().matcher(code);
                if (m.find()) {
                    found.putIfAbsent(r.neuron(), "uses " + m.group());
                }
            }
        }
        return found;
    }

    /**
     * What a failed build missed that the network knows: "TH1D (root)", "ClusterSequence.hh (fastjet)"; null when the
     * failure is the program's own (a syntax error, a typo the network has no module for).
     */
    public static String healable(String compilerOutput) {
        if (compilerOutput == null || compilerOutput.isBlank()) {
            return null;
        }
        for (Pattern p : HEADER) {
            final Matcher m = p.matcher(compilerOutput);
            while (m.find()) {
                final String h = m.group(1);
                final String n = known("header", h) != null ? known("header", h) : known("base", h.substring(h.lastIndexOf('/') + 1));
                if (n != null) {
                    return h + " (" + n + ")";
                }
            }
        }
        for (Pattern p : NAME) {
            final Matcher m = p.matcher(compilerOutput);
            while (m.find()) {
                final String x = m.group(1);
                final String n = known("class", x) != null ? known("class", x) : known("ns", x);
                if (n != null) {
                    return x + " (" + n + ")";
                }
            }
        }
        final Matcher m = SYMBOL.matcher(compilerOutput);
        while (m.find()) {
            String s = m.group(1).replaceFirst("^(?:vtable|typeinfo|typeinfo name|VTT) for ", "");
            s = s.contains("(") ? s.substring(0, s.indexOf('(')) : s;
            for (String part : s.replaceAll("<[^<>]*>", "").split("::")) {
                final String n = known("class", part.trim()) != null ? known("class", part.trim()) : known("ns", part.trim());
                if (n != null) {
                    return m.group(1) + " (" + n + ")";
                }
            }
        }
        return null;
    }

    /** The user gave the compiler flags of their own (-I, -L, -l, a config script): their way is tried first. */
    public static boolean ownFlags(List<String> flags) {
        if (flags == null) {
            return false;
        }
        for (String f : flags) {
            if (f.startsWith("-I") || f.startsWith("-L") || f.startsWith("-l") || f.contains("-config")) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Built and run by the network
    // ------------------------------------------------------------------

    /**
     * 'snn run' on a source: built where its neurons are, healed when it does not build, run with what it needs. Each
     * line said -- the plan, each fix, the program's output -- handed to out as it comes; the exit status returned.
     */
    public static int run(Path source, List<String> args, long seconds, Consumer<String> out, Consumer<Process> started) throws IOException {
        final List<String> cmd = new ArrayList<>(List.of("run", source.toAbsolutePath().toString()));
        if (args != null) {
            cmd.addAll(args);
        }
        return Snn.cortex(cmd, null, seconds, out, started);
    }

    /** A compiler's messages about the WSL copy of a source, made to name the source as Sphere knows it. */
    public static String local(String text, Path source) {
        final String win = source.toAbsolutePath().toString();
        if (win.length() < 3 || win.charAt(1) != ':') {
            return text;
        }
        final String wsl = "/mnt/" + Character.toLowerCase(win.charAt(0)) + win.substring(2).replace('\\', '/');
        return text.replace(wsl, win);
    }
}
