package com.sphere.core.hepmc3.validation;

import java.util.List;

/**
 * One of HepMC3's test programs, ported: what ctest runs, in a directory
 * holding the input files it needs, with the same arguments, to be compared
 * with what the C++ printed, returned and wrote.
 *
 * @param name   the program ("testIO1")
 * @param args   the command-line arguments ctest passes
 * @param inputs the input files of the test directory it reads
 * @param rules  how its output is compared
 * @param body   the port of main(), returning main's value
 */
public record TestProgram(String name, String[] args, List<String> inputs, Rules rules, Body body) {

    /** The body of main(): returns what main returns. */
    @FunctionalInterface
    public interface Body {
        int run(TestContext ctx) throws Exception;
    }

    /** How a stream of text is compared with the C++'s. */
    public enum Output {
        /** Line for line. */
        EXACT,
        /** Line for line once both are sorted (lines printed by concurrent threads). */
        SORTED,
        /**
         * The rows of a HEPEVT listing in any order, their numbers and their
         * parents' numbers aside: the C++ orders particles equal for its
         * sort by their addresses, which change from run to run.
         */
        ROWS_ANY_ORDER,
        /** Line for line with the addresses (0x...) masked: they are those of the run. */
        POINTERS,
        /** Line for line with the durations masked: they are those of the run. */
        TIMES,
        /** Not compared (timings only, or a path the C++ does not take on Windows). */
        IGNORE
    }

    /**
     * @param stdout     how std::cout and printf are compared
     * @param stderr     how std::cerr is compared
     * @param reference  whether the C++ output exists (false: the program checks itself, main must return 0)
     * @param note       why a rule departs from the plain comparison, shown in the report
     */
    public record Rules(Output stdout, Output stderr, boolean reference, String note) {
        public static final Rules EXACT = new Rules(Output.EXACT, Output.EXACT, true, "");

        public Rules stdout(Output o, String why) {
            return new Rules(o, stderr, reference, why);
        }

        public static Rules selfCheck(String why) {
            return new Rules(Output.IGNORE, Output.IGNORE, false, why);
        }
    }

    public static TestProgram of(String name, List<String> inputs, Body body) {
        return new TestProgram(name, new String[0], inputs, Rules.EXACT, body);
    }

    public static TestProgram of(String name, List<String> inputs, Rules rules, Body body) {
        return new TestProgram(name, new String[0], inputs, rules, body);
    }

    public TestProgram withArgs(String... a) {
        return new TestProgram(name, a, inputs, rules, body);
    }

    /** The name ctest gives the run: the program, and its argument when it has one ("testIO32_reset"). */
    public String id() {
        return args.length == 0 ? name : name + "_" + args[0];
    }
}
