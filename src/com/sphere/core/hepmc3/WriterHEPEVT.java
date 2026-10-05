package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFiles;
import com.sphere.core.hepmc3.cxx.CFormat;
import com.sphere.core.hepmc3.cxx.COutput;

import java.io.OutputStream;
import java.nio.file.Path;

/**
 * Writes events as HEPEVT text records: "E% 8i %8i" then two lines per
 * particle in % 8i and % 19.8E, or one shorter line when vertex positions
 * are left out.
 */
public class WriterHEPEVT extends Writer {

    protected final COutput out;
    private final boolean ownsFile;
    protected int eventsCount;
    protected final HEPEVT hepevt = new HEPEVT(100000);

    public WriterHEPEVT(Path filename, GenRunInfo run) {
        Setup.warning(900, "WriterHEPEVT::WriterHEPEVT: HEPEVT format is outdated. Please use HepMC3 format instead.");
        out = COutput.create(filename);
        ownsFile = true;
    }

    public WriterHEPEVT(Path filename) {
        this(filename, null);
    }

    public WriterHEPEVT(String filename) {
        this(CFiles.path(filename), null);
    }

    public WriterHEPEVT(OutputStream stream, GenRunInfo run) {
        this(new COutput(stream), run);
    }

    public WriterHEPEVT(COutput stream, GenRunInfo run) {
        Setup.warning(900, "WriterHEPEVT::WriterHEPEVT: HEPEVT format is outdated. Please use HepMC3 format instead.");
        out = stream;
        ownsFile = false;
    }

    /** The block the last event went through. */
    public HEPEVT hepevt() {
        return hepevt;
    }

    public void writeHepevtParticle(int index, boolean iflong) {
        final StringBuilder b = new StringBuilder(256);
        b.append(CFormat.sprintf("% 8i% 8i", hepevt.status(index), hepevt.id(index)));
        if (iflong) {
            b.append(CFormat.sprintf("% 8i% 8i", hepevt.firstParent(index), hepevt.lastParent(index)));
            b.append(CFormat.sprintf("% 8i% 8i", hepevt.firstChild(index), hepevt.lastChild(index)));
            b.append(CFormat.sprintf("% 19.8E% 19.8E% 19.8E% 19.8E% 19.8E\n", hepevt.px(index), hepevt.py(index),
                hepevt.pz(index), hepevt.e(index), hepevt.m(index)));
            b.append(CFormat.sprintf("%-48s% 19.8E% 19.8E% 19.8E% 19.8E\n", " ", hepevt.x(index), hepevt.y(index),
                hepevt.z(index), hepevt.t(index)));
        } else {
            b.append(CFormat.sprintf("% 8i% 8i", hepevt.firstChild(index), hepevt.lastChild(index)));
            b.append(CFormat.sprintf("% 19.8E% 19.8E% 19.8E% 19.8E\n", hepevt.px(index), hepevt.py(index),
                hepevt.pz(index), hepevt.m(index)));
        }
        out.write(b);
    }

    public void writeHepevtEventHeader() {
        out.write(CFormat.sprintf("E% 8i %8i\n", hepevt.eventNumber(), hepevt.numberEntries()));
    }

    @Override
    public void writeEvent(GenEvent evt) {
        hepevt.fromGenEvent(evt);
        hepevt.fixDaughters();
        writeHepevtEventHeader();
        for (int i = 1; i <= hepevt.numberEntries(); ++i) writeHepevtParticle(i, getVerticesPositionsPresent());
        eventsCount++;
    }

    @Override
    public void close() {
        if (ownsFile) out.close();
        else out.flush();
    }

    @Override
    public boolean failed() {
        return out.failed();
    }

    /**
     * As in HepMC3, true adds the option "vertices_positions_are_absent" and
     * false removes it (the flag reads the other way round).
     */
    public void setVerticesPositionsPresent(boolean iflong) {
        if (iflong) options.put("vertices_positions_are_absent", "");
        else options.remove("vertices_positions_are_absent");
    }

    public boolean getVerticesPositionsPresent() {
        return !options.containsKey("vertices_positions_are_absent");
    }
}
