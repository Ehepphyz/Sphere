package com.sphere.core.fastjet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * A choice among jets, fastjet::Selector: by pt, rapidity, area, relation to
 * a reference jet, or the n hardest, combined with and, or, not and the
 * product (apply the second, then the first).
 *
 * A selector that applies jet by jet can be asked about a single jet; one that
 * does not (SelectorNHardest and the combinations using it) only makes sense
 * applied to a list. One that takes a reference (circle, strip, pt fraction)
 * must be given it first; {@link #setReference} gives this selector its own
 * copy of the worker, so other selectors sharing it are left alone.
 */
public final class Selector implements Predicate<PseudoJet> {

    /** fastjet::SelectorWorker. */
    public interface Worker {
        boolean pass(PseudoJet jet);

        /** Sets to null the entries of a list that do not pass. */
        default void terminator(PseudoJet[] jets) {
            for (int i = 0; i < jets.length; i++) {
                if (jets[i] != null && !pass(jets[i])) jets[i] = null;
            }
        }

        default boolean appliesJetByJet() { return true; }

        default String description() { return "missing description"; }

        default boolean takesReference() { return false; }

        default void setReference(PseudoJet reference) {
            throw new FastJetException("set_reference(...) cannot be used for a selector worker that does not take a reference");
        }

        default Worker copy() {
            throw new FastJetException("this SelectorWorker has nothing to copy");
        }

        /** {rapmin, rapmax}. */
        default double[] rapidityExtent() {
            return new double[]{Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
        }

        default boolean isGeometric() { return false; }

        default boolean hasFiniteArea() {
            if (!isGeometric()) return false;
            final double[] r = rapidityExtent();
            return r[1] != Double.POSITIVE_INFINITY && -r[0] != Double.POSITIVE_INFINITY;
        }

        default boolean hasKnownArea() { return false; }

        default double knownArea() {
            throw new FastJetException("this selector has no computable area");
        }
    }

    private Worker worker;

    public Selector(Worker worker) {
        this.worker = worker;
    }

    /** A selector with no worker; using it is an error. */
    public Selector() {
    }

    public Worker worker() {
        return worker;
    }

    public Worker validatedWorker() {
        if (worker == null) {
            throw new FastJetException("Attempt to use Selector with no valid underlying worker");
        }
        return worker;
    }

    /* ------------------------------------------------------------------ */
    /* Applying                                                            */
    /* ------------------------------------------------------------------ */

    public boolean pass(PseudoJet jet) {
        if (!validatedWorker().appliesJetByJet()) {
            throw new FastJetException("Cannot apply this selector to an individual jet");
        }
        return worker.pass(jet);
    }

    @Override
    public boolean test(PseudoJet jet) {
        return pass(jet);
    }

    private PseudoJet[] terminated(Collection<PseudoJet> jets) {
        final PseudoJet[] ptrs = jets.toArray(new PseudoJet[0]);
        validatedWorker().terminator(ptrs);
        return ptrs;
    }

    /** The jets that pass, in their order. */
    public List<PseudoJet> apply(Collection<PseudoJet> jets) {
        final List<PseudoJet> result = new ArrayList<>();
        if (validatedWorker().appliesJetByJet()) {
            for (PseudoJet j : jets) {
                if (worker.pass(j)) result.add(j);
            }
        } else {
            for (PseudoJet j : terminated(jets)) {
                if (j != null) result.add(j);
            }
        }
        return result;
    }

    public int count(Collection<PseudoJet> jets) {
        return apply(jets).size();
    }

    /** The four-vector sum of those that pass. */
    public PseudoJet sum(Collection<PseudoJet> jets) {
        final PseudoJet s = new PseudoJet(0, 0, 0, 0);
        for (PseudoJet j : apply(jets)) {
            s.plusEqual(j);
        }
        return s;
    }

    public double scalarPtSum(Collection<PseudoJet> jets) {
        double s = 0;
        for (PseudoJet j : apply(jets)) {
            s += j.pt();
        }
        return s;
    }

    /** Splits the jets into those that pass and those that fail. */
    public void sift(Collection<PseudoJet> jets, List<PseudoJet> pass, List<PseudoJet> fail) {
        pass.clear();
        fail.clear();
        if (validatedWorker().appliesJetByJet()) {
            for (PseudoJet j : jets) {
                if (worker.pass(j)) pass.add(j); else fail.add(j);
            }
        } else {
            final List<PseudoJet> all = new ArrayList<>(jets);
            final PseudoJet[] t = terminated(all);
            for (int i = 0; i < t.length; i++) {
                if (t[i] != null) pass.add(all.get(i)); else fail.add(all.get(i));
            }
        }
    }

    public void nullifyNonSelected(PseudoJet[] jets) {
        validatedWorker().terminator(jets);
    }

    public boolean appliesJetByJet() {
        return validatedWorker().appliesJetByJet();
    }

    public double[] rapidityExtent() {
        return validatedWorker().rapidityExtent();
    }

    public String description() {
        return validatedWorker().description();
    }

    public boolean isGeometric() {
        return validatedWorker().isGeometric();
    }

    public boolean hasFiniteArea() {
        return validatedWorker().hasFiniteArea();
    }

    /** The area in the rapidity-azimuth plane, known or counted with ghosts. */
    public double area() {
        return area(GhostedAreaSpec.DEF_GHOST_AREA);
    }

    public double area(double ghostArea) {
        if (!isGeometric()) {
            throw new FastJetException("Attempt to obtain area from Selector for which this is not meaningful");
        }
        if (worker.hasKnownArea()) {
            return worker.knownArea();
        }
        final double[] r = rapidityExtent();
        final GhostedAreaSpec spec = new GhostedAreaSpec(r[0], r[1], 1, ghostArea);
        final List<PseudoJet> ghosts = new ArrayList<>();
        spec.addGhosts(ghosts);
        return spec.ghostArea() * apply(ghosts).size();
    }

    public boolean takesReference() {
        return validatedWorker().takesReference();
    }

    /** Gives the selector its reference jet, on a worker of its own. */
    public Selector setReference(PseudoJet reference) {
        if (!validatedWorker().takesReference()) {
            return this;
        }
        worker = worker.copy();
        worker.setReference(reference);
        return this;
    }

    @Override
    public String toString() {
        return worker == null ? "invalid selector" : description();
    }

    /* ------------------------------------------------------------------ */
    /* Combinations                                                        */
    /* ------------------------------------------------------------------ */

    public Selector and(Selector other) {
        return new Selector(new And(this, other));
    }

    public Selector or(Selector other) {
        return new Selector(new Or(this, other));
    }

    @Override
    public Selector negate() {
        return new Selector(new Not(this));
    }

    public Selector not() {
        return negate();
    }

    /** s1 * s2: s2 applied first, then s1 to what is left. */
    public Selector times(Selector other) {
        return new Selector(new Mult(this, other));
    }

    public static Selector not(Selector s) {
        return new Selector(new Not(s));
    }

    private static final class Identity implements Worker {
        public boolean pass(PseudoJet jet) { return true; }
        public void terminator(PseudoJet[] jets) { }
        public String description() { return "Identity"; }
        public boolean isGeometric() { return true; }
    }

    private static final class Not implements Worker {
        private final Selector s;

        Not(Selector s) { this.s = copyOf(s); }

        public Worker copy() { return new Not(s); }

        public boolean pass(PseudoJet jet) {
            if (!appliesJetByJet()) throw new FastJetException("Cannot apply this selector worker to an individual jet");
            return !s.pass(jet);
        }

        public boolean appliesJetByJet() { return s.appliesJetByJet(); }

        public void terminator(PseudoJet[] jets) {
            if (appliesJetByJet()) {
                defaultTerminator(this, jets);
                return;
            }
            final PseudoJet[] sJets = jets.clone();
            s.worker().terminator(sJets);
            for (int i = 0; i < sJets.length; i++) {
                if (sJets[i] != null) jets[i] = null;
            }
        }

        public String description() { return "!(" + s.description() + ")"; }
        public boolean isGeometric() { return s.isGeometric(); }
        public boolean takesReference() { return s.takesReference(); }
        public void setReference(PseudoJet ref) { s.setReference(ref); }
    }

    private abstract static class Binary implements Worker {
        final Selector s1;
        final Selector s2;
        final boolean jetByJet;
        final boolean reference;
        final boolean geometric;

        Binary(Selector a, Selector b) {
            s1 = copyOf(a);
            s2 = copyOf(b);
            jetByJet = s1.appliesJetByJet() && s2.appliesJetByJet();
            reference = s1.takesReference() || s2.takesReference();
            geometric = s1.isGeometric() && s2.isGeometric();
        }

        public boolean appliesJetByJet() { return jetByJet; }
        public boolean takesReference() { return reference; }

        public void setReference(PseudoJet centre) {
            s1.setReference(centre);
            s2.setReference(centre);
        }

        public boolean isGeometric() { return geometric; }
    }

    private static class And extends Binary {
        And(Selector a, Selector b) { super(a, b); }

        public Worker copy() { return new And(s1, s2); }

        public boolean pass(PseudoJet jet) {
            if (!appliesJetByJet()) throw new FastJetException("Cannot apply this selector worker to an individual jet");
            return s1.pass(jet) && s2.pass(jet);
        }

        public void terminator(PseudoJet[] jets) {
            if (appliesJetByJet()) {
                defaultTerminator(this, jets);
                return;
            }
            final PseudoJet[] s1Jets = jets.clone();
            s1.worker().terminator(s1Jets);
            s2.worker().terminator(jets);
            for (int i = 0; i < jets.length; i++) {
                if (s1Jets[i] == null) jets[i] = null;
            }
        }

        public double[] rapidityExtent() {
            final double[] a = s1.rapidityExtent();
            final double[] b = s2.rapidityExtent();
            return new double[]{Math.max(a[0], b[0]), Math.min(a[1], b[1])};
        }

        public String description() { return "(" + s1.description() + " && " + s2.description() + ")"; }
    }

    private static final class Or extends Binary {
        Or(Selector a, Selector b) { super(a, b); }

        public Worker copy() { return new Or(s1, s2); }

        public boolean pass(PseudoJet jet) {
            if (!appliesJetByJet()) throw new FastJetException("Cannot apply this selector worker to an individual jet");
            return s1.pass(jet) || s2.pass(jet);
        }

        public void terminator(PseudoJet[] jets) {
            if (appliesJetByJet()) {
                defaultTerminator(this, jets);
                return;
            }
            final PseudoJet[] s1Jets = jets.clone();
            s1.worker().terminator(s1Jets);
            s2.worker().terminator(jets);
            for (int i = 0; i < jets.length; i++) {
                if (s1Jets[i] != null) jets[i] = s1Jets[i];
            }
        }

        public String description() { return "(" + s1.description() + " || " + s2.description() + ")"; }

        public double[] rapidityExtent() {
            final double[] a = s1.rapidityExtent();
            final double[] b = s2.rapidityExtent();
            return new double[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])};
        }
    }

    private static final class Mult extends And {
        Mult(Selector a, Selector b) { super(a, b); }

        public Worker copy() { return new Mult(s1, s2); }

        public void terminator(PseudoJet[] jets) {
            if (appliesJetByJet()) {
                for (int i = 0; i < jets.length; i++) {
                    if (jets[i] != null && !pass(jets[i])) jets[i] = null;
                }
                return;
            }
            s2.worker().terminator(jets);
            s1.worker().terminator(jets);
        }

        public String description() { return "(" + s1.description() + " * " + s2.description() + ")"; }
    }

    /** SelectorWorker::terminator, for workers that override it. */
    private static void defaultTerminator(Worker w, PseudoJet[] jets) {
        for (int i = 0; i < jets.length; i++) {
            if (jets[i] != null && !w.pass(jets[i])) jets[i] = null;
        }
    }

    /** A selector sharing nothing mutable with the one given. */
    private static Selector copyOf(Selector s) {
        return new Selector(s.validatedWorker());
    }

    /* ------------------------------------------------------------------ */
    /* Cuts on a quantity                                                  */
    /* ------------------------------------------------------------------ */

    /** A quantity of a jet, compared with a cut that may be the square of what is described. */
    private interface Quantity {
        double of(PseudoJet jet);
        String name();
        default boolean geometric() { return false; }
        default boolean squared() { return false; }
    }

    private static final Quantity PT2 = new Quantity() {
        public double of(PseudoJet j) { return j.perp2(); }
        public String name() { return "pt"; }
        public boolean squared() { return true; }
    };
    private static final Quantity ET2 = new Quantity() {
        public double of(PseudoJet j) { return j.Et2(); }
        public String name() { return "Et"; }
        public boolean squared() { return true; }
    };
    private static final Quantity E = new Quantity() {
        public double of(PseudoJet j) { return j.E(); }
        public String name() { return "E"; }
    };
    private static final Quantity M2 = new Quantity() {
        public double of(PseudoJet j) { return j.m2(); }
        public String name() { return "mass"; }
        public boolean squared() { return true; }
    };
    private static final Quantity RAP = new Quantity() {
        public double of(PseudoJet j) { return j.rap(); }
        public String name() { return "rap"; }
        public boolean geometric() { return true; }
    };
    private static final Quantity ABS_RAP = new Quantity() {
        public double of(PseudoJet j) { return Math.abs(j.rap()); }
        public String name() { return "|rap|"; }
        public boolean geometric() { return true; }
    };
    private static final Quantity ETA = new Quantity() {
        public double of(PseudoJet j) { return j.eta(); }
        public String name() { return "eta"; }
    };
    private static final Quantity ABS_ETA = new Quantity() {
        public double of(PseudoJet j) { return Math.abs(j.eta()); }
        public String name() { return "|eta|"; }
        public boolean geometric() { return true; }
    };

    private static class QuantityMin implements Worker {
        final Quantity q;
        final double cut;
        final double shown;

        QuantityMin(Quantity q, double value) {
            this.q = q;
            this.shown = value;
            this.cut = q.squared() ? value * value : value;
        }

        public boolean pass(PseudoJet jet) { return q.of(jet) >= cut; }
        public String description() { return q.name() + " >= " + Fmt.g(shown); }
        public boolean isGeometric() { return q.geometric(); }
    }

    private static class QuantityMax implements Worker {
        final Quantity q;
        final double cut;
        final double shown;

        QuantityMax(Quantity q, double value) {
            this.q = q;
            this.shown = value;
            this.cut = q.squared() ? value * value : value;
        }

        public boolean pass(PseudoJet jet) { return q.of(jet) <= cut; }
        public String description() { return q.name() + " <= " + Fmt.g(shown); }
        public boolean isGeometric() { return q.geometric(); }
    }

    private static class QuantityRange implements Worker {
        final Quantity q;
        final double min, max, shownMin, shownMax;

        QuantityRange(Quantity q, double lo, double hi) {
            this.q = q;
            shownMin = lo;
            shownMax = hi;
            min = q.squared() ? lo * lo : lo;
            max = q.squared() ? hi * hi : hi;
        }

        public boolean pass(PseudoJet jet) {
            final double v = q.of(jet);
            return v >= min && v <= max;
        }

        public String description() {
            return Fmt.g(shownMin) + " <= " + q.name() + " <= " + Fmt.g(shownMax);
        }

        public boolean isGeometric() { return q.geometric(); }
    }

    public static Selector identity() { return new Selector(new Identity()); }

    public static Selector ptMin(double ptmin) { return new Selector(new QuantityMin(PT2, ptmin)); }
    public static Selector ptMax(double ptmax) { return new Selector(new QuantityMax(PT2, ptmax)); }
    public static Selector ptRange(double lo, double hi) { return new Selector(new QuantityRange(PT2, lo, hi)); }
    public static Selector etMin(double v) { return new Selector(new QuantityMin(ET2, v)); }
    public static Selector etMax(double v) { return new Selector(new QuantityMax(ET2, v)); }
    public static Selector etRange(double lo, double hi) { return new Selector(new QuantityRange(ET2, lo, hi)); }
    public static Selector eMin(double v) { return new Selector(new QuantityMin(E, v)); }
    public static Selector eMax(double v) { return new Selector(new QuantityMax(E, v)); }
    public static Selector eRange(double lo, double hi) { return new Selector(new QuantityRange(E, lo, hi)); }
    public static Selector massMin(double v) { return new Selector(new QuantityMin(M2, v)); }
    public static Selector massMax(double v) { return new Selector(new QuantityMax(M2, v)); }
    public static Selector massRange(double lo, double hi) { return new Selector(new QuantityRange(M2, lo, hi)); }
    public static Selector etaMin(double v) { return new Selector(new QuantityMin(ETA, v)); }
    public static Selector etaMax(double v) { return new Selector(new QuantityMax(ETA, v)); }
    public static Selector etaRange(double lo, double hi) { return new Selector(new QuantityRange(ETA, lo, hi)); }
    public static Selector absEtaMin(double v) { return new Selector(new QuantityMin(ABS_ETA, v)); }
    public static Selector absEtaMax(double v) { return new Selector(new QuantityMax(ABS_ETA, v)); }
    public static Selector absEtaRange(double lo, double hi) { return new Selector(new QuantityRange(ABS_ETA, lo, hi)); }

    public static Selector rapMin(double rapmin) {
        return new Selector(new QuantityMin(RAP, rapmin) {
            public double[] rapidityExtent() { return new double[]{cut, Double.MAX_VALUE}; }
        });
    }

    public static Selector rapMax(double rapmax) {
        return new Selector(new QuantityMax(RAP, rapmax) {
            public double[] rapidityExtent() { return new double[]{-Double.MAX_VALUE, cut}; }
        });
    }

    public static Selector rapRange(double rapmin, double rapmax) {
        if (rapmin > rapmax) throw new FastJetException("SelectorRapRange: rapmin > rapmax");
        return new Selector(new QuantityRange(RAP, rapmin, rapmax) {
            public double[] rapidityExtent() { return new double[]{min, max}; }
            public boolean hasKnownArea() { return true; }
            public double knownArea() { return PseudoJet.TWOPI * (max - min); }
        });
    }

    public static Selector absRapMin(double v) { return new Selector(new QuantityMin(ABS_RAP, v)); }

    public static Selector absRapMax(double absrapmax) {
        return new Selector(new QuantityMax(ABS_RAP, absrapmax) {
            public double[] rapidityExtent() { return new double[]{-cut, cut}; }
            public boolean hasKnownArea() { return true; }
            public double knownArea() { return PseudoJet.TWOPI * 2 * cut; }
        });
    }

    public static Selector absRapRange(double lo, double hi) {
        return new Selector(new QuantityRange(ABS_RAP, lo, hi) {
            public double[] rapidityExtent() { return new double[]{-max, max}; }
            public boolean hasKnownArea() { return true; }
            public double knownArea() { return PseudoJet.TWOPI * 2 * (max - Math.max(min, 0.0)); }
        });
    }

    public static Selector phiRange(double phimin, double phimax) {
        return new Selector(new PhiRange(phimin, phimax));
    }

    private static final class PhiRange implements Worker {
        final double phimin, phimax, phispan;

        PhiRange(double phimin, double phimax) {
            if (!(phimin < phimax && phimin > -PseudoJet.TWOPI && phimax < 2 * PseudoJet.TWOPI)) {
                throw new FastJetException("SelectorPhiRange: invalid range " + phimin + " .. " + phimax);
            }
            this.phimin = phimin;
            this.phimax = phimax;
            this.phispan = phimax - phimin;
        }

        public boolean pass(PseudoJet jet) {
            double dphi = jet.phi() - phimin;
            if (dphi >= PseudoJet.TWOPI) dphi -= PseudoJet.TWOPI;
            if (dphi < 0) dphi += PseudoJet.TWOPI;
            return dphi <= phispan;
        }

        public String description() { return Fmt.g(phimin) + " <= phi <= " + Fmt.g(phimax); }
        public boolean isGeometric() { return true; }
    }

    public static Selector rapPhiRange(double rapmin, double rapmax, double phimin, double phimax) {
        final double known = ((phimax - phimin > PseudoJet.TWOPI) ? PseudoJet.TWOPI : phimax - phimin) * (rapmax - rapmin);
        return new Selector(new And(rapRange(rapmin, rapmax), phiRange(phimin, phimax)) {
            public double knownArea() { return known; }
        });
    }

    /** The n hardest jets in pt, applicable only to a list. */
    public static Selector nHardest(int n) {
        return new Selector(new NHardest(n));
    }

    private static final class NHardest implements Worker {
        private final int n;

        NHardest(int n) { this.n = n; }

        public boolean pass(PseudoJet jet) {
            throw new FastJetException("Cannot apply this selector worker to an individual jet");
        }

        public void terminator(PseudoJet[] jets) {
            if (jets.length < n) return;
            final Integer[] idx = new Integer[jets.length];
            final double[] minusPt2 = new double[jets.length];
            for (int i = 0; i < jets.length; i++) {
                idx[i] = i;
                minusPt2[i] = jets[i] != null ? -jets[i].perp2() : 0.0;
            }
            Arrays.sort(idx, (a, b) -> Double.compare(minusPt2[a], minusPt2[b]));
            for (int i = n; i < jets.length; i++) {
                jets[idx[i]] = null;
            }
        }

        public boolean appliesJetByJet() { return false; }
        public String description() { return n + " hardest"; }
    }

    /* ------------------------------------------------------------------ */
    /* Relative to a reference jet                                         */
    /* ------------------------------------------------------------------ */

    private abstract static class WithReference implements Worker {
        PseudoJet reference;
        boolean initialised;

        public boolean takesReference() { return true; }

        public void setReference(PseudoJet centre) {
            initialised = true;
            reference = centre.copy();
        }

        void check(String name) {
            if (!initialised) {
                throw new FastJetException("To use a " + name + " (or any selector that requires a reference), you first have to call set_reference(...)");
            }
        }

        <T extends WithReference> T withReferenceOf(T copy) {
            copy.reference = reference;
            copy.initialised = initialised;
            return copy;
        }
    }

    public static Selector circle(double radius) {
        return new Selector(new Circle(radius));
    }

    private static final class Circle extends WithReference {
        final double radius2;

        Circle(double radius) { radius2 = radius * radius; }

        public Worker copy() { return withReferenceOf(new Circle(Math.sqrt(radius2))); }

        public boolean pass(PseudoJet jet) {
            check("SelectorCircle");
            return jet.squaredDistance(reference) <= radius2;
        }

        public String description() { return "distance from the centre <= " + Fmt.g(Math.sqrt(radius2)); }

        public double[] rapidityExtent() {
            check("SelectorCircle");
            return new double[]{reference.rap() - Math.sqrt(radius2), reference.rap() + Math.sqrt(radius2)};
        }

        public boolean isGeometric() { return true; }
        public boolean hasFiniteArea() { return true; }
        public boolean hasKnownArea() { return true; }
        public double knownArea() { return Math.PI * radius2; }
    }

    public static Selector doughnut(double radiusIn, double radiusOut) {
        return new Selector(new Doughnut(radiusIn, radiusOut));
    }

    private static final class Doughnut extends WithReference {
        final double in2, out2, in, out;

        Doughnut(double in, double out) {
            this.in = in;
            this.out = out;
            in2 = in * in;
            out2 = out * out;
        }

        public Worker copy() { return withReferenceOf(new Doughnut(in, out)); }

        public boolean pass(PseudoJet jet) {
            check("SelectorDoughnut");
            final double d2 = jet.squaredDistance(reference);
            return d2 <= out2 && d2 >= in2;
        }

        public String description() {
            return Fmt.g(Math.sqrt(in2)) + " <= distance from the centre <= " + Fmt.g(Math.sqrt(out2));
        }

        public double[] rapidityExtent() {
            check("SelectorDoughnut");
            return new double[]{reference.rap() - Math.sqrt(out2), reference.rap() + Math.sqrt(out2)};
        }

        public boolean isGeometric() { return true; }
        public boolean hasFiniteArea() { return true; }
        public boolean hasKnownArea() { return true; }
        public double knownArea() { return Math.PI * (out2 - in2); }
    }

    public static Selector strip(double halfWidth) {
        return new Selector(new Strip(halfWidth));
    }

    private static final class Strip extends WithReference {
        final double delta;

        Strip(double delta) { this.delta = delta; }

        public Worker copy() { return withReferenceOf(new Strip(delta)); }

        public boolean pass(PseudoJet jet) {
            check("SelectorStrip");
            return Math.abs(jet.rap() - reference.rap()) <= delta;
        }

        public String description() { return "|rap - rap_reference| <= " + Fmt.g(delta); }

        public double[] rapidityExtent() {
            check("SelectorStrip");
            return new double[]{reference.rap() - delta, reference.rap() + delta};
        }

        public boolean isGeometric() { return true; }
        public boolean hasFiniteArea() { return true; }
        public boolean hasKnownArea() { return true; }
        public double knownArea() { return PseudoJet.TWOPI * 2 * delta; }
    }

    public static Selector rectangle(double halfRapWidth, double halfPhiWidth) {
        return new Selector(new Rectangle(halfRapWidth, halfPhiWidth));
    }

    private static final class Rectangle extends WithReference {
        final double drap, dphi;

        Rectangle(double drap, double dphi) {
            this.drap = drap;
            this.dphi = dphi;
        }

        public Worker copy() { return withReferenceOf(new Rectangle(drap, dphi)); }

        public boolean pass(PseudoJet jet) {
            check("SelectorRectangle");
            return Math.abs(jet.rap() - reference.rap()) <= drap
                && Math.abs(jet.deltaPhiTo(reference)) <= dphi;
        }

        public String description() {
            return "|rap - rap_reference| <= " + Fmt.g(drap) + " && |phi - phi_reference| <= " + Fmt.g(dphi);
        }

        public double[] rapidityExtent() {
            check("SelectorRectangle");
            return new double[]{reference.rap() - drap, reference.rap() + drap};
        }

        public boolean isGeometric() { return true; }
        public boolean hasFiniteArea() { return true; }
        public boolean hasKnownArea() { return true; }
        public double knownArea() { return 4 * drap * dphi; }
    }

    /** Jets carrying at least a fraction of the reference's pt. */
    public static Selector ptFractionMin(double fraction) {
        return new Selector(new PtFractionMin(fraction));
    }

    private static final class PtFractionMin extends WithReference {
        final double fraction2;
        final double fraction;

        PtFractionMin(double fraction) {
            this.fraction = fraction;
            fraction2 = fraction * fraction;
        }

        public Worker copy() { return withReferenceOf(new PtFractionMin(fraction)); }

        public boolean pass(PseudoJet jet) {
            check("SelectorPtFractionMin");
            return jet.perp2() >= fraction2 * reference.perp2();
        }

        public String description() { return "pt >= " + Fmt.g(Math.sqrt(fraction2)) + "* pt_ref"; }
    }

    /** Jets of exactly zero four-momentum. */
    public static Selector isZero() {
        return new Selector(new Worker() {
            public boolean pass(PseudoJet jet) { return jet.isZero(); }
            public String description() { return "zero"; }
        });
    }

    /** Jets made only of ghosts. */
    public static Selector isPureGhost() {
        return new Selector(new Worker() {
            public boolean pass(PseudoJet jet) {
                if (!jet.hasArea()) return false;
                return jet.isPureGhost();
            }
            public String description() { return "pure ghost"; }
        });
    }

    /** Any predicate as a selector, applied jet by jet. */
    public static Selector of(String description, Predicate<PseudoJet> predicate) {
        return new Selector(new Worker() {
            public boolean pass(PseudoJet jet) { return predicate.test(jet); }
            public String description() { return description; }
        });
    }
}
