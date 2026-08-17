package com.sphere.core.commandrouterincludes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Represents a subcommand within an internal command structure.
 */
public class InternalSubcommand {
    
    private final String name;
    private final String description;
    private final List<String> parameters;
    private final Consumer<InternalSubcommandCall> action;

    /**
     * Constructs a new InternalSubcommand.
     *
     * @param name        The name of the subcommand.
     * @param description A brief description of what the subcommand does.
     * @param action      The logic to execute when this subcommand is called.
     */
    public InternalSubcommand(String name, String description, Consumer<InternalSubcommandCall> action) {
        this.name = name;
        this.description = description;
        this.action = action;
        this.parameters = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getParameters() {
        return parameters;
    }

    public Consumer<InternalSubcommandCall> getAction() {
        return action;
    }
}
