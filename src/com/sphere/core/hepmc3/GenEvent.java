package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.LongDouble;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An event: its particles and vertices, weights, units, position, run
 * information and attributes. Translated from HepMC3's GenEvent, with the
 * same ids (particles 1, 2, ...; vertices -1, -2, ...), the same order of
 * everything and the same rules for adding, removing and sorting.
 *
 * <p>Particles without a production vertex hang from a root vertex that the
 * event keeps outside its vertex list (id 0, at the event position): those
 * are the beams.
 */
public final class GenEvent {

    private final List<GenParticle> particles = new ArrayList<>();
    private final List<GenVertex> vertices = new ArrayList<>();
    private final List<GenParticle> particlesView = Collections.unmodifiableList(particles);
    private final List<GenVertex> verticesView = Collections.unmodifiableList(vertices);
    private int eventNumber;
    private List<Double> weights = new ArrayList<>();
    private Units.MomentumUnit momentumUnit = Units.MomentumUnit.GEV;
    private Units.LengthUnit lengthUnit = Units.LengthUnit.MM;
    private GenVertex rootVertex = new GenVertex();
    private GenRunInfo runInfo;
    /** Name, then owner id (0 event, < 0 vertex, > 0 particle), then the attribute. */
    private final TreeMap<String, TreeMap<Integer, Attribute>> attributes = new TreeMap<>();
    private final Object lock = new Object();

    public GenEvent() {
        this(Units.MomentumUnit.GEV, Units.LengthUnit.MM);
    }

    public GenEvent(Units.MomentumUnit mu, Units.LengthUnit lu) {
        this.momentumUnit = mu;
        this.lengthUnit = lu;
    }

    public GenEvent(GenRunInfo run) {
        this(run, Units.MomentumUnit.GEV, Units.LengthUnit.MM);
    }

    public GenEvent(GenRunInfo run, Units.MomentumUnit mu, Units.LengthUnit lu) {
        this(mu, lu);
        this.runInfo = run;
        if (run != null && !run.weightNames().isEmpty()) {
            weights = new ArrayList<>(Collections.nCopies(run.weightNames().size(), 1.0));
        }
    }

    /** A deep copy, through the stored form as the C++ copy constructor does; the run information is shared. */
    public GenEvent(GenEvent e) {
        if (e != null) {
            final GenEventData tdata = new GenEventData();
            e.writeData(tdata);
            readData(tdata);
            runInfo = e.runInfo;
        }
    }

    /** operator=: this event becomes a copy of another. */
    public GenEvent assign(GenEvent e) {
        if (e != this && e != null) {
            final GenEventData tdata = new GenEventData();
            e.writeData(tdata);
            readData(tdata);
            runInfo = e.runInfo;
        }
        return this;
    }

    public GenEvent copy() {
        return new GenEvent(this);
    }

    /* ---- particles and vertices ---------------------------------------- */

    /** The particles, in id order (a read-only view). */
    public List<GenParticle> particles() {
        return particlesView;
    }

    /** The vertices, in order of -id (a read-only view). */
    public List<GenVertex> vertices() {
        return verticesView;
    }

    public int particlesSize() {
        return particles.size();
    }

    public boolean particlesEmpty() {
        return particles.isEmpty();
    }

    public int verticesSize() {
        return vertices.size();
    }

    public boolean verticesEmpty() {
        return vertices.isEmpty();
    }

    /* ---- weights ----------------------------------------------------------- */

    /** The weights (the list itself, to change). */
    public List<Double> weights() {
        return weights;
    }

    /** The first weight. */
    public double weight() {
        return weight(0);
    }

    public double weight(int index) {
        if (index >= 0 && index < weights.size()) return weights.get(index);
        throw new IndexOutOfBoundsException("GenEvent::weight(const unsigned long&): weight index outside of range");
    }

    /**
     * A weight by its name, which needs a GenRunInfo naming them. The checks
     * and messages are those of the non-const C++ overload, the one C++ code
     * reaches on an event it can change.
     */
    public double weight(String name) {
        if (runInfo == null) {
            throw new IllegalStateException("GenEvent::weight(const std::string&): named access to event weights requires the event to have a GenRunInfo");
        }
        final int pos = runInfo.weightIndex(name);
        if (pos < 0) throw new IllegalArgumentException("GenEvent::weight(const std::string&): no weight with given name in this run");
        if (pos >= weights.size()) throw new IndexOutOfBoundsException("GenEvent::weight(const std::string&): weight index outside of range");
        return weights.get(pos);
    }

