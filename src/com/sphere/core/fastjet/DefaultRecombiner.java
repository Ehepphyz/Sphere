package com.sphere.core.fastjet;

/**
 * The recombination schemes FastJet provides, JetDefinition::DefaultRecombiner.
 *
 * With double inputs the arithmetic is the C++ one; with double-double inputs
 * every sum, weight and average is carried to 106 bits.
 */
public class DefaultRecombiner implements Recombiner {

    private final RecombinationScheme scheme;

    public DefaultRecombiner(RecombinationScheme scheme) {
        if (scheme == RecombinationScheme.EXTERNAL_SCHEME) {
            throw new FastJetException("DefaultRecombiner: the external scheme needs a user recombiner");
        }
        this.scheme = scheme;
    }

    public DefaultRecombiner() {
        this(RecombinationScheme.E_SCHEME);
    }

    @Override
    public RecombinationScheme scheme() {
        return scheme;
    }

    @Override
    public String description() {
        return scheme.description();
    }

    @Override
    public void recombine(PseudoJet pa, PseudoJet pb, PseudoJet pab) {
        if (pa.dd || pb.dd) {
            recombineDD(pa, pb, pab);
            return;
        }
        pab.dd = false;
        double weighta;
        double weightb;
        switch (scheme) {
            case E_SCHEME -> {
                pab.reset(pa.px + pb.px, pa.py + pb.py, pa.pz + pb.pz, pa.e + pb.e);
                return;
            }
            case PT_SCHEME, ET_SCHEME, BIPT_SCHEME -> {
                weighta = pa.perp();
                weightb = pb.perp();
            }
            case PT2_SCHEME, ET2_SCHEME, BIPT2_SCHEME -> {
                weighta = pa.perp2();
                weightb = pb.perp2();
            }
            case WTA_PT_SCHEME -> {
                final PseudoJet phard = (pa.pt2() >= pb.pt2()) ? pa : pb;
                pab.resetPtYPhiM(pa.pt() + pb.pt(), phard.rap(), phard.phi(), phard.m());
                return;
            }
            case WTA_MODP_SCHEME -> {
                final boolean aHardest = pa.modp2() >= pb.modp2();
                final PseudoJet phard = aHardest ? pa : pb;
                final PseudoJet psoft = aHardest ? pb : pa;
                final double modpHard = phard.modp();
                final double modpAb = modpHard + psoft.modp();
                if (phard.modp2() == 0.0) {
                    pab.reset(0.0, 0.0, 0.0, phard.m());
                } else {
                    final double scale = modpAb / modpHard;
                    pab.reset(phard.px * scale, phard.py * scale, phard.pz * scale,
                              Math.sqrt(modpAb * modpAb + phard.m2()));
                }
                return;
            }
            default -> throw new FastJetException("DefaultRecombiner: unrecognized recombination scheme " + scheme);
        }
        final double perpAb = pa.perp() + pb.perp();
        if (perpAb != 0.0) {
            final double yAb = (weighta * pa.rap() + weightb * pb.rap()) / (weighta + weightb);
            final double phiA = pa.phi();
            double phiB = pb.phi();
            if (phiA - phiB > Math.PI) phiB += PseudoJet.TWOPI;
            if (phiA - phiB < -Math.PI) phiB -= PseudoJet.TWOPI;
            final double phiAb = (weighta * phiA + weightb * phiB) / (weighta + weightb);
            pab.resetPtYPhiM(perpAb, yAb, phiAb, 0.0);
        } else {
            pab.reset(0.0, 0.0, 0.0, 0.0);
        }
    }

