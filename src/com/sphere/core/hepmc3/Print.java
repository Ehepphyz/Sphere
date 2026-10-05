package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * HepMC3's printing: the content of every container, the HepMC2-style
 * listing, and one-line summaries, written through an ostream with the same
 * widths, precisions and flags as the C++, so that the text is the same.
 *
 * <p>Methods taking a {@link COStream} write to it; {@code line(x)} returns
 * the one-line text; {@code printLine}, {@code content(event)} and
 * {@code listing(event)} write to std::cout as the C++ overloads without a
 * stream do.
 */
public final class Print {

    private Print() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** An ostream writing to this thread's std::cout. */
    public static COStream cout() {
        return new COStream() {
            @Override
            protected void write(CharSequence text) {
                StdStreams.cout().print(text);
            }
        };
    }

    /* ---- content ---------------------------------------------------------- */

    public static void content(COStream os, GenEvent event) {
        os.put("--------------------------------").endl();
        os.put("--------- EVENT CONTENT --------").endl();
        os.put("--------------------------------").endl();
        os.endl();
        os.put("Weights (").put(event.weights().size()).put("): ").endl();
        for (double w : event.weights()) os.put(" ").put(w);
        os.put("Attributes:").endl();
        for (Map.Entry<String, TreeMap<Integer, Attribute>> vt1 : event.attributes().entrySet()) {
            for (Map.Entry<Integer, Attribute> vt2 : vt1.getValue().entrySet()) {
                os.put(vt2.getKey()).put(": ").put(vt1.getKey()).endl();
            }
        }
        os.put("GenParticlePtr (").put(event.particles().size()).put(")").endl();
        for (GenParticle p : event.particles()) {
            line(os, p, true);
            os.endl();
        }
        os.put("GenVertexPtr (").put(event.vertices().size()).put(")").endl();
        for (GenVertex v : event.vertices()) {
            line(os, v, true);
            os.endl();
        }
        os.put("-----------------------------").endl();
    }

    public static void content(GenEvent event) {
        content(cout(), event);
    }

    public static String contentString(GenEvent event) {
        final COStream os = new COStream();
        content(os, event);
        return os.str();
    }

    /* ---- listing ------------------------------------------------------------ */

    public static void listing(COStream os, GenEvent event, int precision) {
        final COStream.Flags orig = os.flags();
        final int prec = os.precision();
        os.precision(precision);
        os.put("________________________________________________________________________").endl();
        os.put("GenEvent: #").put(event.eventNumber()).endl();
        os.put(" Momentum units: ").put(Units.name(event.momentumUnit()))
            .put(" Position units: ").put(Units.name(event.lengthUnit())).endl();
        os.put(" Entries in this event: ").put(event.vertices().size()).put(" vertices, ")
            .put(event.particles().size()).put(" particles, ")
            .put(event.weights().size()).put(" weights.").endl();
        final FourVector pos = event.eventPos();
        os.put(" Position offset: ").put(pos.x()).put(", ").put(pos.y()).put(", ").put(pos.z()).put(", ").put(pos.t()).endl();
        os.put("                                    GenParticle Legend").endl();
        os.put("         ID    PDG ID   ").put("( px,       py,       pz,     E )").put("   Stat ProdVtx").endl();
        os.put("________________________________________________________________________").endl();
        for (GenVertex v : event.vertices()) listing(os, v);
        os.flags(orig);
        os.precision(prec);
        os.put("________________________________________________________________________").endl();
    }

    public static void listing(GenEvent event) {
        listing(cout(), event, 2);
    }

    public static void listing(GenEvent event, int precision) {
        listing(cout(), event, precision);
    }

    public static String listingString(GenEvent event, int precision) {
        final COStream os = new COStream();
        listing(os, event, precision);
        return os.str();
    }

    public static void listing(COStream os, GenRunInfo ri, int precision) {
        final COStream.Flags orig = os.flags();
        final int prec = os.precision();
        os.precision(precision);
        os.put("________________________________________________________________________").endl();
        os.put("GenRunInfo:").endl();
        final List<String> names = ri.weightNames();
        os.put(" Names: ( ");
        for (String n : names) os.put(n);
        os.put(" )").endl();
        os.put(" Tools: ").endl();
        for (GenRunInfo.ToolInfo t : ri.tools()) line(os, t);
        os.put("Attributes:").endl();
        for (Map.Entry<String, Attribute> att : ri.attributes().entrySet()) {
            final String st = att.getValue().serialize();
            if (st == null) {
                Setup.warning(300, "Print::listing: problem serializing attribute: " + att.getKey());
            } else {
                os.put(att.getKey()).put(" ").put(st);
            }
            os.endl();
        }
        os.flags(orig);
        os.precision(prec);
        os.put("________________________________________________________________________").endl();
    }

    public static void listing(GenRunInfo ri) {
        listing(cout(), ri, 2);
    }

