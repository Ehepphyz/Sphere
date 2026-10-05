package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFiles;
import com.sphere.core.hepmc3.cxx.CInput;
import com.sphere.core.hepmc3.cxx.CStr;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads HepMC2's IO_GenEvent text format into HepMC3 events, as HepMC3 does:
 * barcodes dropped, the event-level values of HepMC2 (mpi, scale, couplings,
 * signal process, random states) turned into attributes, particle flows and
 * polarisations and vertex weights kept as attributes, the vertices with
 * nothing entering or leaving removed, and the tree added in topological
 * order.
 */
public class ReaderAsciiHepMC2 extends Reader {

    private static final int BUFFER = 262144;

    private final CInput in;
    private final boolean isStream;
    private final List<GenVertex> vertexCache = new ArrayList<>();
    private final List<Integer> vertexBarcodes = new ArrayList<>();
    private final List<GenParticle> particleCache = new ArrayList<>();
    private final List<Integer> endVertexBarcodes = new ArrayList<>();
    private GenEvent eventGhost;
    private final List<GenParticle> particleCacheGhost = new ArrayList<>();
    private final List<GenVertex> vertexCacheGhost = new ArrayList<>();

    public ReaderAsciiHepMC2(Path filename) {
        in = CInput.open(filename);
        isStream = false;
        if (!in.isOpen()) Setup.error(100, "ReaderAsciiHepMC2: could not open input file: " + filename);
        setRunInfo(new GenRunInfo());
        eventGhost = new GenEvent();
    }

    public ReaderAsciiHepMC2(String filename) {
        this(CFiles.path(filename));
    }

    public ReaderAsciiHepMC2(InputStream stream) {
        this(new CInput(stream));
    }

    public ReaderAsciiHepMC2(CInput stream) {
        in = stream;
        isStream = true;
        if (!in.good()) Setup.error(100, "ReaderAsciiHepMC2: could not open input stream ");
        setRunInfo(new GenRunInfo());
        eventGhost = new GenEvent();
    }

    private boolean usable() {
        return isStream || in.isOpen();
    }

    @Override
    public boolean skip(int n) {
        int nn = n;
        while (!failed()) {
            if (!usable()) return false;
            final int peek = in.peek();
            if (peek == 'E') nn--;
            if (nn < 0) return true;
            in.getline(BUFFER);
        }
        return true;
    }

