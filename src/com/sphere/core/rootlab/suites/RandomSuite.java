package com.sphere.core.rootlab.suites;

import com.sphere.core.rootlab.bench.LabContext;
import com.sphere.core.rootlab.bench.LabFailure;
import com.sphere.core.rootlab.bench.LabSuite;
import com.sphere.core.rootlab.bench.LabTest;
import com.sphere.core.rootlab.random.TRandom;
import com.sphere.core.rootlab.random.TRandom2;
import com.sphere.core.rootlab.random.TRandom3;

import java.util.ArrayList;
import java.util.List;

/**
 * ROOT's generators, as every test of ROOT's test/ directory draws its numbers
 * from them: TRandom2 (stressHistogram) and TRandom3 (gRandom, so hsimple,
 * stress, MainEvent). The reference values were printed by the code of
 * math/mathcore/src/TRandom*.cxx compiled with g++; they are compared bit for
 * bit, uniform numbers and the distributions built on them alike. TRandom3 is
 * also held to the C++ standard's own check of MT19937.
 */
public final class RandomSuite implements LabSuite {

    /** {generator seed, what, index, IEEE 754 bits of the value(s)}, from TRandom*.cxx compiled with g++. */
    private static final String[] REFERENCE = {
        "R2_8652 rndm 0 3fea054ce1800000", "R2_8652 rndm 1 3fee980b83a00000",
        "R2_8652 rndm 2 3fc5774d72000000", "R2_8652 rndm 3 3fb5de246b000000",
        "R2_8652 rndm 4 3fd87bc9b7800000", "R2_8652 rndm 5 3fd82637a7000000",
        "R2_8652 rndm 6 3feaf8f116a00000", "R2_8652 rndm 7 3fb914c512000000",
        "R2_8652 uniform 0 4014dd51d3573333", "R2_8652 uniform 1 4012ccd4d4ca6666",
        "R2_8652 uniform 2 3ff189be1d300000", "R2_8652 uniform 3 3ff8e0a58a266666",
        "R2_8652 uniform 4 40099c488be33333", "R2_8652 uniform 5 3ff48f6de3766666",
        "R2_8652 uniform 6 3fee4250c30ccccd", "R2_8652 uniform 7 400ad6998ef7ffff",
        "R2_8652 gaus 0 4001941f86c00000", "R2_8652 gaus 1 bff36375f5c2d860",
        "R2_8652 gaus 2 3ff815acd4c00000", "R2_8652 gaus 3 bff1d22113400000",
        "R2_8652 gaus 4 3fd839ff786b74f0", "R2_8652 gaus 5 bfe8c8a889800000",
        "R2_8652 gaus 6 3fddd7af10000000", "R2_8652 gaus 7 4006435234ad6ee2",
        "R2_8652 rannor 0 bfaa9f7576a48245 bfd2c34c3f47d28c", "R2_8652 rannor 1 bfb834a09014b677 bff57ce64b0b5556",
        "R2_8652 rannor 2 bff8bf776eb9bf3f bfddf32b3f145edd", "R2_8652 rannor 3 3f9bfe2291a76320 bfb0faa2f2312f88",
        "R2_8652 exp 0 3ff36dba600078f2", "R2_8652 exp 1 4008691bbdfcef5d",
        "R2_8652 exp 2 3fcb9979a411be47", "R2_8652 exp 3 3ff998d41222c786",
        "R2_8652 poisson 0 4008000000000000", "R2_8652 poisson 1 4014000000000000",
        "R2_8652 poisson 2 4018000000000000", "R2_8652 poisson 3 4020000000000000",
        "R2_8652 million 0 3fd0db592d000000",
        "R3_4357 rndm 0 3feffde268800000", "R3_4357 rndm 1 3fc4da3b15800000",
        "R3_4357 rndm 2 3fd21668fdc00000", "R3_4357 rndm 3 3fee4f78a4c00000",
        "R3_4357 rndm 4 3fcda6ebed800000", "R3_4357 rndm 5 3fdf09cec5400000",
        "R3_4357 rndm 6 3feea3a6b6e00000", "R3_4357 rndm 7 3fe7d15970600000",
        "R3_4357 uniform 0 400b12d7e247ffff", "R3_4357 uniform 1 4011371314000000",
        "R3_4357 uniform 2 4011953d40999999", "R3_4357 uniform 3 400f7015750e6666",
        "R3_4357 uniform 4 4002d0c2479ccccd", "R3_4357 uniform 5 401266a8f32c0000",
        "R3_4357 uniform 6 400a52ed53ee6666", "R3_4357 uniform 7 3fface92fb1ccccc",
        "R3_4357 gaus 0 c0008dd613c00000", "R3_4357 gaus 1 3fd693a661000000",
        "R3_4357 gaus 2 bff6054466b8be7c", "R3_4357 gaus 3 bff46cef4d000000",
        "R3_4357 gaus 4 40084741a1917726", "R3_4357 gaus 5 3f9c9c8bb95d3a00",
        "R3_4357 gaus 6 40054689d6c3f49e", "R3_4357 gaus 7 3fe78b1c0c0127c0",
        "R3_4357 rannor 0 bfdc12819850d2ba bff185f15f218c52", "R3_4357 rannor 1 3ffc44e1822cc776 bfe103e28fa63d0d",
        "R3_4357 rannor 2 3fe981e09300fafe 3ffe901ee9c88d81", "R3_4357 rannor 3 bfed727d360af63e bfc256aa9641877a",
        "R3_4357 exp 0 3fecd9934941cf54", "R3_4357 exp 1 3fe591f425733def",
        "R3_4357 exp 2 4012746428f23f0d", "R3_4357 exp 3 3fe6e4ec4dbafd74",
        "R3_4357 poisson 0 3ff0000000000000", "R3_4357 poisson 1 4000000000000000",
        "R3_4357 poisson 2 3ff0000000000000", "R3_4357 poisson 3 4000000000000000",
        "R3_4357 million 0 3fb6abe8e1000000",
    };

