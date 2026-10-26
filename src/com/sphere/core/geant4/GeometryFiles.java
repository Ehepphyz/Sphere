package com.sphere.core.geant4;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The files a geometry lives in, and the conversions between them: GDML as
 * G4editor writes it (parametric, for Geant4), portable GDML (numbers, every
 * material written out, for ROOT's GDML reader as well as Geant4's), and ROOT
 * files holding a TGeoManager. A file is recognised by what it holds, not by
 * its name: "root" at its start is ROOT, an XML document is GDML.
 */
public final class GeometryFiles {

    private GeometryFiles() {
    }

    public enum Format {
        GDML("GDML for Geant4 (parametric)", "gdml"),
        GDML_PORTABLE("GDML for ROOT and Geant4 (numbers)", "gdml"),
        ROOT("ROOT geometry (TGeoManager)", "root");

        public final String label;
        public final String extension;

        Format(String label, String extension) {
            this.label = label;
            this.extension = extension;
        }
    }

    /** A design read from a file: the document, the format it came in, and what the reading had to say. */
    public record Loaded(Geometry geometry, Format format, List<String> warnings, List<String> notes) {
    }

    /** The format a file holds, from its first bytes; by its name when it is empty or missing. */
    public static Format detect(Path file) throws IOException {
        if (Files.isRegularFile(file) && Files.size(file) >= 4) {
            try (InputStream in = Files.newInputStream(file)) {
                final byte[] head = in.readNBytes(4);
                if (head[0] == 'r' && head[1] == 'o' && head[2] == 'o' && head[3] == 't') {
                    return Format.ROOT;
                }
            }
            return Format.GDML;
        }
        return byName(file);
    }

    /** The format a file name asks for: .root is ROOT, anything else GDML. */
    public static Format byName(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".root") ? Format.ROOT : Format.GDML;
    }

    /** True for the files G4editor opens: GDML designs, and ROOT files (which may hold a geometry). */
    public static boolean isGeometryFile(Path file) {
        final String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".gdml") || n.endsWith(".root") || n.endsWith(".xml");
    }

    /**
     * Reads a design. For a ROOT file, rootName picks the geometry (null: the
     * first TGeoManager, as TGeoManager::Import takes it).
     */
    public static Loaded read(Path file, String rootName) throws IOException, GeometryException {
        if (detect(file) == Format.ROOT) {
            final TGeoReader.Result r = TGeoReader.read(file, rootName);
            final List<String> notes = new ArrayList<>(r.notes());
            final List<MaterialMatcher.Match> nist = MaterialMatcher.matches(r.geometry());
            if (!nist.isEmpty()) {
                notes.add(nist.size() + " material" + (nist.size() > 1 ? "s are" : " is") + " a NIST one under another name"
                    + " (console 'nist', or Materials > Use NIST equivalents):");
                for (MaterialMatcher.Match m : nist) {
                    notes.add("  " + m.describe());
                }
            }
            return new Loaded(r.geometry(), Format.ROOT, r.warnings(), notes);
        }
        final Gdml.Result r = Gdml.read(file);
        return new Loaded(r.geometry(), Format.GDML, r.warnings(), List.of());
    }

    /** Writes a design in a format; returns what had to be approximated. */
    public static List<String> write(Geometry g, Path file, Format format) throws IOException {
        final List<String> notes = new ArrayList<>();
        switch (format) {
            case ROOT -> {
                final TGeoWriter.Report r = TGeoWriter.write(g, file);
                notes.addAll(r.warnings());
                boolean sensitive = false;
                for (LogicalVolume lv : g.volumes()) {
                    sensitive |= lv.sensitive != null;
                }
                if (sensitive) {
                    notes.add("ROOT has no sensitive detectors: the SensDet marks stay in the GDML only");
                }
            }
            case GDML_PORTABLE -> Gdml.writePortable(g, file, notes);
            default -> Gdml.write(g, file);
        }
        return notes;
    }

    /** Reads one file and writes it in another format, without opening it in the editor. */
    public static List<String> convert(Path in, Path out, Format format) throws IOException, GeometryException {
        final Loaded l = read(in, null);
        final List<String> notes = new ArrayList<>();
        for (String w : l.warnings()) {
            notes.add("reading: " + w);
        }
        for (String w : write(l.geometry(), out, format)) {
            notes.add("writing: " + w);
        }
        return notes;
    }
}
