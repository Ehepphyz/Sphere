package com.sphere.core.hepmc3.validation;

import com.sphere.core.hepmc3.Attribute;
import com.sphere.core.hepmc3.DoubleAttribute;
import com.sphere.core.hepmc3.FourVector;
import com.sphere.core.hepmc3.GenEvent;
import com.sphere.core.hepmc3.GenHeavyIon;
import com.sphere.core.hepmc3.GenParticle;
import com.sphere.core.hepmc3.GenPdfInfo;
import com.sphere.core.hepmc3.GenRunInfo;
import com.sphere.core.hepmc3.GenVertex;
import com.sphere.core.hepmc3.HEPEVT;
import com.sphere.core.hepmc3.IntAttribute;
import com.sphere.core.hepmc3.Print;
import com.sphere.core.hepmc3.ReaderAscii;
import com.sphere.core.hepmc3.ReaderAsciiHepMC2;
import com.sphere.core.hepmc3.Units;
import com.sphere.core.hepmc3.VectorDoubleAttribute;
import com.sphere.core.hepmc3.WriterAscii;
import com.sphere.core.hepmc3.WriterAsciiHepMC2;
import com.sphere.core.hepmc3.cxx.CInput;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.search.Relatives;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static com.sphere.core.hepmc3.Units.LengthUnit.MM;
import static com.sphere.core.hepmc3.Units.MomentumUnit.GEV;

/**
 * The tests of HepMC3's test directory on the event record itself: four
 * vectors, units, printing, weights, boosts and rotations, polarisation
 * attributes, loops, copies, attributes, the HEPEVT wrappers and the search
 * module. Each is main() of the .cc, statement for statement.
 */
final class CoreTests {

    private CoreTests() {
    }

    static List<TestProgram> all() {
        final List<TestProgram> l = new ArrayList<>();
        l.add(TestProgram.of("testFourVector", List.of(), CoreTests::testFourVector));
        l.add(TestProgram.of("testUnits", List.of(), CoreTests::testUnits));
        l.add(TestProgram.of("testGenHeavyIon", List.of(), CoreTests::testGenHeavyIon));
        l.add(TestProgram.of("testPrint", List.of(),
            TestProgram.Rules.EXACT.stdout(TestProgram.Output.POINTERS, "a shared_ptr prints its address"), CoreTests::testPrint));
        l.add(TestProgram.of("testPrintBug", List.of(), CoreTests::testPrintBug));
        l.add(TestProgram.of("testWeights", List.of(), CoreTests::testWeights));
        l.add(TestProgram.of("testBoost", List.of(), CoreTests::testBoost));
        l.add(TestProgram.of("testPolarization", List.of(), CoreTests::testPolarization));
        l.add(TestProgram.of("testLoops", List.of(), CoreTests::testLoops));
        l.add(TestProgram.of("testMass", List.of("inputMass.hepmc"), CoreTests::testMass));
        l.add(TestProgram.of("testMultipleCopies", List.of("inputMultipleCopies1.hepmc", "inputMultipleCopies2.hepmc"),
            CoreTests::testMultipleCopies));
        l.add(TestProgram.of("testAttributes", List.of(),
            TestProgram.Rules.EXACT.stdout(TestProgram.Output.IGNORE, "prints only durations"), CoreTests::testAttributes));
        l.add(TestProgram.of("testVertexAttributes", List.of(), CoreTests::testVertexAttributes));
        l.add(TestProgram.of("testOrder", List.of(), CoreTests::testOrder));
        l.add(TestProgram.of("testHEPEVTWrapper1", List.of(), TestProgram.Rules.EXACT.stdout(TestProgram.Output.ROWS_ANY_ORDER,
            "the C++ sorts particles equal for its order by address: each C++ run lists them in another order"),
            CoreTests::testHEPEVTWrapper1));
        l.add(TestProgram.of("testSearch1", List.of(), CoreTests::testSearch1));
        l.add(TestProgram.of("testThreadssearch", List.of(), CoreTests::testThreadssearch));
        return l;
    }

    /** assert() of a program built without NDEBUG. */
    private static void check(boolean condition, String expression) {
        if (!condition) throw new AssertionError("Assertion failed: " + expression);
    }

    private static <T> T last(List<T> l) {
        return l.get(l.size() - 1);
    }

    /* ---- testFourVector ------------------------------------------------------------- */

    static int testFourVector(TestContext c) {
        final List<FourVector> vectorsToTest = List.of(
            new FourVector(0.0, 0.0, 0.0, 0.0),
            new FourVector(1.0, 2.0, 0.0, 0.0),
            new FourVector(0.0, 0.0, 0.0, 1.0),
            new FourVector(0.0, 0.0, 0.0, -1.0),
            new FourVector(0.0, 0.0, 1.0, 0.0),
            new FourVector(0.0, 0.0, -1.0, 0.0),
            new FourVector(1.0, 2.0, 3.0, 4.0),
            new FourVector(1.0, 2.0, 3.0, -4.0),
            new FourVector(1.0, 2.0, -3.0, 4.0),
            new FourVector(1.0, 2.0, -3.0, -4.0),
            new FourVector(1.0, 2.0, -3.0, 40.0),
            new FourVector(1.0, 2.0, -3.0, -40.0));
        final double s = Math.sqrt(1.0 * 1.0 + 2.0 * 2.0 + 3.0 * 3.0);
        final double[] correctEta = {
            0.0, 0.0, 0.0, 0.0,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Math.log((s + 3.0) / (s - 3.0)) * 0.5,
            Math.log((s + 3.0) / (s - 3.0)) * 0.5,
            Math.log((s - 3.0) / (s + 3.0)) * 0.5,
            Math.log((s - 3.0) / (s + 3.0)) * 0.5,
            Math.log((s - 3.0) / (s + 3.0)) * 0.5,
            Math.log((s - 3.0) / (s + 3.0)) * 0.5};
        final double[] correctRap = {
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            Math.log((4.0 + 3.0) / (4.0 - 3.0)) * 0.5,
            Math.log((-4.0 + 3.0) / (-4.0 - 3.0)) * 0.5,
            Math.log((4.0 - 3.0) / (4.0 + 3.0)) * 0.5,
            Math.log((-4.0 - 3.0) / (-4.0 + 3.0)) * 0.5,
            Math.log((40.0 - 3.0) / (40.0 + 3.0)) * 0.5,
            Math.log((-40.0 - 3.0) / (-40.0 + 3.0)) * 0.5};
        final COStream cout = c.cout;
        cout.scientific();
        cout.precision(10);
        cout.width(15);
        cout.showpos(true);
        for (int i = 0; i < vectorsToTest.size(); i++) {
            final FourVector v = vectorsToTest.get(i);
            cout.put("         eta() = ").put(v.eta()).put("         rap()=").put(v.rap()).put(" for ");
            Print.line(cout, v);
            cout.endl();
            cout.put(" Correct eta() = ").put(correctEta[i]).put(" Correct rap()=").put(correctRap[i]).endl().endl();
        }
        for (int i = 0; i < vectorsToTest.size(); i++) {
            cout.put("Testing ");
            Print.line(cout, vectorsToTest.get(i));
            cout.endl();
            check(vectorsToTest.get(i).eta() == correctEta[i], "vectors_to_test.at(i).eta() == correct_eta.at(i)");
            check(vectorsToTest.get(i).rap() == correctRap[i], "vectors_to_test.at(i).rap() == correct_rap.at(i)");
        }
        return 0;
    }

