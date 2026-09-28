package com.sphere.core.fastjet.contrib;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.PseudoJet;

import java.util.ArrayList;
import java.util.List;

/**
 * The Lund jet plane (F. Dreyer, G. Salam, G. Soyez, JHEP 12 (2018) 064):
 * the jet reclustered with Cambridge/Aachen is declustered along its harder
 * branch, each splitting giving a point (ln 1/Delta, ln kt) with
 * kt = pt_2 Delta, z = pt_2/(pt_1+pt_2) and the azimuth psi of the softer
 * branch about the harder. Secondary planes follow the softer branches.
 *
 * Written for Sphere from the paper; Delta, z and kt are computed to 106
 * bits for double-double jets.
 */
public final class LundPlane {

    static {
        Citations.use("lund"); // listed in the console's Citations menu once used
    }

    /** One declustering: the parent, its two branches and the Lund variables. */
    public record Declustering(PseudoJet pair, PseudoJet harder, PseudoJet softer, double delta, double kt,
                               double z, double kappa, double psi, double m, int depth) {
        public double lnOneOverDelta() { return Math.log(1.0 / delta); }
        public double lnKt() { return Math.log(kt); }
        public double lnZ() { return Math.log(z); }
    }

    private final boolean spherical;

    public LundPlane() {
        this(false);
    }

    /** @param spherical energies and opening angles (e+e-) rather than pt and (y, phi) */
    public LundPlane(boolean spherical) {
        this.spherical = spherical;
    }

    /** The primary declusterings, hardest branch first. */
    public List<Declustering> primary(PseudoJet jet) {
        return decluster(SoftDrop.asCambridge(jet, spherical), 0);
    }

    /** Primary and secondary (following every softer branch too) declusterings. */
    public List<Declustering> all(PseudoJet jet) {
        final List<Declustering> out = new ArrayList<>();
        final List<PseudoJet> todo = new ArrayList<>();
        final List<Integer> depth = new ArrayList<>();
        todo.add(SoftDrop.asCambridge(jet, spherical));
        depth.add(0);
        while (!todo.isEmpty()) {
            final PseudoJet j = todo.remove(0);
            final int d = depth.remove(0);
            for (Declustering s : decluster(j, d)) {
                out.add(s);
                todo.add(s.softer());
                depth.add(d + 1);
            }
        }
        return out;
    }

    private List<Declustering> decluster(PseudoJet jet, int depth) {
        final List<Declustering> out = new ArrayList<>();
        PseudoJet j = jet;
        PseudoJet[] parents;
        while ((parents = j.parents()) != null) {
            PseudoJet p1 = parents[0];
            PseudoJet p2 = parents[1];
            DD s1 = spherical ? Kin.e(p1) : Kin.pt(p1);
            DD s2 = spherical ? Kin.e(p2) : Kin.pt(p2);
            if (s1.lt(s2)) {
                final PseudoJet t = p1;
                p1 = p2;
                p2 = t;
                final DD u = s1;
                s1 = s2;
                s2 = u;
            }
            final DD delta = spherical ? Kin.angle(p1, p2) : Kin.dR2(p1, p2).sqrt();
            final DD kt = s2.mul(delta);
            final DD z = s2.div(s1.add(s2));
            final double psi = spherical ? 0.0
                : Math.atan2(p2.rap() - p1.rap(), Kin.dphi(p2, p1).doubleValue());
            out.add(new Declustering(j, p1, p2, delta.doubleValue(), kt.doubleValue(), z.doubleValue(),
                z.mul(delta).doubleValue(), psi, j.m(), depth));
            j = p1;
        }
        return out;
    }

    /** A 2D histogram of the primary plane: counts in (ln 1/Delta, ln kt) bins. */
    public static double[][] density(List<List<Declustering>> jets, int nx, double xmin, double xmax,
                                     int ny, double ymin, double ymax) {
        final double[][] h = new double[nx][ny];
        for (List<Declustering> jet : jets) {
            for (Declustering s : jet) {
                final int ix = (int) Math.floor((s.lnOneOverDelta() - xmin) / (xmax - xmin) * nx);
                final int iy = (int) Math.floor((s.lnKt() - ymin) / (ymax - ymin) * ny);
                if (ix >= 0 && ix < nx && iy >= 0 && iy < ny) h[ix][iy] += 1.0 / jets.size();
            }
        }
        return h;
    }
}
