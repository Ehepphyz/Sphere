package com.sphere.core.fjcontrib.jetswithoutjets;

import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.ContribCitations;

import java.util.List;

/**
 * An event shape that sums a jet measurement over the neighbourhoods of the
 * particles, fastjet::jwj::JetLikeEventShape: sum_i w_i F({p within R_jet of
 * i}) over the particles i whose neighbourhood passes the pt cut (and the
 * trimming condition), w_i = pt_i / pt_in_Rjet. With F = 1 it counts the
 * jets, with F = scalar pt it is H_T, and so on; the subclasses compute the
 * common ones from the storage directly, without the neighbours.
 */
public class JetLikeEventShape implements FunctionOfVectorOfPseudoJets<Double> {

    static {
        ContribCitations.use("jetswithoutjets");
    }

    protected final FunctionOfVectorOfPseudoJets<Double> measurement;
    protected final double rjet;
    protected final double ptcut;
    protected final double rsub;
    protected final double fcut;
    protected final boolean trim;
    protected boolean useLocalStorage = true;
    protected boolean storeNeighbors = true;
    protected boolean storeMass;

    public JetLikeEventShape(FunctionOfVectorOfPseudoJets<Double> measurement, double rjet, double ptcut) {
        this.measurement = measurement;
        this.rjet = rjet;
        this.ptcut = ptcut;
        this.rsub = rjet;
        this.fcut = 1.0;
        this.trim = false;
    }

    public JetLikeEventShape(FunctionOfVectorOfPseudoJets<Double> measurement, double rjet, double ptcut, double rsub, double fcut) {
        this.measurement = measurement;
        this.rjet = rjet;
        this.ptcut = ptcut;
        this.rsub = rsub;
        this.fcut = fcut;
        this.trim = true;
    }

    /** The shape on a storage built with this shape's parameters. */
    public double result(EventStorage storage) {
        if (!checkStorageParameters(storage) || !storage.storeNeighbors()) {
            throw new FastJetException("Storage cannot be used for this shape");
        }
        double m = 0.0;
        for (int i = 0; i < storage.size(); i++) {
            final EventStorage.ParticleStorage p = storage.get(i);
            if (p.includeParticle()) m += p.weight() * measurement.result(storage.particlesNearTo(i));
        }
        return m;
    }

    @Override
    public Double result(List<PseudoJet> particles) {
        return result(storage(particles));
    }

    /** The storage this shape builds for a set of particles. */
    public EventStorage storage(List<PseudoJet> particles) {
        final EventStorage s = new EventStorage(rjet, ptcut, rsub, fcut, useLocalStorage, storeNeighbors, storeMass);
        s.establishStorage(particles);
        return s;
    }

    public void setUseLocalStorage(boolean v) { useLocalStorage = v; }

    public String jetParameterString() {
        String s = "R_jet=" + Fmt.g(rjet) + ", pT_cut=" + Fmt.g(ptcut);
        if (trim) s += ", trimming with R_sub=" + Fmt.g(rsub) + ", fcut=" + Fmt.g(fcut);
        return s;
    }

    @Override
    public String description() {
        return "Summed " + measurement.description() + " as event shape, " + jetParameterString();
    }

    protected void setStoreNeighbors(boolean v) { storeNeighbors = v; }
    protected void setStoreMass(boolean v) { storeMass = v; }

    protected boolean checkStorageParameters(EventStorage s) {
        if (!trim) return s.Rjet() == rjet && s.ptcut() == ptcut;
        return s.Rjet() == rjet && s.ptcut() == ptcut && s.Rsub() == rsub && s.fcut() == fcut;
    }

    protected void requireConsistent(EventStorage s) {
        if (!checkStorageParameters(s)) throw new FastJetException("Storage parameters are not consistent with shape parameters");
    }
}
