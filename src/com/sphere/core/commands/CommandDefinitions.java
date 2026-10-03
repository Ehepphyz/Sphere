package com.sphere.core.commands;

import com.sphere.core.commandrouterincludes.Tokenizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Registry for internal shell commands.
 * Supports prefix-based matching to allow command arguments.
 * Designed for incremental updates as development progresses.
 */
public class CommandDefinitions {

    public static class CommandInfo {
        public final String name;
        public final String description;
        public final BiConsumer<String, CommandExecutionContext> handler;

        public CommandInfo(String name, String description,
                           BiConsumer<String, CommandExecutionContext> handler) {
            this.name = name;
            this.description = description;
            this.handler = handler;
        }
    }

    private static final Map<String, CommandInfo> INTERNAL_COMMANDS = new LinkedHashMap<>();

    static {
        // --- Core Platform Commands ---
        register(":help", "Show help and list available platform commands", Handlers::help);
        register(":version", "Display active Sphere platform version details", Handlers::version);
        register(":quit", "Safely terminate and exit the Sphere session", Handlers::quit);
        register(":edit", "Open a file for editing in the default editor", Handlers::editNfile);
        register(":echo", "Evaluate and print runtime variables or raw terminal text output", Handlers::echoCommand);
        register(":set", "Assign or update an application runtime context variable", Handlers::setCommand);
        register(":create new", "Create a new file within the active directory", Handlers::createNew);

        // --- Workspace & Project Management ---
        register(":project new", "Create and initialize a new project", Handlers::projectNew);
        register(":project open", "Open an existing project from disk", Handlers::projectOpen);
        register(":project close", "Close the currently active project", Handlers::projectClose);
        register(":project set", "Set the active project context", Handlers::projectSet);
        register(":project info", "Show metadata and status for the current project", Handlers::projectInfo);
        register(":project list", "List all registered projects in the workspace", Handlers::projectList);
        register(":project delete", "Delete a specified project and its metadata", Handlers::projectDelete);
        register(":workspace scan", "Scan the workspace directory for missing or untracked projects", Handlers::workspaceScan);
        register(":workspace clean", "Clean temporary files and cache build outputs in workspace", Handlers::workspaceClean);
        register(":workspace diag", "Run diagnostic checks on the active workspace", Handlers::workspaceDiag);

        // --- Environment & Configuration ---
        register(":env list", "List all available runtime environments", Handlers::envList);
        register(":env activate", "Activate a target runtime environment profile", Handlers::envActivate);
        register(":env deactivate", "Deactivate the currently running environment", Handlers::envDeactivate);
        register(":env info", "Display context information for the active environment", Handlers::envInfo);
        register(":backend list", "List all available execution backends", Handlers::backendList);
        register(":backend diag", "Run system diagnostics on active execution backends", Handlers::backendDiag);
        register(":backend reload", "Reload configuration parameters for all execution backends", Handlers::backendReload);
        register(":config show", "Display active platform configuration settings", Handlers::configShow);
        register(":config edit", "Modify platform configuration parameters", Handlers::configEdit);
        register(":config reset", "Reset configuration settings to factory default values", Handlers::configReset);
        register(":log level", "Set the current application logging severity level", Handlers::logLevel);
        register(":log tail", "Tail live log output in real time", Handlers::logTail);
        register(":log clear", "Clear application log file contents", Handlers::logClear);

        // --- Interactive Language Engines ---
        register(":py settings", "Open Python environment configuration settings", Handlers::pySettings);
        register(":py mode", "Enter the interactive persistent Python shell mode", Handlers::pyMode);
        register(":py exit", "Exit interactive Python mode and return to default console", Handlers::pyExit);
        register(":py diag", "Run diagnostic checks on the local Python interpreter", Handlers::pyDiag);
        register(":py vars", "List defined global and local Python memory variables", Handlers::pyVars);
        register(":cpp vars", "Inspect registered C++ memory variables and structures", Handlers::cppVars);
        register(":cpp diag", "Run toolchain and compiler diagnostics for the C++ backend", Handlers::cppDiag);
        register(":cpp mode", "Enter the interactive C++ interpreter shell mode", Handlers::cppMode);
        register(":root mode", "Send every line to ROOT until ':root exit'. Usage: :root mode", Handlers::rootMode);
        register(":root exit", "Leave ROOT mode. Usage: :root exit", Handlers::rootExit);
        register(":cpp exit", "Exit interactive C++ mode", Handlers::cppExit);
        register(":cpp plan", "Say what a build would compile and what it would reuse. Usage: :cpp plan", Handlers::cppPlan);
        register(":cpp build", "Compile only what changed, then link. Usage: :cpp build", Handlers::cppBuild);
        register(":cpp rebuild", "Compile everything, ignoring the cache. Usage: :cpp rebuild", Handlers::cppRebuild);
        register(":cpp clean", "Remove the objects, the index and the binary. Usage: :cpp clean", Handlers::cppClean);
        register(":cpp project", "Report the build system found and the commands it would run. Usage: :cpp project", Handlers::cppProject);
        register(":cpp project configure", "Configure the project with CMake. Usage: :cpp project configure [cmake arguments]", Handlers::cppProjectConfigure);
        register(":cpp project build", "Build with CMake, Ninja or Make. Usage: :cpp project build [target]", Handlers::cppProjectBuild);
        register(":cpp project clean", "Clean through the project's own build system. Usage: :cpp project clean", Handlers::cppProjectClean);
        register(":js env", "Display JavaScript engine runtime parameters", Handlers::jsEnv);
        register(":js diag", "Run diagnostics on the ECMAScript interpreter engine", Handlers::jsDiag);

        // --- System Tools & Utilities ---
        register(":snippet list", "List all indexed code snippets", Handlers::snippetList);
        register(":snippet info", "Display metadata and content details for a specific snippet", Handlers::snippetInfo);
        register(":snippet reload", "Hot-reload the snippet registry index from disk", Handlers::snippetReload);
        register(":tools diag", "Audit system toolchain and dependency installations", Handlers::toolsDiag);
        register(":tools list", "List available binary tool executables", Handlers::toolsList);
        register(":tools update", "Update metadata and version registries for external tools", Handlers::toolsUpdate);
        register(":clear", "Clear the active console user interface screen output buffer", Handlers::clearConsole);
        register(":kill", "Stop a running command by name, or by PID. Usage: :kill <name|PID>", Handlers::terminateProcess);
        register(":tasks", "List all active background threads and process tasks", Handlers::listActiveTasks);

        // --- ROOT Framework Bridge — Files & Directories ---
        register(":root file open", "Open a ROOT file handle. Usage: :root file open <path> [mode]", RootFileCommands::rootOpenFile);
        register(":root file open-remote", "Open a remote ROOT file via web/XROOTD. Usage: :root file open-remote <url>", RootFileCommands::rootOpenRemoteFile);
        register(":root file list", "List the open ROOT files with their id and name", RootFileCommands::rootFileList);
        register(":root file close", "Close an open file. Usage: :root file close <id|name>", RootFileCommands::rootClose);
        register(":root file close-all", "Close all opened ROOT file handles", RootFileCommands::rootCloseAll);
        register(":root file ls", "List keys inside an opened ROOT file handle. Usage: :root file ls [file_id]", RootFileCommands::rootLs);
        register(":root file info", "Display metadata info for an open file handle. Usage: :root file info <file_id>", RootFileCommands::rootFileInfo);
        register(":root file write", "Flush and write an open file. Usage: :root file write <id|name>", RootFileCommands::rootFileWrite);
        register(":root file keys", "List keys inside an opened ROOT file handle. Usage: :root file keys [file_id]", RootFileCommands::rootFileKeys);
        register(":root file scan", "Report a ROOT file's health, structure and basket layout. Usage: :root file scan <path> [--json]", RootFileCommands::rootFileScan);
        register(":root file cd", "Change directory inside ROOT file. Usage: :root file cd <path>", RootFileCommands::rootFileCd);
        register(":root file pwd", "Print current working directory inside active ROOT file", RootFileCommands::rootFilePwd);
        register(":root file dir", "List keys inside an opened ROOT file handle. Usage: :root file dir [file_id]", RootFileCommands::rootFileDir);
        register(":root file get", "Extract object from ROOT file handle. Usage: :root file get <name>", RootFileCommands::rootFileGet);
        register(":root file recreate", "Recreate a ROOT file, overwriting existing contents", RootFileCommands::rootFileRecreate);
        register(":root file open-update", "Open ROOT file in UPDATE mode", RootFileCommands::rootFileOpenUpdate);
        register(":root file mkdir", "Create directory inside ROOT file handle. Usage: :root file mkdir <name>", RootFileCommands::rootFileMkdir);
        register(":root file rmdir", "Remove directory inside ROOT file handle. Usage: :root file rmdir <name>", RootFileCommands::rootFileRmdir);
        register(":root file copy", "Copy object key within ROOT file structure", RootFileCommands::rootFileCopy);
        register(":root file move", "Move object key within ROOT file structure", RootFileCommands::rootFileMove);
        register(":root file delete", "Delete key/object from active ROOT file handle", RootFileCommands::rootFileDelete);
        register(":root ping", "Probe the engine (CMD_PING)", RootEngineCommands::rootPing);
        register(":root version", "ROOT version reported by the engine (CMD_SYS_VERSION)", RootEngineCommands::rootVersion);
        register(":root sys uptime", "Engine uptime (CMD_SYS_UPTIME)", RootEngineCommands::rootSysUptime);
        register(":root sys config", "root-config value. Usage: :root sys config <key>, no key lists them (cflags libs incdir libdir prefix arch ncpu cxx-standard ...)", RootEngineCommands::rootSysConfig);
        register(":root schema discover", "Describe a TTree schema (CMD_SCHEMA_DISCOVER). Usage: :root schema discover <tree_id>", RootTreeCommands::rootSchemaDiscover);
        register(":root tree attach", "Bind a tree to an id, which the other tree commands then use. Usage: :root tree attach <tree_id> <file_id|name> <tree_path>", RootTreeCommands::rootTreeAttach);
        register(":root tree column", "Read one branch as a column (CMD_TTREE_READ_COLUMN). Usage: :root tree column <tree_id> <branch>", RootTreeCommands::rootTreeColumn);
        register(":root tree plot", "Draw one branch against another in the Plots tab. Usage: :root tree plot <tree_id> <x_branch> <y_branch>", RootTreeCommands::rootTreePlot);
        register(":plots add", "Show a picture in the Plots tab. Usage: :plots add <file.png|jpg|tif|svg>", Handlers::plotsAdd);
        register(":plots watch", "Watch a folder for new pictures, or list the folders watched. Usage: :plots watch [folder]", Handlers::plotsWatch);
        register(":plots unwatch", "Stop watching a folder. Usage: :plots unwatch <folder>", Handlers::plotsUnwatch);
        register(":plots folder", "Show or move the folder the Plots tab writes pictures into. Usage: :plots folder [folder]", Handlers::plotsFolder);
        register(":plots clear", "Empty the Plots tab", Handlers::plotsClear);
        register(":julia start", "Open a Julia session that keeps what it defines. "
                 + "Usage: :julia start [julia flags, e.g. -t 4 --project=.]",
                 Handlers::juliaStart);
        register(":julia stop", "Close the Julia session", Handlers::juliaStop);
        register(":julia mode", "Send every line to the Julia session",
                 Handlers::juliaMode);
        register(":julia exit", "Leave Julia mode", Handlers::juliaExit);

        register(":layout", "Report the width of the three columns and where it came from",
                 Handlers::layoutReport);

        register(":fort mode", "Build a Fortran program line by line; :exec runs it",
                 Handlers::fortMode);
        register(":fort exit", "Leave Fortran mode", Handlers::fortExit);

        register(":exec", "Run what the active mode still holds, chiefly the Fortran program",
                 Handlers::execRun);
        register(":exec reset", "Empty what the active mode still holds",
                 Handlers::execReset);
        register(":julia run", "Run a file in the Julia session. Usage: :julia run <file.jl>",
                 Handlers::juliaRun);
        register(":julia vars", "List what the Julia session is holding",
                 Handlers::juliaVars);
        register(":julia diag", "Say which Julia is used", Handlers::juliaDiag);
        register(":vars", "List the variables of every language", Handlers::varsList);
        register(":vars diag", "Say where the variables come from and what is in the way",
                 Handlers::varsDiag);
        register(":vars refresh", "Ask the languages that are still running",
                 Handlers::varsRefresh);
        register(":vars clear", "Empty the Variables tab, or one language of it. "
                 + "Usage: :vars clear [language]", Handlers::varsClear);
        register(":vars folder", "Show or move the folder variable files are read from. "
                 + "Usage: :vars folder [folder]", Handlers::varsFolder);
        register(":vars watch", "Watch a folder for variable files, or list those watched. "
                 + "Usage: :vars watch [folder]", Handlers::varsWatch);
        register(":vars unwatch", "Stop watching a folder. Usage: :vars unwatch <folder>",
                 Handlers::varsUnwatch);
        register(":vars wrap", "Collect a Python script's variables, or leave the run alone. "
                 + "Usage: :vars wrap [on|off]", Handlers::varsWrap);
        register(":vars helper", "Write what a language needs to publish its variables. "
                 + "Usage: :vars helper <python|julia|cpp|fortran>", Handlers::varsHelper);
        register(":clip", "Say what the clipboard holds and which fields answer the copy keys",
                 Handlers::clipStatus);
        register(":clip test", "Write a marker to the clipboard and read it back",
                 Handlers::clipTest);
        register(":root tree stats", "Compute branch statistics in the engine (CMD_TTREE_COMPUTE_STATS). Usage: :root tree stats <tree_id> <branch>", RootTreeCommands::rootTreeStats);
        register(":root tree filter", "Apply a selection in the engine (CMD_TTREE_APPLY_FILTER). Usage: :root tree filter <tree_id> <expr>", RootTreeCommands::rootTreeFilter);
        register(":root tree open", "Entry count for a named TTree. Usage: :root tree open <name>", RootTreeCommands::rootGetTree);
        register(":root tree branch", "Print one branch. Usage: :root tree branch <tree> <branch>", RootTreeCommands::rootGetBranch);
        register(":root chain add", "Add a file to a bound chain. Usage: :root chain add <chain> <file>", RootTreeCommands::rootChainAdd);
        register(":root chain new", "Bind a TChain to a name. Usage: :root chain new <name> <tree>", RootTreeCommands::rootChainNew);
        register(":root chain entries", "Entries in a bound chain. Usage: :root chain entries <chain>", RootTreeCommands::rootChainEntries);
        register(":root rdf open", "Bind an RDataFrame to a name. Usage: :root rdf open [<name>] <tree> <file>", RootTreeCommands::rootRdfOpen);
        register(":root rdf filter", "Filter a bound frame in place. Usage: :root rdf filter <name> <expr>", RootTreeCommands::rootRdfFilter);
        register(":root rdf count", "Entries left in a bound frame. Usage: :root rdf count [<name>]", RootTreeCommands::rootRdfCount);
        register(":root func new", "Create a TF1. Usage: :root func new <name> <formula>", RootHistCommands::rootFuncNew);
        register(":root style set", "Select a ROOT style. Usage: :root style set <name>", RootGraphicsCommands::rootStyleSet);
        register(":root script load", "Load a macro. Usage: :root script load <file>", RootEngineCommands::rootLoadScript);
        register(":root script run", "Run a macro. Usage: :root script run <file>", RootEngineCommands::rootRunScript);
        register(":root script compile", "Compile a macro with ACLiC. Usage: :root script compile <file> | --all", RootEngineCommands::rootCompileScripts);
        register(":root includes load", "Add an include path to the interpreter. Usage: :root includes load <dir>", RootEngineCommands::rootLoadIncludes);
        register(":root includes compile", "Add an include path to the compiler. Usage: :root includes compile <dir>", RootEngineCommands::rootCompileIncludes);
        register(":root script list", "List the macros in user_scripts/, global then project", RootEngineCommands::rootScriptList);
        register(":root includes list", "List the libraries and sources in includes/", RootEngineCommands::rootIncludesList);
        register(":root includes reload", "Load again what is in includes/, after a rebuild", RootEngineCommands::rootIncludesReload);
        register(":root includes build", "Build includes/ into shared libraries. Usage: :root includes build <file.cpp> | --all [--force]", RootEngineCommands::rootIncludesBuild);
        register(":root build", "Build includes/ then user_scripts/, the whole pipeline", RootEngineCommands::rootBuildAll);
        register(":root personal pipeline", "Open the builder where a pipeline is written, built and loaded", RootEngineCommands::rootPersonalPipeline);
        register(":root pipeline list", "Pipelines loaded in the engine, and those still waiting on disk", RootEngineCommands::rootPipelineList);
        register(":root pipeline new", "Start a pipeline in the builder. Usage: :root pipeline new <name>", RootEngineCommands::rootPipelineNew);
        register(":root pipeline show", "What a pipeline declares about itself. Usage: :root pipeline show <name>", RootEngineCommands::rootPipelineShow);
        register(":root pipeline edit", "Open a pipeline source in the editor. Usage: :root pipeline edit <name>", RootEngineCommands::rootPipelineEdit);
        register(":root pipeline build", "Build one pipeline with its own flags. Usage: :root pipeline build <name>", RootEngineCommands::rootPipelineBuild);
        register(":root pipeline load", "Give a built pipeline to the running engine. Usage: :root pipeline load <name>", RootEngineCommands::rootPipelineLoad);
        register(":root pipeline drop", "Take a pipeline back out of the engine. Usage: :root pipeline drop <name>", RootEngineCommands::rootPipelineDrop);
        register(":root pipeline run", "Call a loaded pipeline. Usage: :root pipeline run <name> [args...]", RootEngineCommands::rootPipelineRun);
        register(":root pipeline time", "Time a pipeline once per set of build flags. Usage: :root pipeline time <name> [calls]", RootEngineCommands::rootPipelineTime);
        register(":root pipeline watch", "Call a pipeline and print every value its code named. Usage: :root pipeline watch <name> [args...]", RootEngineCommands::rootPipelineWatch);
        register(":root pipeline check", "Sweep the inputs and count the answers that are not a number. Usage: :root pipeline check <name> [low] [high]", RootEngineCommands::rootPipelineCheck);
        register(":root pipeline scan", "Walk one input across a range and print the curve. Usage: :root pipeline scan <name> [input] [low] [high] [others-at]", RootEngineCommands::rootPipelineScan);
        register(":root pdf open", "Open a parton distribution set from its folder. Usage: :root pdf open <name> <folder> [--all] [--weighted]", RootPdfCommands::rootPdfOpen);
        register(":root pdf list", "The sets open right now, and what each one is", RootPdfCommands::rootPdfList);
        register(":root pdf info", "Range, knots, flavors and seams of a set. Usage: :root pdf info <name>", RootPdfCommands::rootPdfInfo);
        register(":root pdf check", "What is wrong with a set before it is trusted. Usage: :root pdf check <name>", RootPdfCommands::rootPdfCheck);
        register(":root pdf close", "Forget a set, or all of them. Usage: :root pdf close [name]", RootPdfCommands::rootPdfClose);
        register(":root pdf value", "xf at one point. Usage: :root pdf value <set> <pid|g|u|d|...> <x> <Q>", RootPdfCommands::rootPdfValue);
        register(":root pdf band", "The same with the uncertainty of every member. Usage: :root pdf band <set> <pid> <x> <Q>", RootPdfCommands::rootPdfBand);
        register(":root pdf sumrules", "Momentum and valence integrals, which the fit constrains. Usage: :root pdf sumrules <set> [Q]", RootPdfCommands::rootPdfSumrules);
        register(":root pdf scan", "One flavor across x, printed. Usage: :root pdf scan <set> <pid> [Q] [points]", RootPdfCommands::rootPdfScan);
        register(":root pdf grid", "The knots the file actually carries. Usage: :root pdf grid <set>", RootPdfCommands::rootPdfGrid);
        register(":root pdf seams", "Where the subgrids meet, and how far the value jumps. Usage: :root pdf seams <set>", RootPdfCommands::rootPdfSeams);
        register(":root pdf lumi", "Parton luminosity against mass. Usage: :root pdf lumi <set> <gg|qq|gq|uu|dd|cc|bb> [mass] [collider]", RootPdfCommands::rootPdfLumi);
        register(":root pdf compare", "Two sets point by point, with their bands. Usage: :root pdf compare <a> <b> <pid> [Q] [points]", RootPdfCommands::rootPdfCompare);
        register(":root pdf graph", "Build a named TGraph of one flavor in the engine. Usage: :root pdf graph <graph> <set> <pid> [Q] [points]", RootPdfCommands::rootPdfGraph);
        register(":root pdf plot", "Draw every flavor the set carries on one canvas. Usage: :root pdf plot <set> [Q] [points]", RootPdfCommands::rootPdfPlot);
        register(":root pdf export", "Write one flavor to a text file. Usage: :root pdf export <set> <file> <pid> [Q] [points]", RootPdfCommands::rootPdfExport);
        register(":root pdf header", "Write the C++ reader a pipeline includes. Usage: :root pdf header [file]", RootPdfCommands::rootPdfHeader);
        register(":root pdf alphas", "The strong coupling the set was fitted with. Usage: :root pdf alphas <set> [Q]", RootPdfCommands::rootPdfAlphas);
        register(":root pdf alphas-match", "Answer exactly what LHAPDF would, including where it holds alpha_s constant. Usage: :root pdf alphas-match <set> [on|off]", RootPdfCommands::rootPdfAlphasMatch);
        register(":root pdf where", "The folders searched for sets. Usage: :root pdf where [add|drop <folder>]", RootPdfCommands::rootPdfWhere);
        register(":root pdf installed", "The sets already on disk, wherever they are", RootPdfCommands::rootPdfInstalled);
        register(":root pdf search", "Look a published set up by name or by its number. Usage: :root pdf search <text|id>", RootPdfCommands::rootPdfSearch);
        register(":root pdf fetch", "Download a set that is not on disk. Usage: :root pdf fetch <name> [folder]", RootPdfCommands::rootPdfFetch);
        register(":root pdf install", "Unpack an archive already downloaded. Usage: :root pdf install <file.tar.gz> [folder]", RootPdfCommands::rootPdfInstall);
        register(":root pdf mirror", "Where sets are fetched from, for a laboratory that keeps a mirror. Usage: :root pdf mirror [url]", RootPdfCommands::rootPdfMirror);
        register(":root pdf use", "Open a set by its published name. Usage: :root pdf use <name> [as <handle>] [--all] [--weighted]", RootPdfCommands::rootPdfUse);
        register(":root pdf rule", "Which rule joins the knots and what happens outside them. Usage: :root pdf rule <set> [interpolation] [extrapolation]", RootPdfCommands::rootPdfRule);
        register(":root env detect", "What ROOT and what PDF folders Sphere is using, and which programs would find them", RootPdfCommands::rootEnvDetect);
        register(":root env write", "Write what MadGraph, Herwig, Rivet or a Geant4 build needs to find them. Usage: :root env write <folder>", RootPdfCommands::rootEnvWrite);
        register(":root env show", "The environment variables themselves, for a script that sets them its own way", RootPdfCommands::rootEnvShow);

        // --- Jets (Sphere's Java FastJet) and parton distributions (LHAPDF-style) ---
        FastJetCommands.register();
        // --- The FastJet contribs (fjcontrib), on the same events and definitions ---
        FjContribCommands.register();
        // --- ping, diag, mode and exit for the engines inside Sphere, as ROOT and Python have them ---
        EngineCommands.register();
        LhapdfCommands.register();
        // --- ROOT's prompt: .demo and the tutorials, the formats canvases reach the Plots tab in ---
        RootDemoCommands.register();
        // --- The bridge: every engine reads what the others export ---
        BridgeCommands.register();
        register(":root cut new", "Name a selection so it can be reused and combined", RootGraphicsCommands::rootCutNew);
        register(":root cut and", "Both selections at once. Usage: :root cut and <out> <a> <b>", RootGraphicsCommands::rootCutAnd);
        register(":root cut or", "Either selection. Usage: :root cut or <out> <a> <b>", RootGraphicsCommands::rootCutOr);
        register(":root cut not", "The entries a selection rejects. Usage: :root cut not <out> <a>", RootGraphicsCommands::rootCutNot);
        register(":root cut show", "What a named selection says. Usage: :root cut show <name>", RootGraphicsCommands::rootCutShow);
        register(":root cut apply", "Draw an expression under a named selection. Usage: :root cut apply <tree> <cut> <expr>", RootGraphicsCommands::rootCutApply);
        register(":root cut count", "How many entries a named selection keeps. Usage: :root cut count <tree> <cut>", RootGraphicsCommands::rootCutCount);
        register(":root xrootd open", "Open a file over the network. Usage: :root xrootd open <name> <url>", RootFileCommands::rootXrootdOpen);
        register(":root xrootd ls", "List a remote directory. Usage: :root xrootd ls <url>", RootFileCommands::rootXrootdLs);
        register(":root xrootd copy", "Copy a file to or from the network. Usage: :root xrootd copy <from> <to>", RootFileCommands::rootXrootdCopy);
        register(":root xrootd exists", "Whether a remote path is there. Usage: :root xrootd exists <url>", RootFileCommands::rootXrootdExists);
        register(":root xrootd stat", "Size and date of a remote path. Usage: :root xrootd stat <url>", RootFileCommands::rootXrootdStat);
        register(":root xrootd redirector", "Set the XRootD redirector. Usage: :root xrootd redirector <host>", RootFileCommands::rootXrootdRedirector);
        register(":root graph2d new", "Create a two-dimensional graph. Usage: :root graph2d new <name> <points>", RootGraphicsCommands::rootGraph2dNew);
        register(":root graph2d set", "Place one point. Usage: :root graph2d set <name> <i> <x> <y> <z>", RootGraphicsCommands::rootGraph2dSet);
        register(":root graph2d draw", "Draw it. Usage: :root graph2d draw <name> [option]", RootGraphicsCommands::rootGraph2dDraw);
        register(":root graph2d interpolate", "Read the surface between its points. Usage: :root graph2d interpolate <name> <x> <y>", RootGraphicsCommands::rootGraph2dInterp);
        register(":root graph2d points", "How many points it holds. Usage: :root graph2d points <name>", RootGraphicsCommands::rootGraph2dPoints);
        register(":root graph2d hist", "The histogram it interpolates onto. Usage: :root graph2d hist <name>", RootGraphicsCommands::rootGraph2dHist);
        register(":root axis time", "Show an axis as dates. Usage: :root axis time <hist> <x|y> [format]", RootGraphicsCommands::rootAxisTime);
        register(":root axis label", "Put a word on one bin. Usage: :root axis label <hist> <bin> <text>", RootGraphicsCommands::rootAxisLabel);
        register(":root axis deflate", "Drop the empty labeled bins. Usage: :root axis deflate <hist>", RootGraphicsCommands::rootAxisDeflate);
        register(":root axis divisions", "How many ticks an axis shows. Usage: :root axis divisions <hist> <x|y> <n>", RootGraphicsCommands::rootAxisDivisions);
        register(":root axis moreloglabels", "Label the decades in between on a log axis. Usage: :root axis moreloglabels <hist> <x|y>", RootGraphicsCommands::rootAxisMoreLog);
        register(":root axis titleoffset", "Move an axis title away from it. Usage: :root axis titleoffset <hist> <x|y> <value>", RootGraphicsCommands::rootAxisTitleOffset);
        register(":root axis new", "Draw an axis of your own. Usage: :root axis new <x1> <y1> <x2> <y2> <wmin> <wmax> <ndiv> [options]", RootGraphicsCommands::rootAxisNew);
        register(":root color new", "Define a color. Usage: :root color new <index> <r> <g> <b>  (0 to 1)", RootGraphicsCommands::rootColorNew);
        register(":root color find", "The index nearest a color. Usage: :root color find <r> <g> <b>  (0 to 1)", RootGraphicsCommands::rootColorFind);
        register(":root color show", "What a color index holds. Usage: :root color show <index>", RootGraphicsCommands::rootColorShow);
        register(":root color transparent", "A color you can see through. Usage: :root color transparent <index> <alpha>", RootGraphicsCommands::rootColorTransparent);
        register(":root color bright", "A lighter shade of a color. Usage: :root color bright <index>", RootGraphicsCommands::rootColorBright);
        register(":root color dark", "A darker shade of a color. Usage: :root color dark <index>", RootGraphicsCommands::rootColorDark);
        register(":root color hls", "A color from hue, light and saturation. Usage: :root color hls <h> <l> <s>", RootGraphicsCommands::rootColorHls);
        register(":root ratio new", "Data over simulation, one above the other. Usage: :root ratio new <name> <top> <bottom>", RootGraphicsCommands::rootRatioNew);
        register(":root ratio draw", "Draw the pair. Usage: :root ratio draw <name> [option]", RootGraphicsCommands::rootRatioDraw);
        register(":root ratio range", "What the lower panel shows. Usage: :root ratio range <name> <low> <high>", RootGraphicsCommands::rootRatioRange);
        register(":root ratio grid", "Lines across the lower panel. Usage: :root ratio grid <name> <values...>", RootGraphicsCommands::rootRatioGrid);
        register(":root ratio margin", "How much room the lower panel takes. Usage: :root ratio margin <name> <fraction>", RootGraphicsCommands::rootRatioMargin);
        register(":root clones new", "An array of one class, reused rather than reallocated. Usage: :root clones new <name> <class> <size>", RootAnalysisCommands::rootClonesNew);
        register(":root clones size", "How many it holds. Usage: :root clones size <name>", RootAnalysisCommands::rootClonesSize);
        register(":root clones at", "What sits at one place. Usage: :root clones at <name> <index>", RootAnalysisCommands::rootClonesAt);
        register(":root clones clear", "Empty it without giving the memory back. Usage: :root clones clear <name>", RootAnalysisCommands::rootClonesClear);
        register(":root clones expand", "Make room for more. Usage: :root clones expand <name> <size>", RootAnalysisCommands::rootClonesExpand);
        register(":root stat new", "Keep a running mean and spread. Usage: :root stat new <name>", RootAnalysisCommands::rootStatNew);
        register(":root stat fill", "Add a value. Usage: :root stat fill <name> <value> [weight]", RootAnalysisCommands::rootStatFill);
        register(":root stat mean", "Its mean so far. Usage: :root stat mean <name>", RootAnalysisCommands::rootStatMean);
        register(":root stat rms", "Its spread so far. Usage: :root stat rms <name>", RootAnalysisCommands::rootStatRms);
        register(":root stat sum", "The total it has added. Usage: :root stat sum <name>", RootAnalysisCommands::rootStatSum);
        register(":root stat count", "How many values went in. Usage: :root stat count <name>", RootAnalysisCommands::rootStatCount);
        register(":root stat print", "Everything it knows. Usage: :root stat print <name>", RootAnalysisCommands::rootStatPrint);
        register(":root time now", "The moment it is now", RootEngineCommands::rootTimeNow);
        register(":root time convert", "A unix second as a date. Usage: :root time convert <seconds>", RootEngineCommands::rootTimeConvert);
        register(":root time unix", "A date as unix seconds. Usage: :root time unix <\"YYYY-MM-DD HH:MM:SS\">", RootEngineCommands::rootTimeUnix);
        register(":root time diff", "Seconds between two unix times. Usage: :root time diff <a> <b>", RootEngineCommands::rootTimeDiff);
        register(":root time stamp", "Now, to the nanosecond", RootEngineCommands::rootTimeStamp);
        register(":root hist chi2test", "Whether two histograms agree. Usage: :root hist chi2test <a> <b> [UU|UW|WW|P|CHI2/NDF]", RootHistCommands::rootHistChi2Test);
        register(":root hist sumw2", "Keep the sum of squared weights, so the errors stay right. Usage: :root hist sumw2 <name>", RootHistCommands::rootHistSumw2);
        register(":root hist quantiles", "Where a fraction of the entries lies below. Usage: :root hist quantiles <name> <fraction>", RootHistCommands::rootHistQuantiles);
        register(":root hist interpolate", "Read between the bins. Usage: :root hist interpolate <name> <x>", RootHistCommands::rootHistInterpolate);
        register(":root hist cumulative", "The running total, bin by bin. Usage: :root hist cumulative <name> <out>", RootHistCommands::rootHistCumulative);
        register(":root hist getrandom", "Draw a value the histogram would give. Usage: :root hist getrandom <name>", RootHistCommands::rootHistGetRandom);
        register(":root hist fillrandom", "Fill it from a function. Usage: :root hist fillrandom <name> <function> <entries>", RootHistCommands::rootHistFillRandom);
        register(":root hist dividebinomial", "Divide as an efficiency, with binomial errors. Usage: :root hist dividebinomial <out> <passed> <total>", RootHistCommands::rootHistDivideBinomial);
        register(":root hist residuals", "What a fit left behind, bin by bin. Usage: :root hist residuals <hist> <function> <out>", RootHistCommands::rootHistResiduals);
        register(":root hist pulls", "The same in units of the error, which is what to look at. Usage: :root hist pulls <hist> <function> <out>", RootHistCommands::rootHistPulls);
        register(":root hist underflow", "What fell off the low end. Usage: :root hist underflow <name>", RootHistCommands::rootHistUnderflow);
        register(":root hist overflow", "What fell off the high end. Usage: :root hist overflow <name>", RootHistCommands::rootHistOverflow);
        register(":root hist maxbin", "Which bin holds the most. Usage: :root hist maxbin <name>", RootHistCommands::rootHistMaximumBin);
        register(":root hist binwidth", "How wide a bin is. Usage: :root hist binwidth <name> <bin>", RootHistCommands::rootHistBinWidth);
        register(":root hist effective", "Entries a weighted histogram is worth. Usage: :root hist effective <name>", RootHistCommands::rootHistEffectiveEntries);
        register(":root fit fractions", "How much of the data each model accounts for. Usage: :root fit fractions <data> <model1> <model2> [model3]", RootAnalysisCommands::rootFitFractions);
        register(":root fit range", "Fit only part of it. Usage: :root fit range <hist> <function> <low> <high>", RootAnalysisCommands::rootFitRange);
        register(":root fit likelihood", "Fit by likelihood, which is right when the bins are thin. Usage: :root fit likelihood <hist> <function>", RootAnalysisCommands::rootFitLikelihood);
        register(":root fit weighted", "Likelihood fit of a weighted histogram. Usage: :root fit weighted <hist> <function>", RootAnalysisCommands::rootFitWeighted);
        register(":root fit quality", "Whether a fit is any good. Usage: :root fit quality <function>", RootAnalysisCommands::rootFitQuality);
        register(":root fit confband", "The band a fit is confident within. Usage: :root fit confband <hist> <out> [level]", RootAnalysisCommands::rootFitConfBand);
        register(":root eff wilson", "Efficiency with the Wilson interval, which stays right at the ends. Usage: :root eff wilson <passed> <total> [level]", RootAnalysisCommands::rootEffWilson);
        register(":root eff agresti", "The Agresti-Coull interval, the simple one that also behaves. Usage: :root eff agresti <passed> <total> [level]", RootAnalysisCommands::rootEffAgresti);
        register(":root eff normal", "The plain interval, which is wrong near zero and one. Usage: :root eff normal <passed> <total> [level]", RootAnalysisCommands::rootEffNormal);
        register(":root eff poisson", "The interval on a count, for a rate rather than a fraction. Usage: :root eff poisson <observed> [level]", RootAnalysisCommands::rootEffPoisson);
        register(":root func derivative", "How fast a function climbs. Usage: :root func derivative <name> <x>", RootHistCommands::rootFuncDerivative);
        register(":root func second", "How fast its slope changes. Usage: :root func second <name> <x>", RootHistCommands::rootFuncSecond);
        register(":root func integralerror", "What the fit uncertainty does to an integral. Usage: :root func integralerror <name> <low> <high>", RootHistCommands::rootFuncIntegralError);
        register(":root func random", "Draw a value distributed as the function. Usage: :root func random <name>", RootHistCommands::rootFuncRandom);
        register(":root func normalized", "Make it integrate to one over its range. Usage: :root func normalized <name> <0|1>", RootHistCommands::rootFuncNormalized);
        register(":root func fwhm", "How wide a peak is at half its height. Usage: :root func fwhm <name> <low> <high>", RootHistCommands::rootFuncFwhm);
        register(":root tree drawopt", "Draw with a selection and an option. Usage: :root tree drawopt <tree> <expr> <selection> <option>", RootTreeCommands::rootTreeDrawOpt);
        register(":root tree estimate", "How many entries Draw may hold at once. Usage: :root tree estimate <tree> <n>", RootTreeCommands::rootTreeEstimate);
        register(":root tree aliases", "The short names this tree answers to. Usage: :root tree aliases <tree>", RootTreeCommands::rootTreeAliasList);
        register(":root tree refresh", "Pick up what has been written since. Usage: :root tree refresh <tree>", RootTreeCommands::rootTreeRefresh);
        register(":root tree formula", "Evaluate an expression on one entry. Usage: :root tree formula <tree> <entry> <expression>", RootTreeCommands::rootTreeFormula);
        register(":root tree size", "What the tree costs on disk and in memory. Usage: :root tree size <tree>", RootTreeCommands::rootTreeTotalSize);
        register(":root file recover", "Read what is left of a file that was not closed. Usage: :root file recover <name>", RootFileCommands::rootFileRecover);
        register(":root file compression", "How much the compression saved. Usage: :root file compression <name>", RootFileCommands::rootFileCompressionInfo);
        register(":root file trees", "The trees a file holds, and how big they are. Usage: :root file trees <name>", RootFileCommands::rootFileTreeList);
        register(":root file free", "Room a file has left over inside itself. Usage: :root file free <name>", RootFileCommands::rootFileFree);
        register(":root matrix multiply", "One matrix times another. Usage: :root matrix multiply <out> <a> <b>", RootAnalysisCommands::rootMatrixMultiply);
        register(":root matrix add", "One matrix plus another. Usage: :root matrix add <out> <a> <b>", RootAnalysisCommands::rootMatrixAdd);
        register(":root matrix norm", "How large a matrix is. Usage: :root matrix norm <name>", RootAnalysisCommands::rootMatrixNorm);
        register(":root matrix eigen", "What a symmetric matrix stretches, and by how much. Usage: :root matrix eigen <name>", RootAnalysisCommands::rootMatrixEigen);
        register(":root matrix similarity", "How an error matrix looks after a change of variables. Usage: :root matrix similarity <out> <cov> <jacobian>", RootAnalysisCommands::rootMatrixSimilarity);
        register(":root matrix condition", "Whether inverting it will mean anything. Usage: :root matrix condition <name>", RootAnalysisCommands::rootMatrixCondition);
        register(":root chain list", "The analysis chains saved in user_scripts/, both layers", RootEngineCommands::rootChainList);
        register(":root chain show", "What a chain would run, both ways. Usage: :root chain show <name>", RootEngineCommands::rootChainShow);
        register(":root chain run", "Replay a chain on the running engine. Usage: :root chain run <name>", RootEngineCommands::rootChainRun);
        register(":root chain macro", "Write a chain as a standalone RDataFrame macro. Usage: :root chain macro <name>", RootEngineCommands::rootChainMacro);

        // A pipeline is loaded from several places, one of them before any
        // command is asked for. This is how all of them end up listed.
        com.sphere.core.rootbackend.RootUserPipeline.onLoad(RootEngineCommands::publish);

        register(":root cache size", "Set TFile.CacheSize. Usage: :root cache size <bytes>", RootFileCommands::rootSetCacheSize);
        register(":root cache policy", "Set TFile.CachePolicy. Usage: :root cache policy <n>", RootFileCommands::rootSetCachePolicy);
        register(":root cache stats", "Print the ROOT environment table", RootFileCommands::rootCacheStats);
        register(":root cache clear", "List open files held by ROOT", RootFileCommands::rootCacheClear);
        register(":root limits obj-size", "Set TFile.MaxSize. Usage: :root limits obj-size <bytes>", RootFileCommands::rootSetMaxObjSize);
        register(":root limits handles", "Set TFile.MaxHandles. Usage: :root limits handles <n>", RootFileCommands::rootSetMaxHandles);
        register(":root limits age", "Set TFile.MaxAge. Usage: :root limits age <n>", RootFileCommands::rootSetMaxAge);
        register(":root getenv", "Read an environment variable through ROOT. Usage: :root getenv <name>", RootEngineCommands::rootGetEnv);
        register(":root vars", "Print a ROOT global. Usage: :root vars <name>", RootEngineCommands::rootVars);
        register(":root config", "Show the shared-library build command ROOT uses", RootEngineCommands::rootConfig);
        register(":root info", "ROOT version string", RootEngineCommands::rootInfo);
        register(":root stats", "Engine figures: counters, rings, heap, resident memory", RootEngineCommands::rootStats);
        register(":root gc", "Heap held by the engine: allocated, reclaimable, recycled", RootEngineCommands::rootGc);
        register(":root benchmark", "Times the round trip to the engine. Usage: :root benchmark [rounds]", RootEngineCommands::rootBenchmark);
        register(":root safe-mode", "Toggle ROOT batch mode. Usage: :root safe-mode <0|1>", RootEngineCommands::rootSafeMode);
        register(":root output set", "Redirect ROOT output to a file. Usage: :root output set <file>", RootEngineCommands::rootSetOutput);
        register(":root cd", "Change directory inside the current ROOT file", RootEngineCommands::rootCd);
        register(":root pwd", "Print the current ROOT directory", RootEngineCommands::rootPwd);
        register(":root mkdir", "Create a directory in the current ROOT file", RootEngineCommands::rootMkdir);
        register(":root analyze", "Print a named ROOT object. Usage: :root analyze <name>", RootEngineCommands::rootAnalyze);
        register(":root handles list", "List all active bridge file and object handles", RootEngineCommands::rootListHandles);
        register(":root bind list", "Names bound in the interpreter and what each one holds", RootEngineCommands::rootBindList);
        register(":root bind drop", "Release a bound name. Usage: :root bind drop <name>", RootEngineCommands::rootBindDrop);
        // --- ROOT coverage added from the ROOT API ---
        register(":root ntuple list", "The RNTuples a file holds. Usage: :root ntuple list <file>", RootTreeCommands::rootNtupleList);
        register(":root ntuple attach", "Bind an RNTuple to an id. Usage: :root ntuple attach <id> <file> <ntuple>", RootTreeCommands::rootNtupleAttach);
        register(":root ntuple info", "Entries, fields and clusters of a bound RNTuple. Usage: :root ntuple info <id>", RootTreeCommands::rootNtupleInfo);
        register(":root ntuple fields", "The schema of a bound RNTuple, one field per line. Usage: :root ntuple fields <id>", RootTreeCommands::rootNtupleFields);
        register(":root ntuple column", "Read one field into shared memory. Usage: :root ntuple column <id> <field>", RootTreeCommands::rootNtupleColumn);
        register(":root file merge", "Merge files into one, the way hadd does. Usage: :root file merge <output> <input> <input> ...", RootFileCommands::rootFileMerge);
        register(":root mt on", "Turn ROOT implicit multithreading on. Usage: :root mt on [threads]", RootEngineCommands::rootMtOn);
        register(":root mt off", "Turn ROOT implicit multithreading off", RootEngineCommands::rootMtOff);
        register(":root mt status", "Whether implicit multithreading is on, and the pool size", RootEngineCommands::rootMtStatus);
        register(":root pdg mass", "Mass of a particle in GeV, from the PDG table", RootPhysicsCommands::rootPdgMass);
        register(":root pdg charge", "Charge of a particle, in units of e/3", RootPhysicsCommands::rootPdgCharge);
        register(":root pdg lifetime", "Lifetime of a particle in seconds", RootPhysicsCommands::rootPdgLifetime);
        register(":root pdg class", "The family a particle belongs to", RootPhysicsCommands::rootPdgClass);
        register(":root pdg code", "PDG code of a particle named by name", RootPhysicsCommands::rootPdgCode);
        register(":root pdg name", "Name of the particle carrying a PDG code", RootPhysicsCommands::rootPdgName);
        register(":root pdg print", "Everything the PDG table holds on a particle", RootPhysicsCommands::rootPdgPrint);
        register(":root vec new", "Bind a four-vector. Usage: :root vec new <name> <px> <py> <pz> <e>", RootPhysicsCommands::rootVecNew);
        register(":root vec ptetaphim", "Bind a four-vector from collider coordinates. Usage: :root vec ptetaphim <name> <pt> <eta> <phi> <m>", RootPhysicsCommands::rootVecPtEtaPhiM);
        register(":root vec pt", "Transverse momentum of a bound four-vector", RootPhysicsCommands::rootVecPt);
        register(":root vec eta", "Pseudorapidity of a bound four-vector", RootPhysicsCommands::rootVecEta);
        register(":root vec phi", "Azimuth of a bound four-vector", RootPhysicsCommands::rootVecPhi);
        register(":root vec mass", "Invariant mass of a bound four-vector", RootPhysicsCommands::rootVecMass);
        register(":root vec energy", "Energy of a bound four-vector", RootPhysicsCommands::rootVecEnergy);
        register(":root vec p", "Momentum of a bound four-vector", RootPhysicsCommands::rootVecP);
        register(":root vec add", "Add the second four-vector into the first. Usage: :root vec add <into> <from>", RootPhysicsCommands::rootVecAdd);
        register(":root vec boost", "Boost a bound four-vector. Usage: :root vec boost <name> <bx> <by> <bz>", RootPhysicsCommands::rootVecBoost);
        register(":root vec deltar", "Angular distance between two bound four-vectors", RootPhysicsCommands::rootVecDeltaR);
        register(":root vec print", "Print a bound four-vector", RootPhysicsCommands::rootVecPrint);
        register(":root rand new", "Bind a random generator. Usage: :root rand new <name> [seed]", RootPhysicsCommands::rootRandNew);
        register(":root rand seed", "Set the seed of a bound generator. Usage: :root rand seed <name> <n>", RootPhysicsCommands::rootRandSeed);
        register(":root rand uniform", "A uniform number. Usage: :root rand uniform <name> [max]", RootPhysicsCommands::rootRandUniform);
        register(":root rand gaus", "A gaussian number. Usage: :root rand gaus <name> <mean> <sigma>", RootPhysicsCommands::rootRandGaus);
        register(":root rand poisson", "A poisson number. Usage: :root rand poisson <name> <mean>", RootPhysicsCommands::rootRandPoisson);
        register(":root rand exp", "An exponential number. Usage: :root rand exp <name> <tau>", RootPhysicsCommands::rootRandExp);
        register(":root rand landau", "A landau number. Usage: :root rand landau <name> <mpv> <sigma>", RootPhysicsCommands::rootRandLandau);
        register(":root rand integer", "An integer below a bound. Usage: :root rand integer <name> <max>", RootPhysicsCommands::rootRandInteger);
        register(":root matrix new", "Bind a matrix. Usage: :root matrix new <name> <rows> <cols>", RootPhysicsCommands::rootMatrixNew);
        register(":root matrix set", "Set one element. Usage: :root matrix set <name> <row> <col> <value>", RootPhysicsCommands::rootMatrixSet);
        register(":root matrix get", "Read one element. Usage: :root matrix get <name> <row> <col>", RootPhysicsCommands::rootMatrixGet);
        register(":root matrix print", "Print a bound matrix", RootPhysicsCommands::rootMatrixPrint);
        register(":root matrix invert", "Invert a bound matrix in place", RootPhysicsCommands::rootMatrixInvert);
        register(":root matrix det", "Determinant of a bound matrix", RootPhysicsCommands::rootMatrixDet);
        register(":root matrix unit", "Turn a bound matrix into the identity", RootPhysicsCommands::rootMatrixUnit);
        register(":root matrix transpose", "Transpose a bound matrix in place", RootPhysicsCommands::rootMatrixTranspose);
        register(":root eff new", "Create an efficiency. Usage: :root eff new <name> <bins> <low> <high>", RootHistCommands::rootEffNew);
        register(":root eff fill", "Add one trial. Usage: :root eff fill <name> <passed 0|1> <x>", RootHistCommands::rootEffFill);
        register(":root eff value", "Efficiency in one bin. Usage: :root eff value <name> <bin>", RootHistCommands::rootEffValue);
        register(":root eff errlow", "Lower error on one bin. Usage: :root eff errlow <name> <bin>", RootHistCommands::rootEffErrLow);
        register(":root eff errup", "Upper error on one bin. Usage: :root eff errup <name> <bin>", RootHistCommands::rootEffErrUp);
        register(":root eff draw", "Draw an efficiency. Usage: :root eff draw <name> [option]", RootHistCommands::rootEffDraw);
        register(":root prof new", "Create a profile. Usage: :root prof new <name> <bins> <low> <high>", RootHistCommands::rootProfNew);
        register(":root prof fill", "Add one point. Usage: :root prof fill <name> <x> <y>", RootHistCommands::rootProfFill);
        register(":root prof mean", "Mean y in one bin. Usage: :root prof mean <name> <bin>", RootHistCommands::rootProfMean);
        register(":root prof error", "Error on one bin. Usage: :root prof error <name> <bin>", RootHistCommands::rootProfError);
        register(":root prof draw", "Draw a profile. Usage: :root prof draw <name> [option]", RootHistCommands::rootProfDraw);
        register(":root prof reset", "Empty a profile. Usage: :root prof reset <name>", RootHistCommands::rootProfReset);
        register(":root stack new", "Bind a histogram stack. Usage: :root stack new <name>", RootHistCommands::rootStackNew);
        register(":root stack add", "Add a histogram to a stack. Usage: :root stack add <stack> <hist>", RootHistCommands::rootStackAdd);
        register(":root stack draw", "Draw a stack. Usage: :root stack draw <name> [option]", RootHistCommands::rootStackDraw);
        register(":root stack count", "How many histograms a stack holds", RootHistCommands::rootStackCount);
        register(":root stack max", "Largest value in a stack", RootHistCommands::rootStackMax);
        register(":root mgraph new", "Bind a multigraph. Usage: :root mgraph new <name>", RootHistCommands::rootMgraphNew);
        register(":root mgraph add", "Add a graph to a multigraph. Usage: :root mgraph add <multigraph> <graph>", RootHistCommands::rootMgraphAdd);
        register(":root mgraph draw", "Draw a multigraph. Usage: :root mgraph draw <name> [option]", RootHistCommands::rootMgraphDraw);
        register(":root mgraph fit", "Fit a multigraph. Usage: :root mgraph fit <name> <function>", RootHistCommands::rootMgraphFit);
        register(":root gerr new", "Bind a graph with errors. Usage: :root gerr new <name> <points>", RootHistCommands::rootGerrNew);
        register(":root gerr set", "Set one point. Usage: :root gerr set <name> <point> <x> <y>", RootHistCommands::rootGerrSet);
        register(":root gerr error", "Set the errors on one point. Usage: :root gerr error <name> <point> <ex> <ey>", RootHistCommands::rootGerrError);
        register(":root gerr draw", "Draw a graph with errors. Usage: :root gerr draw <name> [option]", RootHistCommands::rootGerrDraw);
        register(":root gerr fit", "Fit a graph with errors. Usage: :root gerr fit <name> <function>", RootHistCommands::rootGerrFit);
        register(":root peaks new", "Bind a peak finder. Usage: :root peaks new <name> [maximum peaks]", RootHistCommands::rootPeaksNew);
        register(":root peaks search", "Find the peaks of a histogram. Usage: :root peaks search <name> <hist> [sigma] [threshold]", RootHistCommands::rootPeaksSearch);
        register(":root peaks count", "How many peaks the last search found", RootHistCommands::rootPeaksCount);
        register(":root peaks at", "Position of one peak. Usage: :root peaks at <name> <index>", RootHistCommands::rootPeaksAt);
        register(":root peaks background", "Estimate the background of a histogram. Usage: :root peaks background <name> <hist> [iterations]", RootHistCommands::rootPeaksBackground);
        register(":root draw legend", "Bind a legend. Usage: :root draw legend <name> <x1> <y1> <x2> <y2>", RootGraphicsCommands::rootDrawLegend);
        register(":root draw entry", "Add a line to a legend. Usage: :root draw entry <legend> <object> <label>", RootGraphicsCommands::rootDrawEntry);
        register(":root draw latex", "Write a formula on the pad. Usage: :root draw latex <x> <y> <text>", RootGraphicsCommands::rootDrawLatex);
        register(":root draw text", "Write plain text on the pad. Usage: :root draw text <x> <y> <text>", RootGraphicsCommands::rootDrawText);
        register(":root draw line", "Draw a line. Usage: :root draw line <x1> <y1> <x2> <y2>", RootGraphicsCommands::rootDrawLine);
        register(":root draw arrow", "Draw an arrow. Usage: :root draw arrow <x1> <y1> <x2> <y2>", RootGraphicsCommands::rootDrawArrow);
        register(":root draw box", "Draw a box. Usage: :root draw box <x1> <y1> <x2> <y2>", RootGraphicsCommands::rootDrawBox);
        register(":root draw ellipse", "Draw an ellipse. Usage: :root draw ellipse <x> <y> <r1> <r2>", RootGraphicsCommands::rootDrawEllipse);
        register(":root draw marker", "Draw a marker. Usage: :root draw marker <x> <y> <style>", RootGraphicsCommands::rootDrawMarker);
        register(":root palette set", "Choose a color palette. Usage: :root palette set <n>", RootGraphicsCommands::rootPaletteSet);
        register(":root palette invert", "Reverse the current color palette", RootGraphicsCommands::rootPaletteInvert);
        register(":root palette grayscale", "Draw in shades of grey. Usage: :root palette grayscale <0|1>", RootGraphicsCommands::rootPaletteGrayscale);
        register(":root palette contours", "Number of contour levels. Usage: :root palette contours <n>", RootGraphicsCommands::rootPaletteContours);
        register(":root friend add", "Attach a friend tree. Usage: :root friend add <tree> <friend> <file>", RootTreeCommands::rootFriendAdd);
        register(":root friend list", "The friends of a tree. Usage: :root friend list <tree>", RootTreeCommands::rootFriendList);
        register(":root friend remove", "Detach a friend tree. Usage: :root friend remove <tree> <friend>", RootTreeCommands::rootFriendRemove);
        register(":root entrylist make", "Build an entry list from a selection. Usage: :root entrylist make <tree> <name> <selection>", RootTreeCommands::rootEntrylistMake);
        register(":root entrylist apply", "Restrict a tree to an entry list. Usage: :root entrylist apply <tree> <name>", RootTreeCommands::rootEntrylistApply);
        register(":root entrylist clear", "Let a tree see all its entries again. Usage: :root entrylist clear <tree>", RootTreeCommands::rootEntrylistClear);
        register(":root entrylist count", "How many entries a list holds. Usage: :root entrylist count <name>", RootTreeCommands::rootEntrylistCount);
        register(":root phase new", "Bind a phase space decay. Usage: :root phase new <name> <energy> <mass> <mass> ...", RootPhysicsCommands::rootPhaseNew);
        register(":root phase generate", "Throw one decay and give its weight. Usage: :root phase generate <name>", RootPhysicsCommands::rootPhaseGenerate);
        register(":root phase mass", "Mass of one decay product. Usage: :root phase mass <name> <index>", RootPhysicsCommands::rootPhaseMass);
        register(":root phase keep", "Bind one decay product as a four-vector. Usage: :root phase keep <name> <index> <vector>", RootPhysicsCommands::rootPhaseKeep);
        register(":root limit feldman", "Feldman-Cousins interval. Usage: :root limit feldman <observed> <background> [confidence]", RootPhysicsCommands::rootLimitFeldman);
        register(":root limit significance", "Asimov discovery significance. Usage: :root limit significance <signal> <background>", RootPhysicsCommands::rootLimitSignificance);
        register(":root limit pvalue", "p-value of a chi square. Usage: :root limit pvalue <chi2> <ndf>", RootPhysicsCommands::rootLimitPvalue);
        register(":root limit zvalue", "Significance in sigma for a p-value. Usage: :root limit zvalue <p>", RootPhysicsCommands::rootLimitZvalue);
        register(":root limit chi2test", "Compare two histograms. Usage: :root limit chi2test <a> <b> [option]", RootPhysicsCommands::rootLimitChi2Test);
        register(":root json object", "A named object as JSON. Usage: :root json object <name> [compact]", RootFileCommands::rootJsonObject);
        register(":root json hist", "A histogram as JSON. Usage: :root json hist <name>", RootFileCommands::rootJsonHist);
        register(":root json save", "Write an object as JSON to a file. Usage: :root json save <name> <file>", RootFileCommands::rootJsonSave);
        register(":root timer new", "Bind a stopwatch. Usage: :root timer new <name>", RootEngineCommands::rootTimerNew);
        register(":root timer start", "Start a stopwatch. Usage: :root timer start <name>", RootEngineCommands::rootTimerStart);
        register(":root timer stop", "Stop a stopwatch and give the elapsed time. Usage: :root timer stop <name>", RootEngineCommands::rootTimerStop);
        register(":root timer real", "Elapsed wall time of a stopwatch", RootEngineCommands::rootTimerReal);
        register(":root timer cpu", "Elapsed CPU time of a stopwatch", RootEngineCommands::rootTimerCpu);
        register(":root formula new", "Bind a formula. Usage: :root formula new <name> <expression>", RootHistCommands::rootFormulaNew);
        register(":root formula eval", "Evaluate a bound formula. Usage: :root formula eval <name> <x>", RootHistCommands::rootFormulaEval);
        register(":root formula params", "How many parameters a bound formula has", RootHistCommands::rootFormulaParams);
        register(":root formula f2", "Create a two-dimensional function. Usage: :root formula f2 <name> <expr> <xmin> <xmax> <ymin> <ymax>", RootHistCommands::rootFormulaF2);
        register(":root formula f3", "Create a three-dimensional function. Usage: :root formula f3 <name> <expr> <xmin> <xmax> <ymin> <ymax> <zmin> <zmax>", RootHistCommands::rootFormulaF3);
        register(":root dataloader new", "Bind a TMVA data loader. Usage: :root dataloader new <name>", RootAnalysisCommands::rootDataloaderNew);
        register(":root dataloader variable", "Add an input variable. Usage: :root dataloader variable <loader> <expression>", RootAnalysisCommands::rootDataloaderVariable);
        register(":root dataloader signal", "Use a tree as the signal sample. Usage: :root dataloader signal <loader> <tree>", RootAnalysisCommands::rootDataloaderSignal);
        register(":root dataloader background", "Use a tree as the background sample. Usage: :root dataloader background <loader> <tree>", RootAnalysisCommands::rootDataloaderBackground);
        register(":root dataloader prepare", "Split the samples into training and test. Usage: :root dataloader prepare <loader> [options]", RootAnalysisCommands::rootDataloaderPrepare);
        register(":root dataloader book", "Book a method on a factory. Usage: :root dataloader book <factory> <loader> <TMVA type> <name> [options]", RootAnalysisCommands::rootDataloaderBook);
        register(":root hist new", "Create a histogram. Usage: :root hist new <name> <bins> <low> <high>", RootHistCommands::rootHistNew);
        register(":root hist new2", "Create a two-dimensional histogram. Usage: :root hist new2 <name> <xbins> <xlow> <xhigh> <ybins> <ylow> <yhigh>", RootHistCommands::rootHistNew2);
        register(":root hist new3", "Create a three-dimensional histogram. Usage: :root hist new3 <name> <xbins> <xlow> <xhigh> <ybins> <ylow> <yhigh> <zbins> <zlow> <zhigh>", RootHistCommands::rootHistNew3);
        register(":root hist fill2", "Fill a two-dimensional histogram. Usage: :root hist fill2 <name> <x> <y> [weight]", RootHistCommands::rootHistFill2);
        register(":root hist entries", "How many entries a histogram holds", RootHistCommands::rootHistEntries);
        register(":root hist mean", "Mean of a histogram. Usage: :root hist mean <name> [axis]", RootHistCommands::rootHistMean);
        register(":root hist rms", "Standard deviation of a histogram. Usage: :root hist rms <name> [axis]", RootHistCommands::rootHistRms);
        register(":root hist content", "Content of one bin. Usage: :root hist content <name> <bin>", RootHistCommands::rootHistContent);
        register(":root hist error", "Error on one bin. Usage: :root hist error <name> <bin>", RootHistCommands::rootHistError);
        register(":root hist edge", "Lower edge of one bin. Usage: :root hist edge <name> <bin>", RootHistCommands::rootHistEdge);
        register(":root hist findbin", "Which bin a value falls in. Usage: :root hist findbin <name> <x>", RootHistCommands::rootHistFindBin);
        register(":root hist add", "Add one histogram into another. Usage: :root hist add <into> <from> [factor]", RootHistCommands::rootHistAdd);
        register(":root hist divide", "Divide one histogram by another. Usage: :root hist divide <into> <by>", RootHistCommands::rootHistDivide);
        register(":root hist multiply", "Multiply one histogram by another. Usage: :root hist multiply <into> <by>", RootHistCommands::rootHistMultiply);
        register(":root hist ks", "Kolmogorov test between two histograms. Usage: :root hist ks <a> <b>", RootHistCommands::rootHistKs);
        register(":root hist normalize", "Scale a histogram to unit area. Usage: :root hist normalize <name>", RootHistCommands::rootHistNormalize);
        register(":root hist title", "Set the title of a histogram. Usage: :root hist title <name> <text>", RootHistCommands::rootHistTitle);
        register(":root hist axis", "Title one axis. Usage: :root hist axis <name> <x|y|z> <text>", RootHistCommands::rootHistAxis);
        register(":root hist range", "Show part of an axis. Usage: :root hist range <name> <x|y|z> <low> <high>", RootHistCommands::rootHistRange);
        register(":root hist color", "Line color of a histogram. Usage: :root hist color <name> <n>", RootHistCommands::rootHistColor);
        register(":root hist save", "Write a histogram to a file. Usage: :root hist save <name> <file>", RootHistCommands::rootHistSave);
        register(":root math prob", "p-value of a chi square. Usage: :root math prob <chi2> <ndf>", RootHistCommands::rootMathProb);
        register(":root math gaus", "Gaussian value. Usage: :root math gaus <x> <mean> <sigma>", RootHistCommands::rootMathGaus);
        register(":root math landau", "Landau value. Usage: :root math landau <x> <mpv> <sigma>", RootHistCommands::rootMathLandau);
        register(":root math poisson", "Poisson value. Usage: :root math poisson <x> <mean>", RootHistCommands::rootMathPoisson);
        register(":root math binomial", "Binomial coefficient. Usage: :root math binomial <n> <k>", RootHistCommands::rootMathBinomial);
        register(":root math erf", "Error function. Usage: :root math erf <x>", RootHistCommands::rootMathErf);
        register(":root math quantile", "Normal quantile. Usage: :root math quantile <p>", RootHistCommands::rootMathQuantile);
        register(":root math list", "Mean, spread and median of a list. Usage: :root math list <v> <v> ...", RootHistCommands::rootMathList);
        register(":root rdf define", "Add a computed column. Usage: :root rdf define <name> <column> <expression>", RootTreeCommands::rootRdfDefine);
        register(":root rdf redefine", "Replace an existing column. Usage: :root rdf redefine <name> <column> <expression>", RootTreeCommands::rootRdfRedefine);
        register(":root rdf alias", "Give a column a second name. Usage: :root rdf alias <name> <alias> <column>", RootTreeCommands::rootRdfAlias);
        register(":root rdf range", "Keep only a slice of the entries. Usage: :root rdf range <name> <begin> [end]", RootTreeCommands::rootRdfRange);
        register(":root rdf cache", "Hold the frame in memory. Usage: :root rdf cache <name> [column ...]", RootTreeCommands::rootRdfCache);
        register(":root rdf sum", "Sum of a column. Usage: :root rdf sum <name> <column>", RootTreeCommands::rootRdfSum);
        register(":root rdf mean", "Mean of a column. Usage: :root rdf mean <name> <column>", RootTreeCommands::rootRdfMean);
        register(":root rdf min", "Smallest value of a column. Usage: :root rdf min <name> <column>", RootTreeCommands::rootRdfMin);
        register(":root rdf max", "Largest value of a column. Usage: :root rdf max <name> <column>", RootTreeCommands::rootRdfMax);
        register(":root rdf stddev", "Standard deviation of a column. Usage: :root rdf stddev <name> <column>", RootTreeCommands::rootRdfStdDev);
        register(":root rdf histo", "Fill a histogram from a column. Usage: :root rdf histo <name> <hist> <bins> <low> <high> <column>", RootTreeCommands::rootRdfHisto);
        register(":root rdf histo2", "Fill a two-dimensional histogram. Usage: :root rdf histo2 <name> <hist> <xbins> <xlow> <xhigh> <ybins> <ylow> <yhigh> <xcolumn> <ycolumn>", RootTreeCommands::rootRdfHisto2);
        register(":root rdf profile", "Fill a profile from two columns. Usage: :root rdf profile <name> <profile> <bins> <low> <high> <xcolumn> <ycolumn>", RootTreeCommands::rootRdfProfile);
        register(":root rdf snapshot", "Write the frame to a new file. Usage: :root rdf snapshot <name> <tree> <file> [column ...]", RootTreeCommands::rootRdfSnapshot);
        register(":root rdf display", "Print the first entries. Usage: :root rdf display <name> [rows] [column ...]", RootTreeCommands::rootRdfDisplay);
        register(":root rdf report", "How many entries each named filter kept. Usage: :root rdf report <name>", RootTreeCommands::rootRdfReport);
        register(":root rdf columns", "The columns the frame carries. Usage: :root rdf columns <name>", RootTreeCommands::rootRdfColumns);
        register(":root rdf type", "The type of one column. Usage: :root rdf type <name> <column>", RootTreeCommands::rootRdfType);
        register(":root rdf describe", "Everything the frame knows about itself. Usage: :root rdf describe <name>", RootTreeCommands::rootRdfDescribe);

        // --- ROOT Framework Bridge — Histograms ---
        register(":root hist get", "Extract histogram from file handle. Usage: :root hist get <name>", RootHistCommands::rootGetHist);
        register(":root hist bins", "Dump bin contents of a TH1 histogram handle. Usage: :root hist bins <name>", RootHistCommands::rootDumpHistBins);
        register(":root hist reset", "Reset bin contents and stats of a histogram handle", RootHistCommands::rootHistReset);
        register(":root hist rebin", "Rebin x-axis channels of a histogram handle. Usage: :root hist rebin <name> <ngroup>", RootHistCommands::rootHistRebin);
        register(":root hist scale", "Scale histogram entries by a numeric factor. Usage: :root hist scale <name> <factor>", RootHistCommands::rootHistScale);
        register(":root hist draw", "Render a visual plot of a histogram handle. Usage: :root hist draw <name> [opt]", RootHistCommands::rootHistDraw);
        register(":root hist fit", "Fit a formula or TF1 function to a histogram. Usage: :root hist fit <name> <formula>", RootHistCommands::rootHistFit);
        register(":root hist integral", "Calculate integral of a histogram handle. Usage: :root hist integral <name>", RootHistCommands::rootHistIntegral);
        register(":root hist max", "Retrieve maximum bin value from histogram handle", RootHistCommands::rootHistMax);
        register(":root hist min", "Retrieve minimum bin value from histogram handle", RootHistCommands::rootHistMin);
        register(":root hist list", "List all histogram handles registered in bridge memory", RootHistCommands::rootHistList);
        register(":root hist smooth", "Smooth bin contents of a histogram handle", RootHistCommands::rootHistSmooth);
        register(":root hist project", "Project 2D/3D histogram to 1D axis handle", RootHistCommands::rootHistProject);
        register(":root hist statbox", "Toggle or configure stats box display on histogram", RootHistCommands::rootHistStatbox);
        register(":root hist setbin", "Set one bin. Usage: :root hist setbin <name> <bin> <value>", RootHistCommands::rootHistSetbin);
        register(":root hist fill", "Fill a histogram. Usage: :root hist fill <name> <value> [weight]", RootHistCommands::rootHistFill);
        register(":root hist clone", "Clone an existing histogram handle into memory", RootHistCommands::rootHistClone);

        // --- ROOT Framework Bridge — Generic Objects ---
        register(":root obj get", "Retrieve generic object handle from file. Usage: :root obj get <name>", RootEngineCommands::rootGetObject);
        register(":root obj dump", "Dump raw object data layout. Usage: :root obj dump <name>", RootEngineCommands::rootDumpObject);
        register(":root obj describe", "Inspect member methods and structural properties of an object handle", RootEngineCommands::rootDescribeObject);
        register(":root obj clone", "Duplicate an active object handle in memory", RootEngineCommands::rootObjClone);
        register(":root obj write", "Write object handle to active output file", RootEngineCommands::rootObjWrite);
        register(":root obj delete", "Delete target object handle from memory", RootEngineCommands::rootObjDelete);
        register(":root obj methods", "List exposed C++ methods on target object handle", RootEngineCommands::rootObjMethods);
        register(":root obj members", "List data members of target object handle", RootEngineCommands::rootObjMembers);
        register(":root obj list", "List all generic object handles active in memory", RootEngineCommands::rootObjList);
        register(":root obj class", "Print native C++ class name of target object handle", RootEngineCommands::rootObjClass);
        register(":root obj type", "Print structural type definition of object handle", RootEngineCommands::rootObjType);
        register(":root obj print", "Invoke native Print() method on object handle", RootEngineCommands::rootObjPrint);
        register(":root obj inspect", "Open detailed inspector on object handle attributes", RootEngineCommands::rootObjInspect);

        // --- ROOT Framework Bridge — TTree & TChain Data Processing ---
        register(":root tree print", "Print structure and branch metadata for TTree handle. Usage: :root tree print <tree_id>", RootTreeCommands::rootTreePrint);
        register(":root tree scan", "Scan and print values of tree branches for selected entries. Usage: :root tree scan <tree_id> [expr]", RootTreeCommands::rootTreeScan);
        register(":root tree draw", "Draw variable or branch expression from a TTree handle. Usage: :root tree draw <tree_id> <expr> [cut]", RootTreeCommands::rootTreeDraw);
        register(":root tree process", "Execute C++ macro selector on a TTree dataset", RootTreeCommands::rootTreeProcess);
        register(":root tree project", "Project into a histogram. Usage: :root tree project <tree> <hist> <expr> [selection]", RootTreeCommands::rootTreeProject);
        register(":root tree entries", "Retrieve total entry count from a TTree handle", RootTreeCommands::rootTreeEntries);
        register(":root tree branches", "List all branch names for a TTree handle", RootTreeCommands::rootTreeBranches);
        register(":root tree leaves", "List all leaf data names for a TTree handle", RootTreeCommands::rootTreeLeaves);
        register(":root tree getentry", "Read single entry record into TTree buffer memory", RootTreeCommands::rootTreeGetentry);
        register(":root tree copytree", "Create sub-tree copy filtered by selection criteria", RootTreeCommands::rootTreeCopytree);

        // --- ROOT Framework Bridge — Graphs & Graphics ---
        register(":root graph draw", "Render visual representation of a TGraph object handle", RootHistCommands::rootGraphDraw);
        register(":root graph fit", "Fit function model to TGraph data points", RootHistCommands::rootGraphFit);
        register(":root graph points", "Print data point coordinates for a TGraph handle", RootHistCommands::rootGraphPoints);
        register(":root graph add", "Set one point of a TGraph. Usage: :root graph add <name> <point> <x> <y>", RootHistCommands::rootGraphAdd);
        register(":root canvas new", "Create new visual TCanvas context window", RootGraphicsCommands::rootCanvasNew);
        register(":root canvas cd", "Focus active pad within current TCanvas window", RootGraphicsCommands::rootCanvasCd);
        register(":root canvas save", "Export active canvas to image file. Usage: :root canvas save <filename.png>", RootGraphicsCommands::rootCanvasSave);
        register(":root canvas clear", "Clear contents of active TCanvas visual context", RootGraphicsCommands::rootCanvasClear);
        register(":root canvas update", "Update and repaint display buffer for active canvas", RootGraphicsCommands::rootCanvasUpdate);
        register(":root canvas list", "List all open TCanvas visual window handles", RootGraphicsCommands::rootCanvasList);

        // --- ROOT Framework Bridge — Math, Fitting & Statistics ---
        register(":root fit expr", "Fit user-defined math formula to dataset handle", RootHistCommands::rootFitExpr);
        register(":root fit function", "Fit TF1 function object to active dataset handle", RootHistCommands::rootFitFunction);
        register(":root fit reset", "Drop the fits drawn on a histogram. Usage: :root fit reset <hist>", RootHistCommands::rootFitReset);
        register(":root fit params", "Print resulting fit parameters and parameter errors", RootHistCommands::rootFitParams);
        register(":root math eval", "Evaluate mathematical function or formula expression", RootHistCommands::rootMathEval);
        register(":root math deriv", "Calculate numerical derivative of function handle", RootHistCommands::rootMathDeriv);
        register(":root math integral", "Definite integral of a TF1. Usage: :root math integral <name> <from> <to>", RootHistCommands::rootMathIntegral);

        // --- Advanced ROOT Modules — Machine Learning & Statistics ---
        register(":root tmva factory", "Bind a TMVA factory to a name. Usage: :root tmva factory <name> [options]", RootAnalysisCommands::rootTmvaFactory);
        register(":root tmva train", "Train the methods of a bound factory. Usage: :root tmva train <factory>", RootAnalysisCommands::rootTmvaTrain);
        register(":root tmva test", "Test the methods of a bound factory. Usage: :root tmva test <factory>", RootAnalysisCommands::rootTmvaTest);
        register(":root tmva evaluate", "Evaluate the methods of a bound factory. Usage: :root tmva evaluate <factory>", RootAnalysisCommands::rootTmvaEvaluate);
        register(":root tmva gui", "Open interactive TMVA results visualization GUI", RootAnalysisCommands::rootTmvaGui);
        register(":root roofit workspace", "Bind a RooWorkspace to a name. Usage: :root roofit workspace <name>", RootAnalysisCommands::rootRoofitWorkspace);
        register(":root roofit pdf", "Build in a bound workspace. Usage: :root roofit pdf <workspace> <expr>", RootAnalysisCommands::rootRoofitPdf);
        register(":root roofit fit", "Fit a pdf to a dataset. Usage: :root roofit fit <workspace> <pdf> <data>", RootAnalysisCommands::rootRoofitFit);
        register(":root roofit plot", "Draw a workspace variable. Usage: :root roofit plot <workspace> <var>", RootAnalysisCommands::rootRoofitPlot);

        // --- Advanced ROOT Modules — Geometry, SQL, Proof & Distributed ---
        register(":root geom load", "Load 3D geometry file into TGeoManager", RootGraphicsCommands::rootGeomLoad);
        register(":root geom draw", "Render 3D detector or volume geometry scene", RootGraphicsCommands::rootGeomDraw);
        register(":root geom export", "Export loaded 3D geometry model to external format", RootGraphicsCommands::rootGeomExport);
        register(":root sql connect", "Bind a database connection. Usage: :root sql connect <name> <url> [user] [password]", RootFileCommands::rootSqlConnect);
        register(":root sql query", "Query a bound connection. Usage: :root sql query <name> <sql>", RootFileCommands::rootSqlQuery);
        register(":root sql disconnect", "Close a bound connection and drop the name. Usage: :root sql disconnect <name>", RootFileCommands::rootSqlDisconnect);
        register(":root net server", "Bind a listening socket. Usage: :root net server <name> <port>", RootFileCommands::rootNetServer);
        register(":root net connect", "Bind a client socket. Usage: :root net connect <name> <host> <port>", RootFileCommands::rootNetConnect);
        register(":root net send", "Send over a bound socket. Usage: :root net send <name> <text>", RootFileCommands::rootNetSend);
        register(":root proof open", "Initialize PROOF parallel processing cluster session", RootAnalysisCommands::rootProofOpen);
        register(":root proof process", "Run distributed dataset processing task on PROOF cluster", RootAnalysisCommands::rootProofProcess);
        register(":root proof status", "Display runtime status and node activity for PROOF session", RootAnalysisCommands::rootProofStatus);

        // --- Advanced ROOT Modules — GUI, PyROOT & System ---
        register(":root gui new", "Bind a GUI window to a name. Usage: :root gui new <name>", RootGraphicsCommands::rootGuiNew);
        register(":root gui show", "Show a bound window. Usage: :root gui show <name>", RootGraphicsCommands::rootGuiShow);
        register(":root gui close", "Close a bound window and drop the name. Usage: :root gui close <name>", RootGraphicsCommands::rootGuiClose);
        register(":root py import", "Import Python module inside ROOT C++ interpreter engine", RootAnalysisCommands::rootPyImport);
        register(":root py eval", "Evaluate Python script expression via PyROOT bridge", RootAnalysisCommands::rootPyEval);
        register(":root py exec", "Execute raw Python code block inside ROOT context", RootAnalysisCommands::rootPyExec);
        register(":root sys info", "Display host system resource and ROOT configuration status", RootEngineCommands::rootSysInfo);
        register(":root sys memory", "Resident and virtual size of the engine process", RootEngineCommands::rootSysMemory);
        register(":root sys plugins", "List loaded dynamic ROOT plugin handlers and libraries", RootEngineCommands::rootSysPlugins);

        // --- ROOT Bridge Maintenance, Diagnostics & Profiling ---
        register(":root status", "Print status summary of active ROOT subsystem handles", RootEngineCommands::rootStatus);
        register(":root reset", "Reset ROOT session state and clear bridge memory handles", RootEngineCommands::rootReset);
        register(":root dump", "Dump summary of all active ROOT objects registered in session", RootEngineCommands::rootDump);
        register(":root diag", "Same report as :root stats, kept under its older name", RootEngineCommands::rootDiag);
        register(":root profile", "Engine figures, the same report as :root stats", RootEngineCommands::rootProfile);
        register(":root profile stats", "Engine figures, the same report as :root stats", RootEngineCommands::rootProfileStats);
        register(":root profile json", "The same report as JSON", RootEngineCommands::rootProfileJson);
        register(":root profile reset", "Clears the engine counters and the error latch", RootEngineCommands::rootProfileReset);
        register(":root profile level", "ROOT verbosity. Usage: :root profile level <n>", RootEngineCommands::rootProfileLevel);
        
        // --- ROOT Framework Bridge — Debug & Diagnostics ---
        register(":root debug dump", "Dump full debug snapshot of backend internal state", RootEngineCommands::rootDebugDump);
        register(":root debug graphviz", "Generate Graphviz DOT representation of active handle graph", RootEngineCommands::rootDebugGraphviz);
        register(":root debug audit", "Audit ROOT ecosystem and detect orphan memory handles", RootEngineCommands::rootDebugAudit);
        register(":root debug level", "ROOT verbosity. Usage: :root debug level <n>", RootEngineCommands::rootDebugLevel);
        // --- ROOT Framework Bridge — Profiling & System Diagnostics ---
        register(":root profiling status", "Engine figures, the same report as :root stats", RootEngineCommands::rootProfilingStatus);
        register(":root profiling json", "The same report as JSON", RootEngineCommands::rootProfilingJson);
        register(":root profiling reset", "Clears the engine counters and the error latch", RootEngineCommands::rootProfilingReset);
        register(":root profiling level", "ROOT verbosity. Usage: :root profiling level <n>", RootEngineCommands::rootProfilingLevel);
        register(":root profile threshold", "ROOT verbosity. Usage: :root profile threshold <n>", RootEngineCommands::rootProfileThreshold);

        // --- ROOT Framework Bridge — Watchdog Diagnostics & Control ---
        register(":root watchdog status", "Engine liveness: state, heartbeats, jobs, latency", RootEngineCommands::rootWatchdogStatus);
        register(":root watchdog kill", "Clears the engine counters and the error latch", RootEngineCommands::rootWatchdogKill);
        // --- ROOT::Math GenVector, the vectors that are not legacy ---
        register(":root gv new", "Bind a vector from cartesian components. Usage: :root gv new <name> <px> <py> <pz> <e>", RootPhysicsCommands::rootGvNew);
        register(":root gv ptetaphim", "Bind a vector from collider coordinates. Usage: :root gv ptetaphim <name> <pt> <eta> <phi> <m>", RootPhysicsCommands::rootGvPtEtaPhiM);
        register(":root gv ptetaphie", "Bind a vector given its energy rather than its mass. Usage: :root gv ptetaphie <name> <pt> <eta> <phi> <e>", RootPhysicsCommands::rootGvPtEtaPhiE);
        register(":root gv pt", "Transverse momentum of a bound vector. Usage: :root gv pt <name>", RootPhysicsCommands::rootGvPt);
        register(":root gv eta", "Pseudorapidity of a bound vector. Usage: :root gv eta <name>", RootPhysicsCommands::rootGvEta);
        register(":root gv phi", "Azimuth of a bound vector. Usage: :root gv phi <name>", RootPhysicsCommands::rootGvPhi);
        register(":root gv mass", "Invariant mass of a bound vector. Usage: :root gv mass <name>", RootPhysicsCommands::rootGvM);
        register(":root gv energy", "Energy of a bound vector. Usage: :root gv energy <name>", RootPhysicsCommands::rootGvE);
        register(":root gv p", "Momentum of a bound vector. Usage: :root gv p <name>", RootPhysicsCommands::rootGvP);
        register(":root gv px", "x component of a bound vector. Usage: :root gv px <name>", RootPhysicsCommands::rootGvPx);
        register(":root gv py", "y component of a bound vector. Usage: :root gv py <name>", RootPhysicsCommands::rootGvPy);
        register(":root gv pz", "z component of a bound vector. Usage: :root gv pz <name>", RootPhysicsCommands::rootGvPz);
        register(":root gv rapidity", "Rapidity of a bound vector. Usage: :root gv rapidity <name>", RootPhysicsCommands::rootGvRapidity);
        register(":root gv mt", "Transverse mass of a bound vector. Usage: :root gv mt <name>", RootPhysicsCommands::rootGvMt);
        register(":root gv et", "Transverse energy of a bound vector. Usage: :root gv et <name>", RootPhysicsCommands::rootGvEt);
        register(":root gv perp2", "Squared transverse momentum of a bound vector. Usage: :root gv perp2 <name>", RootPhysicsCommands::rootGvPerp2);
        register(":root gv add", "Add the second vector into the first. Usage: :root gv add <into> <from>", RootPhysicsCommands::rootGvAdd);
        register(":root gv scale", "Multiply a bound vector by a factor. Usage: :root gv scale <name> <factor>", RootPhysicsCommands::rootGvScale);
        register(":root gv boost", "Boost a bound vector. Usage: :root gv boost <name> <bx> <by> <bz>", RootPhysicsCommands::rootGvBoost);
        register(":root gv deltar", "Angular distance between two bound vectors. Usage: :root gv deltar <a> <b>", RootPhysicsCommands::rootGvDeltaR);
        register(":root gv deltaphi", "Azimuthal distance between two bound vectors. Usage: :root gv deltaphi <a> <b>", RootPhysicsCommands::rootGvDeltaPhi);
        register(":root gv deltaeta", "Difference in pseudorapidity. Usage: :root gv deltaeta <a> <b>", RootPhysicsCommands::rootGvDeltaEta);
        register(":root gv angle", "Opening angle between two bound vectors. Usage: :root gv angle <a> <b>", RootPhysicsCommands::rootGvAngle);
        register(":root gv invmass", "Invariant mass of a pair of bound vectors. Usage: :root gv invmass <a> <b>", RootPhysicsCommands::rootGvInvMass);
        register(":root gv costheta", "Cosine of the angle between two bound vectors. Usage: :root gv costheta <a> <b>", RootPhysicsCommands::rootGvCosTheta);
        register(":root gv print", "The four components of a bound vector. Usage: :root gv print <name>", RootPhysicsCommands::rootGvPrint);
        // --- TTreeReader, the selector generators, and writing an RNTuple ---
        register(":root ntuple from-tree", "Convert a TTree into an RNTuple. Usage: :root ntuple from-tree <file> <tree> <output> <ntuple>", RootTreeCommands::rootNtupleFromTree);
        register(":root reader new", "Bind a reader to a tree. Usage: :root reader new <name> <tree>", RootTreeCommands::rootReaderNew);
        register(":root reader next", "Move to the next entry. Usage: :root reader next <name>", RootTreeCommands::rootReaderNext);
        register(":root reader restart", "Go back to the first entry. Usage: :root reader restart <name>", RootTreeCommands::rootReaderRestart);
        register(":root reader entry", "Which entry the reader is on. Usage: :root reader entry <name>", RootTreeCommands::rootReaderEntry);
        register(":root reader entries", "How many entries the reader can see. Usage: :root reader entries <name>", RootTreeCommands::rootReaderEntries);
        register(":root reader value", "Bind one scalar branch of a reader. Usage: :root reader value <reader> <name> <type> <branch>", RootTreeCommands::rootReaderValue);
        register(":root reader read", "The value of a bound branch on the current entry. Usage: :root reader read <name> <type>", RootTreeCommands::rootReaderRead);
        register(":root reader array", "Bind one array branch of a reader. Usage: :root reader array <reader> <name> <type> <branch>", RootTreeCommands::rootReaderArray);
        register(":root reader size", "How many values a bound array holds this entry. Usage: :root reader size <name> <type>", RootTreeCommands::rootReaderSize);
        register(":root reader at", "One value of a bound array. Usage: :root reader at <name> <type> <index>", RootTreeCommands::rootReaderAt);
        register(":root selector make", "Write a TSelector skeleton for a tree. Usage: :root selector make <tree> <name>", RootTreeCommands::rootSelectorMake);
        register(":root selector class", "Write an analysis class for a tree. Usage: :root selector class <tree> <name>", RootTreeCommands::rootSelectorClass);
        register(":root selector code", "Write a skeleton macro for a tree. Usage: :root selector code <tree> <name>", RootTreeCommands::rootSelectorCode);
        register(":root rdf take", "Read a column into memory. Usage: :root rdf take <name> <column> <type>", RootTreeCommands::rootRdfTake);
        register(":root rdf vary", "Declare systematic variations of a column. Usage: :root rdf vary <name> <column> <expression> <tag> [tag ...]", RootTreeCommands::rootRdfVary);
        register(":root rdf varied", "Fill the nominal histogram and one per variation. Usage: :root rdf varied <name> <hist> <bins> <low> <high> <column>", RootTreeCommands::rootRdfVaried);
        // --- RooStats, sparse histograms, splines, density estimation, principal components, unfolding, decompositions, integrators, interpolators, FFT, XML and compression ---
        register(":root roostats model", "Bind a statistical model to a workspace. Usage: :root roostats model <name> <workspace>", RootAnalysisCommands::rootRoostatsModel);
        register(":root roostats pdf", "Name the likelihood of a model. Usage: :root roostats pdf <model> <workspace> <pdf>", RootAnalysisCommands::rootRoostatsPdf);
        register(":root roostats poi", "Name the parameter being measured. Usage: :root roostats poi <model> <workspace> <variable>", RootAnalysisCommands::rootRoostatsPoi);
        register(":root roostats observables", "Name what was measured. Usage: :root roostats observables <model> <workspace> <variable>", RootAnalysisCommands::rootRoostatsObservables);
        register(":root roostats nuisance", "Name a nuisance parameter. Usage: :root roostats nuisance <model> <workspace> <variable>", RootAnalysisCommands::rootRoostatsNuisance);
        register(":root roostats interval", "Profile likelihood interval. Usage: :root roostats interval <model> <workspace> <data> [confidence]", RootAnalysisCommands::rootRoostatsInterval);
        register(":root roostats significance", "Discovery significance of a model. Usage: :root roostats significance <model> <workspace> <data>", RootAnalysisCommands::rootRoostatsSignificance);
        register(":root roostats asymptotic", "Asymptotic hypothesis test. Usage: :root roostats asymptotic <alternate> <null> <workspace> <data>", RootAnalysisCommands::rootRoostatsAsymptotic);
        register(":root sparse new", "Bind a sparse histogram, the same range on every axis. Usage: :root sparse new <name> <dimensions> <bins> <low> <high>", RootHistCommands::rootSparseNew);
        register(":root sparse fill", "Add one point. Usage: :root sparse fill <name> <x> <x> ...", RootHistCommands::rootSparseFill);
        register(":root sparse bins", "How many filled bins a sparse histogram holds. Usage: :root sparse bins <name>", RootHistCommands::rootSparseBins);
        register(":root sparse entries", "How many entries a sparse histogram holds. Usage: :root sparse entries <name>", RootHistCommands::rootSparseEntries);
        register(":root sparse project", "Project one axis into a histogram. Usage: :root sparse project <name> <axis> <hist>", RootHistCommands::rootSparseProject);
        register(":root spline new", "Bind a cubic spline through a histogram. Usage: :root spline new <name> <hist>", RootHistCommands::rootSplineNew);
        register(":root spline eval", "Value of a bound spline. Usage: :root spline eval <name> <x>", RootHistCommands::rootSplineEval);
        register(":root spline derivative", "Slope of a bound spline. Usage: :root spline derivative <name> <x>", RootHistCommands::rootSplineDerivative);
        register(":root spline draw", "Draw a bound spline. Usage: :root spline draw <name> [option]", RootHistCommands::rootSplineDraw);
        register(":root kde new", "Bind a kernel density estimate over a list of values. Usage: :root kde new <name> <value> <value> ...", RootHistCommands::rootKdeNew);
        register(":root kde eval", "Density at a point. Usage: :root kde eval <name> <x>", RootHistCommands::rootKdeEval);
        register(":root kde integral", "Integral of the density between two points. Usage: :root kde integral <name> <from> <to>", RootHistCommands::rootKdeIntegral);
        register(":root kde draw", "Draw a bound density. Usage: :root kde draw <name> [option]", RootHistCommands::rootKdeDraw);
        register(":root pca new", "Bind a principal component analysis. Usage: :root pca new <name> <dimensions>", RootPhysicsCommands::rootPcaNew);
        register(":root pca add", "Add one row of data. Usage: :root pca add <name> <x> <x> ...", RootPhysicsCommands::rootPcaAdd);
        register(":root pca make", "Compute the components. Usage: :root pca make <name>", RootPhysicsCommands::rootPcaMake);
        register(":root pca print", "Report the analysis. Usage: :root pca print <name> [option]", RootPhysicsCommands::rootPcaPrint);
        register(":root pca eigen", "The eigenvalues. Usage: :root pca eigen <name>", RootPhysicsCommands::rootPcaEigen);
        register(":root pca covariance", "The covariance matrix. Usage: :root pca covariance <name>", RootPhysicsCommands::rootPcaCovariance);
        register(":root unfold new", "Bind an SVD unfolding. Usage: :root unfold new <name> <measured> <reconstructed> <truth> <response>", RootHistCommands::rootUnfoldNew);
        register(":root unfold run", "Unfold with a regularisation. Usage: :root unfold run <name> <k> <hist>", RootHistCommands::rootUnfoldRun);
        register(":root unfold dvector", "The d vector, which says where to cut the regularisation. Usage: :root unfold dvector <name>", RootHistCommands::rootUnfoldDVector);
        register(":root unfold singular", "The singular values of the response. Usage: :root unfold singular <name>", RootHistCommands::rootUnfoldSingular);
        register(":root decomp svd", "Singular values of a bound matrix. Usage: :root decomp svd <matrix>", RootPhysicsCommands::rootDecompSvd);
        register(":root decomp lu", "LU decomposition of a bound matrix. Usage: :root decomp lu <matrix>", RootPhysicsCommands::rootDecompLu);
        register(":root decomp chol", "Cholesky decomposition of a bound matrix. Usage: :root decomp chol <matrix>", RootPhysicsCommands::rootDecompChol);
        register(":root decomp qr", "QR decomposition of a bound matrix. Usage: :root decomp qr <matrix>", RootPhysicsCommands::rootDecompQr);
        register(":root decomp solve", "Solve a linear system. Usage: :root decomp solve <matrix> <b> <b> ...", RootPhysicsCommands::rootDecompSolve);
        register(":root integrate expr", "Integrate an expression. Usage: :root integrate expr <from> <to> <expression>", RootPhysicsCommands::rootIntegrateExpr);
        register(":root integrate adaptive", "Integrate an expression with the adaptive integrator. Usage: :root integrate adaptive <from> <to> <expression>", RootPhysicsCommands::rootIntegrateAdaptive);
        register(":root interp new", "Bind an interpolation through points. Usage: :root interp new <name> <x,y> <x,y> ...", RootPhysicsCommands::rootInterpNew);
        register(":root interp eval", "Interpolated value. Usage: :root interp eval <name> <x>", RootPhysicsCommands::rootInterpEval);
        register(":root interp derivative", "Interpolated slope. Usage: :root interp derivative <name> <x>", RootPhysicsCommands::rootInterpDerivative);
        register(":root interp integral", "Integral of the interpolation. Usage: :root interp integral <name> <from> <to>", RootPhysicsCommands::rootInterpIntegral);
        register(":root fft magnitude", "Magnitude of the transform of a histogram. Usage: :root fft magnitude <hist> <output>", RootHistCommands::rootFftMagnitude);
        register(":root fft phase", "Phase of the transform of a histogram. Usage: :root fft phase <hist> <output>", RootHistCommands::rootFftPhase);
        register(":root fft real", "Real part of the transform. Usage: :root fft real <hist> <output>", RootHistCommands::rootFftReal);
        register(":root fft imaginary", "Imaginary part of the transform. Usage: :root fft imaginary <hist> <output>", RootHistCommands::rootFftImaginary);
        register(":root xml write", "Write a named object to an XML file. Usage: :root xml write <file> <object>", RootFileCommands::rootXmlWrite);
        register(":root xml keys", "What an XML file holds. Usage: :root xml keys <file>", RootFileCommands::rootXmlKeys);
        register(":root xml get", "Read an object back out of an XML file. Usage: :root xml get <file> <object>", RootFileCommands::rootXmlGet);
        register(":root compress level", "Compression level of the current file, 0 to 9. Usage: :root compress level <n>", RootFileCommands::rootCompressLevel);
        register(":root compress algorithm", "Compression algorithm of the current file. Usage: :root compress algorithm <1 zlib|2 lzma|4 lz4|5 zstd>", RootFileCommands::rootCompressAlgorithm);
        register(":root compress show", "How the current file is compressed", RootFileCommands::rootCompressShow);
        register(":root compress factor", "How much smaller the current file is than its contents", RootFileCommands::rootCompressFactor);
        // --- reading fits back, TF1 parameters, applying a TMVA model, canvas layout and output, geometry, RooFit datasets and results, tree caches and indices, the rest of RDataFrame, the host system, regular expressions, file internals and more of TMath ---
        register(":root fitres run", "Fit and keep the result. Usage: :root fitres run <name> <hist> <function> [options]", RootHistCommands::rootFitresRun);
        register(":root fitres chi2", "Chi square of a kept fit. Usage: :root fitres chi2 <name>", RootHistCommands::rootFitresChi2);
        register(":root fitres ndf", "Degrees of freedom of a kept fit. Usage: :root fitres ndf <name>", RootHistCommands::rootFitresNdf);
        register(":root fitres prob", "Fit probability. Usage: :root fitres prob <name>", RootHistCommands::rootFitresProb);
        register(":root fitres param", "One fitted parameter. Usage: :root fitres param <name> <index>", RootHistCommands::rootFitresParam);
        register(":root fitres error", "Error on one fitted parameter. Usage: :root fitres error <name> <index>", RootHistCommands::rootFitresError);
        register(":root fitres pname", "Name of one fitted parameter. Usage: :root fitres pname <name> <index>", RootHistCommands::rootFitresPname);
        register(":root fitres correlation", "Correlation between two parameters. Usage: :root fitres correlation <name> <i> <j>", RootHistCommands::rootFitresCorrelation);
        register(":root fitres covariance", "The covariance matrix of a kept fit. Usage: :root fitres covariance <name>", RootHistCommands::rootFitresCovariance);
        register(":root fitres status", "What the minimiser returned. Usage: :root fitres status <name>", RootHistCommands::rootFitresStatus);
        register(":root fitres valid", "Whether the fit converged. Usage: :root fitres valid <name>", RootHistCommands::rootFitresValid);
        register(":root fitres print", "Everything about a kept fit. Usage: :root fitres print <name>", RootHistCommands::rootFitresPrint);
        register(":root func set", "Set one parameter. Usage: :root func set <function> <index> <value>", RootHistCommands::rootFuncSet);
        register(":root func get", "Read one parameter. Usage: :root func get <function> <index>", RootHistCommands::rootFuncGet);
        register(":root func perror", "Error on one parameter. Usage: :root func perror <function> <index>", RootHistCommands::rootFuncPerror);
        register(":root func pname", "Name of one parameter. Usage: :root func pname <function> <index>", RootHistCommands::rootFuncPname);
        register(":root func rename", "Name one parameter. Usage: :root func rename <function> <index> <text>", RootHistCommands::rootFuncRename);
        register(":root func limits", "Bound one parameter. Usage: :root func limits <function> <index> <low> <high>", RootHistCommands::rootFuncLimits);
        register(":root func fix", "Hold one parameter at a value. Usage: :root func fix <function> <index> <value>", RootHistCommands::rootFuncFix);
        register(":root func release", "Let a held parameter float again. Usage: :root func release <function> <index>", RootHistCommands::rootFuncRelease);
        register(":root func npar", "How many parameters. Usage: :root func npar <function>", RootHistCommands::rootFuncNpar);
        register(":root func chi2", "Chi square of the last fit. Usage: :root func chi2 <function>", RootHistCommands::rootFuncChi2);
        register(":root func ndf", "Degrees of freedom of the last fit. Usage: :root func ndf <function>", RootHistCommands::rootFuncNdf);
        register(":root func maximum", "Largest value over a range. Usage: :root func maximum <function> <from> <to>", RootHistCommands::rootFuncMaximum);
        register(":root func maxx", "Where the function peaks. Usage: :root func maxx <function> <from> <to>", RootHistCommands::rootFuncMaxX);
        register(":root func minimum", "Smallest value over a range. Usage: :root func minimum <function> <from> <to>", RootHistCommands::rootFuncMinimum);
        register(":root func solve", "The x where the function reaches a value. Usage: :root func solve <function> <y> <from> <to>", RootHistCommands::rootFuncSolve);
        register(":root func mean", "Mean of the function over a range. Usage: :root func mean <function> <from> <to>", RootHistCommands::rootFuncMean);
        register(":root func variance", "Variance of the function over a range. Usage: :root func variance <function> <from> <to>", RootHistCommands::rootFuncVariance);
        register(":root func moment", "A moment of the function. Usage: :root func moment <function> <order> <from> <to>", RootHistCommands::rootFuncMoment);
        register(":root func range", "Set the range a function is defined on. Usage: :root func range <function> <from> <to>", RootHistCommands::rootFuncRange);
        register(":root func points", "How finely a function is drawn. Usage: :root func points <function> <n>", RootHistCommands::rootFuncPoints);
        register(":root func draw", "Draw a function. Usage: :root func draw <function> [option]", RootHistCommands::rootFuncDraw);
        register(":root tmva reader", "Bind a reader that applies a trained model. Usage: :root tmva reader <name> [options]", RootAnalysisCommands::rootTmvaReader);
        register(":root tmva input", "Declare an input of a reader, bound to a slot you can feed. Usage: :root tmva input <reader> <slot> <expression>", RootAnalysisCommands::rootTmvaInput);
        register(":root tmva feed", "Put a value in an input slot. Usage: :root tmva feed <slot> <value>", RootAnalysisCommands::rootTmvaFeed);
        register(":root tmva load", "Load a trained method into a reader. Usage: :root tmva load <reader> <method> <weight file>", RootAnalysisCommands::rootTmvaLoad);
        register(":root tmva apply", "The answer of a loaded method for the fed inputs. Usage: :root tmva apply <reader> <method>", RootAnalysisCommands::rootTmvaApply);
        register(":root canvas divide", "Split the pad into cells. Usage: :root canvas divide <columns> <rows>", RootGraphicsCommands::rootCanvasDivide);
        register(":root canvas logx", "Logarithmic x axis. Usage: :root canvas logx <0|1>", RootGraphicsCommands::rootCanvasLogX);
        register(":root canvas logy", "Logarithmic y axis. Usage: :root canvas logy <0|1>", RootGraphicsCommands::rootCanvasLogY);
        register(":root canvas logz", "Logarithmic z axis. Usage: :root canvas logz <0|1>", RootGraphicsCommands::rootCanvasLogZ);
        register(":root canvas grid", "Show the grid. Usage: :root canvas grid <0|1>", RootGraphicsCommands::rootCanvasGrid);
        register(":root canvas margins", "Set the four margins. Usage: :root canvas margins <left> <right> <bottom> <top>", RootGraphicsCommands::rootCanvasMargins);
        register(":root canvas range", "Set what the pad shows. Usage: :root canvas range <x1> <y1> <x2> <y2>", RootGraphicsCommands::rootCanvasRange);
        register(":root canvas size", "Resize the canvas. Usage: :root canvas size <width> <height>", RootGraphicsCommands::rootCanvasSize);
        register(":root canvas print", "Write the pad to a file, any format ROOT knows. Usage: :root canvas print <file>", RootGraphicsCommands::rootCanvasPrint);
        register(":root canvas pdf-open", "Start a multi-page PDF. Usage: :root canvas pdf-open <file>", RootGraphicsCommands::rootCanvasPdfOpen);
        register(":root canvas pdf-page", "Add the current pad as a page. Usage: :root canvas pdf-page <file>", RootGraphicsCommands::rootCanvasPdfPage);
        register(":root canvas pdf-close", "Close a multi-page PDF. Usage: :root canvas pdf-close <file>", RootGraphicsCommands::rootCanvasPdfClose);
        register(":root canvas modified", "Tell the pad it needs redrawing", RootGraphicsCommands::rootCanvasModified);
        register(":root geom gdml", "Import a GDML geometry. Usage: :root geom gdml <file>", RootGraphicsCommands::rootGeomGdml);
        register(":root geom top", "The name of the top volume", RootGraphicsCommands::rootGeomTop);
        register(":root geom volumes", "Every volume in the geometry", RootGraphicsCommands::rootGeomVolumes);
        register(":root geom materials", "Every material in the geometry", RootGraphicsCommands::rootGeomMaterials);
        register(":root geom media", "Every tracking medium in the geometry", RootGraphicsCommands::rootGeomMedia);
        register(":root geom nodes", "What sits inside a volume. Usage: :root geom nodes <volume>", RootGraphicsCommands::rootGeomNodes);
        register(":root geom shape", "The shape of a volume. Usage: :root geom shape <volume>", RootGraphicsCommands::rootGeomShape);
        register(":root geom overlaps", "Look for volumes that intersect. Usage: :root geom overlaps [precision]", RootGraphicsCommands::rootGeomOverlaps);
        register(":root geom find", "Which volume contains a point. Usage: :root geom find <x> <y> <z>", RootGraphicsCommands::rootGeomFind);
        register(":root geom weight", "Weight of the geometry in grams. Usage: :root geom weight [precision]", RootGraphicsCommands::rootGeomWeight);
        register(":root geom close", "Close the geometry, which makes it usable", RootGraphicsCommands::rootGeomClose);
        register(":root roofit var", "Declare an observable. Usage: :root roofit var <workspace> <name> <low> <high>", RootAnalysisCommands::rootRoofitVar);
        register(":root roofit generate", "Throw a toy dataset from a pdf. Usage: :root roofit generate <workspace> <pdf> <observable> <events> <name>", RootAnalysisCommands::rootRoofitGenerate);
        register(":root roofit fitsave", "Fit and keep the result. Usage: :root roofit fitsave <name> <workspace> <pdf> <data>", RootAnalysisCommands::rootRoofitFitSave);
        register(":root roofit status", "What the minimiser returned. Usage: :root roofit status <result>", RootAnalysisCommands::rootRoofitStatus);
        register(":root roofit edm", "Estimated distance to the minimum. Usage: :root roofit edm <result>", RootAnalysisCommands::rootRoofitEdm);
        register(":root roofit minnll", "The minimised negative log likelihood. Usage: :root roofit minnll <result>", RootAnalysisCommands::rootRoofitMinNll);
        register(":root roofit correlation", "Correlation between two fitted parameters. Usage: :root roofit correlation <result> <a> <b>", RootAnalysisCommands::rootRoofitCorrelation);
        register(":root roofit printfit", "Everything about a kept fit. Usage: :root roofit printfit <result>", RootAnalysisCommands::rootRoofitPrintFit);
        register(":root roofit vars", "The variables of a workspace. Usage: :root roofit vars <workspace>", RootAnalysisCommands::rootRoofitVars);
        register(":root roofit pdfs", "The pdfs of a workspace. Usage: :root roofit pdfs <workspace>", RootAnalysisCommands::rootRoofitPdfs);
        register(":root roofit contents", "Everything a workspace holds. Usage: :root roofit contents <workspace>", RootAnalysisCommands::rootRoofitContents);
        register(":root roofit save", "Write a workspace to a file. Usage: :root roofit save <workspace> <file>", RootAnalysisCommands::rootRoofitSave);
        register(":root roofit load", "Read a workspace out of a file. Usage: :root roofit load <name> <file> <workspace>", RootAnalysisCommands::rootRoofitLoad);
        register(":root tree cache", "Size of the read cache in bytes. Usage: :root tree cache <tree> <bytes>", RootTreeCommands::rootTreeCache);
        register(":root tree cachebranch", "Put one branch in the read cache. Usage: :root tree cachebranch <tree> <branch>", RootTreeCommands::rootTreeCacheBranch);
        register(":root tree cachestats", "How the read cache behaved. Usage: :root tree cachestats <tree>", RootTreeCommands::rootTreeCacheStats);
        register(":root tree alias", "Give an expression a short name. Usage: :root tree alias <tree> <alias> <expression>", RootTreeCommands::rootTreeAlias);
        register(":root tree index", "Build an index so entries can be found by value. Usage: :root tree index <tree> <major> [minor]", RootTreeCommands::rootTreeIndex);
        register(":root tree seek", "Load the entry an index points at. Usage: :root tree seek <tree> <major> [minor]", RootTreeCommands::rootTreeSeek);
        register(":root tree branchstatus", "Read a branch, or stop reading it. Usage: :root tree branchstatus <tree> <branch> <0|1>", RootTreeCommands::rootTreeBranchStatus);
        register(":root tree clone", "Copy the structure of a tree without its entries. Usage: :root tree clone <tree> <name>", RootTreeCommands::rootTreeClone);
        register(":root tree copyentries", "Append the entries of one tree to another. Usage: :root tree copyentries <into> <from>", RootTreeCommands::rootTreeCopyEntries);
        register(":root tree optimize", "Rebalance the basket sizes. Usage: :root tree optimize <tree>", RootTreeCommands::rootTreeOptimize);
        register(":root tree autosave", "How often a tree writes itself. Usage: :root tree autosave <tree> <bytes>", RootTreeCommands::rootTreeAutoSave);
        register(":root tree file", "Which file a tree is reading. Usage: :root tree file <tree>", RootTreeCommands::rootTreeFile);
        register(":root rdf histo3", "Fill a three-dimensional histogram. Usage: :root rdf histo3 <name> <hist> <xb> <xlo> <xhi> <yb> <ylo> <yhi> <zb> <zlo> <zhi> <x> <y> <z>", RootTreeCommands::rootRdfHisto3);
        register(":root rdf graph", "Build a graph from two columns. Usage: :root rdf graph <name> <graph> <x> <y>", RootTreeCommands::rootRdfGraph);
        register(":root rdf runs", "How many times the frame has been looped over. Usage: :root rdf runs <name>", RootTreeCommands::rootRdfRuns);
        register(":root rdf slots", "How many threads the frame will use. Usage: :root rdf slots <name>", RootTreeCommands::rootRdfSlots);
        register(":root rdf stats", "Mean, spread and count of a column in one pass. Usage: :root rdf stats <name> <column>", RootTreeCommands::rootRdfStats);
        register(":root sys ls", "What a directory holds, through ROOT. Usage: :root sys ls [directory]", RootEngineCommands::rootSysLs);
        register(":root sys exists", "Whether a path is there. Usage: :root sys exists <path>", RootEngineCommands::rootSysExists);
        register(":root sys mkdir", "Create a directory on disk. Usage: :root sys mkdir <path>", RootEngineCommands::rootSysMkdir);
        register(":root sys rm", "Delete a file on disk. Usage: :root sys rm <path>", RootEngineCommands::rootSysRm);
        register(":root sys cp", "Copy a file. Usage: :root sys cp <from> <to>", RootEngineCommands::rootSysCp);
        register(":root sys mv", "Rename or move a file. Usage: :root sys mv <from> <to>", RootEngineCommands::rootSysMv);
        register(":root sys which", "Where ROOT finds a library or macro. Usage: :root sys which <file>", RootEngineCommands::rootSysWhich);
        register(":root sys cwd", "The working directory of the engine", RootEngineCommands::rootSysCwd);
        register(":root sys chdir", "Change the working directory of the engine. Usage: :root sys chdir <directory>", RootEngineCommands::rootSysChdir);
        register(":root sys expand", "Expand a path with its variables. Usage: :root sys expand <path>", RootEngineCommands::rootSysExpand);
        register(":root sys exec", "Run a shell command from the engine. Usage: :root sys exec <command>", RootEngineCommands::rootSysExec);
        register(":root sys hostname", "The host the engine runs on", RootEngineCommands::rootSysHostname);
        register(":root sys pid", "The process id of the engine", RootEngineCommands::rootSysPid);
        register(":root sys load", "Load a shared library into the engine. Usage: :root sys load <library>", RootEngineCommands::rootSysLoad);
        register(":root sys libraries", "The libraries the engine has loaded", RootEngineCommands::rootSysLibraries);
        register(":root sys now", "The date and time the engine sees", RootEngineCommands::rootSysNow);
        register(":root str match", "Whether a regular expression matches. Usage: :root str match <regex> <text>", RootEngineCommands::rootStrMatch);
        register(":root str replace", "Rewrite with a regular expression. Usage: :root str replace <regex> <replacement> <text>", RootEngineCommands::rootStrReplace);
        register(":root str hash", "The ROOT hash of a string. Usage: :root str hash <text>", RootEngineCommands::rootStrHash);
        register(":root file makeproject", "Write the C++ classes a file needs to be read. Usage: :root file makeproject <file> <directory>", RootFileCommands::rootFileMakeProject);
        register(":root file streamers", "The class layouts a file records. Usage: :root file streamers <file>", RootFileCommands::rootFileStreamers);
        register(":root file version", "Which ROOT wrote a file. Usage: :root file version <file>", RootFileCommands::rootFileVersion);
        register(":root math sort", "The values in order. Usage: :root math sort <v> <v> ...", RootPhysicsCommands::rootMathSort);
        register(":root math factorial", "Factorial of an integer. Usage: :root math factorial <n>", RootPhysicsCommands::rootMathFactorial);
        register(":root math freq", "The normal cumulative distribution. Usage: :root math freq <x>", RootPhysicsCommands::rootMathFreq);
        register(":root math student", "Student quantile. Usage: :root math student <p> <ndf>", RootPhysicsCommands::rootMathStudent);
        register(":root math chisquare", "Chi square quantile. Usage: :root math chisquare <p> <ndf>", RootPhysicsCommands::rootMathChisquare);
        register(":root math beta", "The beta function. Usage: :root math beta <a> <b>", RootPhysicsCommands::rootMathBeta);
        register(":root math bessel", "Modified Bessel function of the first kind. Usage: :root math bessel <order> <x>", RootPhysicsCommands::rootMathBessel);
        register(":root math complex", "Modulus and argument of a complex number. Usage: :root math complex <real> <imaginary>", RootPhysicsCommands::rootMathComplex);

    }

