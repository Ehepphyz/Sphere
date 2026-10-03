package com.sphere.core.fjcontrib.softkiller;

import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.RectangularGrid;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fjcontrib.ContribCitations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SoftKiller (SoftKiller 1.0.0; M. Cacciari, G.P. Salam and G. Soyez,
 * Eur. Phys. J. C 75 (2015) 59): the event up to |y| = rapmax is cut into
 * cells, and soft particles are removed in order of increasing pt until half
 * of the cells are empty; the same pt cut is then applied to the whole event.
 * With a sifter, only the particles it selects are put on the grid and cut
 * (for instance only neutral ones); the others are kept untouched.
 */
public class SoftKiller extends RectangularGrid {

    static {
        ContribCitations.use("softkiller");
    }

    /** The reduced event and the pt threshold applied. */
    public record Result(List<PseudoJet> reducedEvent, double ptThreshold) {
    }

    private final Selector sifter;

    public SoftKiller(double rapmax, double tileSize) {
        this(rapmax, tileSize, new Selector());
    }

    public SoftKiller(double rapmax, double tileSize, Selector sifter) {
        super(rapmax, tileSize);
        this.sifter = sifter == null ? new Selector() : sifter;
    }

    public SoftKiller(double rapmin, double rapmax, double drap, double dphi) {
        this(rapmin, rapmax, drap, dphi, new Selector());
    }

    public SoftKiller(double rapmin, double rapmax, double drap, double dphi, Selector sifter) {
        super(rapmin, rapmax, drap, dphi, new Selector());
        this.sifter = sifter == null ? new Selector() : sifter;
    }

    public SoftKiller(RectangularGrid grid, Selector sifter) {
        super(grid);
        this.sifter = sifter == null ? new Selector() : sifter;
    }

    public SoftKiller(RectangularGrid grid) {
        this(grid, new Selector());
    }

    @Override
    public String description() {
        String d = "SoftKiller with " + super.description();
        if (sifter.worker() != null) {
            d += " and applied to particles passing the selection (" + sifter.description() + ")";
        }
        return d;
    }

    /** The reduced event. */
    public List<PseudoJet> result(List<PseudoJet> event) {
        return apply(event).reducedEvent();
    }

    /** The reduced event with the pt threshold that made it. */
    public Result apply(List<PseudoJet> event) {
        if (nTiles() < 2) throw new FastJetException("SoftKiller not properly initialised.");
        double[] maxPt2 = new double[nTiles()];
        final PseudoJet[] ptrs = event.toArray(new PseudoJet[0]);
        if (sifter.worker() != null) sifter.nullifyNonSelected(ptrs);
        for (int i = 0; i < event.size(); i++) {
            if (ptrs[i] == null) continue;
            final int idx = tileIndex(event.get(i));
            if (idx < 0) continue;
            maxPt2[idx] = Math.max(maxPt2[idx], event.get(i).pt2());
        }
        if (nGoodTiles() != nTiles()) {
            int newn = 0;
            for (int i = 0; i < maxPt2.length; i++) {
                if (tileIsGood(i)) {
                    final double t = maxPt2[i];
                    maxPt2[i] = maxPt2[newn];
                    maxPt2[newn] = t;
                    newn++;
                }
            }
            maxPt2 = Arrays.copyOf(maxPt2, newn);
        }
        Arrays.sort(maxPt2);
        final int intMedianPos = maxPt2.length / 2;
        final double pt2cut = (1 + 1e-12) * maxPt2[intMedianPos];
        final List<PseudoJet> reduced = new ArrayList<>();
        for (int i = 0; i < event.size(); i++) {
            if (ptrs[i] == null || event.get(i).pt2() >= pt2cut) reduced.add(event.get(i));
        }
        return new Result(reduced, Math.sqrt(pt2cut));
    }
}
