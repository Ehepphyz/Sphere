package com.sphere.core.fastjet;

import java.util.List;

/**
 * How the ghosts of an active or passive area are laid out, fastjet::GhostedAreaSpec:
 * a grid in rapidity and azimuth up to a maximum rapidity (or over the region
 * a selector allows), each cell of the requested area holding one ghost of
 * infinitesimal pt, scattered randomly inside it.
 *
 * The random numbers come from one generator shared by every specification,
 * as in FastJet, unless a generator of one's own is given; seeding it the same
 * way reproduces FastJet's ghosts exactly.
 */
public final class GhostedAreaSpec {

    public static final double DEF_GHOST_MAXRAP = 6.0;
    public static final int DEF_REPEAT = 1;
    public static final double DEF_GHOST_AREA = 0.01;
    public static final double DEF_GRID_SCATTER = 1.0;
    public static final double DEF_PT_SCATTER = 0.1;
    public static final double DEF_MEAN_GHOST_PT = 1e-100;

    private static final BasicRandom SHARED_GENERATOR = new BasicRandom();
    private static final LimitedWarning WARN_FJ2_PLACEMENT = new LimitedWarning();
    private static final LimitedWarning WARN_FIXED_SEEDS = new LimitedWarning();

    private double ghostMaxrap;
    private double ghostRapOffset;
    private int repeat;
    private double ghostArea;
    private double gridScatter;
    private double ptScatter;
    private double meanGhostPt;
    private boolean fj2Placement;
    private Selector selector = new Selector();

    private double actualGhostArea, dphi, drap;
    private int nGhosts, nphi, nrap;

    private int[] randomCheckpoint;
    private int[] fixedSeed;
    private int[] lastUsedSeed;
    private BasicRandom userRandomGenerator;

    public GhostedAreaSpec() {
        this(DEF_GHOST_MAXRAP);
    }

    public GhostedAreaSpec(double ghostMaxrap) {
        this(ghostMaxrap, DEF_REPEAT, DEF_GHOST_AREA, DEF_GRID_SCATTER, DEF_PT_SCATTER, DEF_MEAN_GHOST_PT);
    }

    public GhostedAreaSpec(double ghostMaxrap, int repeat, double ghostArea) {
        this(ghostMaxrap, repeat, ghostArea, DEF_GRID_SCATTER, DEF_PT_SCATTER, DEF_MEAN_GHOST_PT);
    }

    public GhostedAreaSpec(double ghostMaxrap, int repeat, double ghostArea, double gridScatter,
                           double ptScatter, double meanGhostPt) {
        this.ghostMaxrap = ghostMaxrap;
        this.ghostRapOffset = 0.0;
        this.repeat = repeat;
        this.ghostArea = ghostArea;
        this.gridScatter = gridScatter;
        this.ptScatter = ptScatter;
        this.meanGhostPt = meanGhostPt;
        initialize();
    }

    /** Ghosts over [ghostMinrap, ghostMaxrap]. */
    public GhostedAreaSpec(double ghostMinrap, double ghostMaxrap, int repeat, double ghostArea) {
        this(ghostMinrap, ghostMaxrap, repeat, ghostArea, DEF_GRID_SCATTER, DEF_PT_SCATTER, DEF_MEAN_GHOST_PT);
    }

    public GhostedAreaSpec(double ghostMinrap, double ghostMaxrap, int repeat, double ghostArea,
                           double gridScatter, double ptScatter, double meanGhostPt) {
        this.ghostMaxrap = 0.5 * (ghostMaxrap - ghostMinrap);
        this.ghostRapOffset = 0.5 * (ghostMaxrap + ghostMinrap);
        this.repeat = repeat;
        this.ghostArea = ghostArea;
        this.gridScatter = gridScatter;
        this.ptScatter = ptScatter;
        this.meanGhostPt = meanGhostPt;
        initialize();
    }