    /** Sets a weight by its name (C++: double& weight(name) = w). */
    public void setWeight(String name, double w) {
        if (runInfo == null) {
            throw new IllegalStateException("GenEvent::weight(const std::string&): named access to event weights requires the event to have a GenRunInfo");
        }
        final int pos = runInfo.weightIndex(name);
        if (pos < 0) throw new IllegalArgumentException("GenEvent::weight(const std::string&): no weight with given name in this run");
        if (pos >= weights.size()) throw new IndexOutOfBoundsException("GenEvent::weight(const std::string&): weight index outside of range");
        weights.set(pos, w);
    }

    public List<String> weightNames() {
        if (runInfo == null) {
            throw new IllegalStateException("GenEvent::weight_names(): access to event weight names requires the event to have a GenRunInfo");
        }
        final List<String> names = runInfo.weightNames();
        if (names.isEmpty()) {
            throw new IllegalStateException("GenEvent::weight_names(): no event weight names are registered for this run");
        }
        return names;
    }

    /* ---- metadata ------------------------------------------------------------ */

    public GenRunInfo runInfo() {
        return runInfo;
    }

    /** Sets the run; the weights grow to as many as it names, filled with 1. */
    public void setRunInfo(GenRunInfo run) {
        runInfo = run;
        if (run != null && !run.weightNames().isEmpty()) {
            final int n = run.weightNames().size();
            while (weights.size() < n) weights.add(1.0);
            while (weights.size() > n) weights.remove(weights.size() - 1);
        }
    }

    public int eventNumber() {
        return eventNumber;
    }

    public void setEventNumber(int num) {
        eventNumber = num;
    }

    public Units.MomentumUnit momentumUnit() {
        return momentumUnit;
    }

    public Units.LengthUnit lengthUnit() {
        return lengthUnit;
    }

    /** Converts the event to other units. */
    public void setUnits(Units.MomentumUnit newMomentumUnit, Units.LengthUnit newLengthUnit) {
        if (newMomentumUnit != momentumUnit) {
            for (GenParticle p : particles) {
                Units.convert(p.data.momentum, momentumUnit, newMomentumUnit);
                p.data.mass = Units.convert(p.data.mass, momentumUnit, newMomentumUnit);
            }
            momentumUnit = newMomentumUnit;
        }
        if (newLengthUnit != lengthUnit) {
            for (GenVertex v : vertices) {
                final FourVector fv = v.data.position;
                if (!fv.isZero()) Units.convert(fv, lengthUnit, newLengthUnit);
            }
            lengthUnit = newLengthUnit;
        }
    }

    public GenHeavyIon heavyIon() {
        return attribute("GenHeavyIon", GenHeavyIon.class);
    }

    public void setHeavyIon(GenHeavyIon hi) {
        addAttribute("GenHeavyIon", hi);
    }

    public GenPdfInfo pdfInfo() {
        return attribute("GenPdfInfo", GenPdfInfo.class);
    }

    public void setPdfInfo(GenPdfInfo pi) {
        addAttribute("GenPdfInfo", pi);
    }

    public GenCrossSection crossSection() {
        return attribute("GenCrossSection", GenCrossSection.class);
    }

    public void setCrossSection(GenCrossSection cs) {
        addAttribute("GenCrossSection", cs);
    }

    /* ---- position, beams, Lorentz transformations --------------------------- */

    /** The position of the whole event: that of the root vertex. */
    public FourVector eventPos() {
        return rootVertex.data().position;
    }

    /** The particles of the root vertex. */
    public List<GenParticle> beams() {
        return rootVertex.particlesOut();
    }

    /** The particles of the root vertex with this status (all of them for 0). */
    public List<GenParticle> beams(int status) {
        if (status == 0) return rootVertex.particlesOut();
        final List<GenParticle> ret = new ArrayList<>();
        for (GenParticle p : rootVertex.particlesOut()) if (p.status() == status) ret.add(p);
        return ret;
    }

    /** Shifts the event and every vertex with a position of its own. */
    public void shiftPositionBy(FourVector delta) {
        rootVertex.setPosition(eventPos().plus(delta));
        for (GenVertex v : vertices) {
            if (v.hasSetPosition()) v.setPosition(v.position().plus(delta));
        }
    }

    public void shiftPositionTo(FourVector newpos) {
        final FourVector delta = newpos.minus(eventPos());
        shiftPositionBy(delta);
    }

