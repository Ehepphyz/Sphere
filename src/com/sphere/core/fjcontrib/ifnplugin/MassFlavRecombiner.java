package com.sphere.core.fjcontrib.ifnplugin;

import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.PseudoJet;

/**
 * A flavour recombiner that carries the mass of each object alongside its
 * flavour, fastjet::contrib::MassFlavRecombiner (IFNPlugin's MassFlav.hh):
 * two objects far forward are recombined after a common rapidity shift, so
 * that the rapidity of the sum stays accurate at rapidities where E and pz
 * have cancelled all their digits. Meant for infrared and collinear safety
 * tests with extreme kinematics.
 */
public class MassFlavRecombiner extends FlavRecombiner {

    /** A FlavHistory with the mass of the object, MassFlavHistory. */
    public static class MassFlavHistory extends FlavHistory {
        private double mass;

        public MassFlavHistory(FlavHistory history, double mass) {
            super(history);
            this.mass = mass;
        }

        public void setMass(double m) {
            mass = m;
        }

        public double mass() {
            return mass;
        }
    }

    public MassFlavRecombiner() {
        super();
    }

    public MassFlavRecombiner(FlavSummation summation) {
        super(summation);
    }

    @Override
    public void recombine(PseudoJet pa, PseudoJet pb, PseudoJet pab) {
        // both rapidities small, or one small and the result small too:
        // the plain 4-vector sum keeps small components accurate
        final double rapLim = 0.1;
        final boolean paSmall = Math.abs(pa.rap()) < rapLim;
        final boolean pbSmall = Math.abs(pb.rap()) < rapLim;
        final double pabApproxRap = (pa.pt() * pa.rap() + pb.pt() * pb.rap()) / (pa.pt() + pb.pt());
        final boolean pabSmall = Math.abs(pabApproxRap) < rapLim;
        final int nSmall = (paSmall ? 1 : 0) + (pbSmall ? 1 : 0) + (pabSmall ? 1 : 0);
        if (nSmall >= 2) {
            super.recombine(pa, pb, pab);
            pab.setUserInfo(new MassFlavHistory(pab.userInfo(FlavHistory.class), pab.m()));
            return;
        }
        final double ma = massOfParticle(pa);
        final double mb = massOfParticle(pb);
        final double avrap = (pa.rap() + pb.rap()) / 2;
        final PseudoJet shiftedA = PseudoJet.ptYPhiM(pa.pt(), pa.rap() - avrap, pa.phi(), ma);
        final PseudoJet shiftedB = PseudoJet.ptYPhiM(pb.pt(), pb.rap() - avrap, pb.phi(), mb);
        shiftedA.setUserInfo(new MassFlavHistory(pa.userInfo(FlavHistory.class), ma));
        shiftedB.setUserInfo(new MassFlavHistory(pb.userInfo(FlavHistory.class), mb));
        final PseudoJet shiftedAB = new PseudoJet();
        super.recombine(shiftedA, shiftedB, shiftedAB);
        final PseudoJet out = PseudoJet.ptYPhiM(shiftedAB.pt(), shiftedAB.rap() + avrap, shiftedAB.phi(), shiftedAB.m());
        pab.reset(out);
        pab.setUserInfo(new MassFlavHistory(shiftedAB.userInfo(FlavHistory.class), shiftedAB.m()));
    }

    /** The mass carried, or zero for an input whose mass is zero to rounding. */
    public double massOfParticle(PseudoJet p) {
        if (p.hasUserInfo(MassFlavHistory.class)) return p.userInfo(MassFlavHistory.class).mass();
        final double m2 = p.m2();
        final double safety = 10.0;
        if (Math.abs(m2) > safety * Math.ulp(1.0) * Math.pow(p.E(), 2)) {
            throw new FastJetException(
                "MassFlavRecombiner found particle without MassFlavHistory, whose mass is inconsistent with zero");
        }
        return 0.0;
    }
}
