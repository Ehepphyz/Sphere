package com.sphere.core.geant4;

import com.sphere.core.geantbackend.G4Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Geant4's UI commands (/run/beamOn, /vis/open, /gun/energy...) as the master
 * sources define them, read from uicommands/g4commands.json. That catalog is
 * extracted from every messenger of the Geant4 tree (G4UIcommand,
 * G4UIcmdWith*, G4GenericMessenger) and from the templates that make the
 * /analysis/h1..p2 commands and the commands of the /vis/modeling and
 * /vis/filtering models; g4cmds.py and g4post.py beside it rebuild it from
 * another Geant4. Browsing, completing and checking a macro need no Geant4.
 *
 * The commands of a vis model are listed under its first default name
 * (drawByCharge-0, particleFilter-0, context default): find() answers for any
 * other name a model or context was given.
 */
public final class UiCommands {

    public record Param(String name, char type, boolean omittable, boolean currentAsDefault, String defaultValue,
                        String candidates, String range, String defaultUnit, String unitCategory, List<String> guidance) {

        /** The parameter as the usage line shows it: name, or [name=default] when it can be left out. */
        public String usage() {
            final String n = name + (candidates != null && !candidates.isBlank() && candidates.split(" ").length <= 6
                ? ":" + candidates.replace(' ', '|') : "");
            if (!omittable) {
                return "<" + n + ">";
            }
            return "[" + n + (defaultValue != null && !defaultValue.isEmpty() ? "=" + defaultValue
                : currentAsDefault ? "=current" : "") + "]";
        }
    }

    public record Command(String path, String kind, List<String> guidance, List<Param> params, String range,
                          List<String> states, String source, boolean template) {

        public String usage() {
            final StringBuilder b = new StringBuilder(path);
            for (Param p : params) {
                b.append(' ').append(p.usage());
            }
            return b.toString();
        }

        /** The first line of guidance, for listings. */
        public String summary() {
            for (String g : guidance) {
                final String s = g.strip();
                if (!s.isEmpty()) {
                    final int nl = s.indexOf('\n');
                    return nl < 0 ? s : s.substring(0, nl).strip();
                }
            }
            return "";
        }
    }

    public record Directory(String path, List<String> guidance) {
    }

    private static final String RESOURCE = "/com/sphere/core/geant4/uicommands/g4commands.json";
    private static volatile UiCommands instance;

    private final String version;
    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final Map<String, Directory> directories = new LinkedHashMap<>();

    private UiCommands(Map<String, Object> doc) {
        version = G4Json.str(doc, "geant4", "");
        for (Object o : G4Json.list(doc, "directories")) {
            final String p = G4Json.str(o, "path", "");
            directories.put(p, new Directory(p, strings(G4Json.list(o, "guidance"))));
        }
        for (Object o : G4Json.list(doc, "commands")) {
            final List<Param> params = new ArrayList<>();
            for (Object q : G4Json.list(o, "params")) {
                final String t = G4Json.str(q, "type", "s");
                params.add(new Param(G4Json.str(q, "name", ""), t.isEmpty() ? 's' : t.charAt(0), bool(q, "omittable"),
                    bool(q, "currentAsDefault"), G4Json.str(q, "default", null), G4Json.str(q, "candidates", null),
                    G4Json.str(q, "range", null), G4Json.str(q, "defaultUnit", null), G4Json.str(q, "unitCategory", null),
                    strings(G4Json.list(q, "guidance"))));
            }
            final Command c = new Command(G4Json.str(o, "path", ""), G4Json.str(o, "class", ""),
                strings(G4Json.list(o, "guidance")), List.copyOf(params), G4Json.str(o, "range", null),
                strings(G4Json.list(o, "states")), G4Json.str(o, "source", ""), bool(o, "template"));
            commands.put(c.path(), c);
        }
    }