    private static void listing(COStream os, GenVertex v) {
        if (v == null) {
            os.put("Vtx: Empty vertex").endl();
            return;
        }
        os.put("Vtx: ");
        os.width(6);
        os.put(v.id()).put(" stat: ");
        os.width(3);
        os.put(v.status());
        final FourVector pos = v.position();
        if (!pos.isZero()) {
            os.put(" (X,cT): ").put(pos.x()).put(" ").put(pos.y()).put(" ").put(pos.z()).put(" ").put(pos.t());
        } else {
            os.put(" (X,cT): 0");
        }
        os.endl();
        boolean printedHeader = false;
        for (GenParticle p : v.particlesIn()) {
            if (!printedHeader) {
                os.put(" I: ");
                printedHeader = true;
            } else {
                os.put("    ");
            }
            listing(os, p);
        }
        printedHeader = false;
        for (GenParticle p : v.particlesOut()) {
            if (!printedHeader) {
                os.put(" O: ");
                printedHeader = true;
            } else {
                os.put("    ");
            }
            listing(os, p);
        }
    }

    private static void listing(COStream os, GenParticle p) {
        if (p == null) {
            os.put(" Empty particle").endl();
            return;
        }
        os.put(" ");
        os.width(6);
        os.put(p.id());
        os.width(9);
        os.put(p.pid()).put(" ");
        os.width(9);
        os.scientific();
        os.showpos(true);
        final FourVector momentum = p.momentum();
        os.width(9);
        os.put(momentum.px()).put(",");
        os.width(9);
        os.put(momentum.py()).put(",");
        os.width(9);
        os.put(momentum.pz()).put(",");
        os.width(9);
        os.put(momentum.e()).put(" ");
        os.defaultfloat();
        os.showpos(false);
        os.width(3);
        os.put(p.status());
        final GenVertex prod = p.productionVertex();
        if (prod != null) {
            os.width(6);
            os.put(prod.id());
        }
        os.endl();
    }

    /* ---- one-line summaries ---------------------------------------------------- */

    public static void line(COStream os, GenEvent event, boolean attributes) {
        os.put("GenEvent: #").put(event.eventNumber());
        if (attributes) {
            for (String s : event.attributeNames()) os.put(" ").put(s).put("=").put(event.attributeAsString(s));
        }
    }

    public static void line(COStream os, GenRunInfo runInfo, boolean attributes) {
        os.put("GenRunInfo: Number of tools:").put(runInfo.tools().size());
        if (attributes) {
            for (String s : runInfo.attributeNames()) os.put(" ").put(s).put("=").put(runInfo.attributeAsString(s));
        }
    }

    public static void line(COStream os, GenRunInfo.ToolInfo t) {
        os.put("GenRunInfo::ToolInfo ").put(t.name).put(" ").put(t.version).put(" ").put(t.description);
    }

    public static void line(COStream os, GenVertex v, boolean attributes) {
        if (v == null) {
            os.put("GenVertex: Empty").endl();
            return;
        }
        os.put("GenVertex:  ").put(v.id()).put(" stat: ");
        os.width(3);
        os.put(v.status());
        os.put(" in: ").put(v.particlesIn().size());
        os.width(3);
        os.put(" out: ").put(v.particlesOut().size());
        final FourVector pos = v.position();
        os.put(" has_set_position: ");
        os.put(v.hasSetPosition() ? "true" : "false");
        os.put(" (X,cT): ").put(pos.x()).put(", ").put(pos.y()).put(", ").put(pos.z()).put(", ").put(pos.t());
        if (attributes) {
            for (String ss : v.attributeNames()) os.put(" ").put(ss).put("=").put(v.attributeAsString(ss));
        }
    }

    public static void line(COStream os, FourVector p) {
        os.put("FourVector: ");
        final COStream.Flags orig = os.flags();
        os.scientific();
        os.showpos(true);
        final int prec = os.precision();
        os.precision(2);
        os.put(" (P,E)=").put(p.x()).put(",").put(p.y()).put(",").put(p.z()).put(",").put(p.e());
        os.flags(orig);
        os.precision(prec);
    }

    public static void line(COStream os, GenParticle p, boolean attributes) {
        if (p == null) {
            os.put("GenParticle: Empty").endl();
            return;
        }
        os.put("GenParticle: ");
        os.width(3);
        os.put(p.id()).put(" PDGID: ");
        os.width(5);
        os.put(p.pid());
        final COStream.Flags orig = os.flags();
        os.scientific();
        os.showpos(true);
        final int prec = os.precision();
        os.precision(2);
        final FourVector momentum = p.momentum();
        os.put(" (P,E)=").put(momentum.px()).put(",").put(momentum.py()).put(",").put(momentum.pz()).put(",").put(momentum.e());
        os.flags(orig);
        os.precision(prec);
        final GenVertex prod = p.productionVertex();
        final GenVertex end = p.endVertex();
        final int prodVtxId = prod != null ? prod.id() : 0;
        final int endVtxId = end != null ? end.id() : 0;
        final List<String> names = p.attributeNames();
        os.put(" Stat: ").put(p.status()).put(" PV: ").put(prodVtxId).put(" EV: ").put(endVtxId)
            .put(" Attr: ").put(names.size());
        if (attributes) {
            for (String ss : names) os.put(" ").put(ss).put("=").put(p.attributeAsString(ss));
        }
    }