    /** Ghosts where a geometric selector of finite area allows them. */
    public GhostedAreaSpec(Selector selector, int repeat, double ghostArea, double gridScatter,
                           double ptScatter, double meanGhostPt) {
        if (!selector.hasFiniteArea()) {
            throw new FastJetException("To construct a GhostedAreaSpec with a Selector, the selector must have a finite area");
        }
        if (!selector.appliesJetByJet()) {
            throw new FastJetException("To construct a GhostedAreaSpec with a Selector, the selector must apply jet-by-jet");
        }
        this.selector = selector;
        final double[] r = selector.rapidityExtent();
        this.ghostMaxrap = 0.5 * (r[1] - r[0]);
        this.ghostRapOffset = 0.5 * (r[1] + r[0]);
        this.repeat = repeat;
        this.ghostArea = ghostArea;
        this.gridScatter = gridScatter;
        this.ptScatter = ptScatter;
        this.meanGhostPt = meanGhostPt;
        initialize();
    }

    public GhostedAreaSpec(Selector selector) {
        this(selector, DEF_REPEAT, DEF_GHOST_AREA, DEF_GRID_SCATTER, DEF_PT_SCATTER, DEF_MEAN_GHOST_PT);
    }

    /** A copy. */
    public GhostedAreaSpec(GhostedAreaSpec o) {
        ghostMaxrap = o.ghostMaxrap;
        ghostRapOffset = o.ghostRapOffset;
        repeat = o.repeat;
        ghostArea = o.ghostArea;
        gridScatter = o.gridScatter;
        ptScatter = o.ptScatter;
        meanGhostPt = o.meanGhostPt;
        fj2Placement = o.fj2Placement;
        selector = o.selector;
        actualGhostArea = o.actualGhostArea;
        dphi = o.dphi;
        drap = o.drap;
        nGhosts = o.nGhosts;
        nphi = o.nphi;
        nrap = o.nrap;
        randomCheckpoint = o.randomCheckpoint;
        fixedSeed = o.fixedSeed;
        lastUsedSeed = o.lastUsedSeed;
        userRandomGenerator = o.userRandomGenerator;
    }

    private void initialize() {
        drap = Math.sqrt(ghostArea);
        dphi = drap;
        if (fj2Placement) {
            nphi = (int) Math.ceil(PseudoJet.TWOPI / dphi);
            dphi = PseudoJet.TWOPI / nphi;
            nrap = (int) Math.ceil(ghostMaxrap / drap);
            drap = ghostMaxrap / nrap;
            actualGhostArea = dphi * drap;
            nGhosts = (2 * nrap + 1) * nphi;
        } else {
            nphi = (int) (PseudoJet.TWOPI / dphi + 0.5);
            dphi = PseudoJet.TWOPI / nphi;
            nrap = (int) (ghostMaxrap / drap + 0.5);
            drap = ghostMaxrap / nrap;
            actualGhostArea = dphi * drap;
            nGhosts = (2 * nrap) * nphi;
        }
        checkpointRandom();
        fixedSeed = null;
    }

    private BasicRandom generator() {
        return userRandomGenerator != null ? userRandomGenerator : SHARED_GENERATOR;
    }

    /** Uses a generator of one's own rather than the shared one. */
    public GhostedAreaSpec setRandomGenerator(BasicRandom generator) {
        this.userRandomGenerator = generator;
        return this;
    }

    public int[] randomStatus() {
        return generator().status();
    }

    public void setRandomStatus(int[] s) {
        generator().setStatus(s);
    }

    public void checkpointRandom() {
        randomCheckpoint = randomStatus();
    }

    public void restoreCheckpointRandom() {
        setRandomStatus(randomCheckpoint);
    }

    /** A copy whose ghosts always come from the given seed. */
    public GhostedAreaSpec withFixedSeed(int[] seed) {
        final GhostedAreaSpec s = new GhostedAreaSpec(this);
        s.fixedSeed = seed.clone();
        return s;
    }

    public int[] fixedSeed() {
        return fixedSeed == null ? null : fixedSeed.clone();
    }

    public int[] lastSeed() {
        if (repeat > 1) {
            WARN_FIXED_SEEDS.warn("Using fixed seeds (or accessing last used seeds) not sensible with repeat>1");
        }
        return lastUsedSeed == null ? null : lastUsedSeed.clone();
    }

