package com.sphere.core.rootbackend.includes;

import com.sphere.core.rootbackend.RootProcessBridge;
import com.sphere.utils.AppLogger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Handles dynamic compilation, loading, and execution of ROOT C++ macros.
 * Operates asynchronously over the FFM lock-free SHM command ring.
 */
public final class RootMacroLoader {

    private final RootProcessBridge bridge;

    public RootMacroLoader(RootProcessBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * Safely compiles and loads a C++ macro (.C, .cpp, or .h) into the Cling session.
     * Performs pre-flight checks on the file path to prevent silent failures.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean load(String macroPath) throws IOException {
        Path path = Paths.get(macroPath);
        if (!Files.exists(path)) {
            throw new FileNotFoundException("Target macro file does not exist: " + macroPath);
        }

        // Convert path to absolute to avoid relative-directory issues in the subprocess
        String absolutePath = path.toAbsolutePath().toString().replace("\\", "/");

        AppLogger.info("Compiling and loading ROOT macro: " + absolutePath);

        // Using native ROOT API rather than raw Cling meta-commands (.L) for execution safety
        String clingCmd = String.format(
            "{ int result = gROOT->LoadMacro(\"%s\"); " +
            "  if (result == 0) { " +
            "    std::cout << \"OK\\n\"; " +
            "  } else { " +
            "    std::cout << \"ERROR CompilationOrLoadFailed Code=\" << result << \"\\n\"; " +
            "  } }",
            absolutePath
        );

        return pushClingCommand(clingCmd, "load macro: " + absolutePath);
    }

    /**
     * Safely executes a loaded macro function.
     * Inspects the interpreter's global dictionary to ensure the symbol is declared
     * before running to prevent thread hangs or crashes.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean execute(String macroName) {
        AppLogger.info("Executing macro: " + macroName);

        // Check if the function exists in ROOT's global function table first
        String clingCmd = String.format(
            "{ if (gROOT->GetGlobalFunction(\"%s\", nullptr, kTRUE)) { " +
            "    gROOT->ProcessLine(\"%s()\"); " +
            "    std::cout << \"OK\\n\"; " +
            "  } else { " +
            "    std::cout << \"ERROR MacroSymbolNotFound\\n\"; " +
            "  } }",
            macroName, macroName
        );

        return pushClingCommand(clingCmd, "execute macro: " + macroName);
    }

    /**
     * Safely executes a loaded macro function with arguments.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean executeWithArgs(String macroName, String arguments) {
        AppLogger.info("Executing macro: " + macroName + " with arguments: " + arguments);

        String clingCmd = String.format(
            "{ if (gROOT->GetGlobalFunction(\"%s\", nullptr, kTRUE)) { " +
            "    gROOT->ProcessLine(\"%s(%s)\"); " +
            "    std::cout << \"OK\\n\"; " +
            "  } else { " +
            "    std::cout << \"ERROR MacroSymbolNotFound\\n\"; " +
            "  } }",
            macroName, macroName, arguments
        );

        return pushClingCommand(clingCmd, "execute macro with args: " + macroName);
    }

    /**
     * Attempts to unload a macro from the interpreter session.
     * Note: Dynamic unloading of complex compiled classes is often limited by ROOT/Cling.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean unload(String macroPath) {
        Path path = Paths.get(macroPath);
        String absolutePath = path.toAbsolutePath().toString().replace("\\", "/");
        
        AppLogger.info("Attempting to unload macro: " + absolutePath);

        // Replaces .U with programmatic interpreter commands
        String clingCmd = String.format(
            "{ gInterpreter->UnloadMacro(\"%s\"); " +
            "  std::cout << \"OK\\n\"; }",
            absolutePath
        );

        return pushClingCommand(clingCmd, "unload macro: " + absolutePath);
    }

    private boolean pushClingCommand(String clingCmd, String actionDescription) {
        boolean queued = bridge.pushCommand("CLING_EXEC " + clingCmd);
        if (!queued) {
            AppLogger.error("Failed to queue SHM command to " + actionDescription);
        }
        return queued;
    }
}