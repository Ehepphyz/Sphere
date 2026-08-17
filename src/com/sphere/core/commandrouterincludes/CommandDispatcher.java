package com.sphere.core.commandrouterincludes;

/**
 * Defines the contract for command dispatchers.
 * Implementations should determine if they can handle a specific input and then execute the appropriate logic.
 */
public interface CommandDispatcher {
    
    /**
     * Checks if this dispatcher is capable of handling the provided input.
     *
     * @param input The raw input string from the user.
     * @return true if this dispatcher can process the input, false otherwise.
     */
    boolean canHandle(String input);

    /**
     * Executes the command logic associated with the input.
     *
     * @param input The raw input string.
     * @param ctx   The current execution context.
     */
    void handle(String input, CommandContext ctx);
}
