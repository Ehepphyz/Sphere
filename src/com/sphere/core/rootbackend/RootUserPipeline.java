package com.sphere.core.rootbackend;

import com.sphere.utils.AppLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The user's own ROOT pipeline: shared libraries dropped in includes/ and
 * macros kept in user_scripts/.
 *
 * Both folders existed and were protected from cleanup, but nothing ever read
 * them and no library was ever loaded. This class is that missing step.
 *
 * Two layers are searched, in this order: the global pair beside Sphere, then
 * the active project's own pair. The project comes second so it can override
 * a library or a macro of the same name.
 */
public final class RootUserPipeline {

    public static final String INCLUDES_DIR = "includes";
    public static final String SCRIPTS_DIR = "user_scripts";

    private static final String WORKSPACE = "WorkSpace";
    private static final String BACKEND = "rootbackend";
    private static final long LOAD_TIMEOUT_MS = 20_000L;

    private RootUserPipeline() { }

    /** What one pass of loadInto did, so a caller can report it. */
    public record Outcome(int loaded, int alreadyLoaded, int failed, List<String> problems) {
        public int total() { return loaded + alreadyLoaded + failed; }
    }

    /** A library that is loaded and has said what is in it. */
    public record Pipeline(String name, RootPipelineManifest manifest,
                           Path library, Path source, Path layer) {

        /** True when the library was loaded without saying anything about itself. */
        public boolean isSilent() { return manifest == null; }

        /** One line for a listing, whether or not the library declared itself. */
        public String summary() {
            if (manifest == null) {
                return name + "  (no manifest)";
            }
            String text = manifest.signature();
            if (manifest.description != null && !manifest.description.isBlank()) {
                text = text + "  -- " + manifest.description;
            }
            return text;
        }
    }