    /** The catalog, read once from Sphere's classes (or from the source tree when run from there). */
    public static UiCommands get() throws IOException {
        UiCommands u = instance;
        if (u == null) {
            synchronized (UiCommands.class) {
                if (instance == null) {
                    instance = new UiCommands(G4Json.object(read()));
                }
                u = instance;
            }
        }
        return u;
    }

    private static String read() throws IOException {
        try (InputStream in = UiCommands.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        final Path tree = Path.of("src", RESOURCE.substring(1));
        if (Files.isRegularFile(tree)) {
            return Files.readString(tree);
        }
        throw new IOException("the Geant4 command catalog is not in Sphere's classes (run.bat copies uicommands/)");
    }

    private static boolean bool(Object o, String key) {
        return o instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get(key));
    }

    private static List<String> strings(List<?> l) {
        final List<String> out = new ArrayList<>(l.size());
        for (Object o : l) {
            if (o instanceof String s) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    public String version() {
        return version;
    }

    public int size() {
        return commands.size();
    }

    public Directory directory(String path) {
        return directories.get(path.endsWith("/") ? path : path + "/");
    }

    // ------------------------------------------------------------------
    // Lookup
    // ------------------------------------------------------------------

    private static final Pattern VIS_MODEL = Pattern.compile("^(/vis/(?:modeling|filtering)/\\w+)/([^/]+)/(.+)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\w+)-\\d+$");

    /**
     * The command at a path. A vis model's commands answer under any name the model got
     * (drawByCharge-3, a name given to create/drawByParticleID, a drawByAttribute interval context).
     */
    public Command find(String path) {
        final Command c = commands.get(path);
        if (c != null) {
            return c;
        }
        final Matcher m = VIS_MODEL.matcher(path);
        if (!m.matches()) {
            return null;
        }
        final String base = m.group(1), name = m.group(2), rest = m.group(3);
        final List<String> rests = new ArrayList<>(List.of(rest));
        final int slash = rest.indexOf('/');
        if (slash > 0) {
            rests.add("default" + rest.substring(slash)); // a context of its own: interval1/setLineColour
        }
        final Matcher n = NUMBERED.matcher(name);
        for (String r : rests) {
            if (n.matches() && commands.containsKey(base + "/" + n.group(1) + "-0/" + r)) {
                return commands.get(base + "/" + n.group(1) + "-0/" + r);
            }
            for (Command t : commands.values()) {
                if (t.template() && t.path().endsWith("/" + r)) {
                    // base/<model>-0/r, the model being one path element
                    final String model = t.path().substring(0, t.path().length() - r.length() - 1);
                    if (model.startsWith(base + "/") && model.indexOf('/', base.length() + 1) < 0) {
                        return t;
                    }
                }
            }
        }
        return null;
    }

    /** Commands directly in a directory. */
    public List<Command> in(String dir) {
        final String d = dir.endsWith("/") ? dir : dir + "/";
        final List<Command> out = new ArrayList<>();
        for (Command c : commands.values()) {
            if (c.path().startsWith(d) && c.path().indexOf('/', d.length()) < 0) {
                out.add(c);
            }
        }
        return out;
    }

    /** Sub-directories directly under a directory ("/" for the top ones). */
    public List<String> subdirectories(String dir) {
        final String d = dir.endsWith("/") ? dir : dir + "/";
        final TreeSet<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String p : directories.keySet()) {
            if (p.length() > d.length() && p.startsWith(d) && p.indexOf('/', d.length()) == p.length() - 1) {
                out.add(p);
            }
        }
        return new ArrayList<>(out);
    }

    /** How many commands a directory holds, at any depth. */
    public int countUnder(String dir) {
        int n = 0;
        for (String p : commands.keySet()) {
            if (p.startsWith(dir)) {
                n++;
            }
        }
        return n;
    }

    /** Commands whose path or guidance holds a word, the path matches first. */
    public List<Command> search(String word) {
        final String w = word.toLowerCase(Locale.ROOT);
        final List<Command> byPath = new ArrayList<>(), byText = new ArrayList<>();
        for (Command c : commands.values()) {
            if (c.path().toLowerCase(Locale.ROOT).contains(w)) {
                byPath.add(c);
            } else if (String.join(" ", c.guidance()).toLowerCase(Locale.ROOT).contains(w)) {
                byText.add(c);
            }
        }
        byPath.addAll(byText);
        return byPath;
    }

    /** Command and directory paths that begin with a prefix: the completions of a partly typed command. */
    public List<String> complete(String prefix) {
        final TreeSet<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String p : directories.keySet()) {
            if (p.startsWith(prefix) && p.indexOf('/', prefix.length()) == p.length() - 1) {
                out.add(p);
            }
        }
        for (String p : commands.keySet()) {
            if (p.startsWith(prefix) && p.indexOf('/', prefix.length()) < 0) {
                out.add(p);
            }
        }
        return new ArrayList<>(out);
    }

