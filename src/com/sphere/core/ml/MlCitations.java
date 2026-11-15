package com.sphere.core.ml;

import java.util.List;

/**
 * The references of Sphere's machine learning: frameworks it trains with (Keras, JAX -- PyTorch and TensorFlow
 * are deliberately not cited here), the statistics it fits with (pyhf, HistFactory, CLs, the asymptotic formulae pyhf's tests rest on)
 * and the networks of its zoo (Energy and Particle Flow Networks, ParticleNet, Particle Transformer, LorentzNet,
 * PELICAN and the equivariant layers it rests on), with the methods its training and audit use (DisCo's distance
 * correlation, temperature calibration) and its differentiable analysis (INFERNO, neos, Barlow and Beeston's
 * Monte Carlo statistics); its simulation-based inference (CARL, the gold mined from simulators, ALICES, SCANDAL,
 * and the sandwich of misspecified likelihoods), its unfolding (OmniFold, IBU), its Bayesian optimization (Snoek et
 * al., the constrained and unknown-constraint acquisitions, LogEI, integer variables, Rasmussen and Williams) and its
 * networks' uncertainties (Laplace, Gauss-Newton and Hessian products, Lanczos, conformal prediction, out-of-
 * distribution scores). Shown by the window
 * "Citation ML" of the console's Citations menu; ':ml bib' writes them as BibTeX.
 */
public final class MlCitations {

    /** A publication, as the window shows it and as BibTeX. */
    public record Reference(String key, String text, String bibtex) {
    }

    private MlCitations() {
    }

    public static final Reference KERAS = new Reference("chollet2015keras",
        "F. Chollet et al., \"Keras\", https://keras.io (2015).",
        String.join("\n",
            "@misc{chollet2015keras,",
            "    author = \"Chollet, Fran\\c{c}ois and others\",",
            "    title = \"{Keras}\",",
            "    howpublished = \"\\url{https://keras.io}\",",
            "    year = \"2015\"",
            "}"));

    public static final Reference JAX = new Reference("jax2018github",
        "J. Bradbury et al., \"JAX: composable transformations of Python+NumPy programs\",\n"
            + "  https://github.com/jax-ml/jax (2018).",
        String.join("\n",
            "@misc{jax2018github,",
            "    author = \"Bradbury, James and Frostig, Roy and Hawkins, Peter and Johnson, Matthew James and Leary, Chris"
                + " and Maclaurin, Dougal and Necula, George and Paszke, Adam and VanderPlas, Jake and Wanderman-Milne,"
                + " Skye and Zhang, Qiao\",",
            "    title = \"{JAX: composable transformations of Python+NumPy programs}\",",
            "    howpublished = \"\\url{https://github.com/jax-ml/jax}\",",
            "    year = \"2018\"",
            "}"));

    /** pyhf asks for both: the archive of the release used, and the JOSS paper (README, "Citation"). */
    public static final Reference PYHF_SOFTWARE = new Reference("pyhf",
        "L. Heinrich, M. Feickert, G. Stark, \"pyhf: v0.7.6\", Zenodo, doi:10.5281/zenodo.1169739\n"
            + "  (cite the version used: the DOI names every release).",
        String.join("\n",
            "@software{pyhf,",
            "    author = {Lukas Heinrich and Matthew Feickert and Giordon Stark},",
            "    title = \"{pyhf: v0.7.6}\",",
            "    version = {0.7.6},",
            "    doi = {10.5281/zenodo.1169739},",
            "    url = {https://doi.org/10.5281/zenodo.1169739},",
            "    note = {https://github.com/scikit-hep/pyhf/releases/tag/v0.7.6}",
            "}"));

    public static final Reference PYHF = new Reference("Heinrich:2021gyp",
        "L. Heinrich, M. Feickert, G. Stark, K. Cranmer, \"pyhf: pure-Python implementation of HistFactory\n"
            + "  statistical models\", J. Open Source Softw. 6 (2021) 2823.",
        String.join("\n",
            "@article{Heinrich:2021gyp,",
            "    author = \"Heinrich, Lukas and Feickert, Matthew and Stark, Giordon and Cranmer, Kyle\",",
            "    title = \"{pyhf: pure-Python implementation of HistFactory statistical models}\",",
            "    doi = \"10.21105/joss.02823\",",
            "    journal = \"J. Open Source Softw.\",",
            "    volume = \"6\",",
            "    number = \"58\",",
            "    pages = \"2823\",",
            "    year = \"2021\"",
            "}"));

