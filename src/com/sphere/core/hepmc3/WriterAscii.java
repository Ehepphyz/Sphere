package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFiles;
import com.sphere.core.hepmc3.cxx.CFormat;
import com.sphere.core.hepmc3.cxx.COutput;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Writes HepMC3's text format, Asciiv3, as the C++ writes it to the
 * character: header, run information, then per event its number and sizes,
 * units, weights (%.22e), attributes, and the particles in id order, each
 * preceded by its production vertex when that vertex has to be written
 * (several incoming particles, or a status or position); otherwise the
 * particle names its single mother.
 */
public class WriterAscii extends Writer {

    private final COutput out;
    private final boolean ownsFile;
    private int precision = 16;
    private char floatLetter = 'e';
    private final StringBuilder buffer = new StringBuilder(1 << 16);

    public WriterAscii(Path filename, GenRunInfo run) {
        out = COutput.create(filename);
        ownsFile = true;
        setRunInfo(run);
        if (!out.isOpen()) {
            Setup.error(200, "WriterAscii: could not open output file: " + filename);
        } else {
            out.write("HepMC::Version " + HepMC3.version() + "\nHepMC::Asciiv3-START_EVENT_LISTING\n");
            if (runInfo() != null) writeRunInfo();
        }
    }

    public WriterAscii(Path filename) {
        this(filename, null);
    }

    public WriterAscii(String filename) {
        this(CFiles.path(filename), null);
    }

    public WriterAscii(String filename, GenRunInfo run) {
        this(CFiles.path(filename), run);
    }

    public WriterAscii(OutputStream stream, GenRunInfo run) {
        this(new COutput(stream), run);
    }

    public WriterAscii(COutput stream, GenRunInfo run) {
        out = stream;
        ownsFile = false;
        setRunInfo(run);
        out.write("HepMC::Version " + HepMC3.version() + "\nHepMC::Asciiv3-START_EVENT_LISTING\n");
        if (runInfo() != null) writeRunInfo();
    }

    /** Digits after the point of the momenta and positions, 2 to 24 (16 by default). */
    public void setPrecision(int prec) {
        if (prec < 2 || prec > 24) return;
        precision = prec;
    }

    public int precision() {
        return precision;
    }

    private static String sign(double v) {
        return (v < 0 || (v == 0 && 1 / v < 0)) ? "-" : "";
    }

