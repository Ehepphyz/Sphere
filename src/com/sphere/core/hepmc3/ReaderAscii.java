package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFiles;
import com.sphere.core.hepmc3.cxx.CIStream;
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
import java.util.TreeSet;

/**
 * Reads HepMC3's own text format, Asciiv3: run information (W weight names,
 * T tools, A attributes), then per event E, U units, W weights, A attributes,
 * V vertices and P particles, a particle naming either its production vertex
 * or, when that vertex is implicit, its single mother.
 */
public class ReaderAscii extends Reader {

    private static final int BUFFER = 262144;

    private final CInput in;
    private final boolean isStream;
    /** Explicit vertices by id: incoming and outgoing particle ids. */
    private final TreeMap<Integer, Io> ioExplicit = new TreeMap<>();
    /** Implicit vertices, by the id of their single mother. */
    private final HashMap<Integer, Io> ioImplicit = new HashMap<>();
    private final List<Integer> ioImplicitIds = new ArrayList<>();
    private final TreeSet<Integer> ioExplicitIds = new TreeSet<>();
    private final GenEventData data = new GenEventData();

    private static final class Io {
        final TreeSet<Integer> in = new TreeSet<>();
        final TreeSet<Integer> out = new TreeSet<>();
    }

    public ReaderAscii(Path filename) {
        in = CInput.open(filename);
        isStream = false;
        if (!in.isOpen()) Setup.error(100, "ReaderAscii: could not open input file: " + filename);
        setRunInfo(new GenRunInfo());
    }

    public ReaderAscii(String filename) {
        this(CFiles.path(filename));
    }

    public ReaderAscii(InputStream stream) {
        this(new CInput(stream));
    }

    public ReaderAscii(CInput stream) {
        in = stream;
        isStream = true;
        if (!in.good()) Setup.error(100, "ReaderAscii: could not open input stream ");
        setRunInfo(new GenRunInfo());
    }

    private boolean usable() {
        return isStream || in.isOpen();
    }

    @Override
    public boolean skip(int n) {
        boolean eventContext = false;
        boolean runInfoContext = false;
        int nn = n;
        while (!failed()) {
            if (!usable()) return false;
            final int peek = in.peek();
            if (peek == 'E') {
                eventContext = true;
                nn--;
            }
            if (!eventContext && (peek == 'W' || peek == 'A' || peek == 'T')) {
                final String buf = in.getline(BUFFER);
                if (!runInfoContext) {
                    setRunInfo(new GenRunInfo());
                    runInfoContext = true;
                }
                if (peek == 'W') parseWeightNames(buf);
                if (peek == 'T') parseTool(buf);
                if (peek == 'A') parseRunAttribute(buf);
            }
            if (eventContext && (peek == 'V' || peek == 'P')) eventContext = false;
            if (nn < 0) return true;
            in.getline(BUFFER);
        }
        return true;
    }

