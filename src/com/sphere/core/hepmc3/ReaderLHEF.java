package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFiles;
import com.sphere.core.hepmc3.cxx.CInput;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads Les Houches Event Files into events: the init block becomes the run
 * information (the HEPRUP attribute, the generators as tools, the weight
 * names), each event a small tree of the hard process with the beams of
 * the init block attached to the incoming partons, its weights, the run's
 * cross sections and a PDF summary. An event group gives one event per
 * subevent, all with the same number.
 */
public class ReaderLHEF extends Reader {

    private LHEF.Reader reader;
    private HEPRUPAttribute hepr;
    private int neve;
    private boolean failedState;
    private final ArrayDeque<GenEvent> storage = new ArrayDeque<>();

    public ReaderLHEF(Path filename) {
        reader = new LHEF.Reader(filename);
        init();
    }

    public ReaderLHEF(String filename) {
        this(CFiles.path(filename));
    }

    public ReaderLHEF(InputStream stream) {
        this(new CInput(stream));
    }

    public ReaderLHEF(CInput stream) {
        reader = new LHEF.Reader(stream);
        init();
    }

    /** The LHEF reader underneath. */
    public LHEF.Reader lhefReader() {
        return reader;
    }

    @Override
    public boolean skip(int n) {
        final GenEvent evt = new GenEvent();
        for (int nn = n; nn > 0; --nn) {
            if (!readEvent(evt)) return false;
            evt.clear();
        }
        return !failed();
    }

    private void init() {
        neve = 0;
        failedState = false;
        hepr = new HEPRUPAttribute();
        hepr.heprup = new LHEF.HEPRUP(reader.heprup);
        hepr.tags = LHEF.XMLTag.findXMLTags(reader.headerBlock + reader.initComments);
        setRunInfo(new GenRunInfo());
        runInfo().addAttribute("HEPRUP", hepr);
        runInfo().addAttribute("NPRUP", new IntAttribute(hepr.heprup.NPRUP));
        runInfo().addAttribute("XSECUP", new VectorDoubleAttribute(hepr.heprup.XSECUP));
        runInfo().addAttribute("XERRUP", new VectorDoubleAttribute(hepr.heprup.XERRUP));
        runInfo().addAttribute("LPRUP", new VectorIntAttribute(hepr.heprup.LPRUP));
        runInfo().addAttribute("PDFGUP1", new IntAttribute(hepr.heprup.PDFGUP[0]));
        runInfo().addAttribute("PDFGUP2", new IntAttribute(hepr.heprup.PDFGUP[1]));
        runInfo().addAttribute("PDFSUP1", new IntAttribute(hepr.heprup.PDFSUP[0]));
        runInfo().addAttribute("PDFSUP2", new IntAttribute(hepr.heprup.PDFSUP[1]));
        runInfo().addAttribute("IDBMUP1", new IntAttribute((int) hepr.heprup.IDBMUP[0]));
        runInfo().addAttribute("IDBMUP2", new IntAttribute((int) hepr.heprup.IDBMUP[1]));
        runInfo().addAttribute("EBMUP1", new DoubleAttribute(hepr.heprup.EBMUP[0]));
        runInfo().addAttribute("EBMUP2", new DoubleAttribute(hepr.heprup.EBMUP[1]));
        final List<String> weightnames = new ArrayList<>();
        final int n = hepr.heprup.weightinfo.size();
        weightnames.add("Default");
        for (int i = 0; i < n; ++i) weightnames.add(hepr.heprup.weightNameHepMC(i));
        if (n == 0) Setup.warning(600, "ReaderLHEF::init: no initrwgt weights in the LHEF file.");
        runInfo().setWeightNames(weightnames);
        for (LHEF.Generator g : hepr.heprup.generators) {
            runInfo().tools().add(new GenRunInfo.ToolInfo(g.name, g.version, g.contents));
        }
    }