    /**
     * Rotates momenta and positions by the angles x, y, z of delta (about x,
     * then y, then z), in long double as HepMC3 does on x86-64.
     */
    public boolean rotate(FourVector delta) {
        final LongDouble cosa = LongDouble.of(Math.cos(delta.x()));
        final LongDouble sina = LongDouble.of(Math.sin(delta.x()));
        final LongDouble cosb = LongDouble.of(Math.cos(delta.y()));
        final LongDouble sinb = LongDouble.of(Math.sin(delta.y()));
        final LongDouble cosg = LongDouble.of(Math.cos(delta.z()));
        final LongDouble sing = LongDouble.of(Math.sin(delta.z()));
        for (GenParticle p : particles) {
            final FourVector mom = p.momentum();
            final double[] r = rotated(mom.x(), mom.y(), mom.z(), cosa, sina, cosb, sinb, cosg, sing);
            p.setMomentum(new FourVector(r[0], r[1], r[2], mom.e()));
        }
        for (GenVertex v : vertices) {
            final FourVector pos = v.position();
            if (pos.isZero()) continue;
            final double t = pos.t();
            final double[] r = rotated(pos.x(), pos.y(), pos.z(), cosa, sina, cosb, sinb, cosg, sing);
            v.setPosition(new FourVector(r[0], r[1], r[2], t));
        }
        return true;
    }

    private static double[] rotated(double x, double y, double z, LongDouble cosa, LongDouble sina, LongDouble cosb,
                                    LongDouble sinb, LongDouble cosg, LongDouble sing) {
        LongDouble tempX = LongDouble.of(x);
        LongDouble tempY = LongDouble.of(y);
        LongDouble tempZ = LongDouble.of(z);
        LongDouble tempY_ = cosa.multiply(tempY).add(sina.multiply(tempZ));
        LongDouble tempZ_ = sina.negate().multiply(tempY).add(cosa.multiply(tempZ));
        tempY = tempY_;
        tempZ = tempZ_;
        LongDouble tempX_ = cosb.multiply(tempX).subtract(sinb.multiply(tempZ));
        tempZ_ = sinb.multiply(tempX).add(cosb.multiply(tempZ));
        tempX = tempX_;
        tempZ = tempZ_;
        tempX_ = cosg.multiply(tempX).add(sing.multiply(tempY));
        tempY_ = sing.negate().multiply(tempX).add(cosg.multiply(tempY));
        tempX = tempX_;
        tempY = tempY_;
        return new double[]{tempX.toDouble(), tempY.toDouble(), tempZ.toDouble()};
    }

    /** Changes the sign of one axis (0 x, 1 y, 2 z, 3 t) of every momentum and position. */
    public boolean reflect(int axis) {
        if (axis > 3 || axis < 0) {
            Setup.warning(400, "GenEvent::reflect: wrong axis");
            return false;
        }
        for (GenParticle p : particles) {
            final FourVector temp = p.momentum().copy();
            switch (axis) {
                case 0 -> temp.setX(-p.momentum().x());
                case 1 -> temp.setY(-p.momentum().y());
                case 2 -> temp.setZ(-p.momentum().z());
                default -> temp.setT(-p.momentum().e());
            }
            p.setMomentum(temp);
        }
        for (GenVertex v : vertices) {
            final FourVector temp = v.position().copy();
            switch (axis) {
                case 0 -> temp.setX(-v.position().x());
                case 1 -> temp.setY(-v.position().y());
                case 2 -> temp.setZ(-v.position().z());
                default -> temp.setT(-v.position().t());
            }
            v.setPosition(temp);
        }
        return true;
    }

    /**
     * Boosts every momentum by the velocity (x, y, z) of delta, in units of c,
     * in long double as HepMC3 does on x86-64. False (event unchanged) for
     * |beta| >= 1.
     */
    public boolean boost(FourVector delta) {
        final double deltalength2 = delta.length2();
        if (deltalength2 > 1.0) {
            Setup.warning(400, "GenEvent::boost: wrong large boost vector. Will leave event as is.");
            return false;
        }
        if (Math.abs(deltalength2 - 1.0) < Math.ulp(1.0)) {
            Setup.warning(400, "GenEvent::boost: too large gamma. Will leave event as is.");
            return false;
        }
        if (Math.abs(deltalength2) < Math.ulp(1.0)) {
            Setup.warning(400, "GenEvent::boost: wrong small boost vector. Will leave event as is.");
            return true;
        }
        final LongDouble deltaX = LongDouble.of(delta.x());
        final LongDouble deltaY = LongDouble.of(delta.y());
        final LongDouble deltaZ = LongDouble.of(delta.z());
        final LongDouble deltalength = LongDouble.of(Math.sqrt(deltalength2));
        final LongDouble gamma = LongDouble.of(1.0 / Math.sqrt(1.0 - deltalength2));
        final LongDouble gammaMinusOne = gamma.subtract(1.0);
        for (GenParticle p : particles) {
            final FourVector mom = p.momentum();
            LongDouble tempX = LongDouble.of(mom.x());
            LongDouble tempY = LongDouble.of(mom.y());
            LongDouble tempZ = LongDouble.of(mom.z());
            LongDouble tempE = LongDouble.of(mom.e());
            final LongDouble nr = deltaX.multiply(tempX).add(deltaY.multiply(tempY)).add(deltaZ.multiply(tempZ))
                .divide(deltalength);
            final LongDouble gfac = gammaMinusOne.multiply(nr).divide(deltalength).subtract(tempE.multiply(gamma));
            tempX = tempX.add(deltaX.multiply(gfac));
            tempY = tempY.add(deltaY.multiply(gfac));
            tempZ = tempZ.add(deltaZ.multiply(gfac));
            tempE = gamma.multiply(tempE.subtract(deltalength.multiply(nr)));
            p.setMomentum(new FourVector(tempX.toDouble(), tempY.toDouble(), tempZ.toDouble(), tempE.toDouble()));
        }
        return true;
    }

