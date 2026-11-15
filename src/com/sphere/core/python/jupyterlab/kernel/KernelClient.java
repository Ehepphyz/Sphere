package com.sphere.core.python.jupyterlab.kernel;

import java.io.IOException;
import java.util.List;

/**
 * What the notebook asks of a kernel, whichever kind it is: Sphere's stdio
 * driver or a Jupyter kernel spoken to over its own protocol. Every request
 * returns the id its answers will carry; -1 means the kernel cannot answer it.
 */
public interface KernelClient {

    void start() throws IOException;

    boolean isRunning();

    void shutdown();

    void restart() throws IOException;

    /** Runs code as a cell: counted, kept in the history, shown. */
    int execute(String code);

    void interrupt();

    int complete(String code, int cursor);

    /** Documentation of the name under the cursor (Shift+Tab). */
    int inspect(String code, int cursor);

    /** The live namespace, for the Variables tab. */
    int variables();

    /**
     * For each cell, the names it uses that nothing defines (no cell of its own,
     * no kernel variable, no builtin), and the kind of every live name it uses.
     */
    int analyze(List<String> cells);

    void answerInput(String text);

    /**
     * What a live value looks like (the distribution of its numbers, a frame's
     * columns, a tree's branches), for the notebook's tooltips; -1 when this
     * kernel cannot say.
     */
    default int peek(String name) {
        return -1;
    }

    /** The kernel's memory and CPU (Jupyter's usage_request); -1 when it cannot say. */
    default int usage() {
        return -1;
    }

    /**
     * Runs statements on the namespace as no cell does: no counter, no history,
     * no output (a reactive notebook deleting the variables a cell no longer
     * defines, a rename moving a value to its new name); -1 when it cannot.
     */
    default int effect(String code) {
        return -1;
    }

    /** "Python 3.14.3 · ipykernel 7.2.0" once known, else what is being started. */
    String describe();
}
