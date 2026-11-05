package com.sphere.core.ml;

import java.util.List;

/**
 * The references of Sphere's machine learning: the frameworks it trains with (PyTorch, TensorFlow, Keras,
 * JAX), the statistics it fits with (pyhf, HistFactory, CLs, the asymptotic formulae pyhf's tests rest on)
 * and the networks of its zoo (Energy and Particle Flow Networks, ParticleNet, Particle Transformer), with the
 * methods its training and audit use (DisCo's distance correlation, temperature calibration). Shown by the window
 * "Citation ML" of the console's Citations menu; ':ml bib' writes them as BibTeX.
 */
public final class MlCitations {

    /** A publication, as the window shows it and as BibTeX. */
    public record Reference(String key, String text, String bibtex) {
    }

    private MlCitations() {
    }

    public static final Reference PYTORCH = new Reference("Paszke:2019xhz",
        "A. Paszke et al., \"PyTorch: An Imperative Style, High-Performance Deep Learning Library\",\n"
            + "  Adv. Neural Inf. Process. Syst. 32 (2019) 8024, arXiv:1912.01703.",
        String.join("\n",
            "@inproceedings{Paszke:2019xhz,",
            "    author = \"Paszke, Adam and others\",",
            "    title = \"{PyTorch: An Imperative Style, High-Performance Deep Learning Library}\",",
            "    booktitle = \"{Advances in Neural Information Processing Systems 32}\",",
            "    pages = \"8024--8035\",",
            "    year = \"2019\",",
            "    eprint = \"1912.01703\",",
            "    archivePrefix = \"arXiv\"",
            "}"));

    public static final Reference TENSORFLOW = new Reference("Abadi:2016kic",
        "M. Abadi et al., \"TensorFlow: A System for Large-Scale Machine Learning\",\n"
            + "  12th USENIX Symposium on Operating Systems Design and Implementation (OSDI 16) (2016) 265,"
            + " arXiv:1605.08695.",
        String.join("\n",
            "@inproceedings{Abadi:2016kic,",
            "    author = \"Abadi, Mart\\'\\i{}n and others\",",
            "    title = \"{TensorFlow: A System for Large-Scale Machine Learning}\",",
            "    booktitle = \"{12th USENIX Symposium on Operating Systems Design and Implementation (OSDI 16)}\",",
            "    pages = \"265--283\",",
            "    year = \"2016\",",
            "    eprint = \"1605.08695\",",
            "    archivePrefix = \"arXiv\"",
            "}"));

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

    public static List<Reference> bibliography() {
        return List.of(PYTORCH, TENSORFLOW, KERAS, JAX, PYHF_SOFTWARE, PYHF, HISTFACTORY, ASYMPTOTICS, CLS, EFN,
            PARTICLENET, PARTICLE_TRANSFORMER, DISCO, CALIBRATION, ISOTONIC);
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
