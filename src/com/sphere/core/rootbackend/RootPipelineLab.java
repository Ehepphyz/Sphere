package com.sphere.core.rootbackend;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pipeline laboratory: what a pipeline does at its edges, how sensitive it
 * is, how uncertain its answer is, whether it agrees with another, and what in
 * its source is likely to be wrong.
 *
 * Health, scan and watch (RootPipelineProbe) answer "does it return numbers".
 * The questions a physicist asks next are different. Which input moves the
 * answer most? If the inputs carry errors, what error does the answer carry,
 * and is the linear propagation in the paper good enough or does the function
 * bend? Does the optimized rewrite still give the same bits? Is -ffast-math
 * safe here? What happens on a zero, a NaN, a huge value? Does the pipeline
 * that says it keeps no state really keep none? And before any of it runs:
 * which lines of the source are the classic mistakes -- an unfolded delta phi,
 * atan(y/x), abs() on a double, 1/2 as an integer?
 *
 * Every run happens in a program built for the purpose, as the probe does, so
 * a pipeline that crashes on a corner case takes nothing with it, and the crash
 * itself is reported with the input that caused it.
 */
public final class RootPipelineLab {

    private static final int RUN_TIMEOUT_SECONDS = 180;

    private RootPipelineLab() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /* ================================================================== */
    /* The program every dynamic analysis runs in                          */
    /* ================================================================== */

    /** Bare C++ type of an input, without const. */
    private static String bare(String type) {
        return type == null ? "" : type.replace("const", "").trim();
    }

