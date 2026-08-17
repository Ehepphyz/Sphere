package com.sphere.core.commandrouterincludes;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Fluent builder for creating hierarchical internal commands.
 */
public class InternalCommandBuilder {

    private final Map<String, InternalCommand> registry = new HashMap<>();

    public InternalCommandDraft create(String name, String description) {
        InternalCommand cmd = new InternalCommand(name, description);
        registry.put(name, cmd);
        return new InternalCommandDraft(cmd);
    }

    public void register(InternalCommand cmd) {
        registry.put(cmd.getName(), cmd);
    }

    public InternalCommand get(String name) {
        return registry.get(name);
    }

    /**
     * Draft class for the fluent construction of commands.
     */
    public static class InternalCommandDraft {
        private final InternalCommand cmd;

        InternalCommandDraft(InternalCommand cmd) {
            this.cmd = cmd;
        }

        public InternalCommandDraft sub(String name, String description,
                                        Consumer<InternalSubcommandCall> action) {
            InternalSubcommand sub = new InternalSubcommand(name, description, action);
            cmd.addSubcommand(sub);
            return this;
        }

        public InternalCommandDraft defaultAction(String description,
                                                  Consumer<InternalSubcommandCall> action) {
            InternalSubcommand sub = new InternalSubcommand("", description, action);
            cmd.setDefaultAction(sub);
            return this;
        }

        public InternalCommand build() {
            return cmd;
        }
    }
}