    public static void register(String name, String description,
                                BiConsumer<String, CommandExecutionContext> handler) {
        INTERNAL_COMMANDS.put(name, new CommandInfo(name, description, handler));
    }

    /** Removes a command registered while Sphere was running. */
    public static boolean unregister(String name) {
        return INTERNAL_COMMANDS.remove(name) != null;
    }

    /**
     * Finds the matching command info for the raw text sequence input.
     * Tokenizes the raw input to reliably extract base command names and sub-prefixes.
     */
    public static CommandInfo find(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        List<String> tokens = Tokenizer.DEFAULT.tokenize(input);
        if (tokens.isEmpty()) {
            return null;
        }

        int tokenLimit = Math.min(tokens.size(), 3);
        for (int i = tokenLimit; i > 0; i--) {
            StringBuilder commandNameBuilder = new StringBuilder();
            for (int j = 0; j < i; j++) {
                if (j > 0) {
                    commandNameBuilder.append(" ");
                }
                commandNameBuilder.append(tokens.get(j));
            }
            
            String lookupKey = commandNameBuilder.toString();
            if (INTERNAL_COMMANDS.containsKey(lookupKey)) {
                return INTERNAL_COMMANDS.get(lookupKey);
            }
        }

        String directKey = input.trim();
        if (INTERNAL_COMMANDS.containsKey(directKey)) {
            return INTERNAL_COMMANDS.get(directKey);
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Commands the router serves outside this table
    // -------------------------------------------------------------------------

    /**
     * The file-system plugins registered in CommandRouter, and the directory
     * commands the router answers itself. They work, they are simply dispatched
     * elsewhere, so :help listed none of them. Kept here for the listing only:
     * find() never looks at this map.
     */
    private static final Map<String, String[]> EXTERNAL_COMMANDS = new LinkedHashMap<>();

    /**
     * Documents a command something other than a handler answers.
     *
     * The filesystem tools are plugins, so they are dispatched before the
     * internal table is consulted. Registering them here would intercept them;
     * this names them for the help instead, and the usage is the one the tool
     * itself prints, so the two cannot drift apart without the tool's own
     * message drifting with it.
     */
    private static void registerExternal(String name, String section, String description) {
        registerExternal(name, section, description, "");
    }

    /** The same, with the usage line the tool prints when it is misused. */
    private static void registerExternal(String name, String section,
                                         String description, String usage) {
        EXTERNAL_COMMANDS.put(name, new String[] {section, description, usage});
    }

    /** The usage line of a command, or empty when it takes no arguments. */
    public static String usageOf(String name) {
        final String[] one = EXTERNAL_COMMANDS.get(name);
        return (one == null || one.length < 3) ? "" : one[2];
    }

    static {
        registerExternal(":ls", "system", "List what the current directory holds", ":ls [-l] [-a] [-h] [-t] [-S] [-r] [--all] [path]");
        registerExternal(":cat", "system", "Print a file", ":cat [--head N] [--tail N] <file>");
        registerExternal(":head", "system", "First lines of a file", ":head [-n N] <file>");
        registerExternal(":tail", "system", "Last lines of a file, -f to follow it", ":tail [-n N] [-f] <file>");
        registerExternal(":tail-stop", "system", "Stop following a file", ":tail-stop");
        registerExternal(":wc", "system", "Count lines, words and bytes", ":wc [-l] [-w] [-c] <file> [file...]");
        registerExternal(":grep", "system", "Search a pattern inside files", ":grep [-i] [-n] [-r] [-w] [-v] [-c] [--include=GLOB] <pattern> [path]");
        registerExternal(":find", "system", "Find files by name", ":find [path] [-name GLOB] [-iname GLOB] [-type f|d] [-size N] [-newer FILE] [-maxdepth N]");
        registerExternal(":diff", "system", "What differs between two files", ":diff [-U N] [-w] [-q] <file1> <file2>");
        registerExternal(":cp", "system", "Copy a file or a directory", ":cp [-r] [-f] <source> <destination>");
        registerExternal(":mv", "system", "Move or rename", ":mv [-f] <source> <destination>");
        registerExternal(":rm", "system", "Remove a file or a directory", ":rm [-r] [--confirm] <path> [path...]");
        registerExternal(":mkdir", "system", "Create a directory", ":mkdir <directory>");
        registerExternal(":touch", "system", "Create an empty file, or update its date", ":touch [-c] <file> [file...]");
        registerExternal(":symlink", "system", "Create a symbolic link", ":symlink <target> <link-name>");
        registerExternal(":stat", "system", "Size, dates and permissions of a file", ":stat <path>");
        registerExternal(":tree", "system", "The directory tree", ":tree [-L N] [-a] [-d] [-s] [--all] [path]");
        registerExternal(":du", "system", "What a directory weighs", ":du [-d N] [--top N] [--name] [path]");
        registerExternal(":df", "system", "Free space on the filesystems", ":df [path]");
        registerExternal(":sha256", "system", "SHA-256 of a file", ":sha256 [--check DIGEST] <file> [file...]");
        registerExternal(":md5", "system", "MD5 of a file", ":md5 [--check DIGEST] <file> [file...]");
        registerExternal(":which", "system", "Where a program is found", ":which [-a] [tool...]");
        registerExternal(":env", "system", "What an environment variable holds", ":env [name]");
        registerExternal(":watch", "system", "Report every change under a directory", ":watch [-n SECONDS] <path>");
        registerExternal(":watch-stop", "system", "Stop watching", ":watch-stop");
        registerExternal(":cd", "system", "Change the current directory", ":cd [directory]   (cd also works)");
        registerExternal(":pwd", "system", "Print the current directory", ":pwd   (pwd also works)");
        registerExternal(":pushd", "system", "Change directory, keeping the previous one", ":pushd <directory>   (pushd also works)");
        registerExternal(":popd", "system", "Return to the directory pushd kept", ":popd [+N]   (popd also works)");
        registerExternal(":dirs", "system", "The directory stack", ":dirs [-v | -c]   (dirs also works)");
        registerExternal("cd", "system", "Change the current directory, as a shell does", "cd [directory]");
        registerExternal("pwd", "system", "Print the current directory", "pwd");
        registerExternal("pushd", "system", "Change directory, keeping the previous one on a stack", "pushd <directory>");
        registerExternal("popd", "system", "Return to the directory pushd kept", "popd [+N]");
        registerExternal("dirs", "system", "The directory stack", "dirs [-v | -c]");
    }

    /** Name and one-line description of every command a user can type. */
    public static Map<String, String> allForHelp() {
        Map<String, String> merged = new LinkedHashMap<>();
        for (Map.Entry<String, CommandInfo> entry : INTERNAL_COMMANDS.entrySet()) {
            merged.put(entry.getKey(), entry.getValue().description);
        }
        for (Map.Entry<String, String[]> entry : EXTERNAL_COMMANDS.entrySet()) {
            merged.put(entry.getKey(), entry.getValue()[1]);
        }
        return Collections.unmodifiableMap(merged);
    }

    /**
     * Commands grouped the way :help shows them.
     *
     * A command with several words belongs to its first word, which is what the
     * user types after :help. A one-word command has no family of its own and
     * joins "general", except the ones declared above with their own section.
     */
    public static Map<String, List<String>> sections() {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String name : INTERNAL_COMMANDS.keySet()) {
            final String[] words = name.trim().split("\\s+");
            final String family = (words.length > 1) ? words[0].replaceFirst("^:+", "") : "system";
            grouped.computeIfAbsent(family, key -> new ArrayList<>()).add(name);
        }
        for (Map.Entry<String, String[]> entry : EXTERNAL_COMMANDS.entrySet()) {
            grouped.computeIfAbsent(entry.getValue()[0], key -> new ArrayList<>())
                   .add(entry.getKey());
        }
        // A family of one is not a family; it reads better among the odds and ends.
        List<String> lonely = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            if (entry.getValue().size() == 1 && !"system".equals(entry.getKey())) {
                lonely.add(entry.getKey());
            }
        }
        for (String family : lonely) {
            grouped.computeIfAbsent("system", key -> new ArrayList<>())
                   .addAll(grouped.remove(family));
        }

        // System first: those are the commands read before any other.
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        if (grouped.containsKey("system")) {
            ordered.put("system", grouped.remove("system"));
        }
        ordered.putAll(grouped);
        return ordered;
    }

    public static Map<String, CommandInfo> all() {
        return Collections.unmodifiableMap(INTERNAL_COMMANDS);
    }
}
