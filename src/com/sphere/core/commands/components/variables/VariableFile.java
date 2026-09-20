package com.sphere.components.variables;

import com.sphere.components.variables.VariableStore.Variable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The file a program leaves behind to say what its variables were.
 *
 * One line per variable, three fields separated by tabs: name, type, value. A
 * program that has ended cannot be asked anything, so this is the only way its
 * state can be seen; the format is deliberately poor so that Fortran can write
 * it in three lines and a shell script in one.
 *
 * The file's own name gives the source, which is what lets a language be added
 * without touching any code: dropping herwig.vars in the folder makes a Herwig
 * section appear.
 */
public final class VariableFile {

    /** The extension a file must carry to be read at all. */
    public static final String EXTENSION = ".vars";

    /** A cap per file, so a runaway program cannot fill memory. */
    private static final int MAX_LINES = 20000;

    private VariableFile() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static boolean isVariableFile(Path file) {
        return file != null
            && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(EXTENSION);
    }

    /** The source a file publishes under: its name without the extension. */
    public static String sourceOf(Path file) {
        final String name = file.getFileName().toString();
        final int dot = name.length() - EXTENSION.length();
        return dot <= 0 ? name : name.substring(0, dot).toLowerCase(Locale.ROOT);
    }

    /** Reads one file. Lines that are blank, commented or malformed are skipped. */
    public static List<Variable> read(Path file) throws IOException {
        final String source = sourceOf(file);
        List<Variable> variables = new ArrayList<>();
        int taken = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (++taken > MAX_LINES) {
                break;
            }
            if (line.isBlank() || line.charAt(0) == '#') {
                continue;
            }
            final String[] fields = line.split("\t", -1);
            if (fields[0].isBlank()) {
                continue;
            }
            variables.add(new Variable(source, unescape(fields[0]),
                                       fields.length > 1 ? unescape(fields[1]) : "",
                                       fields.length > 2 ? unescape(fields[2]) : ""));
        }
        return variables;
    }

    /** Writes one source's variables, for Sphere's own use and for the helpers. */
    public static void write(Path file, List<Variable> variables) throws IOException {
        StringBuilder text = new StringBuilder("# name\ttype\tvalue\n");
        for (Variable variable : variables) {
            text.append(escape(variable.name())).append('\t')
                .append(escape(variable.type())).append('\t')
                .append(escape(variable.value())).append('\n');
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
    }

    /** Tabs and line breaks would split a line, so they travel written out. */
    public static String escape(String field) {
        if (field == null) {
            return "";
        }
        return field.replace("\\", "\\\\").replace("\t", "\\t")
                    .replace("\r", "").replace("\n", "\\n");
    }

    public static String unescape(String field) {
        if (field == null || field.indexOf('\\') < 0) {
            return field == null ? "" : field;
        }
        StringBuilder plain = new StringBuilder(field.length());
        for (int i = 0; i < field.length(); i++) {
            final char c = field.charAt(i);
            if (c != '\\' || i + 1 >= field.length()) {
                plain.append(c);
                continue;
            }
            final char next = field.charAt(++i);
            switch (next) {
                case 't' -> plain.append('\t');
                case 'n' -> plain.append('\n');
                case '\\' -> plain.append('\\');
                default -> plain.append('\\').append(next);
            }
        }
        return plain.toString();
    }
}