    /** The C++ program: the source included, one call() for every mode. */
    static String program(RootPipelineManifest m, Path source) {
        final int n = m.inputs.size();
        final StringBuilder o = new StringBuilder(8192);
        o.append("// Written by Sphere's pipeline lab for ").append(m.name).append(". Safe to delete.\n");
        o.append("#include \"").append(source.toAbsolutePath().toString().replace("\\", "/")).append("\"\n");
        o.append("#include <cstdio>\n#include <cstdlib>\n#include <cstring>\n#include <cmath>\n")
         .append("#include <cfloat>\n#include <climits>\n#include <cstdint>\n#include <vector>\n")
         .append("#include <random>\n#include <algorithm>\n\n");
        // Everything of the lab's own is in a namespace, and the pipeline is
        // called by its qualified name: a user function called draw or a
        // global called N must not collide with the harness.
        o.append("namespace sphere_lab {\n\n");
        o.append("static const int N = ").append(n).append(";\n");
        o.append("static double call(const double *a) {\n  return (double) (::").append(m.name).append('(');
        for (int i = 0; i < n; i++) {
            o.append(i > 0 ? ", " : "").append('(').append(m.inputs.get(i).type()).append(") a[").append(i).append(']');
        }
        o.append("));\n}\n\n");
        // The kind of each input decides which corner values are legal for it:
        // a NaN handed to an int is undefined behavior, not a test.
        o.append("static const int KIND[").append(Math.max(1, n)).append("] = {");
        for (int i = 0; i < n; i++) {
            final String t = bare(m.inputs.get(i).type());
            final int kind = switch (t) {
                case "bool" -> 3;
                case "unsigned int" -> 2;
                case "int", "long" -> 1;
                case "float" -> 4;
                default -> 0;
            };
            o.append(i > 0 ? ", " : "").append(kind);
        }
        if (n == 0) {
            o.append('0');
        }
        o.append("};\n\n");
        o.append("""
            static std::vector<double> corners(int kind) {
              switch (kind) {
                case 1: return {0.0, 1.0, -1.0, 2.0, 1000.0, -1000.0, (double) INT_MAX, (double) INT_MIN + 1};
                case 2: return {0.0, 1.0, 2.0, 1000.0, (double) UINT_MAX};
                case 3: return {0.0, 1.0};
                case 4: return {0.0, -0.0, 1.0, -1.0, 1e-30, -1e-30, 1e30, -1e30, (double) FLT_MAX,
                                3.141592653589793, NAN, INFINITY, -INFINITY};
                default: return {0.0, -0.0, 1.0, -1.0, 1e-300, -1e-300, 4.9e-324, 1e300, -1e300,
                                 3.141592653589793, -3.141592653589793, 6.283185307179586,
                                 NAN, INFINITY, -INFINITY};
              }
            }

            static void show(const double *a) {
              for (int i = 0; i < N; ++i) printf("%s%.17g", i ? " " : "", a[i]);
            }

            static unsigned long long state = 88172645463325252ULL;
            static double uniform() {
              state ^= state << 13; state ^= state >> 7; state ^= state << 17;
              return (double) (state >> 11) * (1.0 / 9007199254740992.0);
            }
            static double draw(int i, double lo, double hi) {
              double v = lo + uniform() * (hi - lo);
              if (KIND[i] == 1 || KIND[i] == 2) v = std::floor(v);
              if (KIND[i] == 2 && v < 0) v = -v;
              if (KIND[i] == 3) v = v > 0.5 * (lo + hi) ? 1.0 : 0.0;
              return v;
            }

            static int run_main(int argc, char **argv) {
              if (argc < 2) return 1;
              const char *mode = argv[1];
              double a[N > 0 ? N : 1];

              if (strcmp(mode, "sample") == 0) {
                // sample <count> <low> <high> <seed>: the inputs and the exact answer.
                long count = atol(argv[2]); double lo = atof(argv[3]), hi = atof(argv[4]);
                state ^= (unsigned long long) atoll(argv[5]) * 0x9E3779B97F4A7C15ULL;
                for (long k = 0; k < count; ++k) {
                  for (int i = 0; i < N; ++i) a[i] = draw(i, lo, hi);
                  double v = call(a); show(a); printf(" | %a\\n", v);
                }
                return 0;
              }

              if (strcmp(mode, "fuzz") == 0) {
                // Every corner value of every input. The input is written before
                // the call, so a crash leaves the culprit on the last line.
                std::vector<std::vector<double>> c(N);
                double total = 1; for (int i = 0; i < N; ++i) { c[i] = corners(KIND[i]); total *= c[i].size(); }
                long tried = 0, nan = 0, inf = 0, shown = 0;
                auto one = [&](void) {
                  printf("try "); show(a); printf("\\n"); fflush(stdout);
                  double v = call(a); ++tried;
                  bool inputsFinite = true; for (int i = 0; i < N; ++i) inputsFinite &= std::isfinite(a[i]);
                  if (!std::isfinite(v) && inputsFinite) {
                    if (std::isnan(v)) ++nan; else ++inf;
                    if (shown++ < 25) { printf("bad %s ", std::isnan(v) ? "nan" : "inf"); show(a); printf("\\n"); }
                  }
                };
                if (total <= 20000) {
                  std::vector<size_t> at(N, 0);
                  for (long k = 0; k < (long) total; ++k) {
                    for (int i = 0; i < N; ++i) a[i] = c[i][at[i]];
                    one();
                    for (int i = 0; i < N; ++i) { if (++at[i] < c[i].size()) break; at[i] = 0; }
                  }
                } else {
                  for (int i = 0; i < N; ++i) for (double v : c[i]) {
                    for (int j = 0; j < N; ++j) a[j] = (j == i) ? v : (KIND[j] == 3 ? 1.0 : 1.0);
                    one();
                  }
                  for (int k = 0; k < 20000; ++k) {
                    for (int i = 0; i < N; ++i) a[i] = c[i][(size_t) (uniform() * c[i].size()) % c[i].size()];
                    one();
                  }
                }
                printf("done tried=%ld nan=%ld inf=%ld\\n", tried, nan, inf);
                return 0;
              }

              if (strcmp(mode, "grad") == 0) {
                // grad <x1..xn>: central differences, and each input's elasticity.
                for (int i = 0; i < N; ++i) a[i] = atof(argv[2 + i]);
                const double f0 = call(a);
                printf("f %.17g\\n", f0);
                for (int i = 0; i < N; ++i) {
                  if (KIND[i] != 0 && KIND[i] != 4) { printf("d %d discrete\\n", i); continue; }
                  double x = a[i], h = 1e-6 * std::max(1.0, std::fabs(x));
                  a[i] = x + h; double up = call(a); a[i] = x - h; double dn = call(a);
                  a[i] = x + 2 * h; double up2 = call(a); a[i] = x - 2 * h; double dn2 = call(a); a[i] = x;
                  // Five-point stencil, and the second derivative for curvature.
                  double d = (-up2 + 8 * up - 8 * dn + dn2) / (12 * h);
                  double dd = (up - 2 * f0 + dn) / (h * h);
                  printf("d %d %.17g %.17g\\n", i, d, dd);
                }
                return 0;
              }

              if (strcmp(mode, "mc") == 0) {
                // mc <count> <seed> <x1> <s1> ...: Gaussian inputs, the answer's spread.
                long count = atol(argv[2]); std::mt19937_64 rng((unsigned long long) atoll(argv[3]));
                double mu[N > 0 ? N : 1], sg[N > 0 ? N : 1];
                for (int i = 0; i < N; ++i) { mu[i] = atof(argv[4 + 2 * i]); sg[i] = atof(argv[5 + 2 * i]); }
                std::normal_distribution<double> g(0.0, 1.0);
                std::vector<double> out; out.reserve(count); long bad = 0;
                for (long k = 0; k < count; ++k) {
                  for (int i = 0; i < N; ++i) a[i] = mu[i] + sg[i] * g(rng);
                  double v = call(a); if (std::isfinite(v)) out.push_back(v); else ++bad;
                }
                std::sort(out.begin(), out.end());
                auto q = [&](double p) -> double { if (out.empty()) return std::nan(""); double r = p * (out.size() - 1);
                  size_t lo = (size_t) r; size_t hi = std::min(lo + 1, out.size() - 1);
                  return out[lo] + (r - lo) * (out[hi] - out[lo]); };
                double s = 0, ss = 0; for (double v : out) { s += v; } double mean = out.empty() ? NAN : s / out.size();
                for (double v : out) ss += (v - mean) * (v - mean);
                double sd = out.size() > 1 ? std::sqrt(ss / (out.size() - 1)) : NAN;
                double m3 = 0; for (double v : out) m3 += std::pow((v - mean) / sd, 3); double skew = out.empty() ? 0 : m3 / out.size();
                for (int i = 0; i < N; ++i) a[i] = mu[i];
                const double f0 = call(a); double lin = 0;
                for (int i = 0; i < N; ++i) {
                  if (sg[i] == 0) { continue; }
                  double x = a[i], h = 1e-6 * std::max(1.0, std::fabs(x));
                  a[i] = x + h; double up = call(a); a[i] = x - h; double dn = call(a); a[i] = x;
                  double part = (up - dn) / (2 * h) * sg[i]; lin += part * part;
                  printf("part %d %.17g\\n", i, part);
                }
                printf("central %.17g\\nmean %.17g\\nsd %.17g\\nlinear %.17g\\nskew %.17g\\n", f0, mean, sd, std::sqrt(lin), skew);
                printf("q025 %.17g\\nq16 %.17g\\nq50 %.17g\\nq84 %.17g\\nq975 %.17g\\nbad %ld\\n",
                       q(0.025), q(0.16), q(0.5), q(0.84), q(0.975), bad);
                return 0;
              }

              if (strcmp(mode, "domain") == 0) {
                // domain <low> <high> <points> <held>: where each input gives a number.
                double lo = atof(argv[2]), hi = atof(argv[3]); int pts = atoi(argv[4]); double held = atof(argv[5]);
                for (int i = 0; i < N; ++i) {
                  int status = -1; double start = lo, last = lo;
                  for (int k = 0; k < pts; ++k) {
                    double x = lo + (hi - lo) * k / (pts - 1.0);
                    if (KIND[i] == 1 || KIND[i] == 2) x = std::floor(x);
                    for (int j = 0; j < N; ++j) a[j] = (j == i) ? x : held;
                    int ok = std::isfinite(call(a)) ? 1 : 0;
                    if (status < 0) { status = ok; start = x; }
                    else if (ok != status) { printf("seg %d %.10g %.10g %d\\n", i, start, last, status); status = ok; start = x; }
                    last = x;
                  }
                  printf("seg %d %.10g %.10g %d\\n", i, start, last, status);
                }
                return 0;
              }

              if (strcmp(mode, "state") == 0) {
                // Same inputs, forward then backward, with every other call between:
                // a pipeline that keeps no state answers the same bits both times.
                const int M = 400; std::vector<double> in(M * (N > 0 ? N : 1)), first(M);
                for (int k = 0; k < M; ++k) { for (int i = 0; i < N; ++i) in[k * N + i] = draw(i, -50.0, 150.0); }
                for (int k = 0; k < M; ++k) { for (int i = 0; i < N; ++i) a[i] = in[k * N + i]; first[k] = call(a); }
                long differ = 0; int firstAt = -1; double was = 0, now = 0;
                for (int k = M - 1; k >= 0; --k) {
                  for (int i = 0; i < N; ++i) a[i] = in[k * N + i];
                  double v = call(a);
                  bool same = std::memcmp(&v, &first[k], sizeof v) == 0 || (std::isnan(v) && std::isnan(first[k]));
                  if (!same) { ++differ; if (firstAt < 0) { firstAt = k; was = first[k]; now = v; } }
                }
                printf("calls %d\\ndiffer %ld\\n", 2 * M, differ);
                if (firstAt >= 0) {
                  for (int i = 0; i < N; ++i) a[i] = in[firstAt * N + i];
                  printf("example "); show(a); printf(" | %.17g then %.17g\\n", was, now);
                }
                return 0;
              }
              return 2;
            }

            }  // namespace sphere_lab

            int main(int argc, char **argv) { return sphere_lab::run_main(argc, argv); }
            """);
        return o.toString();
    }

