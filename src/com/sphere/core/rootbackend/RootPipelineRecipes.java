package com.sphere.core.rootbackend;

import java.util.ArrayList;
import java.util.List;

import static com.sphere.core.rootbackend.RootPipelineManifest.Input;
import static com.sphere.core.rootbackend.RootPipelineManifest.Kind;

/**
 * Quantities a physicist writes again in every analysis.
 *
 * A blank body that returns its first argument teaches nothing and is thrown
 * away immediately. These are the real ones, written the way they should be:
 * the azimuthal difference folded before it is squared, a division guarded
 * against a zero denominator, an empty collection answered rather than indexed.
 * Picking one fills the whole form -- kind, inputs, modules and body -- so the
 * pipeline that comes out is correct before a single character is typed, and
 * the user edits physics rather than boilerplate.
 */
public final class RootPipelineRecipes {

    /** What a recipe is about, so a long list still reads as a catalogue. */
    public enum Family {
        START("start"),
        KINEMATICS("kinematics"),
        SELECTION("selection"),
        STATISTICS("statistics"),
        PARTONS("parton densities"),
        SHAPES("shapes"),
        NORMALIZATION("normalization");

        public final String label;

        Family(String label) {
            this.label = label;
        }
    }

    /** One ready-made pipeline: everything the form needs, and the body. */
    public record Recipe(Family family, String title, String about, Kind kind,
                         List<Input> inputs, List<RootPipelineModules> modules,
                         String body) {
        @Override
        public String toString() {
            return title;
        }
    }

    private RootPipelineRecipes() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    private static Input in(String name, String type) {
        return new Input(name, type);
    }

    private static List<RootPipelineModules> mods(RootPipelineModules... list) {
        return List.of(list);
    }

