package com.sphere.core.hepmc3;

import com.sphere.core.rootio.RObject;
import com.sphere.core.rootio.RTree;

import java.io.IOException;
import java.util.List;

/**
 * GenEventData and GenRunInfoData out of a ROOT file, read by Sphere's own
 * ROOT reader: from an object written whole (ReaderRoot's keys) or from the
 * split branches of a tree (ReaderRootTree's hepmc3_tree), whatever the
 * namespace they were written under (HepMC:: by HepMC3 3.0, HepMC3:: since,
 * which the ROOT dictionaries of HepMC3 convert into each other).
 */
final class RootData {

    private RootData() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** True for the class names a GenEventData was written under. */
    static boolean isEventData(String className) {
        return className.startsWith("HepMC3::GenEventData") || className.startsWith("HepMC::GenEventData");
    }

    /* ---- objects written whole ------------------------------------------------------- */

    static GenEventData event(RObject o) {
        final GenEventData d = new GenEventData();
        d.eventNumber = o.integer("event_number");
        d.momentumUnit = Units.MomentumUnit.values()[clamp(o.integer("momentum_unit"), 2)];
        d.lengthUnit = Units.LengthUnit.values()[clamp(o.integer("length_unit"), 2)];
        for (Object p : o.list("particles")) {
            if (!(p instanceof RObject r)) continue;
            final GenParticleData pd = new GenParticleData();
            pd.pid = r.integer("pid");
            pd.status = r.integer("status");
            pd.isMassSet = r.number("is_mass_set") != 0 || Boolean.TRUE.equals(r.get("is_mass_set"));
            pd.mass = r.real("mass");
            pd.momentum = vector(r.object("momentum"));
            d.particles.add(pd);
        }
        for (Object v : o.list("vertices")) {
            if (!(v instanceof RObject r)) continue;
            final GenVertexData vd = new GenVertexData();
            vd.status = r.integer("status");
            vd.position = vector(r.object("position"));
            d.vertices.add(vd);
        }
        for (double w : doubles(o.get("weights"))) d.weights.add(w);
        d.eventPos = vector(o.object("event_pos"));
        for (int i : ints(o.get("links1"))) d.links1.add(i);
        for (int i : ints(o.get("links2"))) d.links2.add(i);
        for (int i : ints(o.get("attribute_id"))) d.attributeId.add(i);
        d.attributeName.addAll(strings(o.get("attribute_name")));
        d.attributeString.addAll(strings(o.get("attribute_string")));
        return d;
    }

    static GenRunInfoData run(RObject o) {
        final GenRunInfoData d = new GenRunInfoData();
        d.weightNames.addAll(strings(o.get("weight_names")));
        d.toolName.addAll(strings(o.get("tool_name")));
        d.toolVersion.addAll(strings(o.get("tool_version")));
        d.toolDescription.addAll(strings(o.get("tool_description")));
        d.attributeName.addAll(strings(o.get("attribute_name")));
        d.attributeString.addAll(strings(o.get("attribute_string")));
        return d;
    }

    /* ---- objects to write ------------------------------------------------------------ */

    /** The class names the writers use (HepMC3's dictionaries). */
    static final String EVENT_CLASS = "HepMC3::GenEventData";
    static final String RUN_CLASS = "HepMC3::GenRunInfoData";
    private static final String PARTICLE_CLASS = "HepMC3::GenParticleData";
    private static final String VERTEX_CLASS = "HepMC3::GenVertexData";
    private static final String VECTOR_CLASS = "HepMC3::FourVector";

    /** A GenEventData as ROOT streams it: members named as in HepMC3's class, the units by their enum value. */
    static RObject object(GenEventData d) {
        final RObject o = new RObject(EVENT_CLASS, 1);
        o.members.put("event_number", d.eventNumber);
        o.members.put("momentum_unit", d.momentumUnit.ordinal());
        o.members.put("length_unit", d.lengthUnit.ordinal());
        final java.util.ArrayList<Object> particles = new java.util.ArrayList<>(d.particles.size());
        for (GenParticleData p : d.particles) {
            final RObject r = new RObject(PARTICLE_CLASS, 1);
            r.members.put("pid", p.pid);
            r.members.put("status", p.status);
            r.members.put("is_mass_set", p.isMassSet);
            r.members.put("mass", p.mass);
            r.members.put("momentum", object(p.momentum));
            particles.add(r);
        }
        o.members.put("particles", particles);
        final java.util.ArrayList<Object> vertices = new java.util.ArrayList<>(d.vertices.size());
        for (GenVertexData v : d.vertices) {
            final RObject r = new RObject(VERTEX_CLASS, 1);
            r.members.put("status", v.status);
            r.members.put("position", object(v.position));
            vertices.add(r);
        }
        o.members.put("vertices", vertices);
        final double[] weights = new double[d.weights.size()];
        for (int i = 0; i < weights.length; i++) weights[i] = d.weights.get(i);
        o.members.put("weights", weights);
        o.members.put("event_pos", object(d.eventPos));
        o.members.put("links1", d.links1.toArray());
        o.members.put("links2", d.links2.toArray());
        o.members.put("attribute_id", d.attributeId.toArray());
        o.members.put("attribute_name", new java.util.ArrayList<>(d.attributeName));
        o.members.put("attribute_string", new java.util.ArrayList<>(d.attributeString));
        return o;
    }

