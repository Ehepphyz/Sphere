package com.sphere.core.hepmc3;

/** The serialisable content of a particle: PDG id, status, generated mass, momentum. */
public final class GenParticleData {

    public int pid;
    public int status;
    public boolean isMassSet;
    public double mass;
    public FourVector momentum = new FourVector();

    public GenParticleData() {
    }

    public GenParticleData(GenParticleData o) {
        pid = o.pid;
        status = o.status;
        isMassSet = o.isMassSet;
        mass = o.mass;
        momentum = o.momentum.copy();
    }
}
