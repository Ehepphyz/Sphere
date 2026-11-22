package com.sphere.core.geantbackend;

import com.sphere.core.geant4.BeamPhysics;
import com.sphere.core.geant4.Geometry;
import com.sphere.core.geant4.LogicalVolume;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Physics Live's prediction against what Geant4 measured, for the same beam in the same design: the energy kept,
 * each volume's energy, the sampling fraction, the resolution, and the profile along a volume's copies when the run
 * has one (geant4-bridge scores each copy number a step was in). Each line carries the run's statistical
 * uncertainty, so that a difference reads as noise or as real.
 *
 * <p>What is compared is energy deposited. A hadron shower hides part of its energy (nuclear binding, neutrinos): its
 * prediction is the shower kept times what a non-compensating calorimeter sees of it, the electromagnetic fraction
 * f_em = 1 - (E / 1 GeV)^(k - 1) with k = 0.82, plus 65% of the rest (Wigmans' textbook figures). A muon's prediction
 * is its mean ionization loss, of which the delta rays leaving thin layers take a little.
 */
public final class RunComparison {

    private RunComparison() {
    }

    /** One quantity, predicted and measured, the measurement's standard error, in a unit. */
    public record Line(String what, double predicted, double measured, double error, String unit) {
        /** The difference in standard errors of the run. */
        public double pull() {
            return error > 0 ? (measured - predicted) / error : Double.NaN;
        }

        public double relative() {
            return predicted != 0 ? (measured - predicted) / Math.abs(predicted) : Double.NaN;
        }

        /** Within two standard errors, or within 5% of the prediction: as close as a parameterization reads. */
        public boolean agrees() {
            return Math.abs(measured - predicted) <= Math.max(2 * error, 0.05 * Math.abs(predicted));
        }

        /** "Energy kept: Geant4 94.9 ± 0.28 %, Physics Live 97.5 % (-2.6 %)". */
        public String describe() {
            final String u = unit.isEmpty() ? "" : " " + unit;
            return String.format(Locale.ROOT, "%s: Geant4 %s ± %s%s, Physics Live %s%s (%+.1f %%)", what, fmt(measured),
                fmtError(error), u, fmt(predicted), u, 100 * relative());
        }
    }

    /** One volume's energy along its copies, in the copy numbers' order: measured, its error, predicted (MeV per event). */
    public record Profile(String volume, int[] copies, double[] measured, double[] error, double[] predicted) {
    }

    /** The comparison: the run's size, then the lines, the profiles and what to make of them. */
    public record Result(int events, String particle, double energy, String physics, Line kept, List<Line> volumes,
                         Line sampling, Line resolution, List<Profile> profiles, List<String> notes) {
        public List<String> lines() {
            final List<String> out = new ArrayList<>();
            out.add(String.format(Locale.ROOT, "Geant4 against Physics Live: %d %s at %s%s", events, particle,
                fmtEnergy(energy), physics.isEmpty() ? "" : " (" + physics + ")"));
            if (kept != null) {
                out.add("  " + kept.describe());
            }
            if (sampling != null) {
                out.add("  " + sampling.describe());
            }
            if (resolution != null) {
                out.add("  " + resolution.describe());
            }
            for (Line l : volumes) {
                out.add("  " + l.describe());
            }
            for (Profile p : profiles) {
                out.add("  " + p.volume() + " along its " + p.copies().length + " copies: measured " + join(p.measured())
                    + " MeV, predicted " + join(p.predicted()));
            }
            for (String n : notes) {
                out.add("  " + n);
            }
            return out;
        }
    }

    /** What a calorimeter sees of a hadron shower's energy: f_em, and 65% of the rest. */
    public static double hadronVisible(double energyMeV) {
        final double e = Math.max(1, energyMeV / 1000);
        final double fem = 1 - Math.pow(e, 0.82 - 1);
        return fem + 0.65 * (1 - fem);
    }

    /** The prediction for a run's answer, or null when the run is of another particle or energy than the report's beam. */
    public static Result compare(BeamPhysics.Report r, Map<String, Object> run, Geometry g) {
        final int n = (int) G4Json.num(run, "events", 0);
        final double e = G4Json.num(run, "energy_MeV", 0);
        final String particle = G4Json.str(run, "particle", "");
        final BeamPhysics.Particle p = BeamPhysics.Particle.of(particle);
        if (r == null || n <= 0 || p != r.beam().particle() || Math.abs(e - r.beam().energy()) > 1e-6 * Math.max(1, e)) {
            return null;
        }
        final double seen = switch (p.kind) {
            case EM, MUON -> 1;
            case HADRON -> hadronVisible(e);
            case NONE -> 0;
        };
        final double sqrtN = Math.sqrt(n);
        // predicted, by volume and by copy (MeV per event)
        final Map<String, Double> predicted = new LinkedHashMap<>();
        final Map<String, Map<Integer, Double>> byCopy = new LinkedHashMap<>();
        double active = 0;
        for (BeamPhysics.Layer l : r.layers()) {
            final double mev = l.edep() * e * seen;
            predicted.merge(l.volume(), mev, Double::sum);
            byCopy.computeIfAbsent(l.volume(), k -> new LinkedHashMap<>()).merge(l.copy(), mev, Double::sum);
            if (l.active()) {
                active += mev;
            }
        }
        final Map<String, Object> edep = G4Json.obj(run, "edep");
        final Set<String> names = new LinkedHashSet<>(edep.keySet());
        names.addAll(predicted.keySet());
        final List<Line> volumes = new ArrayList<>();
        double keptP = 0, keptM = 0, keptVar = 0, sensM = 0, sensVar = 0, sensP = 0;
        int sensitive = 0;
        String only = null;
        for (String name : names) {
            final Object v = edep.get(name);
            final double m = v == null ? 0 : G4Json.num(v, "mean_MeV", 0), rms = v == null ? 0 : G4Json.num(v, "rms_MeV", 0);
            final double pr = predicted.getOrDefault(name, 0.0);
            final LogicalVolume lv = g.volume(name);
            final boolean world = lv != null && lv == g.world();
            if (!world) {
                keptP += pr;
                keptM += m;
                keptVar += rms * rms;
            }
            if (lv != null && lv.sensitive() != null && !lv.sensitive().isBlank()) {
                sensitive++;
                only = name;
                sensM += m;
                sensVar += rms * rms;
                sensP += pr;
            }
            if (Math.max(m, pr) >= 1e-3 * e) {
                volumes.add(new Line(name, pr, m, rms / sqrtN, "MeV"));
            }
        }
        volumes.sort((a, b) -> Double.compare(b.measured(), a.measured()));
        final List<String> notes = new ArrayList<>();
        Line kept = null, sampling = null, resolution = null;
        if (p.kind != BeamPhysics.Particle.Kind.NONE && e > 0) {
            kept = new Line("Energy kept", 100 * keptP / e, 100 * keptM / e, 100 * Math.sqrt(keptVar) / sqrtN / e, "%");
        }
        // the sampling: the sensitive volumes, else the layers Physics Live took as active
        if (sensitive > 0) {
            sampling = new Line("Seen by the sensitive volumes", 100 * sensP / e, 100 * sensM / e, 100 * Math.sqrt(sensVar)
                / sqrtN / e, "% of the beam");
        } else if (active > 0 && r.sampling() != null) {
            double m = 0, var = 0;
            final Set<String> act = new LinkedHashSet<>();
            for (BeamPhysics.Layer l : r.layers()) {
                if (l.active()) {
                    act.add(l.volume());
                }
            }
            for (String a : act) {
                final Object v = edep.get(a);
                if (v != null) {
                    m += G4Json.num(v, "mean_MeV", 0);
                    var += Math.pow(G4Json.num(v, "rms_MeV", 0), 2);
                }
            }
            sampling = new Line("Seen by the active layers (" + String.join(", ", act) + ")", 100 * active / e, 100 * m / e,
                100 * Math.sqrt(var) / sqrtN / e, "% of the beam");
        }
        // the resolution, from the spread of one sensitive volume's energy: an electromagnetic shower's stochastic term
        if (p.kind == BeamPhysics.Particle.Kind.EM && sensitive == 1 && r.sampling() != null && n >= 20) {
            final Object v = edep.get(only);
            final double m = v == null ? 0 : G4Json.num(v, "mean_MeV", 0), rms = v == null ? 0 : G4Json.num(v, "rms_MeV", 0);
            if (m > 0) {
                final double a = rms / m * Math.sqrt(e / 1000);
                resolution = new Line("Stochastic term (" + only + ")", 100 * r.sampling().stochastic(), 100 * a,
                    100 * a / Math.sqrt(2.0 * n), "%/√GeV");
                notes.add("Geant4's spread holds every term (sampling, leakage; no noise is simulated): it lies above the"
                    + " stochastic term alone when the detector leaks.");
            }
        }
        // the profiles along copies
        final List<Profile> profiles = new ArrayList<>();
        for (Map.Entry<String, Object> c : G4Json.obj(run, "copies").entrySet()) {
            final List<?> copy = G4Json.list(c.getValue(), "copy"), mean = G4Json.list(c.getValue(), "mean"),
                rms = G4Json.list(c.getValue(), "rms");
            final Map<Integer, Double> pred = byCopy.get(c.getKey());
            if (pred == null || copy.size() < 2 || mean.size() != copy.size()) {
                continue;
            }
            final int k = copy.size();
            final int[] cs = new int[k];
            final double[] ms = new double[k], es = new double[k], ps = new double[k];
            int matched = 0;
            for (int i = 0; i < k; i++) {
                cs[i] = ((Number) copy.get(i)).intValue();
                ms[i] = ((Number) mean.get(i)).doubleValue();
                es[i] = i < rms.size() ? ((Number) rms.get(i)).doubleValue() / sqrtN : 0;
                ps[i] = pred.getOrDefault(cs[i], 0.0);
                matched += pred.containsKey(cs[i]) ? 1 : 0;
            }
            if (matched >= 2) {
                profiles.add(new Profile(c.getKey(), cs, ms, es, ps));
            }
        }
        if (kept != null && !kept.agrees()) {
            notes.add(0, String.format(Locale.ROOT, "Physics Live is a parameterization (%s): %s apart from Geant4 on the"
                + " energy kept, trust Geant4.", p.kind == BeamPhysics.Particle.Kind.EM ? "GFLASH"
                : p.kind == BeamPhysics.Particle.Kind.HADRON ? "the PDG's hadron shower lengths" : "Bethe's mean loss",
                String.format(Locale.ROOT, "%.1f %%", Math.abs(kept.measured() - kept.predicted()))));
        }
        if (p.kind == BeamPhysics.Particle.Kind.HADRON) {
            notes.add(String.format(Locale.ROOT, "Hadron shower: %.0f %% of its energy is taken as visible (f_em and 65 %% of"
                + " the rest).", 100 * seen));
        }
        if (n < 100) {
            notes.add(n + " events: the errors are wide; a thousand give a fair comparison.");
        }
        return new Result(n, particle, e, G4Json.str(run, "physics", ""), kept, volumes, sampling, resolution, profiles,
            notes);
    }

    private static String fmt(double v) {
        final double a = Math.abs(v);
        return a == 0 ? "0" : a >= 100 ? String.format(Locale.ROOT, "%.0f", v) : a >= 10 ? String.format(Locale.ROOT, "%.1f", v)
            : a >= 1 ? String.format(Locale.ROOT, "%.2f", v) : String.format(Locale.ROOT, "%.3g", v);
    }

    /** An error to two significant digits. */
    private static String fmtError(double v) {
        if (!(v > 0)) {
            return "0";
        }
        final int digits = Math.max(0, 1 - (int) Math.floor(Math.log10(v)));
        return String.format(Locale.ROOT, "%." + digits + "f", v);
    }

    private static String fmtEnergy(double mev) {
        return mev >= 1000 ? fmt(mev / 1000) + " GeV" : fmt(mev) + " MeV";
    }

    private static String join(double[] v) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length && i < 24; i++) {
            b.append(i > 0 ? " " : "").append(fmt(v[i]));
        }
        return b + (v.length > 24 ? " ..." : "");
    }
}