    /** Appends the ghosts to the event. */
    public void addGhosts(List<PseudoJet> event) {
        final double rapOffset;
        final int nrapUpper;
        if (fj2Placement) {
            rapOffset = 0.0;
            nrapUpper = nrap;
        } else {
            rapOffset = 0.5;
            nrapUpper = nrap - 1;
        }
        final int nRandom = (nrapUpper + nrap + 1) * nphi * 3;
        final double[] all;
        final int[] used = new int[2];
        if (fixedSeed != null) {
            if (repeat > 1) {
                WARN_FIXED_SEEDS.warn("Using fixed seeds (or accessing last used seeds) not sensible with repeat>1");
            }
            final BasicRandom local = new BasicRandom();
            local.setStatus(fixedSeed);
            all = local.next(nRandom, used);
        } else {
            all = generator().next(nRandom, used);
        }
        lastUsedSeed = used;

        int counter = 0;
        for (int irap = -nrap; irap <= nrapUpper; irap++) {
            for (int iphi = 0; iphi < nphi; iphi++) {
                final double phiFj2 = (iphi + 0.5) * dphi + dphi * (all[counter++] - 0.5) * gridScatter;
                final double phi = fj2Placement ? 0.5 * Math.PI - phiFj2 : phiFj2;
                final double rap = (irap + rapOffset) * drap + drap * (all[counter++] - 0.5) * gridScatter
                    + ghostRapOffset;
                final double pt = meanGhostPt * (1 + (all[counter++] - 0.5) * ptScatter);
                final double exprap = CRMath.exp(+rap);
                final double pminus = pt / exprap;
                final double pplus = pt * exprap;
                final double px = pt * CRMath.cos(phi);
                final double py = pt * CRMath.sin(phi);
                final PseudoJet mom = new PseudoJet(px, py, 0.5 * (pplus - pminus), 0.5 * (pplus + pminus),
                                                    Precision.DOUBLE);
                mom.setCachedRapPhi(rap, phi);
                if (selector.worker() != null && !selector.pass(mom)) {
                    continue;
                }
                event.add(mom);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Access                                                              */
    /* ------------------------------------------------------------------ */

    public double ghostMaxrap() { return ghostMaxrap; }
    public double ghostRapmax() { return ghostMaxrap; }
    public double ghostArea() { return ghostArea; }
    public double gridScatter() { return gridScatter; }
    public double ptScatter() { return ptScatter; }
    public double meanGhostPt() { return meanGhostPt; }
    public int repeat() { return repeat; }
    public boolean fj2Placement() { return fj2Placement; }
    public double actualGhostArea() { return actualGhostArea; }
    public int nGhosts() { return nGhosts; }
    public int nphi() { return nphi; }
    public int nrap() { return nrap; }
    public Selector selector() { return selector; }

    public void setGhostArea(double v) { ghostArea = v; initialize(); }
    public void setGhostMaxrap(double v) { ghostMaxrap = v; initialize(); }
    public void setGridScatter(double v) { gridScatter = v; }
    public void setPtScatter(double v) { ptScatter = v; }
    public void setMeanGhostPt(double v) { meanGhostPt = v; }
    public void setRepeat(int v) { repeat = v; }

    /** FastJet 2's placement, deprecated there for its edge effects. */
    public void setFj2Placement(boolean v) {
        fj2Placement = v;
        initialize();
        if (v) {
            WARN_FJ2_PLACEMENT.warn("FJ2 placement of ghosts can lead to systematic edge effects in area evaluation and is deprecated. Prefer new (default) FJ3 placement.");
        }
    }

    /** The shared generator's state, to replay a run. */
    public static int[] sharedGeneratorStatus() {
        return SHARED_GENERATOR.status();
    }

    public static void setSharedGeneratorStatus(int[] s) {
        SHARED_GENERATOR.setStatus(s);
    }

    public String description() {
        final StringBuilder o = new StringBuilder();
        o.append("ghosts of area ").append(Fmt.g(actualGhostArea()))
         .append(" (had requested ").append(Fmt.g(ghostArea())).append(")");
        if (selector.worker() != null) {
            o.append(", placed according to selector (").append(selector.description()).append(")");
        } else {
            o.append(", placed up to y = ").append(Fmt.g(ghostMaxrap()));
        }
        o.append(", scattered wrt to perfect grid by (rel) ").append(Fmt.g(gridScatter()))
         .append(", mean_ghost_pt = ").append(Fmt.g(meanGhostPt()))
         .append(", rel pt_scatter =  ").append(Fmt.g(ptScatter()))
         .append(", n repetitions of ghost distributions =  ").append(repeat());
        return o.toString();
    }

    @Override
    public String toString() {
        return description();
    }
}