    public static final Reference HISTFACTORY = new Reference("Cranmer:2012sba",
        "K. Cranmer, G. Lewis, L. Moneta, A. Shibata, W. Verkerke, \"HistFactory: A tool for creating statistical\n"
            + "  models for use with RooFit and RooStats\", CERN-OPEN-2012-016 (2012).",
        String.join("\n",
            "@techreport{Cranmer:2012sba,",
            "    author = \"Cranmer, Kyle and Lewis, George and Moneta, Lorenzo and Shibata, Akira and Verkerke, Wouter\",",
            "    title = \"{HistFactory: A tool for creating statistical models for use with RooFit and RooStats}\",",
            "    reportNumber = \"CERN-OPEN-2012-016\",",
            "    year = \"2012\"",
            "}"));

    public static final Reference ASYMPTOTICS = new Reference("Cowan:2010js",
        "G. Cowan, K. Cranmer, E. Gross, O. Vitells, \"Asymptotic formulae for likelihood-based tests of new\n"
            + "  physics\", Eur. Phys. J. C 71 (2011) 1554 [Erratum: Eur. Phys. J. C 73 (2013) 2501].",
        String.join("\n",
            "@article{Cowan:2010js,",
            "    author = \"Cowan, Glen and Cranmer, Kyle and Gross, Eilam and Vitells, Ofer\",",
            "    title = \"{Asymptotic formulae for likelihood-based tests of new physics}\",",
            "    eprint = \"1007.1727\",",
            "    archivePrefix = \"arXiv\",",
            "    doi = \"10.1140/epjc/s10052-011-1554-0\",",
            "    journal = \"Eur. Phys. J. C\",",
            "    volume = \"71\",",
            "    pages = \"1554\",",
            "    year = \"2011\",",
            "    note = \"[Erratum: Eur.Phys.J.C 73, 2501 (2013)]\"",
            "}"));

    public static final Reference CLS = new Reference("Read:2002hq",
        "A. L. Read, \"Presentation of search results: the CL(s) technique\", J. Phys. G 28 (2002) 2693.",
        String.join("\n",
            "@article{Read:2002hq,",
            "    author = \"Read, Alexander L.\",",
            "    title = \"{Presentation of search results: The CL(s) technique}\",",
            "    doi = \"10.1088/0954-3899/28/10/313\",",
            "    journal = \"J. Phys. G\",",
            "    volume = \"28\",",
            "    pages = \"2693--2704\",",
            "    year = \"2002\"",
            "}"));

    public static final Reference EFN = new Reference("Komiske:2018cqr",
        "P. T. Komiske, E. M. Metodiev, J. Thaler, \"Energy Flow Networks: Deep Sets for Particle Jets\",\n"
            + "  JHEP 01 (2019) 121, arXiv:1810.05165.",
        String.join("\n",
            "@article{Komiske:2018cqr,",
            "    author = \"Komiske, Patrick T. and Metodiev, Eric M. and Thaler, Jesse\",",
            "    title = \"{Energy Flow Networks: Deep Sets for Particle Jets}\",",
            "    eprint = \"1810.05165\",",
            "    archivePrefix = \"arXiv\",",
            "    doi = \"10.1007/JHEP01(2019)121\",",
            "    journal = \"JHEP\",",
            "    volume = \"01\",",
            "    pages = \"121\",",
            "    year = \"2019\"",
            "}"));

    public static final Reference DISCO = new Reference("Kasieczka:2020yyl",
        "G. Kasieczka, D. Shih, \"Robust Jet Classifiers through Distance Correlation\", Phys. Rev. Lett. 125 (2020)\n"
            + "  122001, arXiv:2001.05310.",
        String.join("\n",
            "@article{Kasieczka:2020yyl,",
            "    author = \"Kasieczka, Gregor and Shih, David\",",
            "    title = \"{Robust Jet Classifiers through Distance Correlation}\",",
            "    eprint = \"2001.05310\",",
            "    archivePrefix = \"arXiv\",",
            "    doi = \"10.1103/PhysRevLett.125.122001\",",
            "    journal = \"Phys. Rev. Lett.\",",
            "    volume = \"125\",",
            "    number = \"12\",",
            "    pages = \"122001\",",
            "    year = \"2020\"",
            "}"));