    @Override
    public boolean readEvent(GenEvent evt) {
        if (!usable()) return false;
        String buf = "";
        boolean parsedEventHeader = false;
        boolean isParsingSuccessful = true;
        int parsingResult;
        long verticesCount = 0;
        long currentVertexParticlesCount = 0;
        long currentVertexParticlesParsed = 0;
        evt.clear();
        evt.setRunInfo(runInfo());
        vertexCache.clear();
        vertexBarcodes.clear();
        particleCache.clear();
        endVertexBarcodes.clear();
        particleCacheGhost.clear();
        vertexCacheGhost.clear();
        if (eventGhost == null) eventGhost = new GenEvent();

        while (!failed()) {
            buf = in.getline(BUFFER);
            if (buf.isEmpty()) continue;
            if (buf.startsWith("HepMC")) {
                if (!buf.startsWith("HepMC::Version") && !buf.startsWith("HepMC::IO_GenEvent")) {
                    Setup.warning(500, "ReaderAsciiHepMC2: found unsupported expression in header. Will close the input.");
                    StdStreams.cout().println(buf);
                    in.clear(CInput.EOF);
                }
                if (parsedEventHeader) {
                    isParsingSuccessful = true;
                    break;
                }
                continue;
            }
            switch (buf.charAt(0)) {
                case 'E' -> {
                    parsingResult = parseEventInformation(evt, buf);
                    if (parsingResult < 0) {
                        isParsingSuccessful = false;
                        Setup.error(600, "ReaderAsciiHepMC2: HEPMC3_ERROR parsing event information");
                    } else {
                        verticesCount = Integer.toUnsignedLong(parsingResult);
                        evt.reserve((int) Math.min(verticesCount, Integer.MAX_VALUE), particleCacheGhost.size());
                        isParsingSuccessful = true;
                    }
                    parsedEventHeader = true;
                }
                case 'V' -> {
                    if (currentVertexParticlesParsed < currentVertexParticlesCount) {
                        isParsingSuccessful = false;
                        break;
                    }
                    currentVertexParticlesParsed = 0;
                    parsingResult = parseVertexInformation(buf);
                    if (parsingResult < 0) {
                        isParsingSuccessful = false;
                        Setup.error(600, "ReaderAsciiHepMC2: HEPMC3_ERROR parsing vertex information");
                    } else {
                        currentVertexParticlesCount = parsingResult;
                        isParsingSuccessful = true;
                    }
                }
                case 'P' -> {
                    parsingResult = parseParticleInformation(buf);
                    if (parsingResult < 0) {
                        isParsingSuccessful = false;
                        Setup.error(600, "ReaderAsciiHepMC2: HEPMC3_ERROR parsing particle information");
                    } else {
                        ++currentVertexParticlesParsed;
                        isParsingSuccessful = true;
                    }
                }
                case 'U' -> isParsingSuccessful = parseUnits(evt, buf);
                case 'F' -> isParsingSuccessful = parsePdfInfo(evt, buf);
                case 'H' -> isParsingSuccessful = parseHeavyIon(evt, buf);
                case 'N' -> isParsingSuccessful = parseWeightNames(buf);
                case 'C' -> isParsingSuccessful = parseXsInfo(evt, buf);
                default -> {
                    Setup.warning(500, "ReaderAsciiHepMC2: skipping unrecognised prefix: " + buf.charAt(0));
                    isParsingSuccessful = true;
                }
            }
            if (!isParsingSuccessful) break;
            final int peek = in.peek();
            if (parsedEventHeader && peek == 'E') break;
        }
        if (isParsingSuccessful && currentVertexParticlesParsed < currentVertexParticlesCount) {
            Setup.error(600, "ReaderAsciiHepMC2: not all particles parsed");
            isParsingSuccessful = false;
        } else if (isParsingSuccessful && vertexCache.size() != verticesCount) {
            Setup.error(600, "ReaderAsciiHepMC2: not all vertices parsed");
            isParsingSuccessful = false;
        }
        if (!isParsingSuccessful) {
            Setup.error(600, "ReaderAsciiHepMC2: event parsing failed. Returning empty event");
            if (Setup.debugging(1)) Setup.debug(1, "Parsing failed at line:\n" + buf);
            evt.clear();
            in.clear(CInput.BAD);
            return false;
        }
        if (runInfo() != null && runInfo().weightNames().isEmpty()) {
            runInfo().setWeightNames(List.of("Default"));
        }
        if (evt.weights().isEmpty()) {
            Setup.warning(600, "ReaderAsciiHepMC2: weights are empty, an event weight 1.0 will be added.");
            evt.weights().add(1.0);
        }
        // the end vertices of the particles, by barcode: the first vertex with it wins, as the C++ loop breaks there
        final Map<Integer, Integer> byBarcode = new HashMap<>();
        for (int j = 0; j < vertexCache.size(); ++j) byBarcode.putIfAbsent(vertexBarcodes.get(j), j);
        for (int i = 0; i < particleCache.size(); ++i) {
            if (endVertexBarcodes.get(i) == 0) continue;
            final Integer j = byBarcode.get(endVertexBarcodes.get(i));
            if (j != null) vertexCache.get(j).addParticleIn(particleCache.get(i));
        }
        for (int i = 0; i < vertexCache.size(); ++i) {
            final GenVertex v = vertexCache.get(i);
            if (v.particlesIn().isEmpty()) {
                if (Setup.debugging(30)) {
                    Setup.debug(30, "ReaderAsciiHepMC2::read_event - found a vertex without incoming particles: " + v.id());
                }
                final List<GenParticle> beams = new ArrayList<>(2);
                for (GenParticle p : v.particlesOut()) if (p.status() == 4 && p.endVertex() == null) beams.add(p);
                for (GenParticle p : beams) {
                    v.addParticleIn(p);
                    v.removeParticleOut(p);
                    if (Setup.debugging(30)) {
                        Setup.debug(30, "ReaderAsciiHepMC2::read_event - moved particle with status=4 from the outgoing to the incoming particles of vertex: " + v.id());
                    }
                }
                if (beams.isEmpty()) {
                    if (Setup.debugging(30)) {
                        Setup.debug(30, "ReaderAsciiHepMC2::read_event - removed vertex without incoming particles: " + v.id());
                    }
                    vertexCache.set(i, null);
                }
            } else if (v.particlesOut().isEmpty()) {
                vertexCache.set(i, null);
                if (Setup.debugging(30)) {
                    Setup.debug(30, "ReaderAsciiHepMC2::read_event - removed vertex without outgoing particles: " + v.id());
                }
            }
        }
        evt.reserve(particleCache.size(), vertexCache.size());
        evt.addTree(particleCache);
        if (options.containsKey("event_random_states_are_separated")) {
            final VectorLongIntAttribute randomStates = evt.attribute("random_states", VectorLongIntAttribute.class);
            if (randomStates != null) {
                final List<Long> values = randomStates.value();
                for (int i = 0; i < values.size(); ++i) {
                    evt.addAttribute("random_states" + i, new IntAttribute((int) (long) values.get(i)));
                }
                evt.removeAttribute("random_states");
            }
        }
        final TreeMap<String, TreeMap<Integer, Attribute>> cached = eventGhost.attributes();
        if (cached.containsKey("flows")) {
            final TreeMap<Integer, Attribute> flows = cached.get("flows");
            if (!options.containsKey("particle_flows_are_separated")) {
                for (Map.Entry<Integer, Attribute> f : flows.entrySet()) {
                    if (f.getKey() > 0 && f.getKey() <= particleCache.size()) {
                        particleCache.get(f.getKey() - 1).addAttribute("flows", f.getValue());
                    }
                }
            } else {
                for (Map.Entry<Integer, Attribute> f : flows.entrySet()) {
                    if (f.getKey() > 0 && f.getKey() <= particleCache.size()) {
                        if (!(f.getValue() instanceof VectorIntAttribute casted)) continue;
                        final List<Integer> thisFlow = casted.value();
                        for (int i = 0; i < thisFlow.size(); i++) {
                            particleCache.get(f.getKey() - 1).addAttribute("flow" + (i + 1), new IntAttribute(thisFlow.get(i)));
                        }
                    }
                }
            }
        }
        if (cached.containsKey("phi")) {
            for (Map.Entry<Integer, Attribute> f : cached.get("phi").entrySet()) {
                if (f.getKey() > 0 && f.getKey() <= particleCache.size()) particleCache.get(f.getKey() - 1).addAttribute("phi", f.getValue());
            }
        }
        if (cached.containsKey("theta")) {
            for (Map.Entry<Integer, Attribute> f : cached.get("theta").entrySet()) {
                if (f.getKey() > 0 && f.getKey() <= particleCache.size()) particleCache.get(f.getKey() - 1).addAttribute("theta", f.getValue());
            }
        }
        if (cached.containsKey("weights")) {
            final TreeMap<Integer, Attribute> weights = cached.get("weights");
            if (!options.containsKey("vertex_weights_are_separated")) {
                for (Map.Entry<Integer, Attribute> f : weights.entrySet()) {
                    final int k = -f.getKey() - 1;
                    if (f.getKey() < 0 && f.getKey() >= -vertexCache.size() && vertexCache.get(k) != null) {
                        vertexCache.get(k).addAttribute("weights", f.getValue());
                    }
                }
            } else {
                for (Map.Entry<Integer, Attribute> f : weights.entrySet()) {
                    final int k = -f.getKey() - 1;
                    if (f.getKey() < 0 && f.getKey() >= -vertexCache.size() && vertexCache.get(k) != null) {
                        if (!(f.getValue() instanceof VectorDoubleAttribute casted)) continue;
                        final List<Double> thisWeight = casted.value();
                        // HepMC3 attaches these to the particle of the same index (sic); kept as it does
                        if (k < particleCache.size()) {
                            for (int i = 0; i < thisWeight.size(); i++) {
                                particleCache.get(k).addAttribute("weight" + i, new DoubleAttribute(thisWeight.get(i)));
                            }
                        }
                    }
                }
            }
        }
        final IntAttribute signalProcessVertexBarcode = evt.attribute("signal_process_vertex", IntAttribute.class);
        if (signalProcessVertexBarcode != null) {
            final int barcode = signalProcessVertexBarcode.value();
            for (int i = 0; i < vertexCache.size(); ++i) {
                if (i >= vertexBarcodes.size()) continue;
                if (barcode != vertexBarcodes.get(i)) continue;
                if (vertexCache.get(i) == null) continue;
                evt.addAttribute("signal_process_vertex", new IntAttribute(vertexCache.get(i).id()));
                break;
            }
        }
        particleCacheGhost.clear();
        vertexCacheGhost.clear();
        eventGhost.clear();
        return true;
    }

