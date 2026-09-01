package com.sphere.core.commands;

import com.sphere.core.commandrouterincludes.Tokenizer;
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
        register(":cpp exit", "Exit interactive C++ mode", Handlers::cppExit);
        register(":js env", "Display JavaScript engine runtime parameters", Handlers::jsEnv);
        register(":js diag", "Run diagnostics on the ECMAScript interpreter engine", Handlers::jsDiag);
        register(":js mode", "Enter interactive JavaScript execution shell mode", Handlers::jsMode);
        register(":js exit", "Exit interactive JavaScript mode", Handlers::jsExit);

        // --- System Tools & Utilities ---
        register(":snippet list", "List all indexed code snippets", Handlers::snippetList);
        register(":snippet info", "Display metadata and content details for a specific snippet", Handlers::snippetInfo);
        register(":snippet reload", "Hot-reload the snippet registry index from disk", Handlers::snippetReload);
        register(":tools diag", "Audit system toolchain and dependency installations", Handlers::toolsDiag);
        register(":tools list", "List available binary tool executables", Handlers::toolsList);
        register(":tools update", "Update metadata and version registries for external tools", Handlers::toolsUpdate);
        register(":clear", "Clear the active console user interface screen output buffer", Handlers::clearConsole);
        register(":kill", "Terminate an active long-running process by name", Handlers::terminateProcess);
        register(":tasks", "List all active background threads and process tasks", Handlers::listActiveTasks);

        // --- ROOT Framework Bridge — Files & Directories ---
        register(":root file open", "Open a ROOT file handle. Usage: :root file open <path> [mode]", Handlers::rootOpenFile);
        register(":root file open-remote", "Open a remote ROOT file via web/XROOTD. Usage: :root file open-remote <url>", Handlers::rootOpenRemoteFile);
        register(":root file list", "List the open ROOT files with their id and name", Handlers::rootFileList);
        register(":root file close", "Close an open file. Usage: :root file close <id|name>", Handlers::rootClose);
        register(":root file close-all", "Close all opened ROOT file handles", Handlers::rootCloseAll);
        register(":root file ls", "List keys inside an opened ROOT file handle. Usage: :root file ls [file_id]", Handlers::rootLs);
        register(":root file info", "Display metadata info for an open file handle. Usage: :root file info <file_id>", Handlers::rootFileInfo);
        register(":root file write", "Flush and write an open file. Usage: :root file write <id|name>", Handlers::rootFileWrite);
        register(":root file keys", "List all key structures inside active ROOT directory", Handlers::rootFileKeys);
        register(":root file scan", "Report a ROOT file's health, structure and basket layout. Usage: :root file scan <path> [--json]", Handlers::rootFileScan);
        register(":root file cd", "Change directory inside ROOT file. Usage: :root file cd <path>", Handlers::rootFileCd);
        register(":root file pwd", "Print current working directory inside active ROOT file", Handlers::rootFilePwd);
        register(":root file dir", "Display active ROOT directory contents", Handlers::rootFileDir);
        register(":root file get", "Extract object from ROOT file handle. Usage: :root file get <name>", Handlers::rootFileGet);
        register(":root file recreate", "Recreate a ROOT file, overwriting existing contents", Handlers::rootFileRecreate);
        register(":root file open-update", "Open ROOT file in UPDATE mode", Handlers::rootFileOpenUpdate);
        register(":root file mkdir", "Create directory inside ROOT file handle. Usage: :root file mkdir <name>", Handlers::rootFileMkdir);
        register(":root file rmdir", "Remove directory inside ROOT file handle. Usage: :root file rmdir <name>", Handlers::rootFileRmdir);
        register(":root file copy", "Copy object key within ROOT file structure", Handlers::rootFileCopy);
        register(":root file move", "Move object key within ROOT file structure", Handlers::rootFileMove);
        register(":root file delete", "Delete key/object from active ROOT file handle", Handlers::rootFileDelete);
        register(":root ping", "Probe the engine (CMD_PING)", Handlers::rootPing);
        register(":root version", "ROOT version reported by the engine (CMD_SYS_VERSION)", Handlers::rootVersion);
        register(":root sys uptime", "Engine uptime (CMD_SYS_UPTIME)", Handlers::rootSysUptime);
        register(":root sys config", "root-config value from the engine. Usage: :root sys config <flag>", Handlers::rootSysConfig);
        register(":root schema discover", "Describe a TTree schema (CMD_SCHEMA_DISCOVER). Usage: :root schema discover <tree_id>", Handlers::rootSchemaDiscover);
        register(":root tree column", "Read one branch as a column (CMD_TTREE_READ_COLUMN). Usage: :root tree column <tree_id> <branch>", Handlers::rootTreeColumn);
        register(":root tree stats", "Compute branch statistics in the engine (CMD_TTREE_COMPUTE_STATS). Usage: :root tree stats <tree_id> <branch>", Handlers::rootTreeStats);
        register(":root tree filter", "Apply a selection in the engine (CMD_TTREE_APPLY_FILTER). Usage: :root tree filter <tree_id> <expr>", Handlers::rootTreeFilter);
        register(":root tree open", "Entry count for a named TTree. Usage: :root tree open <name>", Handlers::rootGetTree);
        register(":root tree branch", "Print one branch. Usage: :root tree branch <tree> <branch>", Handlers::rootGetBranch);
        register(":root chain add", "Add a file to a TChain. Usage: :root chain add <chain> <file>", Handlers::rootChainAdd);
        register(":root rdf open", "Open an RDataFrame. Usage: :root rdf open <tree> <file>", Handlers::rootRdfOpen);
        register(":root rdf filter", "Filter the active RDataFrame. Usage: :root rdf filter <expr>", Handlers::rootRdfFilter);
        register(":root rdf count", "Count entries in the active RDataFrame", Handlers::rootRdfCount);
        register(":root func new", "Create a TF1. Usage: :root func new <name> <formula>", Handlers::rootFuncNew);
        register(":root style set", "Select a ROOT style. Usage: :root style set <name>", Handlers::rootStyleSet);
        register(":root script load", "Load a macro. Usage: :root script load <file>", Handlers::rootLoadScript);
        register(":root script run", "Run a macro. Usage: :root script run <file>", Handlers::rootRunScript);
        register(":root script compile", "Compile and load a macro (ACLiC). Usage: :root script compile <file>", Handlers::rootCompileScripts);
        register(":root includes load", "Add an include path to the interpreter. Usage: :root includes load <dir>", Handlers::rootLoadIncludes);
        register(":root includes compile", "Add an include path to the compiler. Usage: :root includes compile <dir>", Handlers::rootCompileIncludes);
        register(":root cache size", "Set TFile.CacheSize. Usage: :root cache size <bytes>", Handlers::rootSetCacheSize);
        register(":root cache policy", "Set TFile.CachePolicy. Usage: :root cache policy <n>", Handlers::rootSetCachePolicy);
        register(":root cache stats", "Print the ROOT environment table", Handlers::rootCacheStats);
        register(":root cache clear", "List open files held by ROOT", Handlers::rootCacheClear);
        register(":root limits obj-size", "Set TFile.MaxSize. Usage: :root limits obj-size <bytes>", Handlers::rootSetMaxObjSize);
        register(":root limits handles", "Set TFile.MaxHandles. Usage: :root limits handles <n>", Handlers::rootSetMaxHandles);
        register(":root limits age", "Set TFile.MaxAge. Usage: :root limits age <n>", Handlers::rootSetMaxAge);
        register(":root getenv", "Read an environment variable through ROOT. Usage: :root getenv <name>", Handlers::rootGetEnv);
        register(":root vars", "Print a ROOT global. Usage: :root vars <name>", Handlers::rootVars);
        register(":root config", "Show the shared-library build command ROOT uses", Handlers::rootConfig);
        register(":root info", "ROOT version string", Handlers::rootInfo);
        register(":root stats", "Engine host memory usage", Handlers::rootStats);
        register(":root gc", "Check ROOT object validity", Handlers::rootGc);
        register(":root benchmark", "Host CPU information", Handlers::rootBenchmark);
        register(":root safe-mode", "Toggle ROOT batch mode. Usage: :root safe-mode <0|1>", Handlers::rootSafeMode);
        register(":root output set", "Redirect ROOT output to a file. Usage: :root output set <file>", Handlers::rootSetOutput);
        register(":root cd", "Change directory inside the current ROOT file", Handlers::rootCd);
        register(":root pwd", "Print the current ROOT directory", Handlers::rootPwd);
        register(":root mkdir", "Create a directory in the current ROOT file", Handlers::rootMkdir);
        register(":root analyze", "Print a named ROOT object. Usage: :root analyze <name>", Handlers::rootAnalyze);
        register(":root handles list", "List all active bridge file and object handles", Handlers::rootListHandles);

        // --- ROOT Framework Bridge — Histograms ---
        register(":root hist get", "Extract histogram from file handle. Usage: :root hist get <name>", Handlers::rootGetHist);
        register(":root hist bins", "Dump bin contents of a TH1 histogram handle. Usage: :root hist bins <name>", Handlers::rootDumpHistBins);
        register(":root hist reset", "Reset bin contents and stats of a histogram handle", Handlers::rootHistReset);
        register(":root hist rebin", "Rebin x-axis channels of a histogram handle. Usage: :root hist rebin <name> <ngroup>", Handlers::rootHistRebin);
        register(":root hist scale", "Scale histogram entries by a numeric factor. Usage: :root hist scale <name> <factor>", Handlers::rootHistScale);
        register(":root hist draw", "Render a visual plot of a histogram handle. Usage: :root hist draw <name> [opt]", Handlers::rootHistDraw);
        register(":root hist fit", "Fit a formula or TF1 function to a histogram. Usage: :root hist fit <name> <formula>", Handlers::rootHistFit);
        register(":root hist integral", "Calculate integral of a histogram handle. Usage: :root hist integral <name>", Handlers::rootHistIntegral);
        register(":root hist max", "Retrieve maximum bin value from histogram handle", Handlers::rootHistMax);
        register(":root hist min", "Retrieve minimum bin value from histogram handle", Handlers::rootHistMin);
        register(":root hist list", "List all histogram handles registered in bridge memory", Handlers::rootHistList);
        register(":root hist smooth", "Smooth bin contents of a histogram handle", Handlers::rootHistSmooth);
        register(":root hist project", "Project 2D/3D histogram to 1D axis handle", Handlers::rootHistProject);
        register(":root hist statbox", "Toggle or configure stats box display on histogram", Handlers::rootHistStatbox);
        register(":root hist setbin", "Set specific bin content value. Usage: :root hist setbin <name> <bin> <val>", Handlers::rootHistSetbin);
        register(":root hist fill", "Fill histogram with a numeric value. Usage: :root hist fill <name> <val> [weight]", Handlers::rootHistFill);
        register(":root hist clone", "Clone an existing histogram handle into memory", Handlers::rootHistClone);

        // --- ROOT Framework Bridge — Generic Objects ---
        register(":root obj get", "Retrieve generic object handle from file. Usage: :root obj get <name>", Handlers::rootGetObject);
        register(":root obj dump", "Dump raw object data layout. Usage: :root obj dump <name>", Handlers::rootDumpObject);
        register(":root obj describe", "Inspect member methods and structural properties of an object handle", Handlers::rootDescribeObject);
        register(":root obj clone", "Duplicate an active object handle in memory", Handlers::rootObjClone);
        register(":root obj write", "Write object handle to active output file", Handlers::rootObjWrite);
        register(":root obj delete", "Delete target object handle from memory", Handlers::rootObjDelete);
        register(":root obj methods", "List exposed C++ methods on target object handle", Handlers::rootObjMethods);
        register(":root obj members", "List data members of target object handle", Handlers::rootObjMembers);
        register(":root obj list", "List all generic object handles active in memory", Handlers::rootObjList);
        register(":root obj class", "Print native C++ class name of target object handle", Handlers::rootObjClass);
        register(":root obj type", "Print structural type definition of object handle", Handlers::rootObjType);
        register(":root obj print", "Invoke native Print() method on object handle", Handlers::rootObjPrint);
        register(":root obj inspect", "Open detailed inspector on object handle attributes", Handlers::rootObjInspect);

        // --- ROOT Framework Bridge — TTree & TChain Data Processing ---
        register(":root tree print", "Print structure and branch metadata for TTree handle. Usage: :root tree print <tree_id>", Handlers::rootTreePrint);
        register(":root tree scan", "Scan and print values of tree branches for selected entries. Usage: :root tree scan <tree_id> [expr]", Handlers::rootTreeScan);
        register(":root tree draw", "Draw variable or branch expression from a TTree handle. Usage: :root tree draw <tree_id> <expr> [cut]", Handlers::rootTreeDraw);
        register(":root tree process", "Execute C++ macro selector on a TTree dataset", Handlers::rootTreeProcess);
        register(":root tree project", "Project TTree expression into a histogram handle. Usage: :root tree project <tree_id> <name> <expr>", Handlers::rootTreeProject);
        register(":root tree entries", "Retrieve total entry count from a TTree handle", Handlers::rootTreeEntries);
        register(":root tree branches", "List all branch names for a TTree handle", Handlers::rootTreeBranches);
        register(":root tree leaves", "List all leaf data names for a TTree handle", Handlers::rootTreeLeaves);
        register(":root tree getentry", "Read single entry record into TTree buffer memory", Handlers::rootTreeGetentry);
        register(":root tree copytree", "Create sub-tree copy filtered by selection criteria", Handlers::rootTreeCopytree);

        // --- ROOT Framework Bridge — Graphs & Graphics ---
        register(":root graph draw", "Render visual representation of a TGraph object handle", Handlers::rootGraphDraw);
        register(":root graph fit", "Fit function model to TGraph data points", Handlers::rootGraphFit);
        register(":root graph points", "Print data point coordinates for a TGraph handle", Handlers::rootGraphPoints);
        register(":root graph add", "Add new coordinate point to TGraph handle", Handlers::rootGraphAdd);
        register(":root canvas new", "Create new visual TCanvas context window", Handlers::rootCanvasNew);
        register(":root canvas cd", "Focus active pad within current TCanvas window", Handlers::rootCanvasCd);
        register(":root canvas save", "Export active canvas to image file. Usage: :root canvas save <filename.png>", Handlers::rootCanvasSave);
        register(":root canvas clear", "Clear contents of active TCanvas visual context", Handlers::rootCanvasClear);
        register(":root canvas update", "Update and repaint display buffer for active canvas", Handlers::rootCanvasUpdate);
        register(":root canvas list", "List all open TCanvas visual window handles", Handlers::rootCanvasList);

        // --- ROOT Framework Bridge — Math, Fitting & Statistics ---
        register(":root fit expr", "Fit user-defined math formula to dataset handle", Handlers::rootFitExpr);
        register(":root fit function", "Fit TF1 function object to active dataset handle", Handlers::rootFitFunction);
        register(":root fit reset", "Reset fit parameters and clear fit results history", Handlers::rootFitReset);
        register(":root fit params", "Print resulting fit parameters and parameter errors", Handlers::rootFitParams);
        register(":root math eval", "Evaluate mathematical function or formula expression", Handlers::rootMathEval);
        register(":root math deriv", "Calculate numerical derivative of function handle", Handlers::rootMathDeriv);
        register(":root math integral", "Calculate definite numerical integral of function handle", Handlers::rootMathIntegral);

        // --- Advanced ROOT Modules — Machine Learning & Statistics ---
        register(":root tmva factory", "Create TMVA Factory for machine learning classification/regression", Handlers::rootTmvaFactory);
        register(":root tmva train", "Train configured multivariate analysis algorithms", Handlers::rootTmvaTrain);
        register(":root tmva test", "Test trained MVA classification or regression models", Handlers::rootTmvaTest);
        register(":root tmva evaluate", "Evaluate performance metrics for trained TMVA models", Handlers::rootTmvaEvaluate);
        register(":root tmva gui", "Open interactive TMVA results visualization GUI", Handlers::rootTmvaGui);
        register(":root roofit workspace", "Create or inspect RooFit RooWorkspace container", Handlers::rootRoofitWorkspace);
        register(":root roofit pdf", "Create probability density function in RooFit context", Handlers::rootRoofitPdf);
        register(":root roofit fit", "Perform maximum likelihood fit using RooFit engine", Handlers::rootRoofitFit);
        register(":root roofit plot", "Plot RooFit variables, datasets, or PDFs to canvas", Handlers::rootRoofitPlot);

        // --- Advanced ROOT Modules — Geometry, SQL, Proof & Distributed ---
        register(":root geom load", "Load 3D geometry file into TGeoManager", Handlers::rootGeomLoad);
        register(":root geom draw", "Render 3D detector or volume geometry scene", Handlers::rootGeomDraw);
        register(":root geom export", "Export loaded 3D geometry model to external format", Handlers::rootGeomExport);
        register(":root sql connect", "Establish connection to database backend via TSQLServer", Handlers::rootSqlConnect);
        register(":root sql query", "Execute SQL query on active database server connection", Handlers::rootSqlQuery);
        register(":root sql disconnect", "Close active TSQLServer database connection", Handlers::rootSqlDisconnect);
        register(":root net server", "Start ROOT TServerSocket for incoming remote connections", Handlers::rootNetServer);
        register(":root net connect", "Connect TSocket client handle to remote ROOT server", Handlers::rootNetConnect);
        register(":root net send", "Send object or message over active TSocket connection", Handlers::rootNetSend);
        register(":root proof open", "Initialize PROOF parallel processing cluster session", Handlers::rootProofOpen);
        register(":root proof process", "Run distributed dataset processing task on PROOF cluster", Handlers::rootProofProcess);
        register(":root proof status", "Display runtime status and node activity for PROOF session", Handlers::rootProofStatus);

        // --- Advanced ROOT Modules — GUI, PyROOT & System ---
        register(":root gui new", "Instantiate new ROOT GUI window (TGMainFrame)", Handlers::rootGuiNew);
        register(":root gui show", "Display and render specified ROOT GUI component", Handlers::rootGuiShow);
        register(":root gui close", "Close active ROOT GUI window frame", Handlers::rootGuiClose);
        register(":root py import", "Import Python module inside ROOT C++ interpreter engine", Handlers::rootPyImport);
        register(":root py eval", "Evaluate Python script expression via PyROOT bridge", Handlers::rootPyEval);
        register(":root py exec", "Execute raw Python code block inside ROOT context", Handlers::rootPyExec);
        register(":root sys info", "Display host system resource and ROOT configuration status", Handlers::rootSysInfo);
        register(":root sys memory", "Display current memory consumption for active ROOT process", Handlers::rootSysMemory);
        register(":root sys plugins", "List loaded dynamic ROOT plugin handlers and libraries", Handlers::rootSysPlugins);

        // --- ROOT Bridge Maintenance, Diagnostics & Profiling ---
        register(":root status", "Print status summary of active ROOT subsystem handles", Handlers::rootStatus);
        register(":root reset", "Reset ROOT session state and clear bridge memory handles", Handlers::rootReset);
        register(":root dump", "Dump summary of all active ROOT objects registered in session", Handlers::rootDump);
        register(":root diag", "Run health diagnostic suite on ROOT bridge inter-process subsystem", Handlers::rootDiag);
        register(":root profile", "View execution timing summaries and active profiling metrics", Handlers::rootProfile);
        register(":root profile stats", "Dump detailed execution timing statistics for active scopes", Handlers::rootProfileStats);
        register(":root profile json", "Export current execution profiling performance data in JSON format", Handlers::rootProfileJson);
        register(":root profile reset", "Clear accumulated profiling execution counters and timers", Handlers::rootProfileReset);
        register(":root profile level", "Configure bridge profiling detail level. Usage: :root profile level <basic|advanced|full>", Handlers::rootProfileLevel);
        
        // --- ROOT Framework Bridge — Debug & Diagnostics ---
        register(":root debug dump", "Dump full debug snapshot of backend internal state", Handlers::rootDebugDump);
        register(":root debug graphviz", "Generate Graphviz DOT representation of active handle graph", Handlers::rootDebugGraphviz);
        register(":root debug audit", "Audit ROOT ecosystem and detect orphan memory handles", Handlers::rootDebugAudit);
        register(":root debug level", "Set C++ bridge debug level. Usage: :root debug level <0-5>", Handlers::rootDebugLevel);
        // --- ROOT Framework Bridge — Profiling & System Diagnostics ---
        register(":root profiling status", "Display runtime execution statistics and command latency histograms", Handlers::rootProfilingStatus);
        register(":root profiling json", "Export complete execution latency profiles in JSON format", Handlers::rootProfilingJson);
        register(":root profiling reset", "Clear accumulated profiling metrics and latency history", Handlers::rootProfilingReset);
        register(":root profiling level", "Configure active profiling granularity (BASIC, ADVANCED, FULL)", Handlers::rootProfilingLevel);
        register(":root profile threshold", "Set latency threshold for profile tracking in milliseconds", Handlers::rootProfileThreshold);

        // --- ROOT Framework Bridge — Watchdog Diagnostics & Control ---
        register(":root watchdog status", "Display active tasks monitored by the C++ watchdog in JSON format", Handlers::rootWatchdogStatus);
        register(":root watchdog kill", "Trigger manual scan and terminate long-running stuck execution threads", Handlers::rootWatchdogKill);

    }

    public static void register(String name, String description,
                                BiConsumer<String, CommandExecutionContext> handler) {
        INTERNAL_COMMANDS.put(name, new CommandInfo(name, description, handler));
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

    public static Map<String, CommandInfo> all() {
        return Collections.unmodifiableMap(INTERNAL_COMMANDS);
    }
}