    public static final Reference CALIBRATION = new Reference("Guo:2017cal",
        "C. Guo, G. Pleiss, Y. Sun, K. Q. Weinberger, \"On Calibration of Modern Neural Networks\", Proceedings of\n"
            + "  the 34th International Conference on Machine Learning, PMLR 70 (2017) 1321, arXiv:1706.04599.",
        String.join("\n",
            "@inproceedings{Guo:2017cal,",
            "    author = \"Guo, Chuan and Pleiss, Geoff and Sun, Yu and Weinberger, Kilian Q.\",",
            "    title = \"{On Calibration of Modern Neural Networks}\",",
            "    booktitle = \"{Proceedings of the 34th International Conference on Machine Learning}\",",
            "    series = \"Proceedings of Machine Learning Research\",",
            "    volume = \"70\",",
            "    pages = \"1321--1330\",",
            "    eprint = \"1706.04599\",",
            "    archivePrefix = \"arXiv\",",
            "    year = \"2017\"",
            "}"));

    public static final Reference ISOTONIC = new Reference("Zadrozny:2002iso",
        "B. Zadrozny, C. Elkan, \"Transforming classifier scores into accurate multiclass probability estimates\",\n"
            + "  Proceedings of the 8th ACM SIGKDD International Conference on Knowledge Discovery and Data Mining (2002)"
            + " 694, doi:10.1145/775047.775151.",
        String.join("\n",
            "@inproceedings{Zadrozny:2002iso,",
            "    author = \"Zadrozny, Bianca and Elkan, Charles\",",
            "    title = \"{Transforming classifier scores into accurate multiclass probability estimates}\",",
            "    booktitle = \"{Proceedings of the 8th ACM SIGKDD International Conference on Knowledge Discovery and Data Mining}\",",
            "    pages = \"694--699\",",
            "    doi = \"10.1145/775047.775151\",",
            "    year = \"2002\"",
            "}"));

    public static final Reference PARTICLENET = new Reference("Qu:2019gqs",
        "H. Qu, L. Gouskos, \"ParticleNet: Jet Tagging via Particle Clouds\", Phys. Rev. D 101 (2020) 056019,"
            + " arXiv:1902.08570.",
        String.join("\n",
            "@article{Qu:2019gqs,",
            "    author = \"Qu, Huilin and Gouskos, Loukas\",",
            "    title = \"{ParticleNet: Jet Tagging via Particle Clouds}\",",
            "    eprint = \"1902.08570\",",
            "    archivePrefix = \"arXiv\",",
            "    doi = \"10.1103/PhysRevD.101.056019\",",
            "    journal = \"Phys. Rev. D\",",
            "    volume = \"101\",",
            "    pages = \"056019\",",
            "    year = \"2020\"",
            "}"));

    public static final Reference PARTICLE_TRANSFORMER = new Reference("Qu:2022mxj",
        "H. Qu, C. Li, S. Qian, \"Particle Transformer for Jet Tagging\", Proceedings of the 39th International\n"
            + "  Conference on Machine Learning, PMLR 162 (2022) 18281, arXiv:2202.03772.",
        String.join("\n",
            "@inproceedings{Qu:2022mxj,",
            "    author = \"Qu, Huilin and Li, Congqiao and Qian, Sitian\",",
            "    title = \"{Particle Transformer for Jet Tagging}\",",
            "    booktitle = \"{Proceedings of the 39th International Conference on Machine Learning}\",",
            "    series = \"Proceedings of Machine Learning Research\",",
            "    volume = \"162\",",
            "    pages = \"18281--18292\",",
            "    eprint = \"2202.03772\",",
            "    archivePrefix = \"arXiv\",",
            "    year = \"2022\"",
            "}"));

    public static final Reference LORENTZNET = new Reference("Gong:2022lye",
        "S. Gong, Q. Meng, J. Zhang, H. Qu, C. Li, S. Qian, W. Du, Z.-M. Ma, T.-Y. Liu, \"An efficient Lorentz equivariant\n"
            + "  graph neural network for jet tagging\", JHEP 07 (2022) 030, arXiv:2201.08187.",
        String.join("\n",
            "@article{Gong:2022lye,",
            "    author = \"Gong, Shiqi and Meng, Qi and Zhang, Jue and Qu, Huilin and Li, Congqiao and Qian, Sitian and Du, Weitao"
                + " and Ma, Zhi-Ming and Liu, Tie-Yan\",",
            "    title = \"{An efficient Lorentz equivariant graph neural network for jet tagging}\",",
            "    journal = \"JHEP\",",
            "    volume = \"07\",",
            "    pages = \"030\",",
            "    year = \"2022\",",
            "    eprint = \"2201.08187\",",
            "    archivePrefix = \"arXiv\"",
            "}"));