    private static final List<Recipe> ALL = List.of(

        new Recipe(Family.START, "Empty", "Start from nothing.", Kind.TRANSFORM,
            List.of(in("x", "double")), mods(RootPipelineModules.MATH),
            null),

        new Recipe(Family.KINEMATICS, "Azimuthal difference",
            "The angle between two objects, folded into (-pi, pi]",
            Kind.TRANSFORM,
            List.of(in("phi1", "double"), in("phi2", "double")),
            mods(RootPipelineModules.MATH),
            """
              // Folding matters: an unfolded difference of 6.1 is really 0.18,
              // and every quantity built on it would be wrong by 2 pi.
              double d = phi1 - phi2;
              while (d > TMath::Pi()) {
                d -= 2.0 * TMath::Pi();
              }
              while (d <= -TMath::Pi()) {
                d += 2.0 * TMath::Pi();
              }
              return d;
            """),

        new Recipe(Family.KINEMATICS, "Angular distance deltaR",
            "Distance in the eta-phi plane between two objects",
            Kind.TRANSFORM,
            List.of(in("eta1", "double"), in("phi1", "double"),
                    in("eta2", "double"), in("phi2", "double")),
            mods(RootPipelineModules.MATH),
            """
              const double dEta = eta1 - eta2;
              double dPhi = phi1 - phi2;
              while (dPhi > TMath::Pi()) {
                dPhi -= 2.0 * TMath::Pi();
              }
              while (dPhi <= -TMath::Pi()) {
                dPhi += 2.0 * TMath::Pi();
              }
              return TMath::Sqrt(dEta * dEta + dPhi * dPhi);
            """),

        new Recipe(Family.KINEMATICS, "Invariant mass of two objects",
            "The mass of the pair, from each object's pt, eta, phi and mass",
            Kind.TRANSFORM,
            List.of(in("pt1", "double"), in("eta1", "double"),
                    in("phi1", "double"), in("m1", "double"),
                    in("pt2", "double"), in("eta2", "double"),
                    in("phi2", "double"), in("m2", "double")),
            mods(RootPipelineModules.MATH, RootPipelineModules.LORENTZ),
            """
              // GenVector rather than TLorentzVector: the same physics, without
              // the legacy class ROOT itself now advises against.
              const ROOT::Math::PtEtaPhiMVector a(pt1, eta1, phi1, m1);
              const ROOT::Math::PtEtaPhiMVector b(pt2, eta2, phi2, m2);
              return (a + b).M();
            """),

        new Recipe(Family.KINEMATICS, "Transverse mass",
            "Transverse mass of an object and the missing energy",
            Kind.TRANSFORM,
            List.of(in("pt", "double"), in("met", "double"), in("dphi", "double")),
            mods(RootPipelineModules.MATH),
            """
              const double inside = 2.0 * pt * met * (1.0 - TMath::Cos(dphi));
              // Rounding can put a quantity that is zero on physical grounds
              // just below it, and a square root would then answer NaN.
              return inside > 0.0 ? TMath::Sqrt(inside) : 0.0;
            """),

        new Recipe(Family.KINEMATICS, "Pseudorapidity from the polar angle",
            "Eta from theta, refusing the two directions where it is infinite",
            Kind.TRANSFORM,
            List.of(in("theta", "double")),
            mods(RootPipelineModules.MATH),
            """
              const double half = 0.5 * theta;
              const double t = TMath::Tan(half);
              // Exactly along the beam eta is unbounded, so the two ends are
              // answered with a large finite value instead of an infinity that
              // would poison every histogram it reaches.
              if (t <= 0.0) {
                return 10.0;
              }
              const double eta = -TMath::Log(t);
              return eta > 10.0 ? 10.0 : (eta < -10.0 ? -10.0 : eta);
            """),

        new Recipe(Family.KINEMATICS, "Rapidity",
            "Rapidity from the energy and the longitudinal momentum",
            Kind.TRANSFORM,
            List.of(in("energy", "double"), in("pz", "double")),
            mods(RootPipelineModules.MATH),
            """
              const double up = energy + pz;
              const double down = energy - pz;
              if (up <= 0.0 || down <= 0.0) {
                return 0.0;
              }
              return 0.5 * TMath::Log(up / down);
            """),

        new Recipe(Family.KINEMATICS, "Relative isolation",
            "Energy around an object divided by its own transverse momentum",
            Kind.TRANSFORM,
            List.of(in("sumPt", "double"), in("pt", "double")),
            mods(RootPipelineModules.MATH),
            """
              // An object with no transverse momentum has no isolation to speak
              // of; answering a large number keeps it out of the tight region.
              if (pt <= 0.0) {
                return 999.0;
              }
              return sumPt / pt;
            """),

        new Recipe(Family.KINEMATICS, "Highest pt of a collection",
            "The leading object's transverse momentum, per entry",
            Kind.TRANSFORM,
            List.of(in("pt", "ROOT::RVec<double>")),
            mods(RootPipelineModules.MATH, RootPipelineModules.VECTORS),
            """
              // An entry with no object is normal, not an error, so it is
              // answered rather than indexed.
              if (pt.empty()) {
                return -1.0;
              }
              double best = pt[0];
              for (std::size_t i = 1; i < pt.size(); ++i) {
                if (pt[i] > best) {
                  best = pt[i];
                }
              }
              return best;
            """),

        new Recipe(Family.KINEMATICS, "Scalar sum HT",
            "Sum of the transverse momenta above a threshold",
            Kind.TRANSFORM,
            List.of(in("pt", "ROOT::RVec<double>"), in("minPt", "double")),
            mods(RootPipelineModules.MATH, RootPipelineModules.VECTORS),
            """
              double total = 0.0;
              for (std::size_t i = 0; i < pt.size(); ++i) {
                if (pt[i] > minPt) {
                  total += pt[i];
                }
              }
              return total;
            """),

        new Recipe(Family.KINEMATICS, "Count above a threshold",
            "How many objects of the collection pass a cut",
            Kind.TRANSFORM,
            List.of(in("value", "ROOT::RVec<double>"), in("cut", "double")),
            mods(RootPipelineModules.VECTORS),
            """
              double found = 0.0;
              for (std::size_t i = 0; i < value.size(); ++i) {
                if (value[i] > cut) {
                  found += 1.0;
                }
              }
              return found;
            """),

        new Recipe(Family.SELECTION, "Filter: mass window",
            "Keeps the entries whose mass sits inside a window",
            Kind.FILTER,
            List.of(in("mass", "double"), in("center", "double"), in("halfWidth", "double")),
            mods(RootPipelineModules.MATH),
            """
              return TMath::Abs(mass - center) < halfWidth;
            """),

        new Recipe(Family.SELECTION, "Filter: at least N above a threshold",
            "Keeps the entries with enough objects passing a cut",
            Kind.FILTER,
            List.of(in("pt", "ROOT::RVec<double>"), in("cut", "double"), in("least", "int")),
            mods(RootPipelineModules.VECTORS),
            """
              int found = 0;
              for (std::size_t i = 0; i < pt.size(); ++i) {
                if (pt[i] > cut) {
                  found++;
                  // Counting the rest would change nothing once the entry is in.
                  if (found >= least) {
                    return true;
                  }
                }
              }
              return false;
            """),

        new Recipe(Family.SELECTION, "Filter: central and hard",
            "Keeps the entries whose object is central and energetic enough",
            Kind.FILTER,
            List.of(in("pt", "double"), in("eta", "double"),
                    in("minPt", "double"), in("maxEta", "double")),
            mods(RootPipelineModules.MATH),
            """
              return pt > minPt && TMath::Abs(eta) < maxEta;
            """),

        new Recipe(Family.KINEMATICS, "Histogram of a branch",
            "Reads one branch of the tree and fills a histogram from it",
            Kind.HISTOGRAM,
            List.of(),
            mods(RootPipelineModules.MATH, RootPipelineModules.TREE,
                 RootPipelineModules.HIST),
            """
              TH1D *out = new TH1D("NAME_h", "NAME", 100, 0.0, 200.0);
              out->SetDirectory(nullptr);
              if (tree == nullptr) {
                return out;
              }
              // Change the branch name and its type to match the tree.
              TTreeReader reader(tree);
              TTreeReaderValue<Float_t> value(reader, "pt");
              while (reader.Next()) {
                out->Fill(*value);
              }
              return out;
            """),

        new Recipe(Family.KINEMATICS, "Histogram of a collection",
            "Fills one entry per object of a per-event collection",
            Kind.HISTOGRAM,
            List.of(),
            mods(RootPipelineModules.MATH, RootPipelineModules.TREE,
                 RootPipelineModules.HIST, RootPipelineModules.VECTORS),
            """
              TH1D *out = new TH1D("NAME_h", "NAME", 100, 0.0, 200.0);
              out->SetDirectory(nullptr);
              if (tree == nullptr) {
                return out;
              }
              // TTreeReaderArray, not Value: a collection has one length per
              // entry and reading it as a scalar would take only the first.
              TTreeReader reader(tree);
              TTreeReaderArray<Float_t> values(reader, "Jet_pt");
              while (reader.Next()) {
                for (std::size_t i = 0; i < values.GetSize(); ++i) {
                  out->Fill(values[i]);
                }
              }
              return out;
            """),

        // ---- statistics: what a counting experiment is worth ----------------

        new Recipe(Family.STATISTICS, "Discovery significance (Asimov)",
            "Expected significance of s signal events over b background",
            Kind.TRANSFORM,
            List.of(in("s", "double"), in("b", "double")),
            mods(RootPipelineModules.MATH),
            """
              // Equation 97 of Cowan, Cranmer, Gross and Vitells,
              // Eur. Phys. J. C71 (2011) 1554. This is the expression, not the
              // s/sqrt(b) approximation, which is only valid for s much
              // smaller than b and overstates the reach otherwise.
              if (b <= 0.0 || s <= 0.0) {
                return 0.0;
              }
              const double inside = (s + b) * TMath::Log(1.0 + s / b) - s;
              return inside > 0.0 ? TMath::Sqrt(2.0 * inside) : 0.0;
            """),

        new Recipe(Family.STATISTICS, "Discovery significance with background uncertainty",
            "The same, when the background itself is known only to sigmaB",
            Kind.TRANSFORM,
            List.of(in("s", "double"), in("b", "double"), in("sigmaB", "double")),
            mods(RootPipelineModules.MATH),
            """
              // The background is profiled out under a Gaussian constraint.
              // With sigmaB at zero this reduces to the expression above, which
              // is the check worth running after any change here.
              if (b <= 0.0 || s <= 0.0) {
                return 0.0;
              }
              if (sigmaB <= 0.0) {
                const double plain = (s + b) * TMath::Log(1.0 + s / b) - s;
                return plain > 0.0 ? TMath::Sqrt(2.0 * plain) : 0.0;
              }
              const double n = s + b;
              const double v = sigmaB * sigmaB;
              // The background that best fits the Asimov data under the
              // constraint: the positive root, since a yield cannot be negative.
              const double bHat =
                  ((b - v) + TMath::Sqrt((b - v) * (b - v) + 4.0 * n * v)) / 2.0;
              if (bHat <= 0.0) {
                return 0.0;
              }
              const double inside =
                  -2.0 * (n * TMath::Log(bHat / n) + n - bHat
                          - (b - bHat) * (b - bHat) / (2.0 * v));
              return inside > 0.0 ? TMath::Sqrt(inside) : 0.0;
            """),

        new Recipe(Family.STATISTICS, "Naive significance s over sqrt(b)",
            "The old approximation, for comparing against the Asimov one",
            Kind.TRANSFORM,
            List.of(in("s", "double"), in("b", "double")),
            mods(RootPipelineModules.MATH),
            """
              // Kept because it is what everyone quotes, not because it is
              // right: it drifts high as soon as s stops being small next to b.
              return b > 0.0 ? s / TMath::Sqrt(b) : 0.0;
            """),

        new Recipe(Family.STATISTICS, "Poisson probability",
            "The chance of seeing exactly n when mean are expected",
            Kind.TRANSFORM,
            List.of(in("n", "double"), in("mean", "double")),
            mods(RootPipelineModules.MATH),
            """
              // Through logarithms: n! overflows a double above 170, while
              // lgamma does not, and the answer stays exact where it matters.
              if (mean <= 0.0 || n < 0.0) {
                return n == 0.0 && mean == 0.0 ? 1.0 : 0.0;
              }
              const double k = TMath::Floor(n + 0.5);
              return TMath::Exp(k * TMath::Log(mean) - mean - std::lgamma(k + 1.0));
            """),

        new Recipe(Family.STATISTICS, "Poisson p-value of an excess",
            "The chance that the background alone produced this many or more",
            Kind.TRANSFORM,
            List.of(in("observed", "double"), in("expected", "double")),
            mods(RootPipelineModules.MATH),
            """
              // One minus the sum below the observation, each term from the one
              // before it: a ratio, so no factorial is ever formed.
              if (expected <= 0.0) {
                return observed > 0.0 ? 0.0 : 1.0;
              }
              const long n = (long) TMath::Floor(observed + 0.5);
              if (n <= 0) {
                return 1.0;
              }
              double term = TMath::Exp(-expected);
              double below = term;
              for (long k = 1; k < n; ++k) {
                term *= expected / (double) k;
                below += term;
              }
              const double p = 1.0 - below;
              return p < 0.0 ? 0.0 : (p > 1.0 ? 1.0 : p);
            """),

        new Recipe(Family.STATISTICS, "p-value to significance",
            "A one-sided p-value turned into a number of sigma",
            Kind.TRANSFORM,
            List.of(in("p", "double")),
            mods(RootPipelineModules.MATH),
            """
              // Z is the upper-tail quantile of a standard normal, which the
              // inverse error function gives directly.
              if (p <= 0.0) {
                return 40.0;
              }
              if (p >= 1.0) {
                return 0.0;
              }
              return TMath::Sqrt(2.0) * TMath::ErfInverse(1.0 - 2.0 * p);
            """),

        new Recipe(Family.STATISTICS, "Significance to p-value",
            "A number of sigma turned back into a one-sided p-value",
            Kind.TRANSFORM,
            List.of(in("z", "double")),
            mods(RootPipelineModules.MATH),
            """
              return 0.5 * (1.0 - TMath::Erf(z / TMath::Sqrt(2.0)));
            """),

        new Recipe(Family.STATISTICS, "Binomial efficiency",
            "How many passed out of how many were tried",
            Kind.TRANSFORM,
            List.of(in("passed", "double"), in("total", "double")),
            mods(RootPipelineModules.MATH),
            """
              return total > 0.0 ? passed / total : 0.0;
            """),

        new Recipe(Family.STATISTICS, "Binomial efficiency error",
            "The spread on that efficiency, refusing the two ends where it is wrong",
            Kind.TRANSFORM,
            List.of(in("passed", "double"), in("total", "double")),
            mods(RootPipelineModules.MATH),
            """
              // The normal error collapses to zero when everything or nothing
              // passed, which is never true. Those two cases are answered with
              // the one-sided 68% bound instead, which is the honest value.
              if (total <= 0.0) {
                return 0.0;
              }
              const double e = passed / total;
              if (e <= 0.0 || e >= 1.0) {
                return 1.0 - TMath::Power(1.0 - 0.6827, 1.0 / total);
              }
              return TMath::Sqrt(e * (1.0 - e) / total);
            """),

        // ---- parton densities -----------------------------------------------

        new Recipe(Family.PARTONS, "Bjorken x of the first parton",
            "Momentum fraction carried by the parton going one way",
            Kind.TRANSFORM,
            List.of(in("mass", "double"), in("rapidity", "double"), in("sqrtS", "double")),
            mods(RootPipelineModules.MATH),
            """
              // For a system of mass M and rapidity y made at leading order,
              // x1 = (M / sqrt(s)) * exp(+y) and x2 the same with -y.
              if (sqrtS <= 0.0) {
                return 0.0;
              }
              const double x = (mass / sqrtS) * TMath::Exp(rapidity);
              return x > 1.0 ? 1.0 : x;
            """),

        new Recipe(Family.PARTONS, "Bjorken x of the second parton",
            "Momentum fraction carried by the parton going the other way",
            Kind.TRANSFORM,
            List.of(in("mass", "double"), in("rapidity", "double"), in("sqrtS", "double")),
            mods(RootPipelineModules.MATH),
            """
              if (sqrtS <= 0.0) {
                return 0.0;
              }
              const double x = (mass / sqrtS) * TMath::Exp(-rapidity);
              return x > 1.0 ? 1.0 : x;
            """),

        new Recipe(Family.PARTONS, "Parton distribution xf(x, Q)",
            "What a parton distribution set says, read by Sphere with nothing installed",
            Kind.TRANSFORM,
            List.of(in("flavor", "int"), in("x", "double"), in("q", "double")),
            mods(RootPipelineModules.MATH, RootPipelineModules.PDF),
            """
              // Point this at the set folder the analysis uses. It is read once
              // and kept: reading a grid per entry would dominate the run.
              // flavor follows the PDG numbering: 1 down, 2 up, 21 gluon.
              static sphere::Pdf set =
                sphere::Pdf::openSet("/path/to/NNPDF31_nnlo_as_0118");
              if (x <= 0.0 || x >= 1.0 || q <= 0.0) {
                return 0.0;
              }
              return set.xfxQ(flavor, x, q);
            """),

        new Recipe(Family.PARTONS, "Parton luminosity at a mass",
            "How many collisions of two partons are available at a given mass",
            Kind.TRANSFORM,
            List.of(in("flavor1", "int"), in("flavor2", "int"),
                    in("mass", "double"), in("collider", "double")),
            mods(RootPipelineModules.MATH, RootPipelineModules.PDF),
            """
              // The integral over the two momentum fractions that give this
              // mass, which is what decides whether a search is worth doing
              // before any matrix element is written. Both energies in GeV.
              static sphere::Pdf set =
                sphere::Pdf::openSet("/path/to/NNPDF31_nnlo_as_0118");
              if (mass <= 0.0 || collider <= 0.0 || mass >= collider) {
                return 0.0;
              }
              return set.luminosityAtMass(flavor1, flavor2, mass, collider);
            """),

        new Recipe(Family.PARTONS, "Two distributions multiplied",
            "The product at two fixed momentum fractions, before any integration",
            Kind.TRANSFORM,
            List.of(in("flavor1", "int"), in("flavor2", "int"),
                    in("x1", "double"), in("x2", "double"), in("q", "double")),
            mods(RootPipelineModules.MATH, RootPipelineModules.PDF),
            """
              static sphere::Pdf set =
                sphere::Pdf::openSet("/path/to/NNPDF31_nnlo_as_0118");
              if (q <= 0.0 || x1 <= 0.0 || x1 >= 1.0 || x2 <= 0.0 || x2 >= 1.0) {
                return 0.0;
              }
              // xf, not f, on both sides: the ratio is what cancels in the
              // usual definition, and mixing the two is a factor of x1*x2.
              return set.xfxQ(flavor1, x1, q) * set.xfxQ(flavor2, x2, q);
            """),

        new Recipe(Family.PARTONS, "Momentum carried by the partons",
            "One if the set is sound, which is worth asking before trusting it",
            Kind.TRANSFORM,
            List.of(in("q", "double")),
            mods(RootPipelineModules.MATH, RootPipelineModules.PDF),
            """
              // The fit constrains this to one. A set that answers anything
              // else has been damaged, most often by a download that stopped
              // part way, and nothing else about it announces that.
              static sphere::Pdf set =
                sphere::Pdf::openSet("/path/to/NNPDF31_nnlo_as_0118");
              if (q <= 0.0) {
                return 0.0;
              }
              return set.momentum(q * q);
            """),

        // ---- kinematics of a collision --------------------------------------

        new Recipe(Family.KINEMATICS, "Collins-Soper cos theta*",
            "The decay angle in the frame that halves the quark direction ambiguity",
            Kind.TRANSFORM,
            List.of(in("e1", "double"), in("pz1", "double"),
                    in("e2", "double"), in("pz2", "double"),
                    in("mass", "double"), in("qt", "double"), in("pzPair", "double")),
            mods(RootPipelineModules.MATH),
            """
              // Light-cone components, p+- = (E +- pz)/sqrt(2). The factor two
              // and the two halves cancel, so the products are written plainly.
              // The sign follows the longitudinal boost of the pair, which is
              // the best guess at which way the quark went.
              if (mass <= 0.0) {
                return 0.0;
              }
              const double a = (e1 + pz1) * (e2 - pz2);
              const double b = (e1 - pz1) * (e2 + pz2);
              const double norm = mass * TMath::Sqrt(mass * mass + qt * qt);
              if (norm <= 0.0) {
                return 0.0;
              }
              const double cs = (a - b) / norm;
              const double signed_ = pzPair < 0.0 ? -cs : cs;
              return signed_ > 1.0 ? 1.0 : (signed_ < -1.0 ? -1.0 : signed_);
            """),

        new Recipe(Family.KINEMATICS, "Invariant mass of a collection",
            "The mass of everything in the event taken together",
            Kind.TRANSFORM,
            List.of(in("pt", "ROOT::RVec<double>"), in("eta", "ROOT::RVec<double>"),
                    in("phi", "ROOT::RVec<double>"), in("m", "ROOT::RVec<double>")),
            mods(RootPipelineModules.MATH, RootPipelineModules.VECTORS,
                 RootPipelineModules.LORENTZ),
            """
              // The four branches are read together, so the shortest of them
              // decides how far the sum goes rather than an index running off.
              std::size_t n = pt.size();
              n = eta.size() < n ? eta.size() : n;
              n = phi.size() < n ? phi.size() : n;
              n = m.size() < n ? m.size() : n;
              if (n == 0) {
                return 0.0;
              }
              // Summed in Cartesian coordinates: adding in pt, eta, phi and
              // mass would convert back and forth at every step and lose
              // precision for no reason.
              ROOT::Math::PxPyPzEVector total(0.0, 0.0, 0.0, 0.0);
              for (std::size_t i = 0; i < n; ++i) {
                total += ROOT::Math::PxPyPzEVector(
                    ROOT::Math::PtEtaPhiMVector(pt[i], eta[i], phi[i], m[i]));
              }
              return total.M();
            """),

        new Recipe(Family.KINEMATICS, "Missing energy significance",
            "Missing transverse energy against the energy that was seen",
            Kind.TRANSFORM,
            List.of(in("met", "double"), in("sumEt", "double")),
            mods(RootPipelineModules.MATH),
            """
              // Missing energy grows with the square root of what was measured,
              // so the ratio to that square root is what separates a real
              // imbalance from ordinary resolution.
              return sumEt > 0.0 ? met / TMath::Sqrt(sumEt) : 0.0;
            """),

        new Recipe(Family.KINEMATICS, "Lorentz gamma",
            "The time dilation factor of a particle, from its energy and mass",
            Kind.TRANSFORM,
            List.of(in("energy", "double"), in("mass", "double")),
            mods(RootPipelineModules.MATH),
            """
              // A massless particle has no rest frame, so it has no gamma.
              return mass > 0.0 ? energy / mass : 0.0;
            """),

        new Recipe(Family.KINEMATICS, "Proper decay length",
            "How far a particle of this lifetime travels before decaying",
            Kind.TRANSFORM,
            List.of(in("flightDistance", "double"), in("momentum", "double"),
                    in("mass", "double")),
            mods(RootPipelineModules.MATH),
            """
              // ct = L * m / p, which is the quantity that does not depend on
              // how fast this particular one happened to be going.
              return momentum > 0.0 ? flightDistance * mass / momentum : 0.0;
            """),

        // ---- the shapes a fit is made of ------------------------------------

        new Recipe(Family.SHAPES, "Relativistic Breit-Wigner",
            "The shape of a resonance, as ROOT defines it",
            Kind.TRANSFORM,
            List.of(in("x", "double"), in("mass", "double"), in("width", "double")),
            mods(RootPipelineModules.MATH),
            """
              return TMath::BreitWignerRelativistic(x, mass, width);
            """),

        new Recipe(Family.SHAPES, "Crystal Ball",
            "A Gaussian with the power-law tail a calorimeter really has",
            Kind.TRANSFORM,
            List.of(in("x", "double"), in("mean", "double"), in("sigma", "double"),
                    in("alpha", "double"), in("n", "double")),
            mods(RootPipelineModules.MATH),
            """
              // Gaussian above -alpha sigma, a power law below it, joined so
              // that the value and the slope both match at the crossing.
              if (sigma <= 0.0 || n <= 1.0) {
                return 0.0;
              }
              const double t = (x - mean) / sigma;
              const double a = TMath::Abs(alpha);
              if (t > -a) {
                return TMath::Exp(-0.5 * t * t);
              }
              const double head = TMath::Power(n / a, n) * TMath::Exp(-0.5 * a * a);
              const double tail = n / a - a - t;
              return tail > 0.0 ? head * TMath::Power(tail, -n) : 0.0;
            """),

        new Recipe(Family.SHAPES, "Exponential background",
            "The falling shape a continuum usually takes, normalized on a window",
            Kind.TRANSFORM,
            List.of(in("x", "double"), in("slope", "double"),
                    in("low", "double"), in("high", "double")),
            mods(RootPipelineModules.MATH),
            """
              // Normalized over the window, so the value does not change when
              // the fit range is moved.
              if (high <= low || slope == 0.0) {
                return high > low ? 1.0 / (high - low) : 0.0;
              }
              const double area =
                  (TMath::Exp(slope * high) - TMath::Exp(slope * low)) / slope;
              return area != 0.0 ? TMath::Exp(slope * x) / area : 0.0;
            """),

        // ---- turning a count into a measurement ------------------------------

        new Recipe(Family.NORMALIZATION, "Cross section from a yield",
            "What a number of events says about how often the process happens",
            Kind.TRANSFORM,
            List.of(in("observed", "double"), in("background", "double"),
                    in("efficiency", "double"), in("luminosity", "double")),
            mods(RootPipelineModules.MATH),
            """
              // A downward fluctuation can put the excess below zero, and a
              // negative cross section means the measurement, not the process.
              if (efficiency <= 0.0 || luminosity <= 0.0) {
                return 0.0;
              }
              return (observed - background) / (efficiency * luminosity);
            """),

        new Recipe(Family.NORMALIZATION, "Luminosity weight",
            "What one simulated event is worth once the sample is normalized",
            Kind.TRANSFORM,
            List.of(in("crossSection", "double"), in("luminosity", "double"),
                    in("generated", "double")),
            mods(RootPipelineModules.MATH),
            """
              // Generated, not selected: dividing by what survived the cuts
              // would take the efficiency out twice.
              return generated > 0.0 ? crossSection * luminosity / generated : 0.0;
            """),

        new Recipe(Family.NORMALIZATION, "Pile-up reweighting",
            "Brings the simulated number of collisions onto the measured one",
            Kind.TRANSFORM,
            List.of(in("dataProbability", "double"), in("mcProbability", "double")),
            mods(RootPipelineModules.MATH),
            """
              // A bin the simulation never filled cannot be reweighted into
              // existence, so it is answered with one rather than an infinity.
              if (mcProbability <= 0.0) {
                return 1.0;
              }
              const double w = dataProbability / mcProbability;
              // A handful of events in a thin tail would otherwise carry the
              // whole distribution, so the weight is capped.
              return w > 10.0 ? 10.0 : w;
            """),

        new Recipe(Family.NORMALIZATION, "Scale factor from two efficiencies",
            "How much the simulation has to be corrected, and by how much it is known",
            Kind.TRANSFORM,
            List.of(in("dataEfficiency", "double"), in("mcEfficiency", "double")),
            mods(RootPipelineModules.MATH),
            """
              return mcEfficiency > 0.0 ? dataEfficiency / mcEfficiency : 1.0;
            """),

        // ---- what ROOT does not carry, written out here ---------------------

        new Recipe(Family.PARTONS, "Parton distribution, written out",
            "The shape a PDF fit uses, with no external library",
            Kind.TRANSFORM,
            List.of(in("x", "double"), in("a0", "double"), in("a1", "double"),
                    in("a2", "double"), in("a3", "double"), in("a4", "double")),
            mods(RootPipelineModules.MATH),
            """
              // xf(x) = a0 * x^a1 * (1-x)^a2 * (1 + a3*sqrt(x) + a4*x)
              //
              // This is the form the global fits start from at their input
              // scale: a1 governs the small-x rise, a2 the fall towards x = 1,
              // and the bracket bends the middle. It is not CT18 or NNPDF --
              // those are grids, not formulas -- but it is a real parton
              // density, it needs nothing installed, and its parameters can be
              // fitted here like any others.
              if (x <= 0.0 || x >= 1.0) {
                return 0.0;
              }
              const double sx = TMath::Sqrt(x);
              const double shape = 1.0 + a3 * sx + a4 * x;
              return a0 * TMath::Power(x, a1) * TMath::Power(1.0 - x, a2) * shape;
            """),

        new Recipe(Family.PARTONS, "Strong coupling at one loop",
            "How alpha_s runs with the scale, written out",
            Kind.TRANSFORM,
            List.of(in("q", "double"), in("lambdaQCD", "double"), in("nf", "int")),
            mods(RootPipelineModules.MATH),
            """
              // alpha_s(Q) = 4 pi / (beta0 * ln(Q^2 / Lambda^2)),
              // with beta0 = 11 - 2 nf / 3.
              // Below Lambda the expression has a pole and QCD is no longer
              // perturbative, so the region is refused rather than returned.
              if (q <= lambdaQCD || lambdaQCD <= 0.0) {
                return 0.0;
              }
              const double beta0 = 11.0 - 2.0 * (double) nf / 3.0;
              const double t = TMath::Log((q * q) / (lambdaQCD * lambdaQCD));
              if (beta0 <= 0.0 || t <= 0.0) {
                return 0.0;
              }
              const double a = 4.0 * TMath::Pi() / (beta0 * t);
              // Being above Lambda is not the same as being perturbative: just
              // above it the expression still returns a number, and that number
              // is larger than one, where the series it was expanded from no
              // longer converges. Answering zero says so; answering 6 does not.
              return a > 1.0 ? 0.0 : a;
            """),

        new Recipe(Family.PARTONS, "Strong coupling at two loops",
            "The same with the second term, which matters at LHC scales",
            Kind.TRANSFORM,
            List.of(in("q", "double"), in("lambdaQCD", "double"), in("nf", "int")),
            mods(RootPipelineModules.MATH),
            """
              // The one-loop answer times (1 - beta1 ln(t) / (beta0^2 t)),
              // with beta1 = 102 - 38 nf / 3. At a few hundred GeV the
              // correction is several percent, which is more than most of the
              // uncertainties it would be compared against.
              if (q <= lambdaQCD || lambdaQCD <= 0.0) {
                return 0.0;
              }
              const double beta0 = 11.0 - 2.0 * (double) nf / 3.0;
              const double beta1 = 102.0 - 38.0 * (double) nf / 3.0;
              const double t = TMath::Log((q * q) / (lambdaQCD * lambdaQCD));
              if (beta0 <= 0.0 || t <= 1.0) {
                return 0.0;
              }
              const double one = 4.0 * TMath::Pi() / (beta0 * t);
              const double a = one * (1.0 - beta1 * TMath::Log(t) / (beta0 * beta0 * t));
              return (a > 1.0 || a < 0.0) ? 0.0 : a;
            """),

        new Recipe(Family.STATISTICS, "Poisson log-likelihood ratio",
            "What a fit actually minimizes, bin by bin",
            Kind.TRANSFORM,
            List.of(in("observed", "double"), in("expected", "double")),
            mods(RootPipelineModules.MATH),
            """
              // 2 * (expected - observed + observed * ln(observed/expected)).
              // This is the Poisson equivalent of a chi-square term, and unlike
              // (o-e)^2/e it stays correct when a bin holds two events.
              if (expected <= 0.0) {
                return observed > 0.0 ? 1e30 : 0.0;
              }
              if (observed <= 0.0) {
                return 2.0 * expected;
              }
              return 2.0 * (expected - observed
                            + observed * TMath::Log(observed / expected));
            """),

        new Recipe(Family.STATISTICS, "Chi-square p-value",
            "How good a fit is, written out rather than looked up",
            Kind.TRANSFORM,
            List.of(in("chi2", "double"), in("ndf", "int")),
            mods(RootPipelineModules.MATH),
            """
              // The upper tail of the chi-square is the regularized incomplete
              // gamma Q(ndf/2, chi2/2), which is computed here by its series
              // below the crossover and by its continued fraction above it.
              // Each converges quickly on its own side and badly on the other,
              // which is why both are written.
              if (ndf <= 0 || chi2 < 0.0) {
                return 0.0;
              }
              const double a = 0.5 * (double) ndf;
              const double x = 0.5 * chi2;
              if (x == 0.0) {
                return 1.0;
              }
              const double lead = -x + a * TMath::Log(x) - std::lgamma(a);
              if (x < a + 1.0) {
                double term = 1.0 / a;
                double sum = term;
                for (int i = 1; i < 500; ++i) {
                  term *= x / (a + (double) i);
                  sum += term;
                  if (TMath::Abs(term) < TMath::Abs(sum) * 1e-15) {
                    break;
                  }
                }
                return 1.0 - sum * TMath::Exp(lead);
              }
              // Continued fraction, evaluated by the modified Lentz method.
              const double tiny = 1e-300;
              double b = x + 1.0 - a;
              double c = 1.0 / tiny;
              double d = 1.0 / b;
              double result = d;
              for (int i = 1; i < 500; ++i) {
                const double an = -(double) i * ((double) i - a);
                b += 2.0;
                d = an * d + b;
                if (TMath::Abs(d) < tiny) {
                  d = tiny;
                }
                c = b + an / c;
                if (TMath::Abs(c) < tiny) {
                  c = tiny;
                }
                d = 1.0 / d;
                const double step = d * c;
                result *= step;
                if (TMath::Abs(step - 1.0) < 1e-15) {
                  break;
                }
              }
              return result * TMath::Exp(lead);
            """),

        new Recipe(Family.SHAPES, "Trigger turn-on curve",
            "The error-function rise an efficiency really has at threshold",
            Kind.TRANSFORM,
            List.of(in("x", "double"), in("threshold", "double"),
                    in("width", "double"), in("plateau", "double")),
            mods(RootPipelineModules.MATH),
            """
              // A trigger does not switch on at a point: resolution smears the
              // step into the integral of a Gaussian, which is what is fitted.
              if (width <= 0.0) {
                return x >= threshold ? plateau : 0.0;
              }
              return 0.5 * plateau
                     * (1.0 + TMath::Erf((x - threshold)
                                         / (width * TMath::Sqrt(2.0))));
            """),

        new Recipe(Family.SHAPES, "Landau, in the Moyal form",
            "Energy loss in a thin layer, written out",
            Kind.TRANSFORM,
            List.of(in("x", "double"), in("mpv", "double"), in("sigma", "double")),
            mods(RootPipelineModules.MATH),
            """
              // The Moyal approximation to the Landau distribution: a long tail
              // towards large losses, which a Gaussian would never produce and
              // which is what makes a truncated mean necessary.
              if (sigma <= 0.0) {
                return 0.0;
              }
              const double t = (x - mpv) / sigma;
              // Guarded because exp(-t) overflows well before t does.
              if (t < -40.0) {
                return 0.0;
              }
              return TMath::Exp(-0.5 * (t + TMath::Exp(-t)))
                     / (sigma * TMath::Sqrt(2.0 * TMath::Pi()));
            """),

        new Recipe(Family.SHAPES, "Argus background",
            "The shape a background takes as it runs into a kinematic edge",
            Kind.TRANSFORM,
            List.of(in("x", "double"), in("endpoint", "double"), in("curvature", "double")),
            mods(RootPipelineModules.MATH),
            """
              // Zero above the endpoint by construction: no event can be made
              // beyond the energy that was available to make it.
              if (endpoint <= 0.0 || x <= 0.0 || x >= endpoint) {
                return 0.0;
              }
              const double r = x / endpoint;
              const double u = 1.0 - r * r;
              if (u <= 0.0) {
                return 0.0;
              }
              return x * TMath::Sqrt(u) * TMath::Exp(curvature * u);
            """),

        new Recipe(Family.SHAPES, "Voigt profile",
            "A resonance seen through a detector: Breit-Wigner and Gaussian together",
            Kind.TRANSFORM,
            List.of(in("x", "double"), in("mean", "double"),
                    in("sigma", "double"), in("gamma", "double")),
            mods(RootPipelineModules.MATH),
            """
              // The convolution has no closed form, so it is integrated: the
              // natural width and the resolution are rarely far enough apart
              // for either to be dropped, which is the whole reason this shape
              // is used instead of one or the other.
              if (sigma <= 0.0) {
                return gamma > 0.0
                    ? (gamma / (2.0 * TMath::Pi()))
                      / ((x - mean) * (x - mean) + 0.25 * gamma * gamma)
                    : 0.0;
              }
              if (gamma <= 0.0) {
                const double t = (x - mean) / sigma;
                return TMath::Exp(-0.5 * t * t)
                       / (sigma * TMath::Sqrt(2.0 * TMath::Pi()));
              }
              // u = scale * tan(theta) carries theta in (-pi/2, pi/2) over the
              // whole real line, so the Lorentzian wings are integrated rather
              // than cut off at a few widths, where half a percent of the area
              // still lies.
              const double scale = 0.5 * gamma + sigma;
              const int steps = 600;
              const double dTheta = TMath::Pi() / (double) steps;
              double total = 0.0;
              for (int i = 0; i < steps; ++i) {
                // Midpoints, so neither end of the substitution is evaluated.
                const double theta = -0.5 * TMath::Pi() + ((double) i + 0.5) * dTheta;
                const double u = scale * TMath::Tan(theta);
                const double jacobian = scale / (TMath::Cos(theta) * TMath::Cos(theta));
                const double d = x - mean - u;
                const double lorentz = (gamma / (2.0 * TMath::Pi()))
                    / (d * d + 0.25 * gamma * gamma);
                const double gauss = TMath::Exp(-0.5 * (u / sigma) * (u / sigma))
                    / (sigma * TMath::Sqrt(2.0 * TMath::Pi()));
                total += lorentz * gauss * jacobian;
              }
              return total * dTheta;
            """),

        new Recipe(Family.SELECTION, "Filter: isolated and central",
            "Keeps the entries whose object is isolated, central and hard enough",
            Kind.FILTER,
            List.of(in("pt", "double"), in("eta", "double"), in("iso", "double"),
                    in("minPt", "double"), in("maxEta", "double"), in("maxIso", "double")),
            mods(RootPipelineModules.MATH),
            """
              return pt > minPt && TMath::Abs(eta) < maxEta && iso < maxIso;
            """),

        new Recipe(Family.SELECTION, "Filter: back to back",
            "Keeps the entries whose two objects are opposite in azimuth",
            Kind.FILTER,
            List.of(in("phi1", "double"), in("phi2", "double"), in("least", "double")),
            mods(RootPipelineModules.MATH),
            """
              double d = TMath::Abs(phi1 - phi2);
              while (d > TMath::Pi()) {
                d = 2.0 * TMath::Pi() - d;
              }
              return d > least;
            """),

        new Recipe(Family.NORMALIZATION, "Weight from a formula",
            "A per-entry weight, clipped so one entry cannot dominate",
            Kind.TRANSFORM,
            List.of(in("value", "double"), in("maximum", "double")),
            mods(RootPipelineModules.MATH),
            """
              if (!TMath::Finite(value) || value < 0.0) {
                return 0.0;
              }
              return value > maximum ? maximum : value;
            """)
    );