    /** A built lab program, kept for as many runs as a command needs. */
    public static final class Bench implements AutoCloseable {
        private final Path workspace;
        private final Path binary;
        public final boolean built;
        public final String problem;

        private Bench(Path workspace, Path binary, boolean built, String problem) {
            this.workspace = workspace;
            this.binary = binary;
            this.built = built;
            this.problem = problem;
        }

        /** Runs one mode. Exit code and output, or null when it never started. */
        public Run run(List<String> arguments) {
            if (!built) {
                return null;
            }
            try {
                List<String> command = new ArrayList<>();
                command.add(binary.toAbsolutePath().toString());
                command.addAll(arguments);
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.redirectErrorStream(true);
                Process process = builder.start();
                List<String> lines = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                    }
                }
                if (!process.waitFor(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return new Run(lines, -1, true);
                }
                return new Run(lines, process.exitValue(), false);
            } catch (IOException cannotRun) {
                return null;
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        @Override
        public void close() {
            remove(workspace);
        }
    }

    /** What one run said, and how it ended. */
    public record Run(List<String> lines, int exitCode, boolean timedOut) {
        public boolean crashed() {
            return timedOut || exitCode != 0;
        }

        /** The value of "key value" lines. */
        public double value(String key) {
            for (String line : lines) {
                if (line.startsWith(key + " ")) {
                    return number(line.substring(key.length() + 1));
                }
            }
            return Double.NaN;
        }

        /** The last input tried before a crash, when the mode writes them. */
        public String lastTried() {
            for (int k = lines.size() - 1; k >= 0; k--) {
                if (lines.get(k).startsWith("try ")) {
                    return lines.get(k).substring(4);
                }
            }
            return null;
        }
    }

