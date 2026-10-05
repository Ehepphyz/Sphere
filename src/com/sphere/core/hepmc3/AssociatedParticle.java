package com.sphere.core.hepmc3;

/**
 * A particle of the same event attached to another object, stored as its
 * id: a link that survives writing and reading back.
 */
public final class AssociatedParticle extends IntAttribute {

    private GenParticle associated;

    public AssociatedParticle() {
    }

    public AssociatedParticle(GenParticle p) {
        super(p.id());
        this.associated = p;
    }

    @Override
    public boolean fromString(String att) {
        super.fromString(att);
        // C++ dereferences the event unconditionally; without one there is nothing to point to
        if (event() == null) return false;
        if (associatedId() > event().particles().size() || associatedId() <= 0) return false;
        associated = event().particles().get(associatedId() - 1);
        return true;
    }

    public int associatedId() {
        return value();
    }

    public GenParticle associated() {
        return associated;
    }

    public void setAssociated(GenParticle p) {
        super.setValue(p.id());
        associated = p;
    }
}
