package com.sphere.core.bridge;

import com.sphere.core.hepmc3.FourVector;
import com.sphere.core.hepmc3.GenCrossSection;
import com.sphere.core.hepmc3.GenEvent;
import com.sphere.core.hepmc3.GenEventData;
import com.sphere.core.hepmc3.GenParticle;
import com.sphere.core.hepmc3.GenParticleData;
import com.sphere.core.hepmc3.GenPdfInfo;
import com.sphere.core.hepmc3.GenRunInfo;
import com.sphere.core.hepmc3.GenVertexData;
import com.sphere.core.hepmc3.HepMC3;
import com.sphere.core.hepmc3.ReaderAscii;
import com.sphere.core.hepmc3.Units;
import com.sphere.core.hepmc3.WriterAscii;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HepMC3 events as SPX, for every engine, and back.
 *
 * <p>The file is an ordinary SPX event sample (kind 2): the sections every
 * reader already knows, {@code evt_offset}, {@code p4}, {@code pdg},
 * {@code weight} and, when every event carries a GenPdfInfo,
 * {@code incoming}, so that what reads the :fjet events reads these. To them
 * it adds the whole record, column by column, the way GenEventData holds it,
 * so that nothing is lost and the events read back are the events written,
 * ids included:
 *
 * <pre>
 *   status, gen_mass, mass_set   per particle (gen_mass is NaN when not set)
 *   vtx_offset, vtx_pos, vtx_status   the vertices (x, y, z, t), per event
 *   link_offset, links           (particle id, vertex id) for a particle entering a
 *                                vertex, (vertex id, particle id) leaving it, as HepMC3
 *   event_number, event_pos, units   per event (units: 0 GeV / 1 MeV, 0 mm / 1 cm)
 *   wgt_offset, wgt_all          every weight of every event
 *   attributes                   text: event, id, name, value, tab separated, escaped
 *   run_info                     text: the run information as Asciiv3 writes it
 * </pre>
 *
 * and, for the engines, the graph made easy: {@code prod_vtx} and
 * {@code end_vtx} (the vertex a particle comes from and goes to, counted from
 * 0 in its event, -1 for none), {@code mothers} and {@code daughters} (the
 * HEPEVT ranges JMOHEP and JDAHEP, counted from 1 in the event, 0 for none),
 * and {@code xsec} (cross-section and error per event, NaN when absent).
 */
public final class SpxHepMC {

    private SpxHepMC() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * The event's cross-section, read from its text into an object of its
     * own: asking the event for its GenCrossSection would parse it in place
     * and pad it to the number of weights, which changes what the event is
     * written as afterwards (in C++ too). Null when the event has none.
     */
    public static GenCrossSection crossSectionOf(GenEvent e) {
        final String s = e.attributeAsString("GenCrossSection");
        if (s.isEmpty()) return null;
        final GenCrossSection cs = new GenCrossSection();
        return cs.fromString(s) ? cs : null;
    }

    /** The event's GenPdfInfo, the same way: read into an object of its own. */
    public static GenPdfInfo pdfInfoOf(GenEvent e) {
        final String s = e.attributeAsString("GenPdfInfo");
        if (s.isEmpty()) return null;
        final GenPdfInfo pi = new GenPdfInfo();
        return pi.fromString(s) ? pi : null;
    }

    /** What a file gives back. */
    public record Sample(List<GenEvent> events, GenRunInfo runInfo, Map<String, String> meta, boolean complete) {
    }