    public static final Reference PELICAN = new Reference("Bogatskiy:2022czk",
        "A. Bogatskiy, T. Hoffman, D. W. Miller, J. T. Offermann, \"PELICAN: Permutation Equivariant and Lorentz Invariant\n"
            + "  or Covariant Aggregator Network for Particle Physics\", arXiv:2211.00454 (2022).",
        String.join("\n",
            "@article{Bogatskiy:2022czk,",
            "    author = \"Bogatskiy, Alexander and Hoffman, Timothy and Miller, David W. and Offermann, Jan T.\",",
            "    title = \"{PELICAN: Permutation Equivariant and Lorentz Invariant or Covariant Aggregator Network for Particle"
                + " Physics}\",",
            "    eprint = \"2211.00454\",",
            "    archivePrefix = \"arXiv\",",
            "    year = \"2022\"",
            "}"));

    public static final Reference EQUIVARIANT_LAYERS = new Reference("Maron:2018bnd",
        "H. Maron, H. Ben-Hamu, N. Shamir, Y. Lipman, \"Invariant and Equivariant Graph Networks\", International\n"
            + "  Conference on Learning Representations (2019), arXiv:1812.09902.",
        String.join("\n",
            "@inproceedings{Maron:2018bnd,",
            "    author = \"Maron, Haggai and Ben-Hamu, Heli and Shamir, Nadav and Lipman, Yaron\",",
            "    title = \"{Invariant and Equivariant Graph Networks}\",",
            "    booktitle = \"{International Conference on Learning Representations}\",",
            "    year = \"2019\",",
            "    eprint = \"1812.09902\",",
            "    archivePrefix = \"arXiv\"",
            "}"));

    public static final Reference INFERNO = new Reference("deCastro:2018psv",
        "P. de Castro, T. Dorigo, \"INFERNO: Inference-Aware Neural Optimisation\", Comput. Phys. Commun. 244 (2019)\n"
            + "  170, arXiv:1806.04743.",
        String.join("\n",
            "@article{deCastro:2018psv,",
            "    author = \"de Castro, Pablo and Dorigo, Tommaso\",",
            "    title = \"{INFERNO: Inference-Aware Neural Optimisation}\",",
            "    journal = \"Comput. Phys. Commun.\",",
            "    volume = \"244\",",
            "    pages = \"170--179\",",
            "    year = \"2019\",",
            "    eprint = \"1806.04743\",",
            "    archivePrefix = \"arXiv\"",
            "}"));

    public static final Reference NEOS = new Reference("Simpson:2022suz",
        "N. Simpson, L. Heinrich, \"neos: End-to-End-Optimised Summary Statistics for High Energy Physics\",\n"
            + "  J. Phys. Conf. Ser. 2438 (2023) 012105, arXiv:2203.05570.",
        String.join("\n",
            "@article{Simpson:2022suz,",
            "    author = \"Simpson, Nathan and Heinrich, Lukas\",",
            "    title = \"{neos: End-to-End-Optimised Summary Statistics for High Energy Physics}\",",
            "    journal = \"J. Phys. Conf. Ser.\",",
            "    volume = \"2438\",",
            "    pages = \"012105\",",
            "    year = \"2023\",",
            "    eprint = \"2203.05570\",",
            "    archivePrefix = \"arXiv\"",
            "}"));

    public static final Reference BARLOW_BEESTON = new Reference("Barlow:1993dm",
        "R. J. Barlow, C. Beeston, \"Fitting using finite Monte Carlo samples\", Comput. Phys. Commun. 77 (1993) 219.",
        String.join("\n",
            "@article{Barlow:1993dm,",
            "    author = \"Barlow, Roger J. and Beeston, Christine\",",
            "    title = \"{Fitting using finite Monte Carlo samples}\",",
            "    journal = \"Comput. Phys. Commun.\",",
            "    volume = \"77\",",
            "    pages = \"219--228\",",
            "    year = \"1993\"",
            "}"));


    // the second frontier: simulation-based inference, unfolding, Bayesian optimization, uncertainty

    public static final Reference CARL = new Reference("Cranmer:2015bka",
        "K. Cranmer, J. Pavez, G. Louppe, \"Approximating Likelihood Ratios with Calibrated Discriminative Classifiers\",\n"
            + "  arXiv:1506.02169.",
        String.join("\n",
            "@article{Cranmer:2015bka,",
            "    author = \"Cranmer, Kyle and Pavez, Juan and Louppe, Gilles\",",
            "    title = \"{Approximating Likelihood Ratios with Calibrated Discriminative Classifiers}\",",
            "    year = \"2015\",",
            "    eprint = \"1506.02169\",",
            "    archivePrefix = \"arXiv\"",
            "}"));

