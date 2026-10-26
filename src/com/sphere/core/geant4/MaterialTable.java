package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The materials and elements a document can name: those it defines itself,
 * then Geant4's NIST ones, the way G4GDMLReadMaterials falls back on
 * G4NistManager for a reference it does not define.
 */
public final class MaterialTable {

    private final NistDB nist;
    private final Map<String, Element> elements = new LinkedHashMap<>();
    private final Map<String, Material> materials = new LinkedHashMap<>();

    private MaterialTable(NistDB nist) {
        this.nist = nist;
    }

    public MaterialTable() {
        this(NistDB.get());
    }

    static MaterialTable nistOnly(NistDB db) {
        return new MaterialTable(db);
    }

    public Element element(String name) {
        final Element e = elements.get(name);
        return e != null ? e : nist.element(name);
    }

    public Material material(String name) {
        if (name == null) {
            return null;
        }
        final Material m = materials.get(name);
        return m != null ? m : nist.material(name);
    }

    public boolean isDefined(String name) {
        return materials.containsKey(name);
    }

    public List<Material> defined() {
        return new ArrayList<>(materials.values());
    }

    public List<Element> definedElements() {
        return new ArrayList<>(elements.values());
    }

    public void defineElement(Element e) {
        elements.put(e.name, e);
    }

    /** Adds or replaces a document material and computes its properties. */
    public Material define(Material m) throws GeometryException {
        if (m.isNist()) {
            throw new GeometryException(m.name() + " is a NIST material; define a new name");
        }
        materials.put(m.name(), m);
        m.resolve(this);
        return m;
    }

    public void remove(String name) {
        materials.remove(name);
    }

    public void rename(String from, String to) {
        final Material m = materials.remove(from);
        if (m != null) {
            final Material r = new Material(to, false);
            r.density = m.density;
            r.state = m.state;
            r.temperature = m.temperature;
            r.pressure = m.pressure;
            r.meanExcitation = m.meanExcitation;
            r.formula = m.formula;
            r.parts.addAll(m.parts);
            materials.put(to, r);
            try {
                r.resolve(this);
            } catch (GeometryException ignored) {
                // left unresolved; reported when used
            }
        }
    }

    /** Recomputes every document material, after an element or a component changed. */
    public List<String> resolveAll() {
        final List<String> errors = new ArrayList<>();
        for (Material m : materials.values()) {
            try {
                m.resolve(this);
            } catch (GeometryException e) {
                errors.add(e.getMessage());
            }
        }
        return errors;
    }

    public NistDB nist() {
        return nist;
    }

    public void clear() {
        elements.clear();
        materials.clear();
    }
}
