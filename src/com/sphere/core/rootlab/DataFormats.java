package com.sphere.core.rootlab;

import com.sphere.components.rootview.XzBlock;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Recognises the files high-energy physics passes around, by what they hold
 * rather than by their name: ROOT files (and whether they carry an RNTuple),
 * HepMC event records (version 3 and 2), Les Houches event files, YODA
 * histograms (Rivet), SLHA spectra, LHAPDF grids and set descriptions,
 * HEPData records (YAML, JSON), HDF5, Parquet, CSV -- and the same through
 * gzip, xz or Zstandard, since event files are often stored compressed.
 */
public final class DataFormats {

    public enum Kind {
        ROOT("ROOT file", true),
        HEPMC3("HepMC3 event record (Asciiv3)", true),
        HEPMC2("HepMC2 event record (IO_GenEvent)", true),
        LHEF("Les Houches Event File", true),
        YODA("YODA histograms (Rivet)", true),
        SLHA("SLHA spectrum / decay table", true),
        LHAPDF_INFO("LHAPDF set description", true),
        LHAPDF_GRID("LHAPDF grid", true),
        HEPDATA("HEPData record", true),
        HDF5("HDF5 file", true),
        PARQUET("Apache Parquet table", true),
        CSV("CSV / TSV table", true),
        JSON("JSON document", false),
        ZIP("zip archive", false),
        TEXT("text", false),
        BINARY("binary data", false),
        EMPTY("empty file", false);

        public final String label;
        /** True for the formats physics analyses produce and read. */
        public final boolean physics;

        Kind(String label, boolean physics) {
            this.label = label;
            this.physics = physics;
        }
    }

    /** What a file is, through what compression, and a line of detail. */
    public record Sniff(Kind kind, String compression, String detail) {
        public String describe() {
            return kind.label + (compression == null ? "" : " (" + compression + " compressed)")
                 + (detail == null || detail.isEmpty() ? "" : " -- " + detail);
        }
    }

    private DataFormats() {
    }

    public static Sniff sniff(Path path) throws IOException {
        if (Files.size(path) == 0) {
            return new Sniff(Kind.EMPTY, null, "");
        }
        final byte[] head;
        try (InputStream in = Files.newInputStream(path)) {
            head = in.readNBytes(256 * 1024);
        }
        return sniff(head, path.getFileName().toString());
    }

    /** Sniffs the first bytes of a file; the name only breaks ties between text formats. */
    public static Sniff sniff(byte[] head, String name) {
        if (head.length == 0) {
            return new Sniff(Kind.EMPTY, null, "");
        }
        // compressed wrappers: look inside
        if (head.length >= 2 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B) {
            final Sniff inner = inside(gunzip(head), name);
            return new Sniff(inner.kind(), "gzip", inner.detail());
        }
        if (XzBlock.isXz(head, 0, head.length)) {
            final byte[] plain = XzBlock.decodePrefix(head, 0, head.length, 1 << 20);
            if (plain.length == 0) {
                return new Sniff(Kind.BINARY, "xz", "the first chunk of the stream is larger than the part read");
            }
            final Sniff inner = inside(plain, name);
            return new Sniff(inner.kind(), "xz", inner.detail());
        }
        if (head.length >= 4 && (head[0] & 0xFF) == 0x28 && (head[1] & 0xFF) == 0xB5
            && (head[2] & 0xFF) == 0x2F && (head[3] & 0xFF) == 0xFD) {
            return new Sniff(guessFromName(name), "Zstandard", "content not inspected");
        }
        return inside(head, name);
    }

