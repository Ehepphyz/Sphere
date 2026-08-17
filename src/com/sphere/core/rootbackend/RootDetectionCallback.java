package com.sphere.core.rootbackend;

import java.util.Map;

/**
 * Callback interface to decouple UI prompts from the core backend logic.
 * This will be implemented by the UI layer to show the review dialog.
 */
public interface RootDetectionCallback {
    /**
     * Triggered when a ROOT installation is auto-detected, but differs from 
     * the configured ROOT_DIR (or if ROOT_DIR was empty)
     */
    boolean onRootDetected(String detectedPath, Map<String, String> environment);
}