    @Override
    public boolean readEvent(GenEvent ev) {
        if (!storage.isEmpty()) {
            ev.assign(storage.pollFirst());
            return true;
        }
        if (!reader.readEvent()) return false;
        final HEPEUPAttribute hepe = new HEPEUPAttribute();
        if (reader.outsideBlock.length() > 0) hepe.tags = LHEF.XMLTag.findXMLTags(reader.outsideBlock.toString());
        hepe.hepeup = new LHEF.HEPEUP(reader.hepeup);
        final List<LHEF.HEPEUP> input = new ArrayList<>();
        if (!reader.hepeup.subevents.isEmpty()) input.addAll(hepe.hepeup.subevents);
        else input.add(reader.hepeup);
        final int firstGroupEvent = neve;
        neve++;
        for (LHEF.HEPEUP ahepeup : input) {
            final GenEvent evt = new GenEvent();
            evt.setRunInfo(runInfo());
            evt.setEventNumber(firstGroupEvent);
            evt.addAttribute("AlphaQCD", new DoubleAttribute(ahepeup.AQCDUP));
            evt.addAttribute("AlphaEM", new DoubleAttribute(ahepeup.AQEDUP));
            evt.addAttribute("NUP", new IntAttribute(ahepeup.NUP));
            evt.addAttribute("IDPRUP", new LongAttribute(ahepeup.IDPRUP));
            final List<GenParticle> particles = new ArrayList<>(Math.max(0, ahepeup.NUP));
            final TreeMap<Mothers, GenVertex> vertices = new TreeMap<>();
            for (int i = 0; i < ahepeup.NUP; ++i) {
                final double[] pup = ahepeup.PUP.get(i);
                final FourVector mom = new FourVector(pup[0], pup[1], pup[2], pup[3]);
                particles.add(new GenParticle(mom, (int) (long) ahepeup.IDUP.get(i), ahepeup.ISTUP.get(i)));
                if (i < 2) continue;
                final Mothers key = new Mothers(ahepeup.MOTHUP.get(i)[0], ahepeup.MOTHUP.get(i)[1]);
                vertices.computeIfAbsent(key, k -> new GenVertex()).addParticleOut(particles.get(particles.size() - 1));
            }
            for (Map.Entry<Mothers, GenVertex> v : vertices.entrySet()) {
                final int first = v.getKey().first();
                final int second = v.getKey().second();
                for (int i = first - 1; i < second; ++i) {
                    if (i >= 0 && i < particles.size()) v.getValue().addParticleIn(particles.get(i));
                }
            }
            final Mothers rootKey = new Mothers(0, 0);
            vertices.computeIfAbsent(rootKey, k -> new GenVertex());
            for (int i = 0; i < particles.size(); ++i) {
                final GenParticle p = particles.get(i);
                if (p.endVertex() == null && p.productionVertex() == null) {
                    if (i < 2) vertices.get(rootKey).addParticleIn(p);
                    else vertices.get(rootKey).addParticleOut(p);
                }
            }
            for (GenVertex v : vertices.values()) {
                if (!v.particlesOut().isEmpty() && !v.particlesIn().isEmpty()) evt.addVertex(v);
            }
            int particleFromBeamIndex = 0;
            if (hepr.heprup.IDBMUP[0] != 0) {
                final FourVector beamMom = new FourVector(0, 0, hepr.heprup.EBMUP[0], hepr.heprup.EBMUP[0]);
                final GenParticle beam = new GenParticle(beamMom, (int) hepr.heprup.IDBMUP[0], 4);
                evt.addBeamParticle(beam);
                if (particles.size() > particleFromBeamIndex) {
                    if (particles.get(particleFromBeamIndex).status() != -1) {
                        Setup.error("ReaderLHEF::read_event: beam particle in the event is not an incoming particle (status != -1).");
                        return false;
                    }
                    final GenVertex v = new GenVertex();
                    v.addParticleOut(particles.get(particleFromBeamIndex));
                    particleFromBeamIndex++;
                    v.addParticleIn(beam);
                    evt.addVertex(v);
                } else {
                    Setup.error("ReaderLHEF::read_event: fewer particles than beams in the event, cannot add beam particle(s).");
                    return false;
                }
            }
            if (hepr.heprup.IDBMUP[1] != 0) {
                final FourVector beamMom = new FourVector(0, 0, -hepr.heprup.EBMUP[1], hepr.heprup.EBMUP[1]);
                final GenParticle beam = new GenParticle(beamMom, (int) hepr.heprup.IDBMUP[1], 4);
                evt.addBeamParticle(beam);
                if (particles.size() > particleFromBeamIndex) {
                    if (particles.get(particleFromBeamIndex).status() != -1) {
                        Setup.error("ReaderLHEF::read_event: beam particle in the event is not an incoming particle (status != -1).");
                        return false;
                    }
                    final GenVertex v = new GenVertex();
                    v.addParticleOut(particles.get(particleFromBeamIndex));
                    particleFromBeamIndex++;
                    v.addParticleIn(beam);
                    evt.addVertex(v);
                } else {
                    Setup.error("ReaderLHEF::read_event: fewer particles than beams in the event, cannot add beam particle(s).");
                    return false;
                }
            }
            final List<Double> wts = new ArrayList<>(ahepeup.weights.size());
            for (LHEF.WeightValue w : ahepeup.weights) wts.add(w.first);
            evt.weights().clear();
            evt.weights().addAll(wts);
            final GenCrossSection xs = new GenCrossSection();
            evt.addAttribute("GenCrossSection", xs);
            xs.setCrossSection(hepr.heprup.XSECUP, hepr.heprup.XERRUP);
            final GenPdfInfo pi = new GenPdfInfo();
            if (particles.size() > 1) {
                pi.partonId[0] = particles.get(0).pdgId();
                pi.partonId[1] = particles.get(1).pdgId();
                pi.x[0] = Math.abs(particles.get(0).momentum().pz() / hepr.heprup.EBMUP[0]);
                pi.x[1] = Math.abs(particles.get(1).momentum().pz() / hepr.heprup.EBMUP[1]);
            }
            pi.scale = ahepeup.pdfinfo.scale;
            pi.xf[0] = 1;
            pi.xf[1] = 1;
            pi.pdfId[0] = hepr.heprup.PDFSUP[0];
            pi.pdfId[1] = hepr.heprup.PDFSUP[1];
            evt.addAttribute("GenPdfInfo", pi);
            storage.addLast(evt);
        }
        ev.assign(storage.pollFirst());
        return true;
    }

    /** The std::pair&lt;int,int&gt; of mothers a vertex is keyed by, ordered as the pair is. */
    private record Mothers(int first, int second) implements Comparable<Mothers> {
        @Override
        public int compareTo(Mothers o) {
            return first != o.first ? Integer.compare(first, o.first) : Integer.compare(second, o.second);
        }
    }

    @Override
    public boolean failed() {
        return (reader.initfileRdstate() != CInput.GOOD || reader.fileRdstate() != CInput.GOOD) && storage.isEmpty();
    }

    @Override
    public void close() {
    }
}
