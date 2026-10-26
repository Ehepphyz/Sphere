package com.sphere.components.vault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the vault does to files: make one from a template, rename, copy, put
 * aside and bring back, import, and apply a fix.
 *
 * Nothing is ever deleted. A file sent away goes to the .vault-trash folder of
 * its own root, which the index does not list, and comes back with Undo.
 * Every path is checked to stay under one of the vault's folders.
 */
final class VaultFiles {

    /** A file put aside, so it can come back. */
    record Trashed(Path from, Path to, String key) {
    }

    /** A starting point for a new item. */
    enum Template {
        PYTHON("Python snippet", ".py", VaultItem.Kind.SNIPPET),
        ROOT_MACRO("ROOT macro", ".C", VaultItem.Kind.MACRO),
        CPP("C++ program", ".cpp", VaultItem.Kind.SNIPPET),
        JULIA("Julia script", ".jl", VaultItem.Kind.SNIPPET),
        FORTRAN("Fortran program", ".f90", VaultItem.Kind.SNIPPET),
        JS("JavaScript (node)", ".js", VaultItem.Kind.SNIPPET),
        EMPTY("Empty file", "", VaultItem.Kind.SNIPPET);

        final String label;
        final String extension;
        final VaultItem.Kind kind;

        Template(String label, String extension, VaultItem.Kind kind) {
            this.label = label;
            this.extension = extension;
            this.kind = kind;
        }
    }

    private VaultFiles() {
    }

    // ---- creating -------------------------------------------------------------