    /** What is loaded right now, by pipeline name, in the order it was loaded. */
    private static final java.util.Map<String, Pipeline> REGISTRY =
        java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());

    /** Told about every pipeline that becomes loaded, whoever loaded it. */
    private static volatile java.util.function.Consumer<Pipeline> announce;

    /**
     * Sets what is told when a pipeline is loaded.
     *
     * A library is loaded from four places, one of them before any command
     * exists. A hook lets the command layer hear about all four without this
     * class having to know it is there.
     */
    public static void onLoad(java.util.function.Consumer<Pipeline> hook) {
        announce = hook;
    }

    /* ------------------------------------------------------------------ */
    /* Where the folders are                                               */
    /* ------------------------------------------------------------------ */

    /** The pair beside Sphere, shared by every project. */
    public static Path globalRoot() {
        return Paths.get(System.getProperty("user.dir"), BACKEND).toAbsolutePath().normalize();
    }

    /** The pair inside a project, or null when no project is active. */
    public static Path projectRoot(String activeProject) {
        if (activeProject == null || activeProject.isBlank()) {
            return null;
        }
        Path project = Paths.get(WORKSPACE, activeProject).toAbsolutePath().normalize();
        return Files.isDirectory(project) ? project : null;
    }

    /** Global first, then the project, which is what lets the project win. */
    public static List<Path> layers(String activeProject) {
        List<Path> roots = new ArrayList<>(2);
        roots.add(globalRoot());
        Path project = projectRoot(activeProject);
        if (project != null && !project.equals(globalRoot())) {
            roots.add(project);
        }
        return roots;
    }

    /** Creates includes/ and user_scripts/ under a root if they are missing. */
    public static void ensureLayout(Path root) {
        if (root == null) return;
        for (String name : new String[]{INCLUDES_DIR, SCRIPTS_DIR}) {
            Path dir = root.resolve(name);
            if (Files.isDirectory(dir)) continue;
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                AppLogger.warn("Could not create " + dir + ": " + e.getMessage());
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* What is in them                                                     */
    /* ------------------------------------------------------------------ */

    /** Shared libraries under a layer's includes/, whatever this platform calls them. */
    public static List<Path> libraries(Path root) {
        return filesIn(root, INCLUDES_DIR, name ->
            name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".dylib")
            || name.contains(".so."));
    }

    /** C++ sources under a layer's includes/, the ones :root includes build compiles. */
    public static List<Path> sources(Path root) {
        return filesIn(root, INCLUDES_DIR, name ->
            name.endsWith(".cpp") || name.endsWith(".cxx") || name.endsWith(".cc")
            || name.endsWith(".c"));
    }

    /** Macros under a layer's user_scripts/. */
    public static List<Path> macros(Path root) {
        return filesIn(root, SCRIPTS_DIR, name ->
            name.endsWith(".c") || name.endsWith(".cpp") || name.endsWith(".cxx")
            || name.endsWith(".cc") || name.endsWith(".h"));
    }

    private static List<Path> filesIn(Path root, String folder,
                                      java.util.function.Predicate<String> accept) {
        List<Path> found = new ArrayList<>();
        if (root == null) return found;
        Path dir = root.resolve(folder);
        if (!Files.isDirectory(dir)) return found;
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isRegularFile)
                   .filter(p -> accept.test(p.getFileName().toString().toLowerCase(Locale.ROOT)))
                   .sorted()
                   .forEach(found::add);
        } catch (IOException e) {
            AppLogger.warn("Could not read " + dir + ": " + e.getMessage());
        }
        return found;
    }

    /**
     * Finds a macro by bare name, so ":root script run analyze.C" works without
     * a path. The project is searched first, then the global folder, then the
     * name is taken as a path relative to the working directory.
     */
    public static Path resolveMacro(String name, String activeProject) {
        if (name == null || name.isBlank()) return null;
        String wanted = name.trim().replace("\"", "");

        Path direct = Paths.get(wanted);
        if (direct.isAbsolute() && Files.isRegularFile(direct)) {
            return direct.normalize();
        }

        List<Path> roots = layers(activeProject);
        // Reversed: the project layer wins over the global one
        for (int i = roots.size() - 1; i >= 0; i--) {
            Path candidate = roots.get(i).resolve(SCRIPTS_DIR).resolve(wanted);
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }

        Path relative = Paths.get(System.getProperty("user.dir")).resolve(wanted);
        return Files.isRegularFile(relative) ? relative.normalize() : null;
    }

    /* ------------------------------------------------------------------ */
    /* Loading                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Adds every layer's includes/ to the interpreter's search path, then loads
     * each shared library it finds there.
     *
     * A library that refuses to load is named and the next one is tried, so one
     * stale build cannot keep the whole session from starting.
     */
    public static Outcome loadInto(RootBackend backend, String activeProject) {
        List<String> problems = new ArrayList<>();
        int loaded = 0, already = 0, failed = 0;

        if (backend == null || !backend.isAvailable()) {
            return new Outcome(0, 0, 0, problems);
        }

        final List<Path> roots = layers(activeProject);
        // The project overrides the global layer by name, which only holds if
        // the global library is never loaded: ROOT 6.41 takes a library by its
        // file name, so with the global libmt.so in, the project's libmt.so is
        // answered "already loaded" and never read, and every call by name
        // reaches the global one.
        final java.util.Set<String> overridden = new java.util.HashSet<>();
        for (int k = 1; k < roots.size(); k++) {
            for (Path library : libraries(roots.get(k))) {
                overridden.add(pipelineName(library));
            }
        }
        final List<String> staleNames = new ArrayList<>();
        final List<String> shadowed = new ArrayList<>();

        for (int r = 0; r < roots.size(); r++) {
            final Path root = roots.get(r);
            ensureLayout(root);
            Path includes = root.resolve(INCLUDES_DIR);
            if (!Files.isDirectory(includes)) continue;

            // The header search path first, so a library's own headers resolve
            ask(backend, "gROOT->ProcessLine(\".I " + forCling(includes) + "\")");

            for (Path library : libraries(root)) {
                final String name = pipelineName(library);
                if (r < roots.size() - 1 && overridden.contains(name)) {
                    continue;
                }
                final Loaded one = loadOne(backend, root, library);
                if (one.ok() && one.shadowedBy() != null) {
                    shadowed.add(name + " (" + one.shadowedBy() + ")");
                }
                switch (one.code()) {
                    case 0 -> loaded++;
                    case 1 -> {
                        already++;
                        if (one.stale()) {
                            staleNames.add(name);
                        }
                    }
                    default -> {
                        failed++;
                        problems.add(library.getFileName() + "  (" + one.problem() + ")");
                        AppLogger.warn("Could not load " + library + ": " + one.problem());
                    }
                }
            }
        }

        if (loaded + already > 0) {
            AppLogger.success("ROOT pipeline: " + loaded + " librar"
                + (loaded == 1 ? "y" : "ies") + " loaded"
                + (already > 0 ? ", " + already + " already there" : "")
                + (failed > 0 ? ", " + failed + " refused" : "") + ".");
        } else if (failed > 0) {
            AppLogger.warn("ROOT pipeline: none of the " + failed
                + " libraries in includes/ could be loaded.");
        }
        if (!staleNames.isEmpty()) {
            AppLogger.warn("Rebuilt after the engine loaded them, still running their old build: "
                + String.join(", ", staleNames) + ". " + RESTART);
        }
        if (!shadowed.isEmpty()) {
            AppLogger.warn("Calls by name still reach the library of the same name the engine read first: "
                + String.join(", ", shadowed) + ". " + RESTART);
        }
        return new Outcome(loaded, already, failed, problems);
    }

    /** How a library the engine holds is replaced, the one way that is safe. */
    public static final String RESTART =
        "A loaded library cannot be swapped: stop and start the ROOT backend"
        + " (console menu, ROOT) to run the new one.";

    /* ------------------------------------------------------------------ */
    /* Which build the engine runs                                         */
    /* ------------------------------------------------------------------ */

    /**
     * When each library on disk was built, as the engine first loaded it.
     *
     * gSystem->Load reads a library once: asked again, it answers "already
     * loaded" and the old build keeps running, whatever was rebuilt since.
     * Unloading is no way out. With ROOT 6.41, gSystem->Unload crashed inside
     * cling once RDataFrame had called into the library, and otherwise took
     * back every declaration and variable made since the library came in.
     * So a rebuild is only noticed and said, and a restart of the engine is
     * what runs it.
     */
    private static final java.util.Map<Path, Read> LOADED_BUILD =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** The build the engine read, and when it read it relative to the others. */
    private record Read(long builtAt, long order) { }

    private static final java.util.concurrent.atomic.AtomicLong ORDER =
        new java.util.concurrent.atomic.AtomicLong();

    /**
     * The engine those builds were read by. Stopping and starting the ROOT
     * backend makes a new one, with nothing loaded: what the old one held
     * says nothing about it.
     */
    private static RootBackend readBy;

    private static synchronized void engineIs(RootBackend backend) {
        if (backend != null && backend != readBy) {
            LOADED_BUILD.clear();
            readBy = backend;
        }
    }

    private static Path key(Path library) {
        return library.toAbsolutePath().normalize();
    }

    private static long builtAt(Path library) {
        try {
            return Files.getLastModifiedTime(library).toMillis();
        } catch (IOException | RuntimeException gone) {
            return Long.MIN_VALUE;
        }
    }

    /** Remembers which build of a library the engine has just read. */
    static void noteLoaded(Path library) {
        LOADED_BUILD.put(key(library), new Read(builtAt(library), ORDER.incrementAndGet()));
    }

    /** True when the library on disk was built after the engine read it: the engine runs an older build. */
    public static boolean stale(Path library) {
        final Read then = LOADED_BUILD.get(key(library));
        return then != null && builtAt(library) > then.builtAt();
    }

    /**
     * Another library of the same pipeline name that the engine read earlier.
     * It keeps the name whatever comes after it: ROOT takes the second for the
     * same library (same file name) and does not read it, and were it read,
     * both would export the same symbol and the first would answer the calls.
     */
    static Path earlierNamesake(Path library) {
        final String name = pipelineName(library);
        final Path self = key(library);
        final Read mine = LOADED_BUILD.get(self);
        for (java.util.Map.Entry<Path, Read> other : LOADED_BUILD.entrySet()) {
            if (!other.getKey().equals(self) && pipelineName(other.getKey()).equals(name)
                    && (mine == null || other.getValue().order() < mine.order())) {
                return other.getKey();
            }
        }
        return null;
    }

    /**
     * What loading one library came to.
     *
     * @param stale      the engine kept an older build of this very file
     * @param shadowedBy another library of the same name read earlier, which
     *                   keeps answering calls by name; null when there is none
     */
    public record Loaded(int code, String answer, Pipeline pipeline, boolean stale, Path shadowedBy) {
        /** Loaded now, or there already. */
        public boolean ok() {
            return code == 0 || code == 1;
        }

        /** Why it did not load, in words. */
        public String problem() {
            return describe(code, answer);
        }
    }

    /**
     * Loads one library and makes it a pipeline: gSystem->Load, its manifest
     * read back, its entry point declared. Whoever loads -- the startup pass,
     * the builder's Load, :root pipeline load -- goes through here, so each
     * says the same thing about a build the engine did not take.
     */
    public static Loaded loadOne(RootBackend backend, Path layer, Path library) {
        engineIs(backend);
        final Path earlier = earlierNamesake(library);
        final String answer = ask(backend, "gSystem->Load(\"" + forCling(library) + "\")");
        final int code = parseCode(answer);
        if (code == 0) {
            noteLoaded(library);
        } else if (code == 1 && earlier == null && !LOADED_BUILD.containsKey(key(library))) {
            // Loaded by someone else first (a macro, an earlier Sphere): the
            // build on disk is the best guess of what the engine holds. With a
            // namesake read before, "already loaded" means that one, not this.
            noteLoaded(library);
        }
        if (code != 0 && code != 1) {
            return new Loaded(code, answer, null, false, null);
        }
        remember(backend, layer, library);
        return new Loaded(code, answer, pipeline(pipelineName(library)),
            code == 1 && stale(library), earlier);
    }

    private static String ask(RootBackend backend, String expression) {
        if (backend == null || !backend.isAvailable()) {
            return null;
        }
        try {
            return backend.executeClingAwait(expression, LOAD_TIMEOUT_MS);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* What is in a loaded library                                         */
    /* ------------------------------------------------------------------ */

    /**
     * The pipeline name a library file carries: libmyCalc.so is myCalc.
     *
     * The compiler adds the lib prefix on the platforms that expect one, so it
     * comes off again here rather than being carried around everywhere else.
     */
    public static String pipelineName(Path library) {
        if (library == null) return "";
        String file = library.getFileName().toString();
        final int dot = file.indexOf(".so");
        int cut = dot >= 0 ? dot : file.lastIndexOf('.');
        if (cut > 0) {
            file = file.substring(0, cut);
        }
        if (file.startsWith("lib") && file.length() > 3) {
            file = file.substring(3);
        }
        return file;
    }

    /** The source a library was built from, when it is still beside it. */
    private static Path sourceBeside(Path library) {
        if (library == null || library.getParent() == null) return null;
        final String name = pipelineName(library);
        for (String extension : new String[]{".cpp", ".cxx", ".cc", ".c"}) {
            Path candidate = library.getParent().resolve(name + extension);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Records what a freshly loaded library is, asking it what it holds.
     *
     * The source beside it is read first because it costs nothing and works
     * before the engine is asked anything. A library that arrived without its
     * source is asked directly, through the symbol it exports under its own
     * name. A library that answers neither is still recorded, so a listing
     * shows everything that is loaded rather than only what declares itself.
     */
    private static void remember(RootBackend backend, Path layer, Path library) {
        final String name = pipelineName(library);
        if (name.isEmpty()) return;

        Path source = sourceBeside(library);
        // A build the engine did not take: what runs is the old one, which
        // the library in memory describes, not the source that changed since.
        RootPipelineManifest manifest = stale(library) ? null : RootPipelineManifest.fromSource(source);
        if (manifest == null) {
            manifest = askLibrary(backend, library, name);
        }
        if (manifest == null) {
            manifest = RootPipelineManifest.fromSource(source);
        }
        Pipeline one = new Pipeline(name, manifest, library, source, layer);
        REGISTRY.put(name, one);
        declare(backend, one);

        final java.util.function.Consumer<Pipeline> hook = announce;
        if (hook != null) {
            try {
                hook.accept(one);
            } catch (RuntimeException refused) {
                AppLogger.warn("Could not publish " + name + ": " + refused.getMessage());
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Making a loaded library callable by name                            */
    /* ------------------------------------------------------------------ */

    /**
     * Tells the interpreter what a loaded library defines.
     *
     * gSystem->Load maps the library and stops there. Cling compiles every
     * line it is given and refuses a name it has never seen declared, before
     * the library is even looked at: without this step mt(40, 25, 1.2) at the
     * prompt, a published :root mt, :root pipeline tf and an RDataFrame
     * Define("v", "mt(pt, met, dphi)") all end on "use of undeclared
     * identifier", although the library is loaded and its symbol is in it.
     *
     * A source edited since the build may no longer say what the library
     * holds, so the library's own manifest is the one declared then.
     */
    private static void declare(RootBackend backend, Pipeline one) {
        RootPipelineManifest compiled = one.manifest();
        if (one.source() != null && RootUserCompiler.needsBuilding(one.source()) && !stale(one.library())) {
            final RootPipelineManifest own = askLibrary(backend, one.library(), one.name());
            if (own != null) {
                compiled = own;
            }
        }
        String text = null;
        if (one.source() != null && (compiled == null || compiled.kind == RootPipelineManifest.Kind.LIBRARY)) {
            // A library that comes with its own header says what it offers
            // there, default values and all; declaring its functions a second
            // time could clash with that header when a macro includes it.
            // Only a header that does not compile on its own falls back.
            final Path header = headerBeside(one.source());
            if (header != null && !refused(ask(backend, declareExpression(includeOf(header))))) {
                return;
            }
            try {
                text = Files.readString(one.source());
            } catch (IOException | RuntimeException unreadable) {
                text = null;
            }
        }
        final List<String> blocks = declarations(compiled, text);
        for (int k = 0; k < blocks.size(); k++) {
            final String answer = ask(backend, declareExpression(blocks.get(k)));
            if (!refused(answer) || compiled == null || compiled.kind == RootPipelineManifest.Kind.LIBRARY) {
                continue;
            }
            if (k == 0) {
                AppLogger.warn(one.name() + " is loaded, but the interpreter refused " + compiled.signature()
                    + ": it cannot be called by name until the engine restarts"
                    + " (a loaded pipeline whose signature changed keeps the old one).");
            } else {
                // The entry point is there; one of the extras is not.
                AppLogger.warn(one.name() + " is loaded, but the interpreter refused one of its extras"
                    + " (per object, uncertainty or report): " + firstLine(answer));
            }
        }
    }

    /**
     * What has to be declared before a library can be called by name, one
     * block per declaration so that one refused cannot take the others with it.
     *
     * The entry point is the manifest's signature, the very line the form
     * writes into the source, so it names the symbol the library exports. A
     * plain library has no entry point: its source is read for the functions
     * it defines, each declared as written.
     *
     * @param source the library's source text, read only for a plain library; may be null
     */
    public static List<String> declarations(RootPipelineManifest manifest, String source) {
        final List<String> out = new ArrayList<>();
        if (manifest != null && manifest.kind != RootPipelineManifest.Kind.LIBRARY) {
            final String entry = manifest.signature() + ";";
            out.add((manifest.kind == RootPipelineManifest.Kind.HISTOGRAM
                     ? "#include <TTree.h>\n#include <TH1.h>\n" : "") + headersFor(entry) + entry);
            // After the entry point, which the per-object and uncertainty
            // templates call and must find declared.
            out.addAll(RootPipelineExtras.declarations(manifest));
            return out;
        }
        if (source == null) {
            return out;
        }
        for (String one : RootPipelineStarts.callableDeclarations(source)) {
            out.add(headersFor(one) + one);
        }
        return out;
    }

    /**
     * The headers a declaration's types need, and only those: ROOT's and the
     * standard library's, which the interpreter always finds. A ROOT class
     * (TH1D, TLorentzVector...) needs none, ROOT loads it when it is named;
     * the library's own includes are not repeated, since one the interpreter
     * cannot find (LHAPDF, a header beside a macro) would refuse them all.
     */
    private static String headersFor(String declaration) {
        final StringBuilder out = new StringBuilder();
        if (declaration.contains("RVec")) {
            out.append("#include <ROOT/RVec.hxx>\n");
        }
        if (declaration.matches("(?s).*\\b(?:PtEtaPhiMVector|PtEtaPhiEVector|PxPyPzEVector|PxPyPzMVector|XYZTVector)\\b.*")) {
            out.append("#include <Math/Vector4D.h>\n");
        }
        if (declaration.matches("(?s).*\\b(?:XYZVector|XYZPoint)\\b.*")) {
            out.append("#include <Math/Vector3D.h>\n");
        }
        if (declaration.matches("(?s).*\\bstring(?:_view)?\\b.*")) {
            out.append("#include <string>\n#include <string_view>\n");
        }
        if (declaration.contains("vector<")) {
            out.append("#include <vector>\n");
        }
        if (declaration.contains("array<")) {
            out.append("#include <array>\n");
        }
        if (declaration.contains("pair<")) {
            out.append("#include <utility>\n");
        }
        return out.toString();
    }

    /** The block that declares a library through its own header. */
    public static String includeOf(Path header) {
        return "#include \"" + forCling(header) + "\"";
    }

    /** mylib.h, .hh, .hpp or .hxx beside mylib.cpp, or null. */
    public static Path headerBeside(Path source) {
        if (source == null || source.getParent() == null) {
            return null;
        }
        final String file = source.getFileName().toString();
        final int dot = file.lastIndexOf('.');
        final String stem = dot > 0 ? file.substring(0, dot) : file;
        for (String extension : new String[]{".h", ".hh", ".hpp", ".hxx"}) {
            final Path candidate = source.getParent().resolve(stem + extension);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** A declaration as the expression the engine evaluates. */
    public static String declareExpression(String block) {
        return "gInterpreter->Declare(\"" + block.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n") + "\")";
    }

    /** True when the engine answered that a Declare failed; unknown answers are not counted. */
    private static boolean refused(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        final String[] lines = answer.strip().split("\\R");
        final String last = lines[lines.length - 1].strip();
        return last.startsWith("ERROR") || last.matches("(?i)(?:\\(bool\\)\\s*)?(?:false|0)");
    }

    /**
     * The manifest the library itself hands back, or null when it exports none.
     *
     * The symbol carries the pipeline's name, so the lookup cannot land on a
     * neighbor that happens to export a manifest too.
     */
    private static RootPipelineManifest askLibrary(RootBackend backend, Path library, String name) {
        final String expression =
            "[]{ Func_t raw = gSystem->DynFindSymbol(\"" + forCling(library) + "\", \""
          + RootPipelineManifest.exportName(name) + "\");"
          + " if (raw == nullptr) { return std::string(); }"
          + " const char *text = ((const char *(*)()) raw)();"
          + " return text == nullptr ? std::string() : std::string(text); }()";

        final String answer = ask(backend, expression);
        if (answer == null) return null;
        for (String line : answer.strip().split("\\R")) {
            RootPipelineManifest manifest = RootPipelineManifest.decode(line.strip());
            if (manifest != null && !manifest.name.isBlank()) {
                return manifest;
            }
        }
        return null;
    }

    /** Everything loaded right now, in load order. */
    public static List<Pipeline> pipelines() {
        synchronized (REGISTRY) {
            return new ArrayList<>(REGISTRY.values());
        }
    }

    /** One loaded pipeline by name, or null. */
    public static Pipeline pipeline(String name) {
        if (name == null) return null;
        synchronized (REGISTRY) {
            Pipeline exact = REGISTRY.get(name.trim());
            if (exact != null) return exact;
            for (Pipeline one : REGISTRY.values()) {
                if (one.name().equalsIgnoreCase(name.trim())) return one;
            }
        }
        return null;
    }

    /** Drops a pipeline from the registry, after its library has been unloaded. */
    public static Pipeline forget(String name) {
        Pipeline gone = pipeline(name);
        if (gone != null) {
            REGISTRY.remove(gone.name());
        }
        return gone;
    }

    /**
     * gSystem->Load answers 0 loaded, 1 already there, negative on refusal.
     *
     * The engine sends what the expression printed and then its value, in that
     * order, so the code is on the LAST line. A failing Load prints
     * "Error in <TCling::Load>: ..." first, and reading from the left would pick
     * a digit out of that message instead of the return code.
     */
    private static int parseCode(String answer) {
        if (answer == null) return NO_ANSWER;
        String text = answer.strip();
        if (text.isEmpty()) return NO_ANSWER;
        if (text.startsWith("ERROR:")) return REFUSED;

        String[] lines = text.split("\\R");
        String last = "";
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) { last = lines[i].strip(); break; }
        }
        if (last.equals("OK")) return NO_VALUE;

        try {
            return Integer.parseInt(last);
        } catch (NumberFormatException notBare) {
            // ROOT sometimes prefixes the type, as in "(int) -1"
            java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(-?\\d+)\\s*$").matcher(last);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); }
                catch (NumberFormatException bad) { return NO_ANSWER; }
            }
            return NO_ANSWER;
        }
    }

    private static final int NO_ANSWER = -101;
    private static final int NO_VALUE = -102;
    private static final int REFUSED = -103;

    /** The message the engine sent, when it refused outright. */
    private static String firstLine(String answer) {
        if (answer == null) return "";
        for (String line : answer.strip().split("\\R")) {
            if (!line.isBlank()) return line.strip();
        }
        return "";
    }

    private static String describe(int code, String answer) {
        return switch (code) {
            case -1 -> "the library or one of its dependencies is missing";
            case -2 -> "built against a different ROOT version";
            case -3 -> "already loaded from another path";
            case NO_ANSWER -> answer == null || answer.isBlank()
                    ? "the engine did not answer"
                    : "unexpected answer: " + firstLine(answer);
            case NO_VALUE -> "the engine answered without a return code";
            case REFUSED -> firstLine(answer).replaceFirst("^ERROR:\\s*", "");
            default -> "code " + code;
        };
    }

    /** Cling reads its own string literals, so separators must be forward slashes. */
    public static String forCling(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "/");
    }
}
