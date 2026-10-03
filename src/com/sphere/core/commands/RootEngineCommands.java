package com.sphere.core.commands;

import com.sphere.utils.AppLogger;

/**
 * The engine itself, the interpreter session and the build pipeline.
 *
 * Split out of Handlers, which had grown to hold every backend at once. The
 * helpers these handlers share -- the argument readers, the interpreter call,
 * the named-handle builders -- stay in Handlers and are called through it.
 */
public final class RootEngineCommands {

    private RootEngineCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static void rootAnalyze(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root analyze");
        if (a.isEmpty()) {
            Handlers.usage(":root analyze <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gROOT->FindObject(\"" + a0 + "\")->Print()");
    }

    public static void rootBenchmark(String i, CommandExecutionContext c) {
        final com.sphere.core.rootbackend.RootBackend b = Handlers.backend(c);
        if (b == null) {
            return;
        }
        int rounds = Handlers.asInt(Handlers.head(Handlers.args(i, ":root benchmark")), 200);
        rounds = Math.max(1, Math.min(rounds, 20000));

        final long[] taken = new long[rounds];
        int answered = 0;
        for (int n = 0; n < rounds; n++) {
            final long start = System.nanoTime();
            if (b.sendAwait(com.sphere.core.rootbackend.RootBackend.CMD_PING,
                            0, null, Handlers.TIMEOUT_MS) == null) {
                break;
            }
            taken[answered++] = System.nanoTime() - start;
        }
        if (answered == 0) {
            AppLogger.error("The engine answered no ping.");
            return;
        }
        final long[] sorted = java.util.Arrays.copyOf(taken, answered);
        java.util.Arrays.sort(sorted);
        long total = 0L;
        for (long one : sorted) {
            total += one;
        }
        AppLogger.result("round trips " + answered
            + "   median " + (sorted[answered / 2] / 1000L) + " us"
            + "   mean " + (total / answered / 1000L) + " us"
            + "   p99 " + (sorted[Math.min(answered - 1, (answered * 99) / 100)] / 1000L) + " us"
            + "   worst " + (sorted[answered - 1] / 1000L) + " us");
    }

    public static void rootBindDrop(String i, CommandExecutionContext c) {
        final String name = Handlers.head(Handlers.args(i, ":root bind drop"));
        if (name.isEmpty()) {
            Handlers.usage(":root bind drop <name>");
            return;
        }
        Handlers.cling(c, "SphereBridge::HandleDrop(\"" + name + "\")");
    }

    public static void rootBindList(String i, CommandExecutionContext c) {
        Handlers.cling(c, "SphereBridge::HandleList()");
    }

    /** Builds both folders in order: the libraries first, then the macros. */
    public static void rootBuildAll(String i, CommandExecutionContext c) {
        AppLogger.info("Building includes/ then user_scripts/.");
        rootIncludesBuild(":root includes build --all", c);
        // The macros come after, so ACLiC already has the libraries it may need.
        rootScriptCompileAll(":root script compile --all", c);
    }

    public static void rootCd(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root cd");
        if (a.isEmpty()) {
            Handlers.usage(":root cd <path>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gDirectory->cd(\"" + a0 + "\")");
    }

    public static void rootCompileIncludes(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root includes compile");
        if (a.isEmpty()) {
            Handlers.usage(":root includes compile <dir>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gSystem->AddIncludePath(\"-I" + a0 + "\")");
    }

    public static void rootCompileScripts(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root script compile");
        if (a.trim().equals("--all")) {
            rootScriptCompileAll(i, c);
            return;
        }
        if (a.isEmpty()) {
            Handlers.usage(":root script compile <file> | --all");
            return;
        }
        String a0 = Handlers.macroPath(a, c);
        if (a0 == null) return;
        Handlers.cling(c, "gROOT->LoadMacro(\"" + a0 + "+\")");
    }

    public static void rootConfig(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gSystem->GetMakeSharedLib()");
    }

    public static void rootDebugAudit(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gROOT->GetListOfFiles()->Print()");
    }

    public static void rootDebugDump(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gROOT->GetListOfSpecials()->Print()");
    }

    public static void rootDebugGraphviz(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gROOT->GetListOfClasses()->Print()");
    }

    public static void rootDebugLevel(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root debug level");
        if (a.isEmpty()) {
            Handlers.usage(":root debug level <0-5>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gDebug=" + a0 + "");
    }

    public static void rootDescribeObject(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root obj describe");
        if (a.isEmpty()) {
            Handlers.usage(":root obj describe <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gROOT->FindObject(\"" + a0 + "\")->IsA()->Print()");
    }

    public static void rootDiag(String i, CommandExecutionContext c) {
        Handlers.metrics(c, "text");
    }

    public static void rootDump(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gROOT->GetListOfSpecials()->Print()");
    }

    public static void rootDumpObject(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root obj dump");
        if (a.isEmpty()) {
            Handlers.usage(":root obj dump <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gROOT->FindObject(\"" + a0 + "\")->Dump()");
    }

    public static void rootGc(String i, CommandExecutionContext c) {
        Handlers.metrics(c, "heap");
    }

    public static void rootGetEnv(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root getenv");
        if (a.isEmpty()) {
            Handlers.usage(":root getenv <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gSystem->Getenv(\"" + a0 + "\")");
    }

    public static void rootGetObject(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root obj get");
        if (a.isEmpty()) {
            Handlers.usage(":root obj get <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gROOT->FindObject(\"" + a0 + "\")->ClassName()");
    }

    /**
     * Builds the C++ of includes/ into shared libraries. One named source, or
     * every source with --all, and only what changed unless --force is given.
     */
    public static void rootIncludesBuild(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root includes build").trim();
        String project = c != null && c.ctx != null ? c.ctx.getActiveProject() : null;

        boolean all = a.contains("--all");
        boolean force = a.contains("--force");
        String named = a.replace("--all", "").replace("--force", "").trim();

        if (!all && named.isEmpty()) {
            AppLogger.raw("Usage: :root includes build <file.cpp> | --all [--force]");
            AppLogger.raw("  --all      build every source of includes/, both layers");
            AppLogger.raw("  --force    build even when the library is already newer");
            return;
        }

        java.util.List<java.nio.file.Path> queue = new java.util.ArrayList<>();
        if (all) {
            for (java.nio.file.Path root
                    : com.sphere.core.rootbackend.RootUserPipeline.layers(project)) {
                for (java.nio.file.Path source
                        : com.sphere.core.rootbackend.RootUserPipeline.sources(root)) {
                    if (force
                        || com.sphere.core.rootbackend.RootUserCompiler.needsBuilding(source)) {
                        queue.add(source);
                    }
                }
            }
            if (queue.isEmpty()) {
                AppLogger.result("Nothing to build. Everything in includes/ is up to date.");
                return;
            }
        } else {
            java.nio.file.Path source = Handlers.findSource(named, project);
            if (source == null) {
                AppLogger.error("No source named " + named + " in includes/.");
                return;
            }
            queue.add(source);
        }

        Handlers.runBuilds(queue, project, c);
    }

    /** The libraries of both layers, and the C++ sources waiting to be built. */
    public static void rootIncludesList(String i, CommandExecutionContext c) {
        String project = c != null && c.ctx != null ? c.ctx.getActiveProject() : null;
        int libraries = 0, sources = 0;
        for (java.nio.file.Path root
                : com.sphere.core.rootbackend.RootUserPipeline.layers(project)) {
            java.util.List<java.nio.file.Path> found =
                com.sphere.core.rootbackend.RootUserPipeline.libraries(root);
            java.util.List<java.nio.file.Path> toBuild =
                com.sphere.core.rootbackend.RootUserPipeline.sources(root);
            if (found.isEmpty() && toBuild.isEmpty()) continue;
            AppLogger.raw("  " + root.resolve(
                com.sphere.core.rootbackend.RootUserPipeline.INCLUDES_DIR));
            for (java.nio.file.Path library : found) {
                AppLogger.raw(String.format("      %-32s %s  loaded at startup",
                    library.getFileName(), Handlers.humanBytes(Handlers.sizeOf(library))));
                libraries++;
            }
            for (java.nio.file.Path source : toBuild) {
                AppLogger.raw(String.format("      %-32s %s  source, :root includes build",
                    source.getFileName(), Handlers.humanBytes(Handlers.sizeOf(source))));
                sources++;
            }
        }
        if (libraries + sources == 0) {
            AppLogger.result("Nothing in includes/ yet. A .so there is loaded at startup.");
        }

        com.sphere.core.rootbackend.RootUserCompiler compiler =
            new com.sphere.core.rootbackend.RootUserCompiler(
                new com.sphere.utils.SettingsManager(), Handlers.backend(c));
        AppLogger.raw("  compiler    " + String.valueOf(compiler.compiler()));
        AppLogger.raw("  flags from  " + compiler.flagSource());
        String rootCompiler = compiler.rootBuildCompiler();
        if (rootCompiler != null) {
            AppLogger.raw("  ROOT built with " + rootCompiler);
        }
    }

    /** Loads again what is in includes/, for a library rebuilt while Sphere runs. */
    public static void rootIncludesReload(String i, CommandExecutionContext c) {
        com.sphere.core.rootbackend.RootBackend b = Handlers.backend(c);
        if (b == null) {
            AppLogger.error("The ROOT engine is not running.");
            return;
        }
        String project = c != null && c.ctx != null ? c.ctx.getActiveProject() : null;
        com.sphere.core.rootbackend.RootBackend.setActivePipelineProject(project);
        var outcome = com.sphere.core.rootbackend.RootUserPipeline.loadInto(b, project);
        if (outcome.total() == 0) {
            AppLogger.result("Nothing to load in includes/.");
        }
        for (String problem : outcome.problems()) {
            AppLogger.raw("      " + problem);
        }
    }

    public static void rootInfo(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gROOT->GetVersion()");
    }

    public static void rootListHandles(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gROOT->GetListOfFiles()->Print()");
    }

    public static void rootLoadIncludes(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root includes load");
        if (a.isEmpty()) {
            Handlers.usage(":root includes load <dir>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gROOT->ProcessLine(\".I " + a0 + "\")");
    }

    public static void rootLoadScript(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root script load");
        if (a.isEmpty()) {
            Handlers.usage(":root script load <file>");
            return;
        }
        String a0 = Handlers.macroPath(a, c);
        if (a0 == null) return;
        Handlers.cling(c, "gROOT->LoadMacro(\"" + a0 + "\")");
    }

    public static void rootMkdir(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root mkdir");
        if (a.isEmpty()) {
            Handlers.usage(":root mkdir <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gDirectory->mkdir(\"" + a0 + "\")");
    }

    public static void rootMtOff(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root mt off");
        final String[] w = Handlers.words(a);
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_SYS_THREADS,
             0, "off");
    }

    public static void rootMtOn(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root mt on");
        final String[] w = Handlers.words(a);
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_SYS_THREADS,
             0, "on " + Handlers.head(a));
    }

    public static void rootMtStatus(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root mt status");
        final String[] w = Handlers.words(a);
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_SYS_THREADS,
             0, "status");
    }

    public static void rootObjClass(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root obj class");
        if (a.isEmpty()) {
            Handlers.usage(":root obj class <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gROOT->FindObject(\"" + a0 + "\")->ClassName()");
    }

    public static void rootObjClone(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root obj clone");
        if (a.isEmpty()) {
            Handlers.usage(":root obj clone <name> <new>");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "gROOT->FindObject(\"" + a0 + "\")->Clone(\"" + a1 + "\")");
    }

    public static void rootObjDelete(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root obj delete");
        if (a.isEmpty()) {
            Handlers.usage(":root obj delete <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gROOT->FindObject(\"" + a0 + "\")->Delete()");
    }

    public static void rootObjInspect(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root obj inspect");
        if (a.isEmpty()) {
            Handlers.usage(":root obj inspect <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gROOT->FindObject(\"" + a0 + "\")->Inspect()");
    }

    public static void rootObjList(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gROOT->GetListOfSpecials()->Print()");
    }

    public static void rootObjMembers(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root obj members");
        if (a.isEmpty()) {
            Handlers.usage(":root obj members <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gROOT->FindObject(\"" + a0 + "\")->IsA()->GetListOfDataMembers()->Print()");
    }

    public static void rootObjMethods(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root obj methods");
        if (a.isEmpty()) {
            Handlers.usage(":root obj methods <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gROOT->FindObject(\"" + a0 + "\")->IsA()->GetListOfMethods()->Print()");
    }

    public static void rootObjPrint(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root obj print");
        if (a.isEmpty()) {
            Handlers.usage(":root obj print <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gROOT->FindObject(\"" + a0 + "\")->Print()");
    }

    public static void rootObjType(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root obj type");
        if (a.isEmpty()) {
            Handlers.usage(":root obj type <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gROOT->FindObject(\"" + a0 + "\")->IsA()->GetName()");
    }

    public static void rootObjWrite(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root obj write");
        if (a.isEmpty()) {
            Handlers.usage(":root obj write <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gROOT->FindObject(\"" + a0 + "\")->Write()");
    }

    public static void rootPing(String i, CommandExecutionContext c) {
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_PING, 0, null);
    }

    public static void rootProfile(String i, CommandExecutionContext c) {
        Handlers.metrics(c, "text");
    }

    public static void rootProfileJson(String i, CommandExecutionContext c) {
        Handlers.metrics(c, "json");
    }

    public static void rootProfileLevel(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root profile level");
        if (a.isEmpty()) {
            Handlers.usage(":root profile level <n>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gDebug=" + a0 + "");
    }

    public static void rootProfileReset(String i, CommandExecutionContext c) {
        Handlers.metrics(c, "reset");
    }

    public static void rootProfileStats(String i, CommandExecutionContext c) {
        Handlers.metrics(c, "text");
    }

    public static void rootProfileThreshold(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root profile threshold");
        if (a.isEmpty()) {
            Handlers.usage(":root profile threshold <ms>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gDebug=" + a0 + "");
    }

    public static void rootProfilingJson(String i, CommandExecutionContext c) {
        Handlers.metrics(c, "json");
    }

    public static void rootProfilingLevel(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root profiling level");
        if (a.isEmpty()) {
            Handlers.usage(":root profiling level <n>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gDebug=" + a0 + "");
    }

    public static void rootProfilingReset(String i, CommandExecutionContext c) {
        Handlers.metrics(c, "reset");
    }

    public static void rootProfilingStatus(String i, CommandExecutionContext c) {
        Handlers.metrics(c, "text");
    }

    /* ------------------------------------------------------------------ */
    /* The user's own pipelines                                            */
    /* ------------------------------------------------------------------ */

    /** Loading a library can take far longer than a round trip to the engine. */
    private static final long LOAD_TIMEOUT_MS = 20_000L;

    /** Opens the window where a pipeline is written, built and loaded. */
    public static void rootPersonalPipeline(String i, CommandExecutionContext c) {
        final String project = activeProject(c);
        final String name = Handlers.args(i, ":root personal pipeline").trim();
        com.sphere.core.rootbackend.RootPipelineWindow.show(
            Handlers.backend(c), project, name.isEmpty() ? null : name);
        AppLogger.result("The pipeline builder is open."
            + (name.isEmpty() ? "" : " Showing " + name + "."));
    }

    /**
     * Everything that is a pipeline: loaded in the engine, or waiting on disk.
     *
     * A library is listed as loaded only once it has said what is in it, which
     * is the difference between a shared object in a folder and a pipeline.
     */
    public static void rootPipelineList(String i, CommandExecutionContext c) {
        final java.util.List<com.sphere.core.rootbackend.RootUserPipeline.Pipeline> live =
            com.sphere.core.rootbackend.RootUserPipeline.pipelines();

        if (!live.isEmpty()) {
            AppLogger.raw("  loaded in the engine");
            for (com.sphere.core.rootbackend.RootUserPipeline.Pipeline one : live) {
                AppLogger.raw(String.format("      %-20s %s", one.name(), one.summary()));
            }
        }

        int waiting = 0;
        for (java.nio.file.Path root
                : com.sphere.core.rootbackend.RootUserPipeline.layers(activeProject(c))) {
            for (java.nio.file.Path source
                    : com.sphere.core.rootbackend.RootUserPipeline.sources(root)) {
                var manifest =
                    com.sphere.core.rootbackend.RootPipelineManifest.fromSource(source);
                if (manifest == null
                    || com.sphere.core.rootbackend.RootUserPipeline
                           .pipeline(manifest.name) != null) {
                    continue;
                }
                if (waiting == 0) {
                    AppLogger.raw("  written but not loaded");
                }
                waiting++;
                AppLogger.raw(String.format("      %-20s %s   %s", manifest.name,
                    manifest.signature(),
                    com.sphere.core.rootbackend.RootUserCompiler.needsBuilding(source)
                        ? "needs building" : "built, not loaded"));
            }
        }

        if (live.isEmpty() && waiting == 0) {
            AppLogger.result("No pipeline yet. :root personal pipeline opens the builder.");
        }
    }

    /** Opens the builder on a new pipeline, with the name already in the form. */
    public static void rootPipelineNew(String i, CommandExecutionContext c) {
        final String name = Handlers.head(Handlers.args(i, ":root pipeline new"));
        if (name.isEmpty()) {
            Handlers.usage(":root pipeline new <name>");
            return;
        }
        com.sphere.core.rootbackend.RootPipelineWindow.show(
            Handlers.backend(c), activeProject(c), name);
    }

    /** Builds one pipeline with the flags its own manifest asks for. */
    public static void rootPipelineBuild(String i, CommandExecutionContext c) {
        final String name = Handlers.head(Handlers.args(i, ":root pipeline build"));
        if (name.isEmpty()) {
            Handlers.usage(":root pipeline build <name>");
            return;
        }
        java.nio.file.Path source = pipelineSource(name, activeProject(c));
        if (source == null) {
            AppLogger.error("No pipeline named " + name + " in includes/.");
            return;
        }
        Handlers.runBuilds(new java.util.ArrayList<>(java.util.List.of(source)),
                           activeProject(c), c);
    }

    /** Gives one built library to the running engine and reads back its manifest. */
    public static void rootPipelineLoad(String i, CommandExecutionContext c) {
        final String name = Handlers.head(Handlers.args(i, ":root pipeline load"));
        if (name.isEmpty()) {
            Handlers.usage(":root pipeline load <name>");
            return;
        }
        final com.sphere.core.rootbackend.RootBackend b = Handlers.backend(c);
        if (b == null || !b.isAvailable()) {
            AppLogger.error("The ROOT engine is not running.");
            return;
        }
        java.nio.file.Path source = pipelineSource(name, activeProject(c));
        if (source == null) {
            AppLogger.error("No pipeline named " + name + " in includes/.");
            return;
        }
        java.nio.file.Path library =
            com.sphere.core.rootbackend.RootUserCompiler.libraryFor(source);
        if (!java.nio.file.Files.isRegularFile(library)) {
            AppLogger.error(name + " has not been built yet. :root pipeline build " + name);
            return;
        }
        final String answer = b.executeClingAwait("gSystem->Load(\""
            + com.sphere.core.rootbackend.RootUserPipeline.forCling(library) + "\")",
            Handlers.TIMEOUT_MS);
        if (answer == null || answer.startsWith("ERROR")) {
            AppLogger.error("The engine refused " + library.getFileName()
                + (answer == null ? "." : ": " + answer));
            return;
        }
        var loaded = com.sphere.core.rootbackend.RootUserPipeline.refresh(
            b, source.getParent() == null ? null : source.getParent().getParent(), library);
        if (loaded == null || loaded.isSilent()) {
            AppLogger.warn(library.getFileName()
                + " is loaded but says nothing about itself. Rebuild it from the builder.");
            return;
        }
        AppLogger.success(loaded.name() + " is loaded:  " + loaded.summary());
    }

    /** Takes a pipeline back out of the engine. */
    public static void rootPipelineDrop(String i, CommandExecutionContext c) {
        final String name = Handlers.head(Handlers.args(i, ":root pipeline drop"));
        if (name.isEmpty()) {
            Handlers.usage(":root pipeline drop <name>");
            return;
        }
        var gone = com.sphere.core.rootbackend.RootUserPipeline.pipeline(name);
        if (gone == null) {
            AppLogger.error("No pipeline named " + name + " is loaded.");
            return;
        }
        final com.sphere.core.rootbackend.RootBackend b = Handlers.backend(c);
        if (b != null && b.isAvailable()) {
            b.executeClingAwait("gSystem->Unload(\""
                + com.sphere.core.rootbackend.RootUserPipeline.forCling(gone.library())
                + "\")", Handlers.TIMEOUT_MS);
        }
        com.sphere.core.rootbackend.RootUserPipeline.forget(name);
        final String published = ":root " + gone.name();
        if (PUBLISHED.remove(published)) {
            CommandDefinitions.unregister(published);
        }
        AppLogger.success(gone.name() + " is no longer loaded.");
    }

    /**
     * Calls a loaded pipeline's entry point.
     *
     * The arguments are handed over as the user wrote them, so a number stays a
     * number and a string keeps its quotes. What comes back is whatever the
     * pipeline returns.
     */
    public static void rootPipelineRun(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pipeline run"));
        if (w.length < 1) {
            Handlers.usage(":root pipeline run <name> [args...]");
            return;
        }
        call(w[0], Handlers.join(w, 1), c);
    }

    /**
     * Makes a loaded pipeline a command of its own, when its form asked for it.
     *
     * This is what the option in the builder means: the pipeline is not only
     * loaded, it is listed in :help root and callable by its own name. A name
     * that is already a command is left alone, so a pipeline can never take a
     * ROOT command's place.
     */
    static void publish(com.sphere.core.rootbackend.RootUserPipeline.Pipeline one) {
        final var manifest = one.manifest();
        if (manifest == null || !manifest.showInHelp
                || manifest.kind
                   == com.sphere.core.rootbackend.RootPipelineManifest.Kind.LIBRARY) {
            return;
        }
        final String name = ":root " + one.name();
        // A name that is already a command stays as it is, unless it is one we
        // published before: a pipeline rebuilt with a new form must not keep
        // describing itself the old way.
        if (CommandDefinitions.find(name) != null && !PUBLISHED.contains(name)) {
            return;
        }
        CommandDefinitions.unregister(name);
        final StringBuilder usage = new StringBuilder(name);
        if (manifest.kind
                == com.sphere.core.rootbackend.RootPipelineManifest.Kind.HISTOGRAM) {
            usage.append(" <tree>");
        }
        for (var input : manifest.inputs) {
            usage.append(" <").append(input.name()).append('>');
        }
        final String about =
            (manifest.description == null || manifest.description.isBlank()
                ? manifest.signature() : manifest.description)
            + ". Usage: " + usage;
        CommandDefinitions.register(name, "Your own pipeline. " + about,
            (input, context) -> call(one.name(),
                Handlers.join(Handlers.words(Handlers.args(input, name)), 0), context));
        PUBLISHED.add(name);
    }

    /** The commands published for a pipeline, so they can be taken back. */
    private static final java.util.Set<String> PUBLISHED =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Calls one loaded pipeline's entry point with the arguments as written. */
    private static void call(String wanted, String rest, CommandExecutionContext c) {
        final String[] w = Handlers.words(rest == null ? "" : rest);
        var one = com.sphere.core.rootbackend.RootUserPipeline.pipeline(wanted);
        if (one == null) {
            AppLogger.error("No pipeline named " + wanted
                + " is loaded. :root pipeline load " + wanted);
            return;
        }
        final var manifest = one.manifest();
        if (manifest != null
                && manifest.kind
                   == com.sphere.core.rootbackend.RootPipelineManifest.Kind.LIBRARY) {
            AppLogger.error(one.name()
                + " is a plain library and has no entry point. Call its functions with :root run.");
            return;
        }

        // A histogram is handed the tree it reads, which the entry point takes
        // first and the manifest does not carry as an input of its own.
        final boolean takesTree = manifest != null
            && manifest.kind == com.sphere.core.rootbackend.RootPipelineManifest.Kind.HISTOGRAM;
        final int expected = manifest == null ? w.length
                                              : manifest.inputs.size() + (takesTree ? 1 : 0);
        if (w.length != expected) {
            AppLogger.error(one.name() + " takes " + expected
                + (expected == 1 ? " argument:  " : " arguments:  ")
                + (takesTree ? "a tree, then " : "") + one.summary());
            return;
        }

        final String arguments = takesTree
            ? Handlers.obj("TTree", w[0])
              + (w.length > 1 ? ", " + Handlers.csv(Handlers.join(w, 1)) : "")
            : Handlers.csv(Handlers.join(w, 0));
        Handlers.cling(c, one.name() + "(" + arguments + ")");
    }

    /** Opens a pipeline's source in the Sphere editor. */
    public static void rootPipelineEdit(String i, CommandExecutionContext c) {
        final String name = Handlers.head(Handlers.args(i, ":root pipeline edit"));
        if (name.isEmpty()) {
            Handlers.usage(":root pipeline edit <name>");
            return;
        }
        java.nio.file.Path source = pipelineSource(name, activeProject(c));
        if (source == null) {
            AppLogger.error("No pipeline named " + name + " in includes/.");
            return;
        }
        final java.nio.file.Path open = source;
        javax.swing.SwingUtilities.invokeLater(
            () -> com.sphere.ui.WindowManager.showFileInEditor(open.toFile()));
        AppLogger.result(source.getFileName() + " is open in the editor.");
    }

    /** What one pipeline declares about itself, in full. */
    public static void rootPipelineShow(String i, CommandExecutionContext c) {
        final String name = Handlers.head(Handlers.args(i, ":root pipeline show"));
        if (name.isEmpty()) {
            Handlers.usage(":root pipeline show <name>");
            return;
        }
        var one = com.sphere.core.rootbackend.RootUserPipeline.pipeline(name);
        var manifest = one != null ? one.manifest() : null;
        java.nio.file.Path source = one != null ? one.source()
                                                : pipelineSource(name, activeProject(c));
        if (manifest == null) {
            manifest = com.sphere.core.rootbackend.RootPipelineManifest.fromSource(source);
        }
        if (manifest == null) {
            AppLogger.error("Nothing named " + name + " declares itself as a pipeline.");
            return;
        }
        AppLogger.raw("  name        " + manifest.name);
        AppLogger.raw("  kind        " + manifest.kind.token + "   " + manifest.kind.explanation);
        AppLogger.raw("  entry       " + manifest.signature());
        AppLogger.raw("  symbol      " + manifest.exportName());
        if (manifest.description != null && !manifest.description.isBlank()) {
            AppLogger.raw("  about       " + manifest.description);
        }
        AppLogger.raw("  flags       " + String.join(" ", manifest.flags()));
        AppLogger.raw("  per entry   " + (manifest.threadSafe
            ? "keeps no state, so :root mt on may run it on several at once"
            : "keeps state, so it runs on one entry at a time"));
        AppLogger.raw("  source      " + (source == null ? "not on disk" : source.toString()));
        AppLogger.raw("  loaded      " + (one == null ? "no" : one.library().toString()));
    }

    /* ------------------------------------------------------------------ */
    /* Chains                                                              */
    /* ------------------------------------------------------------------ */

    /** The chains saved in user_scripts/, both layers. */
    public static void rootChainList(String i, CommandExecutionContext c) {
        int found = 0;
        for (java.nio.file.Path layer
                : com.sphere.core.rootbackend.RootUserPipeline.layers(activeProject(c))) {
            for (java.nio.file.Path one
                    : com.sphere.core.rootbackend.RootPipelineChain.chainsIn(layer)) {
                var chain = com.sphere.core.rootbackend.RootPipelineChain.read(one);
                if (chain == null) {
                    continue;
                }
                if (found == 0) {
                    AppLogger.raw("  chain                tree        stages   reads");
                }
                found++;
                AppLogger.raw(String.format("      %-16s %-11s %-8d %s",
                    chain.name, chain.tree, chain.stages.size(), chain.file));
            }
        }
        if (found == 0) {
            AppLogger.result("No chain yet. The Chain tab of :root personal pipeline builds one.");
        }
    }

    /** Everything one chain would do, both ways, without doing any of it. */
    public static void rootChainShow(String i, CommandExecutionContext c) {
        var chain = findChain(Handlers.head(Handlers.args(i, ":root chain show")),
                              activeProject(c));
        if (chain == null) {
            AppLogger.error("No chain of that name. :root chain list says which there are.");
            return;
        }
        AppLogger.raw("  reads       " + chain.tree + "  in  " + chain.file);
        AppLogger.raw("  on the engine");
        for (String one : chain.commands()) {
            AppLogger.raw("      " + one);
        }
        AppLogger.raw("  as a macro  " + chain.name + ".C, written by :root chain macro");
    }

    /** Replays a saved chain on the engine, command by command. */
    public static void rootChainRun(String i, CommandExecutionContext c) {
        final String name = Handlers.head(Handlers.args(i, ":root chain run"));
        var chain = findChain(name, activeProject(c));
        if (chain == null) {
            AppLogger.error("No chain named " + name + ".");
            return;
        }
        final String why = chain.validate();
        if (why != null) {
            AppLogger.error(why);
            return;
        }
        com.sphere.core.CommandRouter router = com.sphere.core.CommandRouter.active();
        if (router == null) {
            AppLogger.error("No command router is running to replay it.");
            return;
        }
        AppLogger.info("Replaying " + chain.name + ": "
            + chain.commands().size() + " commands.");
        for (String one : chain.commands()) {
            AppLogger.raw("  " + one);
            router.processInput(one);
        }
    }

    /** Writes the standalone macro a chain stands for. */
    public static void rootChainMacro(String i, CommandExecutionContext c) {
        final String name = Handlers.head(Handlers.args(i, ":root chain macro"));
        var chain = findChain(name, activeProject(c));
        if (chain == null) {
            AppLogger.error("No chain named " + name + ".");
            return;
        }
        java.nio.file.Path layer =
            com.sphere.core.rootbackend.RootUserPipeline.globalRoot();
        java.nio.file.Path target = layer
            .resolve(com.sphere.core.rootbackend.RootUserPipeline.SCRIPTS_DIR)
            .resolve(chain.name + ".C");
        try {
            java.nio.file.Files.writeString(target, chain.macro(),
                java.nio.charset.StandardCharsets.UTF_8);
            AppLogger.success("Written " + target);
            AppLogger.raw("  :root script run " + chain.name + ".C");
        } catch (java.io.IOException failure) {
            AppLogger.error("Could not write " + target + ": " + failure.getMessage());
        }
    }

    /** A chain by name, the project layer winning. */
    private static com.sphere.core.rootbackend.RootPipelineChain findChain(
            String name, String project) {
        if (name == null || name.isBlank()) {
            return null;
        }
        com.sphere.core.rootbackend.RootPipelineChain found = null;
        for (java.nio.file.Path layer
                : com.sphere.core.rootbackend.RootUserPipeline.layers(project)) {
            for (java.nio.file.Path one
                    : com.sphere.core.rootbackend.RootPipelineChain.chainsIn(layer)) {
                var chain = com.sphere.core.rootbackend.RootPipelineChain.read(one);
                if (chain != null && chain.name.equalsIgnoreCase(name.trim())) {
                    found = chain;
                }
            }
        }
        return found;
    }

    /**
     * Times a pipeline once per set of build flags.
     *
     * The answer decides whether -O3 and -march=native are worth what they cost
     * -- the second ties the library to one kind of processor -- so it is worth
     * asking rather than assuming.
     */
    public static void rootPipelineTime(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pipeline time"));
        if (w.length < 1) {
            Handlers.usage(":root pipeline time <name> [calls]");
            return;
        }
        java.nio.file.Path source = pipelineSource(w[0], activeProject(c));
        if (source == null) {
            AppLogger.error("No pipeline named " + w[0] + " in includes/.");
            return;
        }
        var manifest = com.sphere.core.rootbackend.RootPipelineManifest.fromSource(source);
        if (manifest == null) {
            AppLogger.error(w[0] + " does not declare itself as a pipeline.");
            return;
        }
        if (!com.sphere.core.rootbackend.RootPipelineTemplates.canBeTimed(manifest)) {
            AppLogger.error("Only a transform or a filter taking numbers can be timed.");
            return;
        }
        final long calls = w.length > 1 ? Handlers.asInt(w[1], 1000000) : 1000000L;
        AppLogger.info("Building " + manifest.name + " once per set of flags...");

        var compiler = new com.sphere.core.rootbackend.RootUserCompiler(
            new com.sphere.utils.SettingsManager(), Handlers.backend(c));
        var results = com.sphere.core.rootbackend.RootPipelineBench.compare(
            compiler, manifest, source, calls);

        AppLogger.raw(String.format("  %-26s %-14s %s", "flags", "per call", "gain"));
        for (var one : results) {
            AppLogger.raw(String.format("      %-24s %-14s %s",
                one.label().isEmpty() ? "--" : one.label(),
                one.succeeded() ? one.reading() : one.message(),
                com.sphere.core.rootbackend.RootPipelineBench.relativeTo(one, results)));
        }
        var best = com.sphere.core.rootbackend.RootPipelineBench.best(results);
        if (best != null) {
            AppLogger.success("Fastest: " + best.label() + " at " + best.reading() + " per call.");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Looking inside one                                                  */
    /* ------------------------------------------------------------------ */

    /** Calls a pipeline once and prints every value its code named. */
    public static void rootPipelineWatch(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pipeline watch"));
        if (w.length < 1) {
            Handlers.usage(":root pipeline watch <name> [args...]");
            return;
        }
        var ready = readyFor(w[0], activeProject(c));
        if (ready == null) {
            return;
        }
        final double[] arguments = new double[ready.manifest.inputs.size()];
        for (int n = 0; n < arguments.length; n++) {
            arguments[n] = n + 1 < w.length ? asDouble(w[n + 1]) : 1.0;
        }
        var values = com.sphere.core.rootbackend.RootPipelineProbe.watch(
            compilerFor(c), ready.manifest, ready.source, arguments);
        if (values.isEmpty()) {
            AppLogger.result("Nothing was recorded. Name a value with SPHERE_WATCH(x) "
                + "inside the body, then look again.");
            return;
        }
        for (var one : values) {
            AppLogger.raw(String.format("      %-24s %s", one.name(), one.value()));
        }
    }

    /** Sweeps the inputs and says how often the answer was not a number. */
    public static void rootPipelineCheck(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pipeline check"));
        if (w.length < 1) {
            Handlers.usage(":root pipeline check <name> [low] [high]");
            return;
        }
        var ready = readyFor(w[0], activeProject(c));
        if (ready == null) {
            return;
        }
        final double low = w.length > 1 ? asDouble(w[1]) : 0.0;
        final double high = w.length > 2 ? asDouble(w[2]) : 100.0;
        AppLogger.info("Sweeping " + ready.manifest.name
            + " over [" + low + ", " + high + "]...");
        var found = com.sphere.core.rootbackend.RootPipelineProbe.health(
            compilerFor(c), ready.manifest, ready.source, 20000, low, high);
        AppLogger.raw("      " + found.summary());
        if (found.isClean()) {
            AppLogger.success("Every call answered a number.");
        } else {
            AppLogger.warn("Some calls did not. :root pipeline scan "
                + ready.manifest.name + " finds where.");
        }
    }

    /** Walks one input across a range and prints the curve. */
    public static void rootPipelineScan(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pipeline scan"));
        if (w.length < 1) {
            Handlers.usage(":root pipeline scan <name> [input] [low] [high] [others-at]");
            return;
        }
        var ready = readyFor(w[0], activeProject(c));
        if (ready == null) {
            return;
        }
        int which = 0;
        if (w.length > 1) {
            for (int n = 0; n < ready.manifest.inputs.size(); n++) {
                if (ready.manifest.inputs.get(n).name().equalsIgnoreCase(w[1])) {
                    which = n;
                }
            }
        }
        final double low = w.length > 2 ? asDouble(w[2]) : 0.0;
        final double high = w.length > 3 ? asDouble(w[3]) : 100.0;
        final double held = w.length > 4 ? asDouble(w[4]) : 1.0;

        var found = com.sphere.core.rootbackend.RootPipelineProbe.scan(
            compilerFor(c), ready.manifest, ready.source, which, low, high, 41, held);
        final String name = which < ready.manifest.inputs.size()
            ? ready.manifest.inputs.get(which).name() : "input";
        AppLogger.raw("  " + name + " from " + low + " to " + high
            + ", the others held at " + held);
        for (var p : found.points()) {
            AppLogger.raw(String.format("      %12.6g   %s", p.x(), p.y()));
        }
        for (String one : found.findings()) {
            AppLogger.warn("  " + one);
        }
        if (found.findings().isEmpty() && !found.points().isEmpty()) {
            AppLogger.success("Nothing odd along that range.");
        }
    }

    /** A pipeline that can be inspected, or a message saying why not. */
    private record Ready(com.sphere.core.rootbackend.RootPipelineManifest manifest,
                         java.nio.file.Path source) { }

    private static Ready readyFor(String name, String project) {
        java.nio.file.Path source = pipelineSource(name, project);
        if (source == null) {
            AppLogger.error("No pipeline named " + name + " in includes/.");
            return null;
        }
        var manifest = com.sphere.core.rootbackend.RootPipelineManifest.fromSource(source);
        if (manifest == null) {
            AppLogger.error(name + " does not declare itself as a pipeline.");
            return null;
        }
        if (!com.sphere.core.rootbackend.RootPipelineTemplates.canBeTimed(manifest)) {
            AppLogger.error("Only a transform or a filter taking numbers can be inspected.");
            return null;
        }
        return new Ready(manifest, source);
    }

    private static com.sphere.core.rootbackend.RootUserCompiler compilerFor(
            CommandExecutionContext c) {
        return new com.sphere.core.rootbackend.RootUserCompiler(
            new com.sphere.utils.SettingsManager(), Handlers.backend(c));
    }

    private static double asDouble(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException notANumber) {
            return 1.0;
        }
    }

    /** The source of a pipeline by its name, the project layer winning. */
    private static java.nio.file.Path pipelineSource(String name, String project) {
        java.nio.file.Path found = null;
        for (java.nio.file.Path root
                : com.sphere.core.rootbackend.RootUserPipeline.layers(project)) {
            for (java.nio.file.Path source
                    : com.sphere.core.rootbackend.RootUserPipeline.sources(root)) {
                var manifest =
                    com.sphere.core.rootbackend.RootPipelineManifest.fromSource(source);
                if (manifest != null && manifest.name.equalsIgnoreCase(name.trim())) {
                    found = source;
                }
            }
        }
        return found;
    }

    private static String activeProject(CommandExecutionContext c) {
        return c != null && c.ctx != null ? c.ctx.getActiveProject() : null;
    }

    public static void rootPwd(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gDirectory->pwd()");
    }

    public static void rootReset(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gROOT->Reset()");
    }

    public static void rootRunScript(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root script run");
        if (a.isEmpty()) {
            Handlers.usage(":root script run <file>");
            return;
        }
        String a0 = Handlers.macroPath(a, c);
        if (a0 == null) return;
        Handlers.cling(c, "gROOT->ProcessLine(\".x " + a0 + "\")");
    }

    public static void rootSafeMode(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root safe-mode");
        if (a.isEmpty()) {
            Handlers.usage(":root safe-mode <0|1>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gROOT->SetBatch(" + a0 + ")");
    }

    /** The macros of both layers, the project marked, so the user sees what runs. */
    public static void rootScriptList(String i, CommandExecutionContext c) {
        String project = c != null && c.ctx != null ? c.ctx.getActiveProject() : null;
        int shown = 0;
        for (java.nio.file.Path root
                : com.sphere.core.rootbackend.RootUserPipeline.layers(project)) {
            java.util.List<java.nio.file.Path> found =
                com.sphere.core.rootbackend.RootUserPipeline.macros(root);
            if (found.isEmpty()) continue;
            AppLogger.raw("  " + root.resolve(
                com.sphere.core.rootbackend.RootUserPipeline.SCRIPTS_DIR));
            for (java.nio.file.Path macro : found) {
                AppLogger.raw(String.format("      %-32s %s",
                    macro.getFileName(), Handlers.humanBytes(Handlers.sizeOf(macro))));
                shown++;
            }
        }
        if (shown == 0) {
            AppLogger.result("No macro yet. Put a .C or .cpp in user_scripts/.");
        } else {
            AppLogger.raw("  " + shown + " macros. A later folder overrides an earlier name.");
        }
    }

    public static void rootSetOutput(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root output set");
        if (a.isEmpty()) {
            Handlers.usage(":root output set <file>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gSystem->RedirectOutput(\"" + a0 + "\")");
    }

    public static void rootStats(String i, CommandExecutionContext c) {
        Handlers.metrics(c, "text");
    }

    public static void rootStatus(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gROOT->GetListOfFiles()->Print()");
    }

    public static void rootSysConfig(String i, CommandExecutionContext c) {
        // An empty request is answered by the engine with the list of keys.
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_SYS_CONFIG, 0,
             Handlers.head(Handlers.args(i, ":root sys config")));
    }

    public static void rootSysInfo(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gSystem->GetBuildArch()");
    }

    public static void rootSysMemory(String i, CommandExecutionContext c) {
        Handlers.metrics(c, "memory");
    }

    public static void rootSysPlugins(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gROOT->GetListOfTypes()->Print()");
    }

    public static void rootSysUptime(String i, CommandExecutionContext c) {
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_SYS_UPTIME, 0, null);
    }

    public static void rootTimerCpu(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root timer cpu"));
        if (w.length < 1) {
            Handlers.usage(":root timer cpu <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TStopwatch") + "->CpuTime()");
    }

    public static void rootTimerNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root timer new"));
        if (w.length < 1) {
            Handlers.usage(":root timer new <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "TStopwatch", "new TStopwatch()"));
    }

    public static void rootTimerReal(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root timer real"));
        if (w.length < 1) {
            Handlers.usage(":root timer real <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TStopwatch") + "->RealTime()");
    }

    public static void rootTimerStart(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root timer start"));
        if (w.length < 1) {
            Handlers.usage(":root timer start <name>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TStopwatch") + "->Start(), std::string(\"started\"))");
    }

    public static void rootTimerStop(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root timer stop"));
        if (w.length < 1) {
            Handlers.usage(":root timer stop <name>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TStopwatch") + "->Stop(), " + Handlers.held(w[0], "TStopwatch") + "->RealTime())");
    }

    public static void rootVars(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root vars");
        if (a.isEmpty()) {
            Handlers.askAndReport("root");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gROOT->GetGlobal(\"" + a0 + "\")->Print()");
    }

    public static void rootVersion(String i, CommandExecutionContext c) {
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_SYS_VERSION, 0, null);
    }

    public static void rootWatchdogKill(String i, CommandExecutionContext c) {
        Handlers.metrics(c, "reset");
    }

    public static void rootWatchdogStatus(String i, CommandExecutionContext c) {
        Handlers.metrics(c, "engine");
    }

    /**
     * Compiles the macros of user_scripts/ with ACLiC, inside the interpreter.
     * One named macro, or every macro with --all.
     */
    public static void rootScriptCompileAll(String i, CommandExecutionContext c) {
        String project = c != null && c.ctx != null ? c.ctx.getActiveProject() : null;
        com.sphere.core.rootbackend.RootBackend engine = Handlers.backend(c);
        if (engine == null || !engine.isAvailable()) {
            AppLogger.error("ACLiC compiles inside the interpreter, "
                + "so the ROOT engine has to be running.");
            return;
        }

        java.util.List<java.nio.file.Path> queue = new java.util.ArrayList<>();
        for (java.nio.file.Path root
                : com.sphere.core.rootbackend.RootUserPipeline.layers(project)) {
            queue.addAll(com.sphere.core.rootbackend.RootUserPipeline.macros(root));
        }
        if (queue.isEmpty()) {
            AppLogger.result("No macro in user_scripts/ to compile.");
            return;
        }

        AppLogger.info("Compiling " + queue.size() + " macros with ACLiC...");
        for (java.nio.file.Path macro : queue) {
            String path = com.sphere.core.rootbackend.RootUserPipeline.forCling(macro);
            Handlers.cling(c, "gROOT->LoadMacro(\"" + path + "+\")");
        }
    }

    // --- reading fits back, TF1 parameters, applying a TMVA model, canvas layout and output, geometry, RooFit datasets and results, tree caches and indices, the rest of RDataFrame, the host system, regular expressions, file internals and more of TMath ---

    public static void rootSysLs(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sys ls"));
        Handlers.cling(c, "[]{ TSystemDirectory dir(\"d\", \"" + (w.length > 0 ? w[0] : ".") + "\"); TList *files = dir.GetListOfFiles(); if (files == nullptr) { return std::string(\"ERROR: cannot read that directory\"); } files->Print(); return std::string(\"\"); }()");
    }

    public static void rootSysExists(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sys exists"));
        if (w.length < 1) {
            Handlers.usage(":root sys exists <path>".trim());
            return;
        }
        Handlers.cling(c, "(gSystem->AccessPathName(\"" + w[0] + "\") == 0)");
    }

    public static void rootSysMkdir(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sys mkdir"));
        if (w.length < 1) {
            Handlers.usage(":root sys mkdir <path>".trim());
            return;
        }
        Handlers.cling(c, "gSystem->mkdir(\"" + w[0] + "\", true)");
    }

    public static void rootSysRm(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sys rm"));
        if (w.length < 1) {
            Handlers.usage(":root sys rm <path>".trim());
            return;
        }
        Handlers.cling(c, "gSystem->Unlink(\"" + w[0] + "\")");
    }

    public static void rootSysCp(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sys cp"));
        if (w.length < 2) {
            Handlers.usage(":root sys cp <from> <to>".trim());
            return;
        }
        Handlers.cling(c, "gSystem->CopyFile(\"" + w[0] + "\", \"" + w[1] + "\", true)");
    }

    public static void rootSysMv(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sys mv"));
        if (w.length < 2) {
            Handlers.usage(":root sys mv <from> <to>".trim());
            return;
        }
        Handlers.cling(c, "gSystem->Rename(\"" + w[0] + "\", \"" + w[1] + "\")");
    }

    public static void rootSysWhich(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sys which"));
        if (w.length < 1) {
            Handlers.usage(":root sys which <file>".trim());
            return;
        }
        Handlers.cling(c, "[]{ const char *found = gSystem->Which(gSystem->GetDynamicPath(), \"" + w[0] + "\"); return (found == nullptr) ? std::string(\"not found\") : std::string(found); }()");
    }

    public static void rootSysCwd(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sys cwd"));
        Handlers.cling(c, "gSystem->WorkingDirectory()");
    }

    public static void rootSysChdir(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sys chdir"));
        if (w.length < 1) {
            Handlers.usage(":root sys chdir <directory>".trim());
            return;
        }
        Handlers.cling(c, "gSystem->ChangeDirectory(\"" + w[0] + "\")");
    }

    public static void rootSysExpand(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sys expand"));
        if (w.length < 1) {
            Handlers.usage(":root sys expand <path>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TString p(\"" + w[0] + "\"); gSystem->ExpandPathName(p); return std::string(p.Data()); }()");
    }

    public static void rootSysExec(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sys exec"));
        if (w.length < 1) {
            Handlers.usage(":root sys exec <command>".trim());
            return;
        }
        Handlers.cling(c, "gSystem->Exec(\"" + Handlers.join(w, 0) + "\")");
    }

    public static void rootSysHostname(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sys hostname"));
        Handlers.cling(c, "gSystem->HostName()");
    }

    public static void rootSysPid(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sys pid"));
        Handlers.cling(c, "gSystem->GetPid()");
    }

    public static void rootSysLoad(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sys load"));
        if (w.length < 1) {
            Handlers.usage(":root sys load <library>".trim());
            return;
        }
        Handlers.cling(c, "gSystem->Load(\"" + w[0] + "\")");
    }

    public static void rootSysLibraries(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sys libraries"));
        Handlers.cling(c, "gSystem->GetLibraries()");
    }

    public static void rootSysNow(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sys now"));
        Handlers.cling(c, "[]{ TDatime now; return std::string(now.AsString()); }()");
    }

    public static void rootStrMatch(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root str match"));
        if (w.length < 2) {
            Handlers.usage(":root str match <regex> <text>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TPRegexp re(\"" + w[0] + "\"); TString s(\"" + Handlers.join(w, 1) + "\"); return re.Match(s) > 0; }()");
    }

    public static void rootStrReplace(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root str replace"));
        if (w.length < 3) {
            Handlers.usage(":root str replace <regex> <replacement> <text>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TPRegexp re(\"" + w[0] + "\"); TString s(\"" + Handlers.join(w, 2) + "\"); re.Substitute(s, \"" + w[1] + "\"); return std::string(s.Data()); }()");
    }

    public static void rootStrHash(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root str hash"));
        if (w.length < 1) {
            Handlers.usage(":root str hash <text>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TString s(\"" + Handlers.join(w, 0) + "\"); return (long)s.Hash(); }()");
    }

    /** No arguments. */
    public static void rootTimeNow(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root time now"));
        if (w.length < 0) {
            return;
        }
        Handlers.cling(c, "[]{ TDatime d; return std::string(d.AsString()); }()");
    }

    /** <seconds> */
    public static void rootTimeConvert(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root time convert"));
        if (w.length < 1) {
            Handlers.usage(":root time convert <seconds>");
            return;
        }
        Handlers.cling(c, "[]{ TDatime d((UInt_t) " + w[0] + "); return std::string(d.AsString()); }()");
    }

    /** The date to convert. */
    public static void rootTimeUnix(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root time unix"));
        if (w.length < 1) {
            Handlers.usage(":root time unix <YYYY-MM-DD HH:MM:SS>");
            return;
        }
        Handlers.cling(c, "[]{ TDatime d(\"" + Handlers.join(w, 0) + "\"); return (long) d.Convert(); }()");
    }

    /** <a> <b> */
    public static void rootTimeDiff(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root time diff"));
        if (w.length < 2) {
            Handlers.usage(":root time diff <a> <b>");
            return;
        }
        Handlers.cling(c, "[]{ TDatime a((UInt_t) " + w[0] + "); TDatime b((UInt_t) " + w[1] + "); return (long) b.Convert() - (long) a.Convert(); }()");
    }

    /** No arguments. */
    public static void rootTimeStamp(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root time stamp"));
        if (w.length < 0) {
            return;
        }
        Handlers.cling(c, "[]{ TTimeStamp t; return std::string(t.AsString(\"l\")); }()");
    }

}