    /**
     * Writes a new file from a template. A ROOT macro's function takes the file's
     * name, since that is the function .x calls, so a name C++ cannot spell is
     * made into one it can before anything is written.
     */
    static Path create(Path root, String requested, Template template) throws IOException {
        String name = requested.strip().replace('\\', '/');
        if (name.isEmpty()) throw new IOException("A name is needed.");
        String folder = "";
        final int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            folder = name.substring(0, slash + 1);
            name = name.substring(slash + 1);
        }
        if (template != Template.EMPTY && !name.contains(".")) name = name + template.extension;
        String stem = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        final String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
        if (template == Template.ROOT_MACRO || template == Template.FORTRAN) {
            final String identifier = identifier(stem);
            if (!identifier.equals(stem)) {
                stem = identifier;
                name = stem + ext;
            }
        }
        final Path target = inside(root, folder + name);
        if (Files.exists(target)) throw new FileAlreadyExistsException(target.getFileName().toString());
        Files.createDirectories(target.getParent());
        Files.writeString(target, body(template, stem), StandardCharsets.UTF_8);
        return target;
    }

    static String identifier(String text) {
        String id = text.replaceAll("[^A-Za-z0-9_]", "_");
        if (id.isEmpty() || Character.isDigit(id.charAt(0))) id = "m_" + id;
        return id;
    }

    private static String body(Template template, String stem) {
        final String nl = System.lineSeparator();
        final String text = switch (template) {
            case PYTHON -> """
                #!/usr/bin/env python3
                \"\"\"STEM: one sentence on what it does.

                @tags
                \"\"\"
                import argparse


                def main():
                    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
                    parser.add_argument("--events", type=int, default=1000, help="how many events")
                    parser.add_argument("--plot", action="store_true", help="draw the result")
                    args = parser.parse_args()
                    print(f"STEM: {args.events} events")


                if __name__ == "__main__":
                    main()
                """;
            case ROOT_MACRO -> """
                // STEM: one sentence on what the macro does.
                // @tags
                #include "TCanvas.h"
                #include "TH1D.h"
                #include "TRandom3.h"

                /// @param nEvents how many values are drawn
                /// @param title   the histogram's title
                void STEM(int nEvents = 10000, const char *title = "STEM")
                {
                   TRandom3 random(0);
                   auto *h = new TH1D("h_STEM", title, 100, -4, 4);
                   for (int i = 0; i < nEvents; ++i) {
                      h->Fill(random.Gaus());
                   }
                   auto *c = new TCanvas("c_STEM", title);
                   h->Draw();
                   c->Update();
                }
                """;
            case CPP -> """
                // STEM: one sentence on what the program does.
                // @arg n:int=10 how many times
                #include <cstdlib>
                #include <iostream>

                int main(int argc, char **argv)
                {
                    const int n = argc > 1 ? std::atoi(argv[1]) : 10;
                    std::cout << "STEM: " << n << '\\n';
                    return 0;
                }
                """;
            case JULIA -> """
                # STEM: one sentence on what it does.
                # @arg n:int=10 how many times

                n = length(ARGS) >= 1 ? parse(Int, ARGS[1]) : 10
                println("STEM: ", n)
                """;
            case FORTRAN -> """
                ! STEM: one sentence on what the program does.
                program STEM
                  implicit none
                  print *, 'STEM'
                end program STEM
                """;
            case JS -> """
                // STEM: one sentence on what it does.
                // @arg n:int=10 how many times
                const n = Number(process.argv[2] ?? 10);
                console.log(`STEM: ${n}`);
                """;
            case EMPTY -> "";
        };
        return text.replace("STEM", stem).replace("\n", nl);
    }

    // ---- moving -------------------------------------------------------------------

    static Path rename(VaultItem item, String requested) throws IOException {
        final String name = requested.strip().replace('\\', '/');
        if (name.isEmpty()) throw new IOException("A name is needed.");
        final Path target = name.contains("/")
            ? inside(item.root, name)
            : inside(item.root, item.folder() + name);
        if (target.equals(item.path)) return target;
        if (Files.exists(target) && !sameFileIgnoringCase(target, item.path)) {
            throw new FileAlreadyExistsException(target.getFileName().toString());
        }
        Files.createDirectories(target.getParent());
        Files.move(item.path, target);
        return target;
    }

    /** On Windows a.C and a.c are one file: renaming between them is a case change, not a clash. */
    private static boolean sameFileIgnoringCase(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    static Path duplicate(VaultItem item) throws IOException {
        final String stem = item.stem();
        final String ext = item.extension();
        for (int i = 2; i < 100; i++) {
            final String suffix = item.isRootMacro() ? "_v" + i : "_copy" + (i == 2 ? "" : i);
            final Path target = item.path.resolveSibling(stem + suffix + ext);
            if (!Files.exists(target)) {
                Files.copy(item.path, target);
                if (item.isRootMacro() && item.info.entry != null) {
                    // The copy's function follows its new name, or .x would not find it.
                    final String text = Files.readString(target, StandardCharsets.UTF_8);
                    final String renamed = text.replaceAll("\\b" + java.util.regex.Pattern.quote(item.info.entry)
                        + "\\s*\\(", java.util.regex.Matcher.quoteReplacement(stem + suffix) + "(");
                    Files.writeString(target, renamed, StandardCharsets.UTF_8);
                }
                return target;
            }
        }
        throw new IOException("Too many copies of " + item.name());
    }

    /** Moves the file between snippets/ and user_scripts/, in the same layer. */
    static Path moveTo(VaultItem item, Path otherRoot) throws IOException {
        final Path target = inside(otherRoot, item.name());
        if (Files.exists(target)) throw new FileAlreadyExistsException(target.getFileName().toString());
        Files.createDirectories(target.getParent());
        Files.move(item.path, target);
        return target;
    }

    static Trashed trash(VaultItem item) throws IOException {
        final Path bin = item.root.resolve(VaultIndex.TRASH);
        Files.createDirectories(bin);
        final String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        final Path target = bin.resolve(item.relative.replace('/', '~') + "." + stamp);
        Files.move(item.path, target);
        return new Trashed(item.path, target, item.key());
    }

    static void restore(Trashed trashed) throws IOException {
        if (Files.exists(trashed.from())) {
            throw new FileAlreadyExistsException(trashed.from().getFileName().toString());
        }
        Files.createDirectories(trashed.from().getParent());
        Files.move(trashed.to(), trashed.from());
    }

    static List<Path> importFiles(List<java.io.File> sources, Path root) throws IOException {
        final List<Path> done = new ArrayList<>();
        for (java.io.File source : sources) {
            if (source.isDirectory()) {
                final Path base = source.toPath();
                try (var walk = Files.walk(base, 6)) {
                    for (Path p : (Iterable<Path>) walk::iterator) {
                        if (!Files.isRegularFile(p)) continue;
                        final Path target = inside(root, source.getName() + "/"
                            + base.relativize(p).toString().replace('\\', '/'));
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                        done.add(target);
                    }
                }
            } else if (source.isFile()) {
                final Path target = inside(root, source.getName());
                Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
                done.add(target);
            }
        }
        return done;
    }

    // ---- fixes --------------------------------------------------------------------------

    /** Applies one fix the analysis offered. Returns the file as it is afterwards. */
    static Path apply(VaultItem item, VaultAnalysis.Fix fix) throws IOException {
        return switch (fix.action()) {
            case RENAME -> rename(item, fix.value());
            case ADD_INCLUDES -> addIncludes(item.path, fix.value().split("\n"));
        };
    }

    /**
     * Puts the #include lines after the last one the file has, or after its
     * leading comment when it has none, keeping its own line ends.
     */
    static Path addIncludes(Path file, String[] headers) throws IOException {
        final String text = Files.readString(file, StandardCharsets.UTF_8);
        final String nl = text.contains("\r\n") ? "\r\n" : "\n";
        final String[] lines = text.split("\\R", -1);
        int insertAt = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].strip().matches("#\\s*include\\b.*")) insertAt = i + 1;
        }
        if (insertAt < 0) {
            insertAt = 0;
            boolean inBlock = false;
            for (int i = 0; i < lines.length; i++) {
                final String t = lines[i].strip();
                if (inBlock) {
                    if (t.contains("*/")) inBlock = false;
                    insertAt = i + 1;
                } else if (t.startsWith("//")) {
                    insertAt = i + 1;
                } else if (t.startsWith("/*")) {
                    inBlock = !t.contains("*/");
                    insertAt = i + 1;
                } else {
                    break;
                }
            }
        }
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == insertAt) {
                for (String header : headers) {
                    if (!header.isBlank()) out.append("#include \"").append(header.strip()).append('"').append(nl);
                }
            }
            out.append(lines[i]);
            if (i < lines.length - 1) out.append(nl);
        }
        if (insertAt >= lines.length) {
            for (String header : headers) out.append(nl).append("#include \"").append(header.strip()).append('"');
        }
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        return file;
    }

    // ---- the fence ------------------------------------------------------------------------

    /** A path under the root, refused when the name would climb out of it. */
    static Path inside(Path root, String relative) throws IOException {
        final Path base = root.toAbsolutePath().normalize();
        final Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base) || target.equals(base)) {
            throw new IOException("\"" + relative + "\" is not a name inside " + base.getFileName());
        }
        final String name = target.getFileName().toString();
        if (name.matches(".*[<>:\"|?*].*") || name.toLowerCase(Locale.ROOT).matches("(con|prn|aux|nul|com\\d|lpt\\d)(\\..*)?")) {
            throw new IOException("\"" + name + "\" is not a name this system accepts");
        }
        return target;
    }
}
