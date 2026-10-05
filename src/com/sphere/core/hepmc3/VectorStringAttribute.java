package com.sphere.core.hepmc3;

import java.util.ArrayList;
import java.util.List;

/**
 * A vector of strings, split at blanks to read (and appended to what it
 * already holds, as HepMC3 does), joined by blanks to write.
 */
public class VectorStringAttribute extends Attribute {

    private List<String> val = new ArrayList<>();

    public VectorStringAttribute() {
    }

    public VectorStringAttribute(List<String> val) {
        this.val = new ArrayList<>(val);
    }

    @Override
    public boolean fromString(String att) {
        int posb = firstNotOf(att, 0);
        // C++ would throw std::out_of_range on a string of blanks only; nothing to add then
        if (posb < 0) {
            setIsParsed(true);
            return true;
        }
        do {
            final int pose = att.indexOf(' ', posb);
            val.add(pose < 0 ? att.substring(posb) : att.substring(posb, pose));
            posb = pose < 0 ? -1 : firstNotOf(att, pose);
        } while (posb >= 0);
        setIsParsed(true);
        return true;
    }

    private static int firstNotOf(String s, int from) {
        for (int i = from; i < s.length(); i++) if (s.charAt(i) != ' ') return i;
        return -1;
    }

    @Override
    public String serialize() {
        final StringBuilder att = new StringBuilder();
        for (final String a : val) {
            if (att.length() > 0) att.append(' ');
            att.append(a);
        }
        return att.toString();
    }

    public List<String> value() {
        return new ArrayList<>(val);
    }

    public void setValue(List<String> i) {
        val = new ArrayList<>(i);
        setIsParsed(true);
    }
}
