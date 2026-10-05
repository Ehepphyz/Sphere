package com.sphere.core.hepmc3.validation;

import com.sphere.core.hepmc3.Compression;
import com.sphere.core.hepmc3.FourVector;
import com.sphere.core.hepmc3.GenCrossSection;
import com.sphere.core.hepmc3.GenEvent;
import com.sphere.core.hepmc3.GenParticle;
import com.sphere.core.hepmc3.GenRunInfo;
import com.sphere.core.hepmc3.IntAttribute;
import com.sphere.core.hepmc3.Print;
import com.sphere.core.hepmc3.Reader;
import com.sphere.core.hepmc3.ReaderAscii;
import com.sphere.core.hepmc3.ReaderAsciiHepMC2;
import com.sphere.core.hepmc3.ReaderFactory;
import com.sphere.core.hepmc3.ReaderGZ;
import com.sphere.core.hepmc3.ReaderHEPEVT;
import com.sphere.core.hepmc3.ReaderLHEF;
import com.sphere.core.hepmc3.ReaderMT;
import com.sphere.core.hepmc3.Setup;
import com.sphere.core.hepmc3.Writer;
import com.sphere.core.hepmc3.WriterAscii;
import com.sphere.core.hepmc3.WriterAsciiHepMC2;
import com.sphere.core.hepmc3.WriterGZ;
import com.sphere.core.hepmc3.WriterHEPEVT;
import com.sphere.core.hepmc3.Writerprotobuf;
import com.sphere.core.hepmc3.cxx.CInput;
import com.sphere.core.hepmc3.cxx.COStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.sphere.core.hepmc3.Units.LengthUnit.MM;
import static com.sphere.core.hepmc3.Units.MomentumUnit.GEV;

/**
 * The input/output tests of HepMC3's test directory: conversions between
 * the formats there and back, compressed files, the reader factory, several
 * readers on one file, skipping, and malformed input. Each is main() of the
 * .cc, statement for statement.
 */
final class IOTests {

    private IOTests() {
    }

    static List<TestProgram> all() {
        final List<TestProgram> l = new ArrayList<>();
        l.add(TestProgram.of("testIO1", List.of("inputIO1.hepmc"), IOTests::testIO1));
        l.add(TestProgram.of("testIO3", List.of("inputIO3.hepevt"), IOTests::testIO3));
        l.add(TestProgram.of("testIO5", List.of("inputIO5.hepmc"), IOTests::testIO5));
        l.add(TestProgram.of("testIO6", List.of("inputIO6.hepmc"), IOTests::testIO6));
        l.add(TestProgram.of("testIO7", List.of("inputIO7.hepmc"), IOTests::testIO7));
        l.add(TestProgram.of("testIO8", List.of("inputIO8.hepmc"), IOTests::testIO8));
        l.add(TestProgram.of("testIO9", List.of("inputIO9.hepmc"),
            TestProgram.Rules.selfCheck("the C++ references cannot be made on Windows (no lzma, bz2, zstd DLLs); gzip round trip checked by the test itself"),
            IOTests::testIO9));
        l.add(TestProgram.of("testIO10", List.of("inputIO10.hepmc"), IOTests::testIO10));
        l.add(TestProgram.of("testIO11", List.of("inputIO11.lhe", "inputIO11_1.plhe", "inputIO11_2.plhe"), IOTests::testIO11));
        l.add(TestProgram.of("testIO12", List.of(), IOTests::testIO12));
        l.add(TestProgram.of("testIO13", List.of("inputIO13.lhe"), IOTests::testIO13));
        l.add(TestProgram.of("testIO30", List.of("inputIO30.hepmc"), IOTests::testIO30));
        l.add(TestProgram.of("testIO31", List.of("inputIO31.hepmc"), IOTests::testIO31));
        final List<String> io32 = List.of("inputIO32.hepmc", "inputIO32_invalid.hepmc", "inputIO32_missing_count.hepmc",
            "inputIO32_reset.hepmc", "inputIO32_truncated.hepmc");
        for (String scenario : List.of("positions", "reset", "truncated", "invalid", "missing_count", "roundtrip")) {
            l.add(TestProgram.of("testIO32", io32, IOTests::testIO32).withArgs(scenario));
        }
        l.add(TestProgram.of("testDelete", List.of("inputDelete.hepmc"), IOTests::testDelete));
        l.add(TestProgram.of("testSkip1", List.of("inputSkip1.hepmc"),
            TestProgram.Rules.EXACT.stdout(TestProgram.Output.TIMES, "prints how long it took"), IOTests::testSkip1));
        l.add(TestProgram.of("testReaderFactory1", List.of("inputReaderFactory1.hepmc"), IOTests::testReaderFactory1));
        final TestProgram.Rules unixOnly = TestProgram.Rules.EXACT.stdout(TestProgram.Output.IGNORE,
            "the C++ runs this on Unix only (main returns 0 on Windows); the port runs the Unix code");
        l.add(TestProgram.of("testReaderFactory3", List.of("inputReaderFactory1.hepmc"), unixOnly, IOTests::testReaderFactory3));
        l.add(TestProgram.of("testReaderFactory4", List.of("inputReaderFactory1.hepmc"), unixOnly, IOTests::testReaderFactory4));
        l.add(TestProgram.of("testReaderFactory5", List.of("inputReaderFactory5.hepmc"), unixOnly, IOTests::testReaderFactory5));
        l.add(TestProgram.of("testSingleVertexHepMC2", List.of("inputSingleVertexHepMC2.hepmc"), IOTests::testSingleVertexHepMC2));
        l.add(TestProgram.of("testSherpa140Crash", List.of("inputSherpa140Crash.hepmc"), IOTests::testSherpa140Crash));
        l.add(TestProgram.of("testThreads1", List.of("inputThreads1.hepmc"),
            TestProgram.Rules.EXACT.stdout(TestProgram.Output.SORTED, "threads print in the order they run"), IOTests::testThreads1));
        return l;
    }