    private static Sniff inside(byte[] b, String name) {
        if (b.length >= 4 && b[0] == 'r' && b[1] == 'o' && b[2] == 'o' && b[3] == 't') {
            return new Sniff(Kind.ROOT, null, rootDetail(b));
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'H' && b[2] == 'D' && b[3] == 'F') {
            return new Sniff(Kind.HDF5, null, "");
        }
        if (b.length >= 4 && b[0] == 'P' && b[1] == 'A' && b[2] == 'R' && b[3] == '1') {
            return new Sniff(Kind.PARQUET, null, "");
        }
        if (b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4) {
            return new Sniff(Kind.ZIP, null, "a .root file stored in it can be opened as archive.zip#file.root");
        }
        if (!looksLikeText(b)) {
            return new Sniff(Kind.BINARY, null, "");
        }
        final String text = new String(b, StandardCharsets.UTF_8);
        if (text.contains("HepMC::Version 3") || text.contains("HepMC::Asciiv3-START_EVENT_LISTING")) {
            return new Sniff(Kind.HEPMC3, null, countLines(text, "E ") + " event(s) in the first part read");
        }
        if (text.contains("HepMC::IO_GenEvent-START_EVENT_LISTING") || text.contains("HepMC::Version 2")) {
            return new Sniff(Kind.HEPMC2, null, countLines(text, "E ") + " event(s) in the first part read");
        }
        if (text.contains("<LesHouchesEvents")) {
            final int at = text.indexOf("version=\"");
            final String version = at >= 0 ? text.substring(at + 9, Math.max(at + 9, text.indexOf('"', at + 9))) : "?";
            return new Sniff(Kind.LHEF, null, "version " + version + ", " + count(text, "<event") + " event(s) in the first part read");
        }
        if (text.contains("BEGIN YODA_") || text.contains("# BEGIN YODA")) {
            return new Sniff(Kind.YODA, null, count(text, "BEGIN YODA_") + " object(s) in the first part read");
        }
        if (text.contains("Format: lhagrid1") || text.contains("Format: lhagrid")) {
            return new Sniff(Kind.LHAPDF_GRID, null, "");
        }
        if (text.contains("SetDesc:") && text.contains("NumMembers:")) {
            return new Sniff(Kind.LHAPDF_INFO, null, "");
        }
        final String upper = text.toUpperCase(Locale.ROOT);
        if ((upper.contains("BLOCK MASS") || upper.contains("BLOCK SMINPUTS") || upper.contains("\nDECAY "))
            && !text.contains("<")) {
            return new Sniff(Kind.SLHA, null, count(upper, "\nBLOCK ") + " block(s)");
        }
        if (text.contains("independent_variables:") && text.contains("dependent_variables:")) {
            return new Sniff(Kind.HEPDATA, null, "YAML table");
        }
        final String trimmed = text.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            if (text.contains("\"independent_variables\"")) {
                return new Sniff(Kind.HEPDATA, null, "JSON table");
            }
            return new Sniff(Kind.JSON, null, "");
        }
        final String csv = csvShape(text);
        if (csv != null) {
            return new Sniff(Kind.CSV, null, csv);
        }
        return new Sniff(Kind.TEXT, null, "");
    }

    private static String rootDetail(byte[] b) {
        if (b.length < 8) {
            return "";
        }
        int version = ((b[4] & 0xFF) << 24) | ((b[5] & 0xFF) << 16) | ((b[6] & 0xFF) << 8) | (b[7] & 0xFF);
        final boolean big = version > 1_000_000;
        if (big) {
            version -= 1_000_000;
        }
        final String v = (version / 10000) + "." + String.format("%02d", (version / 100) % 100) + "/"
                       + String.format("%02d", version % 100);
        final boolean rntuple = contains(b, "ROOT::RNTuple") || contains(b, "ROOT::Experimental::RNTuple");
        return "written by ROOT " + v + (big ? ", large file (64-bit pointers)" : "")
             + (rntuple ? ", holds an RNTuple" : "");
    }

    private static boolean contains(byte[] b, String s) {
        final byte[] needle = s.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + needle.length <= b.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (b[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static Kind guessFromName(String name) {
        final String n = name.toLowerCase(Locale.ROOT);
        if (n.contains(".hepmc")) {
            return Kind.HEPMC3;
        }
        if (n.contains(".lhe")) {
            return Kind.LHEF;
        }
        if (n.contains(".yoda")) {
            return Kind.YODA;
        }
        if (n.contains(".root")) {
            return Kind.ROOT;
        }
        return Kind.BINARY;
    }

    private static byte[] gunzip(byte[] head) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(head))) {
            return in.readNBytes(256 * 1024);
        } catch (IOException truncated) {
            // a partial gzip stream still gives what came before the cut
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(head))) {
                final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                final byte[] buf = new byte[8192];
                int n;
                try {
                    while ((n = in.read(buf)) > 0 && out.size() < 256 * 1024) {
                        out.write(buf, 0, n);
                    }
                } catch (IOException cut) {
                    // keep what was inflated
                }
                return out.toByteArray();
            } catch (IOException notGzip) {
                return new byte[0];
            }
        }
    }

    private static boolean looksLikeText(byte[] b) {
        int bad = 0;
        final int n = Math.min(b.length, 8192);
        for (int i = 0; i < n; i++) {
            final int c = b[i] & 0xFF;
            if (c == 0) {
                return false;
            }
            if (c < 9 || (c > 13 && c < 32)) {
                bad++;
            }
        }
        return bad * 50 < n;
    }

    private static int countLines(String text, String prefix) {
        int n = 0;
        int at = 0;
        while (at < text.length()) {
            final int end = text.indexOf('\n', at);
            final String line = text.substring(at, end < 0 ? text.length() : end);
            if (line.startsWith(prefix)) {
                n++;
            }
            if (end < 0) {
                break;
            }
            at = end + 1;
        }
        return n;
    }

    private static int count(String text, String what) {
        int n = 0;
        int at = text.indexOf(what);
        while (at >= 0) {
            n++;
            at = text.indexOf(what, at + what.length());
        }
        return n;
    }

    /** "n columns, m rows read" when the text has the same number of separators on every line. */
    private static String csvShape(String text) {
        final String[] lines = text.split("\\R");
        if (lines.length < 3) {
            return null;
        }
        for (char sep : new char[] {',', '\t', ';'}) {
            int columns = -1;
            int rows = 0;
            boolean consistent = true;
            for (int i = 0; i < Math.min(lines.length - 1, 200); i++) {
                final String line = lines[i];
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                final int c = (int) line.chars().filter(ch -> ch == sep).count() + 1;
                if (columns < 0) {
                    columns = c;
                } else if (c != columns) {
                    consistent = false;
                    break;
                }
                rows++;
            }
            if (consistent && columns > 1 && rows >= 2) {
                return columns + " columns (" + (sep == '\t' ? "tab" : String.valueOf(sep)) + " separated)";
            }
        }
        return null;
    }
}
