package com.sphere.components.workspace;

import com.sphere.components.WorkspaceManager.PresetRule;
import com.sphere.utils.AppLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * High-performance JSON parser utility tailored for decoding structured scientific rules, 
 * software release configurations, and metadata constraint matrices warning-free.
 */
public final class MinimalPresetJson {

    private MinimalPresetJson() {
        throw new UnsupportedOperationException("Utility architectural provider class cannot be instantiated.");
    }

    public static Map<String, PresetRule> loadPresetRules(String jsonString) {
        if (jsonString == null || jsonString.isBlank()) {
            return new LinkedHashMap<>();
        }

        Object rootObject = MinimalJson.parse(jsonString);

        if (!(rootObject instanceof Map<?, ?> rawRootMap)) {
            AppLogger.error("Preset Schema Exception: Core JSON text layout does not map to an object root block.");
            return new LinkedHashMap<>();
        }

        Map<String, PresetRule> structuredPresetRules = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : rawRootMap.entrySet()) {
            if (!(entry.getKey() instanceof String experimentKey)) {
                continue;
            }

            Object rulePayloadValue = entry.getValue();

            if (!(rulePayloadValue instanceof Map<?, ?> ruleSpecsMap)) {
                // FIX: Fallback to AppLogger.info
                AppLogger.info("Skipping unstructured payload item listed under experiment target identifier: " + experimentKey);
                continue;
            }

            Object allowedPrefixesContainer = ruleSpecsMap.get("allowedPrefixes");
            if (!(allowedPrefixesContainer instanceof List<?> matchingPrefixesList)) {
                continue; 
            }

            List<String> validatedPrefixes = matchingPrefixesList.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(String::trim)
                    .filter(trimmedString -> !trimmedString.isEmpty())
                    .toList();

            structuredPresetRules.put(experimentKey, new PresetRule(new ArrayList<>(validatedPrefixes)));
        }

        return structuredPresetRules;
    }
}