    /** Reads every event of a reader and writes it with a writer, printing the end of file as the tests do. */
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

    /* ---- conversions and back ---------------------------------------------------------- */

    static int testIO1(TestContext c) {
        final ReaderAsciiHepMC2 inputA = new ReaderAsciiHepMC2("inputIO1.hepmc");
        if (inputA.failed()) return 1;
        final WriterAscii outputA = new WriterAscii("frominputIO1.hepmc");
        if (outputA.failed()) return 2;
        copy(c, inputA, outputA);
        inputA.close();
        outputA.close();
        final ReaderAscii inputB = new ReaderAscii("frominputIO1.hepmc");
        if (inputB.failed()) return 3;
        final WriterAsciiHepMC2 outputB = new WriterAsciiHepMC2("fromfrominputIO1.hepmc");
        if (outputB.failed()) return 4;
        copy(c, inputB, outputB);
        inputB.close();
        outputB.close();
        return c.compareAsciiFiles("fromfrominputIO1.hepmc", "inputIO1.hepmc");
    }

    static int testIO3(TestContext c) {
        final ReaderHEPEVT inputA = new ReaderHEPEVT("inputIO3.hepevt");
        if (inputA.failed()) return 1;
        final WriterAscii outputA = new WriterAscii("frominputIO3.hepmc");
        if (outputA.failed()) return 2;
        copy(c, inputA, outputA);
        inputA.close();
        outputA.close();
        final ReaderAscii inputB = new ReaderAscii("frominputIO3.hepmc");
        if (inputB.failed()) return 3;
        final WriterHEPEVT outputB = new WriterHEPEVT("fromfrominputIO3.hepevt");
        if (outputB.failed()) return 4;
        copy(c, inputB, outputB);
        inputB.close();
        outputB.close();
        return c.compareAsciiFiles("fromfrominputIO3.hepevt", "inputIO3.hepevt");
    }

    /**
     * A std::filebuf opened for writing: created empty at open, its text
     * reaching the file only when the filebuf is destroyed at the end of
     * main, as for the little text testIO5 writes (less than the buffer).
     */
    private static final class Filebuf extends ByteArrayOutputStream {
        private final Path file;

        Filebuf(Path file) throws IOException {
            this.file = file;
            Files.write(file, new byte[0]);
        }

        @Override
        public void close() {
            // closing the ostream leaves the filebuf as it is
        }

        void destroy() throws IOException {
            Files.write(file, toByteArray());
        }
    }

    /**
     * testIO5 reads "inputI05.hepmc" (a zero for the letter O), a file that
     * does not exist, through a std::filebuf that failed to open: the reader
     * sees an empty stream. Its output stays in the buffer of the filebuf
     * until the end of main, so the second pass reads an empty file and the
     * comparison compares an empty file with a missing one.
     */
    static int testIO5(TestContext c) throws IOException {
        // std::istream over a filebuf that did not open: good, and empty
        final ReaderAsciiHepMC2 inputA = new ReaderAsciiHepMC2(new CInput(InputStream.nullInputStream()));
        if (inputA.failed()) return 1;
        final Filebuf osrA = new Filebuf(c.path("frominputI05.hepmc"));
        final Filebuf osrB;
        try {
            final WriterAscii outputA = new WriterAscii(osrA, null);
            if (outputA.failed()) return 2;
            copy(c, inputA, outputA);
            inputA.close();
            outputA.close();
            final ReaderAscii inputB = new ReaderAscii(CInput.open(c.path("frominputI05.hepmc")));
            if (inputB.failed()) return 3;
            osrB = new Filebuf(c.path("fromfrominputI05.hepmc"));
            try {
                final WriterAsciiHepMC2 outputB = new WriterAsciiHepMC2(osrB, null);
                if (outputB.failed()) return 4;
                copy(c, inputB, outputB);
                inputB.close();
                outputB.close();
                return c.compareAsciiFiles("fromfrominputI05.hepmc", "inputI05.hepmc");
            } finally {
                osrB.destroy();
            }
        } finally {
            osrA.destroy();
        }
    }

    static int testIO6(TestContext c) {
        final ReaderAsciiHepMC2 inputA = new ReaderAsciiHepMC2("inputIO6.hepmc");
        if (inputA.failed()) return 1;
        final WriterAscii outputA = new WriterAscii("frominputIO6.hepmc");
        if (outputA.failed()) return 2;
        copy(c, inputA, outputA);
        inputA.close();
        outputA.close();
        final ReaderAscii inputB = new ReaderAscii("frominputIO6.hepmc");
        if (inputB.failed()) return 3;
        final WriterAscii outputB = new WriterAscii("fromfrominputIO6.hepmc");
        if (outputB.failed()) return 4;
        copy(c, inputB, outputB);
        inputB.close();
        outputB.close();
        return c.compareAsciiFiles("fromfrominputIO6.hepmc", "frominputIO6.hepmc");
    }