    /**
     * Every recipe, grouped by family.
     *
     * Forty entries in one flat list is a heap to scroll through. Sorted by
     * family it is a catalogue, and the window shows the family beside each
     * title so the boundaries are visible without separators.
     */
    public static List<Recipe> all() {
        List<Recipe> sorted = new ArrayList<>(ALL);
        sorted.sort(java.util.Comparator.comparingInt(one -> one.family().ordinal()));
        return sorted;
    }

    /** The recipes of one family, in the order they were written. */
    public static List<Recipe> of(Family family) {
        List<Recipe> found = new ArrayList<>();
        for (Recipe one : ALL) {
            if (one.family() == family) {
                found.add(one);
            }
        }
        return found;
    }

    public static Recipe byTitle(String title) {
        for (Recipe one : ALL) {
            if (one.title().equalsIgnoreCase(title == null ? "" : title.trim())) {
                return one;
            }
        }
        return null;
    }

    /**
     * Puts a recipe into a manifest, keeping the name the user already chose.
     *
     * The recipe decides everything the body depends on -- the kind, the inputs
     * and the modules -- because a body pasted under a signature it was not
     * written for would not compile, and the form would be lying about it.
     */
    public static void applyTo(Recipe recipe, RootPipelineManifest manifest) {
        if (recipe == null || manifest == null) {
            return;
        }
        manifest.kind = recipe.kind();
        manifest.inputs.clear();
        manifest.inputs.addAll(recipe.inputs());
        manifest.modules.clear();
        manifest.modules.addAll(recipe.modules());
        if (manifest.description == null || manifest.description.isBlank()) {
            manifest.description = recipe.about();
        }
    }

    /**
     * The recipe's body, with the pipeline's own name put where it belongs.
     *
     * Null for the empty recipe, which means the generator writes its usual
     * starting body instead.
     */
    public static String bodyFor(Recipe recipe, RootPipelineManifest manifest) {
        if (recipe == null || recipe.body() == null) {
            return null;
        }
        final String name = manifest == null || manifest.name.isBlank()
            ? "pipeline" : manifest.name;
        final List<String> lines = new ArrayList<>();
        for (String line : recipe.body().stripTrailing().split("\n")) {
            lines.add(line.replace("NAME", name));
        }
        return String.join("\n", lines);
    }
}
