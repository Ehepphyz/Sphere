package com.sphere.core.commandrouterincludes;

import com.sphere.utils.AppLogger;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages and delegates commands to the appropriate CommandDispatcher based on priority.
 * Thread-safe implementation using an inherently sorted insertion strategy.
 */
public class CommandRegistry {

    // Internal wrapper to manage priority during dispatching
    private record PrioritizedDispatcher(CommandDispatcher dispatcher, int priority) {}

    // CopyOnWriteArrayList provides thread-safe traversal over dynamic plugin updates
    private final List<PrioritizedDispatcher> dispatchers = new CopyOnWriteArrayList<>();

    /**
     * Registers a new dispatcher to the registry with default priority (0).
     */
    public void addDispatcher(CommandDispatcher dispatcher) {
        addDispatcher(dispatcher, 0);
    }

    /**
     * Registers a new dispatcher with a specific priority.
     * Higher values are checked first.
     */
    public synchronized void addDispatcher(CommandDispatcher dispatcher, int priority) {
        PrioritizedDispatcher newPd = new PrioritizedDispatcher(dispatcher, priority);
        
        // Find the precise sorted location to avoid sorting the whole list over again
        int index = 0;
        for (PrioritizedDispatcher pd : dispatchers) {
            if (priority > pd.priority()) {
                break;
            }
            index++;
        }
        dispatchers.add(index, newPd);
    }

    /**
     * Dispatches the input string to the first capable dispatcher found.
     *
     * @param input The raw command input from the user.
     * @param ctx   The current command context.
     */
    public void dispatch(String input, CommandContext ctx) {
        for (PrioritizedDispatcher pd : dispatchers) {
            if (pd.dispatcher().canHandle(input)) {
                pd.dispatcher().handle(input, ctx);
                return;
            }
        }
        
        AppLogger.error("No dispatcher found to handle command: " + input);
    }
}
