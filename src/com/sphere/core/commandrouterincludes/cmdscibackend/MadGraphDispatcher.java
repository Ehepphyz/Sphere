package com.sphere.core.commandrouterincludes.cmdscibackend;

import com.sphere.core.commandrouterincludes.CommandContext;
import com.sphere.core.commandrouterincludes.ParsedCommand;

public class MadGraphDispatcher extends ScientificBaseDispatcher {

    @Override
    protected String getToolName() { return "madgraph"; }

    @Override
    protected String getBinaryName() { return "mg5_aMC"; }

    @Override
    public boolean canHandle(String input) {
        return input != null && input.startsWith(":madgraph");
    }

    @Override
    protected void executeScientificCommand(String resolvedArgs, ParsedCommand commandStructure, CommandContext ctx) {
        String fullCommand = resolvedArgs;
        ctx.executeBackendSafely("madgraph", fullCommand.trim());
    }
}
