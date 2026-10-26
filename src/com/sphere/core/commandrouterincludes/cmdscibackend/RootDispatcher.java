package com.sphere.core.commandrouterincludes.cmdscibackend;

import com.sphere.core.commandrouterincludes.CommandContext;
import com.sphere.core.commandrouterincludes.ParsedCommand;
import com.sphere.core.rootbackend.RootBackend;
import com.sphere.core.rootbackend.RootProcessBridge;
import com.sphere.utils.AppLogger;

import java.nio.charset.StandardCharsets;

/**
 * Handles incoming ROOT execution commands. Decomposes the 3-level Sphere grammar
 * and transforms structured tokens into clear sequential protocols for root-bridge.cpp
 * over the lock-free SHM pipeline adhering to Architecture v2.
 */
public class RootDispatcher extends ScientificBaseDispatcher {

    // One backend per process: several pumps on one event ring steal each
    // other's replies.
    private static RootBackend rootBackend;

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

    private static synchronized RootBackend getOrInitBackend(CommandContext ctx) {
        if (rootBackend != null) {
            return rootBackend;
        }

        // The instance CommandRouter built.
        rootBackend = RootBackend.getInstance();
        if (rootBackend != null) {
            return rootBackend;
        }

        // Same instance, through the context table.
        if (ctx != null && ctx.backends != null) {
            Object shared = ctx.backends.get("root");
            if (shared instanceof RootBackend backend) {
                rootBackend = backend;
                return rootBackend;
            }
        }

        // Last resort. Note: RootBackend(String) takes this as the REGION path,
        // not the binary, and never compiles the engine.
        String bridgePath = "rootbackend/root-bridge";
        try {
            rootBackend = new RootBackend(bridgePath);
            rootBackend.initialize();
        } catch (Exception e) {
            AppLogger.error("Failed to initialize RootBackend: " + e.getMessage(), e);
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
                dispatch(backend, RootBackend.CMD_OPEN_FILE, rawStructure.filepath);
            }

            // Step B: Inject Macro Flags/Options into Cling environment
            if (!rawStructure.macroOptions.isEmpty() || !rawStructure.macroFlags.isEmpty()) {
                String variableBinds = String.join(" ", rawStructure.macroOptions).strip();
                if (!variableBinds.isEmpty()) {
                    dispatch(backend, RootBackend.CMD_CLING_EXEC, variableBinds);
                }
            }

            // Step C: Handle LEVEL 3: Enclosed Snippet execution ([@ snippet.C ...])
            if (rawStructure.hasSnippet) {
                // No CMD_RUN_SCRIPT opcode: cling runs a macro with ".x".
                AppLogger.info("ROOT Bridge: Spawning isolated snippet -> " + rawStructure.filepath);
                dispatch(backend, RootBackend.CMD_CLING_EXEC, ".x " + rawStructure.filepath);
            }

            return;
        }

        // 2. Handle LEVEL 1: Standard Direct Commands (:root)
        boolean isDirectExec = rawStructure.macroFlags.stream().anyMatch("-e"::equalsIgnoreCase) ||
                               rawStructure.macroOptions.stream().anyMatch("--exec"::equalsIgnoreCase);

        String joinedTokens = String.join(" ", rawStructure.macroTokens).strip();
        boolean isRawProtocolCmd = "ping".equalsIgnoreCase(joinedTokens);

        if (isDirectExec || isRawProtocolCmd) {
            if (isRawProtocolCmd) {
                dispatch(backend, RootBackend.CMD_PING, null);
            } else if (!joinedTokens.isEmpty()) {
                dispatch(backend, RootBackend.CMD_CLING_EXEC, joinedTokens);
            }
        } else {
            // Route standard standalone processes (e.g., interactive file checks or batch calculations)
            String fullCommand = "root -b " + joinedTokens;
            AppLogger.info("Launching standalone ROOT process: " + fullCommand);
            ctx.executeBackendSafely("root", fullCommand.strip());
        }
    }

    // The opcode carries the meaning; the payload is the argument alone.
    private static void dispatch(RootBackend backend, short opcode, String payload) {
        final byte[] bytes = (payload == null || payload.isEmpty())
            ? null
            : payload.getBytes(StandardCharsets.UTF_8);

        // Through the backend, which puts a payload longer than the 44 bytes of a
        // message on the shared heap; the bridge's own push cut it short, so
        // ".x <path>" lost the end of any real path. The request id comes from
        // the backend's counter, the one table the pump routes replies through.
        RootProcessBridge bridge = backend.getProcessBridge();
        if (bridge != null) {
            if (!backend.sendCommand(opcode, 0, RootBackend.nextRequestId(), bytes)) {
                AppLogger.error("The ROOT engine did not take the command (its queue or"
                                + " its shared heap is full).");
            }
        } else {
            backend.execute(payload);
        }
    }

    public synchronized void shutdown() {
        synchronized (RootDispatcher.class) {
        if (rootBackend != null) {
            rootBackend.close();
            rootBackend = null;
        }
        }
    }

    public synchronized RootBackend getActiveBackend(CommandContext ctx) {
        return getOrInitBackend(ctx);
    }
}
