package com.sphere.core.telemetry;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a command line says about itself.
 *
 * The runners already hold the command they are about to run, so the tool, the
 * source and the flags are there for the taking rather than worth passing down
 * through every call.
 */
public final class CommandFacts {

    private static final List<String> SOURCE_ENDINGS =
        List.of(".cpp", ".cc", ".cxx", ".c", ".c++", ".f90", ".f95", ".f03", ".f",
                ".py", ".jl");

    private CommandFacts() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** The program being called, without the path it was found at. */
    public static String toolOf(List<String> command) {
        if (command == null || command.isEmpty()) {
            return "";
        }
        return new File(command.get(0)).getName();
    }

    /** The first token that looks like a source file, or the last plain token. */
    public static String sourceOf(List<String> command) {
        if (command == null || command.isEmpty()) {
            return "";
        }
        for (String token : command) {
            final String lower = token.toLowerCase(Locale.ROOT);
            for (String ending : SOURCE_ENDINGS) {
                if (lower.endsWith(ending)) {
                    return token;
                }
            }
        }
        // A compiled program has no source on its line; its own name identifies it.
        return command.size() > 1 ? "" : new File(command.get(0)).getName();
    }

    /** The options, without the output file that -o carries with it. */
    public static List<String> flagsOf(List<String> command) {
        List<String> flags = new ArrayList<>();
        if (command == null) {
            return flags;
        }
        for (int i = 1; i < command.size(); i++) {
            final String token = command.get(i);
            if ("-o".equals(token)) {
                i++;
                continue;
            }
            if (token.startsWith("-")) {
                flags.add(token);
            }
        }
        return flags;
    }

    /** The size of what -o names, when it was produced. */
    public static long producedBytes(List<String> command) {
        if (command == null) {
            return RunRecord.UNKNOWN;
        }
        for (int i = 0; i + 1 < command.size(); i++) {
            if ("-o".equals(command.get(i))) {
                File produced = new File(command.get(i + 1));
                return produced.isFile() ? produced.length() : RunRecord.UNKNOWN;
            }
        }
        return RunRecord.UNKNOWN;
    }

    /** How many warnings and errors a compiler wrote, counted in its own output. */
    public static int[] diagnosticsIn(String output) {
        if (output == null || output.isEmpty()) {
            return new int[] {0, 0};
        }
        int warnings = 0;
        int errors = 0;
        for (String line : output.split("\n")) {
            final String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("warning:")) {
                warnings++;
            } else if (lower.contains("error:") || lower.contains("fatal error:")) {
                errors++;
            }
        }
        return new int[] {warnings, errors};
    }
}
