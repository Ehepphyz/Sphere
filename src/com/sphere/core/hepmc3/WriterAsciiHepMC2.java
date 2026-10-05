package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFiles;
import com.sphere.core.hepmc3.cxx.CFormat;
import com.sphere.core.hepmc3.cxx.COutput;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes HepMC2's IO_GenEvent text format from HepMC3 events, as HepMC3 does:
 * the HepMC2 event values taken from the attributes HepMC2 reading made,
 * barcodes 10001... for particles, vertex ids for vertices, beams found
 * among the particles of status 4.
 */
public class WriterAsciiHepMC2 extends Writer {

    private final COutput out;
    private final boolean ownsFile;
    private int precision = 16;
    private String floatSpec = " %.16e";
    private final StringBuilder buffer = new StringBuilder(1 << 16);
    private long particleCounter;
    private boolean closed;

    public WriterAsciiHepMC2(Path filename, GenRunInfo run) {
        Setup.warning(900, "WriterAsciiHepMC2::WriterAsciiHepMC2: HepMC2 IO_GenEvent format is outdated. Please use HepMC3 Asciiv3 format instead.");
        out = COutput.create(filename);
        ownsFile = true;
        setRunInfo(run);
        if (runInfo() == null) setRunInfo(new GenRunInfo());
        if (!out.isOpen()) {
            Setup.error(100, "WriterAsciiHepMC2: could not open output file: " + filename);
        } else {
            out.write("HepMC::Version " + HepMC3.version() + "\nHepMC::IO_GenEvent-START_EVENT_LISTING\n");
        }
    }

    public WriterAsciiHepMC2(Path filename) {
        this(filename, null);
    }

    public WriterAsciiHepMC2(String filename) {
        this(CFiles.path(filename), null);
    }

    public WriterAsciiHepMC2(OutputStream stream, GenRunInfo run) {
        this(new COutput(stream), run);
    }

    public WriterAsciiHepMC2(COutput stream, GenRunInfo run) {
        Setup.warning(900, "WriterAsciiHepMC2::WriterAsciiHepMC2: HepMC2 IO_GenEvent format is outdated. Please use HepMC3 Asciiv3 format instead.");
        out = stream;
        ownsFile = false;
        setRunInfo(run);
        if (runInfo() == null) setRunInfo(new GenRunInfo());
        out.write("HepMC::Version " + HepMC3.version() + "\nHepMC::IO_GenEvent-START_EVENT_LISTING\n");
    }

    public void setPrecision(int prec) {
        if (prec < 2 || prec > 24) return;
        precision = prec;
    }

    public int precision() {
        return precision;
    }

    private void f(double v) {
        if (floatSpec.endsWith("e") && Double.isFinite(v)) {
            buffer.append(' ');
            if (v < 0 || (v == 0 && 1 / v < 0)) buffer.append('-');
            buffer.append(CFormat.e(Math.abs(v), precision, false));
        } else {
            buffer.append(CFormat.sprintf(floatSpec, v));
        }
    }

