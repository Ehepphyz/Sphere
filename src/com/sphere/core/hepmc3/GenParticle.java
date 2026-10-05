package com.sphere.core.hepmc3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A particle of the event record: PDG id, status, momentum, generated mass,
 * and the vertices it comes from and goes into. Its id is its position in
 * the event, from 1; it is 0 outside an event.
 */
public final class GenParticle {

    GenEvent event;
    int id;
    final GenParticleData data;
    GenVertex productionVertex;
    GenVertex endVertex;

    public GenParticle() {
        this(new FourVector(), 0, 0);
    }

    public GenParticle(FourVector momentum, int pid, int status) {
        data = new GenParticleData();
        data.pid = pid;
        data.momentum = momentum == null ? new FourVector() : momentum.copy();
        data.status = status;
        data.isMassSet = false;
        data.mass = 0.0;
    }

    /** From stored data (copied). */
    public GenParticle(GenParticleData d) {
        data = new GenParticleData(d);
    }

    public boolean inEvent() {
        return event != null;
    }

    public GenEvent parentEvent() {
        return event;
    }

    /** The particle's id in its event (not its PDG id), from 1. */
    public int id() {
        return id;
    }

    /** The stored data; changing it changes the particle. */
    public GenParticleData data() {
        return data;
    }

    public GenVertex productionVertex() {
        return productionVertex;
    }

    public GenVertex endVertex() {
        return endVertex;
    }

    /** The incoming particles of the production vertex (empty without one). */
    public List<GenParticle> parents() {
        return productionVertex == null ? Collections.emptyList() : new ArrayList<>(productionVertex.particlesIn());
    }

    /** The outgoing particles of the end vertex (empty without one). */
    public List<GenParticle> children() {
        return endVertex == null ? Collections.emptyList() : new ArrayList<>(endVertex.particlesOut());
    }

    public int pid() {
        return data.pid;
    }

    public int absPid() {
        return Math.abs(pid());
    }

    public int status() {
        return data.status;
    }

    /** The momentum held (not a copy). */
    public FourVector momentum() {
        return data.momentum;
    }

    public boolean isGeneratedMassSet() {
        return data.isMassSet;
    }

    /** The mass a generator set, else the mass of the momentum. */
    public double generatedMass() {
        return data.isMassSet ? data.mass : data.momentum.m();
    }

    public void setPid(int pid) {
        data.pid = pid;
    }

    public void setStatus(int status) {
        data.status = status;
    }

    public void setMomentum(FourVector momentum) {
        data.momentum = momentum.copy();
    }

    public void setGeneratedMass(double m) {
        data.mass = m;
        data.isMassSet = true;
    }

    public void unsetGeneratedMass() {
        data.mass = 0.;
        data.isMassSet = false;
    }

    /** Adds an attribute, kept by the event; false outside an event. */
    public boolean addAttribute(String name, Attribute att) {
        if (event == null) return false;
        event.addAttribute(name, att, id);
        return true;
    }

    public List<String> attributeNames() {
        if (event != null) return event.attributeNames(id);
        return new ArrayList<>();
    }

    public void removeAttribute(String name) {
        if (event != null) event.removeAttribute(name, id);
    }

    /** The attribute parsed as the given type, or null. */
    public <T extends Attribute> T attribute(String name, Class<T> type) {
        return event != null ? event.attribute(name, type, id) : null;
    }

    public String attributeAsString(String name) {
        return event != null ? event.attributeAsString(name, id) : "";
    }

    /** Deprecated: pid(). */
    public int pdgId() {
        return pid();
    }

    /** Deprecated: setPid(). */
    public void setPdgId(int pid) {
        setPid(pid);
    }

    /** Print::line of the particle. */
    @Override
    public String toString() {
        return Print.line(this, false);
    }
}
