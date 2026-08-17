package com.sphere.core.snippets;

/**
 * Interprets and resolves custom tag macros (e.g., [@ snippet.py]) found within command inputs.
 * Replaces tags with their corresponding absolute filesystem paths while preserving operational arguments.
 */
public class TagInterpreter {

    /**
     * Scans the command string for target tags and evaluates them against the active project ecosystem.
     *
     * @param input         The raw command execution text string.
     * @param activeProject The currently selected active project identifier.
     * @return A sanitized instruction block where metadata tags are swapped for evaluated absolute paths.
     */
    public static String resolve(String input, String activeProject) {
        if (input == null || !input.contains("[@")) {
            return input;
        }

        StringBuilder out = new StringBuilder();
        int i = 0;

        while (i < input.length()) {
            int start = input.indexOf("[@", i);
            if (start < 0) {
                out.append(input.substring(i));
                break;
            }

            out.append(input, i, start);

            int end = input.indexOf("]", start);
            if (end < 0) {
                out.append(input.substring(start));
                break;
            }

            String inside = input.substring(start + 2, end).trim();
            String[] parts = inside.split("\\s+", 2);
            String snippet = parts[0];
            String internalArgs = (parts.length > 1) ? parts[1] : "";

            // Evaluate tag structure using the path locator system
            String resolved = SnippetResolver.resolve(snippet, activeProject);

            out.append("[@ ").append(resolved).append(" ]");
            if (!internalArgs.isEmpty()) {
                out.append(" ").append(internalArgs);
            }

            i = end + 1;
        }

        return out.toString();
    }
}