    /* ---- testUnits ------------------------------------------------------------------ */

    private static FourVector randomVector(TestContext c) {
        // the C++ evaluates the four arguments in an order of the compiler's
        // choosing; only the ratio of energies is used, the same either way
        final double rm = c.randMax();
        final double x = 0.5 * rm - c.rand();
        final double y = 0.5 * rm - c.rand();
        final double z = 0.5 * rm - c.rand();
        final double t = 0.5 * rm - c.rand();
        return new FourVector(x, y, z, t);
    }

    private static double conversionFactor(TestContext c, Units.MomentumUnit from, Units.MomentumUnit to) {
        final FourVector m = randomVector(c);
        final FourVector msave = new FourVector(m);
        Units.convert(m, from, to);
        return m.e() / msave.e();
    }

    private static double conversionFactor(TestContext c, Units.LengthUnit from, Units.LengthUnit to) {
        final FourVector m = randomVector(c);
        final FourVector msave = new FourVector(m);
        Units.convert(m, from, to);
        return m.e() / msave.e();
    }

    private static boolean neq(double a, double b) {
        return Math.abs(a - b) >= 0.001 * (Math.abs(a) + Math.abs(b));
    }

    static int testUnits(TestContext c) {
        int err = 0;
        double cf;
        final GenEvent evt = new GenEvent();
        c.cout.put("Default units: ").put(Units.name(evt.momentumUnit())).put(" ").put(Units.name(evt.lengthUnit())).endl();
        final Object[][] cases = {
            {GEV, GEV, 1.0, "GEV to GEV - should be 1"},
            {Units.MomentumUnit.MEV, Units.MomentumUnit.MEV, 1.0, "MEV to MEV - should be 1"},
            {Units.MomentumUnit.MEV, GEV, 0.001, "MEV to GEV - should be 0.001"},
            {GEV, Units.MomentumUnit.MEV, 1000.0, "GEV to MEV - should be 1000"},
            {MM, MM, 1.0, "MM to MM - should be 1"},
            {Units.LengthUnit.CM, Units.LengthUnit.CM, 1.0, "CM to CM - should be 1"},
            {Units.LengthUnit.CM, MM, 10.0, "CM to MM - should be 10"},
            {MM, Units.LengthUnit.CM, 0.1, "MM to CM - should be 0.1"}};
        for (Object[] k : cases) {
            if (k[0] instanceof Units.MomentumUnit from) cf = conversionFactor(c, from, (Units.MomentumUnit) k[1]);
            else cf = conversionFactor(c, (Units.LengthUnit) k[0], (Units.LengthUnit) k[1]);
            if (neq(cf, (Double) k[2])) {
                ++err;
                c.cerr.put("wrong conversion factor ").put(cf).put(" for ").put((String) k[3]).put(" \n");
            }
        }
        return err;
    }

    /* ---- testGenHeavyIon ------------------------------------------------------------ */

    static int testGenHeavyIon(TestContext c) {
        final GenHeavyIon hi1 = new GenHeavyIon();
        hi1.nCollHard = 1;
        hi1.nPartProj = 2;
        hi1.nPartTarg = 3;
        hi1.nColl = 4;
        hi1.spectatorNeutrons = 5;
        hi1.spectatorProtons = 6;
        hi1.nNwoundedCollisions = 7;
        hi1.nwoundedNCollisions = 8;
        hi1.nwoundedNwoundedCollisions = 9;
        hi1.impactParameter = 10;
        hi1.eventPlaneAngle = 11;
        hi1.eccentricity = 12;
        hi1.sigmaInelNN = 13;
        hi1.centrality = 14;
        hi1.userCentEstimate = 15;
        hi1.nSpecProjN = 16;
        hi1.nSpecTargN = 17;
        hi1.nSpecProjP = 18;
        hi1.nSpecTargP = 19;
        hi1.participantPlaneAngles.put(0, 20.0);
        hi1.participantPlaneAngles.put(1, 21.0);
        hi1.eccentricities.put(0, 22.0);
        hi1.eccentricities.put(1, 23.0);
        final String s1 = hi1.serialize();
        final GenHeavyIon hi2 = new GenHeavyIon();
        hi2.fromString(s1);
        final String s2 = hi2.serialize();
        if (!s1.equals(s2)) {
            c.printf("1->%s<-\n!=\n1->%s<-\n", s1, s2);
            return 1;
        }
        return 0;
    }

    /* ---- testPrint, testPrintBug -------------------------------------------------- */

    /** What operator&lt;&lt; of a non-const shared_ptr prints: the address it holds. */
    private static String address(Object o) {
        return "0x" + Long.toHexString(0x7f0000000000L + ((long) System.identityHashCode(o) << 4));
    }

    static int testPrint(TestContext c) {
        final GenVertex vertex = new GenVertex();
        final GenVertex constVertex = new GenVertex();
        final GenParticle particle = new GenParticle(new FourVector(1, 2, 3, 4), 1, 2);
        final GenParticle constParticle = new GenParticle(new FourVector(1, 2, 3, 4), 1, 2);
        c.cout.put(address(vertex)).endl();
        Print.line(c.cout, constVertex, false);
        c.cout.endl();
        c.cout.put(address(particle)).endl();
        Print.line(c.cout, constParticle, false);
        c.cout.endl();
        return 0;
    }

    static int testPrintBug(TestContext c) {
        final GenEvent event = new GenEvent(GEV, MM);
        for (int i = 0; i < 10; i++) {
            final FourVector vector = new FourVector(1.0, 1.0, 1.0, 1.0);
            final GenVertex vertex = new GenVertex();
            vertex.setPosition(vector);
            for (int j = 0; j < 3; j++) vertex.addParticleIn(new GenParticle(vector, 1, 2));
            for (int j = 0; j < 3; j++) vertex.addParticleOut(new GenParticle(vector, 1, 2));
            event.addVertex(vertex);
        }
        Print.listing(event);
        Print.content(event);
        Print.content(c.cout, event);
        event.clear();
        return 0;
    }

    /* ---- testWeights ---------------------------------------------------------------- */