    /** A GenRunInfoData as ROOT streams it. */
    static RObject object(GenRunInfoData d) {
        final RObject o = new RObject(RUN_CLASS, 1);
        o.members.put("weight_names", new java.util.ArrayList<>(d.weightNames));
        o.members.put("tool_name", new java.util.ArrayList<>(d.toolName));
        o.members.put("tool_version", new java.util.ArrayList<>(d.toolVersion));
        o.members.put("tool_description", new java.util.ArrayList<>(d.toolDescription));
        o.members.put("attribute_name", new java.util.ArrayList<>(d.attributeName));
        o.members.put("attribute_string", new java.util.ArrayList<>(d.attributeString));
        return o;
    }

    private static RObject object(FourVector v) {
        final RObject r = new RObject(VECTOR_CLASS, 1);
        r.members.put("m_v1", v.x());
        r.members.put("m_v2", v.y());
        r.members.put("m_v3", v.z());
        r.members.put("m_v4", v.t());
        return r;
    }

    /* ---- split branches ------------------------------------------------------------- */

    /** The branches of a split GenEventData, found once. */
    static final class EventBranches {
        final RTree.RBranch eventNumber, momentumUnit, lengthUnit, pid, status, isMassSet, mass, px, py, pz, e,
            vstatus, vx, vy, vz, vt, weights, posX, posY, posZ, posT, links1, links2, attributeId, attributeName,
            attributeString;

        EventBranches(RTree.RBranch top) {
            eventNumber = top.find("event_number");
            momentumUnit = top.find("momentum_unit");
            lengthUnit = top.find("length_unit");
            pid = top.find("particles.pid");
            status = top.find("particles.status");
            isMassSet = top.find("particles.is_mass_set");
            mass = top.find("particles.mass");
            px = top.find("particles.momentum.m_v1");
            py = top.find("particles.momentum.m_v2");
            pz = top.find("particles.momentum.m_v3");
            e = top.find("particles.momentum.m_v4");
            vstatus = top.find("vertices.status");
            vx = top.find("vertices.position.m_v1");
            vy = top.find("vertices.position.m_v2");
            vz = top.find("vertices.position.m_v3");
            vt = top.find("vertices.position.m_v4");
            weights = top.find("weights");
            posX = top.find("event_pos.m_v1");
            posY = top.find("event_pos.m_v2");
            posZ = top.find("event_pos.m_v3");
            posT = top.find("event_pos.m_v4");
            links1 = top.find("links1");
            links2 = top.find("links2");
            attributeId = top.find("attribute_id");
            attributeName = top.find("attribute_name");
            attributeString = top.find("attribute_string");
        }

