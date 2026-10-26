package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A material: either one of Geant4's NIST materials (G4NistManager builds it
 * from its name) or one defined in the document, by atom counts or by mass
 * fractions of elements and other materials, as G4Material and GDML allow.
 *
 * The derived quantities are G4Material's: atoms per volume, the radiation
 * length from Tsai's formula (G4Material::ComputeRadiationLength) and the
 * nuclear interaction length (G4Material::ComputeNuclearInterLength). The
 * Moliere radius and critical energy, which Geant4 does not keep, follow the
 * PDG's rules for mixtures.
 */
public final class Material {

    public enum State {
        SOLID("solid"), LIQUID("liquid"), GAS("gas"), UNDEFINED("undefined");

        public final String gdml;

        State(String g) {
            this.gdml = g;
        }

        public static State of(String s) {
            if (s == null) {
                return UNDEFINED;
            }
            return switch (s.trim().toLowerCase()) {
                case "solid", "s" -> SOLID;
                case "liquid", "l" -> LIQUID;
                case "gas", "g" -> GAS;
                default -> UNDEFINED;
            };
        }
    }

    /** One ingredient of a defined material: an element or a material, by count or by fraction. */
    public record Part(String ref, double amount, boolean byAtoms) {
    }

    final String name;
    final boolean nist;
    double density;          // internal units
    State state = State.SOLID;
    double temperature = 293.15;           // kelvin (NTP)
    double pressure = 1.0;                 // atmospheres
    double meanExcitation;                 // eV, 0 when Geant4 computes it
    String formula = "";
    final List<Part> parts = new ArrayList<>();
    /** Optical properties (GDML property): name, e.g. RINDEX, to the matrix or constant giving it. */
    final Map<String, String> properties = new LinkedHashMap<>();

    // Derived, filled by resolve()
    private List<Element> elements = List.of();
    private double[] massFractions = new double[0];
    private double[] atomsPerVolume = new double[0];
    private double radLength, nuclLength, electronDensity, zEff;

    Material(String name, boolean nist) {
        this.name = name;
        this.nist = nist;
    }

    public static Material define(String name, double densityGPerCm3) {
        final Material m = new Material(name, false);
        m.density = densityGPerCm3 * Units.G_PER_CM3;
        return m;
    }

    public String name() {
        return name;
    }

    public boolean isNist() {
        return nist;
    }

    public double density() {
        return density;
    }

    public double densityGcm3() {
        return density / Units.G_PER_CM3;
    }

    public State state() {
        return state;
    }

    public double temperature() {
        return temperature;
    }

    public double pressure() {
        return pressure;
    }

    public double meanExcitation() {
        return meanExcitation;
    }

    public String formula() {
        return formula;
    }

    public List<Part> parts() {
        return Collections.unmodifiableList(parts);
    }

    public Map<String, String> properties() {
        return properties;
    }

    public void setDensity(double internal) {
        this.density = internal;
    }

    public void setState(State s) {
        this.state = s;
    }

    public void setTemperature(double k) {
        this.temperature = k;
    }

    public void setPressure(double atm) {
        this.pressure = atm;
    }

    public void setMeanExcitation(double ev) {
        this.meanExcitation = ev;
    }

    public void clearParts() {
        parts.clear();
    }

    public void addPart(String ref, double amount, boolean byAtoms) {
        parts.add(new Part(ref, amount, byAtoms));
    }

    public List<Element> elements() {
        return elements;
    }

    public double[] massFractions() {
        return massFractions.clone();
    }

    /** Atoms of each element per mm^3. */
    public double[] atomsPerVolume() {
        return atomsPerVolume.clone();
    }

    /** G4Material::GetRadlen, in mm. */
    public double radiationLength() {
        return radLength;
    }

    /** G4Material::GetNuclearInterLength, in mm. */
    public double nuclearInteractionLength() {
        return nuclLength;
    }

    /** Electrons per mm^3. */
    public double electronDensity() {
        return electronDensity;
    }

    /** Electron-weighted mean atomic number. */
    public double zEffective() {
        return zEff;
    }

