package com.sphere.core.bridge;

import com.sphere.core.ml.NeuralGraph;
import com.sphere.utils.SettingsManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The runtimes that have neither Python nor PyTorch, held to PyTorch all the same.
 *
 * sphere_xf.fuzz writes each operation's cases as a graph with no inputs, PyTorch's answers among its weights: its
 * outputs come in pairs, the runtime's value and what it should be. Replaying them takes nothing but the runtime, here
 * and in C++ (compiled with the machine's g++ against sphere_graph.hpp), by the rule sphere_xf uses for the others:
 * NaN where NaN, the same infinities, the rest within 64 units in the last place.
 */
public final class BridgeConformance {

    private static final double EPS = Math.ulp(1.0), TINY = Double.MIN_NORMAL;

    private BridgeConformance() {
    }

    /** What a runtime made of the cases; note says why it could not, if files is 0. */
    public record Result(String runtime, int files, long compared, long rounding, long flushed, List<String> defects, String note) {
        public boolean ok() {
            return files > 0 && defects.isEmpty();
        }

        public String verdict() {
            if (files == 0) {
                return runtime + ": not run (" + note + ")";
            }
            return String.format(Locale.ROOT, "%s: %s, %d operations, %d outputs against PyTorch's (%d within rounding, %d subnormals)", runtime,
                defects.isEmpty() ? "conformant" : defects.size() + " DIFFERENCES", files, compared, rounding, flushed);
        }
    }

    /** 0 the same, 1 a difference of subnormals, 2 of rounding, 3 a wrong value: sphere_xf._difference's rule. allowed: a sum's rounding. */
    private static int classify(double want, double got, double allowed) {
        if ((Double.isNaN(want) && Double.isNaN(got)) || want == got) {
            return 0;
        }
        if (Double.isNaN(want) || Double.isNaN(got) || Double.isInfinite(want) || Double.isInfinite(got)) {
            return 3;
        }
        final double gap = Math.abs(want - got), error = gap / Math.max(Math.max(Math.abs(want), Math.abs(got)), TINY);
        return error <= 64 * EPS ? 0 : gap <= 4 * TINY ? 1 : error <= 1e-10 || gap <= allowed ? 2 : 3;
    }

    /** The cases: for each file, its name and how much of the largest value of an output a difference of rounding may be (for sums of many terms). */
    private static List<String[]> cases(Path folder) throws IOException {
        final Path list = folder.resolve("cases.txt");
        if (!Files.isRegularFile(list)) {
            throw new IOException("no cases in " + folder + ": ':ml fuzz' writes them");
        }
        return Files.readAllLines(list).stream().map(String::strip).filter(s -> !s.isEmpty()).map(s -> s.split("\t")).toList();
    }

    /** The cases, by the Java runtime. */
    public static Result java(Path folder) throws IOException {
        final List<String[]> files = cases(folder);
        final List<String> defects = new ArrayList<>();
        long compared = 0, rounding = 0, flushed = 0;
        for (String[] file : files) {
            final String name = file[0];
            final double share = file.length > 1 ? Double.parseDouble(file[1]) : 0;
            final List<NeuralGraph.Tensor> out;
            try {
                out = NeuralGraph.load(folder.resolve(name)).run(List.of());
            } catch (RuntimeException failed) {
                defects.add(name + ": " + failed.getMessage());
                continue;
            }
            for (int k = 0; k + 1 < out.size(); k += 2) {
                compared++;
                final NeuralGraph.Tensor got = out.get(k), want = out.get(k + 1);
                if (!Arrays.equals(got.shape, want.shape)) {
                    defects.add(name + " output " + k / 2 + ": shape " + Arrays.toString(want.shape) + ", here " + Arrays.toString(got.shape));
                    continue;
                }
                double largest = 0;
                for (double v : want.data) {
                    if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                        largest = Math.max(largest, Math.abs(v));
                    }
                }
                int worst = 0, at = -1;
                for (int i = 0; i < want.data.length; i++) {
                    final int how = classify(want.data[i], got.data[i], share * largest);
                    if (how > worst) {
                        worst = how;
                        at = i;
                    }
                }
                if (worst == 3) {
                    defects.add(name + " output " + k / 2 + " at " + at + ": PyTorch " + want.data[at] + ", here " + got.data[at]);
                } else if (worst == 2) {
                    rounding++;
                } else if (worst == 1) {
                    flushed++;
                }
            }
        }
        return new Result("Java", files.size(), compared, rounding, flushed, defects, "");
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String tail(String output) {
        final String[] lines = output.split("\n");
        return String.join(" | ", Arrays.copyOfRange(lines, Math.max(0, lines.length - 4), lines.length));
    }

    /** The cases, by the C++ runtime: a small program compiled here against sphere_graph.hpp. */
    public static Result cpp(Path folder, SettingsManager settings) throws IOException {
        final String cxx = Bridge.cppCompiler(settings);
        if (cxx == null) {
            return new Result("C++", 0, 0, 0, 0, List.of(), "no C++ compiler (GPP_DIR)");
        }
        final Path build = Bridge.buildFolder().resolve("conformance");
        Files.createDirectories(build);
        final Path main = build.resolve("replay.cpp"), exe = build.resolve(windows() ? "replay.exe" : "replay");
        Files.writeString(main, CPP);
        final List<String> compile = new ArrayList<>(List.of(cxx, "-std=c++17", "-O1", "-I" + Bridge.libraries(), main.toString(), "-o", exe.toString()));
        compile.add(windows() ? "-static" : "-pthread");
        final Bridge.Outcome made = Bridge.run(compile, build, 900, Bridge.parentOf(cxx));
        if (!made.ok()) {
            return new Result("C++", 0, 0, 0, 0, List.of(), "did not compile: " + tail(made.output()));
        }
        final Bridge.Outcome ran = Bridge.run(List.of(exe.toString(), folder.toString()), null, 900, Bridge.parentOf(cxx));
        final List<String> defects = new ArrayList<>();
        for (String line : ran.output().split("\n")) {
            final String[] done = line.strip().split(" ");
            if (line.startsWith("defect ")) {
                defects.add(line.substring(7).strip());
            } else if (done.length == 5 && done[0].equals("done")) {
                return new Result("C++", Integer.parseInt(done[1]), Long.parseLong(done[2]), Long.parseLong(done[3]), Long.parseLong(done[4]), defects, "");
            }
        }
        return new Result("C++", 0, 0, 0, 0, List.of(), "stopped (" + ran.status() + "): " + tail(ran.output()));
    }

    private static final String CPP = """
        #include "sphere_graph.hpp"
        #include <algorithm>
        #include <cmath>
        #include <fstream>
        #include <iostream>
        #include <limits>
        #include <string>
        #include <vector>

        using sphere::graph::Graph;
        using sphere::graph::Tensor;

        static int classify(double want, double got, double allowed) {
            const double eps = std::numeric_limits<double>::epsilon(), tiny = std::numeric_limits<double>::min();
            if ((std::isnan(want) && std::isnan(got)) || want == got) return 0;
            if (!std::isfinite(want) || !std::isfinite(got)) return 3;
            const double gap = std::fabs(want - got), error = gap / std::max(std::max(std::fabs(want), std::fabs(got)), tiny);
            return error <= 64 * eps ? 0 : gap <= 4 * tiny ? 1 : error <= 1e-10 || gap <= allowed ? 2 : 3;
        }

        int main(int argc, char** argv) {
            const std::string folder = argv[1];
            std::ifstream list(folder + "/cases.txt");
            std::string name;
            long files = 0, compared = 0, rounding = 0, flushed = 0;
            while (std::getline(list, name)) {
                if (!name.empty() && name.back() == '\\r') name.pop_back();
                if (name.empty()) continue;
                const size_t tab = name.find('\\t');
                const double share = tab == std::string::npos ? 0.0 : std::stod(name.substr(tab + 1));
                name = name.substr(0, tab);
                ++files;
                try {
                    const Graph g(folder + "/" + name);
                    const std::vector<Tensor> out = g.run(std::vector<Tensor>());
                    for (size_t k = 0; k + 1 < out.size(); k += 2) {
                        ++compared;
                        const Tensor& got = out[k];
                        const Tensor& want = out[k + 1];
                        if (got.shape != want.shape) {
                            std::cout << "defect " << name << " output " << k / 2 << ": another shape" << std::endl;
                            continue;
                        }
                        double largest = 0;
                        for (long i = 0; i < static_cast<long>(want.numel()); ++i)
                            if (std::isfinite(want.p[i])) largest = std::max(largest, std::fabs(want.p[i]));
                        int worst = 0;
                        long at = -1;
                        for (long i = 0; i < static_cast<long>(want.numel()); ++i) {
                            const int how = classify(want.p[i], got.p[i], share * largest);
                            if (how > worst) { worst = how; at = i; }
                        }
                        if (worst == 3) std::cout << "defect " << name << " output " << k / 2 << " at " << at << ": PyTorch " << want.p[at] << ", here " << got.p[at] << std::endl;
                        else if (worst == 2) ++rounding;
                        else if (worst == 1) ++flushed;
                    }
                } catch (const std::exception& e) {
                    std::cout << "defect " << name << ": " << e.what() << std::endl;
                }
            }
            std::cout << "done " << files << ' ' << compared << ' ' << rounding << ' ' << flushed << std::endl;
            return 0;
        }
        """;
}
