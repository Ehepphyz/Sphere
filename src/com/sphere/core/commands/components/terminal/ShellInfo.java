package com.sphere.components.terminal;

import java.util.List;

/**
 * A shell Sphere can open: what to run, the arguments that make it interactive,
 * and a line to type once it is up.
 */
public class ShellInfo {
    public final String name;
    public final String command;
    /** Arguments that load the shell's own rc file, so aliases and prompt exist. */
    public final List<String> arguments;
    /** Sent to the shell after start, for environments such as conda; may be null. */
    public final String initCommand;

    public ShellInfo(String name, String command) {
        this(name, command, List.of(), null);
    }

    public ShellInfo(String name, String command, List<String> arguments, String initCommand) {
        this.name = name;
        this.command = command;
        this.arguments = arguments == null ? List.of() : List.copyOf(arguments);
        this.initCommand = initCommand;
    }

    /** The path belongs to the identity: two entries differ by what they launch. */
    @Override
    public String toString() {
        return name + " (" + command + ")";
    }
}
