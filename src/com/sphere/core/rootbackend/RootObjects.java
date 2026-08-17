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
        private final boolean valid;
        private final String errorMessage;

        public RootFile(RootProcessBridge bridge, String handleId, String path, String mode) {
            this.bridge = bridge;
            this.handleId = handleId;
            
            if (bridge == null || !bridge.isAlive()) {
                this.valid = false;
                this.errorMessage = "RootProcessBridge daemon is offline or uninitialized.";
                return;
            }

            boolean pushed = bridge.pushCommand("OPEN_FILE " + handleId + " " + path + " " + mode);
            if (pushed) {
                this.valid = true;
                this.errorMessage = "";
            } else {
                this.valid = false;
                this.errorMessage = "Failed to push OPEN_FILE command into the MPMC Command Ring.";
            }
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
         * Dispatches an asynchronous request over SHM to load a histogram handle.
         *
         * @param name Name of the histogram within the ROOT file.
         * @param histHandleId Generated unique handle ID for tracking in event ring callbacks.
         * @return Instantiated RootHistogram representation for the given handle.
         * @throws IOException If the parent file resource is invalid or bridge fails to enqueue.
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
                bridge.pushCommand("CLOSE " + this.handleId);
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
                bridge.pushCommand("CLOSE " + this.handleId);
            }
        }

        /**
         * Record container for off-heap deserialized bin payload events.
         */
        public record BinData(int index, double center, double content) {}
    }
}