        GenEventData read(long entry) throws IOException {
            final GenEventData d = new GenEventData();
            d.eventNumber = intValue(eventNumber, entry);
            d.momentumUnit = Units.MomentumUnit.values()[clamp(intValue(momentumUnit, entry), 2)];
            d.lengthUnit = Units.LengthUnit.values()[clamp(intValue(lengthUnit, entry), 2)];
            final int[] pids = ints(value(pid, entry));
            final int[] statuses = ints(value(status, entry));
            final boolean[] massSet = bools(value(isMassSet, entry));
            final double[] masses = doubles(value(mass, entry));
            final double[] x = doubles(value(px, entry));
            final double[] y = doubles(value(py, entry));
            final double[] z = doubles(value(pz, entry));
            final double[] t = doubles(value(e, entry));
            for (int i = 0; i < pids.length; i++) {
                final GenParticleData pd = new GenParticleData();
                pd.pid = pids[i];
                pd.status = at(statuses, i);
                pd.isMassSet = i < massSet.length && massSet[i];
                pd.mass = at(masses, i);
                pd.momentum = new FourVector(at(x, i), at(y, i), at(z, i), at(t, i));
                d.particles.add(pd);
            }
            final int[] vs = ints(value(vstatus, entry));
            final double[] wx = doubles(value(vx, entry));
            final double[] wy = doubles(value(vy, entry));
            final double[] wz = doubles(value(vz, entry));
            final double[] wt = doubles(value(vt, entry));
            for (int i = 0; i < vs.length; i++) {
                final GenVertexData vd = new GenVertexData();
                vd.status = vs[i];
                vd.position = new FourVector(at(wx, i), at(wy, i), at(wz, i), at(wt, i));
                d.vertices.add(vd);
            }
            for (double w : doubles(value(weights, entry))) d.weights.add(w);
            d.eventPos = new FourVector(doubleValue(posX, entry), doubleValue(posY, entry), doubleValue(posZ, entry),
                doubleValue(posT, entry));
            for (int i : ints(value(links1, entry))) d.links1.add(i);
            for (int i : ints(value(links2, entry))) d.links2.add(i);
            for (int i : ints(value(attributeId, entry))) d.attributeId.add(i);
            d.attributeName.addAll(strings(value(attributeName, entry)));
            d.attributeString.addAll(strings(value(attributeString, entry)));
            return d;
        }
    }

    /** The branches of a split GenRunInfoData. */
    static final class RunBranches {
        final RTree.RBranch weightNames, toolName, toolVersion, toolDescription, attributeName, attributeString;

        RunBranches(RTree.RBranch top) {
            weightNames = top.find("weight_names");
            toolName = top.find("tool_name");
            toolVersion = top.find("tool_version");
            toolDescription = top.find("tool_description");
            attributeName = top.find("attribute_name");
            attributeString = top.find("attribute_string");
        }

        void read(long entry, GenRunInfoData d) throws IOException {
            d.weightNames.addAll(strings(value(weightNames, entry)));
            d.toolName.addAll(strings(value(toolName, entry)));
            d.toolVersion.addAll(strings(value(toolVersion, entry)));
            d.toolDescription.addAll(strings(value(toolDescription, entry)));
            d.attributeName.addAll(strings(value(attributeName, entry)));
            d.attributeString.addAll(strings(value(attributeString, entry)));
        }
    }

    /* ---- values ------------------------------------------------------------------------ */

    private static Object value(RTree.RBranch b, long entry) throws IOException {
        return b == null ? null : b.value(entry);
    }

    private static int intValue(RTree.RBranch b, long entry) throws IOException {
        final Object v = value(b, entry);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static double doubleValue(RTree.RBranch b, long entry) throws IOException {
        final Object v = value(b, entry);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    private static int clamp(int i, int n) {
        return i < 0 || i >= n ? 0 : i;
    }

    private static FourVector vector(RObject v) {
        if (v == null) return new FourVector();
        return new FourVector(v.real("m_v1"), v.real("m_v2"), v.real("m_v3"), v.real("m_v4"));
    }

    private static double at(double[] a, int i) {
        return i < a.length ? a[i] : 0;
    }

    private static int at(int[] a, int i) {
        return i < a.length ? a[i] : 0;
    }

    static int[] ints(Object v) {
        if (v instanceof int[] a) return a;
        if (v instanceof long[] a) {
            final int[] out = new int[a.length];
            for (int i = 0; i < a.length; i++) out[i] = (int) a[i];
            return out;
        }
        return new int[0];
    }

    static double[] doubles(Object v) {
        if (v instanceof double[] a) return a;
        if (v instanceof float[] a) {
            final double[] out = new double[a.length];
            for (int i = 0; i < a.length; i++) out[i] = a[i];
            return out;
        }
        return new double[0];
    }

    static boolean[] bools(Object v) {
        if (v instanceof boolean[] a) return a;
        if (v instanceof byte[] a) {
            final boolean[] out = new boolean[a.length];
            for (int i = 0; i < a.length; i++) out[i] = a[i] != 0;
            return out;
        }
        return new boolean[0];
    }

    static List<String> strings(Object v) {
        if (!(v instanceof List<?> l)) return List.of();
        final java.util.ArrayList<String> out = new java.util.ArrayList<>(l.size());
        for (Object o : l) out.add(String.valueOf(o));
        return out;
    }
}
