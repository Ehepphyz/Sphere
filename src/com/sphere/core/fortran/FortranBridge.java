package com.sphere.core.fortran;

import com.sphere.core.bridge.Bridge;
import com.sphere.core.bridge.SpxExport;
import com.sphere.core.rootbackend.RootPdfCatalog;
import com.sphere.core.rootbackend.RootPdfGrid;
import com.sphere.core.rootbackend.RootPdfSet;
import com.sphere.utils.AppLogger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** Only the module folder, for a syntax check; nothing is exported. */
    static List<String> includeFlags(File source, String compiler) {
        if (!uses(read(source))) return List.of();
        try {
            return List.of("-I" + Bridge.fortranObjects(compiler));
        } catch (IOException unavailable) {
            return List.of();
        }
    }

    /**
     * The flags that link the bridge in, after exporting the sets the program
     * names; nothing when the program does not use it.
     */
    static List<String> flagsFor(File source, String compiler) {
        final String text = read(source);
        if (!uses(text)) return List.of();
        try {
            final List<String> flags = Bridge.fortranFlags(compiler);
            final Set<String> names = new LinkedHashSet<>();
            final Matcher m = SET.matcher(text);
            while (m.find()) names.add(bare(m.group(1)));
            for (String name : names) export(name);
            AppLogger.info("LHAPDF's Fortran interface and sphere_spx linked from Sphere's bridge"
                + (names.isEmpty() ? "." : "; set(s) exported: " + String.join(", ", names) + "."));
            return flags;
        } catch (IOException unavailable) {
            AppLogger.error(unavailable.getMessage());
            return List.of();
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