    @Override
    public void writeEvent(GenEvent evt) {
        final String option = options.get("float_printf_specifier");
        String letter = option != null ? option.substring(0, Math.min(2, option.length())) : "e";
        if (!letter.equals("e") && !letter.equals("E") && !letter.equals("G") && !letter.equals("g")
                && !letter.equals("f") && !letter.equals("F")) {
            letter = "e";
        }
        floatSpec = " %." + precision + letter;
        flush();
        if (runInfo() == null) setRunInfo(evt.runInfo());
        if (evt.runInfo() != null && runInfo() != evt.runInfo()) setRunInfo(evt.runInfo());
        final DoubleAttribute aEventScale = evt.attribute("event_scale", DoubleAttribute.class);
        final DoubleAttribute aAlphaQED = evt.attribute("alphaQED", DoubleAttribute.class);
        final DoubleAttribute aAlphaQCD = evt.attribute("alphaQCD", DoubleAttribute.class);
        final IntAttribute aSignalProcessId = evt.attribute("signal_process_id", IntAttribute.class);
        final IntAttribute aMpi = evt.attribute("mpi", IntAttribute.class);
        final IntAttribute aSignalProcessVertex = evt.attribute("signal_process_vertex", IntAttribute.class);
        final double eventScale = aEventScale != null ? aEventScale.value() : 0.0;
        final double alphaQED = aAlphaQED != null ? aAlphaQED.value() : 0.0;
        final double alphaQCD = aAlphaQCD != null ? aAlphaQCD.value() : 0.0;
        final int signalProcessId = aSignalProcessId != null ? aSignalProcessId.value() : 0;
        final int mpi = aMpi != null ? aMpi.value() : 0;
        final int signalProcessVertex = aSignalProcessVertex != null ? aSignalProcessVertex.value() : 0;
        final List<Long> randomStates = new ArrayList<>();
        final VectorLongIntAttribute randomStatesA = evt.attribute("random_states", VectorLongIntAttribute.class);
        if (randomStatesA != null) {
            randomStates.addAll(randomStatesA.value());
        } else {
            for (int i = 0; i < 100; i++) {
                final LongAttribute rs = evt.attribute("random_states" + i, LongAttribute.class);
                if (rs == null) break;
                randomStates.add(rs.value());
            }
        }
        final List<Integer> beams = new ArrayList<>(2);
        int idbeam = 0;
        for (GenVertex v : evt.vertices()) {
            for (GenParticle p : v.particlesIn()) {
                if (p.productionVertex() == null) {
                    if (p.status() == 4) beams.add(idbeam);
                    idbeam++;
                } else if (p.productionVertex().id() == 0) {
                    if (p.status() == 4) beams.add(idbeam);
                    idbeam++;
                }
            }
            for (GenParticle p : v.particlesOut()) {
                if (p.status() == 4) beams.add(idbeam);
                idbeam++;
            }
        }
        int idbeam1 = 10000;
        int idbeam2 = 10000;
        if (!beams.isEmpty()) idbeam1 += beams.get(0) + 1;
        if (beams.size() > 1) idbeam2 += beams.get(1) + 1;
        buffer.append(CFormat.sprintf("E %d %d %e %e %e %d %d %zu %i %i", evt.eventNumber(), mpi, eventScale, alphaQCD,
            alphaQED, signalProcessId, signalProcessVertex, (long) evt.vertices().size(), idbeam1, idbeam2));
        flush();
        buffer.append(' ').append(randomStates.size());
        for (int q = 0; q < randomStates.size(); q++) {
            // HepMC3 writes the index here, not the state (sic)
            buffer.append(' ').append(q);
            flush();
        }
        flush();
        buffer.append(' ').append(evt.weights().size());
        if (!evt.weights().isEmpty()) {
            for (double w : evt.weights()) {
                f(w);
                flush();
            }
            buffer.append('\n');
            flush();
            buffer.append("N ").append(evt.weights().size());
            final List<String> names = runInfo().weightNames();
            for (int q = 0; q < evt.weights().size(); q++) {
                if (q < names.size()) writeString(" \"" + names.get(q) + "\"");
                else writeString(" \"" + q + "\"");
                flush();
            }
        }
        buffer.append('\n');
        flush();
        buffer.append("U ").append(Units.name(evt.momentumUnit())).append(' ').append(Units.name(evt.lengthUnit())).append('\n');
        flush();
        final GenCrossSection cs = evt.attribute("GenCrossSection", GenCrossSection.class);
        if (cs != null) {
            buffer.append('C');
            f(cs.xsec());
            f(cs.xsecErr());
            buffer.append('\n');
            flush();
        }
        final GenHeavyIon hi = evt.attribute("GenHeavyIon", GenHeavyIon.class);
        if (hi != null) {
            buffer.append(CFormat.sprintf("H %i %i %i %i %i %i %i %i %i %e %e %e %e\n", hi.nCollHard, hi.nPartProj,
                hi.nPartTarg, hi.nColl, hi.spectatorNeutrons, hi.spectatorProtons, hi.nNwoundedCollisions,
                hi.nwoundedNCollisions, hi.nwoundedNwoundedCollisions, hi.impactParameter, hi.eventPlaneAngle,
                hi.eccentricity, hi.sigmaInelNN));
            flush();
        }
        final GenPdfInfo pi = evt.attribute("GenPdfInfo", GenPdfInfo.class);
        if (pi != null) {
            final String st = pi.serialize();
            if (st == null) {
                Setup.warning(300, "WriterAsciiHepMC2::write_event: problem serializing GenPdfInfo attribute");
            } else {
                buffer.append("F ");
                flush();
                writeString(WriterAscii.escape(st));
                buffer.append('\n');
                flush();
            }
        }
        particleCounter = 0;
        for (GenVertex v : evt.vertices()) {
            final int productionVertex = v.id();
            writeVertex(v);
            for (GenParticle p : v.particlesIn()) {
                if (p.productionVertex() == null) {
                    writeParticle(p, productionVertex);
                } else if (p.productionVertex().id() == 0) {
                    writeParticle(p, productionVertex);
                }
            }
            for (GenParticle p : v.particlesOut()) writeParticle(p, productionVertex);
        }
        forcedFlush();
    }

