package com.sphere.core.geant4;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * Geant4's public C++ API: every class of the source tree's headers, its
 * bases, constructors and public methods with the comment Geant4 writes for
 * each, as api/g4api.py extracted them from Geant4 master. Completion and
 * hover in the code editors and the API browser of :g4code read it, without
 * Geant4 installed.
 */
public final class Geant4Api {

    /** A constructor (name equal to the class) or a method. */
    public record Method(String owner, String name, String params, String returns, boolean isStatic, String doc) {

        public boolean isConstructor() {
            return name.equals(owner);
        }

        /** "G4Box(const G4String& pName, G4double pX, ...)" or "G4double GetXHalfLength()". */
        public String signature() {
            return (returns.isEmpty() ? "" : (isStatic ? "static " : "") + returns + " ") + name + "(" + params + ")";
        }

        /** The description without the parameter notes. */
        public String brief() {
            final int p = doc.indexOf(" ¶ ");
            return p < 0 ? doc : doc.substring(0, p);
        }

        /** "pX: Half length in X." for each documented parameter. */
        public List<String> paramNotes() {
            final int p = doc.indexOf(" ¶ ");
            return p < 0 ? List.of() : List.of(doc.substring(p + 3).split("; "));
        }
    }

    public record ClassInfo(String name, List<String> bases, String category, String header, String doc,
                            List<Method> constructors, List<Method> methods) {
    }

    private static final String RESOURCE = "/com/sphere/core/geant4/api/g4api.txt.gz";
    private static volatile Geant4Api shared;

    private final String version;
    private final Map<String, ClassInfo> classes;

    private Geant4Api(String version, Map<String, ClassInfo> classes) {
        this.version = version;
        this.classes = classes;
    }

    /** The catalog shipped with Sphere, read once (about 3400 classes; empty when the resource is missing). */
    public static Geant4Api get() {
        Geant4Api a = shared;
        if (a == null) {
            synchronized (Geant4Api.class) {
                a = shared;
                if (a == null) {
                    a = load();
                    shared = a;
                }
            }
        }
        return a;
    }

    /** True once the catalog was read: callers on the event thread may prefer not to wait for it. */
    public static boolean loaded() {
        return shared != null;
    }