    public static void line(COStream os, GenCrossSection cs) {
        if (cs == null) {
            os.put(" GenCrossSection: Empty");
            return;
        }
        os.put(" GenCrossSection: ").put(cs.xsec(0)).put(" ").put(cs.xsecErr(0)).put(" ")
            .put(cs.getAcceptedEvents()).put(" ").put(cs.getAttemptedEvents());
    }

    public static void line(COStream os, GenHeavyIon hi) {
        if (hi == null) {
            os.put(" GenHeavyIon: Empty");
            return;
        }
        os.put(" GenHeavyIon: ").put(hi.nCollHard).put(" ").put(hi.nPartProj).put(" ").put(hi.nPartTarg).put(" ")
            .put(hi.nColl).put(" ").put(hi.spectatorNeutrons).put(" ").put(hi.spectatorProtons).put(" ")
            .put(hi.nNwoundedCollisions).put(" ").put(hi.nwoundedNCollisions).put(" ").put(hi.nwoundedNwoundedCollisions)
            .put(" ").put(hi.impactParameter).put(" ").put(hi.eventPlaneAngle).put(" ").put(hi.eccentricity)
            .put(" ").put(hi.sigmaInelNN);
    }

    public static void line(COStream os, GenPdfInfo pi) {
        if (pi == null) {
            os.put(" GenPdfInfo: Empty");
            return;
        }
        os.put(" GenPdfInfo: ").put(pi.partonId[0]).put(" ").put(pi.partonId[1]).put(" ").put(pi.x[0]).put(" ")
            .put(pi.x[1]).put(" ").put(pi.scale).put(" ").put(pi.xf[0]).put(" ").put(pi.xf[1]).put(" ")
            .put(pi.pdfId[0]).put(" ").put(pi.pdfId[1]);
    }

    /* ---- the same as text ----------------------------------------------------- */

    public static String line(GenEvent e, boolean attributes) {
        final COStream os = new COStream();
        line(os, e, attributes);
        return os.str();
    }

    public static String line(GenRunInfo r, boolean attributes) {
        final COStream os = new COStream();
        line(os, r, attributes);
        return os.str();
    }

    public static String line(GenRunInfo.ToolInfo t) {
        final COStream os = new COStream();
        line(os, t);
        return os.str();
    }

    public static String line(GenVertex v, boolean attributes) {
        final COStream os = new COStream();
        line(os, v, attributes);
        return os.str();
    }

    public static String line(GenParticle p, boolean attributes) {
        final COStream os = new COStream();
        line(os, p, attributes);
        return os.str();
    }

    public static String line(FourVector p) {
        final COStream os = new COStream();
        line(os, p);
        return os.str();
    }

    public static String line(GenCrossSection cs) {
        final COStream os = new COStream();
        line(os, cs);
        return os.str();
    }

    public static String line(GenHeavyIon hi) {
        final COStream os = new COStream();
        line(os, hi);
        return os.str();
    }

    public static String line(GenPdfInfo pi) {
        final COStream os = new COStream();
        line(os, pi);
        return os.str();
    }

    /* ---- to std::cout with a newline, as the C++ overloads without a stream ---- */

    public static void printLine(GenEvent e, boolean attributes) {
        StdStreams.cout().println(line(e, attributes));
    }

    public static void printLine(GenEvent e) {
        printLine(e, false);
    }

    public static void printLine(GenRunInfo r, boolean attributes) {
        StdStreams.cout().println(line(r, attributes));
    }

    public static void printLine(GenRunInfo.ToolInfo t) {
        StdStreams.cout().println(line(t));
    }

    public static void printLine(GenVertex v, boolean attributes) {
        StdStreams.cout().println(line(v, attributes));
    }

    public static void printLine(GenVertex v) {
        printLine(v, false);
    }

    public static void printLine(GenParticle p, boolean attributes) {
        StdStreams.cout().println(line(p, attributes));
    }

    public static void printLine(GenParticle p) {
        printLine(p, false);
    }

    public static void printLine(FourVector p) {
        StdStreams.cout().println(line(p));
    }

    public static void printLine(GenCrossSection cs) {
        StdStreams.cout().println(line(cs));
    }

    public static void printLine(GenHeavyIon hi) {
        StdStreams.cout().println(line(hi));
    }

    public static void printLine(GenPdfInfo pi) {
        StdStreams.cout().println(line(pi));
    }
}
