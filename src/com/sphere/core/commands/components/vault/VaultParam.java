package com.sphere.components.vault;

import java.util.List;
import java.util.Locale;

/**
 * One argument a snippet or a macro takes, read from its own source.
 *
 * Where it was read from decides how it is written back: an argparse option
 * is "--name value", a positional of sys.argv is the value alone, and a
 * parameter of a ROOT macro's function is one argument of the call
 * macro.C(3, "abc"), in order.
 *
 * @param flag    "--events" for an option, null for a positional
 * @param cppType the type as the C++ declared it, for a macro's function
 */
public record VaultParam(String name, Type type, String flag, String defaultValue,
                         String help, List<String> choices, boolean required, String cppType) {

    public enum Type { INT, REAL, BOOL, TEXT, PATH, CHOICE, RAW }

    public VaultParam {
        name = name == null ? "" : name;
        defaultValue = defaultValue == null ? "" : defaultValue;
        help = help == null ? "" : help;
        choices = choices == null ? List.of() : List.copyOf(choices);
        cppType = cppType == null ? "" : cppType;
    }

    public boolean isOption() {
        return flag != null && !flag.isEmpty();
    }

    /** The label of its field: the option as typed, or the parameter name. */
    public String label() {
        return isOption() ? flag : name;
    }

    /** Whether a value typed in its field is one it accepts. */
    public boolean accepts(String value) {
        final String v = value == null ? "" : value.strip();
        if (v.isEmpty()) {
            return !required;
        }
        return switch (type) {
            case INT -> v.matches("[-+]?\\d+[uUlL]*") || v.matches("0[xX][0-9a-fA-F]+");
            case REAL -> v.matches("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?[fFlL]?")
                || v.equalsIgnoreCase("inf") || v.equalsIgnoreCase("nan");
            case BOOL -> v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false");
            case CHOICE -> choices.isEmpty() || choices.contains(v);
            default -> true;
        };
    }

    /** A name that reads as a file or a folder, so its field offers a chooser. */
    static boolean looksLikePath(String name) {
        final String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.matches(".*(file|path|dir|folder|input|output|^in$|^out$|root|csv|lhe|hepmc).*");
    }
}
