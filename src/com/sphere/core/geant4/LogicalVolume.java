package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * G4LogicalVolume: a solid, a material, how it is drawn, whether it is a
 * sensitive detector, and the volumes placed inside it.
 */
public final class LogicalVolume {

    String name;
    Solid solid;
    String material;
    final List<PhysicalVolume> daughters = new ArrayList<>();

    // Visualisation (G4VisAttributes), carried in GDML as auxiliary tags
    int color = 0xFF9AA7B8;  // ARGB
    boolean visible = true;
    boolean wireframe;
    /** Sensitive detector name (GDML auxiliary "SensDet"), or null. */
    String sensitive;
    /** Other auxiliary tags, kept as they came. */
    final Map<String, String> aux = new LinkedHashMap<>();
    /** The auxunit of those that have one, e.g. a StepLimit in mm. */
    final Map<String, String> auxUnits = new LinkedHashMap<>();
    /** What else a GDML volume held that the editor does not model (an opticalsurface...), kept as written. */
    final List<String> extras = new ArrayList<>();

    private transient DaughterIndex index;

    public LogicalVolume(String name, Solid solid, String material) {
        this.name = name;
        this.solid = solid;
        this.material = material;
    }

    public String name() {
        return name;
    }

    public Solid solid() {
        return solid;
    }

    public String material() {
        return material;
    }

    public List<PhysicalVolume> daughters() {
        return Collections.unmodifiableList(daughters);
    }

    public int color() {
        return color;
    }

    public boolean visible() {
        return visible;
    }

    public boolean wireframe() {
        return wireframe;
    }

    public String sensitive() {
        return sensitive;
    }

    public Map<String, String> aux() {
        return aux;
    }

    public Map<String, String> auxUnits() {
        return auxUnits;
    }

    public void setColor(int argb) {
        this.color = argb;
    }

    public void setVisible(boolean v) {
        this.visible = v;
    }

    public void setWireframe(boolean w) {
        this.wireframe = w;
    }

    public void setSensitive(String sd) {
        this.sensitive = sd == null || sd.isBlank() ? null : sd.trim();
    }

    public void setMaterial(String m) {
        this.material = m;
    }

    public void setSolid(Solid s) {
        this.solid = s;
        invalidate();
    }

    /** Alpha of the colour, 0..1. */
    public double alpha() {
        return ((color >>> 24) & 0xFF) / 255.0;
    }

    void invalidate() {
        index = null;
    }

    /** The acceleration structure over daughters' boxes, built on first use. */
    DaughterIndex index() {
        DaughterIndex i = index;
        if (i == null) {
            i = new DaughterIndex(this);
            index = i;
        }
        return i;
    }

    /** True when the volume is a descendant of this one (or this one). */
    public boolean contains(LogicalVolume other) {
        return contains(other, new HashSet<>());
    }

    // Each volume looked into once: shared volumes make the unrolled tree exponential
    private boolean contains(LogicalVolume other, Set<LogicalVolume> seen) {
        if (other == this) {
            return true;
        }
        if (!seen.add(this)) {
            return false;
        }
        for (PhysicalVolume pv : daughters) {
            if (pv.logical.contains(other, seen)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return name;
    }
}