    static int testIO7(TestContext c) {
        final ReaderAsciiHepMC2 inputA = new ReaderAsciiHepMC2("inputIO7.hepmc");
        if (inputA.failed()) return 1;
        final WriterAscii outputA = new WriterAscii("frominputIO7.hepmc");
        if (outputA.failed()) return 2;
        int n = 0;
        while (!inputA.failed()) {
            final GenEvent evt = new GenEvent(GEV, MM);
            inputA.readEvent(evt);
            if (inputA.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            evt.setRunInfo(new GenRunInfo());
            final List<String> wNames = new ArrayList<>();
            final List<Double> wValues = new ArrayList<>();
            for (int i = 0; i < n + 2; i++) {
                wNames.add("testname" + i);
                wValues.add(1.0 + 0.1 * i);
            }
            evt.runInfo().addAttribute("testrunattribute", new IntAttribute(10000 + n));
            if (n % 2 == 0) evt.runInfo().setWeightNames(wNames);
            else evt.runInfo().setWeightNames(new ArrayList<>());
            evt.weights().clear();
            evt.weights().addAll(wValues);
            outputA.setRunInfo(null);
            outputA.writeEvent(evt);
            evt.clear();
            n++;
        }
        inputA.close();
        outputA.close();
        final ReaderAscii inputB = new ReaderAscii("frominputIO7.hepmc");
        if (inputB.failed()) return 3;
        final WriterAscii outputB = new WriterAscii("fromfrominputIO7.hepmc");
        if (outputB.failed()) return 4;
        while (!inputB.failed()) {
            final GenEvent evt = new GenEvent(GEV, MM);
            inputB.readEvent(evt);
            if (inputB.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            outputB.setRunInfo(null);
            outputB.writeEvent(evt);
            evt.clear();
        }
        inputB.close();
        outputB.close();
        return c.compareAsciiFiles("fromfrominputIO7.hepmc", "frominputIO7.hepmc");
    }

    private static void printfSpecifierG(Writer w) {
        final Map<String, String> options = new HashMap<>(w.getOptions());
        options.put("float_printf_specifier", "g");
        w.setOptions(options);
    }

    static int testIO8(TestContext c) {
        final ReaderAsciiHepMC2 inputA = new ReaderAsciiHepMC2("inputIO8.hepmc");
        if (inputA.failed()) return 1;
        final WriterAscii outputA = new WriterAscii("frominputIO8.hepmc");
        if (outputA.failed()) return 2;
        printfSpecifierG(outputA);
        copy(c, inputA, outputA);
        inputA.close();
        outputA.close();
        final ReaderAscii inputB = new ReaderAscii("frominputIO8.hepmc");
        if (inputB.failed()) return 3;
        final WriterAsciiHepMC2 outputB = new WriterAsciiHepMC2("fromfrominputIO8.hepmc");
        if (outputB.failed()) return 4;
        printfSpecifierG(outputB);
        copy(c, inputB, outputB);
        inputB.close();
        outputB.close();
        return c.compareAsciiFiles("fromfrominputIO8.hepmc", "inputIO8.hepmc");
    }

    /** The compressions the port writes (HepMC3 writes those its build links). */
    private static List<Compression> writable() {
        final List<Compression> l = new ArrayList<>();
        for (Compression w : Compression.SUPPORTED) {
            try {
                w.compressing(OutputStream.nullOutputStream());
                l.add(w);
            } catch (IOException e) {
                // read only here
            }
        }
        return l;
    }

    static int testIO9(TestContext c) {
        final ReaderAsciiHepMC2 inputA = new ReaderAsciiHepMC2("inputIO9.hepmc");
        if (inputA.failed()) return 1;
        final List<Compression> supported = writable();
        final List<Writer> writersGZ = new ArrayList<>();
        for (Compression w : supported) {
            writersGZ.add(new WriterGZ(c.path("frominputIO9.hepmc." + w.label()), WriterAsciiHepMC2::new, null, w));
            if (writersGZ.get(writersGZ.size() - 1).failed()) return 10 + writersGZ.size();
        }
        while (!inputA.failed()) {
            final GenEvent evt = new GenEvent(GEV, MM);
            inputA.readEvent(evt);
            if (inputA.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            for (Writer w : writersGZ) w.writeEvent(evt);
            evt.clear();
        }
        inputA.close();
        for (Writer w : writersGZ) w.close();
        writersGZ.clear();
        int result = 0;
        for (Compression w : supported) {
            final ReaderGZ inputB = new ReaderGZ(c.path("frominputIO9.hepmc." + w.label()), ReaderAsciiHepMC2::new);
            if (inputB.failed()) return 20;
            final WriterAsciiHepMC2 outputB = new WriterAsciiHepMC2("fromfrominputIO9" + w.label() + ".hepmc");
            if (outputB.failed()) return 4;
            copy(c, inputB, outputB);
            inputB.close();
            outputB.close();
            result += c.compareAsciiFiles("fromfrominputIO9" + w.label() + ".hepmc", "inputIO9.hepmc");
        }
        return result;
    }

    static int testIO10(TestContext c) {
        final ReaderMT inputA = new ReaderMT(c.path("inputIO10.hepmc"), ReaderAsciiHepMC2::new, 3);
        final List<GenEvent> inputAEvents = new ArrayList<>();
        if (inputA.failed()) return 1;
        while (!inputA.failed()) {
            final GenEvent evt = new GenEvent(GEV, MM);
            inputA.readEvent(evt);
            if (inputA.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            evt.setRunInfo(null);
            inputAEvents.add(new GenEvent(evt));
        }
        inputA.close();
        final WriterAscii outputA = new WriterAscii("frominputIO10.hepmc");
        if (outputA.failed()) return 2;
        inputAEvents.sort(Comparator.comparingInt(GenEvent::eventNumber));
        for (GenEvent e : inputAEvents) outputA.writeEvent(e);
        outputA.close();
        inputAEvents.clear();
        final ReaderMT inputB = new ReaderMT(c.path("frominputIO10.hepmc"), ReaderAscii::new, 2);
        final List<GenEvent> inputBEvents = new ArrayList<>();
        if (inputB.failed()) return 3;
        while (!inputB.failed()) {
            final GenEvent evt = new GenEvent(GEV, MM);
            inputB.readEvent(evt);
            if (inputB.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            inputBEvents.add(new GenEvent(evt));
        }
        inputB.close();
        final WriterAsciiHepMC2 outputB = new WriterAsciiHepMC2("fromfrominputIO10.hepmc");
        if (outputB.failed()) return 4;
        inputBEvents.sort(Comparator.comparingInt(GenEvent::eventNumber));
        for (GenEvent e : inputBEvents) outputB.writeEvent(e);
        outputB.close();
        inputBEvents.clear();
        return c.compareAsciiFiles("fromfrominputIO10.hepmc", "inputIO10.hepmc");
    }

    private static int lhefToAscii(TestContext c, String in, String out) {
        final ReaderLHEF inputA = new ReaderLHEF(in);
        if (inputA.failed()) return 1;
        final WriterAscii outputA = new WriterAscii(out);
        if (outputA.failed()) return 2;
        while (!inputA.failed()) {
            final GenEvent evt = new GenEvent(GEV, MM);
            final boolean readRes = inputA.readEvent(evt);
            if (!readRes && !inputA.failed()) {
                c.printf("Error reading event from LHE file. Exit.\n");
                return 1;
            }
            if (inputA.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            if (readRes) outputA.writeEvent(evt);
            evt.clear();
        }
        inputA.close();
        outputA.close();
        return 0;
    }

    static int testIO11(TestContext c) {
        return lhefToAscii(c, "inputIO11.lhe", "frominputIO11.hepmc");
    }

    static int testIO13(TestContext c) {
        return lhefToAscii(c, "inputIO13.lhe", "frominputIO13.hepmc");
    }

    private static final String RAWEVENT = """

        HepMC::Version 3.02.08
        HepMC::IO_GenEvent-START_EVENT_LISTING
        E 0 0 9.188128e+01 1.298440e-01 7.818181e-03 221 0 7 10001 10004 0 1 1.0000000000000000e+00
        N 1 "0"
        U GEV MM
        C 2.6442255100000002e+03 2.6442255100000002e+03
        F 11 -11 9.97420767e-01 9.99999975e-01 9.18812775e+01 1.56824725e+01 2.82148362e+06 0 0
        V -1 0 0 0 0 0 1 2 0
        P 10001 11 0.0000000000000000e+00 0.0000000000000000e+00 4.5999999997161737e+01 4.6000000000000007e+01 5.1099999999999995e-04 4 0 0 -1 0
        P 10002 11 0.0000000000000000e+00 0.0000000000000000e+00 4.5881355265109363e+01 4.5881355265109363e+01 0.0000000000000000e+00 61 0 0 -3 0
        P 10003 22 0.0000000000000000e+00 0.0000000000000000e+00 1.1864473489064410e-01 1.1864473489064410e-01 0.0000000000000000e+00 1 0 0 0 0
        V -2 0 0 0 0 0 1 2 0
        P 10004 -11 0.0000000000000000e+00 0.0000000000000000e+00 -4.5999999997161737e+01 4.6000000000000007e+01 5.1099999999999995e-04 4 0 0 -2 0
        P 10005 -11 0.0000000000000000e+00 0.0000000000000000e+00 -4.5999998855671230e+01 4.5999998855671230e+01 0.0000000000000000e+00 61 0 0 -4 0
        P 10006 22 0.0000000000000000e+00 0.0000000000000000e+00 -1.1443287704082650e-06 1.1443287704082650e-06 0.0000000000000000e+00 1 0 0 0 0
        V -3 0 0 0 0 0 0 1 0
        P 10007 11 0.0000000000000000e+00 0.0000000000000000e+00 4.5881355265109363e+01 4.5881355265109363e+01 0.0000000000000000e+00 21 0 0 -5 0
        V -4 0 0 0 0 0 0 1 0
        P 10008 -11 0.0000000000000000e+00 0.0000000000000000e+00 -4.5999998855671230e+01 4.5999998855671230e+01 0.0000000000000000e+00 21 0 0 -5 0
        V -5 0 0 0 0 0 0 1 0
        P 10009 23 0.0000000000000000e+00 0.0000000000000000e+00 -1.1864359056187369e-01 9.1881354120780586e+01 9.1881277520323493e+01 22 0 0 -6 0
        V -6 0 0 0 0 0 0 1 0
        P 10010 23 0.0000000000000000e+00 0.0000000000000000e+00 -1.1864359056187369e-01 9.1881354120780586e+01 9.1881277520323493e+01 62 0 0 -7 0
        V -7 0 0 0 0 0 0 2 0
        P 10011 15 -2.3389081325813049e+01 -2.6534544925397689e+01 -2.9321164328115071e+01 4.5978461985283630e+01 1.7768200000000001e+00 1 0 0 0 0
        P 10012 -15 2.3389081325813049e+01 2.6534544925397689e+01 2.9202520737553190e+01 4.5902892135496963e+01 1.7768200000000001e+00 1 0 0 0 0
        HepMC::IO_GenEvent-END_EVENT_LISTING
        """;

    static int testIO12(TestContext c) {
        final ReaderAsciiHepMC2 inputA = new ReaderAsciiHepMC2(
            new ByteArrayInputStream(RAWEVENT.getBytes(StandardCharsets.ISO_8859_1)));
        if (inputA.failed()) return 1;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final WriterAsciiHepMC2 outputA = new WriterAsciiHepMC2(output, null);
        if (outputA.failed()) return 2;
        copy(c, inputA, outputA);
        inputA.close();
        outputA.close();
        outputA.close();
        outputA.close();
        final String text = output.toString(StandardCharsets.ISO_8859_1);
        c.cout.put(text);
        return c.compareAsciiStreams(RAWEVENT, text);
    }

    /* ---- malformed and unusual input --------------------------------------------------- */

    private static List<GenParticle> getPartsWith(GenEvent ev, int status, int pid) {
        final List<GenParticle> out = new ArrayList<>();
        for (GenParticle p : ev.particles()) {
            boolean sel = status == 0 || p.status() == status;
            sel &= pid == 0 || p.pid() == pid;
            if (sel) out.add(p);
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("failed to find any parts with status = " + status + ", and pid = " + pid + " in event.");
        }
        return out;
    }

    /** The checks testIO30 makes on each of its two events; 1 (and a message) at the first failing. */
    private static int checkIO30Event(TestContext c, GenEvent ev) {
        final COStream cout = c.cout;
        if (ev.particles().size() != 8) {
            cout.put("-- Expected to find 8 particles in the event, but found ").put(ev.particles().size()).endl();
            return 1;
        }
        if (ev.vertices().size() != 2) {
            cout.put("-- Expected to find 2 vertices in the event, but found ").put(ev.particles().size()).endl();
            return 1;
        }
        final List<GenParticle> s4p14 = getPartsWith(ev, 4, 14);
        if (s4p14.size() != 1) {
            cout.put("-- Expected to find 1 particle with status 4 and pid 14, but found ").put(s4p14.size()).endl();
            return 1;
        }
        if (s4p14.get(0).momentum().x() != 1.23) {
            cout.put("-- Expected to find x momentum of particle with status 4 and pid 14 = 1.23, but found ")
                .put(s4p14.get(0).momentum().x()).endl();
            return 1;
        }
        final List<GenParticle> s21p2212 = getPartsWith(ev, 21, 2212);
        if (s21p2212.size() != 1) {
            cout.put("-- Expected to find 1 particle with status 21 and pid 2212, but found ").put(s21p2212.size()).endl();
            return 1;
        }
        if (s21p2212.get(0).momentum().z() != 1.23) {
            cout.put("-- Expected to find z momentum of particle with status 21 and pid 2212 = 1.23, but found ")
                .put(s21p2212.get(0).momentum().z()).endl();
            return 1;
        }
        final List<GenParticle> s1p211 = getPartsWith(ev, 1, 211);
        if (s1p211.size() != 1) {
            cout.put("-- Expected to find 1 particle with status 1 and pid 211, but found ").put(s1p211.size()).endl();
            return 1;
        }
        if (s1p211.get(0).generatedMass() != 1.23) {
            cout.put("-- Expected to find the gen mass of particle with status 1 and pid 211 = 1.23, but found ")
                .put(s21p2212.get(0).generatedMass()).endl();
            return 1;
        }
        return 0;
    }

    private static void verbose() {
        Setup.setPrintErrors(true);
        Setup.setErrorsLevel(999);
        Setup.setPrintWarnings(true);
        Setup.setWarningsLevel(999);
        Setup.setDebugLevel(999);
    }

    static int testIO30(TestContext c) {
        verbose();
        final Reader rdr = ReaderFactory.deduceReader("inputIO30.hepmc");
        if (rdr == null) return 1;
        final GenEvent ev = new GenEvent();
        final COStream cout = c.cout;
        cout.put("reading event with normal whitespace...").endl();
        rdr.readEvent(ev);
        final COStream ev1List = new COStream();
        Print.listing(ev1List, ev, 2);
        Print.listing(cout, ev, 2);
        if (rdr.failed()) {
            cout.put("-- Failed reading event").endl();
            return 1;
        }
        final String[] names = {"Attr1", "Attr2", "Attr3"};
        final int[] expected = {123, 321, 456};
        for (int k = 0; k < 3; k++) {
            final IntAttribute a = rdr.runInfo().attribute(names[k], IntAttribute.class);
            if (a.value() != expected[k]) {
                cout.put("-- Failed parsing attribute ").put(names[k]).put(" correctly: expected ").put(expected[k])
                    .put(" but found ").put(a.value()).endl();
                return 1;
            }
        }
        if (checkIO30Event(c, ev) != 0) return 1;
        cout.put("reading event with extra whitespace...").endl();
        rdr.readEvent(ev);
        final COStream ev2List = new COStream();
        Print.listing(ev2List, ev, 2);
        Print.listing(cout, ev, 2);
        if (rdr.failed()) {
            cout.put("-- Failed reading event").endl();
            return 1;
        }
        if (checkIO30Event(c, ev) != 0) return 1;
        if (!ev1List.str().equals(ev2List.str())) {
            cout.put("event listings didn't match but were excepted to:\n").put(ev1List.str()).put("\n\n").put(ev2List.str()).endl();
            return 1;
        }
        if (rdr.readEvent(ev)) {
            cout.put("Expected an attempted read of the 3rd event in inputIO30.hepmc to fail due to missed whitespace.").endl();
            return 1;
        }
        return 0;
    }

    static int testIO31(TestContext c) {
        verbose();
        final Reader rdr = ReaderFactory.deduceReader("inputIO31.hepmc");
        if (rdr == null) return 1;
        final GenEvent ev = new GenEvent();
        int ctr = 0;
        while (!rdr.failed()) {
            rdr.readEvent(ev);
            if (rdr.failed()) break;
            ctr++;
        }
        if (ctr != 5) {
            c.cout.put("[ERROR]: Expected to read 5 events, but read: ").put(ctr).endl();
            return 1;
        }
        return 0;
    }

    private static int checkEvents(TestContext c, ReaderAscii reader, List<FourVector> positions, WriterAscii writer) {
        final GenEvent event = new GenEvent();
        int number = 0;
        for (FourVector position : positions) {
            ++number;
            if (!reader.readEvent(event) || reader.failed() || event.eventNumber() != number
                || event.particles().size() != 2 || event.vertices().size() != 1
                || !event.eventPos().equals(position) || !event.vertices().get(0).position().equals(position)) {
                c.cerr.put("Wrong event or position at event ").put(number).put('\n');
                return 1;
            }
            if (writer != null) writer.writeEvent(event);
        }
        reader.readEvent(event);
        if (!reader.failed()) {
            c.cerr.put("Unexpected extra event\n");
            return 1;
        }
        return 0;
    }

    private static int runIO32(TestContext c, String scenario) {
        final COStream cerr = c.cerr;
        if (scenario.equals("truncated") || scenario.equals("invalid") || scenario.equals("missing_count")) {
            final String filename = "inputIO32_" + scenario + ".hepmc";
            final ReaderAscii reader = new ReaderAscii(filename);
            if (reader.failed()) {
                cerr.put("Cannot open ").put(filename).put('\n');
                return 1;
            }
            final GenEvent event = new GenEvent();
            cerr.put("Expected: read_event=false, failed=true, particles=0, vertices=0.\n")
                .put("Reader error messages below are expected for this malformed input.\n");
            final boolean readOk = reader.readEvent(event);
            cerr.put("Observed: read_event=").put(readOk ? "true" : "false").put(", failed=").put(reader.failed() ? "true" : "false")
                .put(", particles=").put(event.particles().size()).put(", vertices=").put(event.vertices().size()).put(".\n");
            if (readOk || !reader.failed() || !event.particles().isEmpty() || !event.vertices().isEmpty()) {
                cerr.put("Expected a failed read and an empty event for: ").put(filename).put('\n');
                return 1;
            }
            return 0;
        }
        if (scenario.equals("reset")) {
            cerr.put("Expected: the event without @ resets the previous nonzero position to zero.\n");
            final ReaderAscii reader = new ReaderAscii("inputIO32_reset.hepmc");
            return checkEvents(c, reader, List.of(new FourVector(1, 2, 3, 4), new FourVector()), null);
        }
        if (!scenario.equals("positions") && !scenario.equals("roundtrip")) {
            cerr.put("Unknown test scenario: ").put(scenario).put('\n');
            return 1;
        }
        final List<FourVector> positions = List.of(new FourVector(1, 2, 3, 4), new FourVector(-0.5, 0.25, 12, 7),
            new FourVector(), new FourVector(5, 6, 7, 8));
        final ReaderAscii reader = new ReaderAscii("inputIO32.hepmc");
        if (scenario.equals("positions")) {
            cerr.put("Expected: all event and inherited vertex positions match the fixture values.\n");
            return checkEvents(c, reader, positions, null);
        }
        cerr.put("Expected: writing and rereading preserves all event and inherited vertex positions.\n");
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final WriterAscii writer = new WriterAscii(output, null);
        if (checkEvents(c, reader, positions, writer) != 0) return 1;
        writer.close();
        final ReaderAscii roundtrip = new ReaderAscii(new ByteArrayInputStream(output.toByteArray()));
        return checkEvents(c, roundtrip, positions, null);
    }

    static int testIO32(TestContext c) {
        final String[] argv = c.args();
        if (argv.length != 1) {
            c.cerr.put("FAIL: expected one test scenario\n");
            return 1;
        }
        final int result = runIO32(c, argv[0]);
        c.cerr.put(result == 0 ? "PASS: " : "FAIL: ").put(argv[0]).put('\n');
        return result;
    }

    /* ---- events changed, skipped, read by several ------------------------------------ */

    static int testDelete(TestContext c) {
        final ReaderAsciiHepMC2 inputA = new ReaderAsciiHepMC2("inputDelete.hepmc");
        if (inputA.failed()) return 1;
        final List<GenEvent> evts = new ArrayList<>();
        while (!inputA.failed()) {
            final GenEvent evt = new GenEvent(GEV, MM);
            inputA.readEvent(evt);
            if (inputA.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            evts.add(new GenEvent(evt));
        }
        inputA.close();
        int i = 0;
        int j = 0;
        while (i == j) {
            i = c.rand() % evts.size();
            j = c.rand() % evts.size();
        }
        evts.get(i).removeParticles(new ArrayList<>(evts.get(j).particles()));
        for (GenParticle p : new ArrayList<>(evts.get(i).particles())) evts.get(j).removeParticle(p);
        for (GenParticle p : evts.get(i).particles()) {
            for (var v : evts.get(j).vertices()) {
                v.removeParticleIn(p);
                v.removeParticleOut(p);
            }
        }
        final WriterAscii outputA = new WriterAscii("frominputDelete.hepmc");
        if (outputA.failed()) return 2;
        for (GenEvent e : evts) outputA.writeEvent(e);
        evts.clear();
        outputA.close();
        final ReaderAscii inputB = new ReaderAscii("frominputDelete.hepmc");
        if (inputB.failed()) return 3;
        final WriterAsciiHepMC2 outputB = new WriterAsciiHepMC2("fromfrominputDelete.hepmc");
        if (outputB.failed()) return 4;
        copy(c, inputB, outputB);
        inputB.close();
        outputB.close();
        return c.compareAsciiFiles("fromfrominputDelete.hepmc", "inputDelete.hepmc");
    }

    static int testSkip1(TestContext c) {
        final long tA = System.nanoTime();
        final ReaderAsciiHepMC2 inputA = new ReaderAsciiHepMC2("inputSkip1.hepmc");
        if (inputA.failed()) return 1;
        final WriterAscii outputA = new WriterAscii("frominputSkip1A.hepmc");
        if (outputA.failed()) return 2;
        int i = 0;
        while (!inputA.failed()) {
            final GenEvent evt = new GenEvent(GEV, MM);
            inputA.readEvent(evt);
            if (inputA.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            if (i % 10 == 0) outputA.writeEvent(evt);
            i++;
            evt.clear();
        }
        inputA.close();
        outputA.close();
        c.printf("Time taken A: %.2fms\n", (System.nanoTime() - tA) / 1e6);
        final long tB = System.nanoTime();
        final ReaderAsciiHepMC2 inputB = new ReaderAsciiHepMC2("inputSkip1.hepmc");
        if (inputB.failed()) return 1;
        final WriterAscii outputB = new WriterAscii("frominputSkip1B.hepmc");
        if (outputB.failed()) return 2;
        while (!inputB.failed()) {
            final GenEvent evt = new GenEvent(GEV, MM);
            inputB.readEvent(evt);
            if (inputB.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            outputB.writeEvent(evt);
            evt.clear();
            inputB.skip(9);
        }
        inputB.close();
        outputB.close();
        c.printf("Time taken B: %.2fms\n", (System.nanoTime() - tB) / 1e6);
        return c.compareAsciiFiles("frominputSkip1A.hepmc", "frominputSkip1B.hepmc");
    }

    /* ---- the reader factory ----------------------------------------------------------- */

    static int testReaderFactory1(TestContext c) {
        final Reader inputA = ReaderFactory.deduceReader("inputReaderFactory1.hepmc");
        if (inputA.failed()) return 1;
        final WriterAscii outputA = new WriterAscii("frominputReaderFactory1.hepmc");
        if (outputA.failed()) return 2;
        copy(c, inputA, outputA);
        inputA.close();
        outputA.close();
        final Reader inputB = ReaderFactory.deduceReader("frominputReaderFactory1.hepmc");
        if (inputB.failed()) return 3;
        final WriterAsciiHepMC2 outputB = new WriterAsciiHepMC2("fromfrominputReaderFactory1.hepmc");
        if (outputB.failed()) return 4;
        copy(c, inputB, outputB);
        inputB.close();
        outputB.close();
        return c.compareAsciiFiles("fromfrominputReaderFactory1.hepmc", "inputReaderFactory1.hepmc");
    }

    /**
     * The Unix code of testReaderFactory3: a writer thread and a reader thread
     * joined by a FIFO, here a pipe (the reader learns the format from the
     * first bytes, as deduce_reader does on the FIFO).
     */
    static int testReaderFactory3(TestContext c) throws Exception {
        final PipedInputStream fifoIn = new PipedInputStream(1 << 16);
        final PipedOutputStream fifoOut = new PipedOutputStream(fifoIn);
        c.printf("FIFO created.\n");
        final int[] results = {0, 0};
        final Thread readt = c.thread(() -> {
            results[0] = 0;
            final Reader inputB = ReaderFactory.deduceReader(fifoIn);
            if (inputB == null || inputB.failed()) {
                results[0] = 1;
                c.printf("Error in reader_function.\n");
                return;
            }
            final WriterAsciiHepMC2 outputB = new WriterAsciiHepMC2("fromfrominputReaderFactory3.hepmc");
            if (outputB.failed()) {
                results[0] = 2;
                c.printf("Error in reader_function.\n");
                return;
            }
            copy(c, inputB, outputB);
            inputB.close();
            outputB.close();
        });
        final Thread writet = c.thread(() -> {
            results[1] = 0;
            final ReaderAsciiHepMC2 inputA = new ReaderAsciiHepMC2("inputReaderFactory1.hepmc");
            if (inputA.failed()) {
                results[1] = 1;
                c.printf("Error in writer_function.\n");
                return;
            }
            final WriterAscii outputA = new WriterAscii(fifoOut, null);
            if (outputA.failed()) {
                results[1] = 2;
                c.printf("Error in writer_function.\n");
                return;
            }
            copy(c, inputA, outputA);
            inputA.close();
            outputA.close();
            try {
                fifoOut.close();
            } catch (IOException ignored) {
                // the reader sees the end either way
            }
        });
        readt.start();
        writet.start();
        readt.join();
        writet.join();
        if (results[0] != 0 || results[1] != 0) {
            c.printf("Something went wrong during reading/writing %i %i\n", results[0], results[1]);
            return 10;
        }
        c.printf("FIFO deleted.\n");
        return c.compareAsciiFiles("fromfrominputReaderFactory3.hepmc", "inputReaderFactory1.hepmc");
    }

    static int testReaderFactory4(TestContext c) {
        final int[] results = {0, 0};
        {
            final ReaderAsciiHepMC2 inputA = new ReaderAsciiHepMC2("inputReaderFactory1.hepmc");
            if (inputA.failed()) {
                results[1] = 1;
                c.printf("Error in writer_function.\n");
            } else {
                final WriterGZ outputA = new WriterGZ(c.path("frominputReaderFactory4.pb.gz"),
                    (out, run) -> new Writerprotobuf(out.asStream(), run), null, Compression.Z);
                if (outputA.failed()) {
                    results[1] = 2;
                    c.printf("Error in writer_function.\n");
                } else {
                    copy(c, inputA, outputA);
                    inputA.close();
                    outputA.close();
                }
            }
        }
        {
            final Reader inputB = ReaderFactory.deduceReader("frominputReaderFactory4.pb.gz");
            if (inputB == null || inputB.failed()) {
                results[0] = 1;
                c.printf("Error in reader_function.\n");
            } else {
                final WriterAsciiHepMC2 outputB = new WriterAsciiHepMC2("fromfrominputReaderFactory4.hepmc");
                if (outputB.failed()) {
                    results[0] = 2;
                    c.printf("Error in reader_function.\n");
                } else {
                    copy(c, inputB, outputB);
                    inputB.close();
                    outputB.close();
                }
            }
        }
        if (results[0] != 0 || results[1] != 0) {
            c.printf("Something went wrong during reading/writing %i %i\n", results[0], results[1]);
            return 10;
        }
        return c.compareAsciiFiles("fromfrominputReaderFactory4.hepmc", "inputReaderFactory1.hepmc");
    }

    /** The Unix code of testReaderFactory5: the events piped to std::cin. */
    static int testReaderFactory5(TestContext c) {
        final Reader inputFile = ReaderFactory.deduceReader(c.stdin());
        if (inputFile == null) return 1;
        int n = 0;
        while (!inputFile.failed()) {
            final GenEvent evt = new GenEvent(GEV, MM);
            inputFile.readEvent(evt);
            if (inputFile.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            n++;
            evt.clear();
        }
        inputFile.close();
        if (n != 10) {
            c.printf("Expected %i events, but obtained %i\n", 10, n);
            return 1;
        }
        return 0;
    }

    static int testSingleVertexHepMC2(TestContext c) {
        Setup.setDebugLevel(60);
        final ReaderAsciiHepMC2 inputA = new ReaderAsciiHepMC2("inputSingleVertexHepMC2.hepmc");
        if (inputA.failed()) return 1;
        final List<GenEvent> evts = new ArrayList<>();
        while (!inputA.failed()) {
            final GenEvent evt = new GenEvent();
            inputA.readEvent(evt);
            if (inputA.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            evts.add(evt);
        }
        inputA.close();
        if (evts.get(0).particles().size() == 120 && evts.get(0).vertices().size() == 1) return 0;
        return 1;
    }

    static int testSherpa140Crash(TestContext c) {
        final ReaderAsciiHepMC2 inputA = new ReaderAsciiHepMC2("inputSherpa140Crash.hepmc");
        if (inputA.failed()) return 1;
        final List<GenEvent> evts = new ArrayList<>();
        while (!inputA.failed()) {
            final GenEvent evt = new GenEvent();
            inputA.readEvent(evt);
            if (inputA.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            evts.add(evt);
        }
        inputA.close();
        return 0;
    }

    static int testThreads1(TestContext c) throws InterruptedException {
        final int copies = 4;
        final int maxThreads = 3;
        final ReaderAsciiHepMC2 inputA = new ReaderAsciiHepMC2("inputThreads1.hepmc");
        if (inputA.failed()) return 1;
        final List<GenEvent> evts = new ArrayList<>();
        while (!inputA.failed()) {
            final GenEvent evt = new GenEvent(GEV, MM);
            inputA.readEvent(evt);
            if (inputA.failed()) {
                c.printf("End of file reached. Exit.\n");
                break;
            }
            evts.add(new GenEvent(evt));
        }
        inputA.close();
        final List<List<GenEvent>> thrEvts = new ArrayList<>();
        for (int i = 0; i < copies; i++) {
            final List<GenEvent> copy = new ArrayList<>();
            for (GenEvent e : evts) copy.add(new GenEvent(e));
            thrEvts.add(copy);
        }
        for (int i = 0; i < copies; i++) {
            for (int e = 0; e < evts.size(); e++) {
                final GenEvent ev = thrEvts.get(i).get(e);
                final int j1 = -ev.vertices().size();
                final int j2 = ev.particles().size();
                final int d = (j2 - j1) / maxThreads;
                final List<Integer> ids = new ArrayList<>();
                ids.add(0);
                for (int j = j1; j < j2; j += d) ids.add(j);
                final List<Thread> threads = new ArrayList<>();
                for (int id : ids) {
                    threads.add(c.thread(() -> {
                        final GenCrossSection xs = ev.attribute("GenCrossSection", GenCrossSection.class, 0);
                        c.printf("XS in event  %i  is %f, id=%i\n", ev.eventNumber(), xs.xsec(), id);
                    }));
                }
                for (Thread th : threads) th.start();
                for (Thread th : threads) th.join();
            }
        }
        for (int k = 0; k < copies; k++) {
            final WriterAscii outputA = new WriterAscii("outputThreads1_" + k + ".hepmc");
            if (outputA.failed()) return 2;
            for (GenEvent e : thrEvts.get(k)) outputA.writeEvent(e);
            thrEvts.get(k).clear();
            outputA.close();
            if (k > 0) {
                final int result = c.compareAsciiFiles("outputThreads1_" + (k - 1) + ".hepmc", "outputThreads1_" + k + ".hepmc");
                if (result != 0) return result;
            }
        }
        return 0;
    }
}