    /** Builds the lab program for a pipeline, with its own flags plus any extra. */
    public static Bench build(RootUserCompiler compiler, RootPipelineManifest manifest,
                              Path source, List<String> extraFlags) {
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("sphere-lab-");
            Path program = workspace.resolve(manifest.name + "_lab.cpp");
            Files.writeString(program, program(manifest, source), StandardCharsets.UTF_8);
            Path binary = workspace.resolve(manifest.name + "_lab"
                + (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? ".exe" : ""));
            List<String> flags = new ArrayList<>(manifest.flags());
            if (extraFlags != null) {
                flags.addAll(extraFlags);
            }
            RootUserCompiler.Build built = compiler.buildProgram(program, binary, flags);
            if (!built.succeeded()) {
                final List<String> said = built.lines();
                return new Bench(workspace, binary, false,
                    "The lab program did not compile" + (said.isEmpty() ? "." : ": " + String.join(" | ",
                        said.subList(0, Math.min(4, said.size())))));
            }
            if (!Files.isRegularFile(binary)) {
                // Some toolchains add .exe on their own.
                Path withExe = workspace.resolve(manifest.name + "_lab.exe");
                if (Files.isRegularFile(withExe)) {
                    return new Bench(workspace, withExe, true, "");
                }
            }
            return new Bench(workspace, binary, true, "");
        } catch (IOException cannotWrite) {
            remove(workspace);
            return new Bench(null, null, false, "Could not write the lab program: " + cannotWrite.getMessage());
        }
    }

    /* ================================================================== */
    /* Differential testing                                                */
    /* ================================================================== */

    /** One input where two builds or two pipelines disagree. */
    public record Disagreement(String inputs, double a, double b, long ulps) { }

    /** How two series of answers compare. */
    public record Comparison(int compared, int identical, long maxUlps, double maxAbs,
                             double maxRel, List<Disagreement> worst, String problem) {
        public boolean bitExact() {
            return problem == null && identical == compared;
        }
    }

    /** The answers of a "sample" run: inputs as text, outputs exact. */
    public static List<String[]> samples(Run run) {
        List<String[]> out = new ArrayList<>();
        if (run == null) {
            return out;
        }
        for (String line : run.lines()) {
            final int bar = line.lastIndexOf(" | ");
            if (bar > 0) {
                out.add(new String[] {line.substring(0, bar), line.substring(bar + 3).trim()});
            }
        }
        return out;
    }

