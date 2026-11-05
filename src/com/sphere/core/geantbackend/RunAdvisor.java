package com.sphere.core.geantbackend;

import com.sphere.core.geant4.Advisor;
import com.sphere.core.geant4.Geometry;
import com.sphere.core.geant4.LogicalVolume;
import com.sphere.core.geant4.Material;
import com.sphere.core.geant4.Units;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a run in Geant4 says about the design, read from geant4-bridge's answer: where the run spent its time
 * (its steps, by volume and by particle), how the shower lay along the copies of a volume (its maximum, what
 * the last copy still holds), how much of the beam's energy the detector kept, and what the spread of the
 * energy in a sensitive volume makes of the resolution. Each finding says what to do, with the console
 * command when there is one.
 */
public final class RunAdvisor {

    private RunAdvisor() {
    }

    /** The findings of a run's answer on the design it ran. */
    public static List<Advisor.Advice> review(Map<String, Object> run, Geometry g) {
        final List<Advisor.Advice> out = new ArrayList<>();
        steps(run, g, out);
        profiles(run, out);
        energy(run, g, out);
        return out;
    }

    /** The run in a few lines: where its steps went, and the profile along each volume's copies. */
    public static List<String> report(Map<String, Object> run) {
        final List<String> out = new ArrayList<>();
        final Map<String, Object> p = G4Json.obj(run, "profile");
        if (!p.isEmpty()) {
            out.add(String.format(Locale.ROOT, "  steps: %s in %d events, %.3g ms per event (slowest %.3g ms)",
                count(G4Json.num(p, "steps", 0)), (int) G4Json.num(p, "events", 0), G4Json.num(p, "ms_per_event", 0),
                G4Json.num(p, "slowest_ms", 0)));
            out.add("    by volume:   " + shares(G4Json.list(p, "volumes")));
            out.add("    by particle: " + shares(G4Json.list(p, "particles")));
        }
        for (Map.Entry<String, Object> e : G4Json.obj(run, "copies").entrySet()) {
            final List<?> mean = G4Json.list(e.getValue(), "mean"), copy = G4Json.list(e.getValue(), "copy");
            if (mean.size() < 3) {
                continue;
            }
            final StringBuilder b = new StringBuilder();
            for (int i = 0; i < mean.size() && i < 40; i++) {
                b.append(i > 0 ? " " : "").append(Units.num(((Number) mean.get(i)).doubleValue()));
            }
            out.add("  " + e.getKey() + " along its " + mean.size() + " copies (" + ((Number) copy.get(0)).intValue() + " to "
                + ((Number) copy.get(copy.size() - 1)).intValue() + "), MeV per event: " + b + (mean.size() > 40 ? " ..." : ""));
        }
        return out;
    }

