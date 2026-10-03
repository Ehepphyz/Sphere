package com.sphere.core.fjcontrib;

import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.plugins.JetSpecs;
import com.sphere.core.fjcontrib.centauro.CentauroPlugin;
import com.sphere.core.fjcontrib.clusteringveto.ClusteringVetoPlugin;
import com.sphere.core.fjcontrib.cmpplugin.CMPPlugin;
import com.sphere.core.fjcontrib.disgenkt.DISGenktPlugin;
import com.sphere.core.fjcontrib.dynamicr.DynamicR;
import com.sphere.core.fjcontrib.ifnplugin.FlavRecombiner;
import com.sphere.core.fjcontrib.ifnplugin.IFNPlugin;
import com.sphere.core.fjcontrib.ktcluscxx.KTClusCXXPlugin;
import com.sphere.core.fjcontrib.nsubjettiness.XConePlugin;
import com.sphere.core.fjcontrib.qcdaware.DistanceMeasure;
import com.sphere.core.fjcontrib.qcdaware.QCDAwarePlugin;
import com.sphere.core.fjcontrib.scjet.ScJet;
import com.sphere.core.fjcontrib.valencia.ValenciaPlugin;
import com.sphere.core.fjcontrib.variabler.VariableRPlugin;

/**
 * The fjcontrib jet algorithms as ':fjet def' specs, so that every command
 * working on the active definition (jets, areas, hist, display, export, the
 * bridge) works on them too. Registered once, when the :fjco commands are.
 *
 * <p>The flavour algorithms (ifn, cmp) take the flavour of each particle
 * from the PDG code Sphere's event readers put in its user index.
 */
public final class ContribSpecs {

    private static volatile boolean registered;

    private ContribSpecs() {
    }

