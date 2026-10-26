package com.sphere.core.geant4;

import com.sphere.components.rootview.RootColors;
import com.sphere.core.rootio.ObjectWriter;
import com.sphere.core.rootio.RFileWriter;
import com.sphere.core.rootio.RList;
import com.sphere.core.rootio.RObject;
import com.sphere.core.rootio.Streamers;
import com.sphere.core.rootio.WBuffer;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A G4editor document written as a ROOT geometry: a .root file holding the
 * TGeoManager that TGeoManager::Import reads, written without ROOT.
 *
 * The objects are those ROOT's own constructors build -- the same classes,
 * the same flags, the same derived members (a tube segment's sines, a
 * trapezoid's vertices, a sphere's drawing divisions) -- streamed with the
 * class descriptions of ROOT 6.36, which the file carries so any ROOT 6 reads
 * it. Geant4's solids map onto TGeo's shapes as TGDMLParse maps GDML onto
 * them: G4Ellipsoid and G4EllipticalCone become scaled shapes, G4Tet a
 * tessellated one, a G4GenericPolycone a polycone when its profile allows,
 * replicas become divisions. Lengths go to cm, angles to degrees, densities
 * stay in g/cm3.
 */
public final class TGeoWriter {

    private TGeoWriter() {
    }

    /** What was written, and what had to be approximated. */
    public record Report(int volumes, int nodes, int shapes, int materials, List<String> warnings) {
    }

    // ------------------------------------------------------------------
    // ROOT's class descriptions, carried as a resource
    // ------------------------------------------------------------------

    /** The TList of TStreamerInfo of a file ROOT 6.36 wrote with every TGeo class (rootgeo/tgeo_reference.C). */
    private record Reference(int fileVersion, int keylen, byte[] list, Streamers streamers) {
    }

    private static Reference reference;

    private static synchronized Reference reference() throws IOException {
        if (reference == null) {
            try (InputStream in = TGeoWriter.class.getResourceAsStream("rootgeo/TGeoStreamers.bin")) {
                if (in == null) {
                    throw new IOException("ROOT's class descriptions (rootgeo/TGeoStreamers.bin) are missing from this build");
                }
                final DataInputStream d = new DataInputStream(in);
                final int version = d.readInt();
                final int keylen = d.readInt();
                final byte[] list = d.readNBytes(d.readInt());
                reference = new Reference(version, keylen, list, Streamers.read(list, keylen));
            }
        }
        return reference;
    }

    /**
     * Regenerates the resource from a file a newer ROOT wrote with every TGeo
     * class (rootgeo/tgeo_reference.C):
     * java -cp Sphere.jar com.sphere.core.geant4.TGeoWriter extract tgeo_reference.root TGeoStreamers.bin
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 3 || !args[0].equals("extract")) {
            System.err.println("usage: TGeoWriter extract <reference.root> <TGeoStreamers.bin>");
            return;
        }
        try (com.sphere.core.rootio.RootIO io = com.sphere.core.rootio.RootIO.open(Path.of(args[1]))) {
            final com.sphere.components.rootview.RootKey k = io.file().streamerInfoKey();
            final byte[] list = io.file().payload(k);
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(Files.newOutputStream(Path.of(args[2])))) {
                out.writeInt(io.file().getFormatVersion());
                out.writeInt(k.keylen);
                out.writeInt(list.length);
                out.write(list);
            }
            System.out.println(io.streamers().all().size() + " class descriptions of ROOT " + io.file().getFormatVersion()
                + " written to " + args[2]);
        }
    }

    /** The version ROOT 6.36 writes STL collections with (TStreamerInfo's own). */
    private static final int STL_VERSION = 10;

    // TObject bits ROOT sets on what it writes
    private static final long REGISTERED = 1L << 21;        // TGeoMatrix::kGeoRegistered
    private static final long TRANSLATION = 1L << 17;       // TGeoMatrix::kGeoTranslation
    private static final long ROTATION = 1L << 18;          // TGeoMatrix::kGeoRotation
    private static final long SCALE = 1L << 19;             // TGeoMatrix::kGeoScale
    private static final long REFLECTION = 1L << 20;        // TGeoMatrix::kGeoReflection
    private static final long VOLUME_ADDED = 1L << 23;      // TGeoVolume::kVolumeAdded
    private static final long VOLUME_REPLICATED = 1L << 14; // TGeoVolume::kVolumeReplicated
    private static final long MATERIAL_USED = 1L << 17;     // TGeoMaterial::kMatUsed
    private static final long NODE_OFFSET = 1L << 15;       // TGeoNode::kGeoNodeOffset
    // TGeoShape::fShapeBits, as each class's constructor sets them
    private static final long BOX = 1L << 10, TUBE = 1L << 13, TUBESEG = 1L << 14, CONE = 1L << 15, CONESEG = 1L << 16;
    private static final long PCON = 1L << 17, PGON = 1L << 18, ARB8 = 1L << 19, ELTU = 1L << 20, CTUB = 1L << 22;
    private static final long TRD1 = 1L << 23, TRD2 = 1L << 24, CLOSED = 1L << 26, XTRU = 1L << 27;
    private static final long PARABOLOID = 1L << 28, HYPE = 1L << 30, TORUS = 1L << 9, PARA = 1L << 11, SPH = 1L << 12;
    private static final long RSEG = 1L << 1, PHISEG = 1L << 2, THETASEG = 1L << 3;
    /** TGeoAtt: kVisThis, kVisDaughters, kActThis, kActDaughters. */
    private static final long VISIBLE_ATT = 0xc0c;

    // ------------------------------------------------------------------

