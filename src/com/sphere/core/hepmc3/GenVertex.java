package com.sphere.core.hepmc3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A vertex: incoming and outgoing particles, a status and a position. Its id
 * is negative in an event (-1, -2, ...), 0 outside one.
 */
public final class GenVertex {

    GenEvent event;
    int id;
    final GenVertexData data;
    final List<GenParticle> particlesIn = new ArrayList<>(2);
    final List<GenParticle> particlesOut = new ArrayList<>(4);
    private final List<GenParticle> inView = Collections.unmodifiableList(particlesIn);
    private final List<GenParticle> outView = Collections.unmodifiableList(particlesOut);

    public GenVertex() {
        this(new FourVector());
    }

    public GenVertex(FourVector position) {
        data = new GenVertexData();
        data.status = 0;
        data.position = position == null ? new FourVector() : position.copy();
    }

    /** From stored data (copied). */
    public GenVertex(GenVertexData d) {
        data = new GenVertexData(d);
    }

    public GenEvent parentEvent() {
        return event;
    }

    public boolean inEvent() {
        return event != null;
    }

    /** The vertex id: negative in an event. (HepMC2's id is now the status.) */
    public int id() {
        return id;
    }

    public int status() {
        return data.status;
    }

    public void setStatus(int stat) {
        data.status = stat;
    }

    public GenVertexData data() {
        return data;
    }

    /** Adds an incoming particle, taking it from the vertex it entered before. */
    public void addParticleIn(GenParticle p) {
        if (p == null) return;
        if (particlesIn.contains(p)) return;
        particlesIn.add(p);
        if (p.endVertex != null) p.endVertex.removeParticleIn(p);
        p.endVertex = this;
        if (event != null) event.addParticle(p);
    }

    /** Adds an outgoing particle, taking it from the vertex it came from before. */
    public void addParticleOut(GenParticle p) {
        if (p == null) return;
        if (particlesOut.contains(p)) return;
        particlesOut.add(p);
        if (p.productionVertex != null) p.productionVertex.removeParticleOut(p);
        p.productionVertex = this;
        if (event != null) event.addParticle(p);
    }

    public void removeParticleIn(GenParticle p) {
        if (p == null) return;
        if (!particlesIn.contains(p)) return;
        p.endVertex = null;
        particlesIn.removeIf(x -> x == p);
    }

    public void removeParticleOut(GenParticle p) {
        if (p == null) return;
        if (!particlesOut.contains(p)) return;
        p.productionVertex = null;
        particlesOut.removeIf(x -> x == p);
    }

    public int particlesInSize() {
        return particlesIn.size();
    }

    public int particlesOutSize() {
        return particlesOut.size();
    }

    /** The incoming particles (a read-only view). */
    public List<GenParticle> particlesIn() {
        return inView;
    }

    /** The outgoing particles (a read-only view). */
    public List<GenParticle> particlesOut() {
        return outView;
    }

    /**
     * The position: this vertex's own when set; else, inside an event, the
     * position of the production vertex of an incoming particle (unless the
     * event says it has cycles), else the event's position; else zero.
     */
    public FourVector position() {
        if (hasSetPosition()) return data.position;
        if (event != null) {
            final IntAttribute cycles = event.attribute("cycles", IntAttribute.class);
            if (cycles == null || cycles.value() == 0) {
                for (GenParticle p : particlesIn) {
                    final GenVertex v = p.productionVertex();
                    if (v != null) return v.position();
                }
            }
            return event.eventPos();
        }
        return FourVector.zero();
    }

    public boolean hasSetPosition() {
        return !data.position.isZero();
    }

    public void setPosition(FourVector pos) {
        data.position = pos.copy();
    }

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

    public <T extends Attribute> T attribute(String name, Class<T> type) {
        return event != null ? event.attribute(name, type, id) : null;
    }

    public String attributeAsString(String name) {
        return event != null ? event.attributeAsString(name, id) : "";
    }

    @Override
    public String toString() {
        return Print.line(this, false);
    }
}