    @Override
    public boolean readEvent(GenEvent evt) {
        if (!usable()) return false;
        String buf = "";
        boolean eventContext = false;
        boolean parsedWeights = false;
        boolean parsedParticlesOrVertices = false;
        boolean runInfoContext = false;
        boolean isParsingSuccessful = false;
        int[] verticesAndParticles = {0, 0};
        evt.clear();
        evt.setRunInfo(runInfo());
        ioExplicit.clear();
        ioImplicit.clear();
        ioImplicitIds.clear();
        ioExplicitIds.clear();
        data.particles.clear();
        data.vertices.clear();
        data.eventPos = new FourVector();
        data.links1.clear();
        data.links2.clear();
        data.attributeId.clear();
        data.attributeName.clear();
        data.attributeString.clear();

        while (!failed()) {
            buf = in.getline(BUFFER);
            if (buf.length() < 2) continue;
            if (buf.startsWith("HepMC")) {
                if (!buf.startsWith("HepMC::Version") && !buf.startsWith("HepMC::Asciiv3")) {
                    Setup.warning(500, "ReaderAscii: found unsupported expression in header. Will close the input.");
                    StdStreams.cout().println(buf);
                    in.clear(CInput.EOF);
                }
                if (eventContext) {
                    isParsingSuccessful = true;
                    in.clear();
                    break;
                }
                continue;
            }
            if (buf.charAt(1) != ' ') {
                isParsingSuccessful = false;
                break;
            }
            switch (buf.charAt(0)) {
                case 'E' -> {
                    verticesAndParticles = parseEventInformation(buf);
                    if (verticesAndParticles[1] < 0) {
                        isParsingSuccessful = false;
                        eventContext = true;
                    } else {
                        isParsingSuccessful = true;
                        eventContext = true;
                        parsedWeights = false;
                        parsedParticlesOrVertices = false;
                    }
                    runInfoContext = false;
                }
                case 'V' -> {
                    isParsingSuccessful = parseVertexInformation(buf);
                    parsedParticlesOrVertices = true;
                }
                case 'P' -> {
                    isParsingSuccessful = parseParticleInformation(buf);
                    parsedParticlesOrVertices = true;
                }
                case 'W' -> {
                    if (eventContext) {
                        isParsingSuccessful = parseWeightValues(buf);
                        parsedWeights = true;
                    } else {
                        if (!runInfoContext) {
                            setRunInfo(new GenRunInfo());
                            evt.setRunInfo(runInfo());
                        }
                        runInfoContext = true;
                        isParsingSuccessful = parseWeightNames(buf);
                    }
                }
                case 'U' -> isParsingSuccessful = parseUnits(buf);
                case 'T' -> {
                    if (!eventContext) {
                        if (!runInfoContext) {
                            setRunInfo(new GenRunInfo());
                            evt.setRunInfo(runInfo());
                        }
                        runInfoContext = true;
                        isParsingSuccessful = parseTool(buf);
                    }
                }
                case 'A' -> {
                    if (eventContext) {
                        isParsingSuccessful = parseAttribute(buf);
                    } else {
                        if (!runInfoContext) {
                            setRunInfo(new GenRunInfo());
                            evt.setRunInfo(runInfo());
                        }
                        runInfoContext = true;
                        isParsingSuccessful = parseRunAttribute(buf);
                    }
                }
                default -> {
                    Setup.warning(500, "ReaderAscii: skipping unrecognised prefix: " + buf.charAt(0));
                    isParsingSuccessful = true;
                }
            }
            if (!isParsingSuccessful) break;
            final int peek = in.peek();
            if (eventContext && peek == 'E') break;
            if (eventContext && peek == 'W' && parsedWeights) break;
            if (eventContext && peek == 'A' && parsedParticlesOrVertices) break;
            if (eventContext && peek == 'T') break;
        }
        if (isParsingSuccessful) {
            // the implicit vertices fill the gaps between the explicit ones
            int currid = -data.vertices.size();
            int fir = ioImplicitIds.size() - 1;
            for (int iofirst : ioExplicitIds) {
                for (; currid < iofirst; ++currid, --fir) {
                    if (fir < 0) {
                        Setup.error(600, "ReaderAscii: not enough implicit vertices");
                        // C++ goes on reading past the end of the list; here the gap stays empty
                        ioExplicit.put(currid, new Io());
                        continue;
                    }
                    final Io moved = ioImplicit.remove(ioImplicitIds.get(fir));
                    ioExplicit.put(currid, moved == null ? new Io() : moved);
                }
                ++currid;
            }
            for (Map.Entry<Integer, Io> io : ioExplicit.entrySet()) {
                for (int i : io.getValue().in) {
                    data.links1.add(i);
                    data.links2.add(io.getKey());
                }
                for (int o : io.getValue().out) {
                    data.links1.add(io.getKey());
                    data.links2.add(o);
                }
            }
            evt.readData(data);
        }
        if (evt.particles().size() > verticesAndParticles[1]) {
            Setup.error(600, "ReaderAscii: too many particles were parsed");
            StdStreams.cout().printf("%zu  vs  %i expected\n", (long) evt.particles().size(), verticesAndParticles[1]);
            isParsingSuccessful = false;
        }
        if (evt.particles().size() < verticesAndParticles[1]) {
            Setup.error(600, "ReaderAscii: too few  particles were parsed");
            StdStreams.cout().printf("%zu  vs  %i expected\n", (long) evt.particles().size(), verticesAndParticles[1]);
            isParsingSuccessful = false;
        }
        if (evt.vertices().size() > verticesAndParticles[0]) {
            Setup.error(600, "ReaderAscii: too many vertices were parsed");
            StdStreams.cout().printf("%zu  vs  %i expected\n", (long) evt.vertices().size(), verticesAndParticles[0]);
            isParsingSuccessful = false;
        }
        if (evt.vertices().size() < verticesAndParticles[0]) {
            Setup.error(600, "ReaderAscii: too few vertices were parsed");
            StdStreams.cout().printf("%zu  vs  %i expected\n", (long) evt.vertices().size(), verticesAndParticles[0]);
            isParsingSuccessful = false;
        }
        if (eventContext && !isParsingSuccessful) {
            Setup.error(600, "ReaderAscii: event parsing failed. Returning empty event");
            if (Setup.debugging(1)) Setup.debug(1, "Parsing failed at line:\n" + buf);
            evt.clear();
            in.clear(CInput.BAD);
            return false;
        }
        return true;
    }

