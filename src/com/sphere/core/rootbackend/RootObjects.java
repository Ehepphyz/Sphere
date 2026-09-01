package com.sphere.core.rootbackend;

import java.io.IOException;

/**
 * High-level native ROOT resource abstractions (Files and Histograms).
 * Designed for asynchronous, lock-free dispatching over the root-bridge v2 MPMC Shared Memory architecture.
 */
public final class RootObjects {

    public static final class RootFile implements AutoCloseable {
        private final RootProcessBridge bridge;
        private final String handleId;
        private final int fileId;
        private final boolean valid;
        private final String errorMessage;

        private static final java.util.concurrent.atomic.AtomicInteger NEXT_FILE_ID =
            new java.util.concurrent.atomic.AtomicInteger(1);

        // Requests an open on the engine.
        public RootFile(RootProcessBridge bridge, String handleId, String path, String mode) {
            this.bridge = bridge;
            this.handleId = handleId;
            this.fileId = NEXT_FILE_ID.getAndIncrement();

            if (bridge == null || !bridge.isAlive()) {
                this.valid = false;
                this.errorMessage = "RootProcessBridge daemon is offline or uninitialized.";
                return;
            }
            if (path == null || path.isBlank()) {
                this.valid = false;
                this.errorMessage = "No file path given.";
                return;
            }

            byte[] payload = path.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (payload.length > RootBackend.BRIDGE_INLINE_CAPACITY) {
                this.valid = false;
                this.errorMessage = "File path exceeds the "
                    + RootBackend.BRIDGE_INLINE_CAPACITY + " byte inline payload.";
                return;
            }

            boolean pushed = bridge.pushCommand(RootBackend.CMD_OPEN_FILE,
                                                fileId, fileId, payload);
            if (pushed) {
                this.valid = true;
                this.errorMessage = "";
            } else {
                this.valid = false;
                this.errorMessage = "Command ring is full; the open was not queued.";
            }
        }

        public int getFileId() {
            return fileId;
        }

        /**
         * Checks whether the command dispatch to open the native ROOT file succeeded.
         */
        public boolean isValid() {
            return valid;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getHandleId() {
            return handleId;
        }

        /**
         * Dispatches an asynchronous request over SHM to load a histogram handle
         */
        public RootHistogram getHistogram(String name, String histHandleId) throws IOException {
            if (!valid) {
                throw new IOException("Cannot fetch histogram. Parent file resource is invalid: " + errorMessage);
            }

            boolean pushed = bridge.pushCommand("GET_HIST " + this.handleId + " " + histHandleId + " " + name);
            if (!pushed) {
                throw new IOException("Failed to push GET_HIST command for '" + name + "' into MPMC Ring.");
            }

            return new RootHistogram(bridge, histHandleId);
        }

        @Override
        public void close() {
            if (valid && bridge != null && handleId != null && !handleId.isEmpty()) {
                bridge.pushCommand(RootBackend.CMD_CLOSE_FILE, fileId, fileId, null);
            }
        }
    }

    public static final class RootHistogram implements AutoCloseable {
        private final RootProcessBridge bridge;
        private final String handleId;

        public RootHistogram(RootProcessBridge bridge, String handleId) {
            this.bridge = bridge;
            this.handleId = handleId;
        }

        public String getHandleId() {
            return handleId;
        }

        /**
         * Pushes an instruction to bind this histogram inside Cling JIT workspace.
         */
        public boolean bindToCling(String variableName, String rootClassName) {
            if (bridge == null || !bridge.isAlive()) {
                return false;
            }
            return bridge.pushCommand("CLING_BIND " + variableName + " " + rootClassName + " " + this.handleId);
        }

        /**
         * Asynchronously triggers a raw bin dump for this histogram over the SHM Event Ring.
         */
        public boolean requestBinDump() {
            if (bridge == null || !bridge.isAlive()) {
                return false;
            }
            return bridge.pushCommand("DUMP_HIST_BINS " + this.handleId);
        }

        @Override
        public void close() {
            if (bridge != null && handleId != null && !handleId.isEmpty()) {
                bridge.pushCommand("CLOSE " + this.handleId); // no opcode for this yet
            }
        }

        /**
         * Record container for off-heap deserialized bin payload events.
         */
        public record BinData(int index, double center, double content) {}
    }
}