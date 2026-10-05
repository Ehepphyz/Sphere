package com.sphere.core.hepmc3;

/** The serialisable content of a vertex: status and position. */
public final class GenVertexData {

    public int status;
    public FourVector position = new FourVector();

    public GenVertexData() {
    }

    public GenVertexData(GenVertexData o) {
        status = o.status;
        position = o.position.copy();
    }

    /** No status and no position. */
    public boolean isZero() {
        if (status != 0) return false;
        return position.isZero();
    }
}