    static int testWeights(TestContext c) {
        final GenEvent evt = new GenEvent();
        final GenRunInfo run = new GenRunInfo();
        evt.setRunInfo(run);
        evt.weights().add(2.0);
        evt.weights().add(4.56);
        check(Math.abs(evt.weights().get(0) - 2.0) < Math.ulp(1.0), "std::abs(evt.weights()[0] - 2.0) < epsilon");
        check(Math.abs(evt.weights().get(1) - 4.56) < Math.ulp(1.0), "std::abs(evt.weights()[1] - 4.56) < epsilon");
        check(evt.weights().size() == 2, "evt.weights().size() == 2");
        check(!evt.weights().isEmpty(), "!evt.weights().empty()");
        final List<Double> vec = new ArrayList<>();
        for (int i = 0; i < 15; ++i) vec.add((double) i + 0.14 * (double) i);
        evt.weights().clear();
        evt.weights().addAll(vec);
        check(evt.weights().size() == 15, "evt.weights().size() == 15");
        evt.weights().remove(evt.weights().size() - 1);
        check(evt.weights().size() == 14, "evt.weights().size() == 14");
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < evt.weights().size() - 1; ++i) names.add(Integer.toString(i));
        final String nm = "tau";
        names.add(nm);
        run.setWeightNames(names);
        evt.setWeight(nm, 3.1);
        try {
            final double x = evt.weight("bad");
            c.cout.put("lookup of nonexistent name returns ").put(x).endl();
        } catch (RuntimeException e) {
            c.cout.put(e.getMessage()).endl();
            c.cout.put("HepMC testWeights: the above error is intentional").endl();
        }
        Print.listing(evt);
        return 0;
    }

    /* ---- testBoost, testPolarization, testLoops: the same event --------------------------- */

    private static double theta(TestContext c) {
        return c.rand() / (double) c.randMax() * Math.PI;
    }

    private static double phi(TestContext c) {
        return c.rand() / (double) c.randMax() * Math.PI * 2;
    }

    private static void flowAndAngles(TestContext c, GenParticle p, int flow) {
        p.addAttribute("flow1", new IntAttribute(flow));
        p.addAttribute("theta", new DoubleAttribute(theta(c)));
        p.addAttribute("phi", new DoubleAttribute(phi(c)));
    }

    private static void printParticles(GenEvent evt) {
        for (GenParticle ip : evt.particles()) Print.printLine(ip, true);
    }

    static int testBoost(TestContext c) {
        final GenEvent evt = new GenEvent(GEV, MM);
        evt.setEventNumber(1);
        evt.addAttribute("signal_process_id", new IntAttribute(20));
        final GenVertex v1 = new GenVertex();
        evt.addVertex(v1);
        v1.addAttribute("weights", new VectorDoubleAttribute(List.of(1.0, 2.0, 5.0)));
        final GenParticle p1 = new GenParticle(new FourVector(1.0, 1.0, 7000, 7000), 2212, 3);
        evt.addParticle(p1);
        p1.addAttribute("flow1", new IntAttribute(231));
        flowAndAngles(c, p1, 231);
        final GenVertex v2 = new GenVertex();
        evt.addVertex(v2);
        final GenParticle p2 = new GenParticle(new FourVector(1.0, 1.0, -7000, 7000), 2212, 3);
        evt.addParticle(p2);
        flowAndAngles(c, p2, 243);
        v2.addParticleIn(p2);
        final GenParticle p3 = new GenParticle(new FourVector(.750, -1.569, 32.191, 32.238), 1, 3);
        evt.addParticle(p3);
        flowAndAngles(c, p3, 231);
        v1.addParticleOut(p3);
        final GenParticle p4 = new GenParticle(new FourVector(-3.047, -19., -54.629, 57.920), -2, 3);
        evt.addParticle(p4);
        flowAndAngles(c, p4, 243);
        v2.addParticleOut(p4);
        final GenVertex v3 = new GenVertex();
        evt.addVertex(v3);
        v3.addParticleIn(p3);
        v3.addParticleIn(p4);
        final GenParticle p6 = new GenParticle(new FourVector(-3.813, 0.113, -1.833, 4.233), 22, 1);
        evt.addParticle(p6);
        flowAndAngles(c, p6, 231);
        v3.addParticleOut(p6);
        final GenParticle p5 = new GenParticle(new FourVector(1.517, -20.68, -20.605, 85.925), -24, 3);
        evt.addParticle(p5);
        flowAndAngles(c, p5, 243);
        v3.addParticleOut(p5);
        final GenVertex v4 = new GenVertex(new FourVector(0.12, -0.3, 0.05, 0.004));
        evt.addVertex(v4);
        v4.addParticleIn(p5);
        final GenParticle p7 = new GenParticle(new FourVector(-2.445, 28.816, 6.082, 29.552), 1, 1);
        evt.addParticle(p7);
        v4.addParticleOut(p7);
        final GenParticle p8 = new GenParticle(new FourVector(3.962, -49.498, -26.687, 56.373), -2, 1);
        evt.addParticle(p8);
        v4.addParticleOut(p8);
        evt.addAttribute("signal_process_vertex", new IntAttribute(v3.id()));
        Print.content(evt);
        Print.listing(evt, 8);
        printParticles(evt);
        final WriterAscii xout1 = new WriterAscii("testBoost1.out");
        xout1.setPrecision(6);
        xout1.writeEvent(evt);
        xout1.close();
        final FourVector b = new FourVector(0.1, 0.3, -0.2, 0);
        final FourVector bp = new FourVector(-0.1, -0.3, 0.2, 0);
        evt.boost(b);
        printParticles(evt);
        evt.boost(bp);
        printParticles(evt);
        final WriterAscii xout2 = new WriterAscii("testBoost2.out");
        xout2.setPrecision(6);
        xout2.writeEvent(evt);
        xout2.close();
        if (c.compareAsciiFiles("testBoost1.out", "testBoost2.out") != 0) return 1;
        if (evt.boost(new FourVector(-1.1, -0.3, 0.2, 0))) return 2;
        if (evt.boost(new FourVector(-1.0, -0.0, 0.0, 0))) return 3;
        if (!evt.boost(new FourVector(Math.ulp(1.0) * 0.9, 0.0, 0.0, 0))) return 4;
        final FourVector rz = new FourVector(0.0, 0.0, -0.9, 0);
        final FourVector rzinv = new FourVector(0.0, 0.0, 0.9, 0);
        evt.rotate(rz);
        printParticles(evt);
        evt.rotate(rzinv);
        printParticles(evt);
        final WriterAscii xout3 = new WriterAscii("testBoost3.out");
        xout3.setPrecision(6);
        xout3.writeEvent(evt);
        xout3.close();
        if (c.compareAsciiFiles("testBoost1.out", "testBoost3.out") != 0) return 5;
        evt.clear();
        return 0;
    }

    static int testPolarization(TestContext c) {
        final WriterAscii xout1 = new WriterAscii("testPolarization1.dat");
        final WriterAscii xout2 = new WriterAscii("testPolarization2.dat");
        final WriterAsciiHepMC2 xout4 = new WriterAsciiHepMC2("testPolarization4.out");
        final WriterAscii xout5 = new WriterAscii("testPolarization5.out");
        final GenEvent evt = new GenEvent(GEV, MM);
        evt.setEventNumber(1);
        evt.addAttribute("signal_process_id", new IntAttribute(20));
        final GenVertex v1 = new GenVertex();
        evt.addVertex(v1);
        final GenParticle p1 = new GenParticle(new FourVector(0, 0, 7000, 7000), 2212, 3);
        evt.addParticle(p1);
        p1.addAttribute("flow1", new IntAttribute(231));
        flowAndAngles(c, p1, 231);
        v1.addParticleIn(p1);
        final GenVertex v2 = new GenVertex();
        evt.addVertex(v2);
        final GenParticle p2 = new GenParticle(new FourVector(0, 0, -7000, 7000), 2212, 3);
        evt.addParticle(p2);
        flowAndAngles(c, p2, 243);
        v2.addParticleIn(p2);
        final GenParticle p3 = new GenParticle(new FourVector(.750, -1.569, 32.191, 32.238), 1, 3);
        evt.addParticle(p3);
        flowAndAngles(c, p3, 231);
        v1.addParticleOut(p3);
        final GenParticle p4 = new GenParticle(new FourVector(-3.047, -19., -54.629, 57.920), -2, 3);
        evt.addParticle(p4);
        flowAndAngles(c, p4, 243);
        v2.addParticleOut(p4);
        final GenVertex v3 = new GenVertex();
        evt.addVertex(v3);
        v3.addParticleIn(p3);
        v3.addParticleIn(p4);
        final GenParticle p6 = new GenParticle(new FourVector(-3.813, 0.113, -1.833, 4.233), 22, 1);
        evt.addParticle(p6);
        flowAndAngles(c, p6, 231);
        v3.addParticleOut(p6);
        final GenParticle p5 = new GenParticle(new FourVector(1.517, -20.68, -20.605, 85.925), -24, 3);
        evt.addParticle(p5);
        flowAndAngles(c, p5, 243);
        v3.addParticleOut(p5);
        final GenVertex v4 = new GenVertex(new FourVector(0.12, -0.3, 0.05, 0.004));
        evt.addVertex(v4);
        v4.addParticleIn(p5);
        final GenParticle p7 = new GenParticle(new FourVector(-2.445, 28.816, 6.082, 29.552), 1, 1);
        evt.addParticle(p7);
        v4.addParticleOut(p7);
        final GenParticle p8 = new GenParticle(new FourVector(3.962, -49.498, -26.687, 56.373), -2, 1);
        evt.addParticle(p8);
        v4.addParticleOut(p8);
        evt.addAttribute("signal_process_vertex", new IntAttribute(v3.id()));
        Print.content(evt);
        Print.listing(evt, 8);
        printParticles(evt);
        xout1.writeEvent(evt);
        xout4.writeEvent(evt);
        xout5.writeEvent(new GenEvent(evt));
        p2.addAttribute("theta", new DoubleAttribute(theta(c)));
        p2.addAttribute("phi", new DoubleAttribute(c.rand() / (double) c.randMax() * Math.PI));
        xout2.writeEvent(evt);
        xout1.close();
        xout2.close();
        xout4.close();
        xout5.close();
        evt.clear();
        final boolean passed = c.compareAsciiFiles("testPolarization1.dat", "testPolarization5.out") == 0
            && c.compareAsciiFiles("testPolarization1.dat", "testPolarization2.dat") != 0;
        return passed ? 0 : 1;
    }

    static int testLoops(TestContext c) {
        final WriterAscii xout1 = new WriterAscii("testLoops1.out");
        final WriterAsciiHepMC2 xout2 = new WriterAsciiHepMC2("testLoops2.out");
        GenEvent evt = new GenEvent(GEV, MM);
        evt.setEventNumber(1);
        evt.addAttribute("signal_process_id", new IntAttribute(20));
        final GenVertex v1 = new GenVertex();
        evt.addVertex(v1);
        final GenParticle p1 = new GenParticle(new FourVector(0, 0, 7000, 7000), 2212, 3);
        v1.addParticleIn(p1);
        p1.addAttribute("flow1", new IntAttribute(231));
        flowAndAngles(c, p1, 231);
        final GenVertex v2 = new GenVertex();
        evt.addVertex(v2);
        final GenParticle p2 = new GenParticle(new FourVector(0, 0, -7000, 7000), 2212, 3);
        v2.addParticleIn(p2);
        flowAndAngles(c, p2, 243);
        final GenParticle p3 = new GenParticle(new FourVector(.750, -1.569, 32.191, 32.238), 1, 3);
        v1.addParticleOut(p3);
        flowAndAngles(c, p3, 231);
        final GenParticle p4 = new GenParticle(new FourVector(-3.047, -19., -54.629, 57.920), -2, 3);
        v2.addParticleOut(p4);
        flowAndAngles(c, p4, 243);
        final GenVertex v3 = new GenVertex();
        evt.addVertex(v3);
        v3.addParticleIn(p3);
        v3.addParticleIn(p4);
        final GenParticle p6 = new GenParticle(new FourVector(-3.813, 0.113, -1.833, 4.233), 22, 1);
        evt.addParticle(p6);
        flowAndAngles(c, p6, 231);
        v3.addParticleOut(p6);
        final GenParticle p5 = new GenParticle(new FourVector(1.517, -20.68, -20.605, 85.925), -24, 3);
        v3.addParticleOut(p5);
        flowAndAngles(c, p5, 243);
        final GenVertex v4 = new GenVertex(new FourVector(0.12, -0.3, 0.05, 0.004));
        evt.addVertex(v4);
        v4.addParticleIn(p5);
        v4.addParticleOut(new GenParticle(new FourVector(-2.445, 28.816, 6.082, 29.552), 1, 1));
        v4.addParticleOut(new GenParticle(new FourVector(3.962, -49.498, -26.687, 56.373), -2, 1));
        final GenParticle ploop = new GenParticle(new FourVector(0.0, 0.0, 0.0, 0.0), 21, 3);
        v3.addParticleOut(ploop);
        v2.addParticleIn(ploop);
        evt.addAttribute("signal_process_vertex", new IntAttribute(v3.id()));
        Print.content(evt);
        Print.listing(evt, 8);
        printParticles(evt);
        xout1.writeEvent(evt);
        xout2.writeEvent(evt);
        evt.clear();
        xout1.close();
        xout2.close();
        int nxin1 = 0;
        final ReaderAscii xin1 = new ReaderAscii("testLoops1.out");
        if (xin1.failed()) {
            xin1.close();
            return 102;
        }
        while (!xin1.failed()) {
            xin1.readEvent(evt);
            if (xin1.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            evt.clear();
            nxin1++;
        }
        xin1.close();
        int nxin2 = 0;
        final ReaderAsciiHepMC2 xin2 = new ReaderAsciiHepMC2("testLoops2.out");
        if (xin2.failed()) {
            xin2.close();
            return 103;
        }
        while (!xin2.failed()) {
            xin2.readEvent(evt);
            if (xin2.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            evt.clear();
            nxin2++;
        }
        xin2.close();
        return 10 * Math.abs(nxin1 - 1) + Math.abs(nxin2 - 1);
    }

    /* ---- testMass ------------------------------------------------------------------- */

    /** IsGoodEventDIS of IsGoodEvent.h: a final-state electron of more than 10 GeV. */
    private static boolean isGoodEventDIS(GenEvent evt) {
        for (GenParticle p : evt.particles()) {
            if (p.status() == 1 && Math.abs(p.pdgId()) == 11 && p.momentum().e() > 10.) return true;
        }
        return false;
    }

    private static boolean massInfo(GenEvent e, COStream os) {
        for (GenParticle p : e.particles()) {
            final double gm = p.generatedMass();
            final double m = p.momentum().m();
            final double d = Math.abs(m - gm);
            if (d > 1.0e-4 && gm > 1.0e-4) {
                os.put("Event ").put(e.eventNumber()).put(" Particle ").put(p.pdgId()).put(" generated mass ").put(gm)
                    .put(" mass from momentum ").put(m).put(" difference ").put(d).endl();
                return false;
            }
        }
        return true;
    }

    static int testMass(TestContext c) throws Exception {
        final ReaderAsciiHepMC2 asciiIn = new ReaderAsciiHepMC2("inputMass.hepmc");
        if (asciiIn.failed()) return 1;
        final WriterAsciiHepMC2 asciiOut = new WriterAsciiHepMC2("testMass1.out");
        int icount = 0;
        int numGoodEvents = 0;
        double x1;
        double x2;
        double q;
        double xf1;
        double xf2;
        final GenEvent evt = new GenEvent();
        final COStream cout = c.cout;
        while (!asciiIn.failed()) {
            final boolean readOK = asciiIn.readEvent(evt);
            if (!readOK) return 1;
            icount++;
            if (icount % 50 == 1) cout.put("Processing Event Number ").put(icount).put(" its # ").put(evt.eventNumber()).endl();
            if (isGoodEventDIS(evt)) {
                if (numGoodEvents == 0) {
                    x1 = Math.min(0.8, 0.07 * icount);
                    x2 = 1 - x1;
                    q = 1.69 * icount;
                    if (evt.beams().size() == 2) {
                        final GenParticle bp1 = evt.beams().get(0);
                        xf1 = x1 * bp1.momentum().p3mod();
                        xf2 = x2 * bp1.momentum().p3mod();
                    } else {
                        xf1 = x1 * 0.34;
                        xf2 = x2 * 0.34;
                    }
                    final GenPdfInfo pdf = new GenPdfInfo();
                    evt.addAttribute("GenPdfInfo", pdf);
                    pdf.set(2, 3, x1, x2, q, xf1, xf2, 230, 230);
                    final GenHeavyIon ion = new GenHeavyIon();
                    evt.addAttribute("GenHeavyIon", ion);
                    ion.set(23, 11, 12, 15, 3, 5, 0, 0, 0, 0.0145, 0.0, 0.0, 0.0, 0.23, 0.);
                }
                cout.put("saving Event ").put(evt.eventNumber()).endl();
                if (!evt.weights().isEmpty()) {
                    cout.put("Weights: ");
                    for (double w : evt.weights()) cout.put(" ").put(w);
                    cout.endl();
                }
                asciiOut.writeEvent(evt);
                ++numGoodEvents;
            }
            evt.clear();
        }
        cout.put(numGoodEvents).put(" out of ").put(icount).put(" processed events passed the cuts. Finished.").endl();
        asciiIn.close();
        asciiOut.close();
        final CInput istr = CInput.open(c.path("testMass1.out"));
        if (istr.fail()) {
            c.cerr.put("testMass: cannot open ").endl();
            return 1;
        }
        final ReaderAsciiHepMC2 xin = new ReaderAsciiHepMC2(istr);
        if (xin.failed()) return 1;
        final WriterAsciiHepMC2 xout = new WriterAsciiHepMC2("testMass2.out");
        if (xout.failed()) return 1;
        int ixin = 0;
        while (!xin.failed()) {
            final boolean readOK = xin.readEvent(evt);
            if (!readOK) return 1;
            ixin++;
            cout.put("reading Event ").put(evt.eventNumber()).endl();
            if (!evt.weights().isEmpty()) {
                cout.put("Weights: ");
                for (double w : evt.weights()) cout.put(" ").put(w);
                cout.endl();
            }
            xout.writeEvent(evt);
            if (!massInfo(evt, cout)) return 1;
            evt.clear();
        }
        cout.put(ixin).put(" events in the second pass. Finished.").endl();
        xin.close();
        xout.close();
        istr.close();
        return 0;
    }

    /* ---- testMultipleCopies --------------------------------------------------------- */

    static int testMultipleCopies(TestContext c) throws Exception {
        final COStream os = new COStream();
        try {
            final ReaderAsciiHepMC2 asciiIn = new ReaderAsciiHepMC2("inputMultipleCopies1.hepmc");
            if (asciiIn.failed()) return 1;
            final ReaderAsciiHepMC2 asciiIn2 = new ReaderAsciiHepMC2("inputMultipleCopies2.hepmc");
            if (asciiIn2.failed()) return 2;
            final OutputStream os1 = Files.newOutputStream(c.path("testMultipleOriginals.out"));
            final OutputStream os2 = Files.newOutputStream(c.path("testMultipleCopies1.out"));
            final OutputStream os3 = Files.newOutputStream(c.path("testMultipleCopies2.out"));
            final WriterAscii out1 = new WriterAscii(os1, null);
            final WriterAscii out2 = new WriterAscii(os2, null);
            final WriterAscii out3 = new WriterAscii(os3, null);
            try {
                int icount = 0;
                int numGoodEvents = 0;
                int icnt;
                final GenEvent evt1 = new GenEvent();
                asciiIn.readEvent(evt1);
                if (asciiIn.failed()) return 3;
                final GenEvent evt2 = new GenEvent();
                asciiIn2.readEvent(evt2);
                if (asciiIn2.failed()) return 4;
                final GenEvent evt3 = new GenEvent();
                asciiIn.readEvent(evt3);
                if (asciiIn.failed()) return 5;
                while (!asciiIn.failed() && !asciiIn2.failed()) {
                    icount++;
                    os.put("Processing Event Number ").put(icount).put(" stream 1 # ").put(evt1.eventNumber())
                        .put(" stream 2 # ").put(evt2.eventNumber()).endl();
                    os.put("good event in stream 1 # ").put(evt1.eventNumber()).endl();
                    out1.writeEvent(evt1);
                    ++numGoodEvents;
                    final GenEvent ec = new GenEvent(evt1);
                    out3.writeEvent(ec);
                    icnt = 0;
                    for (int k = 0; k < ec.particles().size(); k++) {
                        ++icnt;
                        os.put("particle ").put(icnt).put(" barcode ").endl();
                    }
                    final GenEvent evt4 = new GenEvent(evt1);
                    out2.writeEvent(evt4);
                    evt4.clear();
                    evt1.clear();
                    evt2.clear();
                    asciiIn.readEvent(evt1);
                    asciiIn2.readEvent(evt2);
                }
                evt1.clear();
                evt2.clear();
                evt3.clear();
                os.endl();
                os.put(numGoodEvents).put(" out of ").put(icount).put(" processed events passed the cuts.").endl();
                os.endl();
                os.put(" GenEvent copy constructor passes the test").endl();
                os.endl();
                asciiIn.close();
                asciiIn2.close();
            } finally {
                // the destructors, in reverse order of construction
                out3.close();
                out2.close();
                out1.close();
                os3.close();
                os2.close();
                os1.close();
            }
            {
                final ReaderAsciiHepMC2 asciiIn3 = new ReaderAsciiHepMC2("inputMultipleCopies1.hepmc");
                if (asciiIn3.failed()) return 4;
                final GenEvent evt5 = new GenEvent();
                asciiIn3.readEvent(evt5);
                final GenEvent evt6 = new GenEvent();
                os.put("event number for evt5: ").put(evt5.eventNumber()).endl();
                os.put("event number for evt6: ").put(evt6.eventNumber()).endl();
                evt6.assign(evt5);
                evt5.clear();
                os.put("event number for evt6 after copy: ").put(evt6.eventNumber()).endl();
                os.endl();
                evt6.clear();
                os.put(" GenEvent operator= passes the test").endl();
                os.endl();
                asciiIn3.readEvent(evt5);
                if (asciiIn3.failed()) return 5;
                asciiIn3.readEvent(evt6);
                if (asciiIn3.failed()) return 6;
                final GenEvent evt7 = new GenEvent(evt5);
                final GenEvent evt8 = new GenEvent(evt6);
                os.put("event number for evt5: ").put(evt5.eventNumber()).endl();
                os.put("event number for evt6: ").put(evt6.eventNumber()).endl();
                sizes(os, "before swap, evt5 has: ", evt5);
                sizes(os, "before swap, evt6 has: ", evt6);
                sizes(os, "before swap, evt7 has: ", evt7);
                sizes(os, "before swap, evt8 has: ", evt8);
                // std::swap through copies: GenEvent has no move operations
                final GenEvent tmp = new GenEvent(evt6);
                evt6.assign(evt5);
                evt5.assign(tmp);
                os.put("event number for evt5 after swap: ").put(evt5.eventNumber()).endl();
                os.put("event number for evt6 after swap: ").put(evt6.eventNumber()).endl();
                sizes(os, "after swap, evt6 has: ", evt6);
                sizes(os, "after swap, evt7 has: ", evt7);
                sizes(os, "after swap, evt5 has: ", evt5);
                sizes(os, "after swap, evt8 has: ", evt8);
                os.endl();
                os.put(" GenEvent swap passes the test").endl();
                os.endl();
                evt5.clear();
                evt6.clear();
                evt7.clear();
                evt8.clear();
                asciiIn3.close();
            }
            final boolean passed = c.compareAsciiFiles("testMultipleCopies1.out", "testMultipleCopies2.out", true) == 0
                && c.compareAsciiFiles("testMultipleCopies1.out", "testMultipleOriginals.out", true) == 0;
            return passed ? 0 : 1;
        } finally {
            Files.writeString(c.path("testMultipleCopies.out"), os.str(), StandardCharsets.ISO_8859_1);
        }
    }

    private static void sizes(COStream os, String what, GenEvent e) {
        os.put(what).put(e.vertices().size()).put(" vertices and ").put(e.particles().size()).put(" particles").endl();
    }

    /* ---- testAttributes ------------------------------------------------------------- */

    /** The first vertex and the 12 generations of two children every generator of the test builds. */
    private static GenEvent seed() {
        final GenEvent evt = new GenEvent();
        evt.setRunInfo(new GenRunInfo());
        final GenParticle b1 = new GenParticle(new FourVector(0.0, 0.0, 7000.0, 7000.0), 2212, 3);
        final GenParticle b2 = new GenParticle(new FourVector(0.750, -1.569, 32.191, 32.238), 1, 3);
        final GenParticle b3 = new GenParticle(new FourVector(0.750, -1.569, 32.191, 32.238), 1, 3);
        final GenVertex v1 = new GenVertex();
        v1.addParticleIn(b1);
        v1.addParticleIn(b2);
        v1.addParticleOut(b3);
        evt.addVertex(v1);
        return evt;
    }

    /** What each new vertex and its two children are given. */
    private interface Decay {
        void decay(GenVertex v, GenParticle p1, GenParticle p2);
    }

    private static void grow(GenEvent evt, Decay decay) {
        for (int z = 0; z < 12; z++) {
            final List<GenParticle> particles = new ArrayList<>(evt.particles());
            for (GenParticle p : particles) {
                if (p.endVertex() != null) continue;
                final GenParticle p1 = new GenParticle(new FourVector(0.0, 0.0, 7000.0, 7000.0), 2212, 3);
                final GenParticle p2 = new GenParticle(new FourVector(0.750, -1.569, 32.191, 32.238), 1, 3);
                final GenVertex v = new GenVertex();
                v.addParticleIn(p);
                v.addParticleOut(p1);
                v.addParticleOut(p2);
                evt.addVertex(v);
                decay.decay(v, p1, p2);
            }
        }
    }

    static GenEvent generate1() {
        final GenEvent evt = seed();
        grow(evt, (v, p1, p2) -> {
            v.addAttribute("barcode", new IntAttribute(-20));
            p1.addAttribute("phi", new DoubleAttribute(0.1));
            p1.addAttribute("theta", new DoubleAttribute(0.1));
            p1.addAttribute("barcode", new IntAttribute(10));
            p2.addAttribute("phi", new DoubleAttribute(0.1));
            p2.addAttribute("theta", new DoubleAttribute(0.1));
            p2.addAttribute("barcode", new IntAttribute(10));
        });
        return evt;
    }

    static GenEvent generate2() {
        final GenEvent evt = seed();
        final List<String> names = new ArrayList<>(2048);
        final List<Attribute> atts = new ArrayList<>(2048);
        final List<Integer> ids = new ArrayList<>(2048);
        grow(evt, (v, p1, p2) -> {
            names.addAll(List.of("barcode", "barcode", "phi", "theta", "barcode", "phi", "theta"));
            atts.add(new IntAttribute(-20));
            atts.add(new DoubleAttribute(0.1));
            atts.add(new DoubleAttribute(0.1));
            atts.add(new IntAttribute(10));
            atts.add(new DoubleAttribute(0.1));
            atts.add(new DoubleAttribute(0.1));
            atts.add(new IntAttribute(10));
            ids.addAll(List.of(v.id(), p1.id(), p1.id(), p1.id(), p2.id(), p2.id(), p2.id()));
        });
        evt.addAttributes(names, atts, ids);
        return evt;
    }

    static GenEvent generate3() {
        final GenEvent evt = seed();
        final List<Attribute> attsb = new ArrayList<>();
        final List<Integer> idsb = new ArrayList<>();
        final List<Attribute> attsp = new ArrayList<>();
        final List<Integer> idsp = new ArrayList<>();
        final List<Attribute> attst = new ArrayList<>();
        final List<Integer> idst = new ArrayList<>();
        grow(evt, (v, p1, p2) -> {
            attsb.add(new IntAttribute(-20));
            attst.add(new DoubleAttribute(0.1));
            attsp.add(new DoubleAttribute(0.1));
            attsb.add(new IntAttribute(10));
            attst.add(new DoubleAttribute(0.1));
            attsp.add(new DoubleAttribute(0.1));
            attsb.add(new IntAttribute(10));
            idsb.add(v.id());
            idsb.add(p1.id());
            idsp.add(p1.id());
            idst.add(p1.id());
            idsb.add(p2.id());
            idsp.add(p2.id());
            idst.add(p2.id());
        });
        evt.addAttributes("barcode", attsb, idsb);
        evt.addAttributes("phi", attsp, idsp);
        evt.addAttributes("theta", attst, idst);
        return evt;
    }

    static GenEvent generate4() {
        final GenEvent evt = seed();
        final List<Map.Entry<Integer, ? extends Attribute>> attsb = new ArrayList<>();
        final List<Map.Entry<Integer, ? extends Attribute>> attsp = new ArrayList<>();
        final List<Map.Entry<Integer, ? extends Attribute>> attst = new ArrayList<>();
        grow(evt, (v, p1, p2) -> {
            attsb.add(Map.entry(v.id(), new IntAttribute(-20)));
            attst.add(Map.entry(p1.id(), new DoubleAttribute(0.1)));
            attsp.add(Map.entry(p1.id(), new DoubleAttribute(0.1)));
            attsb.add(Map.entry(p1.id(), new IntAttribute(10)));
            attst.add(Map.entry(p2.id(), new DoubleAttribute(0.1)));
            attsp.add(Map.entry(p2.id(), new DoubleAttribute(0.1)));
            attsb.add(Map.entry(p2.id(), new IntAttribute(10)));
        });
        evt.addAttributes("barcode", attsb);
        evt.addAttributes("phi", attsp);
        evt.addAttributes("theta", attst);
        return evt;
    }

    static int testAttributes(TestContext c) {
        final int n = 10;
        final long[] ns = new long[4];
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) generate1();
        ns[0] = System.nanoTime() - t0;
        t0 = System.nanoTime();
        for (int i = 0; i < n; i++) generate2();
        ns[1] = System.nanoTime() - t0;
        t0 = System.nanoTime();
        for (int i = 0; i < n; i++) generate3();
        ns[2] = System.nanoTime() - t0;
        t0 = System.nanoTime();
        for (int i = 0; i < n; i++) generate4();
        ns[3] = System.nanoTime() - t0;
        c.cout.put(ns[0]).put(" ").put(ns[1]).put(" ").put(ns[2]).put(" ").put(ns[3]).endl();
        return 0;
    }

    /* ---- testVertexAttributes, testOrder ----------------------------------------------- */

    static int testVertexAttributes(TestContext c) {
        final GenEvent e = seed();
        grow(e, (v, p1, p2) -> {
            v.addAttribute("barcode", new IntAttribute(v.id()));
            p1.addAttribute("phi", new DoubleAttribute(0.1));
            p1.addAttribute("theta", new DoubleAttribute(0.1));
            p1.addAttribute("barcode", new IntAttribute(p1.id()));
            p2.addAttribute("phi", new DoubleAttribute(0.1));
            p2.addAttribute("theta", new DoubleAttribute(0.1));
            p2.addAttribute("barcode", new IntAttribute(p2.id()));
        });
        final GenVertex v1 = last(e.vertices());
        final IntAttribute barcode1 = v1.attribute("barcode", IntAttribute.class);
        final int val1 = barcode1 != null ? barcode1.value() : -10001;
        e.removeVertex(e.vertices().get(e.vertices().size() / 2));
        final GenVertex v2 = last(e.vertices());
        final IntAttribute barcode2 = v2.attribute("barcode", IntAttribute.class);
        final int val2 = barcode2 != null ? barcode2.value() : -10002;
        if (val1 == val2) return 0;
        Print.printLine(v2, true);
        return 1;
    }

    static int testOrder(TestContext c) {
        final GenEvent evt = new GenEvent();
        evt.setRunInfo(new GenRunInfo());
        final GenParticle b1 = new GenParticle(new FourVector(0.0, 0.0, 7000.0, 7000.0), 2212, 3);
        final GenParticle b2 = new GenParticle(new FourVector(0.750, -1.569, 32.191, 32.238), 1, 3);
        final GenParticle b3 = new GenParticle(new FourVector(0.750, -1.569, 32.191, -32.238), 1, 3);
        final GenParticle b4 = new GenParticle(new FourVector(0.750, -1.569, 32.191, -32.238), 1, 3);
        final GenParticle b5 = new GenParticle(new FourVector(0.750, -1.569, 32.191, -32.238), 1, 3);
        final GenParticle b6 = new GenParticle(new FourVector(0.750, -1.569, 32.191, -32.238), 1, 3);
        final GenVertex v1 = new GenVertex();
        v1.addParticleIn(b3);
        v1.addParticleIn(b4);
        v1.addParticleOut(b5);
        v1.addParticleOut(b6);
        evt.addVertex(v1);
        evt.addBeamParticle(b1);
        evt.addBeamParticle(b2);
        final GenVertex v2 = new GenVertex();
        final GenVertex v3 = new GenVertex();
        v2.addParticleOut(b3);
        v2.addParticleIn(b1);
        v3.addParticleOut(b4);
        v3.addParticleIn(b2);
        evt.addVertex(v2);
        evt.addVertex(v3);
        final WriterAscii w = new WriterAscii("testOrder.hepmc");
        w.writeEvent(evt);
        w.close();
        final ReaderAscii r = new ReaderAscii("testOrder.hepmc");
        final GenEvent e = new GenEvent();
        r.readEvent(e);
        r.close();
        return 0;
    }

    /* ---- testHEPEVTWrapper1 --------------------------------------------------------- */

    static int testHEPEVTWrapper1(TestContext c) {
        final int nmxhep = 4000;
        final GenEvent evt1 = new GenEvent();
        evt1.setRunInfo(new GenRunInfo());
        final GenParticle b2 = new GenParticle(new FourVector(0.0, 0.0, 7000.0, 7000.0), 2212, 3);
        final GenParticle b1 = new GenParticle(new FourVector(0.750, -1.569, 32.191, 32.238), 1, 3);
        final GenParticle b3 = new GenParticle(new FourVector(0.750, -1.569, 32.191, -32.238), 1, 3);
        final GenVertex v1 = new GenVertex();
        v1.addParticleIn(b1);
        v1.addParticleIn(b2);
        v1.addParticleOut(b3);
        evt1.addVertex(v1);
        for (int z = 0; z < 5; z++) {
            final List<GenParticle> particles = new ArrayList<>(evt1.particles());
            for (GenParticle p : particles) {
                if (p.endVertex() != null) continue;
                final GenParticle q2 = new GenParticle(new FourVector(0.0, 0.0, 7000.0 + 0.01 * evt1.particles().size(), 7000.0), 2212, 3);
                final GenParticle q1 = new GenParticle(new FourVector(0.750, -1.569, 32.191 + 0.01 * evt1.particles().size(), 32.238), 1, 3);
                final GenVertex v = new GenVertex();
                v.addParticleIn(p);
                v.addParticleOut(q1);
                v.addParticleOut(q2);
                evt1.addVertex(v);
            }
        }
        // the common block X, zeroed, that every wrapper of the C++ points at or copies
        final HEPEVT x = new HEPEVT(nmxhep);
        if (!x.fromGenEvent(evt1)) {
            c.cerr.put("test1.GenEvent_to_HEPEVT failed. Check your HEPEVT record and make sure NMXHEP is used consistenlty.").endl();
            return 1;
        }
        final GenEvent evt2 = new GenEvent();
        if (!x.toGenEvent(evt2)) {
            c.cerr.put("test2.HEPEVT_to_GenEvent failed. Check your HEPEVT record and make sure NMXHEP is used consistenlty.").endl();
            return 1;
        }
        final HEPEVT test3 = new HEPEVT(20000);
        test3.fromBytes(x.toBytes(), nmxhep, nmxhep);
        final GenEvent evt3 = new GenEvent();
        if (!test3.toGenEvent(evt3)) {
            c.cerr.put("test3.HEPEVT_to_GenEvent with internal storage failed. Check your HEPEVT record and make sure NMXHEP is used consistenlty.").endl();
            return 1;
        }
        final GenEvent evt4 = new GenEvent();
        x.printHepevt(c.cout);
        if (!x.toGenEvent(evt4)) {
            c.cerr.put("HEPEVT_Wrapper_Runtime_Static::HEPEVT_to_GenEvent static failed. Check your HEPEVT record and make sure NMXHEP is used consistenlty.").endl();
            return 1;
        }
        final GenEvent evt5 = new GenEvent();
        if (!x.toGenEvent(evt5)) {
            c.cerr.put("HEPEVT_Wrapper::HEPEVT_to_GenEvent wrapper failed. Check your HEPEVT record and make sure NMXHEP is used consistenlty.").endl();
            return 1;
        }
        final HEPEVT test6 = new HEPEVT(20000);
        test6.fromBytes(x.toBytes(), nmxhep, nmxhep);
        final GenEvent evt6 = new GenEvent();
        if (!test6.toGenEvent(evt6)) {
            c.cerr.put("test6.HEPEVT_to_GenEvent with internal storage and non-default max number of entries failed. Check your HEPEVT record and make sure NMXHEP is used consistenlty.").endl();
            return 1;
        }
        final GenEvent[] events = {evt1, evt2, evt3, evt4, evt5, evt6};
        for (int i = 0; i < events.length; i++) {
            final WriterAscii w = new WriterAscii("testHEPEVTWrapper1output" + (i + 1) + ".txt");
            w.writeEvent(events[i]);
            w.close();
        }
        int result = 0;
        for (int i = 1; i < events.length; i++) {
            result += c.compareAsciiFiles("testHEPEVTWrapper1output" + i + ".txt", "testHEPEVTWrapper1output" + (i + 1) + ".txt");
        }
        return result;
    }

    /* ---- testSearch1, testThreadssearch -------------------------------------------- */

    static int testSearch1(TestContext c) {
        final GenEvent e = new GenEvent();
        final GenVertex v0 = new GenVertex();
        e.addVertex(v0);
        final int n = 3;
        final int iterations = 4;
        int it = 0;
        for (;;) {
            if (it > iterations) {
                for (GenVertex v : new ArrayList<>(e.vertices())) {
                    if (!v.particlesOut().isEmpty()) continue;
                    for (int i = 0; i < n; i++) v.addParticleOut(new GenParticle());
                }
                break;
            }
            final List<GenVertex> vertices = new ArrayList<>(e.vertices());
            for (GenVertex v : vertices) {
                if (!v.particlesOut().isEmpty()) continue;
                for (int i = 0; i < n; i++) v.addParticleOut(new GenParticle());
                for (GenParticle p : new ArrayList<>(v.particlesOut())) {
                    final GenVertex vx = new GenVertex();
                    vx.addParticleIn(p);
                    e.addVertex(vx);
                }
            }
            it++;
        }
        long np = 0;
        for (GenParticle p : e.particles()) np += Relatives.ANCESTORS.apply(p).size();
        return np == 0 ? 1 : 0;
    }

    private static GenEvent generateSearch(int zmax) {
        final GenEvent evt = new GenEvent();
        evt.setRunInfo(new GenRunInfo());
        final GenParticle b2 = new GenParticle(new FourVector(0.0, 0.0, 7000.0, 7000.0), 2212, 3);
        final GenParticle b1 = new GenParticle(new FourVector(0.750, -1.569, 32.191, 32.238), 1, 3);
        final GenParticle b3 = new GenParticle(new FourVector(0.750, -1.569, 32.191, -32.238), 1, 3);
        final GenVertex v1 = new GenVertex();
        v1.addParticleIn(b1);
        v1.addParticleIn(b2);
        v1.addParticleOut(b3);
        evt.addVertex(v1);
        for (int z = 0; z < zmax; z++) {
            for (GenParticle p : new ArrayList<>(evt.particles())) {
                if (p.endVertex() != null) continue;
                final GenParticle p2 = new GenParticle(new FourVector(0.0, 0.0, 7000.0 + 0.01 * evt.particles().size(), 7000.0), 2212, 3);
                final GenParticle p1 = new GenParticle(new FourVector(0.750, -1.569, 32.191 + 0.01 * evt.particles().size(), 32.238), 1, 3);
                final GenVertex v = new GenVertex();
                v.addParticleIn(p);
                v.addParticleOut(p1);
                v.addParticleOut(p2);
                evt.addVertex(v);
            }
        }
        return evt;
    }

    static int testThreadssearch(TestContext c) throws InterruptedException {
        final int copies = 4;
        final List<GenEvent> evts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            evts.add(generateSearch(5 + i / 3));
            evts.get(evts.size() - 1).setEventNumber(i);
        }
        final Random g = new Random();
        final List<List<GenEvent>> thrEvts = new ArrayList<>();
        for (int i = 0; i < copies; i++) {
            final List<GenEvent> copy = new ArrayList<>(evts);
            Collections.shuffle(copy, g);
            thrEvts.add(copy);
        }
        final List<TreeMap<Integer, Integer>> res = new ArrayList<>();
        final List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < copies; i++) {
            final TreeMap<Integer, Integer> r = new TreeMap<>();
            res.add(r);
            final List<GenEvent> mine = thrEvts.get(i);
            threads.add(c.thread(() -> {
                for (GenEvent e : mine) r.put(e.eventNumber(), Relatives.DESCENDANTS.apply(e.particles().get(2)).size());
            }));
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        for (int k = 1; k < copies; k++) {
            if (!res.get(k).equals(res.get(0))) return 1;
        }
        return 0;
    }
}