    public static final Reference MINING_GOLD = new Reference("Brehmer:2018hga",
        "J. Brehmer, G. Louppe, J. Pavez, K. Cranmer, \"Mining gold from implicit models to improve likelihood-free\n"
            + "  inference\", Proc. Natl. Acad. Sci. 117 (2020) 5242, arXiv:1805.12244.",
        String.join("\n",
            "@article{Brehmer:2018hga,",
            "    author = \"Brehmer, Johann and Louppe, Gilles and Pavez, Juan and Cranmer, Kyle\",",
            "    title = \"{Mining gold from implicit models to improve likelihood-free inference}\",",
            "    journal = \"Proc. Nat. Acad. Sci.\",",
            "    volume = \"117\",",
            "    pages = \"5242--5249\",",
            "    year = \"2020\",",
            "    eprint = \"1805.12244\",",
            "    archivePrefix = \"arXiv\"",
            "}"));

    public static final Reference ALICES = new Reference("Stoye:2018ovl",
        "M. Stoye, J. Brehmer, G. Louppe, J. Pavez, K. Cranmer, \"Likelihood-free inference with an improved cross-entropy\n"
            + "  estimator\", arXiv:1808.00973.",
        String.join("\n",
            "@article{Stoye:2018ovl,",
            "    author = \"Stoye, Markus and Brehmer, Johann and Louppe, Gilles and Pavez, Juan and Cranmer, Kyle\",",
            "    title = \"{Likelihood-free inference with an improved cross-entropy estimator}\",",
            "    year = \"2018\",",
            "    eprint = \"1808.00973\",",
            "    archivePrefix = \"arXiv\"",
            "}"));

    public static final Reference SCANDAL = new Reference("Brehmer:2018kdj",
        "J. Brehmer, K. Cranmer, G. Louppe, J. Pavez, \"Constraining Effective Field Theories with Machine Learning\",\n"
            + "  Phys. Rev. Lett. 121 (2018) 111801, arXiv:1805.00013.",
        String.join("\n",
            "@article{Brehmer:2018kdj,",
            "    author = \"Brehmer, Johann and Cranmer, Kyle and Louppe, Gilles and Pavez, Juan\",",
            "    title = \"{Constraining Effective Field Theories with Machine Learning}\",",
            "    journal = \"Phys. Rev. Lett.\",",
            "    volume = \"121\",",
            "    pages = \"111801\",",
            "    year = \"2018\",",
            "    eprint = \"1805.00013\",",
            "    archivePrefix = \"arXiv\"",
            "}"));

    public static final Reference HUBER = new Reference("Huber:1967",
        "P. J. Huber, \"The behavior of maximum likelihood estimates under nonstandard conditions\", Proc. Fifth Berkeley\n"
            + "  Symp. Math. Statist. Prob. 1 (1967) 221.",
        String.join("\n",
            "@inproceedings{Huber:1967,",
            "    author = \"Huber, Peter J.\",",
            "    title = \"{The behavior of maximum likelihood estimates under nonstandard conditions}\",",
            "    booktitle = \"{Proceedings of the Fifth Berkeley Symposium on Mathematical Statistics and Probability}\",",
            "    volume = \"1\",",
            "    pages = \"221--233\",",
            "    year = \"1967\"",
            "}"));

    public static final Reference WHITE = new Reference("White:1982",
        "H. White, \"Maximum Likelihood Estimation of Misspecified Models\", Econometrica 50 (1982) 1.",
        String.join("\n",
            "@article{White:1982,",
            "    author = \"White, Halbert\",",
            "    title = \"{Maximum Likelihood Estimation of Misspecified Models}\",",
            "    journal = \"Econometrica\",",
            "    volume = \"50\",",
            "    pages = \"1--25\",",
            "    year = \"1982\"",
            "}"));

    public static final Reference OMNIFOLD = new Reference("Andreassen:2019cjw",
        "A. Andreassen, P. T. Komiske, E. M. Metodiev, B. Nachman, J. Thaler, \"OmniFold: A Method to Simultaneously\n"
            + "  Unfold All Observables\", Phys. Rev. Lett. 124 (2020) 182001, arXiv:1911.09107.",
        String.join("\n",
            "@article{Andreassen:2019cjw,",
            "    author = \"Andreassen, Anders and Komiske, Patrick T. and Metodiev, Eric M. and Nachman, Benjamin and Thaler, Jesse\",",
            "    title = \"{OmniFold: A Method to Simultaneously Unfold All Observables}\",",
            "    journal = \"Phys. Rev. Lett.\",",
            "    volume = \"124\",",
            "    pages = \"182001\",",
            "    year = \"2020\",",
            "    eprint = \"1911.09107\",",
            "    archivePrefix = \"arXiv\"",
            "}"));