    /* ---- attributes ------------------------------------------------------ */

    /** Adds an attribute of the event (id 0), a vertex (id < 0) or a particle (id > 0), replacing one there. */
    public void addAttribute(String name, Attribute att, int id) {
        if (name == null || name.isEmpty()) return;
        if (att == null) return;
        synchronized (lock) {
            attributes.computeIfAbsent(name, k -> new TreeMap<>()).put(id, att);
            att.event = this;
            if (id > 0 && id <= particles.size()) att.particle = particles.get(id - 1);
            if (id < 0 && -id <= vertices.size()) att.vertex = vertices.get(-id - 1);
        }
    }

    public void addAttribute(String name, Attribute att) {
        addAttribute(name, att, 0);
    }

    /** Several attributes at once: names, attributes and ids of the same length. */
    public void addAttributes(List<String> names, List<? extends Attribute> atts, List<Integer> ids) {
        final int n = names.size();
        if (n == 0) return;
        if (n != atts.size()) return;
        if (n != ids.size()) return;
        synchronized (lock) {
            // std::unique leaves consecutive duplicates once; creating the maps is all it is used for,
            // and C++ creates one for an empty name too (it then stays empty)
            for (String name : names) attributes.computeIfAbsent(name, k -> new TreeMap<>());
            final int particlesSize = particles.size();
            final int verticesSize = vertices.size();
            for (int i = 0; i < n; i++) {
                if (names.get(i).isEmpty()) continue;
                final Attribute a = atts.get(i);
                if (a == null) continue;
                final int id = ids.get(i);
                attributes.get(names.get(i)).put(id, a);
                a.event = this;
                if (id > 0 && id <= particlesSize) a.particle = particles.get(id - 1);
                else if (id < 0 && -id <= verticesSize) a.vertex = vertices.get(-id - 1);
            }
        }
    }

    /** Several attributes of one name, for the owners given. */
    public void addAttributes(String name, List<? extends Attribute> atts, List<Integer> ids) {
        if (name == null || name.isEmpty()) return;
        final int n = ids.size();
        if (n == 0) return;
        if (n != atts.size()) return;
        synchronized (lock) {
            final TreeMap<Integer, Attribute> tmap = attributes.computeIfAbsent(name, k -> new TreeMap<>());
            final int particlesSize = particles.size();
            final int verticesSize = vertices.size();
            for (int i = 0; i < n; i++) {
                final Attribute a = atts.get(i);
                if (a == null) continue;
                final int id = ids.get(i);
                tmap.put(id, a);
                a.event = this;
                if (id > 0 && id <= particlesSize) a.particle = particles.get(id - 1);
                else if (id < 0 && -id <= verticesSize) a.vertex = vertices.get(-id - 1);
            }
        }
    }

    /** Several attributes of one name as (owner id, attribute) pairs; an owner already set keeps its attribute (map insert). */
    public void addAttributes(String name, List<Map.Entry<Integer, ? extends Attribute>> atts) {
        if (name == null || name.isEmpty()) return;
        if (atts.isEmpty()) return;
        synchronized (lock) {
            final TreeMap<Integer, Attribute> tmap = attributes.computeIfAbsent(name, k -> new TreeMap<>());
            final int particlesSize = particles.size();
            final int verticesSize = vertices.size();
            for (Map.Entry<Integer, ? extends Attribute> att : atts) {
                final Attribute a = att.getValue();
                if (a == null) continue;
                tmap.putIfAbsent(att.getKey(), a);
                a.event = this;
                final int id = att.getKey();
                if (id > 0 && id <= particlesSize) a.particle = particles.get(id - 1);
                else if (id < 0 && -id <= verticesSize) a.vertex = vertices.get(-id - 1);
            }
        }
    }