    /**
     * Resolves the parts into elements and mass fractions, then computes the
     * derived quantities. Material parts are expanded into their elements,
     * which is what G4Material::AddMaterial does.
     */
    void resolve(MaterialTable table) throws GeometryException {
        final Map<Element, Double> frac = new LinkedHashMap<>();
        final boolean atoms = !parts.isEmpty() && parts.get(0).byAtoms();
        double total = 0;
        if (atoms) {
            // By atom count: mass fraction ∝ count × A
            for (Part p : parts) {
                final Element e = table.element(p.ref());
                if (e == null) {
                    throw new GeometryException("material " + name + ": unknown element " + p.ref());
                }
                final double w = p.amount() * e.a;
                frac.merge(e, w, Double::sum);
                total += w;
            }
        } else {
            for (Part p : parts) {
                final Element e = table.element(p.ref());
                if (e != null) {
                    frac.merge(e, p.amount(), Double::sum);
                    total += p.amount();
                    continue;
                }
                final Material m = table.material(p.ref());
                if (m == null || m == this) {
                    throw new GeometryException("material " + name + ": unknown component " + p.ref());
                }
                if (m.elements.isEmpty()) {
                    m.resolve(table);
                }
                for (int i = 0; i < m.elements.size(); i++) {
                    frac.merge(m.elements.get(i), p.amount() * m.massFractions[i], Double::sum);
                }
                total += p.amount();
            }
        }
        if (frac.isEmpty() || total <= 0) {
            throw new GeometryException("material " + name + " has no components");
        }
        if (density <= 0) {
            throw new GeometryException("material " + name + ": density must be positive");
        }
        elements = new ArrayList<>(frac.keySet());
        massFractions = new double[elements.size()];
        atomsPerVolume = new double[elements.size()];
        int i = 0;
        for (Map.Entry<Element, Double> en : frac.entrySet()) {
            massFractions[i++] = en.getValue() / total;
        }
        compute();
    }

    private void compute() {
        double radinv = 0, nilinv = 0, ne = 0, zw = 0;
        // CLHEP: amu*cm2/(35 g); amu = 1 g/mole / Avogadro
        final double lambda1 = Units.AMU * Units.CM * Units.CM / (35.0 * Units.GRAM);
        for (int i = 0; i < elements.size(); i++) {
            final Element e = elements.get(i);
            final double aInternal = e.a * Units.GRAM / Units.MOLE;
            atomsPerVolume[i] = Units.AVOGADRO * density * massFractions[i] / aInternal;
            radinv += atomsPerVolume[i] * e.radTsai;
            nilinv += atomsPerVolume[i] * (e.z == 1 ? e.n : Math.pow(e.n, 2.0 / 3.0));
            ne += atomsPerVolume[i] * e.z;
            zw += atomsPerVolume[i] * e.z * e.z;
        }
        radLength = radinv <= 0 ? Double.MAX_VALUE : 1. / radinv;
        nilinv *= lambda1;
        nuclLength = 1.e+20 * Units.METER;
        if (nuclLength * nilinv > 1.0) {
            nuclLength = 1.0 / nilinv;
        }
        electronDensity = ne;
        zEff = ne > 0 ? zw / ne : 0;
    }

    /** Critical energy (MeV) of an element by the PDG's fits to Rossi's definition. */
    static double criticalEnergy(int z, State state) {
        return state == State.GAS ? 710. / (z + 0.92) : 610. / (z + 1.24);
    }

    /** Moliere radius in mm, by the PDG mixture rule 1/R_M = (1/E_s) Σ w_j E_cj / X_j. */
    public double moliereRadius() {
        final double es = 21.2052; // MeV
        double s = 0;
        for (int i = 0; i < elements.size(); i++) {
            final Element e = elements.get(i);
            // Radiation length of the pure element at this material's density
            final double x0 = 1.0 / (Units.AVOGADRO * density / (e.a * Units.GRAM / Units.MOLE) * e.radTsai);
            s += massFractions[i] * criticalEnergy(e.z, state) / x0;
        }
        return s > 0 ? es / s : Double.MAX_VALUE;
    }

    /** Critical energy of the material in MeV, from X0 and the Moliere radius. */
    public double criticalEnergy() {
        final double rm = moliereRadius();
        return rm > 0 && rm < Double.MAX_VALUE ? 21.2052 * radLength / rm : 0;
    }

    /** Short summary of the derived quantities. */
    public String summary() {
        return name + ": " + Units.best(density, Units.Category.DENSITY)
            + ", X0 = " + Units.best(radLength, Units.Category.LENGTH)
            + ", λI = " + Units.best(nuclLength, Units.Category.LENGTH)
            + ", RM = " + Units.best(moliereRadius(), Units.Category.LENGTH)
            + ", Ec = " + Units.num(criticalEnergy()) + " MeV";
    }

    @Override
    public String toString() {
        return name;
    }
}