    /** The first character that is not a blank, from i; -1 at the end of the line. */
    private static int nextToken(String buf, int i) {
        while (i < buf.length() && buf.charAt(i) == ' ') i++;
        return i >= buf.length() ? -1 : i;
    }

    private int[] parseEventInformation(String buf) {
        final int[] err = {-1, -1};
        final int[] ret = {-1, -1};
        int cursor;
        if ((cursor = nextToken(buf, 1)) < 0) return err;
        data.eventNumber = (int) CStr.strtol(buf, cursor, 10);
        if (CStr.end() == cursor) return err;
        cursor = CStr.end();
        if ((cursor = nextToken(buf, cursor)) < 0) return err;
        ret[0] = (int) CStr.strtol(buf, cursor, 10);
        if (CStr.end() == cursor) return err;
        cursor = CStr.end();
        if ((cursor = nextToken(buf, cursor)) < 0) return err;
        ret[1] = (int) CStr.strtol(buf, cursor, 10);
        if (CStr.end() == cursor) return err;
        cursor = CStr.end();
        data.vertices.clear();
        for (int k = 0; k < ret[0]; k++) data.vertices.add(new GenVertexData());
        data.particles.clear();
        for (int k = 0; k < ret[1]; k++) data.particles.add(new GenParticleData());
        final int at = CStr.strchr(buf, cursor, '@');
        if (at >= 0) {
            final FourVector position = data.eventPos;
            cursor = at;
            if ((cursor = nextToken(buf, cursor + 1)) < 0) return err;
            position.setX(CStr.strtod(buf, cursor));
            if (CStr.end() == cursor) return err;
            cursor = CStr.end();
            if ((cursor = nextToken(buf, cursor)) < 0) return err;
            position.setY(CStr.strtod(buf, cursor));
            if (CStr.end() == cursor) return err;
            cursor = CStr.end();
            if ((cursor = nextToken(buf, cursor)) < 0) return err;
            position.setZ(CStr.strtod(buf, cursor));
            if (CStr.end() == cursor) return err;
            cursor = CStr.end();
            if ((cursor = nextToken(buf, cursor)) < 0) return err;
            position.setT(CStr.strtod(buf, cursor));
            if (CStr.end() == cursor) return err;
        }
        if (Setup.debugging(10)) {
            Setup.debug(10, "ReaderAscii: E: " + data.eventNumber + " (" + ret[0] + "V, " + ret[1] + "P)");
        }
        return ret;
    }

    private boolean parseWeightValues(String buf) {
        final CIStream iss = new CIStream(buf.substring(1));
        final List<Double> wts = new ArrayList<>();
        while (true) {
            final double w = iss.nextDouble();
            if (iss.fail()) break;
            wts.add(w);
        }
        if (runInfo() != null && !runInfo().weightNames().isEmpty() && runInfo().weightNames().size() != wts.size()) {
            throw new IllegalStateException("ReaderAscii::parse_weight_values: The number of weights (" + wts.size()
                + ") does not match the  number weight names(" + runInfo().weightNames().size()
                + ") in the GenRunInfo object");
        }
        data.weights.clear();
        data.weights.addAll(wts);
        return true;
    }

    private boolean parseUnits(String buf) {
        int cursor;
        if ((cursor = nextToken(buf, 1)) < 0) return false;
        data.momentumUnit = Units.momentumUnit(buf.substring(cursor));
        cursor = cursor + 3;
        if ((cursor = nextToken(buf, cursor)) < 0) return false;
        data.lengthUnit = Units.lengthUnit(buf.substring(cursor));
        if (Setup.debugging(10)) {
            Setup.debug(10, "ReaderAscii: U: " + Units.name(data.momentumUnit) + " " + Units.name(data.lengthUnit));
        }
        return true;
    }