    /** Compares two sample runs line by line. */
    public static Comparison compare(List<String[]> left, List<String[]> right) {
        final int n = Math.min(left.size(), right.size());
        if (n == 0) {
            return new Comparison(0, 0, 0, 0, 0, List.of(), "Nothing to compare: one side gave no answer.");
        }
        int identical = 0;
        long maxUlps = 0;
        double maxAbs = 0;
        double maxRel = 0;
        List<Disagreement> worst = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            final double a = number(left.get(k)[1]);
            final double b = number(right.get(k)[1]);
            if (Double.doubleToLongBits(a) == Double.doubleToLongBits(b) || (Double.isNaN(a) && Double.isNaN(b))) {
                identical++;
                continue;
            }
            final long ulps = ulpDistance(a, b);
            final double abs = Math.abs(a - b);
            final double rel = abs / Math.max(Math.abs(a), Math.abs(b));
            if (Double.isFinite(abs)) {
                maxAbs = Math.max(maxAbs, abs);
                maxRel = Math.max(maxRel, rel);
            }
            maxUlps = Math.max(maxUlps, ulps);
            worst.add(new Disagreement(left.get(k)[0], a, b, ulps));
        }
        worst.sort((x, y) -> Long.compare(y.ulps(), x.ulps()));
        return new Comparison(n, identical, maxUlps, maxAbs, maxRel,
            worst.subList(0, Math.min(5, worst.size())), null);
    }

    /** Units in the last place between two doubles; Long.MAX_VALUE when one is not finite. */
    static long ulpDistance(double a, double b) {
        if (!Double.isFinite(a) || !Double.isFinite(b)) {
            return Long.MAX_VALUE;
        }
        long x = Double.doubleToLongBits(a);
        long y = Double.doubleToLongBits(b);
        if (x < 0) {
            x = Long.MIN_VALUE - x;
        }
        if (y < 0) {
            y = Long.MIN_VALUE - y;
        }
        final long d = x - y;
        return d == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(d);
    }

    /* ================================================================== */
    /* Static analysis                                                     */
    /* ================================================================== */

    /** How bad a finding is. */
    public enum Severity {
        ERROR(15, "error"), WARNING(6, "warn"), INFO(1, "info");

        public final int cost;
        public final String label;

        Severity(int cost, String label) {
            this.cost = cost;
            this.label = label;
        }
    }

    /** One thing worth saying about one line of the source. */
    public record Finding(int line, Severity severity, String rule, String message,
                          boolean fixable) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%-5s line %-4d %-14s %s%s", severity.label, line, rule,
                message, fixable ? "   [auto-fix]" : "");
        }
    }

    private static final Pattern ABS = Pattern.compile("(?<![\\w:.])abs\\s*\\(");
    private static final Pattern ATAN_RATIO = Pattern.compile(
        "(?<![\\w:])(?:std::|TMath::)?(?:atan|ATan)\\s*\\(\\s*([A-Za-z_][\\w.\\[\\]()]*?)\\s*/\\s*([A-Za-z_][\\w.\\[\\]()]*?)\\s*\\)");
    private static final Pattern POW_SMALL = Pattern.compile(
        "(?:std::|TMath::)?(?:pow|Power)\\s*\\(\\s*([A-Za-z_][\\w.]*(?:\\[[^\\]]*\\])?|\\([^()]*\\))\\s*,\\s*(2|3|0\\.5)(?:\\.0*)?\\s*\\)");
    private static final Pattern INT_DIV = Pattern.compile("(?<![\\w.])(\\d+)\\s*/\\s*(\\d+)(?![\\w.])");
    private static final Pattern DPHI = Pattern.compile(
        "\\b([A-Za-z_]*[pP]hi\\w*(?:\\[[^\\]]*\\])?)\\s*-\\s*([A-Za-z_]*[pP]hi\\w*(?:\\[[^\\]]*\\])?)");
    private static final Pattern STATIC_LOCAL = Pattern.compile("^\\s+static\\s+(?!const\\b|constexpr\\b)");
    private static final Pattern DIVISION = Pattern.compile("/\\s*([A-Za-z_][\\w.]*+)(?!\\s*\\()");
    private static final Pattern FLOAT_EQ = Pattern.compile("[=!]=\\s*-?\\d+\\.\\d*(?:[eE][-+]?\\d+)?\\b");
    private static final Pattern SQRT = Pattern.compile("(?:std::|TMath::)?(?:sqrt|Sqrt)\\s*\\(");
    private static final Pattern ACOS = Pattern.compile("(?:std::|TMath::)?(?:acos|asin|ACos|ASin)\\s*\\(");
    private static final Pattern LOG = Pattern.compile("(?:std::|TMath::)?(?:log|log10|Log|Log10)\\s*\\(");

    /** The user's half of the source, and the line it starts on. */
    private record Body(String text, int firstLine) { }

    private static Body body(String source) {
        final int from = source.indexOf(RootPipelineTemplates.BODY_MARK);
        if (from < 0) {
            return new Body(source, 1);
        }
        final int start = source.indexOf('\n', from) + 1;
        int end = source.indexOf(RootPipelineTemplates.TAIL_MARK, start);
        if (end < 0) {
            end = source.length();
        }
        final int firstLine = (int) source.substring(0, start).chars().filter(ch -> ch == '\n').count() + 1;
        return new Body(source.substring(start, end), firstLine);
    }

    /** Text with comments and string literals blanked, line structure kept. */
    private static String code(String text) {
        final StringBuilder out = new StringBuilder(text.length());
        boolean line = false;
        boolean block = false;
        boolean string = false;
        for (int k = 0; k < text.length(); k++) {
            final char ch = text.charAt(k);
            final char next = k + 1 < text.length() ? text.charAt(k + 1) : '\0';
            if (line) {
                if (ch == '\n') {
                    line = false;
                    out.append('\n');
                } else {
                    out.append(' ');
                }
            } else if (block) {
                if (ch == '*' && next == '/') {
                    block = false;
                    out.append("  ");
                    k++;
                } else {
                    out.append(ch == '\n' ? '\n' : ' ');
                }
            } else if (string) {
                if (ch == '\\') {
                    out.append("  ");
                    k++;
                } else if (ch == '"') {
                    string = false;
                    out.append('"');
                } else {
                    out.append(ch == '\n' ? '\n' : ' ');
                }
            } else if (ch == '/' && next == '/') {
                line = true;
                out.append("  ");
                k++;
            } else if (ch == '/' && next == '*') {
                block = true;
                out.append("  ");
                k++;
            } else if (ch == '"') {
                string = true;
                out.append('"');
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /**
     * Reads the source the way a reviewer who has seen these bugs before would.
     *
     * Each rule is a mistake that has spoiled a real analysis: a delta phi that
     * is not folded gives a wrong delta R for one pair in six, atan(y/x) loses
     * the quadrant, abs() on a double can resolve to the int one and truncate,
     * 1/2 is zero, a static in a pipeline declared thread safe races under
     * :root mt on, and an input that is never read is a forgotten argument.
     */
    public static List<Finding> lint(String source, RootPipelineManifest manifest) {
        List<Finding> out = new ArrayList<>();
        if (source == null) {
            return out;
        }
        final Body body = body(source);
        final String code = code(body.text());
        final String[] lines = code.split("\n", -1);
        final boolean perEntry = manifest != null
            && (manifest.kind == RootPipelineManifest.Kind.TRANSFORM
                || manifest.kind == RootPipelineManifest.Kind.FILTER);
        final boolean foldsPhi = code.matches("(?s).*(Phi_mpi_pi|DeltaPhi|remainder|fmod|M_PI|Pi\\(\\)|6\\.28318|3\\.14159).*");

        for (int k = 0; k < lines.length; k++) {
            final String l = lines[k];
            final int at = body.firstLine() + k;
            if (l.isBlank()) {
                continue;
            }
            if (ABS.matcher(l).find()) {
                out.add(new Finding(at, Severity.WARNING, "abs-on-double",
                    "abs() may be the int overload and truncate a double; use std::abs.", true));
            }
            if (ATAN_RATIO.matcher(l).find()) {
                out.add(new Finding(at, Severity.ERROR, "atan-ratio",
                    "atan(y/x) loses the quadrant and divides by zero at x = 0; use std::atan2(y, x).", true));
            }
            final Matcher pow = POW_SMALL.matcher(l);
            if (pow.find()) {
                out.add(new Finding(at, Severity.INFO, "pow-small",
                    "pow(" + pow.group(1) + ", " + pow.group(2) + ") is a library call; "
                    + (pow.group(2).equals("0.5") ? "std::sqrt is exact and faster." : "a product is exact and faster."), true));
            }
            final Matcher div = INT_DIV.matcher(l);
            while (div.find()) {
                if (!l.contains("#")) {
                    out.add(new Finding(at, Severity.ERROR, "int-division",
                        div.group(1) + "/" + div.group(2) + " is integer division in C++ ("
                        + (Integer.parseInt(div.group(1)) / Math.max(1, Integer.parseInt(div.group(2))))
                        + "); write " + div.group(1) + ".0/" + div.group(2) + ".", true));
                }
            }
            final Matcher phi = DPHI.matcher(l);
            if (phi.find() && !foldsPhi) {
                out.add(new Finding(at, Severity.ERROR, "dphi-unfolded",
                    phi.group(1) + " - " + phi.group(2) + " is not folded into [-pi, pi]: "
                    + "pairs across the +-pi boundary get a delta phi near 2 pi.", true));
            }
            if (STATIC_LOCAL.matcher(l).find() && manifest != null && manifest.threadSafe) {
                out.add(new Finding(at, Severity.ERROR, "static-state",
                    "A static variable keeps state between calls, but the pipeline is declared thread safe: "
                    + ":root mt on would race on it.", false));
            }
            if (l.contains("gRandom") && manifest != null && manifest.threadSafe) {
                out.add(new Finding(at, Severity.ERROR, "shared-random",
                    "gRandom is one shared generator: not thread safe, and the answer depends on call order.", false));
            }
            if (perEntry && l.matches(".*(?<![\\w:])new\\s+[A-Za-z_].*") && !l.contains("unique_ptr")) {
                out.add(new Finding(at, Severity.WARNING, "alloc-per-entry",
                    "new on every entry allocates (and probably leaks) millions of times; use a value or a stack object.", false));
            }
            if (perEntry && l.matches(".*\\b(std::cout|std::cerr|printf|cout\\s*<<|Printf)\\b.*")) {
                out.add(new Finding(at, Severity.WARNING, "print-per-entry",
                    "Printing on every entry is slow and unreadable; name the value with SPHERE_WATCH(x).", false));
            }
            if (FLOAT_EQ.matcher(l).find()) {
                out.add(new Finding(at, Severity.INFO, "float-equality",
                    "Exact comparison of floating values; compare with a tolerance.", false));
            }
            if (l.matches(".*\\bfloat\\s+\\w+\\s*=\\s*0.*") && l.matches(".*\\b(sum|total|acc)\\w*.*")) {
                out.add(new Finding(at, Severity.INFO, "float-sum",
                    "Accumulating in float loses digits after ~10^7 entries; accumulate in double.", false));
            }
            if (l.contains("TLorentzVector")) {
                out.add(new Finding(at, Severity.INFO, "tlorentzvector",
                    "TLorentzVector is legacy; ROOT::Math::PtEtaPhiMVector (module genvector) is lighter and faster.", false));
            }
            guarded(out, l, code, at, SQRT, "sqrt-negative",
                "sqrt of a difference can be negative (a mass squared, a resolution): NaN. Guard it or decide the sign convention.",
                "-", "max(", ">= 0", "> 0", "fabs(", "abs(");
            guarded(out, l, code, at, ACOS, "acos-domain",
                "acos/asin outside [-1, 1] is NaN, and rounding gets there; clamp the argument.",
                null, "max(", "min(", "clamp(", "> 1", "< -1");
            guarded(out, l, code, at, LOG, "log-domain",
                "log of zero or of a negative value is -inf or NaN; guard it.",
                null, "> 0", "<= 0", "max(", "+ 1");
            final Matcher d = DIVISION.matcher(l);
            while (d.find()) {
                final String den = d.group(1);
                if (den.matches("\\d.*") || den.equalsIgnoreCase("TMath") || den.startsWith("M_")
                        || l.trim().startsWith("#") || l.contains("//")) {
                    continue;
                }
                final String name = Pattern.quote(den);
                final boolean guarded = Pattern.compile(
                    "(" + name + "\\s*(==|!=|<=|>=|<|>)|(==|!=|<=|>=|<|>)\\s*" + name + "\\b|if\\s*\\(\\s*!?\\s*" + name + "\\s*\\))")
                    .matcher(code).find();
                if (!guarded) {
                    out.add(new Finding(at, Severity.WARNING, "division-unguarded",
                        "Division by " + den + " with no test of " + den + " anywhere: one zero spoils the histogram.", false));
                }
            }
        }
        if (manifest != null) {
            for (RootPipelineManifest.Input input : manifest.inputs) {
                if (!Pattern.compile("\\b" + Pattern.quote(input.name()) + "\\b").matcher(
                        stripSignature(code, manifest)).find()) {
                    out.add(new Finding(body.firstLine(), Severity.WARNING, "unused-input",
                        "The input '" + input.name() + "' is never read: a forgotten argument, or one too many.", false));
                }
            }
            missingModules(out, body, code, manifest);
        }
        return dedupe(out);
    }

    /**
     * A part of ROOT the code reaches for while its module is not ticked.
     *
     * The module is what writes the #include, so the build would stop on a
     * name the user can see plainly in the code. It matters more now that a
     * formula leaves TMath out when it does not use it: the TMath::Landau
     * typed into it afterward needs the box ticked.
     */
    private static void missingModules(List<Finding> out, Body body, String code, RootPipelineManifest manifest) {
        final java.util.Set<RootPipelineModules> have = new java.util.HashSet<>(manifest.modules);
        // What the template adds on its own, whatever is ticked.
        if (manifest.inputs.stream().anyMatch(input -> input.type() != null && input.type().contains("RVec"))) {
            have.add(RootPipelineModules.VECTORS);
        }
        if (manifest.kind == RootPipelineManifest.Kind.HISTOGRAM) {
            have.add(RootPipelineModules.HIST);
            have.add(RootPipelineModules.TREE);
        }
        for (RootPipelineModules needed : RootPipelineStarts.modulesUsedBy(code)) {
            if (have.contains(needed)) {
                continue;
            }
            // Included by hand in the body counts as included.
            final boolean byHand = needed.headers().stream().anyMatch(header -> body.text().contains(header));
            if (!byHand) {
                out.add(new Finding(body.firstLine(), Severity.ERROR, "module-missing",
                    "The code uses " + needed.label + " but the " + needed.token + " module is not ticked:"
                    + " its #include is missing, so the build would stop there.", false));
            }
        }
    }

    /** The body without the entry point's own declaration, where every input appears. */
    private static String stripSignature(String code, RootPipelineManifest manifest) {
        final int at = code.indexOf(manifest.name + "(");
        if (at < 0) {
            return code;
        }
        final int brace = code.indexOf('{', at);
        return brace < 0 ? code : code.substring(0, at) + code.substring(brace);
    }

    private static void guarded(List<Finding> out, String line, String code, int at, Pattern call,
                                String rule, String message, String argumentMustContain,
                                String... guards) {
        final Matcher m = call.matcher(line);
        while (m.find()) {
            final String argument = balanced(line, m.end() - 1);
            if (argument == null) {
                continue;
            }
            if (argumentMustContain != null && !argument.contains(argumentMustContain)) {
                continue;
            }
            boolean safe = false;
            for (String guard : guards) {
                safe |= argument.contains(guard);
            }
            // A guard elsewhere on a variable named in the argument counts too.
            for (String word : argument.split("[^A-Za-z_0-9]+")) {
                if (word.length() > 1 && Character.isLetter(word.charAt(0))
                        && Pattern.compile("if\\s*\\([^)]*\\b" + Pattern.quote(word) + "\\b[^)]*[<>]").matcher(code).find()) {
                    safe = true;
                }
            }
            if (!safe) {
                out.add(new Finding(at, Severity.WARNING, rule, message, false));
                return;
            }
        }
    }

    /** The text inside the parenthesis that opens at `open`, or null when unbalanced. */
    static String balanced(String text, int open) {
        if (open < 0 || open >= text.length() || text.charAt(open) != '(') {
            return null;
        }
        int depth = 0;
        for (int k = open; k < text.length(); k++) {
            final char ch = text.charAt(k);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return text.substring(open + 1, k);
                }
            }
        }
        return null;
    }

    private static List<Finding> dedupe(List<Finding> in) {
        Set<String> seen = new LinkedHashSet<>();
        List<Finding> out = new ArrayList<>();
        for (Finding f : in) {
            if (seen.add(f.line() + "|" + f.rule())) {
                out.add(f);
            }
        }
        out.sort((a, b) -> a.severity() != b.severity()
            ? Integer.compare(a.severity().ordinal(), b.severity().ordinal())
            : Integer.compare(a.line(), b.line()));
        return out;
    }

    /** A score out of 100 from the findings, and the letter a report shows. */
    public static int score(List<Finding> findings) {
        int s = 100;
        for (Finding f : findings) {
            s -= f.severity().cost;
        }
        return Math.max(0, s);
    }

    public static String grade(int score) {
        return score >= 90 ? "A" : score >= 75 ? "B" : score >= 60 ? "C" : score >= 40 ? "D" : "F";
    }

    /* ================================================================== */
    /* Mechanical fixes                                                    */
    /* ================================================================== */

    /** The fixed source and what was changed, line by line. */
    public record Fixed(String source, List<String> changes) { }

    /**
     * Applies only the rewrites that cannot change a correct program's meaning
     * and do repair the wrong ones: std::abs for abs, atan2 for atan of a
     * ratio, a product for a small power, a real division for an integer one,
     * and a folded delta phi. Everything else is a judgment the user makes.
     */
    public static Fixed fix(String source) {
        final Body b = body(source);
        final String[] lines = b.text().split("\n", -1);
        final String code = code(b.text());
        final String[] codeLines = code.split("\n", -1);
        final boolean foldsPhi = code.matches("(?s).*(Phi_mpi_pi|DeltaPhi|remainder|fmod|M_PI|Pi\\(\\)|6\\.28318|3\\.14159).*");
        List<String> changes = new ArrayList<>();
        for (int k = 0; k < lines.length; k++) {
            if (k < codeLines.length && codeLines[k].isBlank()) {
                continue;
            }
            final String before = lines[k];
            String l = before;
            // Only where the code (not a comment) holds the pattern.
            final String c = k < codeLines.length ? codeLines[k] : l;
            if (ABS.matcher(c).find()) {
                l = ABS.matcher(l).replaceAll("std::abs(");
            }
            if (ATAN_RATIO.matcher(c).find()) {
                l = ATAN_RATIO.matcher(l).replaceAll("std::atan2($1, $2)");
            }
            if (POW_SMALL.matcher(c).find()) {
                final Matcher m = POW_SMALL.matcher(l);
                final StringBuilder sb = new StringBuilder();
                while (m.find()) {
                    final String x = m.group(1);
                    final String rep = switch (m.group(2)) {
                        case "2" -> "(" + x + " * " + x + ")";
                        case "3" -> "(" + x + " * " + x + " * " + x + ")";
                        default -> "std::sqrt(" + x + ")";
                    };
                    m.appendReplacement(sb, Matcher.quoteReplacement(rep));
                }
                m.appendTail(sb);
                l = sb.toString();
            }
            if (INT_DIV.matcher(c).find() && !c.trim().startsWith("#")) {
                l = INT_DIV.matcher(l).replaceAll("$1.0 / $2");
            }
            if (!foldsPhi && DPHI.matcher(c).find()) {
                l = DPHI.matcher(l).replaceAll("std::remainder($1 - $2, 6.283185307179586)");
            }
            if (!l.equals(before)) {
                lines[k] = l;
                changes.add(String.format(Locale.ROOT, "line %d\n      - %s\n      + %s",
                    b.firstLine() + k, before.trim(), l.trim()));
            }
        }
        if (changes.isEmpty()) {
            return new Fixed(source, changes);
        }
        final String fixedBody = String.join("\n", lines);
        final int from = source.indexOf(b.text());
        final String rebuilt = from < 0 ? source
            : source.substring(0, from) + fixedBody + source.substring(from + b.text().length());
        return new Fixed(rebuilt, changes);
    }

    /* ================================================================== */
    /* A pipeline from a formula                                           */
    /* ================================================================== */

    /**
     * What a formula became. problem, when it is not null, is why the formula
     * could not be read -- with a caret under the place -- and nothing should
     * be written from it.
     */
    public record FromFormula(RootPipelineManifest manifest, String source, List<String> notes,
                              String problem) {
        public FromFormula(RootPipelineManifest manifest, String source, List<String> notes) {
            this(manifest, source, notes, null);
        }
    }

    /**
     * A transform written from a one-line formula.
     *
     * The formula is read by RootFormula, the forge's own reader: a real
     * parser, so x^2 is a power whatever its base, 1/2 is 0.5, 0 < x < 1 is
     * both comparisons, and a mistake is caught here, with its place, rather
     * than by the compiler three steps later. The result is a pipeline like
     * any other: it can be edited, built, timed and audited. It stays a
     * transform, as this command always made: a formula that compares gives
     * 1 or 0, and the notes say how to make it a filter instead.
     */
    public static FromFormula fromFormula(String name, String formula) {
        final RootPipelineStarts.Start start = RootPipelineStarts.fromFormula(formula);
        final RootPipelineManifest m = new RootPipelineManifest();
        m.name = name;
        m.kind = RootPipelineManifest.Kind.TRANSFORM;
        m.description = "From the formula " + formula.trim().replace(" :: ", " ");
        if (!start.ok()) {
            return new FromFormula(m, "", List.of(), start.error());
        }
        RootPipelineRecipes.applyTo(start.recipe(), m);
        final boolean compares = m.kind == RootPipelineManifest.Kind.FILTER;
        m.kind = RootPipelineManifest.Kind.TRANSFORM;
        final List<String> notes = new ArrayList<>();
        for (String note : start.notes()) {
            if (!note.equals(RootPipelineStarts.COMPARES)) {
                notes.add(note);
            }
        }
        if (compares) {
            notes.add("It compares: as a transform it gives 1 or 0. "
                + ":root forge " + name + " from = ... makes it a filter.");
        }
        return new FromFormula(m, RootPipelineTemplates.source(m, start.recipe()), notes, null);
    }

    /* ================================================================== */

    /**
     * A number as C++ wrote it, %a hex floats, nan and inf included.
     *
     * Java reads 0x1.8p+1 itself; it does not read "nan", "-nan", "inf".
     */
    public static double number(String text) {
        final String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (t.endsWith("nan") || t.endsWith("nan(ind)")) {
            return Double.NaN;
        }
        if (t.equals("inf") || t.equals("+inf") || t.equals("infinity")) {
            return Double.POSITIVE_INFINITY;
        }
        if (t.equals("-inf") || t.equals("-infinity")) {
            return Double.NEGATIVE_INFINITY;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException notANumber) {
            return Double.NaN;
        }
    }

    private static void remove(Path folder) {
        if (folder == null) {
            return;
        }
        try (var walk = Files.walk(folder)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(one -> {
                try {
                    Files.deleteIfExists(one);
                } catch (IOException leaveIt) {
                    // Nothing here belongs to the user.
                }
            });
        } catch (IOException leaveIt) {
            // A leftover in the temp folder is not worth a message.
        }
    }
}
