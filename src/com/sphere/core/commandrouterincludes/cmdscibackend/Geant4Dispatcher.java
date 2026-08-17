package com.sphere.core.commandrouterincludes.cmdscibackend;

import com.sphere.core.commandrouterincludes.CommandContext;
import com.sphere.core.commandrouterincludes.ParsedCommand;

public class Geant4Dispatcher extends ScientificBaseDispatcher {

    @Override
    protected String getToolName() { return "geant4"; }

    @Override
    protected String getBinaryName() { return "run_g4"; }

    @Override
    public boolean canHandle(String input) {
        return input != null && input.startsWith(":geant4");
    }

    @Override
    protected void executeScientificCommand(String resolvedArgs, ParsedCommand commandStructure, CommandContext ctx) {
        String fullCommand = "run_g4 " + resolvedArgs;
        ctx.executeBackendSafely("geant4", fullCommand.trim());
    }
}