    /** Everything guidance says about a command, as Geant4's help prints it. */
    public List<String> describe(Command c) {
        final List<String> out = new ArrayList<>();
        out.add(c.usage());
        for (String g : c.guidance()) {
            for (String l : g.split("\n", -1)) {
                out.add("  " + l);
            }
        }
        if (c.range() != null) {
            out.add("  Range: " + c.range());
        }
        if (!c.states().isEmpty()) {
            out.add("  Available in: " + String.join(", ", c.states()));
        }
        for (Param p : c.params()) {
            final StringBuilder b = new StringBuilder("  - ").append(p.name()).append(" (").append(typeName(p.type()))
                .append(p.omittable() ? ", optional" : "").append(')');
            if (p.defaultValue() != null && !p.defaultValue().isEmpty()) {
                b.append(" default ").append(p.defaultValue());
            } else if (p.currentAsDefault()) {
                b.append(" default: the current value");
            }
            if (p.defaultUnit() != null && !p.defaultUnit().equals(p.defaultValue())) {
                b.append(", unit ").append(p.defaultUnit());
            }
            if (p.unitCategory() != null) {
                b.append(", ").append(p.unitCategory()).append(" units");
            }
            if (p.candidates() != null && !p.candidates().isBlank()) {
                b.append(", one of: ").append(p.candidates());
            }
            if (p.range() != null) {
                b.append(", range ").append(p.range());
            }
            out.add(b.toString());
            for (String g : p.guidance()) {
                for (String l : g.split("\n")) {
                    out.add("      " + l);
                }
            }
        }
        if (c.template()) {
            out.add("  (a vis model's command: the model may carry another name than the one shown)");
        }
        out.add("  Defined in " + c.source());
        return out;
    }

    static String typeName(char t) {
        return switch (t) {
            case 'i' -> "integer";
            case 'd' -> "double";
            case 'b' -> "bool";
            case 'l' -> "long";
            case 'c' -> "char";
            default -> "string";
        };
    }

    // ------------------------------------------------------------------
    // Checking a macro
    // ------------------------------------------------------------------

    private static final Pattern INT = Pattern.compile("[+-]?\\d+");
    private static final Pattern DOUBLE = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");
    private static final List<String> BOOLS = List.of("y", "n", "yes", "no", "1", "0", "t", "f", "true", "false");

    /**
     * One thing check() found: an ERROR G4UImanager refuses the line for; a NOTE on what Geant4 lets
     * pass but is likely a slip (words past the last parameter, which it ignores); or a FOREIGN
     * command, one Geant4 itself has not in one of its directories, fine when the application defines
     * it (examples add their own commands to /gun/, /run/, /analysis/...).
     */
    public record Finding(String message, Kind kind) {
        public enum Kind { ERROR, NOTE, FOREIGN }

        public boolean error() {
            return kind == Kind.ERROR;
        }
    }