    private void recombineDD(PseudoJet pa, PseudoJet pb, PseudoJet pab) {
        pab.dd = true;
        DD weighta;
        DD weightb;
        switch (scheme) {
            case E_SCHEME -> {
                pab.resetMomentum(pa.pxDD().add(pb.pxDD()), pa.pyDD().add(pb.pyDD()),
                                  pa.pzDD().add(pb.pzDD()), pa.eDD().add(pb.eDD()));
                clearIndices(pab);
                return;
            }
            case PT_SCHEME, ET_SCHEME, BIPT_SCHEME -> {
                weighta = pa.ptDD();
                weightb = pb.ptDD();
            }
            case PT2_SCHEME, ET2_SCHEME, BIPT2_SCHEME -> {
                weighta = pa.kt2DD();
                weightb = pb.kt2DD();
            }
            case WTA_PT_SCHEME -> {
                final PseudoJet phard = pa.kt2DD().ge(pb.kt2DD()) ? pa : pb;
                pab.resetMomentumPtYPhiMDD(pa.ptDD().add(pb.ptDD()), phard.rapDD(), phard.phiDD(), phard.mDD());
                clearIndices(pab);
                return;
            }
            case WTA_MODP_SCHEME -> {
                final DD modp2a = pa.kt2DD().add(pa.pzDD().sqr());
                final DD modp2b = pb.kt2DD().add(pb.pzDD().sqr());
                final boolean aHardest = modp2a.ge(modp2b);
                final PseudoJet phard = aHardest ? pa : pb;
                final DD modp2Hard = aHardest ? modp2a : modp2b;
                final DD modpHard = modp2Hard.sqrt();
                final DD modpAb = modpHard.add((aHardest ? modp2b : modp2a).sqrt());
                if (modp2Hard.isZero()) {
                    pab.resetMomentum(DD.ZERO, DD.ZERO, DD.ZERO, phard.mDD());
                } else {
                    final DD scale = modpAb.div(modpHard);
                    pab.resetMomentum(phard.pxDD().mul(scale), phard.pyDD().mul(scale),
                                      phard.pzDD().mul(scale), modpAb.sqr().add(phard.m2DD()).sqrt());
                }
                clearIndices(pab);
                return;
            }
            default -> throw new FastJetException("DefaultRecombiner: unrecognized recombination scheme " + scheme);
        }
        final DD perpAb = pa.ptDD().add(pb.ptDD());
        if (!perpAb.isZero()) {
            final DD wsum = weighta.add(weightb);
            final DD yAb = weighta.mul(pa.rapDD()).add(weightb.mul(pb.rapDD())).div(wsum);
            final DD phiA = pa.phiDD();
            DD phiB = pb.phiDD();
            final DD diff = phiA.sub(phiB);
            if (diff.gt(DD.PI)) phiB = phiB.add(DD.TWO_PI);
            if (diff.lt(DD.PI.neg())) phiB = phiB.sub(DD.TWO_PI);
            final DD phiAb = weighta.mul(phiA).add(weightb.mul(phiB)).div(wsum);
            pab.resetMomentumPtYPhiMDD(perpAb, yAb, phiAb, DD.ZERO);
        } else {
            pab.resetMomentum(DD.ZERO, DD.ZERO, DD.ZERO, DD.ZERO);
        }
        clearIndices(pab);
    }

    private static void clearIndices(PseudoJet p) {
        p.clusterHistIndex = -1;
        p.userIndex = -1;
        p.structure = null;
        p.userInfo = null;
    }

    @Override
    public void preprocess(PseudoJet p) {
        switch (scheme) {
            case E_SCHEME, BIPT_SCHEME, BIPT2_SCHEME, WTA_PT_SCHEME, WTA_MODP_SCHEME -> {
            }
            case PT_SCHEME, PT2_SCHEME -> {
                if (p.dd) {
                    final DD newE = p.kt2DD().add(p.pzDD().sqr()).sqrt();
                    keepingIndices(p, p.pxDD(), p.pyDD(), p.pzDD(), newE);
                } else {
                    final double newE = Math.sqrt(p.perp2() + p.pz * p.pz);
                    p.resetMomentum(p.px, p.py, p.pz, newE);
                }
            }
            case ET_SCHEME, ET2_SCHEME -> {
                if (p.dd) {
                    final DD rescale = p.eDD().div(p.kt2DD().add(p.pzDD().sqr()).sqrt());
                    keepingIndices(p, p.pxDD().mul(rescale), p.pyDD().mul(rescale),
                                   p.pzDD().mul(rescale), p.eDD());
                } else {
                    final double rescale = p.e / Math.sqrt(p.perp2() + p.pz * p.pz);
                    p.resetMomentum(rescale * p.px, rescale * p.py, rescale * p.pz, p.e);
                }
            }
            default -> throw new FastJetException("DefaultRecombiner: unrecognized recombination scheme " + scheme);
        }
    }

    private static void keepingIndices(PseudoJet p, DD x, DD y, DD z, DD t) {
        p.resetMomentum(x, y, z, t);
    }
}
