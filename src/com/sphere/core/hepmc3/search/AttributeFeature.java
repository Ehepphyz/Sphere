package com.sphere.core.hepmc3.search;

import com.sphere.core.hepmc3.Attribute;
import com.sphere.core.hepmc3.GenParticle;

/** Filters on a particle attribute: present, or equal to a value in its string form. */
public final class AttributeFeature {

    private final String name;

    public AttributeFeature(String name) {
        this.name = name;
    }

    public Filter exists() {
        final String n = name;
        return p -> !p.attributeAsString(n).isEmpty();
    }

    public boolean test(GenParticle p) {
        return !p.attributeAsString(name).isEmpty();
    }

    public Filter eq(Attribute rhs) {
        final String n = name;
        final String other = rhs.serialize() == null ? "" : rhs.serialize();
        return p -> p.attributeAsString(n).equals(other);
    }

    public Filter eq(String rhs) {
        final String n = name;
        return p -> p.attributeAsString(n).equals(rhs);
    }
}