    private void writeVertex(GenVertex v) {
        final List<Double> weights = new ArrayList<>();
        final VectorDoubleAttribute weightsA = v.attribute("weights", VectorDoubleAttribute.class);
        if (weightsA != null) {
            weights.addAll(weightsA.value());
        } else {
            for (int i = 0; i < 100; i++) {
                final DoubleAttribute rs = v.attribute("weight" + i, DoubleAttribute.class);
                if (rs == null) break;
                weights.add(rs.value());
            }
        }
        flush();
        buffer.append("V ").append(v.id()).append(' ').append(v.status());
        int orph = 0;
        for (GenParticle p : v.particlesIn()) {
            if (p.productionVertex() == null) orph++;
            else if (p.productionVertex().id() == 0) orph++;
        }
        final FourVector pos = v.position();
        if (pos.isZero()) {
            buffer.append(" 0 0 0 0");
        } else {
            f(pos.x());
            f(pos.y());
            f(pos.z());
            f(pos.t());
        }
        buffer.append(' ').append(orph).append(' ').append(v.particlesOut().size()).append(' ').append(weights.size());
        flush();
        for (double w : weights) {
            f(w);
            flush();
        }
        buffer.append('\n');
        flush();
    }

    private void writeParticle(GenParticle p, int secondField) {
        flush();
        buffer.append("P ").append(10001 + particleCounter);
        particleCounter++;
        buffer.append(' ').append(p.pid());
        f(p.momentum().px());
        f(p.momentum().py());
        f(p.momentum().pz());
        f(p.momentum().e());
        f(p.generatedMass());
        buffer.append(' ').append(p.status());
        flush();
        int ev = 0;
        if (p.endVertex() != null && p.endVertex().id() != 0) ev = p.endVertex().id();
        final DoubleAttribute aTheta = p.attribute("theta", DoubleAttribute.class);
        final DoubleAttribute aPhi = p.attribute("phi", DoubleAttribute.class);
        if (aTheta != null) f(aTheta.value());
        else buffer.append(" 0");
        if (aPhi != null) f(aPhi.value());
        else buffer.append(" 0");
        buffer.append(' ').append(ev);
        flush();
        final VectorIntAttribute aFlows = p.attribute("flows", VectorIntAttribute.class);
        if (aFlows != null) {
            final List<Integer> flowsv = aFlows.value();
            final StringBuilder flowss = new StringBuilder(" ").append(flowsv.size());
            for (int k = 0; k < flowsv.size(); k++) flowss.append(' ').append(k + 1).append(' ').append(flowsv.get(k));
            flowss.append('\n');
            writeString(flowss.toString());
        } else {
            final IntAttribute f1 = p.attribute("flow1", IntAttribute.class);
            final IntAttribute f2 = p.attribute("flow2", IntAttribute.class);
            final IntAttribute f3 = p.attribute("flow3", IntAttribute.class);
            int flowsize = 0;
            if (f1 != null) flowsize++;
            if (f2 != null) flowsize++;
            if (f3 != null) flowsize++;
            final StringBuilder flowss = new StringBuilder(" ").append(flowsize);
            if (f1 != null) flowss.append(" 1 ").append(f1.value());
            if (f2 != null) flowss.append(" 2 ").append(f2.value());
            if (f3 != null) flowss.append(" 3 ").append(f3.value());
            flowss.append('\n');
            writeString(flowss.toString());
        }
        flush();
    }

    private void writeString(String s) {
        buffer.append(s);
        flush();
    }

    /** No run information in this format. */
    public void writeRunInfo() {
    }

    private void flush() {
        if (buffer.length() > (1 << 16) - 512) forcedFlush();
    }

    private void forcedFlush() {
        if (buffer.length() == 0) return;
        out.write(buffer);
        buffer.setLength(0);
    }

    @Override
    public void close() {
        if (closed || !out.isOpen()) return;
        closed = true;
        forcedFlush();
        out.write("HepMC::IO_GenEvent-END_EVENT_LISTING\n\n");
        if (ownsFile) out.close();
        else out.flush();
    }

    @Override
    public boolean failed() {
        return out.failed();
    }
}
