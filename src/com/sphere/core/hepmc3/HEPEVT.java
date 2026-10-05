package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFormat;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdSort;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The HEPEVT common block of the 1989 standard (T. Sjöstrand et al., "A
 * proposed standard event record", CERN 89-08 vol. 3 p. 327) and HepMC3's
 * conversions to and from GenEvent: HEPEVT_Wrapper, its Runtime and
 * Template forms and HEPEVT_Helpers in one class.
 *
 * <p>Indices are Fortran's, from 1. The arrays are laid out as the common
 * block (status, id, mothers and daughters in pairs, momentum in fives,
 * position in fours), so {@link #toBytes} gives the bytes a Fortran program
 * with the same NMXHEP reads into /HEPEVT/ directly.
 */
public final class HEPEVT {

    /** HEPMC3_HEPEVT_NMXHEP: the size of the static block. */
    public static final int NMXHEP = 10000;

    private final int maxParticles;
    private int nevhep;
    private int nhep;
    private final int[] isthep;
    private final int[] idhep;
    private final int[] jmohep;
    private final int[] jdahep;
    private final double[] phep;
    private final double[] vhep;

    public HEPEVT() {
        this(NMXHEP);
    }

    public HEPEVT(int maxParticles) {
        this.maxParticles = maxParticles;
        isthep = new int[maxParticles];
        idhep = new int[maxParticles];
        jmohep = new int[2 * maxParticles];
        jdahep = new int[2 * maxParticles];
        phep = new double[5 * maxParticles];
        vhep = new double[4 * maxParticles];
    }

    /** The block shared like the C++ static HEPEVT_Wrapper, of NMXHEP entries. */
    public static final HEPEVT COMMON = new HEPEVT(NMXHEP);

    /* ---- accessors ------------------------------------------------------------ */

    public int maxNumberEntries() {
        return maxParticles;
    }

    public int eventNumber() {
        return nevhep;
    }

    public int numberEntries() {
        return nhep;
    }

    public int status(int index) {
        return isthep[index - 1];
    }

    public int id(int index) {
        return idhep[index - 1];
    }

    public int firstParent(int index) {
        return jmohep[2 * (index - 1)];
    }

    public int lastParent(int index) {
        return jmohep[2 * (index - 1) + 1];
    }

    public int firstChild(int index) {
        return jdahep[2 * (index - 1)];
    }

    public int lastChild(int index) {
        return jdahep[2 * (index - 1) + 1];
    }

    public double px(int index) {
        return phep[5 * (index - 1)];
    }

    public double py(int index) {
        return phep[5 * (index - 1) + 1];
    }

    public double pz(int index) {
        return phep[5 * (index - 1) + 2];
    }

    public double e(int index) {
        return phep[5 * (index - 1) + 3];
    }

    public double m(int index) {
        return phep[5 * (index - 1) + 4];
    }

    public double x(int index) {
        return vhep[4 * (index - 1)];
    }

    public double y(int index) {
        return vhep[4 * (index - 1) + 1];
    }

    public double z(int index) {
        return vhep[4 * (index - 1) + 2];
    }

    public double t(int index) {
        return vhep[4 * (index - 1) + 3];
    }

    public int numberParents(int index) {
        return firstParent(index) != 0 ? (lastParent(index) != 0 ? lastParent(index) - firstParent(index) : 1) : 0;
    }

    public int numberChildren(int index) {
        return firstChild(index) != 0 ? (lastChild(index) != 0 ? lastChild(index) - firstChild(index) : 1) : 0;
    }

    public int numberChildrenExact(int index) {
        int nc = 0;
        for (int i = 1; i <= nhep; ++i) {
            if ((firstParent(i) <= index && lastParent(i) >= index) || firstParent(i) == index || lastParent(i) == index) nc++;
        }
        return nc;
    }

    public void setEventNumber(int evtno) {
        nevhep = evtno;
    }

    public void setNumberEntries(int noentries) {
        nhep = noentries;
    }

    public void setStatus(int index, int status) {
        isthep[index - 1] = status;
    }

    public void setId(int index, int id) {
        idhep[index - 1] = id;
    }

    public void setParents(int index, int firstparent, int lastparent) {
        jmohep[2 * (index - 1)] = firstparent;
        jmohep[2 * (index - 1) + 1] = lastparent;
    }

    public void setChildren(int index, int firstchild, int lastchild) {
        jdahep[2 * (index - 1)] = firstchild;
        jdahep[2 * (index - 1) + 1] = lastchild;
    }

    public void setMomentum(int index, double px, double py, double pz, double e) {
        phep[5 * (index - 1)] = px;
        phep[5 * (index - 1) + 1] = py;
        phep[5 * (index - 1) + 2] = pz;
        phep[5 * (index - 1) + 3] = e;
    }

    public void setMass(int index, double mass) {
        phep[5 * (index - 1) + 4] = mass;
    }

    public void setPosition(int index, double x, double y, double z, double t) {
        vhep[4 * (index - 1)] = x;
        vhep[4 * (index - 1) + 1] = y;
        vhep[4 * (index - 1) + 2] = z;
        vhep[4 * (index - 1) + 3] = t;
    }

    public void zeroEverything() {
        nevhep = 0;
        nhep = 0;
        java.util.Arrays.fill(isthep, 0);
        java.util.Arrays.fill(idhep, 0);
        java.util.Arrays.fill(jmohep, 0);
        java.util.Arrays.fill(jdahep, 0);
        java.util.Arrays.fill(phep, 0);
        java.util.Arrays.fill(vhep, 0);
    }

    /* ---- printing ---------------------------------------------------------------- */

    public void printHepevt(COStream ostr) {
        ostr.put(" Event No.: ").put(nevhep).endl();
        ostr.put("  Nr   Type   Parent(s)  Daughter(s)      Px       Py       Pz       E    Inv. M.").endl();
        for (int i = 1; i <= nhep; ++i) printHepevtParticle(i, ostr);
    }

    public void printHepevtParticle(int index, COStream ostr) {
        ostr.put(CFormat.sprintf("%5i %6i", index, idhep[index - 1]));
        ostr.put(CFormat.sprintf("%4i - %4i  ", firstParent(index), lastParent(index)));
        ostr.put(CFormat.sprintf("%4i - %4i ", firstChild(index), lastChild(index)));
        ostr.put(CFormat.sprintf("%8.2f %8.2f %8.2f %8.2f %8.2f", px(index), py(index), pz(index), e(index), m(index)));
        ostr.endl();
    }

    public String printHepevt() {
        final COStream os = new COStream();
        printHepevt(os);
        return os.str();
    }

    /* ---- daughters from mothers ----------------------------------------------------- */

    /**
     * Fills the daughter ranges from the mothers, for a record with correct
     * ordering and mother ids; true when every range holds exactly the
     * daughters (true of proper events).
     */
    public boolean fixDaughters() {
        for (int i = 1; i <= numberEntries(); i++) {
            for (int k = 1; k <= numberEntries(); k++) {
                if (i != k) {
                    if (firstParent(k) <= i && i <= lastParent(k)) {
                        setChildren(i, firstChild(i) == 0 ? k : Math.min(firstChild(i), k),
                            lastChild(i) == 0 ? k : Math.max(lastChild(i), k));
                    }
                }
            }
        }
        boolean isFixed = true;
        for (int i = 1; i <= numberEntries(); i++) {
            isFixed = isFixed && numberChildrenExact(i) == numberChildren(i);
        }
        return isFixed;
    }

    /* ---- HEPEVT to GenEvent ----------------------------------------------------------- */

    private static final class Sets {
        final TreeSet<Integer> in = new TreeSet<>();
        final TreeSet<Integer> out = new TreeSet<>();

        Sets copy() {
            final Sets s = new Sets();
            s.in.addAll(in);
            s.out.addAll(out);
            return s;
        }
    }

    /**
     * Builds an event from the block, trusting the mothers: one vertex per
     * set of mothers, the daughters of identical mothers merged. The C++
     * walks its maps in the order of the objects' addresses; here that order
     * is the order they were made in, which is what a fresh heap gives.
     */
    public boolean toGenEvent(GenEvent evt) {
        if (evt == null) {
            com.sphere.core.hepmc3.cxx.StdStreams.cerr().println("HEPEVT_to_GenEvent_nonstatic  - passed null event.");
            return false;
        }
        evt.setEventNumber(eventNumber());
        final int ne = numberEntries();
        final List<GenParticle> hepevtParticles = new ArrayList<>(ne);
        final Map<Integer, GenParticle> particlesIndex = new TreeMap<>();
        final List<GenVertex> vertexOrder = new ArrayList<>(ne);
        final IdentityHashMap<GenVertex, Sets> hepevtVertices = new IdentityHashMap<>();
        final Map<Integer, GenVertex> vertexIndex = new TreeMap<>();
        for (int i = 1; i <= ne; i++) {
            final GenParticle p = new GenParticle();
            p.setMomentum(new FourVector(px(i), py(i), pz(i), e(i)));
            p.setStatus(status(i));
            p.setPid(id(i));
            p.setGeneratedMass(m(i));
            hepevtParticles.add(p);
            particlesIndex.put(i, p);
            final GenVertex v = new GenVertex();
            v.setPosition(new FourVector(x(i), y(i), z(i), t(i)));
            v.addParticleOut(p);
            final Sets s = new Sets();
            s.out.add(i);
            vertexIndex.put(i, v);
            vertexOrder.add(v);
            hepevtVertices.put(v, s);
        }
        for (int i1 = 1; i1 <= ne; i1++) {
            for (int i2 = 1; i2 <= ne; i2++) {
                int firstParent = firstParent(i2);
                int lastParent = lastParent(i2);
                if (firstParent > lastParent) {
                    final int tmp = firstParent;
                    firstParent = lastParent;
                    lastParent = tmp;
                }
                if (firstParent < 0) {
                    com.sphere.core.hepmc3.cxx.StdStreams.cerr().println("HEPEVT_to_GenEvent_nonstatic - HEPEVT record (" + i2
                        + ") contains negative parent index (" + firstParent + "," + lastParent
                        + "). This should not happen. Check your HEPEVT record and make sure NMXHEP is used consistenlty.");
                    return false;
                } else if (firstParent == 0 && lastParent != 0) {
                    firstParent = lastParent;
                }
                if (firstParent <= i1 && i1 <= lastParent) {
                    final GenVertex pv = hepevtParticles.get(i2 - 1).productionVertex();
                    Sets s = hepevtVertices.get(pv);
                    if (s == null) {
                        s = new Sets();
                        hepevtVertices.put(pv, s);
                        vertexOrder.add(pv);
                    }
                    s.in.add(i1);
                }
            }
        }
        for (int i = 1; i <= ne; i++) vertexIndex.get(i).removeParticleOut(particlesIndex.get(i));
        final List<GenVertex> finalOrder = new ArrayList<>();
        final IdentityHashMap<GenVertex, Sets> finalVertices = new IdentityHashMap<>();
        for (GenVertex vs : vertexOrder) {
            final Sets sets = hepevtVertices.get(vs);
            if (finalVertices.isEmpty() || (sets.in.isEmpty() && !sets.out.isEmpty())) {
                if (!finalVertices.containsKey(vs)) {
                    finalVertices.put(vs, sets.copy());
                    finalOrder.add(vs);
                }
                continue;
            }
            GenVertex merged = null;
            for (GenVertex v2 : finalOrder) {
                if (sets.in.equals(finalVertices.get(v2).in)) {
                    finalVertices.get(v2).out.addAll(sets.out);
                    merged = v2;
                    break;
                }
            }
            if (merged == null && !finalVertices.containsKey(vs)) {
                finalVertices.put(vs, sets.copy());
                finalOrder.add(vs);
            }
        }
        final List<GenParticle> finalParticles = new ArrayList<>();
        final TreeSet<Integer> used = new TreeSet<>();
        for (GenVertex v : finalOrder) {
            final Sets s = finalVertices.get(v);
            used.addAll(s.in);
            used.addAll(s.out);
            for (int el : s.in) v.addParticleIn(particlesIndex.get(el));
            if (!s.in.isEmpty()) for (int el : s.out) v.addParticleOut(particlesIndex.get(el));
        }
        for (int el : used) finalParticles.add(particlesIndex.get(el));
        evt.addTree(finalParticles);
        if (evt.particles().size() != ne) {
            com.sphere.core.hepmc3.cxx.StdStreams.cerr().println("HEPEVT_to_GenEvent_nonstatic - number of particles ("
                + evt.particles().size() + ") in the event is different from the number of particles (" + ne
                + ") in the HEPEVT record. This should not happen. Check your HEPEVT record and make sure NMXHEP is used consistenlty.");
            return false;
        }
        return true;
    }

    /* ---- GenEvent to HEPEVT ------------------------------------------------------------- */

    /** GenParticlePtr_greater: by PDG id, then status, then energy. */
    static boolean particleLess(GenParticle lx, GenParticle rx) {
        if (lx.pid() != rx.pid()) return lx.pid() < rx.pid();
        if (lx.status() != rx.status()) return lx.status() < rx.status();
        return lx.momentum().e() < rx.momentum().e();
    }

    /** pair_GenVertexPtr_int_greater, with the C++ quirks (the "out" lists compare the incoming particles again). */
    static boolean vertexLess(Map.Entry<GenVertex, Integer> lx, Map.Entry<GenVertex, Integer> rx) {
        if (!lx.getValue().equals(rx.getValue())) return lx.getValue() < rx.getValue();
        final GenVertex l = lx.getKey();
        final GenVertex r = rx.getKey();
        if (l.particlesIn().size() != r.particlesIn().size()) return l.particlesIn().size() < r.particlesIn().size();
        if (l.particlesOut().size() != r.particlesOut().size()) return l.particlesOut().size() < r.particlesOut().size();
        final int[] lin = pids(l.particlesIn());
        final int[] rin = pids(r.particlesIn());
        for (int i = 0; i < lin.length; i++) if (lin[i] != rin[i]) return lin[i] < rin[i];
        // C++ fills the "out" lists from particles_in too
        for (int i = 0; i < lin.length; i++) if (lin[i] != rin[i]) return lin[i] < rin[i];
        final double[] lmom = energies(l.particlesIn());
        final double[] rmom = energies(r.particlesIn());
        for (int i = 0; i < lmom.length; i++) if (lmom[i] != rmom[i]) return lmom[i] < rmom[i];
        for (int i = 0; i < lmom.length; i++) if (lmom[i] != rmom[i]) return lmom[i] < rmom[i];
        return false;
    }

    private static int[] pids(List<GenParticle> ps) {
        final int[] a = new int[ps.size()];
        for (int i = 0; i < a.length; i++) a[i] = ps.get(i).pid();
        java.util.Arrays.sort(a);
        return a;
    }

    private static double[] energies(List<GenParticle> ps) {
        final double[] a = new double[ps.size()];
        for (int i = 0; i < a.length; i++) a[i] = ps.get(i).momentum().e();
        sortLikeStd(a);
        return a;
    }

    /** std::sort of doubles with operator<: NaNs aside, the same as any sort. */
    private static void sortLikeStd(double[] a) {
        java.util.Arrays.sort(a);
    }

    /** calculate_longest_path_to_top. */
    private static void longestPathToTop(GenVertex v, IdentityHashMap<GenVertex, Integer> pathl, List<GenVertex> order) {
        int p = 0;
        for (GenParticle pp : v.particlesIn()) {
            final GenVertex v2 = pp.productionVertex();
            if (v2 == v) continue;
            if (v2 == null) {
                p = Math.max(p, 1);
            } else {
                if (!pathl.containsKey(v2)) longestPathToTop(v2, pathl, order);
                p = Math.max(p, pathl.get(v2) + 1);
            }
        }
        if (!pathl.containsKey(v)) order.add(v);
        pathl.put(v, p);
    }

    /**
     * Writes an event into the block: vertices sorted by their longest path
     * from the beams so that mothers come before daughters, the incoming
     * particles of each vertex together, the particles without an end vertex
     * last. The daughter ranges are left at zero (as Pythia does).
     */
    public boolean fromGenEvent(GenEvent evt) {
        if (evt == null) return false;
        final IdentityHashMap<GenVertex, Integer> longestPaths = new IdentityHashMap<>();
        final List<GenVertex> order = new ArrayList<>();
        for (GenVertex v : evt.vertices()) longestPathToTop(v, longestPaths, order);
        // the map in the order of the objects' addresses: the order they were made in (event order, root vertex first)
        order.sort((a, b) -> Integer.compare(creationRank(a, evt), creationRank(b, evt)));
        final List<Map.Entry<GenVertex, Integer>> sortedPaths = new ArrayList<>();
        for (GenVertex v : order) sortedPaths.add(Map.entry(v, longestPaths.get(v)));
        StdSort.sort(sortedPaths, HEPEVT::vertexLess);
        final List<GenParticle> sortedParticles = new ArrayList<>();
        final List<GenParticle> stableParticles = new ArrayList<>();
        for (Map.Entry<GenVertex, Integer> it : sortedPaths) {
            final List<GenParticle> q = new ArrayList<>(it.getKey().particlesIn());
            StdSort.sort(q, HEPEVT::particleLess);
            sortedParticles.addAll(q);
            for (GenParticle pp : it.getKey().particlesOut()) if (pp.endVertex() == null) stableParticles.add(pp);
        }
        StdSort.sort(stableParticles, HEPEVT::particleLess);
        sortedParticles.addAll(stableParticles);
        final int particleCounter = Math.min(sortedParticles.size(), maxNumberEntries());
        setEventNumber(evt.eventNumber());
        setNumberEntries(particleCounter);
        final IdentityHashMap<GenParticle, Integer> position = new IdentityHashMap<>();
        for (int j = particleCounter; j >= 1; j--) position.put(sortedParticles.get(j - 1), j);
        for (int i = 1; i <= particleCounter; ++i) {
            final GenParticle sp = sortedParticles.get(i - 1);
            setStatus(i, sp.status());
            setId(i, sp.pid());
            final FourVector m = sp.momentum();
            setMomentum(i, m.px(), m.py(), m.pz(), m.e());
            setMass(i, sp.generatedMass());
            if (sp.productionVertex() != null && !sp.productionVertex().particlesIn().isEmpty()) {
                final FourVector p = sp.productionVertex().position();
                setPosition(i, p.x(), p.y(), p.z(), p.t());
                final List<Integer> mothers = new ArrayList<>();
                for (GenParticle it : sp.productionVertex().particlesIn()) {
                    // every index j holding this particle; a particle sits once in the list
                    final Integer j = position.get(it);
                    if (j != null) mothers.add(j);
                }
                java.util.Collections.sort(mothers);
                if (mothers.isEmpty()) mothers.add(0);
                if (mothers.size() == 1) mothers.add(mothers.get(0));
                setParents(i, mothers.get(0), mothers.get(mothers.size() - 1));
            } else {
                setPosition(i, 0, 0, 0, 0);
                setParents(i, 0, 0);
            }
            setChildren(i, 0, 0);
        }
        return true;
    }

    /** The vertices of an event were made in id order; a vertex outside the list (the root) counts as first. */
    private static int creationRank(GenVertex v, GenEvent evt) {
        return v.parentEvent() == evt ? -v.id() : 0;
    }

    /* ---- the block as bytes, for Fortran ------------------------------------------------- */

    /**
     * The block as the bytes of a Fortran common /HEPEVT/ of this NMXHEP,
     * little-endian, for nhep entries (the rest of each array zero).
     */
    public byte[] toBytes() {
        final int n = maxParticles;
        final ByteBuffer b = ByteBuffer.allocate(8 + n * (6 * 4 + 9 * 8)).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(nevhep).putInt(nhep);
        for (int v : isthep) b.putInt(v);
        for (int v : idhep) b.putInt(v);
        for (int v : jmohep) b.putInt(v);
        for (int v : jdahep) b.putInt(v);
        for (double v : phep) b.putDouble(v);
        for (double v : vhep) b.putDouble(v);
        return b.array();
    }

    /** Fills the block from such bytes (copy_to_internal_storage of the first N entries). */
    public void fromBytes(byte[] bytes, int blockSize, int n) {
        if (n < 1 || n > maxParticles) return;
        final ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        nevhep = b.getInt(0);
        nhep = b.getInt(4);
        int off = 8;
        for (int i = 0; i < n; i++) isthep[i] = b.getInt(off + 4 * i);
        off += 4 * blockSize;
        for (int i = 0; i < n; i++) idhep[i] = b.getInt(off + 4 * i);
        off += 4 * blockSize;
        for (int i = 0; i < 2 * n; i++) jmohep[i] = b.getInt(off + 4 * i);
        off += 8 * blockSize;
        for (int i = 0; i < 2 * n; i++) jdahep[i] = b.getInt(off + 4 * i);
        off += 8 * blockSize;
        for (int i = 0; i < 5 * n; i++) phep[i] = b.getDouble(off + 8 * i);
        off += 40 * blockSize;
        for (int i = 0; i < 4 * n; i++) vhep[i] = b.getDouble(off + 8 * i);
    }
}
