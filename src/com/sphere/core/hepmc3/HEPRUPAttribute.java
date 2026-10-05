package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.COStream;

import java.util.ArrayList;
import java.util.List;

/**
 * The LHEF init block (HEPRUP) as a run attribute, with whatever other XML
 * tags the file carried (header, comments): written back as the init block
 * followed by those tags.
 */
public final class HEPRUPAttribute extends Attribute {

    public LHEF.HEPRUP heprup = new LHEF.HEPRUP();
    public List<LHEF.XMLTag> tags = new ArrayList<>();

    public HEPRUPAttribute() {
    }

    public HEPRUPAttribute(String s) {
        super(s);
    }

    @Override
    public boolean fromString(String att) {
        boolean found = false;
        clear();
        tags = LHEF.XMLTag.findXMLTags(att);
        for (LHEF.XMLTag t : tags) {
            if (t.name.equals("init")) {
                heprup = new LHEF.HEPRUP(t, 3);
                found = true;
            }
        }
        return found;
    }

    @Override
    public String serialize() {
        final COStream os = new COStream();
        if (heprup.NPRUP != 0) heprup.print(os);
        for (LHEF.XMLTag t : tags) {
            if (heprup.NPRUP == 0 || !t.name.equals("init")) t.print(os);
        }
        return os.str();
    }

    public void clear() {
        tags = new ArrayList<>();
        heprup.clear();
    }
}
