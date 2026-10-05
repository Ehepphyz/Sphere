package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFiles;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads HepMC3's binary format ("hmpb"): the header and run information on
 * opening, then an event per call until the footer, where the input closes.
 */
public class Readerprotobuf extends Reader {

    private InputStream in;
    private boolean fromFile;

    public Readerprotobuf(Path filename) {
        fromFile = true;
        try {
            in = new BufferedInputStream(Files.newInputStream(filename), 1 << 16);
        } catch (IOException e) {
            Setup.error(100, "Readerprotobuf: Problem opening file: " + filename);
            in = null;
            return;
        }
        readFileStart();
    }

    public Readerprotobuf(String filename) {
        this(CFiles.path(filename));
    }

    public Readerprotobuf(InputStream stream) {
        fromFile = false;
        in = stream;
        readFileStart();
    }

    private boolean readFileStart() {
        final byte[] magic = readN(4);
        if (magic == null || !new String(magic, java.nio.charset.StandardCharsets.ISO_8859_1).equals("hmpb")) {
            Setup.error(100, "Failed to find expected Magic first 4 bytes, is this really a hmpb file?");
            return false;
        }
        int[] md = readDigest();
        if (md == null || md[1] != Protobuf.HEADER || readN(md[0]) == null) {
            Setup.error(100, "Readerprotobuf: Problem parsing start of file, expected to find Header, but instead found message type: "
                + (md == null ? 0 : md[1]));
            return false;
        }
        md = readDigest();
        if (md == null || md[1] != Protobuf.RUN_INFO) {
            Setup.error(100, "Readerprotobuf: Problem parsing start of file, expected to find RunInfo, but instead found message type: "
                + (md == null ? 0 : md[1]));
            return false;
        }
        setRunInfo(new GenRunInfo());
        final byte[] msg = readN(md[0]);
        if (msg == null) {
            close();
            return false;
        }
        Protobuf.readRunInfo(msg, runInfo());
        return true;
    }

    private byte[] readN(int n) {
        if (in == null) return null;
        try {
            final byte[] b = in.readNBytes(n);
            return b.length == n ? b : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** The next digest, {bytes, type}; the input closes at the footer. */
    private int[] readDigest() {
        if (failed()) return null;
        final byte[] ten = readN(10);
        if (ten == null) return null;
        final int[] md = Protobuf.readDigest(ten);
        if (md[1] == Protobuf.FOOTER) close();
        return md;
    }

    private boolean readGenEvent(GenEvent evt) {
        final int[] md = readDigest();
        if (md == null) return false;
        if (md[1] != Protobuf.EVENT) return false;
        final byte[] msg = readN(md[0]);
        if (msg == null) {
            close();
            return false;
        }
        Protobuf.readEvent(msg, evt);
        return true;
    }

    @Override
    public boolean skip(int n) {
        final GenEvent dummy = new GenEvent();
        for (int nn = n; nn > 0; --nn) {
            if (!readGenEvent(dummy)) return false;
        }
        return !failed();
    }

    @Override
    public boolean readEvent(GenEvent evt) {
        if (!readGenEvent(evt)) return false;
        evt.setRunInfo(runInfo());
        return true;
    }

    @Override
    public void close() {
        if (in != null && fromFile) {
            try {
                in.close();
            } catch (IOException ignored) {
                // closing is all that is wanted
            }
        }
        in = null;
    }

    @Override
    public boolean failed() {
        return in == null;
    }
}