    /**
     * What is wrong with one macro line: a missing parameter, a value of the wrong type or outside
     * its candidates, a directory given as a command (errors), or a command Geant4 has not in one of
     * its directories (not an error: only the program knows its own commands). Nothing for a fine
     * line, a comment, or a command in a directory of the application's own (/B1/, /testem/...).
     */
    public List<Finding> check(String line) {
        final List<Finding> problems = new ArrayList<>();
        final String s = line.strip();
        if (s.isEmpty() || s.startsWith("#") || !s.startsWith("/")) {
            return problems;
        }
        final List<String> words = tokens(s);
        final String path = words.get(0);
        final Command c = find(path);
        if (c == null) {
            final int second = path.indexOf('/', 1);
            final String top = second < 0 ? path + "/" : path.substring(0, second + 1);
            if (directories.containsKey(path.endsWith("/") ? path : path + "/")) {
                problems.add(new Finding(path + " is a directory, not a command", Finding.Kind.ERROR));
            } else if (directories.containsKey(top) && !path.contains("{")) {
                final List<Command> near = search(path.substring(path.lastIndexOf('/') + 1));
                problems.add(new Finding(path + " is not a Geant4 command: the application must define it"
                    + (near.isEmpty() ? "" : " (or did you mean " + near.get(0).path() + "?)"), Finding.Kind.FOREIGN));
            }
            return problems;
        }
        final List<String> values = words.subList(1, words.size());
        final List<Param> params = c.params();
        for (int i = 0; i < params.size(); i++) {
            final Param p = params.get(i);
            if (i >= values.size()) {
                if (!p.omittable()) {
                    problems.add(new Finding(path + ": missing " + p.name() + " (" + typeName(p.type()) + ")", Finding.Kind.ERROR));
                }
                continue;
            }
            final String v = values.get(i);
            if (v.contains("{") || v.equals("!")) {
                continue; // an alias, or "!" for the default
            }
            final boolean unit = p.name().equals("Unit") || p.defaultUnit() != null && p.type() == 's';
            switch (p.type()) {
                case 'i', 'l' -> {
                    if (!INT.matcher(v).matches()) {
                        problems.add(new Finding(path + ": " + p.name() + " must be an integer, not " + v, Finding.Kind.ERROR));
                    }
                }
                case 'd' -> {
                    if (!DOUBLE.matcher(v).matches()) {
                        problems.add(new Finding(path + ": " + p.name() + " must be a number, not " + v, Finding.Kind.ERROR));
                    }
                }
                case 'b' -> {
                    if (!BOOLS.contains(v.toLowerCase(Locale.ROOT))) {
                        problems.add(new Finding(path + ": " + p.name() + " must be true or false, not " + v, Finding.Kind.ERROR));
                    }
                }
                default -> {
                    if (!unit && p.candidates() != null && !p.candidates().isBlank()
                        && !List.of(p.candidates().split(" ")).contains(v)) {
                        problems.add(new Finding(path + ": " + p.name() + " must be one of " + p.candidates() + ", not " + v, Finding.Kind.ERROR));
                    }
                }
            }
        }
        // Extra words are glued onto a last string parameter, as G4UIcommand does; otherwise it ignores them
        final boolean lastIsString = !params.isEmpty() && params.get(params.size() - 1).type() == 's';
        if (values.size() > params.size() && !lastIsString && !c.kind().startsWith("G4GenericMessenger")) {
            problems.add(new Finding(path + ": " + String.join(" ", values.subList(params.size(), values.size()))
                + " ignored, past the last parameter (usage: " + c.usage() + ")", Finding.Kind.NOTE));
        }
        return problems;
    }

    /** A macro line in words, double-quoted strings kept whole, a trailing # comment dropped. */
    public static List<String> tokens(String line) {
        final List<String> out = new ArrayList<>();
        final StringBuilder b = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            final char ch = line.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
                b.append(ch);
            } else if (!quoted && ch == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                break;
            } else if (!quoted && Character.isWhitespace(ch)) {
                if (!b.isEmpty()) {
                    out.add(b.toString());
                    b.setLength(0);
                }
            } else {
                b.append(ch);
            }
        }
        if (!b.isEmpty()) {
            out.add(b.toString());
        }
        return out;
    }
}
