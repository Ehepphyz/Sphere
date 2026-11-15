package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a learned fast simulation of this detector should be, worked out from the detector and from shower physics
 * before a single event is run: which volume to replace, what the readout is, for which particle and energies the
 * network may be trusted, how large a network and how many showers that takes -- and what stands in the way.
 *
 * <p>The beam's ray through the design gives the upstream material and the depth in radiation lengths; the materials
 * along it give the effective X0, critical energy and Moliere radius (PDG's combination for a sampling structure,
 * weighted by X/X0). From these, the longitudinal profile is the Longo-Sestili gamma distribution: how much of the
 * shower the envelope holds is computed, not guessed, and so is whether its width holds it.
 *
 * <p>The cells are numbered from the tree of placements exactly as {@code SensitiveDetector} numbers them from the
 * touchable, so that the pilot run can hold this plan to what Geant4 really does.
 */
public final class FastSimPlanner {

    private FastSimPlanner() {
    }

    /** Scale energy of multiple scattering, MeV (PDG). */
    private static final double ES = 21.2;
    /** Particles whose showers this scheme describes: electromagnetic, one conditional density in energy. */
    private static final Set<String> PARTICLES = Set.of("e-", "e+", "gamma");
    /** The most cells a flow is asked to learn: past it the network is no faster than the physics it replaces. */
    public static final int MAX_CELLS = 128;

    /** One readout cell: its detector, the number SensitiveDetector gives it, and where it is. */
    public record Cell(String detector, long key, String path) {
    }

