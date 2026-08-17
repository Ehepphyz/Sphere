package com.sphere.core.commandrouterincludes;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents the execution call data for an internal subcommand.
 * This class is immutable to ensure consistency during command execution.
 */
public final class InternalSubcommandCall {
    private final CommandContext context;
    private final String rawArgs;
    private final List<String> args;
    private final Map<String, String> options;

    public InternalSubcommandCall(CommandContext context,
                                  String rawArgs,
                                  List<String> args,
                                  Map<String, String> options) {
        this.context = context;
        this.rawArgs = rawArgs;
        // Defensive copies to ensure immutability
        this.args = (args != null) ? Collections.unmodifiableList(args) : Collections.emptyList();
        this.options = (options != null) ? Collections.unmodifiableMap(options) : Collections.emptyMap();
    }

    // Getters
    public CommandContext getContext() { return context; }
    public String getRawArgs() { return rawArgs; }
    public List<String> getArgs() { return args; }
    public Map<String, String> getOptions() { return options; }
}
