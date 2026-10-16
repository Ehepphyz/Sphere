package com.sphere.core.rootlab.suites;

import com.sphere.core.rootio.RObject;
import com.sphere.core.rootio.RootIO;
import com.sphere.core.rootio.RootLocation;
import com.sphere.core.rootlab.bench.LabContext;
import com.sphere.core.rootlab.bench.LabFailure;
import com.sphere.core.rootlab.bench.LabSuite;
import com.sphere.core.rootlab.bench.LabTest;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ROOT files inside a zip archive, the case stressIOPlugins' test 5 and the
 * zip part of test 4 cover with multi_8.zip (TZIPFile): a member stored
 * without compression is read in place, by the offset of its data, named by
 * "archive.zip#member" or by "archive.zip?zip=N". The archive is built here
 * from the reference files, since the one on the ROOT server is gone.
 */
public final class ArchiveSuite implements LabSuite {

    @Override
    public String id() {
        return "zip";
    }

    @Override
    public String title() {
        return "ROOT files inside a zip archive (TZIPFile)";
    }

    @Override
    public String origin() {
        return "root/test/stressIOPlugins.cxx, test 5 and the zip cases of test 4";
    }

    @Override
    public String description() {
        return "A zip archive holding ROOT files: each member opened in place by name or by index; a "
             + "compressed member refused with an explanation.";
    }

    @Override
    public List<LabTest> tests() {
        final Path[] archive = new Path[1];
        return List.of(
            new LabTest("build", "Build multi.zip (stored members)", ctx -> archive[0] = build(ctx)),
            new LabTest("byName", "sub-file name in fragment (multi.zip#linearIO.root)", ctx -> byName(ctx, archive[0])),
            new LabTest("byIndex", "sub-file index in query (multi.zip?myq=xyz&zip=1)", ctx -> byIndex(ctx, archive[0])),
            new LabTest("deflated", "a compressed member is refused, with the reason", ctx -> deflated(ctx, archive[0])));
    }

    private static void stored(ZipOutputStream zip, String name, byte[] data) throws IOException {
        final ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        final CRC32 crc = new CRC32();
        crc.update(data);
        e.setCrc(crc.getValue());
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }

    private static Path build(LabContext ctx) throws Exception {
        final Path zipPath = ctx.scratch().resolve("multi.zip");
        try (OutputStream out = Files.newOutputStream(zipPath); ZipOutputStream zip = new ZipOutputStream(out)) {
            stored(zip, "linearIO.root", Files.readAllBytes(LabContext.resource("linearIO.root")));
            stored(zip, "hist4_5.root", Files.readAllBytes(LabContext.resource("hist4_5.root")));
            final ZipEntry deflated = new ZipEntry("deflated.root");
            deflated.setMethod(ZipEntry.DEFLATED);
            zip.putNextEntry(deflated);
            zip.write(Files.readAllBytes(LabContext.resource("hist6_8.root")));
            zip.closeEntry();
        }
        ctx.detail(zipPath.getFileName() + ": " + Files.size(zipPath) + " bytes, 3 members");
        return zipPath;
    }

    private static void byName(LabContext ctx, Path zip) throws Exception {
        ctx.check(zip != null, "the archive was not built");
        final String spec = zip + "#linearIO.root";
        try (RootIO io = RootIO.open(spec)) {
            final Object md1 = io.read("md1");
            ctx.check(md1 instanceof RObject r && r.integer("fNrows") == 11, "md1 did not read from inside the archive");
            ctx.detail(spec + ": " + io.paths().size() + " keys, md1 read");
        }
    }

    private static void byIndex(LabContext ctx, Path zip) throws Exception {
        ctx.check(zip != null, "the archive was not built");
        final String spec = zip + "?myq=xyz&zip=1";
        final RootLocation loc = RootLocation.parse(spec);
        ctx.check(loc.zipIndex == 1, "zip=1 was not understood: " + loc);
        try (RootIO io = RootIO.open(spec)) {
            final int keys = io.paths().size();
            ctx.detail(spec + ": " + keys + " keys (hist4_5.root)");
            ctx.check(keys == 16, keys + " keys, expected the 16 of hist4_5.root");
        }
    }

    private static void deflated(LabContext ctx, Path zip) throws Exception {
        ctx.check(zip != null, "the archive was not built");
        RootIO io = null;
        try {
            io = RootIO.open(zip + "#deflated.root");
        } catch (IOException refused) {
            ctx.detail("refused: " + refused.getMessage());
            ctx.check(refused.getMessage().contains("compressed"), "refused, but without saying why: " + refused.getMessage());
            return;
        }
        io.close();
        throw new LabFailure("a compressed member was opened as if it were stored");
    }
}
