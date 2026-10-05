package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFiles;
import com.sphere.core.hepmc3.cxx.COutput;

import java.io.OutputStream;
import java.nio.file.Path;

/**
 * Writes HepMC3's binary format: "hmpb", then for each message a ten-byte
 * digest (size and type) and the message itself: a Header, the run
 * information, the events, and a Footer counting them.
 */
public class Writerprotobuf extends Writer {

    private final COutput out;
    private final boolean ownsFile;
    private long eventsWritten;
    private long eventBytesWritten;
    private boolean closed;

    public Writerprotobuf(Path filename, GenRunInfo run) {
        setRunInfo(run != null ? run : new GenRunInfo());
        out = COutput.create(filename);
        ownsFile = true;
        if (!out.isOpen()) {
            Setup.error(100, "Writerprotobuf: problem opening file: " + filename);
            return;
        }
        startFile();
    }

    public Writerprotobuf(Path filename) {
        this(filename, null);
    }

    public Writerprotobuf(String filename, GenRunInfo run) {
        this(CFiles.path(filename), run);
    }

    public Writerprotobuf(OutputStream stream, GenRunInfo run) {
        setRunInfo(run != null ? run : new GenRunInfo());
        out = new COutput(stream);
        ownsFile = false;
        startFile();
    }

    private void startFile() {
        out.write("hmpb");
        writeMessage(Protobuf.header(), Protobuf.HEADER);
        writeRunInfo();
    }

    private long writeMessage(byte[] msg, int type) {
        final byte[] md = Protobuf.digest(msg.length, type);
        if (md.length != 10) {
            Setup.error(100, "When writing protobuf message, the message digest was not the expected length (10 bytes), but was instead "
                + md.length + " bytes.");
        }
        out.writeBytes(md, 0, md.length);
        out.writeBytes(msg, 0, msg.length);
        return md.length + msg.length;
    }

    @Override
    public void writeEvent(GenEvent evt) {
        eventBytesWritten += writeMessage(Protobuf.event(evt), Protobuf.EVENT);
        eventsWritten++;
    }

    public void writeRunInfo() {
        writeMessage(Protobuf.runInfo(runInfo()), Protobuf.RUN_INFO);
    }

    @Override
    public void close() {
        if (closed || failed()) return;
        closed = true;
        if (eventsWritten == 0) Setup.error(100, "No events were written, the output file will not be parseable.");
        writeMessage(Protobuf.footer(eventsWritten, eventBytesWritten), Protobuf.FOOTER);
        if (ownsFile) out.close();
        else out.flush();
    }

    @Override
    public boolean failed() {
        return out.failed() || (ownsFile && !out.isOpen() && !closed);
    }
}