    public static final Reference IBU = new Reference("DAgostini:1994fjx",
        "G. D'Agostini, \"A Multidimensional unfolding method based on Bayes' theorem\", Nucl. Instrum. Meth. A 362\n"
            + "  (1995) 487.",
        String.join("\n",
            "@article{DAgostini:1994fjx,",
            "    author = \"D'Agostini, G.\",",
            "    title = \"{A Multidimensional unfolding method based on Bayes' theorem}\",",
            "    journal = \"Nucl. Instrum. Meth. A\",",
            "    volume = \"362\",",
            "    pages = \"487--498\",",
            "    year = \"1995\"",
            "}"));

    public static final Reference BAYES_OPT = new Reference("Snoek:2012",
        "J. Snoek, H. Larochelle, R. P. Adams, \"Practical Bayesian Optimization of Machine Learning Algorithms\",\n"
            + "  Adv. Neural Inf. Process. Syst. 25 (2012) 2951, arXiv:1206.2944.",
        String.join("\n",
            "@inproceedings{Snoek:2012,",
            "    author = \"Snoek, Jasper and Larochelle, Hugo and Adams, Ryan P.\",",
            "    title = \"{Practical Bayesian Optimization of Machine Learning Algorithms}\",",
            "    booktitle = \"{Advances in Neural Information Processing Systems 25}\",",
            "    pages = \"2951--2959\",",
            "    year = \"2012\",",
            "    eprint = \"1206.2944\",",
            "    archivePrefix = \"arXiv\"",
            "}"));

    public static final Reference CONSTRAINED_BO = new Reference("Gardner:2014",
        "J. R. Gardner, M. J. Kusner, Z. Xu, K. Q. Weinberger, J. P. Cunningham, \"Bayesian Optimization with Inequality\n"
            + "  Constraints\", Proc. 31st Int. Conf. Mach. Learn., PMLR 32 (2014) 937.",
        String.join("\n",
            "@inproceedings{Gardner:2014,",
            "    author = \"Gardner, Jacob R. and Kusner, Matt J. and Xu, Zhixiang and Weinberger, Kilian Q. and Cunningham, John P.\",",
            "    title = \"{Bayesian Optimization with Inequality Constraints}\",",
            "    booktitle = \"{Proceedings of the 31st International Conference on Machine Learning}\",",
            "    series = \"PMLR\",",
            "    volume = \"32\",",
            "    pages = \"937--945\",",
            "    year = \"2014\"",
            "}"));

    public static final Reference UNKNOWN_CONSTRAINTS = new Reference("Gelbart:2014",
        "M. A. Gelbart, J. Snoek, R. P. Adams, \"Bayesian Optimization with Unknown Constraints\", Proc. 30th Conf.\n"
            + "  Uncertainty in Artificial Intelligence (2014) 250, arXiv:1403.5607.",
        String.join("\n",
            "@inproceedings{Gelbart:2014,",
            "    author = \"Gelbart, Michael A. and Snoek, Jasper and Adams, Ryan P.\",",
            "    title = \"{Bayesian Optimization with Unknown Constraints}\",",
            "    booktitle = \"{Proceedings of the 30th Conference on Uncertainty in Artificial Intelligence}\",",
            "    pages = \"250--259\",",
            "    year = \"2014\",",
            "    eprint = \"1403.5607\",",
            "    archivePrefix = \"arXiv\"",
            "}"));

    public static final Reference LOGEI = new Reference("Ament:2023",
        "S. Ament, S. Daulton, D. Eriksson, M. Balandat, E. Bakshy, \"Unexpected Improvements to Expected Improvement for\n"
            + "  Bayesian Optimization\", Adv. Neural Inf. Process. Syst. 36 (2023), arXiv:2310.20708.",
        String.join("\n",
            "@inproceedings{Ament:2023,",
            "    author = \"Ament, Sebastian and Daulton, Samuel and Eriksson, David and Balandat, Maximilian and Bakshy, Eytan\",",
            "    title = \"{Unexpected Improvements to Expected Improvement for Bayesian Optimization}\",",
            "    booktitle = \"{Advances in Neural Information Processing Systems 36}\",",
            "    year = \"2023\",",
            "    eprint = \"2310.20708\",",
            "    archivePrefix = \"arXiv\"",
            "}"));