    /** " %.{precision}e" of a value, the C way. */
    private void appendFloat(double v) {
        buffer.append(' ');
        if (Double.isNaN(v) || Double.isInfinite(v) || floatLetter != 'e') {
            buffer.append(CFormat.sprintf("%." + precision + floatLetter, v));
        } else {
            buffer.append(sign(v)).append(CFormat.e(Math.abs(v), precision, false));
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
        floatLetter = letter.charAt(0);
        flush();
        if (runInfo() == null) {
            setRunInfo(evt.runInfo());
            writeRunInfo();
        } else if (evt.runInfo() != null && runInfo() != evt.runInfo()) {
            Setup.warning(600, "WriterAscii::write_event: GenEvents contain different GenRunInfo objects from - only the first such object will be serialized.");
        }
        flush();
        buffer.append("E ").append(evt.eventNumber()).append(' ').append(evt.vertices().size()).append(' ')
            .append(evt.particles().size());
        final FourVector pos = evt.eventPos();
        if (!pos.isZero()) {
            buffer.append(" @");
            appendFloat(pos.x());
            appendFloat(pos.y());
            appendFloat(pos.z());
            appendFloat(pos.t());
        }
        buffer.append('\n');
        flush();
        buffer.append("U ").append(Units.name(evt.momentumUnit())).append(' ').append(Units.name(evt.lengthUnit())).append('\n');
        flush();
        if (!evt.weights().isEmpty()) {
            buffer.append('W');
            final int wp = Math.min(3 * precision, 22);
            for (double w : evt.weights()) {
                buffer.append(' ').append(CFormat.sprintf("%." + wp + "e", w));
                flush();
            }
            buffer.append('\n');
            flush();
        }
        for (Map.Entry<String, TreeMap<Integer, Attribute>> vt1 : evt.attributes().entrySet()) {
            for (Map.Entry<Integer, Attribute> vt2 : vt1.getValue().entrySet()) {
                final String st = vt2.getValue().serialize();
                if (st == null) {
                    Setup.warning(300, "WriterAscii::write_event: problem serializing attribute: " + vt1.getKey());
                } else {
                    buffer.append("A ").append(vt2.getKey()).append(' ');
                    buffer.append(escape(vt1.getKey()));
                    flush();
                    buffer.append(' ');
                    buffer.append(escape(st));
                    buffer.append('\n');
                    flush();
                }
            }
        }
        final Set<Integer> alreadyWritten = new HashSet<>();
        for (GenParticle p : evt.particles()) {
            final GenVertex v = p.productionVertex();
            int parentObject = 0;
            if (v != null) {
                if (v.particlesIn().size() > 1 || !v.data().isZero()) {
                    parentObject = v.id();
                } else if (v.particlesIn().size() == 1) {
                    parentObject = v.particlesIn().get(0).id();
                } else if (v.particlesIn().isEmpty() && Setup.debugging(30)) {
                    Setup.debug(30, "WriterAscii::write_event - found a vertex without incoming particles: " + v.id());
                }
                if (!alreadyWritten.contains(v.id()) && parentObject < 0) {
                    writeVertex(v);
                    alreadyWritten.add(v.id());
                }
            }
            writeParticle(p, parentObject);
        }
        forcedFlush();
    }

    /** '\' to "\\", newline to "\|". */
    static String escape(String s) {
        StringBuilder ret = null;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\\' || c == '\n') {
                if (ret == null) {
                    ret = new StringBuilder(s.length() * 2);
                    ret.append(s, 0, i);
                }
                ret.append(c == '\\' ? "\\\\" : "\\|");
            } else if (ret != null) {
                ret.append(c);
            }
        }
        return ret == null ? s : ret.toString();
    }

    private void writeVertex(GenVertex v) {
        flush();
        final List<Integer> pids = new ArrayList<>(v.particlesIn().size());
        for (GenParticle p : v.particlesIn()) pids.add(p.id());
        java.util.Collections.sort(pids);
        final StringBuilder vlist = new StringBuilder();
        for (int k = 0; k < pids.size(); k++) {
            if (k > 0) vlist.append(',');
            vlist.append(pids.get(k));
        }
        final FourVector pos = v.position();
        buffer.append("V ").append(v.id()).append(' ').append(v.status()).append(" [").append(vlist).append(']');
        if (!pos.isZero()) {
            buffer.append(" @");
            appendFloat(pos.x());
            appendFloat(pos.y());
            appendFloat(pos.z());
            appendFloat(pos.t());
        }
        buffer.append('\n');
        flush();
    }

    private void writeParticle(GenParticle p, int secondField) {
        flush();
        buffer.append("P ").append(p.id()).append(' ').append(secondField).append(' ').append(p.pid());
        final FourVector m = p.momentum();
        appendFloat(m.px());
        appendFloat(m.py());
        appendFloat(m.pz());
        appendFloat(m.e());
        appendFloat(p.generatedMass());
        buffer.append(' ').append(p.status()).append('\n');
        flush();
    }

    /** Writes the run information: weight names, tools, attributes. */
    public void writeRunInfo() {
        if (runInfo() == null) setRunInfo(new GenRunInfo());
        final List<String> names = runInfo().weightNames();
        if (!names.isEmpty()) {
            final StringBuilder o = new StringBuilder(names.get(0));
            for (int i = 1; i < names.size(); ++i) o.append('\n').append(names.get(i));
            buffer.append("W ");
            flush();
            buffer.append(escape(o.toString()));
            buffer.append('\n');
        }
        for (GenRunInfo.ToolInfo tool : runInfo().tools()) {
            buffer.append(escape("T " + tool.name + "\n" + tool.version + "\n" + tool.description));
            buffer.append('\n');
        }
        for (Map.Entry<String, Attribute> att : runInfo().attributes().entrySet()) {
            final String st = att.getValue().serialize();
            if (st == null) {
                Setup.warning(300, "WriterAscii::write_run_info: problem serializing attribute: " + att.getKey());
            } else {
                buffer.append("A ");
                buffer.append(att.getKey());
                flush();
                buffer.append(' ');
                buffer.append(escape(st));
                buffer.append('\n');
                flush();
            }
        }
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
        out.write("HepMC::Asciiv3-END_EVENT_LISTING\n\n");
        if (ownsFile) out.close();
        else out.flush();
    }

    private boolean closed;

    @Override
    public boolean failed() {
        return out.failed();
    }
}