    /** Writes the document as a ROOT geometry; the TGeoManager is named after the file. */
    public static Report write(Geometry g, Path file) throws IOException {
        final Reference ref = reference();
        final String stem = file.getFileName().toString().replaceFirst("\\.[^.]*$", "");
        final Builder b = new Builder(g, ref.streamers);
        final RObject manager = b.build(TGeoReader.clean(stem, "Geometry"), g.title());
        final ObjectWriter writer = new ObjectWriter(ref.streamers, Set.of(), STL_VERSION);
        final Path part = file.resolveSibling(file.getFileName() + ".part");
        try {
            final RFileWriter w = new RFileWriter(part, file.getFileName().toString(), "", ref.fileVersion, 101,
                ref.list, ref.keylen);
            try {
                w.writeObject("TGeoManager", manager.string("fName"), manager.string("fTitle"), keylen -> {
                    final WBuffer buf = new WBuffer(keylen);
                    // TKey::Streamer: the object a key holds is known under tag 1, for its members pointing back
                    buf.mapObject(manager, 1L);
                    writer.writeClass(buf, "TGeoManager", manager);
                    return buf.toByteArray();
                });
            } catch (RuntimeException e) {
                w.abandon();
                throw new IOException("the geometry could not be written as ROOT: " + e.getMessage(), e);
            }
            w.close();
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
        return new Report(b.volumesList.size(), b.nodeCount, b.shapesList.size(), b.materialsList.size(), b.warnings);
    }

    // ------------------------------------------------------------------

    private static final class Builder {
        final Geometry g;
        final Streamers streamers;
        final List<String> warnings = new ArrayList<>();
        final RList matrices = list("TObjArray");
        final RList shapesList = list("TObjArray");
        final RList volumesList = list("TObjArray");
        final RList materialsList = list("TList");
        final RList mediaList = list("TList");
        final Map<Solid, RObject> shapes = new IdentityHashMap<>();
        final Map<String, RObject> media = new HashMap<>();
        final Map<LogicalVolume, RObject> volumes = new LinkedHashMap<>();
        final Map<LogicalVolume, Long> totals = new IdentityHashMap<>();
        final Map<RObject, Integer> references = new IdentityHashMap<>();
        final Set<String> shapeNames = new LinkedHashSet<>();
        RObject identity;
        int nodeCount;

        Builder(Geometry g, Streamers streamers) {
            this.g = g;
            this.streamers = streamers;
        }

        // --------------------------------------------------------------

        /** An object of a class at the version of the descriptions written, with a TObject's members. */
        RObject obj(String cls) {
            final Streamers.Info info = streamers.find(cls, -1);
            if (info == null) {
                throw new IllegalStateException("no description of " + cls + " to write it with");
            }
            final RObject o = new RObject(cls, info.version());
            o.members.put("fUniqueID", 0L);
            o.members.put("fBits", 0L);
            return o;
        }

        RObject named(String cls, String name, String title) {
            final RObject o = obj(cls);
            o.members.put("fName", name);
            o.members.put("fTitle", title);
            return o;
        }

        /** A collection as ROOT 6.36 writes one: no in-memory bits in its TObject. */
        static RList list(String cls) {
            final RList l = new RList();
            l.className = cls;
            l.bits = 0;
            return l;
        }

        void warn(String w) {
            if (!warnings.contains(w)) {
                warnings.add(w);
            }
        }

        // --------------------------------------------------------------

        RObject build(String name, String title) throws IOException {
            final LogicalVolume world = g.world();
            if (world == null) {
                throw new IOException("the document has no world volume");
            }
            for (Solid s : g.solids()) {
                if (s.error() == null || g.users(s).isEmpty()) {
                    continue;
                }
                // a value that does not evaluate cannot be written; a shape Geant4 refuses (a polygon that
                // crosses itself, say) still is a TGeo shape, as ROOT itself accepted it
                for (Solid.Param p : s.params()) {
                    if (p.error() != null) {
                        throw new IOException("solid " + s.name() + " cannot be evaluated: " + s.error());
                    }
                }
                warn("solid " + s.name() + " is not valid for Geant4 (" + s.error() + "): written to ROOT as given");
            }
            identity = named("TGeoIdentity", "Identity", "");
            identity.members.put("fBits", REGISTERED);
            matrices.add(identity);

            // volumes in the order a walk from the world meets them, the world first (fNumber 1)
            final ArrayDeque<LogicalVolume> todo = new ArrayDeque<>();
            todo.add(world);
            while (!todo.isEmpty()) {
                final LogicalVolume lv = todo.poll();
                if (volumes.containsKey(lv)) {
                    continue;
                }
                volumes.put(lv, volume(lv, volumes.size() + 1));
                for (PhysicalVolume pv : lv.daughters) {
                    todo.add(pv.logical);
                }
            }
            for (Map.Entry<LogicalVolume, RObject> e : volumes.entrySet()) {
                nodes(e.getKey(), e.getValue());
            }
            final RObject top = volumes.get(world);
            top.members.put("fTitle", "Top volume");
            for (Map.Entry<LogicalVolume, RObject> e : volumes.entrySet()) {
                final RObject v = e.getValue();
                final int refs = references.getOrDefault(v, 0) + (v == top ? 1 : 0);
                v.members.put("fRefCount", Math.max(1, refs));
                v.members.put("fNtotal", (int) Math.min(Integer.MAX_VALUE, total(e.getKey())));
                v.members.put("fBits", VOLUME_ADDED | (refs > 1 ? VOLUME_REPLICATED : 0));
            }

            final RObject topNode = named("TGeoNodeMatrix", world.name + "_1", "Top logical node");
            topNode.members.put("fGeoAtt", VISIBLE_ATT);
            topNode.members.put("fVolume", top);
            topNode.members.put("fMother", null);
            topNode.members.put("fNumber", 1);
            topNode.members.put("fNovlp", 0);
            topNode.members.put("fOverlaps", null);
            topNode.members.put("fMatrix", identity);
            final RList topNodes = list("TObjArray");
            topNodes.add(topNode);

            // fUniqueVolumes: slot 0 the top volume, then each volume at its fNumber
            final RList unique = list("TObjArray");
            unique.add(top);
            unique.addAll(volumesList);

            final RObject glMatrix = named("TGeoHMatrix", "", "");
            glMatrix.members.put("fTranslation", new double[3]);
            glMatrix.members.put("fRotationMatrix", new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1});
            glMatrix.members.put("fScale", new double[] {1, 1, 1});

            final RObject m = named("TGeoManager", name, title == null ? "" : title);
            final Map<String, Object> f = m.members;
            f.put("fNNodes", (int) Math.min(Integer.MAX_VALUE, total(world)));
            f.put("fVisDensity", 0.0);
            f.put("fExplodedView", 0);
            f.put("fVisOption", 1);
            f.put("fVisLevel", 3);
            f.put("fNsegments", 20);
            f.put("fNtracks", 0);
            f.put("fMaxVisNodes", 10000);
            f.put("fNpdg", 0);
            f.put("fPdgId", new int[1024]);
            // voxels are not written: ROOT builds them when it reads the geometry (CloseGeometry)
            f.put("fStreamVoxels", false);
            f.put("fPhiCut", false);
            f.put("fTimeCut", false);
            f.put("fMatrices", matrices);
            f.put("fShapes", shapesList);
            f.put("fVolumes", volumesList);
            for (String empty : new String[] {"fPhysicalNodes", "fTracks", "fPdgNames", "fGDMLMatrices",
                "fOpticalSurfaces", "fSkinSurfaces", "fBorderSurfaces"}) {
                f.put(empty, list("TObjArray"));
            }
            f.put("fMaterials", materialsList);
            f.put("fMedia", mediaList);
            f.put("fNodes", topNodes);
            f.put("fOverlaps", list("TObjArray"));
            f.put("fRegions", list("TObjArray"));
            f.put("fMasterVolume", top);
            f.put("fGLMatrix", glMatrix);
            f.put("fUniqueVolumes", unique);
            f.put("fNLevel", 100);
            f.put("fHashPNE", list("THashList"));
            f.put("fSizePNEId", 0);
            f.put("fNPNEId", 0);
            f.put("fKeyPNEId", null);
            f.put("fValuePNEId", null);
            f.put("fUsePWNav", false);
            f.put("fParallelWorld", null);
            f.put("fProperties", new ArrayList<>());
            return m;
        }

        /** fNtotal: the volume itself and every physical node below it. */
        long total(LogicalVolume lv) {
            final Long known = totals.get(lv);
            if (known != null) {
                return known;
            }
            long n = 1;
            for (PhysicalVolume pv : lv.daughters) {
                n += (long) pv.copies() * total(pv.logical);
            }
            totals.put(lv, n);
            return n;
        }

        // --------------------------------------------------------------
        // Volumes and nodes
        // --------------------------------------------------------------

        RObject volume(LogicalVolume lv, int number) {
            final RObject v = named("TGeoVolume", lv.name, "");
            final Map<String, Object> f = v.members;
            f.put("fGeoAtt", lv.visible ? VISIBLE_ATT : VISIBLE_ATT & ~4L);
            f.put("fLineColor", RootColors.index(new java.awt.Color(lv.color & 0xFFFFFF)));
            f.put("fLineStyle", 1);
            f.put("fLineWidth", 1);
            f.put("fFillColor", 19);
            f.put("fFillStyle", 1001);
            f.put("fNodes", null);
            f.put("fShape", shape(lv.solid));
            f.put("fMedium", medium(lv.material));
            f.put("fFinder", null);
            f.put("fVoxels", null);
            f.put("fNumber", number);
            f.put("fNtotal", 1);
            f.put("fRefCount", 1);
            final int alpha = (lv.color >>> 24) & 0xFF;
            f.put("fTransparency", alpha >= 255 ? -1 : (int) Math.round(100 * (1 - alpha / 255.0)));
            volumesList.add(v);
            return v;
        }

        void nodes(LogicalVolume lv, RObject mother) {
            if (lv.daughters.isEmpty()) {
                return;
            }
            final RList list = list("TObjArray");
            for (PhysicalVolume pv : lv.daughters) {
                final RObject child = volumes.get(pv.logical);
                final PhysicalVolume.Replica r = pv.replica;
                if (r != null && r.axis != PhysicalVolume.Replica.Axis.kRho && mother.get("fFinder") == null) {
                    division(pv, mother, child, list);
                    continue;
                }
                if (r != null && r.axis == PhysicalVolume.Replica.Axis.kRho) {
                    warn("replica " + pv.name + " along rho is written as " + pv.copies()
                        + " placements of the same volume, as G4editor draws it");
                }
                for (int c = 0; c < pv.copies(); c++) {
                    final String name = pv.copies() > 1 ? pv.name + "_" + c : pv.name;
                    list.add(node(name, child, mother, pv.copies() > 1 ? c : pv.copyNumber, matrix(pv.copyTransform(c))));
                }
            }
            mother.members.put("fNodes", list);
        }

        RObject node(String name, RObject volume, RObject mother, int copy, RObject matrix) {
            final RObject n = named("TGeoNodeMatrix", name, "");
            n.members.put("fGeoAtt", VISIBLE_ATT);
            n.members.put("fVolume", volume);
            n.members.put("fMother", mother);
            n.members.put("fNumber", copy);
            n.members.put("fNovlp", 0);
            n.members.put("fOverlaps", null);
            n.members.put("fMatrix", matrix);
            references.merge(volume, 1, Integer::sum);
            nodeCount++;
            return n;
        }

        /**
         * A replica as TGeoVolume::Divide makes one: a pattern finder on the
         * mother, and one TGeoNodeOffset per slice at the offset of its centre.
         * Geant4 centres replicas on the mother (G4ReplicaNavigation), so a
         * replica along x, y or z starts at minus half its length.
         */
        void division(PhysicalVolume pv, RObject mother, RObject child, RList list) {
            final PhysicalVolume.Replica r = pv.replica;
            final int n = r.n;
            final boolean phi = r.axis == PhysicalVolume.Replica.Axis.kPhi;
            final double step = phi ? Math.toDegrees(r.w) : r.w / 10;
            final double start = phi ? Math.toDegrees(r.off) : -0.5 * n * step;
            final RObject finder = obj(switch (r.axis) {
                case kXAxis -> "TGeoPatternX";
                case kYAxis -> "TGeoPatternY";
                case kZAxis -> "TGeoPatternZ";
                default -> "TGeoPatternCylPhi";
            });
            finder.members.put("fStep", step);
            finder.members.put("fStart", start);
            finder.members.put("fEnd", start + n * step);
            finder.members.put("fNdivisions", n);
            finder.members.put("fDivIndex", list.size());
            finder.members.put("fVolume", mother);
            mother.members.put("fFinder", finder);
            final String base = pv.logical.name;
            for (int i = 0; i < n; i++) {
                final RObject node = named("TGeoNodeOffset", base + "_" + (i + 1), "");
                node.members.put("fBits", NODE_OFFSET);
                node.members.put("fGeoAtt", VISIBLE_ATT);
                node.members.put("fVolume", child);
                node.members.put("fMother", mother);
                node.members.put("fNumber", i + 1);
                node.members.put("fNovlp", 0);
                node.members.put("fOverlaps", null);
                node.members.put("fOffset", start + (i + 0.5) * step);
                node.members.put("fIndex", i);
                node.members.put("fFinder", finder);
                list.add(node);
                references.merge(child, 1, Integer::sum);
                nodeCount++;
            }
        }

        /** A placement's daughter-to-mother transform as the matrix class ROOT would make for it. */
        RObject matrix(Xform t) {
            final double[] r = t.rotation();
            final Vec3 p = t.translation();
            if (t.isReflection()) {
                // a mirrored placement: TGeoHMatrix with kGeoReflection, as TGeoHMatrix::ReflectZ leaves it
                final RObject h = named("TGeoHMatrix", "", "");
                h.members.put("fBits", REGISTERED | TRANSLATION | ROTATION | REFLECTION);
                h.members.put("fTranslation", new double[] {p.x() / 10, p.y() / 10, p.z() / 10});
                h.members.put("fRotationMatrix", r.clone());
                h.members.put("fScale", new double[] {1, 1, 1});
                matrices.add(h);
                return h;
            }
            final boolean rotated = !isIdentity(r);
            final boolean moved = p.x() != 0 || p.y() != 0 || p.z() != 0;
            if (!rotated && !moved) {
                return identity;
            }
            if (!rotated) {
                return translation(p.x() / 10, p.y() / 10, p.z() / 10);
            }
            final RObject rot = rotation(r);
            if (!moved) {
                return rot;
            }
            final RObject c = named("TGeoCombiTrans", "", "");
            c.members.put("fBits", REGISTERED | TRANSLATION | ROTATION);
            c.members.put("fTranslation", new double[] {p.x() / 10, p.y() / 10, p.z() / 10});
            c.members.put("fRotation", rot);
            matrices.add(c);
            return c;
        }

        RObject translation(double x, double y, double z) {
            final RObject m = named("TGeoTranslation", "", "");
            m.members.put("fBits", REGISTERED | TRANSLATION);
            m.members.put("fTranslation", new double[] {x, y, z});
            matrices.add(m);
            return m;
        }

        RObject rotation(double[] r) {
            final RObject m = named("TGeoRotation", "", "");
            m.members.put("fBits", REGISTERED | ROTATION);
            m.members.put("fRotationMatrix", r.clone());
            matrices.add(m);
            return m;
        }

        static boolean isIdentity(double[] r) {
            final double[] id = {1, 0, 0, 0, 1, 0, 0, 0, 1};
            for (int i = 0; i < 9; i++) {
                if (Math.abs(r[i] - id[i]) > 1e-15) {
                    return false;
                }
            }
            return true;
        }

        // --------------------------------------------------------------
        // Materials
        // --------------------------------------------------------------

        RObject medium(String name) {
            final RObject known = media.get(name);
            if (known != null) {
                return known;
            }
            Material m = g.materials().material(name);
            if (m == null || m.elements().isEmpty()) {
                warn("material " + name + " is unknown or empty: written as G4_AIR");
                m = g.materials().material("G4_AIR");
            }
            final RObject mat = material(m, name, materialsList.size());
            materialsList.add(mat);
            final RObject med = named("TGeoMedium", name, "");
            med.members.put("fId", mediaList.size() + 1);
            med.members.put("fParams", new double[20]);
            med.members.put("fMaterial", mat);
            mediaList.add(med);
            media.put(name, med);
            return med;
        }

        /** TGeoMaterial for one element, TGeoMixture otherwise, with Geant4's radiation and interaction lengths. */
        RObject material(Material m, String name, int index) {
            final List<Element> els = m.elements();
            final double[] w = m.massFractions();
            final boolean single = els.size() == 1;
            final RObject o = named(single ? "TGeoMaterial" : "TGeoMixture", name, "");
            final Map<String, Object> f = o.members;
            f.put("fBits", MATERIAL_USED);
            f.put("fFillColor", 19);
            f.put("fFillStyle", 1001);
            f.put("fIndex", index);
            final double density = m.densityGcm3();
            double a = 0, z = 0;
            for (int i = 0; i < els.size(); i++) {
                a += w[i] * els.get(i).a;
                z += w[i] * els.get(i).z;
            }
            f.put("fA", a);
            f.put("fZ", z);
            f.put("fDensity", density);
            f.put("fRadLen", Math.min(1e30, m.radiationLength() / 10));
            f.put("fIntLen", Math.min(1e30, m.nuclearInteractionLength() / 10));
            f.put("fTemperature", m.temperature());
            f.put("fPressure", m.pressure() * TGeoReader.ATMOSPHERE);
            f.put("fState", switch (m.state()) {
                case SOLID -> 1;
                case LIQUID -> 2;
                case GAS -> 3;
                default -> 0;
            });
            f.put("fShader", null);
            f.put("fCerenkov", null);
            // no element objects: ROOT takes them from its table, by Z (TGeoMaterial::GetElement)
            f.put("fElement", null);
            f.put("fProperties", list("TList"));
            f.put("fConstProperties", list("TList"));
            if (!single) {
                final int n = els.size();
                final double[] zs = new double[n], as = new double[n];
                for (int i = 0; i < n; i++) {
                    zs[i] = els.get(i).z;
                    as[i] = els.get(i).a;
                }
                f.put("fNelements", n);
                f.put("fZmixture", zs);
                f.put("fAmixture", as);
                f.put("fWeights", w);
                f.put("fNatoms", atoms(m, els));
                f.put("fVecNbOfAtomsPerVolume", null);
                f.put("fElements", null);
            }
            return o;
        }

        /** Atom counts per element, when the material is a formula of whole atoms; null otherwise. */
        int[] atoms(Material m, List<Element> els) {
            if (m.parts().isEmpty() || !m.parts().get(0).byAtoms()) {
                return null;
            }
            final Map<Integer, Double> byZ = new HashMap<>();
            for (Material.Part p : m.parts()) {
                final Element e = g.materials().element(p.ref());
                if (e == null || !p.byAtoms()) {
                    return null;
                }
                byZ.merge(e.z, p.amount(), Double::sum);
            }
            final int[] out = new int[els.size()];
            for (int i = 0; i < els.size(); i++) {
                final double c = byZ.getOrDefault(els.get(i).z, 0.0);
                if (c <= 0 || Math.abs(c - Math.rint(c)) > 1e-9) {
                    return null;
                }
                out[i] = (int) Math.rint(c);
            }
            return out;
        }

        // --------------------------------------------------------------
        // Shapes
        // --------------------------------------------------------------

        /** The TGeo shape of a solid, made once; operands go into the list before what uses them. */
        RObject shape(Solid s) {
            final RObject known = shapes.get(s);
            if (known != null) {
                return known;
            }
            final RObject o = make(s);
            shapes.put(s, o);
            return o;
        }

        /** A shape of a class, with TGeoShape's and TGeoBBox's members from the solid's extent. */
        RObject base(String cls, String name, BBox box, long bits) {
            final RObject o = named(cls, uniqueShapeName(name), "");
            o.members.put("fShapeId", 256);
            o.members.put("fShapeBits", bits);
            final Vec3 c = box.isEmpty() ? Vec3.ZERO : box.center(), h = box.isEmpty() ? Vec3.ZERO : box.halfSize();
            o.members.put("fDX", h.x() / 10);
            o.members.put("fDY", h.y() / 10);
            o.members.put("fDZ", h.z() / 10);
            o.members.put("fOrigin", new double[] {c.x() / 10, c.y() / 10, c.z() / 10});
            shapesList.add(o);
            return o;
        }

        String uniqueShapeName(String name) {
            String n = name;
            for (int i = 2; !shapeNames.add(n); i++) {
                n = name + "_" + i;
            }
            return n;
        }

        static double deg(double rad) {
            return Math.toDegrees(rad);
        }

        /** [0, 360), as TGeo keeps a first phi limit. */
        static double phi1(double rad) {
            double d = Math.toDegrees(rad) % 360;
            return d < 0 ? d + 360 : d;
        }

        static boolean fullTurn(double rad) {
            return rad >= Units.TWOPI - 1e-12;
        }

        /** The solid's extent, or for one that did not validate, the box of its corners as far as they are known. */
        static BBox extentOf(Solid s) {
            if (s.error() == null) {
                return s.extent();
            }
            BBox b = BBox.EMPTY;
            for (Solid.Table t : s.tables()) {
                if (t.element.equals("twoDimVertex") && s.table("section") != null) {
                    final Solid.Table sec = s.table("section");
                    for (int k = 0; k < sec.size(); k++) {
                        for (int i = 0; i < t.size(); i++) {
                            final double sc = sec.value(k, 3);
                            b = b.include(new Vec3(t.value(i, 0) * sc + sec.value(k, 1), t.value(i, 1) * sc + sec.value(k, 2),
                                sec.value(k, 0)));
                        }
                    }
                }
            }
            return b.isEmpty() ? BBox.centered(1, 1, 1) : b;
        }

        RObject make(Solid s) {
            final String tag = s.tag();
            final BBox box = extentOf(s);
            switch (tag) {
                case "box": {
                    final RObject o = base("TGeoBBox", s.name(), BBox.centered(s.v("x") / 2, s.v("y") / 2, s.v("z") / 2), BOX);
                    return o;
                }
                case "tube":
                    return tube(s, box, fullTurn(s.v("deltaphi")) ? "TGeoTube" : "TGeoTubeSeg");
                case "cutTube": {
                    final RObject o = tube(s, box, "TGeoCtub");
                    o.members.put("fNlow", unit(s.v("lowX"), s.v("lowY"), s.v("lowZ")));
                    o.members.put("fNhigh", unit(s.v("highX"), s.v("highY"), s.v("highZ")));
                    return o;
                }
                case "eltube": {
                    final RObject o = base("TGeoEltu", s.name(), box, ELTU | TUBE | BOX);
                    o.members.put("fRmin", s.v("dx") / 10);
                    o.members.put("fRmax", s.v("dy") / 10);
                    o.members.put("fDz", s.v("dz") / 10);
                    return o;
                }
                case "hype": {
                    final RObject o = base("TGeoHype", s.name(), box, HYPE | TUBE | BOX);
                    o.members.put("fRmin", s.v("rmin") / 10);
                    o.members.put("fRmax", s.v("rmax") / 10);
                    o.members.put("fDz", s.v("z") / 20);
                    final double tin = Math.tan(s.v("inst")), tout = Math.tan(s.v("outst"));
                    o.members.put("fStIn", deg(s.v("inst")));
                    o.members.put("fStOut", deg(s.v("outst")));
                    o.members.put("fTin", tin);
                    o.members.put("fTout", tout);
                    o.members.put("fTinsq", tin * tin);
                    o.members.put("fToutsq", tout * tout);
                    return o;
                }
                case "cone":
                    return cone(s, box);
                case "sphere":
                    return sphere(s.name(), box, s.v("rmin"), s.v("rmax"), s.v("startphi"), s.v("deltaphi"),
                        s.v("starttheta"), s.v("deltatheta"));
                case "orb":
                    return sphere(s.name(), box, 0, s.v("r"), 0, Units.TWOPI, 0, Math.PI);
                case "torus": {
                    final RObject o = base("TGeoTorus", s.name(), box, TORUS | BOX);
                    o.members.put("fR", s.v("rtor") / 10);
                    o.members.put("fRmin", s.v("rmin") / 10);
                    o.members.put("fRmax", s.v("rmax") / 10);
                    o.members.put("fPhi1", deg(s.v("startphi")));
                    o.members.put("fDphi", deg(s.v("deltaphi")));
                    return o;
                }
                case "trd": {
                    final boolean one = s.v("y1") == s.v("y2");
                    final RObject o = base(one ? "TGeoTrd1" : "TGeoTrd2", s.name(), box, (one ? TRD1 : TRD2) | BOX);
                    o.members.put("fDx1", s.v("x1") / 20);
                    o.members.put("fDx2", s.v("x2") / 20);
                    if (one) {
                        o.members.put("fDy", s.v("y1") / 20);
                    } else {
                        o.members.put("fDy1", s.v("y1") / 20);
                        o.members.put("fDy2", s.v("y2") / 20);
                    }
                    o.members.put("fDz", s.v("z") / 20);
                    return o;
                }
                case "para": {
                    final RObject o = base("TGeoPara", s.name(), box, PARA | BOX);
                    o.members.put("fX", s.v("x") / 20);
                    o.members.put("fY", s.v("y") / 20);
                    o.members.put("fZ", s.v("z") / 20);
                    o.members.put("fAlpha", deg(s.v("alpha")));
                    o.members.put("fTheta", deg(s.v("theta")));
                    o.members.put("fPhi", deg(s.v("phi")));
                    final double tth = Math.tan(s.v("theta"));
                    o.members.put("fTxy", Math.tan(s.v("alpha")));
                    o.members.put("fTxz", tth * Math.cos(s.v("phi")));
                    o.members.put("fTyz", tth * Math.sin(s.v("phi")));
                    return o;
                }
                case "trap":
                    return trap(s, box);
                case "arb8":
                    return arb8(s, box);
                case "polycone", "polyhedra":
                    return pcon(s, box, tag.equals("polyhedra"));
                case "genericPolycone":
                    return genericPolycone(s, box);
                case "xtru":
                    return xtru(s, box);
                case "paraboloid": {
                    final RObject o = base("TGeoParaboloid", s.name(), box, PARABOLOID | BOX);
                    final double rlo = s.v("rlo") / 10, rhi = s.v("rhi") / 10, dz = s.v("dz") / 10;
                    o.members.put("fRlo", rlo);
                    o.members.put("fRhi", rhi);
                    o.members.put("fDz", dz);
                    final double aa = 2 * dz / (rhi * rhi - rlo * rlo);
                    o.members.put("fA", aa);
                    o.members.put("fB", -dz - aa * rlo * rlo);
                    return o;
                }
                case "ellipsoid":
                    return ellipsoid(s, box);
                case "elcone":
                    return elcone(s, box);
                case "tet":
                    return tet(s, box);
                case "tessellated":
                    return tessellated(s.name(), box, ((Tessellated) s).vertices(), ((Tessellated) s).facets());
                case "union", "subtraction", "intersection":
                    return composite((BooleanSolid) s, box);
                case "scaledSolid": {
                    final ScaledSolid sc = (ScaledSolid) s;
                    final RObject inner = shape(sc.base());
                    return scaled(s.name(), box, inner, s.v("sx"), s.v("sy"), s.v("sz"));
                }
                default:
                    warn("solid " + s.name() + " (" + s.g4Class() + ") has no TGeo shape: written as its tessellated surface");
                    return fromMesh(s, box);
            }
        }

        static double[] unit(double x, double y, double z) {
            final double l = Math.sqrt(x * x + y * y + z * z);
            return l > 0 ? new double[] {x / l, y / l, z / l} : new double[] {0, 0, 0};
        }

        RObject tube(Solid s, BBox box, String cls) {
            final boolean seg = !cls.equals("TGeoTube");
            final long bits = switch (cls) {
                case "TGeoTube" -> TUBE | BOX;
                case "TGeoTubeSeg" -> TUBESEG | TUBE | BOX;
                default -> CTUB | TUBESEG | TUBE | BOX;
            };
            final RObject o = base(cls, s.name(), box, bits);
            o.members.put("fRmin", s.v("rmin") / 10);
            o.members.put("fRmax", s.v("rmax") / 10);
            o.members.put("fDz", s.v("z") / 20);
            if (seg) {
                phiSegment(o, s.v("startphi"), fullTurn(s.v("deltaphi")) ? Units.TWOPI : s.v("deltaphi"));
            }
            return o;
        }

        /** TGeoTubeSeg::SetTubsDimensions and InitTrigonometry: the limits in degrees, then their sines and cosines. */
        static void phiSegment(RObject o, double start, double delta) {
            final double p1 = phi1(start);
            double p2 = p1 + Math.toDegrees(delta);
            while (p2 <= p1) {
                p2 += 360;
            }
            o.members.put("fPhi1", p1);
            o.members.put("fPhi2", p2);
            final double r1 = Math.toRadians(p1), r2 = Math.toRadians(p2);
            o.members.put("fS1", Math.sin(r1));
            o.members.put("fC1", Math.cos(r1));
            o.members.put("fS2", Math.sin(r2));
            o.members.put("fC2", Math.cos(r2));
            o.members.put("fSm", Math.sin(0.5 * (r1 + r2)));
            o.members.put("fCm", Math.cos(0.5 * (r1 + r2)));
            o.members.put("fCdfi", Math.cos(0.5 * (r2 - r1)));
        }

        RObject cone(Solid s, BBox box) {
            final boolean seg = !fullTurn(s.v("deltaphi"));
            final RObject o = base(seg ? "TGeoConeSeg" : "TGeoCone", s.name(), box, (seg ? CONESEG : 0) | CONE | BOX);
            o.members.put("fDz", s.v("z") / 20);
            o.members.put("fRmin1", s.v("rmin1") / 10);
            o.members.put("fRmax1", s.v("rmax1") / 10);
            o.members.put("fRmin2", s.v("rmin2") / 10);
            o.members.put("fRmax2", s.v("rmax2") / 10);
            if (seg) {
                phiSegment(o, s.v("startphi"), s.v("deltaphi"));
            }
            return o;
        }

        RObject sphere(String name, BBox box, double rmin, double rmax, double phi, double dphi, double theta, double dtheta) {
            final double t1 = deg(theta), t2 = deg(theta + dtheta);
            final double p1 = fullTurn(dphi) ? 0 : phi1(phi);
            final double p2 = p1 + (fullTurn(dphi) ? 360 : deg(dphi));
            long bits = SPH | BOX;
            if (rmin > 0) {
                bits |= RSEG;
            }
            if (!fullTurn(dphi)) {
                bits |= PHISEG;
            }
            if (t1 > 0 || t2 < 180) {
                bits |= THETASEG;
            }
            final RObject o = base("TGeoSphere", name, box, bits);
            // TGeoSphere::SetNumberOfDivisions with the manager's 20 segments
            int nz = (int) (20 * (t2 - t1) / (p2 - p1)) + 1;
            o.members.put("fNz", Math.max(nz, 2));
            o.members.put("fNseg", 20);
            o.members.put("fRmin", rmin / 10);
            o.members.put("fRmax", rmax / 10);
            o.members.put("fTheta1", t1);
            o.members.put("fTheta2", t2);
            o.members.put("fPhi1", p1);
            o.members.put("fPhi2", p2);
            return o;
        }

        /** G4Trap as TGeoTrap: the same half lengths and angles, and the eight vertices TGeoTrap's constructor computes. */
        RObject trap(Solid s, BBox box) {
            final double dz = s.v("z") / 20, th = s.v("theta"), ph = s.v("phi");
            final double h1 = s.v("y1") / 20, bl1 = s.v("x1") / 20, tl1 = s.v("x2") / 20, a1 = s.v("alpha1");
            final double h2 = s.v("y2") / 20, bl2 = s.v("x3") / 20, tl2 = s.v("x4") / 20, a2 = s.v("alpha2");
            final RObject o = base("TGeoTrap", s.name(), box, CLOSED | ARB8 | BOX);
            final double tx = Math.tan(th) * Math.cos(ph), ty = Math.tan(th) * Math.sin(ph);
            final double ta1 = Math.tan(a1), ta2 = Math.tan(a2);
            final double[] xy = {
                -dz * tx - h1 * ta1 - bl1, -dz * ty - h1,
                -dz * tx + h1 * ta1 - tl1, -dz * ty + h1,
                -dz * tx + h1 * ta1 + tl1, -dz * ty + h1,
                -dz * tx - h1 * ta1 + bl1, -dz * ty - h1,
                dz * tx - h2 * ta2 - bl2, dz * ty - h2,
                dz * tx + h2 * ta2 - tl2, dz * ty + h2,
                dz * tx + h2 * ta2 + tl2, dz * ty + h2,
                dz * tx - h2 * ta2 + bl2, dz * ty - h2};
            o.members.put("fDz", dz);
            o.members.put("fXY", xy);
            o.members.put("fTheta", deg(th));
            o.members.put("fPhi", deg(ph));
            o.members.put("fH1", h1);
            o.members.put("fBl1", bl1);
            o.members.put("fTl1", tl1);
            o.members.put("fAlpha1", deg(a1));
            o.members.put("fH2", h2);
            o.members.put("fBl2", bl2);
            o.members.put("fTl2", tl2);
            o.members.put("fAlpha2", deg(a2));
            return o;
        }

        /** G4GenericTrap as TGeoArb8, whose ends must be listed clockwise (TGeoArb8::InsidePolygon). */
        RObject arb8(Solid s, BBox box) {
            final double[] xy = new double[16];
            for (int i = 0; i < 8; i++) {
                xy[i * 2] = s.v("v" + (i + 1) + "x") / 10;
                xy[i * 2 + 1] = s.v("v" + (i + 1) + "y") / 10;
            }
            double area = 0;
            for (int end = 0; end < 2 && area == 0; end++) {
                for (int i = 0; i < 4; i++) {
                    final int a = end * 4 + i, b = end * 4 + (i + 1) % 4;
                    area += xy[a * 2] * xy[b * 2 + 1] - xy[b * 2] * xy[a * 2 + 1];
                }
            }
            final double[] out = xy.clone();
            if (area > 0) {
                for (int end = 0; end < 2; end++) {
                    for (int i = 0; i < 4; i++) {
                        out[(end * 4 + i) * 2] = xy[(end * 4 + 3 - i) * 2];
                        out[(end * 4 + i) * 2 + 1] = xy[(end * 4 + 3 - i) * 2 + 1];
                    }
                }
            }
            final RObject o = base("TGeoArb8", s.name(), box, CLOSED | ARB8 | BOX);
            o.members.put("fDz", s.v("dz") / 10);
            o.members.put("fXY", out);
            return o;
        }

        RObject pcon(Solid s, BBox box, boolean pgon) {
            final Solid.Table t = s.table("zplane");
            final int n = t.size();
            final double[] rmin = new double[n], rmax = new double[n], z = new double[n];
            final boolean down = n > 1 && t.value(n - 1, 2) < t.value(0, 2);
            for (int i = 0; i < n; i++) {
                final int k = down ? n - 1 - i : i;
                rmin[i] = t.value(k, 0) / 10;
                rmax[i] = t.value(k, 1) / 10;
                z[i] = t.value(k, 2) / 10;
            }
            return pconShape(s.name(), box, pgon, pgon ? (int) Math.round(s.v("numsides")) : 0, s.v("startphi"),
                s.v("deltaphi"), rmin, rmax, z);
        }

        RObject pconShape(String name, BBox box, boolean pgon, int sides, double phi, double dphi, double[] rmin,
                          double[] rmax, double[] z) {
            final RObject o = base(pgon ? "TGeoPgon" : "TGeoPcon", name, box, CLOSED | (pgon ? PGON : 0) | PCON | BOX);
            o.members.put("fNz", z.length);
            // a polygon's sides turn with its first phi even over a full turn
            o.members.put("fPhi1", fullTurn(dphi) && !pgon ? 0.0 : phi1(phi));
            o.members.put("fDphi", fullTurn(dphi) ? 360.0 : deg(dphi));
            o.members.put("fRmin", rmin);
            o.members.put("fRmax", rmax);
            o.members.put("fZ", z);
            if (pgon) {
                o.members.put("fNedges", sides);
            }
            return o;
        }

        /** G4GenericPolycone has no TGeo class: a TGeoPcon when its profile allows, its tessellated surface otherwise. */
        RObject genericPolycone(Solid s, BBox box) {
            final List<double[]> planes = SolidConversions.polyconePlanes(s);
            if (planes == null) {
                warn("generic polycone " + s.name() + " has a profile no polycone follows: written as its tessellated surface");
                return fromMesh(s, box);
            }
            final double[] pz = new double[planes.size()], pmin = new double[planes.size()], pmax = new double[planes.size()];
            for (int i = 0; i < planes.size(); i++) {
                pz[i] = planes.get(i)[0] / 10;
                pmin[i] = planes.get(i)[1] / 10;
                pmax[i] = planes.get(i)[2] / 10;
            }
            return pconShape(s.name(), box, false, 0, s.v("startphi"), s.v("deltaphi"), pmin, pmax, pz);
        }

        /** G4ExtrudedSolid as TGeoXtru, its polygon clockwise as TGeoXtru::DefinePolygon expects it. */
        RObject xtru(Solid s, BBox box) {
            final Solid.Table v = s.table("twoDimVertex"), sec = s.table("section");
            final int nv = v.size(), nz = sec.size();
            double area = 0;
            for (int i = 0; i < nv; i++) {
                final int j = (i + 1) % nv;
                area += v.value(i, 0) * v.value(j, 1) - v.value(j, 0) * v.value(i, 1);
            }
            final double[] x = new double[nv], y = new double[nv];
            for (int i = 0; i < nv; i++) {
                final int k = area > 0 ? nv - 1 - i : i;
                x[i] = v.value(k, 0) / 10;
                y[i] = v.value(k, 1) / 10;
            }
            final double[] z = new double[nz], sc = new double[nz], x0 = new double[nz], y0 = new double[nz];
            for (int i = 0; i < nz; i++) {
                z[i] = sec.value(i, 0) / 10;
                x0[i] = sec.value(i, 1) / 10;
                y0[i] = sec.value(i, 2) / 10;
                sc[i] = sec.value(i, 3);
            }
            final RObject o = base("TGeoXtru", s.name(), box, XTRU | BOX);
            o.members.put("fNvert", nv);
            o.members.put("fNz", nz);
            o.members.put("fZcurrent", 0.0);
            o.members.put("fX", x);
            o.members.put("fY", y);
            o.members.put("fZ", z);
            o.members.put("fScale", sc);
            o.members.put("fX0", x0);
            o.members.put("fY0", y0);
            return o;
        }

        RObject scaled(String name, BBox box, RObject inner, double sx, double sy, double sz) {
            final RObject scale = named("TGeoScale", name + "_scale", "");
            scale.members.put("fBits", REGISTERED | SCALE);
            scale.members.put("fScale", new double[] {sx, sy, sz});
            matrices.add(scale);
            final RObject o = base("TGeoScaledShape", name, box, BOX);
            o.members.put("fShape", inner);
            o.members.put("fScale", scale);
            return o;
        }

        /** G4Ellipsoid as TGDMLParse builds it: a sphere of radius cz scaled in x and y, cut by a box in z. */
        RObject ellipsoid(Solid s, BBox box) {
            final double ax = s.v("ax"), by = s.v("by"), cz = s.v("cz");
            double zlo = s.v("zcut1"), zhi = s.v("zcut2");
            final boolean cut = !(zlo == 0 && zhi == 0) && (zlo > -cz || zhi < cz);
            final BBox whole = BBox.centered(ax, by, cz);
            final RObject sph = sphere(s.name() + "_sphere", BBox.centered(cz, cz, cz), 0, cz, 0, Units.TWOPI, 0, Math.PI);
            final RObject scl = scaled(cut ? s.name() + "_scaled" : s.name(), whole, sph, ax / cz, by / cz, 1);
            if (!cut) {
                return scl;
            }
            zlo = Math.max(zlo, -cz);
            zhi = Math.min(zhi, cz);
            final RObject cutBox = base("TGeoBBox", s.name() + "_cut", BBox.centered(ax, by, (zhi - zlo) / 2), BOX);
            return compositeOf(s.name(), box, "TGeoIntersection", scl, identity, cutBox,
                translation(0, 0, (zlo + zhi) / 20));
        }

        /** G4EllipticalCone: a cone of the same height scaled by its two slopes, as TGDMLParse::ElCone does. */
        RObject elcone(Solid s, BBox box) {
            final double dx = s.v("dx"), dy = s.v("dy"), h = s.v("zmax");
            final double zcut = Math.min(s.v("zcut"), h);
            final RObject cone = base("TGeoCone", s.name() + "_cone", BBox.centered(h + zcut, h + zcut, zcut), CONE | BOX);
            cone.members.put("fDz", zcut / 10);
            cone.members.put("fRmin1", 0.0);
            cone.members.put("fRmax1", (h + zcut) / 10);
            cone.members.put("fRmin2", 0.0);
            cone.members.put("fRmax2", (h - zcut) / 10);
            return scaled(s.name(), box, cone, dx, dy, 1);
        }

        RObject tet(Solid s, BBox box) {
            final SolidConversions.Surface t = SolidConversions.tet(s);
            return tessellated(s.name(), box, t.vertices(), t.facets());
        }

        RObject fromMesh(Solid s, BBox box) {
            final SolidConversions.Surface t = SolidConversions.surface(s, 2);
            return tessellated(s.name(), box, t.vertices(), t.facets());
        }

        /**
         * TGeoTessellated: its vertices (cm), its facets counter-clockwise
         * seen from outside (turned around if the solid lists them the other
         * way), and the number of edges ROOT counts for drawing.
         */
        RObject tessellated(String name, BBox box, List<double[]> vertices, List<int[]> facets) {
            warn("tessellated solids are drawn by ROOT 6.36 as they are, but navigated as their bounding box"
                + " (TGeoTessellated has no Contains of its own)");
            double volume = 0;
            for (int[] f : facets) {
                for (int k = 1; k + 1 < f.length; k++) {
                    final double[] a = vertices.get(f[0]), b = vertices.get(f[k]), c = vertices.get(f[k + 1]);
                    volume += a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0])
                        + a[2] * (b[0] * c[1] - b[1] * c[0]);
                }
            }
            final boolean flip = volume < 0;
            final RObject o = base("TGeoTessellated", name, box, BOX);
            final List<Object> vs = new ArrayList<>(vertices.size());
            for (double[] p : vertices) {
                final RObject v = obj("ROOT::Geom::Vertex_t");
                v.members.clear();
                v.members.put("fVec", new double[] {p[0] / 10, p[1] / 10, p[2] / 10});
                vs.add(v);
            }
            final List<Object> fs = new ArrayList<>(facets.size());
            final Set<Long> edges = new java.util.HashSet<>();
            for (int[] f0 : facets) {
                final int[] f = f0.clone();
                if (flip) {
                    for (int i = 0, j = f.length - 1; i < j; i++, j--) {
                        final int t = f[i];
                        f[i] = f[j];
                        f[j] = t;
                    }
                }
                final RObject fo = obj("TGeoFacet");
                fo.members.clear();
                fo.members.put("fIvert", new int[] {f[0], f[1], f[2], f.length > 3 ? f[3] : -1});
                fo.members.put("fNvert", f.length);
                fs.add(fo);
                for (int i = 0; i < f.length; i++) {
                    final long a = f[i], b = f[(i + 1) % f.length];
                    edges.add(Math.min(a, b) << 32 | Math.max(a, b));
                }
            }
            o.members.put("fNfacets", facets.size());
            o.members.put("fNvert", vertices.size());
            o.members.put("fNseg", edges.size());
            o.members.put("fClosedBody", true);
            o.members.put("fVertices", vs);
            o.members.put("fFacets", fs);
            return o;
        }

        /** A G4 boolean as TGeoCompositeShape: the operands' placements are the bool node's matrices. */
        RObject composite(BooleanSolid b, BBox box) {
            final RObject left = shape(b.first());
            final RObject right = shape(b.second());
            final RObject lm = b.firstMoved() ? matrix(b.firstTransform()) : identity;
            final RObject rm = matrix(b.secondTransform());
            final String node = switch (b.op()) {
                case UNION -> "TGeoUnion";
                case SUBTRACTION -> "TGeoSubtraction";
                case INTERSECTION -> "TGeoIntersection";
            };
            return compositeOf(b.name(), box, node, left, lm, right, rm);
        }

        RObject compositeOf(String name, BBox box, String nodeClass, RObject left, RObject lm, RObject right, RObject rm) {
            final RObject node = obj(nodeClass);
            node.members.put("fLeft", left);
            node.members.put("fRight", right);
            node.members.put("fLeftMat", lm);
            node.members.put("fRightMat", rm);
            final RObject o = base("TGeoCompositeShape", name, box, BOX);
            o.members.put("fNode", node);
            return o;
        }
    }
}
