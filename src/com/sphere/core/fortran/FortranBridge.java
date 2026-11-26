package com.sphere.core.fortran;

import com.sphere.core.bridge.Bridge;
import com.sphere.core.bridge.SpxExport;
import com.sphere.core.golem95.Golem95;
import com.sphere.core.golem95.GolemNative;
import com.sphere.core.rootbackend.RootPdfCatalog;
import com.sphere.core.rootbackend.RootPdfGrid;
import com.sphere.core.rootbackend.RootPdfSet;
import com.sphere.utils.AppLogger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a Fortran program gets from the bridge without asking for it.
 *
 * A program written against LHAPDF's Fortran interface calls InitPDFsetByName
 * and evolvePDF and expects LHAPDF to be linked in; on Windows it rarely can
 * be. When the source calls those routines, or uses the sphere_spx module,
 * the bridge's compiled reader and its LHAPDF interface are linked in instead,
 * and every set the program names in a literal is exported for it beforehand,
 * from the same grids Sphere's own commands read. The program is not changed
 * and does not know.
 *
 * A program that uses Golem95's modules (matrice_s, form_factor_4p ...) is
 * given Golem95 the same way: an installation of the machine if there is one,
 * else the library Sphere builds once per compiler from the Golem95 and OneLOop
 * sources it carries.
 */
final class FortranBridge {

    /** The routines of LHAPDF's Fortran interface, and the module itself. */
    private static final Pattern USES = Pattern.compile(
        "(?im)^\\s*use\\s+sphere_spx\\b|\\b(initpdfsetbyname|initpdfset|evolvepdf|alphaspdf|initpdf|numberpdf)"
            + "m?\\s*\\(");

    /** A set named in a literal: InitPDFsetByName('CT18NLO') or InitPDFsetByNameM(2, "NNPDF40_nlo_as_01180"). */
    private static final Pattern SET = Pattern.compile(
        "(?i)\\binitpdfset(?:byname)?m?\\s*\\(\\s*(?:\\w+\\s*,\\s*)?['\"]([^'\"]+)['\"]");

    private FortranBridge() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    private static String read(File source) {
        try {
            return Files.readString(source.toPath(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            try {
                return Files.readString(source.toPath(), StandardCharsets.ISO_8859_1);
            } catch (IOException alsoUnreadable) {
                return "";
            }
        }
    }

    static boolean uses(String text) {
        return USES.matcher(text).find();
    }

    /** A program that uses Golem95's modules (matrice_s, form_factor_4p, ...). */
    static boolean usesGolem(String text) {
        return GolemNative.USES.matcher(text).find();
    }

    /** Only the module folders, for a syntax check; nothing is exported, and Golem95 is not built for it. */
    static List<String> includeFlags(File source, String compiler) {
        final String text = read(source);
        final List<String> flags = new ArrayList<>();
        if (uses(text)) {
            try {
                flags.add("-I" + Bridge.fortranObjects(compiler));
            } catch (IOException unavailable) {
                // the check goes on without it
            }
        }
        if (usesGolem(text)) {
            final GolemNative.Library golem = GolemNative.built(compiler);
            final GolemNative.Library found = golem != null ? golem : GolemNative.find();
            if (found != null) flags.addAll(found.compileFlags());
        }
        return flags;
    }

    /**
     * The flags that link the bridge in, after exporting the sets the program
     * names; and Golem95's modules when the program uses them. Nothing when it
     * uses neither.
     */
    static List<String> flagsFor(File source, String compiler) {
        final String text = read(source);
        final List<String> flags = new ArrayList<>();
        if (uses(text)) {
            try {
                flags.addAll(Bridge.fortranFlags(compiler));
                final Set<String> names = new LinkedHashSet<>();
                final Matcher m = SET.matcher(text);
                while (m.find()) names.add(bare(m.group(1)));
                for (String name : names) export(name);
                AppLogger.info("LHAPDF's Fortran interface and sphere_spx linked from Sphere's bridge"
                    + (names.isEmpty() ? "." : "; set(s) exported: " + String.join(", ", names) + "."));
            } catch (IOException unavailable) {
                AppLogger.error(unavailable.getMessage());
            }
        }
        if (usesGolem(text)) {
            final GolemNative.Library golem = golem(compiler);
            if (golem != null) {
                flags.addAll(golem.compileFlags());
                AppLogger.info("Golem95 " + Golem95.VERSION + " linked (" + golem.archive() + ").");
            }
        }
        return flags;
    }

    /** What goes after the program's source on the compiler's line: the Golem95 library, when it is used. */
    static List<String> librariesFor(File source, String compiler) {
        if (!usesGolem(read(source))) return List.of();
        final GolemNative.Library golem = golem(compiler);
        return golem == null ? List.of() : golem.linkFlags();
    }

    /**
     * The Golem95 a program is linked against: one installed (GOLEM95_DIR, pkg-config), else the one Sphere
     * builds once for this compiler from the sources it carries.
     */
    private static GolemNative.Library golem(String compiler) {
        final GolemNative.Library found = GolemNative.find();
        if (found != null && !found.origin().startsWith("built by Sphere")) return found;
        try {
            GolemNative.Library built = GolemNative.built(compiler);
            if (built == null) {
                AppLogger.info("The program uses Golem95: building Golem95 " + Golem95.VERSION + " and OneLOop "
                    + Golem95.ONELOOP_VERSION + " for " + compiler + " from the sources Sphere carries (once; a few minutes).");
                built = GolemNative.build(compiler, AppLogger::info);
            }
            return built;
        } catch (IOException failed) {
            AppLogger.error(failed.getMessage());
            return null;
        }
    }

    /** LHAPDF 5 names carried a suffix and sometimes a folder; the set is the stem. */
    static String bare(String name) {
        String out = name.trim().replace('\\', '/');
        out = out.substring(out.lastIndexOf('/') + 1);
        for (String suffix : new String[]{".LHgrid", ".LHpdf", ".spx"}) {
            if (out.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT))) {
                out = out.substring(0, out.length() - suffix.length());
            }
        }
        return out;
    }

    /**
     * Exports one set with every member, unless an export newer than the set's
     * files is already there. A set that is not installed is said, with the
     * command that installs it: the program would otherwise stop at run time
     * with less to go on.
     */
    private static void export(String name) {
        final Path folder = RootPdfCatalog.find(name);
        if (folder == null) {
            AppLogger.warn("The program names the PDF set " + name + ", which is not installed. "
                + "':lpdf install " + name + "' fetches it.");
            return;
        }
        final Path target = Bridge.folder().resolve(SpxExport.safeName(name) + ".spx");
        try {
            if (Files.isRegularFile(target)) {
                final long exported = Files.getLastModifiedTime(target).toMillis();
                boolean stale;
                try (var files = Files.list(folder)) {
                    stale = files.anyMatch(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() > exported;
                        } catch (IOException e) {
                            return true;
                        }
                    });
                }
                if (!stale) return;
            }
            final RootPdfSet set = RootPdfCatalog.open(name, Integer.MAX_VALUE, RootPdfGrid.Accuracy.LHAPDF);
            SpxExport.pdf(set, target);
        } catch (IOException | RuntimeException failed) {
            AppLogger.error("Could not export " + name + " for the program: " + failed.getMessage());
        }
    }
}
