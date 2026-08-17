package com.sphere.core.commandrouterincludes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents a primary internal command that can contain multiple subcommands.
 */
public class InternalCommand {
    private final String name;
    private final String description;
    private final Map<String, InternalSubcommand> subcommands = new LinkedHashMap<>();
    private InternalSubcommand defaultAction;

    public InternalCommand(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public void addSubcommand(InternalSubcommand subcommand) {
        subcommands.put(subcommand.getName(), subcommand);
    }

    // Getters and Setters
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Map<String, InternalSubcommand> getSubcommands() { return subcommands; }
    
    public InternalSubcommand getDefaultAction() { return defaultAction; }
    public void setDefaultAction(InternalSubcommand defaultAction) { this.defaultAction = defaultAction; }
}