    private static String shares(List<?> list) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < list.size() && i < 6; i++) {
            b.append(i > 0 ? ", " : "").append(G4Json.str(list.get(i), "name", "?")).append(' ')
                .append(String.format(Locale.ROOT, "%.0f %%", 100 * G4Json.num(list.get(i), "fraction", 0)));
        }
        return b.toString();
    }

    private static String count(double n) {
        return n >= 1e9 ? String.format(Locale.ROOT, "%.3g G", n / 1e9) : n >= 1e6 ? String.format(Locale.ROOT,
            "%.3g M", n / 1e6) : n >= 1e4 ? String.format(Locale.ROOT, "%.3g k", n / 1e3) : String.valueOf((long) n);
    }

    // ------------------------------------------------------------------
    // Time
    // ------------------------------------------------------------------

    private static void steps(Map<String, Object> run, Geometry g, List<Advisor.Advice> out) {
        final Map<String, Object> p = G4Json.obj(run, "profile");
        final List<?> volumes = G4Json.list(p, "volumes"), particles = G4Json.list(p, "particles");
        if (volumes.isEmpty()) {
            return;
        }
        // tracking through a gas where nothing is measured
        for (Object v : volumes) {
            final double f = G4Json.num(v, "fraction", 0);
            final LogicalVolume lv = g.volume(G4Json.str(v, "name", ""));
            final Material m = lv == null ? null : g.materials().material(lv.material());
            if (f > 0.25 && m != null && m.densityGcm3() < 0.01 && lv.sensitive() == null) {
                final boolean world = lv == g.world();
                out.add(new Advisor.Advice(Advisor.Level.IDEA, String.format(Locale.ROOT, "%.0f %% of the steps are in %s"
                    + " (%s)", 100 * f, lv.name(), lv.material()), "Particles are tracked through a gas where nothing is"
                    + " measured." + (world ? " Vacuum there, or a world fitted to the detector, gives the time back." : ""),
                    lv.name(), world ? List.of(new Advisor.Fix("Make the world vacuum", "world material G4_Galactic"),
                    new Advisor.Fix("Fit the world", "world fit")) : List.of()));
            }
        }
        double em = 0, neutrons = 0, optical = 0;
        for (Object x : particles) {
            final String n = G4Json.str(x, "name", "");
            final double f = G4Json.num(x, "fraction", 0);
            em += n.equals("e-") || n.equals("e+") || n.equals("gamma") ? f : 0;
            neutrons += n.equals("neutron") ? f : 0;
            optical += n.equals("opticalphoton") ? f : 0;
        }
        if (em > 0.8) {
            out.add(new Advisor.Advice(Advisor.Level.IDEA, String.format(Locale.ROOT, "Electrons and photons take %.0f %%"
                + " of the steps", 100 * em), "The low-energy secondaries of the showers. A larger production cut in the"
                + " passive absorbers alone (a G4Region with /run/setCutForRegion), the sensitive layers keeping the 0.7 mm"
                + " default, saves the steps where nothing is measured.", null, List.of()));
        }
        if (neutrons > 0.3) {
            out.add(new Advisor.Advice(Advisor.Level.IDEA, String.format(Locale.ROOT, "Neutrons take %.0f %% of the steps",
                100 * neutrons), "Slow neutrons wander for microseconds. When their late energy does not count, a time"
                + " limit (G4UserLimits with G4UserSpecialCuts) ends them early.", null, List.of()));
        }
        if (optical > 0.3) {
            out.add(new Advisor.Advice(Advisor.Level.INFO, String.format(Locale.ROOT, "Optical photons take %.0f %% of the"
                + " steps", 100 * optical), "Scintillation or Cherenkov light tracked photon by photon: a light yield scaled"
                + " down (SetScintillationYieldFactor) keeps the shape for a fraction of the time.", null, List.of()));
        }
        final double ms = G4Json.num(p, "ms_per_event", 0);
        if (ms > 0) {
            final double hours = ms * 1e6 / 3.6e6;
            out.add(new Advisor.Advice(Advisor.Level.INFO, String.format(Locale.ROOT, "%.3g ms per event", ms),
                String.format(Locale.ROOT, "A million events take %s on one thread at this pace (%s steps per event).",
                    hours >= 1 ? String.format(Locale.ROOT, "%.3g hours", hours) : String.format(Locale.ROOT, "%.3g minutes",
                    hours * 60), count(G4Json.num(p, "steps", 0) / Math.max(1, G4Json.num(p, "events", 1)))), null,
                List.of()));
        }
    }

    // ------------------------------------------------------------------
    // Showers along copies
    // ------------------------------------------------------------------

    private static void profiles(Map<String, Object> run, List<Advisor.Advice> out) {
        for (Map.Entry<String, Object> e : G4Json.obj(run, "copies").entrySet()) {
            final List<?> mean = G4Json.list(e.getValue(), "mean");
            if (mean.size() < 3) {
                continue;
            }
            double sum = 0, peak = 0;
            int at = 0;
            for (int i = 0; i < mean.size(); i++) {
                final double m = ((Number) mean.get(i)).doubleValue();
                sum += m;
                if (m > peak) {
                    peak = m;
                    at = i;
                }
            }
            if (sum <= 0) {
                continue;
            }
            final double last = ((Number) mean.get(mean.size() - 1)).doubleValue();
            final String where = "copy " + ((Number) G4Json.list(e.getValue(), "copy").get(at)).intValue() + " of "
                + mean.size();
            if (last > 0.05 * peak && at < mean.size() - 1) {
                out.add(new Advisor.Advice(Advisor.Level.WARNING, "The shower in " + e.getKey() + " leaks out the back",
                    String.format(Locale.ROOT, "Its last copy still holds %.0f %% of the peak (the maximum is in %s, the last"
                        + " copy %.2g %% of the energy in %s): more layers, or denser ones, hold the tail.", 100 * last / peak,
                        where, 100 * last / sum, e.getKey()), e.getKey(), List.of()));
            } else {
                out.add(new Advisor.Advice(Advisor.Level.INFO, "The shower in " + e.getKey() + " peaks in " + where,
                    String.format(Locale.ROOT, "Its last copy holds %.2g %% of the energy in %s.", 100 * last / sum,
                        e.getKey()), e.getKey(), List.of()));
            }
        }
    }

    // ------------------------------------------------------------------
    // Energy: kept, sampled, spread
    // ------------------------------------------------------------------

    private static void energy(Map<String, Object> run, Geometry g, List<Advisor.Advice> out) {
        final double beam = G4Json.num(run, "energy_MeV", 0);
        final String particle = G4Json.str(run, "particle", "");
        final Map<String, Object> edep = G4Json.obj(run, "edep");
        if (beam <= 0 || edep.isEmpty() || particle.startsWith("mu") || particle.contains("geantino")) {
            return;
        }
        double all = 0;
        for (Object v : edep.values()) {
            all += G4Json.num(v, "mean_MeV", 0);
        }
        final boolean em = particle.equals("e-") || particle.equals("e+") || particle.equals("gamma");
        final double kept = all / beam;
        if (kept > 0.02) {
            out.add(new Advisor.Advice(em && kept < 0.9 ? Advisor.Level.WARNING : Advisor.Level.INFO, String.format(
                Locale.ROOT, "The detector keeps %.1f %% of the beam's energy", 100 * kept), String.format(Locale.ROOT,
                "%s of the %s each %s brings; the rest leaves (out the back, the sides%s).", Units.best(all,
                    Units.Category.ENERGY), Units.best(beam, Units.Category.ENERGY), particle, em ? ""
                    : ", as neutrinos, or as the binding energy hadron showers never show"), null, List.of()));
        }
        // the sensitive volumes: the fraction they sample, and the resolution their spread gives
        for (Map.Entry<String, Object> e : edep.entrySet()) {
            final LogicalVolume lv = g.volume(e.getKey());
            final double m = G4Json.num(e.getValue(), "mean_MeV", 0), rms = G4Json.num(e.getValue(), "rms_MeV", 0);
            if (lv == null || lv.sensitive() == null || m <= 0 || G4Json.num(e.getValue(), "events", 0) < 10) {
                continue;
            }
            final double res = rms / m, stochastic = res * Math.sqrt(beam / 1000);
            out.add(new Advisor.Advice(Advisor.Level.INFO, String.format(Locale.ROOT, "%s: sampling fraction %.3g %%,"
                + " resolution %.3g %%", lv.name(), 100 * m / beam, 100 * res), String.format(Locale.ROOT, "The energy it"
                + " sees per event spreads by %.3g %% at %s: a stochastic term of %.3g %%/sqrt(E/GeV) if it is the only one"
                + " (a few events give a rough figure; a thousand, a fair one).", 100 * res, Units.best(beam,
                Units.Category.ENERGY), 100 * stochastic), lv.name(), List.of()));
        }
    }
}
