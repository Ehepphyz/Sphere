package com.sphere.core.rootlab.suites;

import com.sphere.components.rootview.RootFile;
import com.sphere.core.rootlab.RootFsck;
import com.sphere.core.rootlab.bench.LabContext;
import com.sphere.core.rootlab.bench.LabFailure;
import com.sphere.core.rootlab.bench.LabSuite;
import com.sphere.core.rootlab.bench.LabTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sphere's file check run over the ROOT test files, and over copies of them
 * damaged on purpose: a healthy file must come out healthy, a truncated one
 * must be reported as cut off, a flipped bit inside a compressed record must
 * be caught by the record's checksum, and a file whose key list is lost must
 * still give back its objects by walking its records.
 */
public final class IntegritySuite implements LabSuite {

    private static final String[] FILES = {
        "stressHistogram.5.18.00.root", "linearIO.root", "hist4_5.root", "hist6_8.root"};

    @Override
    public String id() {
        return "fsck";
    }

    @Override
    public String title() {
        return "Integrity of ROOT files (records, checksums, directories, recovery)";
    }

    @Override
    public String origin() {
        return "TFile::Recover and TFile::GetCompressionFactor's record walk, on the files of root/test";
    }

    @Override
    public String description() {
        return "The ROOT test files checked record by record; damaged copies (truncated, a flipped bit, a "
             + "lost key list) must be diagnosed, and objects recovered.";
    }

    @Override
    public List<LabTest> tests() {
        final List<LabTest> tests = new ArrayList<>();
        for (String f : FILES) {
            tests.add(new LabTest("healthy-" + f, "Healthy: " + f, ctx -> healthy(ctx, f)));
        }
        tests.add(new LabTest("truncated", "A truncated file is reported as cut off", IntegritySuite::truncated));
        tests.add(new LabTest("bitflip", "A flipped bit in a compressed record is caught", IntegritySuite::bitflip));
        tests.add(new LabTest("recover", "A lost key list: objects recovered from the records", IntegritySuite::lostKeys));
        return tests;
    }

    private static RootFsck.Report fsck(Path p) throws Exception {
        try (RootFile f = new RootFile(p)) {
            return RootFsck.check(f, RootFsck.Options.full(), null, null);
        }
    }

    private static void healthy(LabContext ctx, String name) throws Exception {
        final RootFsck.Report r = fsck(LabContext.resource(name));
        ctx.detail(String.format(Locale.ROOT, "health %d/100 (%s): %d records, %d blocks verified, %d objects read, "
            + "compression factor %.3f", r.score(), r.verdict(), r.records, r.blocksVerified, r.objectsRead,
            r.compressionFactor));
        for (RootFsck.Finding f : r.findings) {
            ctx.detail(f.toString());
        }
        ctx.check(r.count(RootFsck.Severity.ERROR) == 0, "errors found in an intact file");
        ctx.check(r.records > 0, "no records found");
    }

    private static Path copy(LabContext ctx, String name) throws Exception {
        final Path target = ctx.scratch().resolve("damaged-" + System.nanoTime() + "-" + name);
        Files.copy(LabContext.resource(name), target);
        return target;
    }

    private static void truncated(LabContext ctx) throws Exception {
        final Path p = copy(ctx, "stressHistogram.5.18.00.root");
        final byte[] all = Files.readAllBytes(p);
        Files.write(p, java.util.Arrays.copyOf(all, all.length * 2 / 3));
        final RootFsck.Report r = fsck(p);
        ctx.detail(r.verdict() + ", health " + r.score());
        for (RootFsck.Finding f : r.findings) {
            ctx.detail(f.toString());
        }
        ctx.check(r.findings.stream().anyMatch(f -> f.what().contains("cut off")), "the truncation was not reported");
    }

