package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFiles;
import com.sphere.core.rootio.ObjectWriter;
import com.sphere.core.rootio.RFileWriter;
import com.sphere.core.rootio.Streamers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The class descriptions a ROOT file of HepMC3 events carries: the TList of
 * TStreamerInfo that ROOT 6.20/04 wrote with HepMC3's WriterRootTree (HepMC3
 * test file inputIO4.root), for the tree, its branches and leaves, and the
 * classes HepMC3::GenEventData, GenParticleData, GenVertexData, FourVector and
 * GenRunInfoData. Sphere's writers store it as it is, behind a key of 64 bytes.
 */
final class RootStreamerInfo {

    /** The length of the key the list was written behind; its tags count from there. */
    static final int KEYLEN = 64;
    /** The ROOT version the files are written as (TFile's fVersion), the one these descriptions come from. */
    static final int FILE_VERSION = com.sphere.Sphere.ROOT_FILE_VERSION;
    /** ROOT's default compression then: zlib, level 1. */
    static final int COMPRESSION = 101;
    /** The size of the list once inflated. */
    private static final int SIZE = 19471;

    private static final String DEFLATED_BASE64 = ""
        + "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";

    private static byte[] list;
    private static Streamers streamers;

    private RootStreamerInfo() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** The TList as ROOT wrote it, uncompressed. */
    static synchronized byte[] list() {
        if (list == null) {
            final Inflater inflater = new Inflater();
            inflater.setInput(Base64.getDecoder().decode(DEFLATED_BASE64));
            final ByteArrayOutputStream out = new ByteArrayOutputStream(SIZE);
            final byte[] buf = new byte[8192];
            try {
                while (!inflater.finished()) {
                    final int n = inflater.inflate(buf);
                    if (n == 0 && inflater.needsInput()) break;
                    out.write(buf, 0, n);
                }
            } catch (DataFormatException e) {
                throw new IllegalStateException("the embedded ROOT class descriptions are damaged", e);
            } finally {
                inflater.end();
            }
            list = out.toByteArray();
        }
        return list.clone();
    }

    /** A new ROOT file (TFile::Open with "RECREATE"), in ROOT 6.20/04's format, compressed with zlib level 1. */
    static RFileWriter create(String filename) throws IOException {
        return create(CFiles.path(filename), filename);
    }

    /** The same at a path, recording the name given. */
    static RFileWriter create(Path path, String filename) throws IOException {
        return new RFileWriter(path, filename, "", FILE_VERSION, COMPRESSION, list(), KEYLEN);
    }

    /**
     * The list of only those classes, in that order, as ROOT writes it for a
     * file holding just them (WriterRoot's files: no tree, no rules).
     */
    static byte[] list(String... classNames) {
        final java.util.List<Object> items = new java.util.ArrayList<>();
        for (String name : classNames) {
            for (Object o : streamers().items()) {
                if (o instanceof Streamers.Info info && info.name().equals(name)) items.add(info);
            }
        }
        return Streamers.write(items, KEYLEN, streamers().listBits());
    }

    /**
     * The list a file ends with once HepMC3's classes are added to those it
     * had (TFile::WriteStreamerInfo): its own descriptions first, then the
     * missing ones, then the rules of both; null when it had them all.
     */
    static byte[] merged(Streamers theirs) {
        final java.util.List<Object> infos = new java.util.ArrayList<>();
        final java.util.Set<String> have = new java.util.HashSet<>();
        com.sphere.core.rootio.RList rules = null;
        for (Object o : theirs.items()) {
            if (o instanceof Streamers.Info info) {
                infos.add(info);
                have.add(info.name() + ";" + info.version());
            } else if (o instanceof com.sphere.core.rootio.RList l) {
                rules = l;
            }
        }
        boolean added = false;
        for (Object o : streamers().items()) {
            if (o instanceof Streamers.Info info && have.add(info.name() + ";" + info.version())) {
                infos.add(info);
                added = true;
            } else if (o instanceof com.sphere.core.rootio.RList ours) {
                if (rules == null) {
                    rules = ours;
                } else {
                    for (Object r : ours) {
                        if (!rules.contains(r)) {
                            rules.add(r);
                            added = true;
                        }
                    }
                }
            }
        }
        if (!added) return null;
        if (rules != null) infos.add(rules);
        return Streamers.write(infos, KEYLEN, streamers().listBits());
    }

    /** A writer of HepMC3's data classes, which have no ClassDef (version 0 and checksum). */
    static ObjectWriter objectWriter() {
        return new ObjectWriter(streamers(), Set.of("HepMC3::", "HepMC::"));
    }

    /** The same, read. */
    static synchronized Streamers streamers() {
        if (streamers == null) streamers = Streamers.read(list(), KEYLEN);
        return streamers;
    }
}
