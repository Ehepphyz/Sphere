package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Geant4's NIST database: the 107 elements of G4NistElementBuilder and the
 * materials of G4NistMaterialBuilder, as G4NistManager::FindOrBuildMaterial
 * would build them.
 */
public final class NistDB {

    private static volatile NistDB instance;

    private final Element[] byZ = new Element[120];
    private final Map<String, Element> bySymbol = new LinkedHashMap<>();
    private final Map<String, Material> materials = new LinkedHashMap<>();
    private final double[] elementI = new double[120];

    private NistDB() {
        for (String line : NistTables.ELEMENTS.split("\n")) {
            final String[] f = line.trim().split("\\s+");
            if (f.length < 4) {
                continue;
            }
            final int z = Integer.parseInt(f[0]);
            final Element e = new Element(f[1], f[1], z, Double.parseDouble(f[2]), Double.parseDouble(f[3]), true);
            byZ[z] = e;
            bySymbol.put(f[1], e);
        }
        final MaterialTable resolver = MaterialTable.nistOnly(this);
        for (String chunk : NistTables.MATERIALS) {
            for (String line : chunk.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                final Material m = parse(line.trim());
                materials.put(m.name, m);
            }
        }
        for (Material m : materials.values()) {
            try {
                m.resolve(resolver);
            } catch (GeometryException e) {
                throw new IllegalStateException("NIST table: " + e.getMessage(), e);
            }
            // Elemental mean excitation energies, from the G4_<symbol> materials
            if (m.parts.size() == 1 && m.name.equals("G4_" + m.parts.get(0).ref()) && m.meanExcitation > 0) {
                final Element e = bySymbol.get(m.parts.get(0).ref());
                if (e != null) {
                    elementI[e.z] = m.meanExcitation;
                }
            }
        }
    }

    public static NistDB get() {
        NistDB db = instance;
        if (db == null) {
            synchronized (NistDB.class) {
                db = instance;
                if (db == null) {
                    db = new NistDB();
                    instance = db;
                }
            }
        }
        return db;
    }

    private Material parse(String line) {
        final String main = line.contains("|") ? line.substring(0, line.indexOf('|')).trim() : line;
        final String extra = line.contains("|") ? line.substring(line.indexOf('|') + 1).trim() : "";
        final String[] f = main.split("\\s+");
        final Material m = new Material(f[0], true);
        m.density = Double.parseDouble(f[1]) * Units.G_PER_CM3;
        m.meanExcitation = Double.parseDouble(f[2]);
        m.state = switch (f[3]) {
            case "L" -> Material.State.LIQUID;
            case "G" -> Material.State.GAS;
            default -> Material.State.SOLID;
        };
        final boolean atoms = f[4].equals("A");
        for (int i = 5; i < f.length; i++) {
            final String[] zc = f[i].split(":");
            final Element e = byZ[Integer.parseInt(zc[0])];
            m.parts.add(new Material.Part(e.symbol, Double.parseDouble(zc[1]), atoms));
        }
        if (m.state == Material.State.GAS) {
            m.temperature = 293.15;
            m.pressure = 1.0;
        }
        for (String kv : extra.split("\\s+")) {
            if (kv.startsWith("T=")) {
                m.temperature = Double.parseDouble(kv.substring(2));
            } else if (kv.startsWith("P=")) {
                m.pressure = Double.parseDouble(kv.substring(2));
            } else if (kv.startsWith("F=")) {
                m.formula = kv.substring(2).replace('_', ' ').trim();
            }
        }
        return m;
    }

    public Element element(int z) {
        return z > 0 && z < byZ.length ? byZ[z] : null;
    }

    /** By symbol ("Si"), or by Geant4 name ("G4_Si" maps to the element too). */
    public Element element(String symbolOrName) {
        if (symbolOrName == null) {
            return null;
        }
        Element e = bySymbol.get(symbolOrName);
        if (e == null && symbolOrName.startsWith("G4_")) {
            e = bySymbol.get(symbolOrName.substring(3));
        }
        return e;
    }

    public Material material(String name) {
        return materials.get(name);
    }

    public List<Material> materials() {
        return Collections.unmodifiableList(new ArrayList<>(materials.values()));
    }

    public List<Element> elements() {
        final List<Element> out = new ArrayList<>();
        for (Element e : byZ) {
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    /** NIST mean excitation energy of an element in eV, 0 when unknown. */
    public double elementMeanExcitation(int z) {
        return z > 0 && z < elementI.length ? elementI[z] : 0;
    }

    /** Materials whose name contains every word of the query, case-insensitive. */
    public List<Material> search(String query) {
        final String[] words = query == null ? new String[0] : query.toUpperCase(Locale.ROOT).trim().split("\\s+");
        final List<Material> out = new ArrayList<>();
        for (Material m : materials.values()) {
            final String n = m.name.toUpperCase(Locale.ROOT);
            boolean all = true;
            for (String w : words) {
                if (!w.isEmpty() && !n.contains(w)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                out.add(m);
            }
        }
        return out;
    }
}