    private boolean parseVertexInformation(String buf) {
        int cursor;
        if ((cursor = nextToken(buf, 1)) < 0) return false;
        final int id = (int) CStr.strtol(buf, cursor, 10);
        if (CStr.end() == cursor) return false;
        cursor = CStr.end();
        if (-id - 1 < 0 || -id - 1 >= data.vertices.size()) {
            // C++ writes outside its vector here; the event is refused instead
            return false;
        }
        if ((cursor = nextToken(buf, cursor)) < 0) return false;
        data.vertices.get(-id - 1).status = (int) CStr.strtol(buf, cursor, 10);
        cursor = CStr.end();
        final FourVector position = data.vertices.get(-id - 1).position;
        if ((cursor = CStr.strchr(buf, cursor, '[')) < 0) return false;
        if ((cursor = nextToken(buf, cursor + 1)) < 0) return false;
        while (true) {
            final int particleIn = (int) CStr.strtol(buf, cursor, 10);
            if (CStr.end() == cursor) return false;
            cursor = CStr.end();
            final int cursor2 = cursor;
            if (particleIn > 0) ioExplicit.computeIfAbsent(id, k -> new Io()).in.add(particleIn);
            if ((cursor = CStr.strchr(buf, cursor, ',')) < 0) {
                if ((cursor = CStr.strchr(buf, cursor2, ']')) < 0) return false;
                break;
            }
            if ((cursor = nextToken(buf, cursor + 1)) < 0) return false;
        }
        final int at = CStr.strchr(buf, cursor, '@');
        if (at >= 0) {
            cursor = at;
            if ((cursor = nextToken(buf, cursor + 1)) < 0) return false;
            position.setX(CStr.strtod(buf, cursor));
            if (CStr.end() == cursor) return false;
            cursor = CStr.end();
            if ((cursor = nextToken(buf, cursor)) < 0) return false;
            position.setY(CStr.strtod(buf, cursor));
            if (CStr.end() == cursor) return false;
            cursor = CStr.end();
            if ((cursor = nextToken(buf, cursor)) < 0) return false;
            position.setZ(CStr.strtod(buf, cursor));
            if (CStr.end() == cursor) return false;
            cursor = CStr.end();
            if ((cursor = nextToken(buf, cursor)) < 0) return false;
            position.setT(CStr.strtod(buf, cursor));
            if (CStr.end() == cursor) return false;
        }
        return true;
    }

    private boolean parseParticleInformation(String buf) {
        int cursor;
        if ((cursor = nextToken(buf, 1)) < 0) return false;
        final int id = (int) CStr.strtol(buf, cursor, 10);
        if (CStr.end() == cursor) return false;
        cursor = CStr.end();
        if (id < 1 || id > data.particles.size()) {
            Setup.error(600, "ReaderAscii: particle ID is out of expected range.");
            return false;
        }
        final GenParticleData pd = data.particles.get(id - 1);
        final FourVector momentum = pd.momentum;
        if ((cursor = nextToken(buf, cursor)) < 0) return false;
        final int motherId = (int) CStr.strtol(buf, cursor, 10);
        if (CStr.end() == cursor) return false;
        cursor = CStr.end();
        if (motherId < -data.vertices.size() || motherId > data.particles.size()) {
            Setup.error(600, "ReaderAscii: ID of particle mother is out of expected range.");
            return false;
        }
        if (motherId > 0) {
            if (!ioImplicit.containsKey(motherId)) ioImplicitIds.add(motherId);
            final Io io = ioImplicit.computeIfAbsent(motherId, k -> new Io());
            io.in.add(motherId);
            io.out.add(id);
        } else {
            ioExplicit.computeIfAbsent(motherId, k -> new Io()).out.add(id);
            ioExplicitIds.add(motherId);
        }
        if ((cursor = nextToken(buf, cursor)) < 0) return false;
        pd.pid = (int) CStr.strtol(buf, cursor, 10);
        if (CStr.end() == cursor) return false;
        cursor = CStr.end();
        if ((cursor = nextToken(buf, cursor)) < 0) return false;
        momentum.setPx(CStr.strtod(buf, cursor));
        if (CStr.end() == cursor) return false;
        cursor = CStr.end();
        if ((cursor = nextToken(buf, cursor)) < 0) return false;
        momentum.setPy(CStr.strtod(buf, cursor));
        if (CStr.end() == cursor) return false;
        cursor = CStr.end();
        if ((cursor = nextToken(buf, cursor)) < 0) return false;
        momentum.setPz(CStr.strtod(buf, cursor));
        if (CStr.end() == cursor) return false;
        cursor = CStr.end();
        if ((cursor = nextToken(buf, cursor)) < 0) return false;
        momentum.setE(CStr.strtod(buf, cursor));
        if (CStr.end() == cursor) return false;
        cursor = CStr.end();
        if ((cursor = nextToken(buf, cursor)) < 0) return false;
        pd.mass = CStr.strtod(buf, cursor);
        if (CStr.end() == cursor) return false;
        cursor = CStr.end();
        pd.isMassSet = true;
        if ((cursor = nextToken(buf, cursor)) < 0) return false;
        pd.status = (int) CStr.strtol(buf, cursor, 10);
        return cursor != CStr.end();
    }

