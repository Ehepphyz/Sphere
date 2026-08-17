package com.sphere.core.commandrouterincludes.cmdscibackend;

import com.sphere.core.commandrouterincludes.CommandContext;
import com.sphere.core.commandrouterincludes.ParsedCommand;
import com.sphere.core.rootbackend.RootBackend;
import com.sphere.utils.AppLogger;

/**
 * Handles incoming ROOT execution commands. Decomposes the 3-level Sphere grammar
 * and transforms structured tokens into clear sequential protocols for root-bridge.cpp
 * over the lock-free SHM pipeline adhering to Architecture v2.
 */
public class RootDispatcher extends ScientificBaseDispatcher {

    private RootBackend rootBackend;

    @Override
    protected String getToolName() { 
        return "root"; 
    }

    @Override
    protected String getBinaryName() { 
        return "root"; 
    }

    @Override
    public boolean canHandle(String input) {
        if (input == null) {
            return false;
        }
        String trimmed = input.strip();
        return trimmed.startsWith(":root") || trimmed.startsWith("::root");
    }

    private synchronized RootBackend getOrInitBackend(CommandContext ctx) {
        if (rootBackend == null) {
            String bridgePath = "rootbackend/root-bridge";
            try {
                rootBackend = new RootBackend(bridgePath);
                rootBackend.initialize();
            } catch (Exception e) {
                AppLogger.error("Failed to initialize RootBackend: " + e.getMessage(), e);
            }
        }
        return rootBackend;
    }

    @Override
    protected void executeScientificCommand(String resolvedArgs, ParsedCommand rawStructure, CommandContext ctx) {
        // Validate pre-parsed grammar structure passed down from the parent dispatcher
        if (rawStructure == null) {
            AppLogger.error("ROOT execution aborted: Malformed grammar input structural data.");
            return;
        }

        RootBackend backend = getOrInitBackend(ctx);
        if (backend == null) {
            AppLogger.error("ROOT execution aborted: Backend initialization failed.");
            return;
        }

        // 1. Handle LEVEL 2 & LEVEL 3: Macro Context (::root)
        if (rawStructure.type == ParsedCommand.RootType.MACRO) {
            
            // Step A: Mount macro target file inside C++ file registry if specified
            if (rawStructure.filepath != null && !rawStructure.filepath.isEmpty()) {
                AppLogger.info("ROOT Bridge: Opening macro workspace file -> " + rawStructure.filepath);
                dispatchToBridge(backend, "OPEN_FILE " + rawStructure.filepath + " READ");
            }

            // Step B: Inject Macro Flags/Options into Cling environment
            if (!rawStructure.macroOptions.isEmpty() || !rawStructure.macroFlags.isEmpty()) {
                String variableBinds = String.join(" ", rawStructure.macroOptions).strip();
                if (!variableBinds.isEmpty()) {
                    dispatchToBridge(backend, "CLING_EXEC " + variableBinds);
                }
            }

            // Step C: Handle LEVEL 3: Enclosed Snippet execution ([@ snippet.C ...])
            if (rawStructure.hasSnippet) {
                String runScriptCmd = "RUN_SCRIPT " + rawStructure.filepath;
                AppLogger.info("ROOT Bridge: Spawning isolated snippet -> " + runScriptCmd);
                dispatchToBridge(backend, runScriptCmd);
            }
            
            return;
        }

        // 2. Handle LEVEL 1: Standard Direct Commands (:root)
        boolean isDirectExec = rawStructure.macroFlags.stream().anyMatch("-e"::equalsIgnoreCase) || 
                               rawStructure.macroOptions.stream().anyMatch("--exec"::equalsIgnoreCase);

        String joinedTokens = String.join(" ", rawStructure.macroTokens).strip();
        boolean isRawProtocolCmd = "ping".equalsIgnoreCase(joinedTokens);

        if (isDirectExec || isRawProtocolCmd) {
            if (!joinedTokens.isEmpty()) {
                String protocolPayload = isRawProtocolCmd ? joinedTokens : "CLING_EXEC " + joinedTokens;
                dispatchToBridge(backend, protocolPayload);
            }
        } else {
            // Route standard standalone processes (e.g., interactive file checks or batch calculations)
            String fullCommand = "root -b " + joinedTokens;
            AppLogger.info("Launching standalone ROOT process: " + fullCommand);
            ctx.executeBackendSafely("root", fullCommand.strip());
        }
    }

    /**
     * Helper method ensuring compliance with Architecture v2 by routing non-blocking 
     * commands through the RootProcessBridge or falling back to backend execution.
     *
     * @param backend The active RootBackend instance.
     * @param command The formatted bridge command string to dispatch.
     */
    private void dispatchToBridge(RootBackend backend, String command) {
        if (backend.getProcessBridge() != null) {
            backend.getProcessBridge().pushCommand(command);
        } else {
            backend.execute(command);
        }
    }

    public synchronized void shutdown() {
        if (rootBackend != null) {
            rootBackend.close();
            rootBackend = null;
        }
    }

    public synchronized RootBackend getActiveBackend(CommandContext ctx) {
        return getOrInitBackend(ctx);
    }
}