    public static final Reference INTEGER_BO = new Reference("GarridoMerchan:2020",
        "E. C. Garrido-Merchan, D. Hernandez-Lobato, \"Dealing with categorical and integer-valued variables in Bayesian\n"
            + "  Optimization with Gaussian processes\", Neurocomputing 380 (2020) 20, arXiv:1805.03463.",
        String.join("\n",
            "@article{GarridoMerchan:2020,",
            "    author = \"Garrido-Merch{\\'a}n, Eduardo C. and Hern{\\'a}ndez-Lobato, Daniel\",",
            "    title = \"{Dealing with categorical and integer-valued variables in Bayesian Optimization with Gaussian processes}\",",
            "    journal = \"Neurocomputing\",",
            "    volume = \"380\",",
            "    pages = \"20--35\",",
            "    year = \"2020\",",
            "    eprint = \"1805.03463\",",
            "    archivePrefix = \"arXiv\"",
            "}"));

    public static final Reference GPML = new Reference("Rasmussen:2006",
        "C. E. Rasmussen, C. K. I. Williams, \"Gaussian Processes for Machine Learning\", MIT Press (2006).",
        String.join("\n",
            "@book{Rasmussen:2006,",
            "    author = \"Rasmussen, Carl Edward and Williams, Christopher K. I.\",",
            "    title = \"{Gaussian Processes for Machine Learning}\",",
            "    publisher = \"MIT Press\",",
            "    year = \"2006\"",
            "}"));

    public static final Reference LAPLACE = new Reference("MacKay:1992lap",
        "D. J. C. MacKay, \"A Practical Bayesian Framework for Backpropagation Networks\", Neural Comput. 4 (1992) 448;\n"
            + "  \"The Evidence Framework Applied to Classification Networks\", Neural Comput. 4 (1992) 720.",
        String.join("\n",
            "@article{MacKay:1992lap,",
            "    author = \"MacKay, David J. C.\",",
            "    title = \"{A Practical Bayesian Framework for Backpropagation Networks}\",",
            "    journal = \"Neural Comput.\",",
            "    volume = \"4\",",
            "    pages = \"448--472\",",
            "    year = \"1992\"",
            "}"));

    public static final Reference LAPLACE_REDUX = new Reference("Daxberger:2021",
        "E. Daxberger, A. Kristiadi, A. Immer, R. Eschenhagen, M. Bauer, P. Hennig, \"Laplace Redux -- Effortless Bayesian\n"
            + "  Deep Learning\", Adv. Neural Inf. Process. Syst. 34 (2021), arXiv:2106.14806.",
        String.join("\n",
            "@inproceedings{Daxberger:2021,",
            "    author = \"Daxberger, Erik and Kristiadi, Agustinus and Immer, Alexander and Eschenhagen, Runa and Bauer, Matthias and Hennig, Philipp\",",
            "    title = \"{Laplace Redux -- Effortless Bayesian Deep Learning}\",",
            "    booktitle = \"{Advances in Neural Information Processing Systems 34}\",",
            "    year = \"2021\",",
            "    eprint = \"2106.14806\",",
            "    archivePrefix = \"arXiv\"",
            "}"));

    public static final Reference GAUSS_NEWTON = new Reference("Schraudolph:2002",
        "N. N. Schraudolph, \"Fast Curvature Matrix-Vector Products for Second-Order Gradient Descent\", Neural Comput. 14\n"
            + "  (2002) 1723.",
        String.join("\n",
            "@article{Schraudolph:2002,",
            "    author = \"Schraudolph, Nicol N.\",",
            "    title = \"{Fast Curvature Matrix-Vector Products for Second-Order Gradient Descent}\",",
            "    journal = \"Neural Comput.\",",
            "    volume = \"14\",",
            "    pages = \"1723--1738\",",
            "    year = \"2002\"",
            "}"));

    public static final Reference HESSIAN_VECTOR = new Reference("Pearlmutter:1994",
        "B. A. Pearlmutter, \"Fast Exact Multiplication by the Hessian\", Neural Comput. 6 (1994) 147.",
        String.join("\n",
            "@article{Pearlmutter:1994,",
            "    author = \"Pearlmutter, Barak A.\",",
            "    title = \"{Fast Exact Multiplication by the Hessian}\",",
            "    journal = \"Neural Comput.\",",
            "    volume = \"6\",",
            "    pages = \"147--160\",",
            "    year = \"1994\"",
            "}"));

    public static final Reference LANCZOS = new Reference("Lanczos:1950",
        "C. Lanczos, \"An iteration method for the solution of the eigenvalue problem of linear differential and integral\n"
            + "  operators\", J. Res. Natl. Bur. Stand. 45 (1950) 255.",
        String.join("\n",
            "@article{Lanczos:1950,",
            "    author = \"Lanczos, Cornelius\",",
            "    title = \"{An iteration method for the solution of the eigenvalue problem of linear differential and integral operators}\",",
            "    journal = \"J. Res. Natl. Bur. Stand.\",",
            "    volume = \"45\",",
            "    pages = \"255--282\",",
            "    year = \"1950\"",
            "}"));