    public static synchronized void register() {
        if (registered) return;
        registered = true;

        JetSpecs.extend("variabler", "[fjcontrib] variabler:rho=600,rmin=0.02,rmax=1.5,type=akt|ca|kt|<p>,precluster",
            s -> {
                final double p = switch (s.s("type", "akt")) {
                    case "akt", "antikt" -> VariableRPlugin.AKTLIKE;
                    case "ca", "cambridge" -> VariableRPlugin.CALIKE;
                    case "kt" -> VariableRPlugin.KTLIKE;
                    default -> s.d("type", -1);
                };
                // a bare number is rho, the scale that sets R_eff = rho/pt
                return new JetDefinition(new VariableRPlugin(Double.isNaN(s.first()) ? s.d("rho", 600) : s.first(),
                    s.d("rmin", 0.02), s.d("rmax", 1.5), p, s.has("precluster")));
            });
        JetSpecs.extend("valencia", "[fjcontrib] valencia:R,beta=1,gamma=1",
            s -> new JetDefinition(new ValenciaPlugin(s.r(1.0), s.d("beta", 1.0), s.d("gamma", 1.0))));
        JetSpecs.extend("centauro", "[fjcontrib] centauro:R[,gammae=..,gammapz=..] (particles in the Breit frame when absent)",
            s -> new JetDefinition(s.has("gammae") && s.has("gammapz")
                ? new CentauroPlugin(s.r(1.0), s.d("gammae", 0), s.d("gammapz", 0))
                : new CentauroPlugin(s.r(1.0))));
        JetSpecs.extend("disgenkt", "[fjcontrib] disgenkt:R,p=1,beam=1|-1",
            s -> new JetDefinition(new DISGenktPlugin(s.d("p", 1.0), s.i("beam", 1), s.r(Math.PI / 2))));
        JetSpecs.extend("ktcluscxx", "[fjcontrib] ktcluscxx:R,mode=4111 (type angle mono recom digits)",
            s -> KTClusCXXPlugin.jetDefinition(s.i("mode", 4111), s.r(1.0)));
        JetSpecs.extend("scjet", "[fjcontrib] scjet:R,mode=mt|pt|et,rexp=3",
            s -> new JetDefinition(new ScJet(s.r(0.7), switch (s.s("mode", "mt")) {
                case "pt" -> ScJet.EnergyMode.use_pt;
                case "et" -> ScJet.EnergyMode.use_et;
                default -> ScJet.EnergyMode.use_mt;
            }, s.i("rexp", 3))));
        final java.util.function.Function<JetSpecs.Spec, JetDefinition> massJump = s -> new JetDefinition(
            new ClusteringVetoPlugin(s.d("mu", 30), s.d("theta", 0.7), s.r(1.0), switch (s.s("type", "ca")) {
                case "kt" -> ClusteringVetoPlugin.ClusterType.KTLIKE;
                case "akt", "antikt" -> ClusteringVetoPlugin.ClusterType.AKTLIKE;
                default -> ClusteringVetoPlugin.ClusterType.CALIKE;
            }));
        JetSpecs.extend("massjump", "[fjcontrib] massjump:Rmax,mu=30,theta=0.7,type=ca|kt|akt", massJump);
        JetSpecs.extend("clusteringveto", "[fjcontrib] clusteringveto:Rmax,... (same as massjump)", massJump);
        JetSpecs.extend("dynamicr", "[fjcontrib] dynamicr:R0,type=ak|ca|kt",
            s -> new JetDefinition(new DynamicR(s.r(0.5), switch (s.s("type", "ak")) {
                case "ca" -> DynamicR.Algorithm.DRCA;
                case "kt" -> DynamicR.Algorithm.DRKT;
                default -> DynamicR.Algorithm.DRAK;
            })));
        JetSpecs.extend("xcone", "[fjcontrib] xcone:R,n=2,beta=2[,pseudo]",
            s -> {
                if (!s.has("n")) throw new FastJetException("xcone needs n=<number of jets>");
                return new JetDefinition(s.has("pseudo")
                    ? new XConePlugin.PseudoXConePlugin(s.i("n", 2), s.r(0.4), s.d("beta", 2.0))
                    : new XConePlugin(s.i("n", 2), s.r(0.4), s.d("beta", 2.0)));
            });
        JetSpecs.extend("qcdaware", "[fjcontrib] qcdaware:R,dm=akt|kt|ca|flavkt,alpha=2,couplings,power=-2,noqed,noqcd",
            s -> {
                final double r = s.r(0.4);
                final DistanceMeasure dm = switch (s.s("dm", "akt")) {
                    case "kt" -> DistanceMeasure.kt(r);
                    case "ca", "cambridge" -> DistanceMeasure.cambridge(r);
                    case "flavkt", "flavourkt" -> DistanceMeasure.flavourKt(r, s.d("alpha", 2.0));
                    default -> DistanceMeasure.antikt(r);
                };
                final QCDAwarePlugin p = new QCDAwarePlugin(dm);
                if (s.has("couplings")) p.setUseCouplings(true).setCouplingPower(s.d("power", -2.0))
                    .setRunningCouplingOrderAlphaS(s.i("running", 0)).setRunningCouplingOrderAlphaEM(s.i("running", 0));
                if (s.has("noqed")) p.setEnableQED(false);
                if (s.has("noqcd")) p.setEnableQCD(false);
                return new JetDefinition(p);
            });
        JetSpecs.extend("ifn", "[fjcontrib] ifn:R,alg=akt|ca,alpha=2,omega=3-alpha,mod2",
            s -> {
                final boolean mod2 = s.has("mod2");
                final FlavRecombiner.FlavSummation sum = mod2 ? FlavRecombiner.FlavSummation.MODULO_2
                    : FlavRecombiner.FlavSummation.NET;
                final JetDefinition base = new JetDefinition("ca".equals(s.s("alg", "akt")) ? JetAlgorithm.CAMBRIDGE
                    : JetAlgorithm.ANTIKT, s.r(0.4));
                base.setRecombiner(new FlavRecombiner(sum).setFlavourFromUserIndex(true));
                return new JetDefinition(new IFNPlugin(base, s.d("alpha", 2.0), s.d("omega", -1), sum)
                    .setFlavourFromUserIndex(true));
            });
        JetSpecs.extend("cmp", "[fjcontrib] cmp:R,a=0.1,corr=sqrt2|sqrt|none|overall|overall2|coshy,ktmax=dynamic|fixed",
            s -> {
                final CMPPlugin.CorrectionType corr = switch (s.s("corr", "sqrt2")) {
                    case "none", "original" -> CMPPlugin.CorrectionType.NO_CORRECTION;
                    case "sqrt" -> CMPPlugin.CorrectionType.SQRT_COSHY_COSPHI_ARGUMENT;
                    case "overall" -> CMPPlugin.CorrectionType.OVERALL_COSHY_COSPHI;
                    case "overall2" -> CMPPlugin.CorrectionType.OVERALL_COSHY_COSPHI_A2;
                    case "coshy" -> CMPPlugin.CorrectionType.COSHY_COSPHI;
                    default -> CMPPlugin.CorrectionType.SQRT_COSHY_COSPHI_ARGUMENT_A2;
                };
                final CMPPlugin.ClusteringType kt = "fixed".equals(s.s("ktmax", "dynamic"))
                    ? CMPPlugin.ClusteringType.FIXED_KTMAX : CMPPlugin.ClusteringType.DYNAMIC_KTMAX;
                final JetDefinition def = new JetDefinition(new CMPPlugin(s.r(0.4), s.d("a", 0.1), corr, kt)
                    .setFlavourFromUserIndex(true));
                def.setRecombiner(new FlavRecombiner().setFlavourFromUserIndex(true));
                return def;
            });
    }
}