    /** The plan. Lengths in mm, energies in MeV. {@code blockers} empty: it can run. */
    public record Plan(String particle, double energy, double emin, double emax, String envelope, Vec3 entry, Vec3 axis,
                       double upstreamX0, double depthX0, double x0, double criticalEnergy, double moliere, double zEff,
                       double showerMaxX0, double containedLongitudinal, double halfWidthRm, double acceptCos, double acceptRadius,
                       List<Cell> cells, List<String> detectors, int targetShowers, int flowLayers, int flowBins, int flowHidden,
                       List<String> notes, List<String> blockers) {

        public boolean runnable() {
            return blockers.isEmpty();
        }

        /** What the plan says, for the console and the panel. */
        public String describe() {
            final StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.ROOT, "Replace the volume %s for %s of %.4g to %.4g MeV (the gun gives %.4g MeV).%n",
                envelope == null ? "(none found)" : envelope, particle, emin, emax, energy));
            if (envelope != null) {
                b.append(String.format(Locale.ROOT, "Along the beam: %.3g X0 upstream, %.1f X0 inside; effective X0 %.2f mm, Ec %.2f MeV,"
                    + " Moliere radius %.1f mm (Z about %.0f).%n", upstreamX0, depthX0, x0, criticalEnergy, moliere, zEff));
                b.append(String.format(Locale.ROOT, "Shower maximum at %.1f X0, %.1f%% of it within the depth; half-width %.1f Moliere radii.%n",
                    showerMaxX0, 100 * containedLongitudinal, halfWidthRm));
                b.append(String.format(Locale.ROOT, "Readout: %d cells in %s. Flow: %d couplings, %d spline bins, %d hidden units; about %d showers to learn from.%n",
                    cells.size(), String.join(", ", detectors), flowLayers, flowBins, flowHidden, targetShowers));
                b.append(String.format(Locale.ROOT, "The network only takes what it was trained on: within %.0f mrad of the axis and %.1f mm of the entry point;"
                    + " anything else stays with Geant4's physics.%n", 1000 * Math.acos(acceptCos), acceptRadius));
            }
            notes.forEach(n -> b.append("Note: ").append(n).append(System.lineSeparator()));
            blockers.forEach(n -> b.append("Cannot run: ").append(n).append(System.lineSeparator()));
            return b.toString();
        }
    }

    /** The plan for a design and the gun its settings describe (or as CppExport defaults it). */
    public static Plan plan(Geometry g, CppExport.Options o) {
        final List<String> notes = new ArrayList<>(), blockers = new ArrayList<>();
        if (g.instances().isEmpty()) {
            g.rebuild();
        }
        final String particle = o.particle();
        if (!PARTICLES.contains(particle)) {
            blockers.add("the gun fires " + particle + ": this learns electromagnetic showers, of e-, e+ or gamma");
        }
        final double energy = number(o.energy(), blockers, "the gun's energy");
        final Vec3 axis = direction(o, blockers);
        final Vec3 origin = position(g, o, blockers);

        // the cells, in the order of the tree: along the beam, for a detector built along it
        final List<Geometry.Instance> sensitive = new ArrayList<>();
        final List<String> detectors = new ArrayList<>();
        for (Geometry.Instance i : g.instances()) {
            final String sd = i.lv().sensitive();
            if (sd != null) {
                sensitive.add(i);
                if (!detectors.contains(sd)) {
                    detectors.add(sd);
                }
            }
        }
        final List<Cell> cells = cells(g, null, blockers);
        if (cells.isEmpty()) {
            blockers.add("no sensitive volume: mark the active volumes sensitive, the network learns what they read");
        } else if (cells.size() > MAX_CELLS) {
            notes.add(cells.size() + " cells: the pilot run will keep the " + MAX_CELLS + " that hold the most energy");
        }

        final LogicalVolume envelope = envelope(g, sensitive, blockers);
        double upstream = 0, depth = 0, length = 0, ecSum = 0;
        Vec3 entry = null;
        if (envelope != null && axis != null && origin != null) {
            final Budget.RayReport ray = Budget.ray(g, origin, axis, false);
            double along = 0;
            boolean inside = false;
            for (Tracer.Segment s : ray.segments()) {
                final Material m = g.materials().material(s.lv().material());
                final boolean in = envelope.contains(s.lv());
                if (in && !inside) {
                    entry = origin.addScaled(axis, along);
                    inside = true;
                }
                if (m != null) {
                    final double t = s.length() / m.radiationLength();
                    if (in) {
                        depth += t;
                        length += s.length();
                        ecSum += t * m.criticalEnergy();
                    } else if (!inside) {
                        upstream += t;
                    }
                }
                along += s.length();
            }
            if (entry == null) {
                blockers.add("the gun's beam misses " + envelope.name() + ": aim it at the detector");
            }
        }
        double x0 = 0, ec = 0, moliere = 0, z = 0, tmax = 0, contained = 0, halfRm = 0;
        if (depth > 0) {
            x0 = length / depth;
            ec = ecSum / depth;
            moliere = ES * x0 / ec;
            z = Math.max(1, 610 / ec - 1.24);
            tmax = Math.max(0.1, Math.log(energy / ec) + (particle.equals("gamma") ? 0.5 : -0.5));
            contained = regularizedGamma(1 + 0.5 * tmax, 0.5 * depth);
            halfRm = halfWidth(envelope, axis) / moliere;
            if (contained < 0.9) {
                notes.add(String.format(Locale.ROOT, "only %.0f%% of the shower is contained: the leakage is learned too, but its fluctuations dominate", 100 * contained));
            }
            if (halfRm < 2) {
                notes.add(String.format(Locale.ROOT, "the envelope is only %.1f Moliere radii wide: expect lateral leakage", halfRm));
            }
            if (upstream > 0.5) {
                notes.add(String.format(Locale.ROOT, "%.2f X0 before the envelope: showers begin upstream, and only what reaches its face within the domain is replaced", upstream));
            }
        } else if (envelope != null && blockers.isEmpty()) {
            blockers.add("no material along the beam inside " + envelope.name());
        }
        final int n = Math.min(cells.size(), MAX_CELLS);
        // a flow of this size overfits 8000 showers (held-out NLL 14.3 against 12.7): 1500 a cell, as the time allows
        final int target = Math.max(20000, Math.min(80000, 1500 * n));
        final int hidden = Math.max(96, Math.min(256, 64 + 2 * (n + 1)));
        // never above half the gun's energy: the nominal beam, a little slower at the envelope, must stay inside the domain
        final double emin = Math.max(energy / 4, Math.min(5 * ec, energy / 2)), emax = energy * 2;
        final double radius = Math.max(1, 0.2 * moliere);
        return new Plan(particle, energy, emin, emax, envelope == null ? null : envelope.name(), entry, axis, upstream, depth, x0, ec, moliere, z,
            tmax, contained, halfRm, Math.cos(0.02), radius, List.copyOf(cells), List.copyOf(detectors), target, 6, 8, hidden, notes, blockers);
    }

    /**
     * The cells of a detector (every detector when null), in the order of the tree, each numbered as SensitiveDetector
     * numbers it. Two cells of one detector with the same number would be read as one -- placements left without copy
     * numbers do that -- and are refused with what to change.
     */
    public static List<Cell> cells(Geometry g, String detector, List<String> blockers) {
        final List<Cell> out = new ArrayList<>();
        final java.util.Map<String, String> seen = new java.util.HashMap<>();
        int clashes = 0;
        for (Geometry.Instance i : g.instances()) {
            final String sd = i.lv().sensitive();
            if (sd == null || detector != null && !detector.equals(sd)) {
                continue;
            }
            final Cell c = new Cell(sd, key(i, blockers), i.pathName());
            final String before = seen.putIfAbsent(sd + ":" + c.key(), c.path());
            if (before != null && blockers != null && clashes++ < 3) {
                blockers.add("the cells " + before + " and " + c.path() + " of " + sd + " read as one (both number " + c.key()
                    + "): give their placements different copy numbers");
            }
            out.add(c);
        }
        if (clashes > 3 && blockers != null) {
            blockers.add((clashes - 3) + " more cells share a number with another");
        }
        return out;
    }

    /** The number SensitiveDetector gives this cell: the copy numbers from the leaf up, three digits each. */
    static long key(Geometry.Instance leaf, List<String> blockers) {
        long key = 0, scale = 1;
        int depth = 0;
        for (Geometry.Instance i = leaf; i != null && i.pv() != null; i = i.parent()) {
            final int copy = i.pv().isReplica() ? i.copy() : i.pv().copyNumber();
            if (copy >= 1000 && blockers != null) {
                blockers.add("copy number " + copy + " of " + i.pv().name() + ": cells are numbered up to 999 a level");
            }
            if (depth++ >= 6) {
                if (blockers != null) {
                    blockers.add("a sensitive volume more than 6 levels deep: " + leaf.pathName());
                }
                break;
            }
            key += scale * copy;
            scale *= 1000;
        }
        return key;
    }

    /**
     * The deepest volume placed once, under the world, that holds every cell: going down while a single placement
     * (not a replica) contains them all. What it is must be a part of the world, or there is nothing to replace.
     */
    private static LogicalVolume envelope(Geometry g, List<Geometry.Instance> sensitive, List<String> blockers) {
        if (sensitive.isEmpty()) {
            return null;
        }
        final List<LogicalVolume> leaves = sensitive.stream().map(Geometry.Instance::lv).distinct().toList();
        LogicalVolume at = g.world();
        while (true) {
            PhysicalVolume next = null;
            for (PhysicalVolume pv : at.daughters()) {
                if (leaves.stream().allMatch(pv.logical()::contains)) {
                    next = pv;
                    break;
                }
            }
            if (next == null || next.isReplica() || leaves.contains(next.logical()) || placedAgain(g, next.logical())) {
                break;
            }
            at = next.logical();
        }
        if (at == g.world()) {
            blockers.add("the sensitive volumes are not inside one volume of their own: place them in a mother (an envelope around the detector)");
            return null;
        }
        return at;
    }

    private static boolean placedAgain(Geometry g, LogicalVolume lv) {
        return g.placements().stream().filter(p -> p.logical() == lv).count() > 1;
    }

    private static double halfWidth(LogicalVolume v, Vec3 axis) {
        final BBox b = v.solid().extent();
        final double hx = (b.maxX() - b.minX()) / 2, hy = (b.maxY() - b.minY()) / 2, hz = (b.maxZ() - b.minZ()) / 2;
        double w = Double.MAX_VALUE;
        // the axes across the beam, an axis counted when the beam is not along it
        if (Math.abs(axis.x()) < 0.7) {
            w = Math.min(w, hx);
        }
        if (Math.abs(axis.y()) < 0.7) {
            w = Math.min(w, hy);
        }
        if (Math.abs(axis.z()) < 0.7) {
            w = Math.min(w, hz);
        }
        return w;
    }

    private static double number(String expr, List<String> blockers, String what) {
        try {
            return Expr.eval(expr, null);
        } catch (GeometryException e) {
            blockers.add(what + " cannot be read: " + expr);
            return 0;
        }
    }

    private static Vec3 direction(CppExport.Options o, List<String> blockers) {
        final String[] d = o.gunDirection() == null ? new String[] {"0", "0", "1"} : o.gunDirection();
        final double x = number(d[0], blockers, "the gun's direction"), y = number(d[1], blockers, "the gun's direction"),
            z = number(d[2], blockers, "the gun's direction");
        final double n = Math.sqrt(x * x + y * y + z * z);
        return n == 0 ? null : new Vec3(x / n, y / n, z / n);
    }

    private static Vec3 position(Geometry g, CppExport.Options o, List<String> blockers) {
        final String[] p = o.gunPosition();
        if (p != null) {
            return new Vec3(length(p[0], blockers), length(p[1], blockers), length(p[2], blockers));
        }
        return new Vec3(0, 0, -0.99 * g.world().solid().extent().maxZ());   // as CppExport's gun
    }

    private static double length(String expr, List<String> blockers) {
        return number(Inputs.normalize(expr, Units.Category.LENGTH, "mm"), blockers, "the gun's position");
    }

    /** P(a, x), the regularized lower incomplete gamma function (Numerical Recipes' series and continued fraction). */
    static double regularizedGamma(double a, double x) {
        if (x <= 0) {
            return 0;
        }
        final double gln = logGamma(a);
        if (x < a + 1) {
            double ap = a, sum = 1 / a, del = sum;
            for (int n = 0; n < 500; n++) {
                ap += 1;
                del *= x / ap;
                sum += del;
                if (Math.abs(del) < Math.abs(sum) * 1e-14) {
                    break;
                }
            }
            return sum * Math.exp(-x + a * Math.log(x) - gln);
        }
        double b = x + 1 - a, c = 1e300, d = 1 / b, h = d;
        for (int i = 1; i < 500; i++) {
            final double an = -i * (i - a);
            b += 2;
            d = an * d + b;
            d = Math.abs(d) < 1e-300 ? 1e-300 : d;
            c = b + an / c;
            c = Math.abs(c) < 1e-300 ? 1e-300 : c;
            d = 1 / d;
            final double del = d * c;
            h *= del;
            if (Math.abs(del - 1) < 1e-14) {
                break;
            }
        }
        return 1 - Math.exp(-x + a * Math.log(x) - gln) * h;
    }

    private static double logGamma(double x) {
        final double[] c = {76.18009172947146, -86.50532032941677, 24.01409824083091, -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5};
        double y = x, t = x + 5.5, s = 1.000000000190015;
        t -= (x + 0.5) * Math.log(t);
        for (double v : c) {
            s += v / ++y;
        }
        return -t + Math.log(2.5066282746310005 * s / x);
    }
}
