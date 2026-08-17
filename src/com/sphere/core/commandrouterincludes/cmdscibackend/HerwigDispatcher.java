package com.sphere.core.commandrouterincludes.cmdscibackend;

import com.sphere.core.commandrouterincludes.CommandContext;
import com.sphere.core.commandrouterincludes.ParsedCommand;

public class HerwigDispatcher extends ScientificBaseDispatcher {

    @Override
    protected String getToolName() { return "herwig"; }

    @Override
    protected String getBinaryName() { return "Herwig"; }

    @Override
    public boolean canHandle(String input) {
        return input != null && input.startsWith(":herwig");
    }

    @Override
    protected void executeScientificCommand(String resolvedArgs, ParsedCommand commandStructure, CommandContext ctx) {
        String fullCommand = "read " + resolvedArgs;
        ctx.executeBackendSafely("herwig", fullCommand.trim());
    }
}