    public void removeAttribute(String name, int id) {
        synchronized (lock) {
            final TreeMap<Integer, Attribute> m = attributes.get(name);
            if (m == null) return;
            m.remove(id);
        }
    }

    public void removeAttribute(String name) {
        removeAttribute(name, 0);
    }

    /** The event's attribute as the type asked for, or null. */
    public <T extends Attribute> T attribute(String name, Class<T> type) {
        return attribute(name, type, 0);
    }

    /**
     * An attribute as the type asked for, or null. Stored unparsed, it is
     * parsed now as that type and kept parsed; an event attribute absent
     * from the event is looked for in the run.
     */
    public <T extends Attribute> T attribute(String name, Class<T> type, int id) {
        synchronized (lock) {
            final TreeMap<Integer, Attribute> m = attributes.get(name);
            if (m == null) {
                if (id == 0 && runInfo != null) return runInfo.attribute(name, type);
                return null;
            }
            final Attribute a = m.get(id);
            if (a == null) return null;
            if (!a.isParsed()) {
                final T att = Attribute.make(type);
                att.event = this;
                if (id > 0 && id <= particles.size()) att.particle = particles.get(id - 1);
                if (id < 0 && -id <= vertices.size()) att.vertex = vertices.get(-id - 1);
                if (att.fromString(a.unparsedString()) && att.init()) {
                    m.put(id, att);
                    return att;
                }
                return null;
            }
            return type.isInstance(a) ? type.cast(a) : null;
        }
    }

    /** An attribute in its string form, "" when absent (event attributes fall back on the run). */
    public String attributeAsString(String name, int id) {
        synchronized (lock) {
            final TreeMap<Integer, Attribute> m = attributes.get(name);
            if (m == null) {
                if (id == 0 && runInfo != null) return runInfo.attributeAsString(name);
                return "";
            }
            final Attribute a = m.get(id);
            if (a == null) return "";
            final String s = a.serialize();
            return s == null ? "" : s;
        }
    }

    public String attributeAsString(String name) {
        return attributeAsString(name, 0);
    }

