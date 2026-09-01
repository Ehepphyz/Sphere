package com.sphere.core.rootbackend;

import java.util.Map;

/**
 * Callback interface to decouple UI prompts from the core backend logic.
 * This will be implemented by the UI layer to show the review dialog.
 */
public interface RootDetectionCallback {
    boolean onRootDetected(String detectedPath, Map<String, String> environment);
}