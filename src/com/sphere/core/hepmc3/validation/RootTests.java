package com.sphere.core.hepmc3.validation;

import com.sphere.core.hepmc3.GenEvent;
import com.sphere.core.hepmc3.GenParticle;
import com.sphere.core.hepmc3.Reader;
import com.sphere.core.hepmc3.ReaderAsciiHepMC2;
import com.sphere.core.hepmc3.ReaderFactory;
import com.sphere.core.hepmc3.ReaderRoot;
import com.sphere.core.hepmc3.ReaderRootTree;
import com.sphere.core.hepmc3.Writer;
import com.sphere.core.hepmc3.WriterAscii;
import com.sphere.core.hepmc3.WriterAsciiHepMC2;
import com.sphere.core.hepmc3.WriterHEPEVT;
import com.sphere.core.hepmc3.WriterRootTree;
import com.sphere.core.rootio.RTree;
import com.sphere.core.rootio.RootIO;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.sphere.core.hepmc3.Units.LengthUnit.MM;
import static com.sphere.core.hepmc3.Units.MomentumUnit.GEV;

/**
 * The tests of HepMC3's test directory that need ROOT (HepMC_root_tests):
 * written and read here with Sphere's own ROOT reader and writer. HepMC3's
 * CMake does not run them on Windows, so there is no C++ output to compare
 * with: each checks itself, as its main() does, and must return 0.
 */
final class RootTests {

    private static final TestProgram.Rules SELF = TestProgram.Rules.selfCheck(
        "ROOT tests: HepMC3's CMake does not run them on Windows, so no C++ output; the test checks itself"
            + " (ROOT files read and written by Sphere's own ROOT I/O)");

    private RootTests() {
    }

    static List<TestProgram> all() {
        final List<TestProgram> l = new ArrayList<>();
        l.add(TestProgram.of("testIO2", List.of("inputIO2.hepmc"), SELF, RootTests::testIO2));
        l.add(TestProgram.of("testIO4", List.of("inputIO4.root"), SELF, RootTests::testIO4));
        l.add(TestProgram.of("testReaderFactory2", List.of("inputReaderFactory2.hepmc"), SELF, RootTests::testReaderFactory2));
        l.add(TestProgram.of("testRoot300", List.of("inputRoot300.root"), SELF, RootTests::testRoot300));
        l.add(TestProgram.of("testRootTree300", List.of("inputRootTree300.root"), SELF, RootTests::testRootTree300));
        return l;
    }

