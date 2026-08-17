package com.sphere.core.commandrouterincludes;

import com.sphere.core.Backend;
import com.sphere.utils.AppLogger;

import java.util.Map;
import java.util.ServiceLoader;

/**
 * Handles the dynamic loading of Backend implementations using the Java ServiceLoader API.
 */
public class BackendLoader {

    /**
     * Loads available Backend service providers into the provided target map.
     *
     * @param target A map to store the loaded backends, keyed by their identifier.
     */
    public static void loadBackends(Map<String, Backend> target) {
        ServiceLoader<Backend> loader = ServiceLoader.load(Backend.class);
        
        for (Backend backend : loader) {
            try {
                String name = backend.getName();
                
                // Validate backend identity before registration
                if (name != null && !name.isBlank()) {
                    target.put(name, backend);
                    AppLogger.info("Backend successfully loaded: " + name);
                } else {
                    AppLogger.error("Skipping backend: Name is null or blank.");
                }
            } catch (Exception e) {
                AppLogger.error("Failed to load backend instance: " + e.getMessage());
            }
        }
    }
}
