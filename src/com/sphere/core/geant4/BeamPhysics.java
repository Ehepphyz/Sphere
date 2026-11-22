package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The physics of a beam crossing the design, worked out in a millisecond from the materials it meets: what a
 * physicist checks on paper before running Geant4, done again at every edit (G4editor's Physics Live).
 *
 * <p>Along the beam: the radiation and interaction lengths crossed, and the energy a minimum-ionizing particle loses
 * in each layer, by Bethe's formula with Sternheimer's density effect (its parameters computed the way
 * G4IonisParamMat computes them for a material without tabulated ones).
 *
 * <p>For an electron or a photon, the shower's mean longitudinal profile as GFLASH parameterizes it (Grindhammer and
 * Peters, hep-ex/0001020, the fast simulation of Geant4's G4GFlash), with its corrections for sampling calorimeters;
 * a photon first converts after an exponential depth of 9/7 X0. The lateral spread follows the Molière radius (90%
 * within one R_M, 99% within 3.5, PDG). Hence the energy kept, where the rest leaks, the sampling fraction and the
 * stochastic term of the resolution, 2.7% sqrt(d / f_samp) (Wigmans).
 *
 * <p>For a hadron, the depth in interaction lengths against the PDG's 95% containment length; for a muon, the energy
 * it loses on the way.
 */
public final class BeamPhysics {

    /** E_s = m_e c^2 sqrt(4 pi / alpha), MeV: the Molière radius is X0 E_s / E_c. */
    static final double ES = 21.2052;
    static final double ME = 0.51099895;
    /** 2 pi r_e^2 m_e c^2, MeV mm^2: Bethe's prefactor per electron of the material. */
    private static final double BETHE = 2 * Math.PI * Units.CLASSIC_ELECTR_RADIUS * Units.CLASSIC_ELECTR_RADIUS * ME;
    /** hbar c, MeV mm. */
    private static final double HBARC = 197.3269804e-12;
    /** Geant4's kGasThreshold: below 10 mg/cm3 a material is a gas the shower crosses without developing. */
    static final double DENSE = 0.01 * Units.G_PER_CM3;
    /** Mean depth of a photon's conversion, in X0. */
    private static final double CONVERSION = 9.0 / 7.0;
    private static final int SIDES = 16;

    private BeamPhysics() {
    }

    // ------------------------------------------------------------------
    // The beam
    // ------------------------------------------------------------------

    public enum Particle {
        ELECTRON("e-", ME, -1, Kind.EM), POSITRON("e+", ME, 1, Kind.EM), PHOTON("gamma", 0, 0, Kind.EM),
        MUON("mu-", 105.6583755, -1, Kind.MUON), ANTIMUON("mu+", 105.6583755, 1, Kind.MUON),
        PION("pi-", 139.57039, -1, Kind.HADRON), PIPLUS("pi+", 139.57039, 1, Kind.HADRON),
        KAON("kaon-", 493.677, -1, Kind.HADRON), KAONPLUS("kaon+", 493.677, 1, Kind.HADRON),
        PROTON("proton", 938.27208816, 1, Kind.HADRON), NEUTRON("neutron", 939.56542052, 0, Kind.HADRON),
        GEANTINO("geantino", 0, 0, Kind.NONE);

        public enum Kind { EM, MUON, HADRON, NONE }

        public final String g4Name;
        public final double mass;
        public final int charge;
        public final Kind kind;

        Particle(String g4Name, double mass, int charge, Kind kind) {
            this.g4Name = g4Name;
            this.mass = mass;
            this.charge = charge;
            this.kind = kind;
        }

        /** By Geant4's name, or a common one: electron, photon, muon, pion, p, n... Null when unknown. */
        public static Particle of(String name) {
            if (name == null) {
                return null;
            }
            final String n = name.trim().toLowerCase(Locale.ROOT);
            for (Particle p : values()) {
                if (p.g4Name.equals(n)) {
                    return p;
                }
            }
            return switch (n) {
                case "electron", "electrons", "e", "electron-" -> ELECTRON;
                case "positron", "positrons" -> POSITRON;
                case "photon", "photons", "gammas", "g", "y" -> PHOTON;
                case "muon", "muons", "mu" -> MUON;
                case "pion", "pions", "pi" -> PION;
                case "kaon", "kaons", "k-", "k" -> KAON;
                case "k+" -> KAONPLUS;
                case "p", "protons", "p+" -> PROTON;
                case "n", "neutrons" -> NEUTRON;
                default -> null;
            };
        }

        /** The name with Unicode signs, for display: e⁻, γ, μ⁻, π⁻... */
        public String symbol() {
            return switch (this) {
                case ELECTRON -> "e⁻";
                case POSITRON -> "e⁺";
                case PHOTON -> "γ";
                case MUON -> "μ⁻";
                case ANTIMUON -> "μ⁺";
                case PION -> "π⁻";
                case PIPLUS -> "π⁺";
                case KAON -> "K⁻";
                case KAONPLUS -> "K⁺";
                case PROTON -> "p";
                case NEUTRON -> "n";
                case GEANTINO -> "geantino";
            };
        }
    }

    /**
     * A beam: the design's particle gun, the one the Run panel fires and the exported C++ uses (the settings
     * gun.particle, gun.energy, gun.position, gun.direction). The energy is kinetic, as G4ParticleGun's.
     */
    public record Beam(Particle particle, double energy, Vec3 origin, Vec3 direction) {
        public Beam {
            particle = particle == null ? Particle.ELECTRON : particle;
            energy = energy > 0 ? energy : 1;
            direction = direction == null || direction.length() < 1e-12 ? Vec3.Z : direction.unit();
            origin = origin == null ? Vec3.ZERO : origin;
        }

        /** The design's gun; where it has no position, the Run panel's default: in the world, before the detector. */
        public static Beam of(Geometry g) {
            final Map<String, String> s = g.settings();
            Particle p = Particle.of(s.getOrDefault("gun.particle", "e-"));
            double e = 1000;
            try {
                e = Expr.eval(Inputs.normalize(s.getOrDefault("gun.energy", "1*GeV"), Units.Category.ENERGY, "mm"), g.scope());
            } catch (GeometryException | RuntimeException ignored) {
                // the default
            }
            Vec3 d = Vec3.Z;
            final String[] ds = s.getOrDefault("gun.direction", "0 0 1").trim().split("\\s+");
            if (ds.length == 3) {
                try {
                    d = new Vec3(Expr.eval(ds[0], g.scope()), Expr.eval(ds[1], g.scope()), Expr.eval(ds[2], g.scope()));
                } catch (GeometryException | RuntimeException ignored) {
                    d = Vec3.Z;
                }
            }
            if (d.length() < 1e-12) {
                d = Vec3.Z;
            }
            Vec3 o = null;
            final String[] ps = s.getOrDefault("gun.position", "").trim().split("\\s+");
            if (ps.length == 3) {
                try {
                    o = new Vec3(length(ps[0], g), length(ps[1], g), length(ps[2], g));
                } catch (GeometryException | RuntimeException ignored) {
                    o = null;
                }
            }
            return new Beam(p, e, o == null ? entry(g, d.unit()) : o, d);
        }

        private static double length(String text, Geometry g) throws GeometryException {
            return Expr.eval(Inputs.normalize(text, Units.Category.LENGTH, "mm"), g.scope());
        }

        /** Just inside the world, on the side the beam comes from, through the world's center. */
        public static Vec3 entry(Geometry g, Vec3 d) {
            if (g.world() == null || g.world().solid() == null || g.world().solid().error() != null) {
                return d.mul(-1000);
            }
            final BBox b = g.world().solid().extent();
            final Vec3 c = new Vec3((b.minX() + b.maxX()) / 2, (b.minY() + b.maxY()) / 2, (b.minZ() + b.maxZ()) / 2);
            double t = Double.MAX_VALUE;
            final double[] half = {(b.maxX() - b.minX()) / 2, (b.maxY() - b.minY()) / 2, (b.maxZ() - b.minZ()) / 2};
            for (int k = 0; k < 3; k++) {
                if (Math.abs(d.get(k)) > 1e-12) {
                    t = Math.min(t, half[k] / Math.abs(d.get(k)));
                }
            }
            return c.sub(d.mul(0.99 * t));
        }

        /** Writes the beam as the design's gun: the Run panel and the C++ export take it from there. */
        public void store(Geometry g) {
            final Map<String, String> s = g.settings();
            s.put("gun.particle", particle.g4Name);
            s.put("gun.energy", energy >= 1000 ? Units.num(energy / 1000) + "*GeV" : energy >= 1 ? Units.num(energy) + "*MeV"
                : Units.num(energy * 1000) + "*keV");
            s.put("gun.position", Units.num(origin.x()) + "*mm " + Units.num(origin.y()) + "*mm " + Units.num(origin.z()) + "*mm");
            s.put("gun.direction", Units.num(direction.x()) + " " + Units.num(direction.y()) + " " + Units.num(direction.z()));
        }

        public Beam with(Particle p, double e) {
            return new Beam(p, e, origin, direction);
        }

        public Beam aimed(Vec3 o, Vec3 d) {
            return new Beam(particle, energy, o, d);
        }

        /** "e- 10 GeV along +z". */
        public String describe() {
            return particle.g4Name + " " + Units.best(energy, Units.Category.ENERGY) + " " + toward(direction);
        }

        public static String toward(Vec3 d) {
            for (int k = 0; k < 3; k++) {
                if (Math.abs(Math.abs(d.get(k)) - 1) < 1e-9) {
                    return "along " + (d.get(k) > 0 ? "+" : "-") + "xyz".charAt(k);
                }
            }
            return String.format(Locale.ROOT, "toward (%.3g, %.3g, %.3g)", d.x(), d.y(), d.z());
        }
    }

    // ------------------------------------------------------------------
    // What the beam meets
    // ------------------------------------------------------------------

    /**
     * One stretch of the beam in one volume. x0 and lambda are its thickness in radiation and interaction lengths,
     * dedx a minimum-ionizing particle's loss (MeV/mm), depthX0 and depthLambda what lies before it from the gun,
     * edep the share of the beam's energy left in it (EM showers, muons).
     */
    public record Layer(String volume, String material, boolean sensitive, boolean dense, boolean active, double from,
                        double to, double x0, double lambda, double dedx, double depthX0, double depthLambda, double edep,
                        String placement, int copy) {
        public double thickness() {
            return to - from;
        }

        /** "Absorber 10 mm G4_Pb". */
        public String describe() {
            return volume + " " + Units.best(thickness(), Units.Category.LENGTH) + " " + material;
        }
    }

    /**
     * An electromagnetic shower. tMax is the depth of its maximum from the detector's front, in X0; t95 the depth
     * holding 95% of it; longitudinal, lateral and contained are fractions of the beam's energy (contained is their
     * product); upstream what went into material before the detector; edge the distance (mm) from the shower's axis to
     * the nearest side of the detector, at the maximum.
     */
    public record Shower(double criticalEnergy, double radiationLength, double moliere, double z, double tMax,
                         double alpha, double beta, double delay, double maxAt, double t95, double longitudinal,
                         double upstream, double lateral, double contained, double edge) {
        /** The half width holding 95% of the shower across, 1.75 R_M. */
        public double width95() {
            return 1.752 * moliere;
        }
    }

    /**
     * The sampling: mip is the share of a minimum-ionizing particle's loss left in the active layers, eHat GFLASH's
     * e/mip, em the share of an electromagnetic shower, stochastic the term a of sigma/E = a / sqrt(E/GeV).
     */
    public record Sampling(double mip, double eHat, double em, double activeThickness, double stochastic, boolean guessed,
                           String active) {
    }

    /** A hadronic shower, in interaction lengths: the PDG's maximum, attenuation and 95% containment length. */
    public record Hadronic(double tMax, double attenuation, double l95, double longitudinal, double lateral,
                           double contained, double interacts, double edge, double maxAt) {
    }

    /** A muon: what it loses by ionization (MeV), what of it in the active layers, the layer it stops in (-1: none). */
    public record Muon(double loss, double visible, int stopsIn, double stopAt) {
    }

    /**
     * What the beam crossed and what it does there. first and last bound the detector: the layers from the first
     * dense material to the last; depth, x0 and lambda measure it; structure tells it ("20 x [Absorber 10 mm G4_Pb,
     * Gap 5 mm G4_lAr]"), periodX0 is its repeated unit's depth and periods their count.
     */
    public record Report(Beam beam, List<Layer> layers, int first, int last, double depth, double x0, double lambda,
                         double mipLoss, double mipVisible, String structure, double periodX0, int periods, Shower shower,
                         Sampling sampling, Hadronic hadronic, Muon muon, List<String> notes, double millis) {

        public boolean hit() {
            return first >= 0;
        }

        /** The share of the beam's energy the detector keeps, or NaN when the particle makes no shower. */
        public double contained() {
            return shower != null ? shower.contained() : hadronic != null ? hadronic.contained() : Double.NaN;
        }

        public double longitudinal() {
            return shower != null ? shower.longitudinal() : hadronic != null ? hadronic.longitudinal() : Double.NaN;
        }

        public double lateral() {
            return shower != null ? shower.lateral() : hadronic != null ? hadronic.lateral() : Double.NaN;
        }

        /** Where the shower peaks, mm along the beam, or NaN. */
        public double maxAt() {
            return shower != null ? shower.maxAt() : hadronic != null ? hadronic.maxAt() : Double.NaN;
        }

        /** The layer at a distance along the beam, or null. */
        public Layer layerAt(double s) {
            for (Layer l : layers) {
                if (s >= l.from() && s < l.to()) {
                    return l;
                }
            }
            return null;
        }

        public double start() {
            return hit() ? layers.get(first).from() : 0;
        }

        public double end() {
            return hit() ? layers.get(last).to() : 0;
        }

        /** The report as console lines. */
        public List<String> lines() {
            final List<String> out = new ArrayList<>();
            out.add("Beam: " + beam.describe() + " from " + point(beam.origin()));
            if (!hit()) {
                out.addAll(notes);
                return out;
            }
            out.add(String.format(Locale.ROOT, "Detector: %s deep, %.2f X0, %.2f lambdaI: %s", Units.best(depth,
                Units.Category.LENGTH), x0, lambda, structure));
            if (shower != null) {
                out.add(String.format(Locale.ROOT, "Shower (GFLASH): max at %.1f X0, 95%% within %.1f X0; kept %.1f%% "
                        + "(%.1f%% in depth, %.1f%% across; R_M %s, E_c %.1f MeV)", shower.tMax(), shower.t95(),
                    100 * shower.contained(), 100 * shower.longitudinal(), 100 * shower.lateral(),
                    Units.best(shower.moliere(), Units.Category.LENGTH), shower.criticalEnergy()));
            }
            if (hadronic != null) {
                out.add(String.format(Locale.ROOT, "Hadronic shower: %.0f%% interact; max at %.2f lambdaI, 95%% within "
                        + "%.2f lambdaI; kept %.1f%% (%.1f%% in depth, %.1f%% across)", 100 * hadronic.interacts(),
                    hadronic.tMax(), hadronic.l95(), 100 * hadronic.contained(), 100 * hadronic.longitudinal(),
                    100 * hadronic.lateral()));
            }
            if (sampling != null) {
                out.add(String.format(Locale.ROOT, "Sampling: %.2f%% for a mip, %.2f%% for a shower (e/mip %.2f), "
                        + "stochastic term %.1f%%/sqrt(E/GeV)%s", 100 * sampling.mip(), 100 * sampling.em(),
                    sampling.eHat(), 100 * sampling.stochastic(), sampling.guessed() ? "  [active layers guessed: "
                        + sampling.active() + "]" : ""));
            }
            if (muon != null) {
                out.add(String.format(Locale.ROOT, "%s: loses %s by ionization, %s in the active layers%s",
                    beam.particle().g4Name, Units.best(muon.loss(), Units.Category.ENERGY), Units.best(muon.visible(),
                    Units.Category.ENERGY), muon.stopsIn() >= 0 ? ", stops in " + layers.get(muon.stopsIn()).volume() + " "
                    + Units.best(muon.stopAt() - start(), Units.Category.LENGTH) + " into the detector" : ""));
            }
            out.add(String.format(Locale.ROOT, "A minimum-ionizing particle loses %s, %s of it in the active layers",
                Units.best(mipLoss, Units.Category.ENERGY), Units.best(mipVisible, Units.Category.ENERGY)));
            out.addAll(notes);
            return out;
        }

        /** A paragraph for the paper describing the detector, the numbers rounded as a paper rounds them. */
        public String paragraph() {
            if (!hit()) {
                return "";
            }
            final StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.ROOT, "Along the beam the detector is %s, %s deep (%.1f X0, %.2f λI).",
                structure, Units.best(depth, Units.Category.LENGTH), x0, lambda));
            if (shower != null) {
                b.append(String.format(Locale.ROOT, " For %s %s, the GFLASH parameterization of the mean shower profile "
                        + "(Grindhammer and Peters) predicts its maximum at %.1f X0 and %.1f%% of the energy contained "
                        + "(%.1f%% longitudinally, %.1f%% laterally, Molière radius %s).",
                    Units.best(beam.energy(), Units.Category.ENERGY), plural(beam.particle()), shower.tMax(),
                    100 * shower.contained(), 100 * shower.longitudinal(), 100 * shower.lateral(),
                    Units.best(shower.moliere(), Units.Category.LENGTH)));
            }
            if (hadronic != null) {
                b.append(String.format(Locale.ROOT, " For %s %s, %.0f%% interact and about %.0f%% of the hadronic "
                        + "shower is contained (95%% would need %.1f λI).", Units.best(beam.energy(),
                    Units.Category.ENERGY), plural(beam.particle()), 100 * hadronic.interacts(), 100 * hadronic.contained(),
                    hadronic.l95()));
            }
            if (sampling != null) {
                b.append(String.format(Locale.ROOT, " The sampling fraction is %.2f%% for minimum-ionizing particles "
                        + "(%.2f%% for electromagnetic showers), giving a stochastic term of about %.0f%%/√E.",
                    100 * sampling.mip(), 100 * sampling.em(), 100 * sampling.stochastic()));
            }
            return b.toString();
        }

        private static String plural(Particle p) {
            return switch (p) {
                case ELECTRON -> "electrons";
                case POSITRON -> "positrons";
                case PHOTON -> "photons";
                case MUON, ANTIMUON -> "muons";
                case PION, PIPLUS -> "pions";
                case KAON, KAONPLUS -> "kaons";
                case PROTON -> "protons";
                case NEUTRON -> "neutrons";
                case GEANTINO -> "geantinos";
            };
        }

        private static String point(Vec3 p) {
            return "(" + Units.best(p.x(), Units.Category.LENGTH) + ", " + Units.best(p.y(), Units.Category.LENGTH) + ", "
                + Units.best(p.z(), Units.Category.LENGTH) + ")";
        }
    }

    // ------------------------------------------------------------------
    // The analysis
    // ------------------------------------------------------------------

    /** Everything the beam meets and does, in a millisecond or so. */
    public static Report analyze(Geometry g, Beam beam) {
        final long t0 = System.nanoTime();
        final double maxLen = g.world() == null || g.world().solid() == null || g.world().solid().error() != null ? 1e7
            : g.world().solid().extent().diagonal() * 2;
        final Tracer tracer = new Tracer(g);
        // The volumes crossed, a stretch per volume (the pieces of one volume that follow each other merged)
        final List<LogicalVolume> lvs = new ArrayList<>();
        final List<double[]> spans = new ArrayList<>();
        // the placement and copy number Geant4's touchable gives a step there: a replica's cell, a placement's own
        final List<PhysicalVolume> pvs = new ArrayList<>();
        final List<Integer> copies = new ArrayList<>();
        for (Tracer.Segment s : tracer.segments(beam.origin(), beam.direction(), maxLen)) {
            final int k = lvs.size() - 1;
            final int copy = s.pv() == null ? 0 : s.pv().isReplica() ? s.copy() : s.pv().copyNumber();
            if (k >= 0 && lvs.get(k) == s.lv() && pvs.get(k) == s.pv() && copies.get(k) == copy
                && Math.abs(spans.get(k)[1] - s.from()) < 1e-5) {
                spans.get(k)[1] = s.to();
            } else {
                lvs.add(s.lv());
                spans.add(new double[] {s.from(), s.to()});
                pvs.add(s.pv());
                copies.add(copy);
            }
        }
        final int n = lvs.size();
        final Material[] mats = new Material[n];
        final double[] d = new double[n], x0 = new double[n], lam = new double[n], dedx = new double[n];
        final double[] cumX0 = new double[n + 1], cumL = new double[n + 1];
        final boolean[] dense = new boolean[n], sens = new boolean[n];
        int first = -1, last = -1;
        for (int i = 0; i < n; i++) {
            final Material m = g.materials().material(lvs.get(i).material());
            mats[i] = m;
            d[i] = spans.get(i)[1] - spans.get(i)[0];
            if (m != null) {
                x0[i] = d[i] / m.radiationLength();
                lam[i] = d[i] / m.nuclearInteractionLength();
                dedx[i] = mipDedx(m);
                dense[i] = m.density() >= DENSE;
            }
            sens[i] = lvs.get(i).sensitive() != null && !lvs.get(i).sensitive().isBlank();
            cumX0[i + 1] = cumX0[i] + x0[i];
            cumL[i + 1] = cumL[i] + lam[i];
            if (dense[i]) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        final List<String> notes = new ArrayList<>();
        if (first < 0) {
            notes.add("The beam crosses no dense material: aim it at the detector.");
            return new Report(beam, layers(lvs, pvs, copies, mats, spans, sens, dense, new boolean[n], x0, lam, dedx, cumX0,
                cumL, new double[n]), -1, -1, 0, 0, 0, 0, 0, "", 0, 0, null, null, null, null, notes, ms(t0));
        }

        // The detector: sums and effective medium (thickness fractions f_i; 1/X0 = sum f_i/X0_i,
        // 1/R_M = (1/E_s) sum f_i E_ci/X0_i, E_c = E_s X0/R_M; Z weighted by mass)
        double depth = 0, sx0 = 0, slam = 0, mip = 0, ecSum = 0, rhoD = 0, rhoDZ = 0;
        for (int i = first; i <= last; i++) {
            depth += d[i];
            sx0 += x0[i];
            slam += lam[i];
            mip += d[i] * dedx[i];
            if (mats[i] != null && x0[i] > 0) {
                ecSum += d[i] * mats[i].criticalEnergy() / mats[i].radiationLength();
                rhoD += mats[i].density() * d[i];
                rhoDZ += mats[i].density() * d[i] * mats[i].zEffective();
            }
        }
        final double x0eff = sx0 > 0 ? depth / sx0 : Double.MAX_VALUE;
        final double ecEff = depth > 0 ? x0eff * ecSum / depth : 1;
        final double rmEff = ecEff > 0 ? ES * x0eff / ecEff : Double.MAX_VALUE;
        final double zEff = rhoD > 0 ? rhoDZ / rhoD : 1;
        final double lamEff = slam > 0 ? depth / slam : Double.MAX_VALUE;

        // Active layers: the sensitive ones; with none marked, the lightest dense material when it is clearly lighter
        // than the others (scintillator, liquid argon, silicon between absorber plates)
        final boolean[] active = new boolean[n];
        boolean anySensitive = false;
        for (int i = first; i <= last; i++) {
            anySensitive |= sens[i] && dense[i];
        }
        boolean guessed = false;
        String activeName = "";
        if (anySensitive) {
            for (int i = first; i <= last; i++) {
                active[i] = sens[i] && dense[i];
            }
        } else {
            final Map<String, Double> x0By = new LinkedHashMap<>();
            for (int i = first; i <= last; i++) {
                if (dense[i] && mats[i] != null) {
                    x0By.put(mats[i].name(), mats[i].radiationLength());
                }
            }
            if (x0By.size() >= 2) {
                String light = null;
                double hi = 0, lo = Double.MAX_VALUE;
                for (Map.Entry<String, Double> e : x0By.entrySet()) {
                    if (e.getValue() > hi) {
                        hi = e.getValue();
                        light = e.getKey();
                    }
                    lo = Math.min(lo, e.getValue());
                }
                if (hi > 3 * lo) {
                    for (int i = first; i <= last; i++) {
                        active[i] = dense[i] && mats[i] != null && mats[i].name().equals(light);
                    }
                    guessed = true;
                    activeName = light;
                }
            }
        }
        double dA = 0, dP = 0, mipA = 0, rhoDA = 0, rhoDZA = 0, rhoDP = 0, rhoDZP = 0;
        int nA = 0, nP = 0;
        for (int i = first; i <= last; i++) {
            if (!dense[i] || mats[i] == null) {
                continue;
            }
            final double r = mats[i].density() * d[i];
            if (active[i]) {
                dA += d[i];
                nA++;
                mipA += d[i] * dedx[i];
                rhoDA += r;
                rhoDZA += r * mats[i].zEffective();
                if (!guessed && activeName.isEmpty()) {
                    activeName = lvs.get(i).name();
                } else if (!guessed && !activeName.contains(lvs.get(i).name())) {
                    activeName += ", " + lvs.get(i).name();
                }
            } else {
                dP += d[i];
                nP++;
                rhoDP += r;
                rhoDZP += r * mats[i].zEffective();
            }
        }
        final boolean sampling = nA > 0 && nP > 0;
        final double eHat = sampling ? 1 / (1 + 0.007 * (rhoDZP / rhoDP - rhoDZA / rhoDA)) : 1;
        final double fMip = mip > 0 ? mipA / mip : 0;
        final double meanA = nA > 0 ? dA / nA : 0, meanP = nP > 0 ? dP / nP : 0;

        // The structure, told with its repeats
        final Structure st = structure(lvs, mats, d, x0, first, last);
        final double[] edep = new double[n];
        Shower shower = null;
        Hadronic hadronic = null;
        Muon muon = null;
        final Particle p = beam.particle();

        // below these energies a particle does not shower: a charged hadron stops by ionization (its Bragg peak), a
        // photon or an electron of a few MeV is absorbed or scattered, a slow neutron is moderated and captured
        final boolean slowHadron = p.kind == Particle.Kind.HADRON && beam.energy() < 1000;
        final boolean softEm = p.kind == Particle.Kind.EM && beam.energy() < 50;
        if (softEm) {
            notes.add(String.format(Locale.ROOT, "A %s %s does not shower: it is absorbed or scattered on the way. The depth"
                + " reads in X0 (%.2f here); a run in Geant4 tells what is deposited.", Units.best(beam.energy(),
                Units.Category.ENERGY), p.g4Name, sx0));
        } else if (slowHadron && p.charge == 0) {
            notes.add(String.format(Locale.ROOT, "Below a GeV a %s is slowed down and captured, not showered: the depth"
                + " reads in interaction lengths (%.2f here).", p.g4Name, slam));
        }
        if (p.kind == Particle.Kind.EM && !softEm) {
            // GFLASH's mean profile: homogeneous media, then the sampling corrections with F_S = X0eff / (d_a + d_p)
            final double lny = Math.log(Math.max(beam.energy() / ecEff, 1.0001));
            double tmax = lny - 0.858;
            double alpha = 0.21 + (0.492 + 2.38 / Math.max(zEff, 1)) * lny;
            if (sampling) {
                final double invFs = (meanA + meanP) / x0eff;
                tmax += -0.59 * invFs - 0.53 * (1 - eHat);
                alpha += -0.444 * invFs;
            }
            tmax = Math.max(tmax, 0.3);
            alpha = Math.max(alpha, 1.05);
            final double beta = (alpha - 1) / tmax;
            final double delay = p == Particle.PHOTON ? CONVERSION : 0;
            final double[] cdf = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                cdf[i] = profile(alpha, beta, delay, cumX0[i]);
            }
            final double upstream = cdf[first];
            final double longitudinal = cdf[last + 1] - cdf[first];
            // depth holding 95% of the shower, from the detector's front
            double lo = 0, hi = 400;
            for (int it = 0; it < 70; it++) {
                final double mid = (lo + hi) / 2;
                if (profile(alpha, beta, delay, cumX0[first] + mid) < 0.95) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            final double t95 = (lo + hi) / 2;
            // where the maximum is, along the beam, and how far the sides are from it
            final double maxX0 = cumX0[first] + tmax + delay;
            final double maxAt = along(spans, x0, cumX0, maxX0, first, last);
            final double[] side = sides(g, tracer, beam, spans, dense, first, last, maxAt, maxLen);
            double lateral = 0, edge = Double.MAX_VALUE;
            for (double r : side) {
                lateral += lateralFraction(r, rmEff);
                edge = Math.min(edge, r);
            }
            lateral /= side.length;
            // The energy left in each layer: the profile's share of its depth, weighted by what a charged particle loses
            // there per radiation length against the medium's mean, the active layers by e/mip; the detector keeps
            // what the profile gives it
            final double dedxEff = mip / depth;
            double sum = 0;
            for (int i = 0; i < n; i++) {
                final double dp = cdf[i + 1] - cdf[i];
                if (i < first || i > last || x0[i] <= 0) {
                    edep[i] = dp;
                    continue;
                }
                final double w = (d[i] / x0[i]) * dedx[i] / (x0eff * dedxEff);
                edep[i] = dp * w * (active[i] && sampling ? eHat : 1);
                sum += edep[i];
            }
            if (sum > 0) {
                for (int i = first; i <= last; i++) {
                    edep[i] *= longitudinal / sum * lateral;
                }
            }
            shower = new Shower(ecEff, x0eff, rmEff, zEff, tmax + delay, alpha, beta, delay, maxAt, t95, longitudinal,
                upstream, lateral, longitudinal * lateral, edge);
            hints(notes, shower, st, sx0);
        } else if (p.kind == Particle.Kind.HADRON && !slowHadron) {
            final double eGeV = Math.max(beam.energy() / 1000, 1);
            final double tmaxL = 0.2 * Math.log(eGeV) + 0.7;
            final double att = Math.pow(eGeV, 0.13);
            final double l95 = tmaxL + 2.5 * att;
            final double up = cumL[first];
            final double longitudinal = hadronCdf(up + slam, tmaxL, att) - hadronCdf(up, tmaxL, att);
            final double maxAt = alongLambda(spans, lam, cumL, up + tmaxL, first, last);
            final double[] side = sides(g, tracer, beam, spans, dense, first, last, maxAt, maxLen);
            double lateral = 0, edge = Double.MAX_VALUE;
            for (double r : side) {
                lateral += lateralFraction(r, lamEff / 1.752);
                edge = Math.min(edge, r);
            }
            lateral /= side.length;
            hadronic = new Hadronic(tmaxL, att, l95, longitudinal, lateral, longitudinal * lateral, 1 - Math.exp(-slam),
                edge, maxAt);
            // where the shower leaves its energy, layer by layer, from the same profile in interaction lengths
            for (int i = first; i <= last; i++) {
                edep[i] = (hadronCdf(cumL[i + 1], tmaxL, att) - hadronCdf(cumL[i], tmaxL, att)) * lateral;
            }
            if (longitudinal < 0.95) {
                notes.add(String.format(Locale.ROOT, "Holds %.0f%% of the %s shower in depth: 95%% needs %.1f λI, the "
                    + "detector has %.2f.", 100 * longitudinal, p.g4Name, l95, slam));
            }
            if (lateral < 0.95) {
                notes.add(String.format(Locale.ROOT, "Leaks %.0f%% at the sides: the nearest side is %s from the beam, "
                    + "95%% needs about one λI (%s) all around.", 100 * (1 - lateral), Units.best(edge,
                    Units.Category.LENGTH), Units.best(lamEff, Units.Category.LENGTH)));
            }
        } else if (p.kind == Particle.Kind.MUON || slowHadron && p.charge != 0) {
            // ionization along the way, Bethe's rate taken again as the particle slows: its range, its Bragg peak
            double t = beam.energy(), loss = 0, vis = 0, stopAt = Double.NaN;
            int stops = -1;
            for (int i = 0; i < n && stops < 0; i++) {
                if (mats[i] == null) {
                    continue;
                }
                double left = d[i];
                int guard = 0;
                while (left > 0 && guard++ < 2000) {
                    final double gamma = 1 + t / p.mass, bg = Math.sqrt(Math.max(gamma * gamma - 1, 1e-12));
                    final double rate = bethe(mats[i], p.mass, p.charge, bg);
                    if (rate <= 0) {
                        // below Bethe's reach (some hundred keV): the rest is spent within a hair, here
                        loss += t;
                        edep[i] += t / beam.energy();
                        if (active[i]) {
                            vis += t;
                        }
                        t = 0;
                        stops = i;
                        stopAt = spans.get(i)[1] - Math.max(0, left);
                        break;
                    }
                    final double step = Math.min(left, Math.max(0.05 * t / rate, 1e-3));
                    final double de = Math.min(t, rate * step);
                    t -= de;
                    loss += de;
                    edep[i] += de / beam.energy();
                    if (active[i]) {
                        vis += de;
                    }
                    left -= step;
                    if (t <= 1e-3) {
                        stops = i;
                        stopAt = spans.get(i)[1] - Math.max(0, left);
                        break;
                    }
                }
            }
            muon = new Muon(loss, vis, stops, stopAt);
            if (slowHadron) {
                // the nuclear interactions on the way, by the interaction lengths crossed before it stops
                double crossed = 0;
                for (int i = 0; i < n; i++) {
                    if (stops >= 0 && i > stops) {
                        break;
                    }
                    if (i == stops) {
                        final double f = d[i] > 0 ? (stopAt - spans.get(i)[0]) / d[i] : 0;
                        crossed += lam[i] * Math.max(0, Math.min(1, f));
                    } else {
                        crossed += lam[i];
                    }
                }
                notes.add(String.format(Locale.ROOT, "%s: %.0f %% of them interact with a nucleus before %s.", Units.best(
                    beam.energy(), Units.Category.ENERGY) + " " + p.g4Name + "s", 100 * (1 - Math.exp(-crossed)), stops >= 0
                    ? "they stop (their Bragg peak, " + Units.best(stopAt - spans.get(first)[0], Units.Category.LENGTH)
                    + " into the detector)" : "they leave"));
            }
            if (beam.energy() > 100_000) {
                notes.add("Above 100 GeV a muon also loses energy by bremsstrahlung and pair production, not counted here.");
            }
        }
        final Sampling smp = sampling ? new Sampling(fMip, eHat, emShare(edep, active, first, last, fMip, eHat), meanA,
            0.027 * Math.sqrt(meanA / Math.max(fMip, 1e-9)), guessed, activeName) : null;
        if (smp != null && smp.guessed()) {
            notes.add("The active layers are guessed (" + activeName + "): mark them sensitive to be sure.");
        }
        if (shower != null && shower.upstream() > 0.01) {
            notes.add(String.format(Locale.ROOT, "%.1f%% of the shower is spent before the detector (%.2f X0 upstream).",
                100 * shower.upstream(), cumX0[first]));
        }
        return new Report(beam, layers(lvs, pvs, copies, mats, spans, sens, dense, active, x0, lam, dedx, cumX0, cumL, edep), first,
            last, depth, sx0, slam, mip, mipA, st.text, st.periodX0, st.periods, shower, smp, hadronic, muon, notes, ms(t0));
    }

    /** The share of an electromagnetic shower the active layers take: from the layers' deposits, else from e/mip. */
    private static double emShare(double[] edep, boolean[] active, int first, int last, double fMip, double eHat) {
        double a = 0, all = 0;
        for (int i = first; i <= last; i++) {
            all += edep[i];
            if (active[i]) {
                a += edep[i];
            }
        }
        if (all > 0 && a > 0) {
            return a / all;
        }
        return eHat * fMip / (eHat * fMip + 1 - fMip);
    }

    private static void hints(List<String> notes, Shower s, Structure st, double detectorX0) {
        final double extra = s.t95() - detectorX0;
        if (s.longitudinal() < 0.95 && extra > 0.01) {
            if (st.periodX0 > 0 && st.periods >= 2) {
                final int more = (int) Math.ceil(extra / st.periodX0 - 1e-9);
                notes.add(String.format(Locale.ROOT, "Leaks %.1f%% at the back: %d more layer%s (%.1f X0) would hold 95%%.",
                    100 * (1 - s.longitudinal() - s.upstream()), more, more == 1 ? "" : "s", more * st.periodX0));
            } else {
                notes.add(String.format(Locale.ROOT, "Leaks %.1f%% at the back: %.1f X0 more would hold 95%%.",
                    100 * (1 - s.longitudinal() - s.upstream()), extra));
            }
        }
        if (s.lateral() < 0.95) {
            notes.add(String.format(Locale.ROOT, "Leaks %.1f%% at the sides: the nearest side is %s from the shower's axis; "
                    + "%s (1.75 R_M) all around would hold 95%%.", 100 * (1 - s.lateral()), Units.best(s.edge(),
                Units.Category.LENGTH), Units.best(s.width95(), Units.Category.LENGTH)));
        }
    }

    private static List<Layer> layers(List<LogicalVolume> lvs, List<PhysicalVolume> pvs, List<Integer> copies,
                                      Material[] mats, List<double[]> spans, boolean[] sens, boolean[] dense,
                                      boolean[] active, double[] x0, double[] lam, double[] dedx, double[] cumX0,
                                      double[] cumL, double[] edep) {
        final List<Layer> out = new ArrayList<>(lvs.size());
        for (int i = 0; i < lvs.size(); i++) {
            out.add(new Layer(lvs.get(i).name(), mats[i] == null ? lvs.get(i).material() : mats[i].name(), sens[i],
                dense[i], active[i], spans.get(i)[0], spans.get(i)[1], x0[i], lam[i], dedx[i], cumX0[i], cumL[i], edep[i],
                pvs.get(i) == null ? lvs.get(i).name() : pvs.get(i).name(), copies.get(i)));
        }
        return List.copyOf(out);
    }

    private static double ms(long t0) {
        return (System.nanoTime() - t0) / 1e6;
    }

    // ------------------------------------------------------------------
    // Shower shapes
    // ------------------------------------------------------------------

    /**
     * The share of an electromagnetic shower deposited before a depth t (X0 from the gun): GFLASH's gamma profile,
     * started after the conversion depth for a photon (an exponential of mean 9/7 X0).
     */
    static double profile(double alpha, double beta, double delay, double t) {
        if (t <= 0) {
            return 0;
        }
        if (delay <= 0) {
            return gammaP(alpha, beta * t);
        }
        final int steps = 64;
        final double h = t / steps;
        double s = 0;
        for (int i = 0; i <= steps; i++) {
            final double u = i * h;
            final double w = i == 0 || i == steps ? 0.5 : 1;
            s += w * Math.exp(-u / delay) / delay * gammaP(alpha, beta * (t - u));
        }
        return Math.min(1, s * h);
    }

    /**
     * The share of a hadronic shower before a depth T (interaction lengths), a ramp to the maximum then the
     * exponential tail of the PDG's attenuation length, so that T = t_max + 2.5 att holds 95%.
     */
    static double hadronCdf(double t, double tmax, double att) {
        final double atMax = 1 - 0.05 * Math.exp(2.5);
        if (t <= 0) {
            return 0;
        }
        if (t < tmax) {
            return atMax * t / tmax;
        }
        return 1 - (1 - atMax) * Math.exp(-(t - tmax) / att);
    }

    /**
     * The share of a shower within a distance r of its axis, in units of the Molière radius: 90% within one R_M
     * (a narrow core), then an exponential tail through 96% at 2 R_M and 99% at 3.5 R_M (PDG).
     */
    static double lateralFraction(double r, double rm) {
        if (!(r > 0) || !(rm > 0)) {
            return 0;
        }
        final double x = r / rm;
        if (x < 1) {
            return 0.936 * x * x / (x * x + 0.04);
        }
        return 1 - 0.2512 * Math.exp(-x / 1.0857);
    }

    /** The distance along the beam where the depth crossed reaches t (X0), within the detector. */
    private static double along(List<double[]> spans, double[] x0, double[] cum, double t, int first, int last) {
        for (int i = first; i <= last; i++) {
            if (t < cum[i + 1] && x0[i] > 0) {
                final double f = Math.max(0, (t - cum[i]) / x0[i]);
                return spans.get(i)[0] + f * (spans.get(i)[1] - spans.get(i)[0]);
            }
        }
        return spans.get(last)[1];
    }

    private static double alongLambda(List<double[]> spans, double[] lam, double[] cum, double t, int first, int last) {
        return along(spans, lam, cum, t, first, last);
    }

    /**
     * The distances from the shower's axis to the detector's sides, at a point along the beam (moved into the
     * nearest dense layer): rays across the beam, stopped where the material is no longer dense.
     */
    private static double[] sides(Geometry g, Tracer tracer, Beam beam, List<double[]> spans, boolean[] dense, int first,
                                  int last, double at, double maxLen) {
        double s = Math.max(spans.get(first)[0], Math.min(spans.get(last)[1], at));
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = first; i <= last; i++) {
            if (!dense[i]) {
                continue;
            }
            final double a = spans.get(i)[0], b = spans.get(i)[1];
            final double dist = s < a ? a - s : s > b ? s - b : 0;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        if (best >= 0 && bestDist > 0) {
            s = (spans.get(best)[0] + spans.get(best)[1]) / 2;
        }
        final Vec3 c = beam.origin().addScaled(beam.direction(), s);
        final Vec3 u = beam.direction().orthogonal().unit(), v = beam.direction().cross(u).unit();
        final double[] out = new double[SIDES];
        for (int k = 0; k < SIDES; k++) {
            final double th = 2 * Math.PI * k / SIDES;
            final Vec3 dir = u.mul(Math.cos(th)).add(v.mul(Math.sin(th)));
            double r = 0;
            boolean stopped = false;
            for (Tracer.Segment seg : tracer.segments(c, dir, maxLen)) {
                final Material m = g.materials().material(seg.lv().material());
                if (m == null || m.density() < DENSE) {
                    r = seg.from();
                    stopped = true;
                    break;
                }
                r = seg.to();
            }
            out[k] = stopped ? r : Math.max(r, maxLen);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // The structure, told with its repeats
    // ------------------------------------------------------------------

    private record Structure(String text, double periodX0, int periods) {
    }

    /** "Front 2 mm G4_Al + 20 x [Absorber 10 mm G4_Pb, Gap 5 mm G4_lAr]": the largest repeats first found, greedily. */
    private static Structure structure(List<LogicalVolume> lvs, Material[] mats, double[] d, double[] x0, int first,
                                       int last) {
        final int n = last - first + 1;
        final String[] key = new String[n];
        final String[] say = new String[n];
        for (int i = 0; i < n; i++) {
            final int j = first + i;
            final String mat = mats[j] == null ? lvs.get(j).material() : mats[j].name();
            key[i] = lvs.get(j).name() + "|" + mat + "|" + Math.round(d[j] * 1e4);
            say[i] = lvs.get(j).name() + " " + Units.best(d[j], Units.Category.LENGTH) + " " + mat;
        }
        final List<String> parts = new ArrayList<>();
        double periodX0 = 0;
        int periods = 0, bestCover = 0;
        int i = 0;
        while (i < n) {
            int bp = 1, bk = 1;
            for (int p = 1; p <= 8 && i + 2 * p <= n; p++) {
                int k = 1;
                while (i + (k + 1) * p <= n && same(key, i, i + k * p, p)) {
                    k++;
                }
                if (k >= 2 && p * k > bp * bk) {
                    bp = p;
                    bk = k;
                }
            }
            if (bk >= 2) {
                final StringBuilder b = new StringBuilder();
                double px0 = 0;
                for (int q = 0; q < bp; q++) {
                    b.append(q > 0 ? ", " : "").append(say[i + q]);
                    px0 += x0[first + i + q];
                }
                parts.add(bk + " × " + (bp > 1 ? "[" + b + "]" : b.toString()));
                if (bp * bk > bestCover) {
                    bestCover = bp * bk;
                    periodX0 = px0;
                    periods = bk;
                }
                i += bp * bk;
            } else {
                parts.add(say[i]);
                i++;
            }
        }
        return new Structure(parts.size() > 12 ? String.join(" + ", parts.subList(0, 12)) + " + ..." : String.join(" + ",
            parts), periodX0, periods);
    }

    private static boolean same(String[] key, int a, int b, int len) {
        for (int q = 0; q < len; q++) {
            if (!key[a + q].equals(key[b + q])) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Energy loss
    // ------------------------------------------------------------------

    /** The loss of a minimum-ionizing particle, MeV/mm: Bethe's formula for a muon at its minimum. */
    public static double mipDedx(Material m) {
        double best = Double.MAX_VALUE;
        for (double bg = 2.5; bg <= 5.0001; bg += 0.25) {
            best = Math.min(best, bethe(m, Particle.MUON.mass, 1, bg));
        }
        return best == Double.MAX_VALUE ? 0 : best;
    }

    /**
     * Bethe's mean rate of energy loss by ionization, MeV/mm, of a particle heavier than the electron (mass in MeV),
     * with the density effect of Sternheimer and Peierls.
     */
    public static double bethe(Material m, double mass, int charge, double betaGamma) {
        final double ne = m.electronDensity();
        if (ne <= 0 || betaGamma <= 0 || mass <= 0) {
            return 0;
        }
        final double bg2 = betaGamma * betaGamma, gamma = Math.sqrt(1 + bg2), beta2 = bg2 / (1 + bg2);
        final double r = ME / mass;
        final double wmax = 2 * ME * bg2 / (1 + 2 * gamma * r + r * r);
        final double i = meanExcitation(m);
        final double delta = densityEffect(m, i, Math.log10(betaGamma));
        final double bracket = Math.log(2 * ME * bg2 * wmax / (i * i)) - 2 * beta2 - delta;
        return Math.max(0, BETHE * ne * charge * charge / beta2 * bracket);
    }

    /**
     * The mean excitation energy, MeV: the material's own (NIST's), else Bragg's rule over its elements, ln I =
     * sum w Z/A ln I_Z / sum w Z/A, with the elements' NIST values (an empirical fit where there is none).
     */
    static double meanExcitation(Material m) {
        if (m.meanExcitation() > 0) {
            return m.meanExcitation() * 1e-6;
        }
        final List<Element> els = m.elements();
        final double[] w = m.massFractions();
        double num = 0, den = 0;
        for (int k = 0; k < els.size(); k++) {
            final Element e = els.get(k);
            double ie = NistDB.get().elementMeanExcitation(e.z);
            if (ie <= 0) {
                ie = e.z == 1 ? 19.2 : e.z < 13 ? 12.0 * e.z + 7.0 : 9.76 * e.z + 58.8 * Math.pow(e.z, -0.19);
            }
            final double c = w[k] * e.z / e.a;
            num += c * Math.log(ie);
            den += c;
        }
        return den > 0 ? Math.exp(num / den) * 1e-6 : 100e-6;
    }

    /**
     * Sternheimer's density correction at x = log10(beta gamma), its parameters as G4IonisParamMat computes them
     * for a material without tabulated ones (R.M. Sternheimer and R.F. Peierls, Phys. Rev. B 3 (1971) 3681).
     */
    static double densityEffect(Material m, double iMeV, double x) {
        final double plasma = HBARC * Math.sqrt(4 * Math.PI * m.electronDensity() * Units.CLASSIC_ELECTR_RADIUS);
        final double c = 1 + 2 * Math.log(iMeV / plasma);
        final boolean gas = m.state() == Material.State.GAS || (m.state() == Material.State.UNDEFINED && m.density() < DENSE);
        double x0, x1;
        if (!gas) {
            final boolean low = iMeV < 100e-6;
            x0 = c < (low ? 3.681 : 5.215) ? 0.2 : 0.326 * c - (low ? 1.0 : 1.5);
            x1 = low ? 2.0 : 3.0;
        } else {
            x1 = 4.0;
            if (c <= 10) {
                x0 = 1.6;
            } else if (c <= 10.5) {
                x0 = 1.7;
            } else if (c <= 11) {
                x0 = 1.8;
            } else if (c <= 11.5) {
                x0 = 1.9;
            } else if (c <= 12.25) {
                x0 = 2.0;
            } else if (c <= 13.804) {
                x0 = 2.0;
                x1 = 5.0;
            } else {
                x0 = 0.326 * c - 2.5;
                x1 = 5.0;
            }
        }
        if (x <= x0) {
            return 0;
        }
        final double twoLn10 = 2 * Math.log(10);
        if (x >= x1 || x1 <= x0) {
            return Math.max(0, twoLn10 * x - c);
        }
        final double a = (c - twoLn10 * x0) / Math.pow(x1 - x0, 3);
        return Math.max(0, twoLn10 * x - c + a * Math.pow(x1 - x, 3));
    }

    // ------------------------------------------------------------------
    // The incomplete gamma function
    // ------------------------------------------------------------------

    private static final double[] LANCZOS = {0.99999999999980993, 676.5203681218851, -1259.1392167224028,
        771.32342877765313, -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6,
        1.5056327351493116e-7};

    static double lnGamma(double x) {
        if (x < 0.5) {
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - lnGamma(1 - x);
        }
        final double y = x - 1;
        double a = LANCZOS[0];
        final double t = y + 7.5;
        for (int i = 1; i < LANCZOS.length; i++) {
            a += LANCZOS[i] / (y + i);
        }
        return 0.5 * Math.log(2 * Math.PI) + (y + 0.5) * Math.log(t) - t + Math.log(a);
    }

    /** The regularized lower incomplete gamma function P(a, x): a series below a + 1, a continued fraction above. */
    static double gammaP(double a, double x) {
        if (x <= 0) {
            return 0;
        }
        final double lead = -x + a * Math.log(x) - lnGamma(a);
        if (x < a + 1) {
            double ap = a, sum = 1 / a, del = sum;
            for (int k = 0; k < 1000; k++) {
                ap += 1;
                del *= x / ap;
                sum += del;
                if (Math.abs(del) < Math.abs(sum) * 1e-15) {
                    break;
                }
            }
            return Math.min(1, sum * Math.exp(lead));
        }
        final double tiny = 1e-300;
        double b = x + 1 - a, c = 1 / tiny, dd = 1 / b, h = dd;
        for (int k = 1; k < 1000; k++) {
            final double an = -k * (k - a);
            b += 2;
            dd = an * dd + b;
            if (Math.abs(dd) < tiny) {
                dd = tiny;
            }
            c = b + an / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            dd = 1 / dd;
            final double del = dd * c;
            h *= del;
            if (Math.abs(del - 1) < 1e-15) {
                break;
            }
        }
        return Math.max(0, 1 - Math.exp(lead) * h);
    }
}