    private static Geant4Api load() {
        final Map<String, ClassInfo> out = new LinkedHashMap<>();
        String version = "Geant4";
        try (InputStream in = Geant4Api.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return new Geant4Api("no Geant4 API catalog", Map.of());
            }
            final BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(in, 1 << 16),
                StandardCharsets.UTF_8));
            String cls = null;
            List<String> bases = List.of();
            String[] head = null;
            List<Method> ctors = new ArrayList<>(), methods = new ArrayList<>();
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                if (line.isEmpty()) {
                    continue;
                }
                final char k = line.charAt(0);
                if (k == '#') {
                    version = line.substring(1).strip();
                } else if (k == '@') {
                    if (cls != null) {
                        out.put(cls, new ClassInfo(cls, bases, head[2], head[3], head[4], List.copyOf(ctors),
                            List.copyOf(methods)));
                    }
                    head = split(line.substring(1), 5);
                    cls = head[0];
                    bases = head[1].isEmpty() ? List.of() : List.of(head[1].split(","));
                    ctors = new ArrayList<>();
                    methods = new ArrayList<>();
                } else if (cls != null && k == 'c') {
                    final String[] f = split(line, 3);
                    ctors.add(new Method(cls, cls, f[1], "", false, f[2]));
                } else if (cls != null && (k == 'm' || k == 's')) {
                    final String[] f = split(line, 5);
                    methods.add(new Method(cls, f[2], f[3], f[1], k == 's', f[4]));
                }
            }
            if (cls != null) {
                out.put(cls, new ClassInfo(cls, bases, head[2], head[3], head[4], List.copyOf(ctors),
                    List.copyOf(methods)));
            }
        } catch (Exception e) {
            return new Geant4Api("Geant4 API catalog unreadable: " + e.getMessage(), Map.of());
        }
        return new Geant4Api(version, Collections.unmodifiableMap(out));
    }

    private static String[] split(String line, int n) {
        final String[] out = new String[n];
        int from = 0;
        for (int i = 0; i < n; i++) {
            final int bar = i == n - 1 ? -1 : line.indexOf('|', from);
            out[i] = bar < 0 ? line.substring(Math.min(from, line.length())) : line.substring(from, bar);
            from = bar < 0 ? line.length() : bar + 1;
        }
        return out;
    }

    public String version() {
        return version;
    }

    public int size() {
        return classes.size();
    }

    public ClassInfo find(String name) {
        return name == null ? null : classes.get(name);
    }

    public Collection<ClassInfo> classes() {
        return classes.values();
    }

    /** The class and its bases, nearest first. */
    public List<ClassInfo> lineage(String name) {
        final List<ClassInfo> out = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        lineage(name, out, seen, 0);
        return out;
    }

    private void lineage(String name, List<ClassInfo> out, Set<String> seen, int depth) {
        final ClassInfo c = find(stripTemplate(name));
        if (c == null || depth > 12 || !seen.add(c.name())) {
            return;
        }
        out.add(c);
        for (String b : c.bases()) {
            lineage(b, out, seen, depth + 1);
        }
    }

    /** Methods of a class with those it inherits, an override hiding the base's method of the same name. */
    public List<Method> allMethods(String name) {
        final Map<String, Method> out = new LinkedHashMap<>();
        for (ClassInfo c : lineage(name)) {
            for (Method m : c.methods()) {
                out.putIfAbsent(m.name() + "(" + m.params() + ")", m);
            }
        }
        return new ArrayList<>(out.values());
    }

    /** The classes deriving directly from one. */
    public List<ClassInfo> subclasses(String name) {
        final List<ClassInfo> out = new ArrayList<>();
        for (ClassInfo c : classes.values()) {
            for (String b : c.bases()) {
                if (stripTemplate(b).equals(name)) {
                    out.add(c);
                    break;
                }
            }
        }
        return out;
    }

    /** Classes by category ("geometry/solids/CSG"), sorted. */
    public Map<String, List<ClassInfo>> byCategory() {
        final Map<String, List<ClassInfo>> out = new TreeMap<>();
        for (ClassInfo c : classes.values()) {
            out.computeIfAbsent(c.category(), k -> new ArrayList<>()).add(c);
        }
        return out;
    }

    /**
     * Classes and methods matching a query, best first: an exact class name,
     * then class names starting with it, containing it, then methods by name
     * and finally descriptions holding every word of it.
     */
    public List<Object> search(String query, int max) {
        final String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            return List.of();
        }
        final String lq = q.toLowerCase(Locale.ROOT);
        final String[] words = lq.split("\\s+");
        final List<Object> exact = new ArrayList<>(), prefix = new ArrayList<>(), inside = new ArrayList<>(),
            methods = new ArrayList<>(), docs = new ArrayList<>();
        for (ClassInfo c : classes.values()) {
            final String ln = c.name().toLowerCase(Locale.ROOT);
            if (ln.equals(lq) || ln.equals("g4" + lq)) {
                exact.add(c);
            } else if (ln.startsWith(lq) || ln.startsWith("g4" + lq)) {
                prefix.add(c);
            } else if (ln.contains(lq)) {
                inside.add(c);
            } else if (words.length > 0 && containsAll(c.doc().toLowerCase(Locale.ROOT), words)) {
                docs.add(c);
            }
            if (methods.size() < max) {
                for (Method m : c.methods()) {
                    if (m.name().toLowerCase(Locale.ROOT).startsWith(lq)) {
                        methods.add(m);
                    }
                }
            }
        }
        prefix.sort((a, b) -> Integer.compare(((ClassInfo) a).name().length(), ((ClassInfo) b).name().length()));
        final List<Object> out = new ArrayList<>(exact);
        for (List<Object> l : List.of(prefix, inside, methods, docs)) {
            for (Object o : l) {
                if (out.size() >= max) {
                    return out;
                }
                out.add(o);
            }
        }
        return out;
    }

    private static boolean containsAll(String text, String[] words) {
        for (String w : words) {
            if (!text.contains(w)) {
                return false;
            }
        }
        return true;
    }

    /** "G4THitsCollection<T>" reads as its template. */
    static String stripTemplate(String name) {
        final int lt = name.indexOf('<');
        return lt < 0 ? name.strip() : name.substring(0, lt).strip();
    }
}
