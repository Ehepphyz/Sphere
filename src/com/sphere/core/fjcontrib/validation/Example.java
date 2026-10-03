package com.sphere.core.fjcontrib.validation;

import java.io.BufferedReader;

/**
 * One of fjcontrib's example programs, ported: what "make check" runs, on
 * the same data file, with the same arguments, to be compared with the
 * output of the C++ recorded in the contrib (the .ref file).
 *
 * @param contrib the contrib's directory name ("RecursiveTools")
 * @param name    the program ("example_softdrop"), which names the .ref
 * @param data    the input file in fjcontrib's data directory
 * @param args    the command-line arguments make check passes
 * @param program the port
 */
public record Example(String contrib, String name, String data, String[] args, Program program) {

    /** The body of main(), writing to cout what the C++ writes. */
    @FunctionalInterface
    public interface Program {
        void run(BufferedReader cin, Cout cout, String[] args) throws Exception;
    }

    public static Example of(String contrib, String name, String data, Program program, String... args) {
        return new Example(contrib, name, data, args, program);
    }

    public String id() {
        return contrib + "/" + name;
    }
}