    public static final Reference CONFORMAL = new Reference("Vovk:2005",
        "V. Vovk, A. Gammerman, G. Shafer, \"Algorithmic Learning in a Random World\", Springer (2005).",
        String.join("\n",
            "@book{Vovk:2005,",
            "    author = \"Vovk, Vladimir and Gammerman, Alex and Shafer, Glenn\",",
            "    title = \"{Algorithmic Learning in a Random World}\",",
            "    publisher = \"Springer\",",
            "    year = \"2005\"",
            "}"));

    public static final Reference CONFORMAL_INTRO = new Reference("Angelopoulos:2021",
        "A. N. Angelopoulos, S. Bates, \"A Gentle Introduction to Conformal Prediction and Distribution-Free Uncertainty\n"
            + "  Quantification\", arXiv:2107.07511.",
        String.join("\n",
            "@article{Angelopoulos:2021,",
            "    author = \"Angelopoulos, Anastasios N. and Bates, Stephen\",",
            "    title = \"{A Gentle Introduction to Conformal Prediction and Distribution-Free Uncertainty Quantification}\",",
            "    year = \"2021\",",
            "    eprint = \"2107.07511\",",
            "    archivePrefix = \"arXiv\"",
            "}"));

    public static final Reference ENERGY_OOD = new Reference("Liu:2020",
        "W. Liu, X. Wang, J. D. Owens, Y. Li, \"Energy-based Out-of-distribution Detection\", Adv. Neural Inf. Process.\n"
            + "  Syst. 33 (2020), arXiv:2010.03759.",
        String.join("\n",
            "@inproceedings{Liu:2020,",
            "    author = \"Liu, Weitang and Wang, Xiaoyun and Owens, John D. and Li, Yixuan\",",
            "    title = \"{Energy-based Out-of-distribution Detection}\",",
            "    booktitle = \"{Advances in Neural Information Processing Systems 33}\",",
            "    year = \"2020\",",
            "    eprint = \"2010.03759\",",
            "    archivePrefix = \"arXiv\"",
            "}"));

    public static final Reference MSP_OOD = new Reference("Hendrycks:2017",
        "D. Hendrycks, K. Gimpel, \"A Baseline for Detecting Misclassified and Out-of-Distribution Examples in Neural\n"
            + "  Networks\", Int. Conf. Learn. Represent. (2017), arXiv:1610.02136.",
        String.join("\n",
            "@inproceedings{Hendrycks:2017,",
            "    author = \"Hendrycks, Dan and Gimpel, Kevin\",",
            "    title = \"{A Baseline for Detecting Misclassified and Out-of-Distribution Examples in Neural Networks}\",",
            "    booktitle = \"{International Conference on Learning Representations}\",",
            "    year = \"2017\",",
            "    eprint = \"1610.02136\",",
            "    archivePrefix = \"arXiv\"",
            "}"));

    public static List<Reference> bibliography() {
        return List.of(KERAS, JAX, PYHF_SOFTWARE, PYHF, HISTFACTORY, ASYMPTOTICS, CLS, EFN,
            PARTICLENET, PARTICLE_TRANSFORMER, LORENTZNET, PELICAN, EQUIVARIANT_LAYERS, DISCO, CALIBRATION, ISOTONIC, INFERNO, NEOS,
            BARLOW_BEESTON, CARL, MINING_GOLD, ALICES, SCANDAL, HUBER, WHITE, OMNIFOLD, IBU, BAYES_OPT, CONSTRAINED_BO,
            UNKNOWN_CONSTRAINTS, LOGEI, INTEGER_BO, GPML, LAPLACE, LAPLACE_REDUX, GAUSS_NEWTON, HESSIAN_VECTOR, LANCZOS, CONFORMAL,
            CONFORMAL_INTRO, ENERGY_OOD, MSP_OOD);
    }

    public static String bibtex() {
        final StringBuilder b = new StringBuilder();
        for (Reference r : bibliography()) {
            b.append(r.bibtex()).append("\n\n");
        }
        return b.toString();
    }

    public static String text() {
        final StringBuilder b = new StringBuilder("Machine learning and statistics in Sphere: cite the frameworks a"
            + " result was trained or fitted with, and the networks used.\n\n");
        for (Reference r : bibliography()) {
            b.append(r.text()).append("\n\n");
        }
        return b.toString().strip();
    }
}