    private static String escape(String s) {
        final StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '\t' -> b.append("\\t");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static String unescape(String s) {
        final StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c != '\\' || i + 1 == s.length()) {
                b.append(c);
                continue;
            }
            final char n = s.charAt(++i);
            b.append(switch (n) {
                case 't' -> '\t';
                case 'n' -> '\n';
                case 'r' -> '\r';
                default -> n;
            });
        }
        return b.toString();
    }

    /** The run information as the Asciiv3 header carries it (empty when there is none). */
    private static String runInfoText(GenRunInfo run) {
        if (run == null) return "";
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final WriterAscii w = new WriterAscii(out, run);
        w.close();
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    private static GenRunInfo runInfoOf(String text) {
        if (text == null || text.isEmpty()) return null;
        final ReaderAscii r = new ReaderAscii(new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)));
        r.readEvent(new GenEvent());
        final GenRunInfo run = r.runInfo();
        r.close();
        return run;
    }

    /** Writes the events; the run information is the one given, else the first event's. */
    public static Path export(List<GenEvent> events, GenRunInfo run, String source, Path file) throws IOException {
        final int nev = events.size();
        final List<GenEventData> data = new ArrayList<>(nev);
        for (GenEvent e : events) {
            final GenEventData d = new GenEventData();
            e.writeData(d);
            data.add(d);
        }
        if (run == null && nev > 0) run = events.get(0).runInfo();

        final long[] evtOffset = new long[nev + 1];
        final long[] vtxOffset = new long[nev + 1];
        final long[] linkOffset = new long[nev + 1];
        final long[] wgtOffset = new long[nev + 1];
        for (int e = 0; e < nev; e++) {
            final GenEventData d = data.get(e);
            evtOffset[e + 1] = evtOffset[e] + d.particles.size();
            vtxOffset[e + 1] = vtxOffset[e] + d.vertices.size();
            linkOffset[e + 1] = linkOffset[e] + d.links1.size();
            wgtOffset[e + 1] = wgtOffset[e] + d.weights.size();
        }
        final int np = (int) evtOffset[nev];
        final int nv = (int) vtxOffset[nev];
        final int nl = (int) linkOffset[nev];

        final double[] p4 = new double[4 * np];
        final long[] pdg = new long[np];
        final long[] status = new long[np];
        final double[] genMass = new double[np];
        final long[] massSet = new long[np];
        final long[] prodVtx = new long[np];
        final long[] endVtx = new long[np];
        final long[] mothers = new long[2 * np];
        final long[] daughters = new long[2 * np];
        final double[] vtxPos = new double[4 * nv];
        final long[] vtxStatus = new long[nv];
        final long[] links = new long[2 * nl];
        final long[] eventNumber = new long[nev];
        final double[] eventPos = new double[4 * nev];
        final long[] units = new long[2 * nev];
        final double[] weight = new double[nev];
        final double[] wgtAll = new double[(int) wgtOffset[nev]];
        final double[] xsec = new double[2 * nev];
        final double[] incoming = new double[5 * nev];
        boolean allIncoming = nev > 0;
        boolean allGevMm = true;
        final StringBuilder attributes = new StringBuilder();

        for (int e = 0; e < nev; e++) {
            final GenEventData d = data.get(e);
            final GenEvent evt = events.get(e);
            final int p0 = (int) evtOffset[e];
            final int v0 = (int) vtxOffset[e];
            for (int i = 0; i < d.particles.size(); i++) {
                final GenParticleData pd = d.particles.get(i);
                final int at = p0 + i;
                p4[4 * at] = pd.momentum.px();
                p4[4 * at + 1] = pd.momentum.py();
                p4[4 * at + 2] = pd.momentum.pz();
                p4[4 * at + 3] = pd.momentum.e();
                pdg[at] = pd.pid;
                status[at] = pd.status;
                genMass[at] = pd.isMassSet ? pd.mass : Double.NaN;
                massSet[at] = pd.isMassSet ? 1 : 0;
                prodVtx[at] = -1;
                endVtx[at] = -1;
            }
            for (int k = 0; k < d.vertices.size(); k++) {
                final GenVertexData vd = d.vertices.get(k);
                final int at = v0 + k;
                vtxPos[4 * at] = vd.position.x();
                vtxPos[4 * at + 1] = vd.position.y();
                vtxPos[4 * at + 2] = vd.position.z();
                vtxPos[4 * at + 3] = vd.position.t();
                vtxStatus[at] = vd.status;
            }
            final int l0 = (int) linkOffset[e];
            for (int k = 0; k < d.links1.size(); k++) {
                final int a = d.links1.get(k);
                final int b = d.links2.get(k);
                links[2 * (l0 + k)] = a;
                links[2 * (l0 + k) + 1] = b;
                if (a > 0) endVtx[p0 + a - 1] = -b - 1;
                else prodVtx[p0 + b - 1] = -a - 1;
            }
            // the HEPEVT ranges, from the vertices the particles meet at
            final List<GenParticle> ps = evt.particles();
            for (int i = 0; i < ps.size(); i++) {
                final GenParticle p = ps.get(i);
                range(mothers, p0 + i, p.productionVertex() == null || p.productionVertex().id() == 0 ? null
                    : p.productionVertex().particlesIn());
                range(daughters, p0 + i, p.endVertex() == null ? null : p.endVertex().particlesOut());
            }
            eventNumber[e] = d.eventNumber;
            eventPos[4 * e] = d.eventPos.x();
            eventPos[4 * e + 1] = d.eventPos.y();
            eventPos[4 * e + 2] = d.eventPos.z();
            eventPos[4 * e + 3] = d.eventPos.t();
            units[2 * e] = d.momentumUnit == Units.MomentumUnit.GEV ? 0 : 1;
            units[2 * e + 1] = d.lengthUnit == Units.LengthUnit.MM ? 0 : 1;
            if (units[2 * e] != 0 || units[2 * e + 1] != 0) allGevMm = false;
            weight[e] = d.weights.isEmpty() ? 1.0 : d.weights.get(0);
            for (int k = 0; k < d.weights.size(); k++) wgtAll[(int) wgtOffset[e] + k] = d.weights.get(k);
            final GenCrossSection cs = crossSectionOf(evt);
            xsec[2 * e] = cs == null ? Double.NaN : cs.xsec();
            xsec[2 * e + 1] = cs == null ? Double.NaN : cs.xsecErr();
            final GenPdfInfo pi = pdfInfoOf(evt);
            if (pi == null) {
                allIncoming = false;
            } else {
                incoming[5 * e] = pi.partonId[0];
                incoming[5 * e + 1] = pi.partonId[1];
                incoming[5 * e + 2] = pi.x[0];
                incoming[5 * e + 3] = pi.x[1];
                incoming[5 * e + 4] = pi.scale;
            }
            for (int k = 0; k < d.attributeId.size(); k++) {
                attributes.append(e).append('\t').append(d.attributeId.get(k)).append('\t')
                    .append(escape(d.attributeName.get(k))).append('\t').append(escape(d.attributeString.get(k))).append('\n');
            }
        }

        final Map<String, String> meta = new LinkedHashMap<>();
        meta.put("Format", "HepMC3");
        meta.put("HepMC3", HepMC3.VERSION_LINE);
        meta.put("Events", Integer.toString(nev));
        meta.put("Particles", Integer.toString(np));
        meta.put("Vertices", Integer.toString(nv));
        meta.put("Units", allGevMm ? "GEV MM" : "per event, see the section units (0 GeV / 1 MeV, 0 mm / 1 cm)");
        if (run != null) {
            meta.put("WeightNames", String.join(" | ", run.weightNames()));
            final List<String> tools = new ArrayList<>();
            for (GenRunInfo.ToolInfo t : run.tools()) tools.add((t.name + " " + t.version).strip());
            if (!tools.isEmpty()) meta.put("Tools", String.join(" | ", tools));
        }
        meta.put("Source", source == null ? "" : source);
        if (allIncoming) meta.put("Incoming", "id1 id2 x1 x2 scale, per event (GenPdfInfo)");

        final String title = source == null || source.isEmpty() ? "hepmc" : Path.of(source).getFileName().toString();
        final Spx.Writer out = new Spx.Writer(Spx.Kind.EVENTS, title);
        out.meta(meta);
        out.i64("evt_offset", evtOffset);
        out.f64("p4", p4);
        out.i64("pdg", pdg);
        out.f64("weight", weight);
        if (allIncoming) out.f64("incoming", incoming);
        out.i64("status", status);
        out.f64("gen_mass", genMass);
        out.i64("mass_set", massSet);
        out.i64("prod_vtx", prodVtx);
        out.i64("end_vtx", endVtx);
        out.i64("mothers", mothers);
        out.i64("daughters", daughters);
        out.i64("vtx_offset", vtxOffset);
        out.f64("vtx_pos", vtxPos);
        out.i64("vtx_status", vtxStatus);
        out.i64("link_offset", linkOffset);
        out.i64("links", links);
        out.i64("event_number", eventNumber);
        out.f64("event_pos", eventPos);
        out.i64("units", units);
        out.i64("wgt_offset", wgtOffset);
        out.f64("wgt_all", wgtAll);
        out.f64("xsec", xsec);
        out.text("attributes", attributes.toString());
        out.text("run_info", runInfoText(run));
        return out.write(file);
    }

    /** The first and last id (from 1) of the particles, or 0 0. */
    private static void range(long[] into, int at, List<GenParticle> ps) {
        long lo = 0;
        long hi = 0;
        if (ps != null) {
            for (GenParticle p : ps) {
                if (p.id() <= 0) continue;
                lo = lo == 0 ? p.id() : Math.min(lo, p.id());
                hi = Math.max(hi, p.id());
            }
        }
        into[2 * at] = lo;
        into[2 * at + 1] = hi;
    }

    /**
     * Reads a sample back: whole when it holds the record (a file of this
     * class, ids and attributes included); else, from any SPX event sample
     * (the :fjet events, or one an engine wrote), one event of final-state
     * particles each, status 1, with their weight and incoming partons.
     */
    public static Sample read(Path file) throws IOException {
        try (Spx.Reader r = new Spx.Reader(file)) {
            if (r.kind() != Spx.Kind.EVENTS) throw new IOException(file + " does not hold events");
            final Map<String, String> meta = r.meta();
            final long[] evtOffset = r.i64("evt_offset");
            final double[] p4 = r.f64("p4");
            final long[] pdg = r.i64("pdg");
            final double[] weight = r.has("weight") ? r.f64("weight") : null;
            final double[] incoming = r.has("incoming") ? r.f64("incoming") : null;
            final int nev = evtOffset.length - 1;
            final List<GenEvent> events = new ArrayList<>(nev);
            if (!r.has("links")) {
                for (int e = 0; e < nev; e++) {
                    final GenEventData d = new GenEventData();
                    d.eventNumber = e;
                    for (long i = evtOffset[e]; i < evtOffset[e + 1]; i++) {
                        final GenParticleData pd = new GenParticleData();
                        final int k = (int) i;
                        pd.momentum = new FourVector(p4[4 * k], p4[4 * k + 1], p4[4 * k + 2], p4[4 * k + 3]);
                        pd.pid = (int) pdg[k];
                        pd.status = 1;
                        d.particles.add(pd);
                    }
                    if (weight != null) d.weights.add(weight[e]);
                    final GenEvent evt = new GenEvent();
                    evt.readData(d);
                    if (incoming != null) {
                        final GenPdfInfo pi = new GenPdfInfo();
                        pi.set((int) incoming[5 * e], (int) incoming[5 * e + 1], incoming[5 * e + 2], incoming[5 * e + 3],
                            incoming[5 * e + 4], 0, 0);
                        evt.setPdfInfo(pi);
                    }
                    events.add(evt);
                }
                return new Sample(events, null, meta, false);
            }
            final long[] status = r.i64("status");
            final double[] genMass = r.f64("gen_mass");
            final long[] massSet = r.i64("mass_set");
            final long[] vtxOffset = r.i64("vtx_offset");
            final double[] vtxPos = r.f64("vtx_pos");
            final long[] vtxStatus = r.i64("vtx_status");
            final long[] linkOffset = r.i64("link_offset");
            final long[] links = r.i64("links");
            final long[] eventNumber = r.i64("event_number");
            final double[] eventPos = r.f64("event_pos");
            final long[] units = r.i64("units");
            final long[] wgtOffset = r.i64("wgt_offset");
            final double[] wgtAll = r.f64("wgt_all");
            final List<List<String[]>> atts = new ArrayList<>();
            for (int e = 0; e < nev; e++) atts.add(new ArrayList<>());
            for (String line : r.text("attributes").split("\n")) {
                if (line.isEmpty()) continue;
                final String[] f = line.split("\t", 4);
                if (f.length < 4) continue;
                final int e = Integer.parseInt(f[0]);
                if (e >= 0 && e < nev) atts.get(e).add(f);
            }
            final GenRunInfo run = runInfoOf(r.has("run_info") ? r.text("run_info") : "");
            for (int e = 0; e < nev; e++) {
                final GenEventData d = new GenEventData();
                d.eventNumber = (int) eventNumber[e];
                d.momentumUnit = units[2 * e] == 0 ? Units.MomentumUnit.GEV : Units.MomentumUnit.MEV;
                d.lengthUnit = units[2 * e + 1] == 0 ? Units.LengthUnit.MM : Units.LengthUnit.CM;
                d.eventPos = new FourVector(eventPos[4 * e], eventPos[4 * e + 1], eventPos[4 * e + 2], eventPos[4 * e + 3]);
                for (long i = evtOffset[e]; i < evtOffset[e + 1]; i++) {
                    final int k = (int) i;
                    final GenParticleData pd = new GenParticleData();
                    pd.momentum = new FourVector(p4[4 * k], p4[4 * k + 1], p4[4 * k + 2], p4[4 * k + 3]);
                    pd.pid = (int) pdg[k];
                    pd.status = (int) status[k];
                    pd.isMassSet = massSet[k] != 0;
                    pd.mass = pd.isMassSet ? genMass[k] : 0.0;
                    d.particles.add(pd);
                }
                for (long v = vtxOffset[e]; v < vtxOffset[e + 1]; v++) {
                    final int k = (int) v;
                    final GenVertexData vd = new GenVertexData();
                    vd.status = (int) vtxStatus[k];
                    vd.position = new FourVector(vtxPos[4 * k], vtxPos[4 * k + 1], vtxPos[4 * k + 2], vtxPos[4 * k + 3]);
                    d.vertices.add(vd);
                }
                for (long l = linkOffset[e]; l < linkOffset[e + 1]; l++) {
                    d.links1.add((int) links[(int) (2 * l)]);
                    d.links2.add((int) links[(int) (2 * l + 1)]);
                }
                for (long w = wgtOffset[e]; w < wgtOffset[e + 1]; w++) d.weights.add(wgtAll[(int) w]);
                for (String[] f : atts.get(e)) {
                    d.attributeId.add(Integer.parseInt(f[1]));
                    d.attributeName.add(unescape(f[2]));
                    d.attributeString.add(unescape(f[3]));
                }
                final GenEvent evt = new GenEvent();
                evt.readData(d);
                if (run != null) evt.setRunInfo(run);
                events.add(evt);
            }
            return new Sample(events, run, meta, true);
        }
    }
}