    /** The names of the attributes an owner has, in name order. */
    public List<String> attributeNames(int id) {
        final List<String> results = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<String, TreeMap<Integer, Attribute>> vt1 : attributes.entrySet()) {
                if (vt1.getValue().containsKey(id)) results.add(vt1.getKey());
            }
        }
        return results;
    }

    public List<String> attributeNames() {
        return attributeNames(0);
    }

    /** A copy of the attribute maps (the attributes themselves shared). */
    public TreeMap<String, TreeMap<Integer, Attribute>> attributes() {
        synchronized (lock) {
            final TreeMap<String, TreeMap<Integer, Attribute>> copy = new TreeMap<>();
            for (Map.Entry<String, TreeMap<Integer, Attribute>> e : attributes.entrySet()) {
                copy.put(e.getKey(), new TreeMap<>(e.getValue()));
            }
            return copy;
        }
    }

    /* ---- building and changing the graph -------------------------------- */

    /** Adds a particle; one without a production vertex goes to the root vertex. */
    public void addParticle(GenParticle p) {
        if (p == null || p.inEvent()) return;
        particles.add(p);
        p.event = this;
        p.id = particles.size();
        if (p.productionVertex() == null) rootVertex.addParticleOut(p);
    }

    /** Adds a vertex and the particles attached to it. */
    public void addVertex(GenVertex v) {
        if (v == null || v.inEvent()) return;
        vertices.add(v);
        v.event = this;
        v.id = -vertices.size();
        for (GenParticle p : new ArrayList<>(v.particlesIn)) {
            if (!p.inEvent()) addParticle(p);
            p.endVertex = v;
        }
        for (GenParticle p : new ArrayList<>(v.particlesOut)) {
            if (!p.inEvent()) addParticle(p);
            p.productionVertex = v;
        }
    }

    /**
     * Removes a particle, and its end vertex when it was the only particle
     * entering it (with the tree below), and its production vertex when it
     * was the only particle leaving it. Ids above it shift down.
     */
    public void removeParticle(GenParticle p) {
        if (p == null || p.parentEvent() != this) return;
        if (Setup.debugging(30)) Setup.debug(30, "GenEvent::remove_particle - called with particle: " + p.id());
        final GenVertex endVtx = p.endVertex();
        if (endVtx != null) {
            endVtx.removeParticleIn(p);
            if (endVtx.particlesIn().isEmpty()) removeVertex(endVtx);
        }
        final GenVertex prodVtx = p.productionVertex();
        if (prodVtx != null) {
            prodVtx.removeParticleOut(p);
            if (prodVtx.particlesOut().isEmpty()) removeVertex(prodVtx);
        }
        if (Setup.debugging(30)) Setup.debug(30, "GenEvent::remove_particle - erasing particle: " + p.id());
        final int idx = p.id();
        particles.remove(idx - 1);
        synchronized (lock) {
            for (TreeMap<Integer, Attribute> vt1 : attributes.values()) vt1.remove(idx);
            for (TreeMap<Integer, Attribute> vt1 : attributes.values()) {
                final List<Map.Entry<Integer, Attribute>> changed = new ArrayList<>();
                for (Map.Entry<Integer, Attribute> vt2 : vt1.entrySet()) {
                    if (vt2.getKey() > p.id()) changed.add(Map.entry(vt2.getKey(), vt2.getValue()));
                }
                for (Map.Entry<Integer, Attribute> val : changed) {
                    vt1.remove(val.getKey());
                    vt1.put(val.getKey() - 1, val.getValue());
                }
            }
        }
        for (int k = idx - 1; k < particles.size(); k++) particles.get(k).id--;
        p.event = null;
        p.id = 0;
    }

    /** Removes particles as removeParticle does, the highest id first. */
    public void removeParticles(List<GenParticle> v) {
        final List<GenParticle> sorted = new ArrayList<>(v);
        com.sphere.core.hepmc3.cxx.StdSort.sort(sorted, (p1, p2) -> p1.id() > p2.id());
        for (GenParticle p : sorted) removeParticle(p);
    }

    /** Removes a vertex and the trees of all its outgoing particles. Ids below it shift up. */
    public void removeVertex(GenVertex v) {
        if (v == null || v.parentEvent() != this) return;
        if (Setup.debugging(30)) Setup.debug(30, "GenEvent::remove_vertex   - called with vertex:  " + v.id());
        for (GenParticle p : new ArrayList<>(v.particlesIn)) p.endVertex = null;
        for (GenParticle p : new ArrayList<>(v.particlesOut)) {
            p.productionVertex = null;
            removeParticle(p);
        }
        if (Setup.debugging(30)) Setup.debug(30, "GenEvent::remove_vertex   - erasing vertex: " + v.id());
        final int idx = -v.id();
        vertices.remove(idx - 1);
        synchronized (lock) {
            for (TreeMap<Integer, Attribute> vt1 : attributes.values()) vt1.remove(-idx);
            for (TreeMap<Integer, Attribute> vt1 : attributes.values()) {
                final List<Map.Entry<Integer, Attribute>> changed = new ArrayList<>();
                for (Map.Entry<Integer, Attribute> vt2 : vt1.entrySet()) {
                    if (vt2.getKey() < v.id()) changed.add(Map.entry(vt2.getKey(), vt2.getValue()));
                }
                // most negative last after reverse, then sorted by key descending: closest to zero first
                changed.sort((a, b) -> Integer.compare(b.getKey(), a.getKey()));
                for (Map.Entry<Integer, Attribute> val : changed) {
                    vt1.remove(val.getKey());
                    vt1.put(val.getKey() + 1, val.getValue());
                }
            }
        }
        for (int k = idx - 1; k < vertices.size(); k++) vertices.get(k).id++;
        v.event = null;
        v.id = 0;
    }

    /** Whether a vertex can be reached twice going down from v (HepMC3 calls that cycles). */
    private static boolean visitChildren(IdentityHashMap<GenVertex, Integer> a, GenVertex v) {
        for (GenParticle p : v.particlesOut()) {
            final GenVertex end = p.endVertex();
            if (end != null) {
                final int seen = a.getOrDefault(end, 0);
                if (seen != 0) return true;
                a.put(end, seen + 1);
                if (visitChildren(a, end)) return true;
            }
        }
        return false;
    }

    /**
     * Adds a whole tree in topological order: from the vertices that the
     * particles with no production vertex (or one with nothing entering it)
     * go into, mothers before daughters.
     */
    public void addTree(List<GenParticle> parts) {
        final IntAttribute existingHc = attribute("cycles", IntAttribute.class);
        boolean hasCycles = false;
        final IdentityHashMap<GenVertex, Integer> sortingv = new IdentityHashMap<>();
        final List<GenVertex> noinv = new ArrayList<>();
        if (existingHc != null && existingHc.value() != 0) hasCycles = true;
        if (existingHc == null) {
            for (GenParticle p : parts) {
                final GenVertex v = p.productionVertex();
                if (v != null) sortingv.put(v, 0);
                if (v == null || v.particlesIn().isEmpty()) {
                    final GenVertex v2 = p.endVertex();
                    if (v2 != null) {
                        noinv.add(v2);
                        sortingv.put(v2, 0);
                    }
                }
            }
            for (GenVertex v : noinv) {
                if (hasCycles) break;
                final IdentityHashMap<GenVertex, Integer> sortingTemp = new IdentityHashMap<>(sortingv);
                hasCycles = visitChildren(sortingTemp, v);
            }
        }
        if (hasCycles) addAttribute("cycles", new IntAttribute(1));

        final ArrayDeque<GenVertex> sorting = new ArrayDeque<>();
        final IdentityHashMap<GenVertex, Integer> queued = new IdentityHashMap<>();
        for (GenParticle p : parts) {
            final GenVertex v = p.productionVertex();
            if (v == null || v.particlesIn().isEmpty()) {
                final GenVertex v2 = p.endVertex();
                if (v2 != null) {
                    sorting.addLast(v2);
                    queued.merge(v2, 1, Integer::sum);
                }
            }
        }
        int sortingLoopCount = 0;
        int maxDequeSize = 0;
        while (!sorting.isEmpty()) {
            if (sorting.size() > maxDequeSize) maxDequeSize = sorting.size();
            ++sortingLoopCount;
            final GenVertex v = sorting.peekFirst();
            boolean added = false;
            for (GenParticle p : v.particlesIn()) {
                final GenVertex v2 = p.productionVertex();
                if (v2 != null && !v2.inEvent() && !queued.containsKey(v2)) {
                    sorting.addFirst(v2);
                    queued.merge(v2, 1, Integer::sum);
                    added = true;
                }
            }
            if (added) continue;
            if (!v.inEvent()) {
                addVertex(v);
                for (GenParticle p : v.particlesOut()) {
                    final GenVertex v2 = p.endVertex();
                    if (v2 != null && !v2.inEvent() && !queued.containsKey(v2)) {
                        sorting.addLast(v2);
                        queued.merge(v2, 1, Integer::sum);
                    }
                }
            }
            // the front is still v: what was put in front has been taken off first
            final GenVertex removed = sorting.pollFirst();
            final int left = queued.getOrDefault(removed, 1) - 1;
            if (left <= 0) queued.remove(removed);
            else queued.put(removed, left);
        }
        // LL: the root vertex keeps index zero and is never written out
        if (rootVertex.id() != 0) {
            final int vx = -1 - rootVertex.id();
            final int rootid = rootVertex.id();
            if (vx >= 0 && vx < vertices.size() && vertices.get(vx) == rootVertex) {
                vertices.remove(vx);
                synchronized (lock) {
                    for (TreeMap<Integer, Attribute> vt1 : attributes.values()) {
                        final List<Map.Entry<Integer, Attribute>> changed = new ArrayList<>();
                        for (Map.Entry<Integer, Attribute> vt2 : vt1.entrySet()) {
                            if (vt2.getKey() <= rootid) changed.add(Map.entry(vt2.getKey(), vt2.getValue()));
                        }
                        for (Map.Entry<Integer, Attribute> val : changed) {
                            vt1.remove(val.getKey());
                            vt1.put(val.getKey() == rootid ? 0 : val.getKey() + 1, val.getValue());
                        }
                    }
                }
                rootVertex.id = 0;
                for (int k = vx; k < vertices.size(); k++) vertices.get(k).id++;
            } else {
                Setup.warning(700, "GenEvent::add_tree Suspicious looking rootvertex found. Will try to cope.");
            }
        }
        if (Setup.debugging(6)) {
            Setup.debug(6, "GenEvent - particles sorted: " + particles.size() + ", max deque size: " + maxDequeSize
                + ", iterations: " + sortingLoopCount);
        }
    }

    /** Capacity hints; Java lists grow by themselves. */
    public void reserve(int parts, int verts) {
        if (particles instanceof ArrayList<GenParticle> l) l.ensureCapacity(parts);
        if (vertices instanceof ArrayList<GenVertex> l) l.ensureCapacity(verts);
    }

    /** Empties the event: number 0, no weights, attributes, particles or vertices; units and run kept. */
    public void clear() {
        synchronized (lock) {
            eventNumber = 0;
            rootVertex = new GenVertex();
            weights.clear();
            attributes.clear();
            particles.clear();
            vertices.clear();
        }
    }

    /** Deprecated: two particles to the root vertex. */
    public void setBeamParticles(GenParticle p1, GenParticle p2) {
        rootVertex.addParticleOut(p1);
        rootVertex.addParticleOut(p2);
    }

    /** Adds a particle as a beam: to the root vertex, with status 4. */
    public void addBeamParticle(GenParticle p1) {
        if (p1 == null) {
            Setup.warning(700, "Attempting to add an empty particle as beam particle. Ignored.");
            return;
        }
        if (p1.inEvent() && p1.parentEvent() != this) {
            Setup.warning(700, "Attempting to add particle from another event. Ignored.");
            return;
        }
        if (p1.productionVertex() != null) p1.productionVertex().removeParticleOut(p1);
        addParticle(p1);
        p1.setStatus(4);
    }

    /* ---- the stored form ------------------------------------------------ */

    /** Fills a GenEventData with this event. */
    public void writeData(GenEventData data) {
        data.links1.reserve(particles.size() * 2);
        data.links2.reserve(particles.size() * 2);
        data.eventNumber = eventNumber;
        data.momentumUnit = momentumUnit;
        data.lengthUnit = lengthUnit;
        data.eventPos = eventPos().copy();
        data.weights.clear();
        data.weights.addAll(weights);
        for (GenParticle p : particles) data.particles.add(new GenParticleData(p.data()));
        for (GenVertex v : vertices) {
            data.vertices.add(new GenVertexData(v.data()));
            final int vId = v.id();
            for (GenParticle p : v.particlesIn()) {
                data.links1.add(p.id());
                data.links2.add(vId);
            }
            for (GenParticle p : v.particlesOut()) {
                data.links1.add(vId);
                data.links2.add(p.id());
            }
        }
        for (Map.Entry<String, TreeMap<Integer, Attribute>> vt1 : attributes().entrySet()) {
            for (Map.Entry<Integer, Attribute> vt2 : vt1.getValue().entrySet()) {
                final String st = vt2.getValue().serialize();
                if (st == null) {
                    Setup.warning(300, "GenEvent::write_data: problem serializing attribute: " + vt1.getKey());
                } else {
                    data.attributeId.add(vt2.getKey());
                    data.attributeName.add(vt1.getKey());
                    data.attributeString.add(st);
                }
            }
        }
    }

    /** Rebuilds this event from a GenEventData. */
    public void readData(GenEventData data) {
        clear();
        setEventNumber(data.eventNumber);
        momentumUnit = data.momentumUnit;
        lengthUnit = data.lengthUnit;
        shiftPositionTo(data.eventPos);
        weights = new ArrayList<>(data.weights);
        for (GenParticleData pd : data.particles) {
            final GenParticle p = new GenParticle(pd);
            particles.add(p);
            p.event = this;
            p.id = particles.size();
        }
        for (GenVertexData vd : data.vertices) {
            final GenVertex v = new GenVertex(vd);
            vertices.add(v);
            v.event = this;
            v.id = -vertices.size();
        }
        for (int i = 0; i < data.links1.size(); ++i) {
            final int id1 = data.links1.get(i);
            final int id2 = data.links2.get(i);
            if ((id1 < 0 && id2 < 0) || (id1 > 0 && id2 > 0)) {
                Setup.warning(600, "GenEvent::read_data: wrong link: " + id1 + " " + id2);
                continue;
            }
            if (id1 > 0) {
                vertices.get(-id2 - 1).addParticleIn(particles.get(id1 - 1));
                continue;
            }
            if (id1 < 0) {
                vertices.get(-id1 - 1).addParticleOut(particles.get(id2 - 1));
            }
        }
        for (GenParticle p : particles) if (p.productionVertex() == null) rootVertex.addParticleOut(p);
        synchronized (lock) {
            for (int i = 0; i < data.attributeId.size(); ++i) {
                final String name = data.attributeName.get(i);
                if (name.isEmpty()) continue;
                final int id = data.attributeId.get(i);
                final StringAttribute att = new StringAttribute(data.attributeString.get(i));
                att.event = this;
                if (id > 0 && id <= particles.size()) att.particle = particles.get(id - 1);
                if (id < 0 && -id <= vertices.size()) att.vertex = vertices.get(-id - 1);
                attributes.computeIfAbsent(name, k -> new TreeMap<>()).put(id, att);
            }
        }
    }

    /** The root vertex, for the readers and writers that need to see it. */
    GenVertex rootVertex() {
        return rootVertex;
    }

    @Override
    public String toString() {
        return Print.line(this, false);
    }
}
