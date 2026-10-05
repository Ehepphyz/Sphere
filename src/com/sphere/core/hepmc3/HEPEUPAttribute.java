package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.COStream;

import java.util.ArrayList;
import java.util.List;

/**
 * An LHEF event (HEPEUP) as an event attribute, with the XML tags around it.
 * Parsed from its string, it is rebuilt against the HEPRUP attribute of the
 * run when initialised.
 */
public final class HEPEUPAttribute extends Attribute {

    public LHEF.HEPEUP hepeup = new LHEF.HEPEUP();
    public List<LHEF.XMLTag> tags = new ArrayList<>();

    public HEPEUPAttribute() {
    }

    public HEPEUPAttribute(String s) {
        super(s);
    }

    @Override
    public boolean fromString(String att) {
        clear();
        tags = LHEF.XMLTag.findXMLTags(att);
        for (LHEF.XMLTag t : tags) {
            if (t.name.equals("event") || t.name.equals("eventgroup")) return true;
        }
        return false;
    }

    @Override
    public boolean init() {
        final HEPRUPAttribute hepr = event() == null ? null : event().attribute("HEPRUP", HEPRUPAttribute.class);
        boolean found = false;
        for (LHEF.XMLTag t : tags) {
            if (t.name.equals("event") || t.name.equals("eventgroup")) {
                if (hepr == null) return false;
                hepeup = new LHEF.HEPEUP(t, hepr.heprup);
                found = true;
            }
        }
        return found;
    }

    @Override
    public boolean init(GenRunInfo run) {
        return true;
    }

    @Override
    public String serialize() {
        final COStream os = new COStream();
        if (hepeup.heprup != null) hepeup.print(os);
        for (LHEF.XMLTag t : tags) {
            if (hepeup.heprup == null || (!t.name.equals("event") && !t.name.equals("eventgroup"))) t.print(os);
        }
        return os.str();
    }

    /** The momentum of particle i. */
    public FourVector momentum(int i) {
        final double[] p = hepeup.PUP.get(i);
        return new FourVector(p[0], p[1], p[2], p[3]);
    }

    public void clear() {
        tags = new ArrayList<>();
        hepeup.clear();
    }
}