    @Override
    public String id() {
        return "random";
    }

    @Override
    public String title() {
        return "Random number generators of ROOT (TRandom2, TRandom3)";
    }

    @Override
    public String origin() {
        return "root/math/mathcore/src/TRandom.cxx, TRandom2.cxx, TRandom3.cxx (values from the C++ compiled with g++)";
    }

    @Override
    public String description() {
        return "The generators the ROOT tests draw from, compared bit for bit with ROOT's C++: uniform, "
             + "Gaus, Rannor, Exp and Poisson, and a million numbers later.";
    }

    @Override
    public List<LabTest> tests() {
        return List.of(
            new LabTest("r2", "TRandom2 (Tausworthe), seed 8652, bit for bit", ctx -> sequence(ctx, "R2_8652", new TRandom2(8652))),
            new LabTest("r3", "TRandom3 (Mersenne Twister), gRandom's seed 4357", ctx -> sequence(ctx, "R3_4357", new TRandom3(4357))),
            new LabTest("mt", "TRandom3 against the C++ standard's MT19937 check", RandomSuite::standard));
    }

    private static String bits(double v) {
        return String.format("%016x", Double.doubleToRawLongBits(v));
    }

    private static void sequence(LabContext ctx, String tag, TRandom r) throws Exception {
        final List<String> wrong = new ArrayList<>();
        int compared = 0;
        // the order of the calls is the order the C++ made them in
        final String[] kinds = {"rndm", "uniform", "gaus", "rannor", "exp", "poisson", "million"};
        final int[] counts = {8, 8, 8, 4, 4, 4, 1};
        for (int k = 0; k < kinds.length; k++) {
            for (int i = 0; i < counts[k]; i++) {
                final String got = switch (kinds[k]) {
                    case "rndm" -> bits(r.rndm());
                    case "uniform" -> bits(r.uniform(0.9, 5.5));
                    case "gaus" -> bits(r.gaus(1.5, 2.0));
                    case "rannor" -> {
                        final double[] ab = r.rannor();
                        yield bits(ab[0]) + " " + bits(ab[1]);
                    }
                    case "exp" -> bits(r.exp(2.0));
                    case "poisson" -> bits(r.poissonD(3.5));
                    default -> {
                        double last = 0;
                        for (int n = 0; n < 1_000_000; n++) {
                            last = r.rndm();
                        }
                        yield bits(last);
                    }
                };
                final String key = tag + " " + kinds[k] + " " + i + " ";
                String want = null;
                for (String ref : REFERENCE) {
                    if (ref.startsWith(key)) {
                        want = ref.substring(key.length());
                    }
                }
                compared++;
                if (want == null || !want.equals(got)) {
                    wrong.add(kinds[k] + " " + i + ": " + got + ", C++ gives " + want);
                }
            }
        }
        ctx.detail(compared + " values compared with the C++ (IEEE 754 bits)");
        if (!wrong.isEmpty()) {
            // Gaus, Rannor and Exp go through log, sin and cos: a last-bit
            // difference there is the C library's, the uniform numbers must agree.
            throw new LabFailure(wrong.size() + " value(s) differ from ROOT's", wrong);
        }
    }

    /** [rand.predef] of the C++ standard: the 10000th output of mt19937 seeded with 5489 is 4123659995. */
    private static void standard(LabContext ctx) throws Exception {
        final TRandom3 r = new TRandom3(5489);
        long v = 0;
        for (int i = 0; i < 10000; i++) {
            v = r.next32();
        }
        ctx.detail("10000th output: " + v);
        if (v != 4123659995L) {
            throw new LabFailure("the 10000th output of MT19937 (seed 5489) is " + v + ", the standard requires 4123659995");
        }
    }
}
