package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFiles;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.CInput;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads HEPEVT records written as text: a line "E event entries", then per
 * particle a line of status, id, mothers, daughters, px py pz e m and a line
 * of x y z t (or, with the option "vertices_positions_are_absent", one line
 * of status id daughters px py pz m).
 */
public class ReaderHEPEVT extends Reader {

    private final CInput in;
    private final boolean isStream;
    private final HEPEVT hepevt = new HEPEVT(100000);

    public ReaderHEPEVT(Path filename) {
        in = CInput.open(filename);
        isStream = false;
        if (!in.isOpen()) {
            Setup.error(100, "ReaderHEPEVT: could not open input file: " + filename);
        } else {
            setRunInfo(new GenRunInfo());
        }
    }

    public ReaderHEPEVT(String filename) {
        this(CFiles.path(filename));
    }

    public ReaderHEPEVT(InputStream stream) {
        this(new CInput(stream));
    }

    public ReaderHEPEVT(CInput stream) {
        in = stream;
        isStream = true;
        if (!in.good()) {
            Setup.error(100, "ReaderHEPEVT: could not open input stream  ");
        } else {
            setRunInfo(new GenRunInfo());
        }
    }

    /** The block the last event was read into. */
    public HEPEVT hepevt() {
        return hepevt;
    }

    @Override
    public boolean skip(int n) {
        int nn = n;
        while (!failed()) {
            if (!isStream && !in.isOpen()) return false;
            final int peek = in.peek();
            if (peek == 'E') nn--;
            if (nn < 0) return true;
            in.getline(262144);
        }
        return true;
    }

    /** Reads the "E event entries" line. */
    public boolean readHepevtEventHeader() {
        boolean eventline = false;
        int mI = 0;
        int mP = 0;
        while (!eventline) {
            final String bufE = in.getline(512);
            if (bufE.isEmpty()) return false;
            final CIStream stE = new CIStream(bufE);
            eventline = false;
            while (!eventline) {
                final int attr = stE.nextChar();
                if (stE.fail()) break;
                if (attr == ' ') continue;
                eventline = false;
                if (attr == 'E') {
                    mI = stE.nextInt();
                    if (!stE.fail()) mP = stE.nextInt();
                    eventline = !stE.fail();
                }
            }
        }
        hepevt.setEventNumber(mI);
        hepevt.setNumberEntries(mP);
        return eventline;
    }

    /** Reads particle i into the block. */
    public boolean readHepevtParticle(int i) {
        final int[] intcodes = new int[6];
        final double[] fltcodes1 = new double[5];
        final double[] fltcodes2 = new double[4];
        final String bufP = in.getline(512);
        if (bufP.isEmpty()) return false;
        final boolean positions = !options.containsKey("vertices_positions_are_absent");
        String bufV = "";
        if (positions) {
            bufV = in.getline(512);
            if (bufV.isEmpty()) return false;
        }
        final CIStream stP = new CIStream(bufP);
        final CIStream stV = new CIStream(bufV);
        if (positions) {
            for (int k = 0; k < 6; k++) intcodes[k] = stP.fail() ? intcodes[k] : stP.nextInt();
            for (int k = 0; k < 5; k++) fltcodes1[k] = stP.fail() ? fltcodes1[k] : stP.nextDouble();
            if (stP.fail()) {
                Setup.error(100, "ReaderHEPEVT: HEPMC3_ERROR reading particle momenta");
                return false;
            }
            for (int k = 0; k < 4; k++) fltcodes2[k] = stV.fail() ? fltcodes2[k] : stV.nextDouble();
            if (stV.fail()) {
                Setup.error(100, "ReaderHEPEVT: HEPMC3_ERROR reading particle vertex");
                return false;
            }
        } else {
            final int[] order = {0, 1, 4, 5};
            for (int k : order) intcodes[k] = stP.fail() ? intcodes[k] : stP.nextInt();
            final int[] forder = {0, 1, 2, 4};
            for (int k : forder) fltcodes1[k] = stP.fail() ? fltcodes1[k] : stP.nextDouble();
            if (stP.fail()) {
                Setup.error(100, "ReaderHEPEVT: HEPMC3_ERROR reading particle momenta");
                return false;
            }
            intcodes[2] = 0;
            intcodes[3] = 0;
            fltcodes1[3] = Math.sqrt(fltcodes1[0] * fltcodes1[0] + fltcodes1[1] * fltcodes1[1]
                + fltcodes1[2] * fltcodes1[2] + fltcodes1[4] * fltcodes1[4]);
            fltcodes2[0] = 0;
            fltcodes2[1] = 0;
            fltcodes2[2] = 0;
            fltcodes2[3] = 0;
        }
        hepevt.setStatus(i, intcodes[0]);
        hepevt.setId(i, intcodes[1]);
        hepevt.setParents(i, intcodes[2], Math.max(intcodes[2], intcodes[3]));
        hepevt.setChildren(i, intcodes[4], intcodes[5]);
        hepevt.setMomentum(i, fltcodes1[0], fltcodes1[1], fltcodes1[2], fltcodes1[3]);
        hepevt.setMass(i, fltcodes1[4]);
        hepevt.setPosition(i, fltcodes2[0], fltcodes2[1], fltcodes2[2], fltcodes2[3]);
        return true;
    }

    @Override
    public boolean readEvent(GenEvent evt) {
        evt.clear();
        hepevt.zeroEverything();
        boolean fileok = readHepevtEventHeader();
        for (int i = 1; i <= hepevt.numberEntries() && fileok; i++) fileok = readHepevtParticle(i);
        boolean result = false;
        if (fileok) {
            result = hepevt.toGenEvent(evt);
            final GenRunInfo g = new GenRunInfo();
            g.setWeightNames(List.of("0"));
            evt.setRunInfo(g);
            evt.weights().clear();
            evt.weights().add(1.0);
        } else {
            in.clear(CInput.BAD);
        }
        return result;
    }

    @Override
    public void close() {
        if (!in.isOpen()) return;
        in.close();
    }

    @Override
    public boolean failed() {
        return in.rdstate() != CInput.GOOD;
    }
}