    private boolean parseAttribute(String buf) {
        int cursor;
        if ((cursor = nextToken(buf, 1)) < 0) return false;
        final int id = (int) CStr.strtol(buf, cursor, 10);
        if (CStr.end() == cursor) return false;
        cursor = CStr.end();
        if ((cursor = nextToken(buf, cursor)) < 0) return false;
        final int cursor2 = CStr.strchr(buf, cursor, ' ');
        if (cursor2 < 0) return false;
        final String name = clip(buf.substring(cursor, cursor2));
        cursor = nextToken(buf, cursor2);
        data.attributeId.add(id);
        data.attributeName.add(name);
        data.attributeString.add(cursor >= 0 ? unescape(buf.substring(cursor)) : "");
        return true;
    }

    private boolean parseRunAttribute(String buf) {
        int cursor;
        if ((cursor = nextToken(buf, 1)) < 0) return false;
        final int cursor2 = CStr.strchr(buf, cursor, ' ');
        if (cursor2 < 0) return false;
        final String name = clip(buf.substring(cursor, cursor2));
        cursor = nextToken(buf, cursor2);
        final StringAttribute att = new StringAttribute(cursor >= 0 ? unescape(buf.substring(cursor)) : "");
        runInfo().addAttribute(name, att);
        return true;
    }

    /** An attribute name goes through a 512-byte buffer: at most 511 characters survive. */
    private static String clip(String name) {
        return name.length() > 511 ? name.substring(0, 511) : name;
    }

    private boolean parseWeightNames(String buf) {
        final int cursor = nextToken(buf, 1);
        if (cursor < 0) return false;
        final CIStream iss = new CIStream(unescape(buf.substring(cursor)));
        final List<String> names = new ArrayList<>();
        while (true) {
            final String name = iss.nextWord();
            if (iss.fail()) break;
            names.add(name);
        }
        runInfo().setWeightNames(names);
        return true;
    }

    private boolean parseTool(String buf) {
        final int cursor = nextToken(buf, 1);
        if (cursor < 0) return false;
        String line = unescape(buf.substring(cursor));
        final GenRunInfo.ToolInfo tool = new GenRunInfo.ToolInfo();
        int pos = line.indexOf('\n');
        tool.name = pos < 0 ? line : line.substring(0, pos);
        line = pos < 0 ? line : line.substring(pos + 1);
        pos = line.indexOf('\n');
        tool.version = pos < 0 ? line : line.substring(0, pos);
        tool.description = pos < 0 ? line : line.substring(pos + 1);
        runInfo().tools().add(tool);
        return true;
    }

    /** "\\" back to '\', "\|" back to a newline. */
    static String unescape(String s) {
        final StringBuilder ret = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\\') {
                ++i;
                if (i >= s.length()) break;
                final char d = s.charAt(i);
                ret.append(d == '|' ? '\n' : d);
            } else {
                ret.append(c);
            }
        }
        return ret.toString();
    }

    @Override
    public boolean failed() {
        return in.rdstate() != CInput.GOOD;
    }

    @Override
    public void close() {
        if (!in.isOpen()) return;
        in.close();
    }
}