    /* ---- the C way of walking a line: strchr to the next blank, then atoi/atof ---- */

    /** strchr(cursor + 1, ' '), -1 when there is none. */
    private static int next(String buf, int cursor) {
        if (cursor + 1 > buf.length()) return -1;
        return CStr.strchr(buf, cursor + 1, ' ');
    }

    private int parseEventInformation(GenEvent evt, String buf) {
        int cursor = 0;
        int randomStatesSize;
        int weightsSize;
        if ((cursor = next(buf, cursor)) < 0) return -1;
        evt.setEventNumber(CStr.atoi(buf, cursor));
        if ((cursor = next(buf, cursor)) < 0) return -1;
        evt.addAttribute("mpi", new IntAttribute(CStr.atoi(buf, cursor)));
        if ((cursor = next(buf, cursor)) < 0) return -1;
        evt.addAttribute("event_scale", new DoubleAttribute(CStr.atof(buf, cursor)));
        if ((cursor = next(buf, cursor)) < 0) return -1;
        evt.addAttribute("alphaQCD", new DoubleAttribute(CStr.atof(buf, cursor)));
        if ((cursor = next(buf, cursor)) < 0) return -1;
        evt.addAttribute("alphaQED", new DoubleAttribute(CStr.atof(buf, cursor)));
        if ((cursor = next(buf, cursor)) < 0) return -1;
        evt.addAttribute("signal_process_id", new IntAttribute(CStr.atoi(buf, cursor)));
        if ((cursor = next(buf, cursor)) < 0) return -1;
        evt.addAttribute("signal_process_vertex", new IntAttribute(CStr.atoi(buf, cursor)));
        if ((cursor = next(buf, cursor)) < 0) return -1;
        final int verticesCount = CStr.atoi(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return -1;
        if ((cursor = next(buf, cursor)) < 0) return -1;
        if ((cursor = next(buf, cursor)) < 0) return -1;
        randomStatesSize = CStr.atoi(buf, cursor);
        final List<Long> randomStates = new ArrayList<>();
        if (randomStatesSize >= 0) {
            for (int i = 0; i < randomStatesSize; i++) randomStates.add(0L);
        } else if (Setup.debugging(0)) {
            Setup.debug(0, "ReaderAsciiHepMC2: E: " + evt.eventNumber() + " (" + Integer.toUnsignedLong(verticesCount) + "V, "
                + randomStatesSize + "RS)");
        }
        for (int i = 0; i < randomStatesSize; ++i) {
            if ((cursor = next(buf, cursor)) < 0) return -1;
            randomStates.set(i, (long) CStr.atoi(buf, cursor));
        }
        if (!randomStates.isEmpty()) evt.addAttribute("random_states", new VectorLongIntAttribute(randomStates));
        if ((cursor = next(buf, cursor)) < 0) return -1;
        weightsSize = CStr.atoi(buf, cursor);
        final List<Double> weights = new ArrayList<>();
        if (weightsSize >= 0) {
            for (int i = 0; i < weightsSize; i++) weights.add(0.0);
        } else if (Setup.debugging(0)) {
            Setup.debug(0, "ReaderAsciiHepMC2: E: " + evt.eventNumber() + " (" + Integer.toUnsignedLong(verticesCount) + "V, "
                + weightsSize + "WS)");
        }
        for (int i = 0; i < weightsSize; ++i) {
            if ((cursor = next(buf, cursor)) < 0) return -1;
            weights.set(i, CStr.atof(buf, cursor));
        }
        evt.weights().clear();
        evt.weights().addAll(weights);
        if (Setup.debugging(10)) {
            Setup.debug(10, "ReaderAsciiHepMC2: E: " + evt.eventNumber() + " (" + Integer.toUnsignedLong(verticesCount) + "V, "
                + weightsSize + "W, " + randomStatesSize + "RS)");
        }
        return verticesCount;
    }

    private static boolean parseUnits(GenEvent evt, String buf) {
        int cursor = 0;
        if ((cursor = next(buf, cursor)) < 0) return false;
        ++cursor;
        final Units.MomentumUnit momentumUnit = Units.momentumUnit(buf.substring(Math.min(cursor, buf.length())));
        if ((cursor = next(buf, cursor)) < 0) return false;
        ++cursor;
        final Units.LengthUnit lengthUnit = Units.lengthUnit(buf.substring(Math.min(cursor, buf.length())));
        evt.setUnits(momentumUnit, lengthUnit);
        if (Setup.debugging(10)) {
            Setup.debug(10, "ReaderAsciiHepMC2: U: " + Units.name(evt.momentumUnit()) + " " + Units.name(evt.lengthUnit()));
        }
        return true;
    }

    private int parseVertexInformation(String buf) {
        final GenVertex vdata = new GenVertex();
        final GenVertex dataGhost = new GenVertex();
        int cursor = 0;
        if ((cursor = next(buf, cursor)) < 0) return -1;
        final int barcode = CStr.atoi(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return -1;
        vdata.setStatus(CStr.atoi(buf, cursor));
        if ((cursor = next(buf, cursor)) < 0) return -1;
        final double x = CStr.atof(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return -1;
        final double y = CStr.atof(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return -1;
        final double z = CStr.atof(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return -1;
        final double t = CStr.atof(buf, cursor);
        vdata.setPosition(new FourVector(x, y, z, t));
        if ((cursor = next(buf, cursor)) < 0) return -1;
        if ((cursor = next(buf, cursor)) < 0) return -1;
        final int numParticlesOut = CStr.atoi(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return -1;
        final int weightsSize = CStr.atoi(buf, cursor);
        final List<Double> weights = new ArrayList<>();
        for (int i = 0; i < weightsSize; i++) weights.add(0.0);
        for (int i = 0; i < weightsSize; ++i) {
            if ((cursor = next(buf, cursor)) < 0) return -1;
            weights.set(i, CStr.atof(buf, cursor));
        }
        vertexCache.add(vdata);
        vertexBarcodes.add(barcode);
        eventGhost.addVertex(dataGhost);
        if (!weights.isEmpty()) dataGhost.addAttribute("weights", new VectorDoubleAttribute(weights));
        vertexCacheGhost.add(dataGhost);
        if (Setup.debugging(10)) {
            Setup.debug(10, "ReaderAsciiHepMC2: V: " + -vertexCache.size() + " (old barcode " + barcode + ") "
                + numParticlesOut + " particles)");
        }
        return numParticlesOut;
    }

    private int parseParticleInformation(String buf) {
        final GenParticle pdata = new GenParticle();
        final GenParticle dataGhost = new GenParticle();
        eventGhost.addParticle(dataGhost);
        int cursor = 0;
        if ((cursor = next(buf, cursor)) < 0) return -1;
        if ((cursor = next(buf, cursor)) < 0) return -1;
        pdata.setPid(CStr.atoi(buf, cursor));
        if ((cursor = next(buf, cursor)) < 0) return -1;
        final double px = CStr.atof(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return -1;
        final double py = CStr.atof(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return -1;
        final double pz = CStr.atof(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return -1;
        final double e = CStr.atof(buf, cursor);
        pdata.setMomentum(new FourVector(px, py, pz, e));
        if ((cursor = next(buf, cursor)) < 0) return -1;
        pdata.setGeneratedMass(CStr.atof(buf, cursor));
        if ((cursor = next(buf, cursor)) < 0) return -1;
        pdata.setStatus(CStr.atoi(buf, cursor));
        if ((cursor = next(buf, cursor)) < 0) return -1;
        final double thetaV = CStr.atof(buf, cursor);
        if (thetaV != 0.0) dataGhost.addAttribute("theta", new DoubleAttribute(thetaV));
        if ((cursor = next(buf, cursor)) < 0) return -1;
        final double phiV = CStr.atof(buf, cursor);
        if (phiV != 0.0) dataGhost.addAttribute("phi", new DoubleAttribute(phiV));
        if ((cursor = next(buf, cursor)) < 0) return -1;
        int endVtx = CStr.atoi(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return -1;
        final int flowsize = CStr.atoi(buf, cursor);
        final TreeMap<Integer, Integer> flows = new TreeMap<>();
        for (int i = 0; i < flowsize; i++) {
            if ((cursor = next(buf, cursor)) < 0) return -1;
            final int flowindex = CStr.atoi(buf, cursor);
            if ((cursor = next(buf, cursor)) < 0) return -1;
            final int flowvalue = CStr.atoi(buf, cursor);
            flows.put(flowindex, flowvalue);
        }
        if (flowsize != 0) {
            final List<Integer> vectorflows = new ArrayList<>(flows.values());
            dataGhost.addAttribute("flows", new VectorIntAttribute(vectorflows));
        }
        if (vertexCache.isEmpty()) {
            if (Setup.debugging(1)) Setup.debug(1, "The first particle in event appears before the first vertex");
            return -1;
        }
        if (endVtx == vertexBarcodes.get(vertexBarcodes.size() - 1)) {
            vertexCache.get(vertexCache.size() - 1).addParticleIn(pdata);
            endVtx = 0;
        } else {
            vertexCache.get(vertexCache.size() - 1).addParticleOut(pdata);
        }
        particleCache.add(pdata);
        particleCacheGhost.add(dataGhost);
        endVertexBarcodes.add(endVtx);
        if (Setup.debugging(10)) {
            Setup.debug(10, "ReaderAsciiHepMC2: P: " + particleCache.size() + " ( pid: " + pdata.pid() + ") end vertex: " + endVtx);
        }
        return 0;
    }

    private boolean parseXsInfo(GenEvent evt, String buf) {
        int cursor = 0;
        final GenCrossSection xs = new GenCrossSection();
        if ((cursor = next(buf, cursor)) < 0) return false;
        final double xsVal = CStr.atof(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return false;
        final double xsErr = CStr.atof(buf, cursor);
        final int all = options.containsKey("disable_pad_cross_sections") ? 1 : Math.max(evt.weights().size(), 1);
        final double xsValDummy = options.containsKey("pad_cross_section_value")
            ? CStr.strtod(options.get("pad_cross_section_value"), 0) : 0.0;
        final double xsErrDummy = options.containsKey("pad_cross_section_error")
            ? CStr.strtod(options.get("pad_cross_section_error"), 0) : 0.0;
        xs.setCrossSection(java.util.Collections.nCopies(all, xsValDummy), java.util.Collections.nCopies(all, xsErrDummy));
        xs.setXsec(0, xsVal);
        xs.setXsecErr(0, xsErr);
        evt.addAttribute("GenCrossSection", xs);
        return true;
    }

    private boolean parseWeightNames(String buf) {
        int cursor = 0;
        if (runInfo() == null) return true;
        if ((cursor = next(buf, cursor)) < 0) return false;
        final int wCount = CStr.atoi(buf, cursor);
        if (wCount <= 0) return false;
        final List<String> wNames = new ArrayList<>(wCount);
        for (int i = 0; i < wCount; ++i) {
            if ((cursor = CStr.strchr(buf, cursor + 1, '"')) < 0) return false;
            final int cursor2 = CStr.strchr(buf, cursor + 1, '"');
            if (cursor2 < 0) return false;
            ++cursor;
            wNames.add(buf.substring(cursor, cursor2));
            cursor = cursor2;
        }
        runInfo().setWeightNames(wNames);
        return true;
    }

    private static boolean parseHeavyIon(GenEvent evt, String buf) {
        final GenHeavyIon hi = new GenHeavyIon();
        int cursor = 0;
        if ((cursor = next(buf, cursor)) < 0) return false;
        hi.nCollHard = CStr.atoi(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return false;
        hi.nPartProj = CStr.atoi(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return false;
        hi.nPartTarg = CStr.atoi(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return false;
        hi.nColl = CStr.atoi(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return false;
        hi.spectatorNeutrons = CStr.atoi(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return false;
        hi.spectatorProtons = CStr.atoi(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return false;
        hi.nNwoundedCollisions = CStr.atoi(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return false;
        hi.nwoundedNCollisions = CStr.atoi(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return false;
        hi.nwoundedNwoundedCollisions = CStr.atoi(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return false;
        hi.impactParameter = CStr.atof(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return false;
        hi.eventPlaneAngle = CStr.atof(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return false;
        hi.eccentricity = CStr.atof(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return false;
        hi.sigmaInelNN = CStr.atof(buf, cursor);
        hi.centrality = 0.0;
        evt.addAttribute("GenHeavyIon", hi);
        return true;
    }

    private static boolean parsePdfInfo(GenEvent evt, String buf) {
        final GenPdfInfo pi = new GenPdfInfo();
        int cursor = 0;
        if ((cursor = next(buf, cursor)) < 0) return false;
        pi.partonId[0] = CStr.atoi(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return false;
        pi.partonId[1] = CStr.atoi(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return false;
        pi.x[0] = CStr.atof(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return false;
        pi.x[1] = CStr.atof(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return false;
        pi.scale = CStr.atof(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return false;
        pi.xf[0] = CStr.atof(buf, cursor);
        if ((cursor = next(buf, cursor)) < 0) return false;
        pi.xf[1] = CStr.atof(buf, cursor);
        boolean pdfids = true;
        if ((cursor = next(buf, cursor)) < 0) pdfids = false;
        pi.pdfId[0] = pdfids ? CStr.atoi(buf, cursor) : 0;
        if (pdfids && (cursor = next(buf, cursor)) < 0) pdfids = false;
        pi.pdfId[1] = pdfids ? CStr.atoi(buf, cursor) : 0;
        evt.addAttribute("GenPdfInfo", pi);
        return true;
    }

    @Override
    public boolean failed() {
        return in.rdstate() != CInput.GOOD;
    }

    @Override
    public void close() {
        if (eventGhost != null) {
            eventGhost.clear();
            eventGhost = null;
        }
        if (!in.isOpen()) return;
        in.close();
    }
}