    private static void copy(TestContext c, Reader in, Writer out) {
        while (!in.failed()) {
            final GenEvent evt = new GenEvent(GEV, MM);
            in.readEvent(evt);
            if (in.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            out.writeEvent(evt);
            evt.clear();
        }
    }

    /** HepMC2 text to a ROOT tree and back: the text must come back the same. */
    static int testIO2(TestContext c) {
        final ReaderAsciiHepMC2 inputA = new ReaderAsciiHepMC2("inputIO2.hepmc");
        if (inputA.failed()) return 1;
        final WriterRootTree outputA = new WriterRootTree("frominputIO2.root");
        if (outputA.failed()) return 2;
        copy(c, inputA, outputA);
        inputA.close();
        outputA.close();
        final ReaderRootTree inputB = new ReaderRootTree("frominputIO2.root");
        if (inputB.failed()) return 3;
        final WriterAsciiHepMC2 outputB = new WriterAsciiHepMC2("fromfrominputIO2.hepmc");
        if (outputB.failed()) return 4;
        copy(c, inputB, outputB);
        inputB.close();
        outputB.close();
        return c.compareAsciiFiles("fromfrominputIO2.hepmc", "inputIO2.hepmc");
    }

    /**
     * The transverse momenta of the charged pions and electrons, once from
     * the tree's branches (the class TTree::MakeClass writes, SomeAnalysis),
     * once from the events ReaderRootTree builds: two histograms of 1000
     * bins from 0 to 100 that must agree bin by bin.
     */
    static int testIO4(TestContext c) throws Exception {
        final double[] h1 = new double[1002];
        try (RootIO file = RootIO.open(c.path("inputIO4.root"))) {
            final RTree tree = file.tree("hepmc3_tree");
            if (tree == null || tree.entries() == 0) return 10001;
            final RTree.RBranch count = tree.branch("particles");
            final RTree.RBranch status = tree.branch("particles.status");
            final RTree.RBranch pid = tree.branch("particles.pid");
            final RTree.RBranch px = tree.branch("particles.momentum.m_v1");
            final RTree.RBranch py = tree.branch("particles.momentum.m_v2");
            for (long entry = 0; entry < tree.entries(); entry++) {
                final int n = ((Number) count.value(entry)).intValue();
                final double[] st = status.numbers(entry);
                final double[] id = pid.numbers(entry);
                final double[] x = px.numbers(entry);
                final double[] y = py.numbers(entry);
                for (int i = 0; i < n; i++) {
                    if (st[i] == 1 && (Math.abs(id[i]) == 211 || Math.abs(id[i]) == 11)) {
                        fill(h1, Math.sqrt(x[i] * x[i] + y[i] * y[i]));
                    }
                }
            }
        }
        final double[] h2 = new double[1002];
        final ReaderRootTree inputA = new ReaderRootTree("inputIO4.root");
        if (inputA.failed()) return 10002;
        while (!inputA.failed()) {
            final GenEvent evt = new GenEvent(GEV, MM);
            inputA.readEvent(evt);
            if (inputA.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            for (GenParticle p : evt.particles()) {
                if (Math.abs(p.status()) == 1 && (Math.abs(p.pdgId()) == 211 || Math.abs(p.pdgId()) == 11)) {
                    fill(h2, p.momentum().perp());
                }
            }
            evt.clear();
        }
        inputA.close();
        int diff = 0;
        for (int i = 0; i < 1000; i++) {
            final double eps = Math.abs(h1[i] - h2[i]);
            if (eps < 1e-5) continue;
            c.printf("Bin: %d %g %g\n", i, h1[i], h2[i]);
            diff++;
        }
        double sum1 = 0;
        double sum2 = 0;
        for (int i = 1; i <= 1000; i++) {
            sum1 += h1[i];
            sum2 += h2[i];
        }
        c.printf("%s\n", String.format(Locale.ROOT, "H1: %.0f entries in range, H2: %.0f", sum1, sum2));
        return diff;
    }

    /** TH1::Fill on 1000 bins from 0 to 100: bin 0 below, 1001 above (TAxis::FindFixBin). */
    private static void fill(double[] h, double x) {
        final int bin = x < 0 ? 0 : x >= 100 ? 1001 : 1 + (int) (1000 * (x - 0) / 100.0);
        h[bin] += 1;
    }

    /** One input, four formats written, each read back through deduce_reader: the HepMC2 texts must agree. */
    static int testReaderFactory2(TestContext c) {
        final Reader input = ReaderFactory.deduceReader("inputReaderFactory2.hepmc");
        if (input == null || input.failed()) return 1;
        final WriterAscii outputA = new WriterAscii("frominputReaderFactory2.hepmc3");
        final WriterAsciiHepMC2 outputB = new WriterAsciiHepMC2("frominputReaderFactory2.hepmc2");
        final WriterHEPEVT outputC = new WriterHEPEVT("frominputReaderFactory2.hepevt");
        final WriterRootTree outputD = new WriterRootTree("frominputReaderFactory2.root");
        if (outputA.failed()) return 2;
        if (outputB.failed()) return 3;
        if (outputC.failed()) return 4;
        if (outputD.failed()) return 5;
        while (!input.failed()) {
            final GenEvent evt = new GenEvent(GEV, MM);
            input.readEvent(evt);
            if (input.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            outputA.writeEvent(evt);
            outputB.writeEvent(evt);
            outputC.writeEvent(evt);
            outputD.writeEvent(evt);
            evt.clear();
        }
        input.close();
        outputA.close();
        outputB.close();
        outputC.close();
        outputD.close();
        final List<Reader> inputv = new ArrayList<>();
        inputv.add(ReaderFactory.deduceReader("frominputReaderFactory2.hepmc3"));
        inputv.add(ReaderFactory.deduceReader("frominputReaderFactory2.hepmc2"));
        inputv.add(ReaderFactory.deduceReader("frominputReaderFactory2.hepevt"));
        inputv.add(ReaderFactory.deduceReader("frominputReaderFactory2.root"));
        final List<WriterAsciiHepMC2> outputv = new ArrayList<>();
        outputv.add(new WriterAsciiHepMC2("AA.hepmc2"));
        outputv.add(new WriterAsciiHepMC2("BB.hepmc2"));
        outputv.add(new WriterAsciiHepMC2("CC.hepmc2"));
        outputv.add(new WriterAsciiHepMC2("DD.hepmc2"));
        for (int i = 0; i < inputv.size(); i++) {
            final Reader in = inputv.get(i);
            if (in == null) continue;
            while (!in.failed()) {
                final GenEvent evt = new GenEvent(GEV, MM);
                in.readEvent(evt);
                if (in.failed()) {
                    c.printf("End of file reached. Exit.\n");
                    break;
                }
                outputv.get(i).writeEvent(evt);
                evt.clear();
            }
        }
        for (WriterAsciiHepMC2 w : outputv) w.close();
        return c.compareAsciiFiles("AA.hepmc2", "BB.hepmc2") + c.compareAsciiFiles("BB.hepmc2", "DD.hepmc2");
    }

    /** The objects of a ROOT file written by HepMC3 3.0's WriterRoot: 1200 particles. */
    static int testRoot300(TestContext c) {
        final ReaderRoot inputA = new ReaderRoot("inputRoot300.root");
        if (inputA.failed()) return 1;
        int particles = 0;
        while (!inputA.failed()) {
            final GenEvent evt = new GenEvent(GEV, MM);
            inputA.readEvent(evt);
            if (inputA.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            particles += evt.particles().size();
            evt.clear();
        }
        inputA.close();
        return particles != 1200 ? 1 : 0;
    }

    /** The tree of a ROOT file written by HepMC3 3.0's WriterRootTree: 1200 particles. */
    static int testRootTree300(TestContext c) {
        final ReaderRootTree inputA = new ReaderRootTree("inputRootTree300.root");
        if (inputA.failed()) return 1;
        int particles = 0;
        while (!inputA.failed()) {
            final GenEvent evt = new GenEvent(GEV, MM);
            inputA.readEvent(evt);
            if (inputA.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            particles += evt.particles().size();
            evt.clear();
        }
        inputA.close();
        return particles != 1200 ? 1 : 0;
    }
}