    /**
     * The ROOT 5 test files are compressed with the old ROOT routine, which
     * carries no checksum: a flipped bit there decodes to wrong numbers in
     * ROOT as anywhere else. So the histogram is first copied, with Sphere's
     * writer, into a file compressed with zlib (ROOT's default since 5.30),
     * whose Adler-32 must then catch the damage. The copy itself must read back
     * as the original histogram.
     */
    private static void bitflip(LabContext ctx) throws Exception {
        final Path p = ctx.scratch().resolve("zlib-copy.root");
        try (RootFile in = new RootFile(LabContext.resource("stressHistogram.5.18.00.root"))) {
            final var info = in.streamerInfoKey();
            com.sphere.components.rootview.RootKey h = null;
            for (var n : in.tree().children) {
                if (n.name.equals("rr1D-h1")) {
                    h = n.key;
                }
            }
            ctx.check(h != null, "rr1D-h1 not found");
            final byte[] list = in.payload(info);
            final byte[] object = in.payload(h);
            try (com.sphere.core.rootio.RFileWriter w = new com.sphere.core.rootio.RFileWriter(
                    p, "zlib-copy.root", "", com.sphere.Sphere.ROOT_FILE_VERSION, 101, list, info.keylen)) {
                w.writeObject(h.className, h.name, h.title, keylen -> object);
            }
        }
        final RootFsck.Report clean = fsck(p);
        ctx.detail("copy written with zlib: health " + clean.score() + ", " + clean.algorithms.keySet());
        ctx.check(clean.count(RootFsck.Severity.ERROR) == 0, "the zlib copy is not healthy: " + clean.findings);
        try (com.sphere.core.rootio.RootIO a = com.sphere.core.rootio.RootIO.open(LabContext.resource("stressHistogram.5.18.00.root"));
             com.sphere.core.rootio.RootIO b = com.sphere.core.rootio.RootIO.open(p)) {
            final var ha = com.sphere.core.rootlab.hist.LabHist.fromObject((com.sphere.core.rootio.RObject) a.read("rr1D-h1"));
            final var hb = com.sphere.core.rootlab.hist.LabHist.fromObject((com.sphere.core.rootio.RObject) b.read("rr1D-h1"));
            final var same = com.sphere.core.rootlab.hist.HistCompare.equals(ha, hb, com.sphere.core.rootlab.hist.HistCompare.OPT_STATS, 0);
            ctx.check(same.ok(), "the copy does not read back as the original: " + same);
        }
        long middle = -1;
        long trailer = -1;
        try (RootFile f = new RootFile(p)) {
            for (RootFile.Record rec : f.scanRecords().records) {
                if (!rec.gap && rec.key.compressed() && rec.key.className.equals("TH1D")) {
                    middle = rec.key.payloadAt() + 9 + rec.key.storedBytes() / 2;
                    trailer = rec.key.payloadAt() + rec.key.storedBytes() - 2;    // inside the Adler-32
                }
            }
        }
        ctx.check(middle > 0, "the copy of the histogram was not compressed");
        final byte[] pristine = Files.readAllBytes(p);
        // A bit of the checksum itself: the data still inflates, only the
        // Adler-32 comparison can tell. Then a byte of the data: a single bit
        // there can, rarely, leave the output unchanged, a whole byte cannot.
        final long[][] damages = {{trailer, 0x01}, {middle, 0xFF}};
        final String[] what = {"a bit of the Adler-32 checksum", "a byte in the middle of the deflate data"};
        for (int i = 0; i < damages.length; i++) {
            final byte[] all = pristine.clone();
            all[(int) damages[i][0]] ^= (byte) damages[i][1];
            Files.write(p, all);
            final RootFsck.Report r = fsck(p);
            ctx.detail("damaged " + what[i] + " at byte " + damages[i][0] + ":");
            for (RootFsck.Finding f : r.findings) {
                if (f.severity() != RootFsck.Severity.INFO) {
                    ctx.detail("  " + f);
                }
            }
            ctx.check(r.count(RootFsck.Severity.ERROR) > 0, "damage to " + what[i] + " went unnoticed");
        }
    }

    private static void lostKeys(LabContext ctx) throws Exception {
        final Path p = copy(ctx, "linearIO.root");
        final byte[] all = Files.readAllBytes(p);
        // the TDirectory record of the TFile: name and title strings, then the
        // directory block; zero its fSeekKeys as a job that never closed the file leaves it
        long seekKeysAt;
        try (RootFile f = new RootFile(p)) {
            final var top = f.keyAt(f.getBegin());
            int at = (int) top.payloadAt();
            at += 1 + (all[at] & 0xFF);                   // fName
            at += 1 + (all[at] & 0xFF);                   // fTitle
            final int version = ((all[at] & 0xFF) << 8) | (all[at + 1] & 0xFF);
            at += 2 + 4 * 4;                              // version, datimes, nbytes
            seekKeysAt = at + (version > 1000 ? 16 : 8);  // past fSeekDir and fSeekParent
            for (int i = 0; i < (version > 1000 ? 8 : 4); i++) {
                all[(int) seekKeysAt + i] = 0;
            }
        }
        Files.write(p, all);
        final RootFsck.Report r = fsck(p);
        ctx.detail(r.verdict() + "; " + r.recovered.size() + " objects recovered");
        for (var n : r.recovered) {
            ctx.detail("  " + n.className + " " + n.name);
        }
        ctx.check(r.keyListMissing, "the missing key list was not noticed");
        final long matrices = r.recovered.stream().filter(n -> n.className.startsWith("TMatrix")
            || n.className.startsWith("TVector")).count();
        if (matrices != 7) {
            throw new LabFailure(matrices + " of the 7 matrices and vectors were recovered");
        }
    }
}
