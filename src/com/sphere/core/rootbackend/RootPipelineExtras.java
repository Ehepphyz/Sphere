package com.sphere.core.rootbackend;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a pipeline can do besides answering for one entry, written for it from
 * the form: be called on whole collections, give its own uncertainty, and
 * keep score of what it was given and what it answered. Nobody then writes
 * the loop over the muons, the error propagation or the counters by hand.
 *
 * Per object and the uncertainty are templates the interpreter is handed when
 * the library is loaded. Nothing of them is compiled into the library: a
 * pipeline pays for them only where they are called, and still builds where
 * ROOT is absent. The monitor is compiled in -- the user's function under an
 * inner name, and the entry point the engine calls counting around it.
 */
public final class RootPipelineExtras {

    private RootPipelineExtras() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    // ---- when each one applies ------------------------------------------------

    /** A plain number: what a collection holds one of. */
    static boolean scalar(String type) {
        return switch (bare(type)) {
            case "double", "float", "int", "long", "unsigned int", "bool" -> true;
            default -> false;
        };
    }

    private static boolean floating(String type) {
        final String t = bare(type);
        return t.equals("double") || t.equals("float");
    }

    private static boolean integral(String type) {
        final String t = bare(type);
        return t.equals("int") || t.equals("long") || t.equals("unsigned int");
    }

    private static boolean collection(String type) {
        return type != null && (type.contains("RVec") || type.contains("vector"));
    }

    private static String bare(String type) {
        return type == null ? "" : type.replace("const", "").trim();
    }

    private static boolean perEntry(RootPipelineManifest m) {
        return m.kind == RootPipelineManifest.Kind.TRANSFORM || m.kind == RootPipelineManifest.Kind.FILTER;
    }

    /** Why the pipeline cannot be called per object, or null when it can. */
    public static String whyNotPerObject(RootPipelineManifest m) {
        if (!perEntry(m)) {
            return "Only a transform or a filter answers once per object.";
        }
        if (m.inputs.isEmpty()) {
            return "It needs an input to hand each object's value to.";
        }
        for (RootPipelineManifest.Input input : m.inputs) {
            if (!scalar(input.type())) {
                return input.name() + " is a " + input.type()
                    + ": per object takes plain numbers and makes the collections itself.";
            }
        }
        return null;
    }

    /** Why the pipeline has no uncertainty to give, or null when it has. */
    public static String whyNotSigma(RootPipelineManifest m) {
        if (m.kind != RootPipelineManifest.Kind.TRANSFORM) {
            return "Only a transform has an uncertainty: a filter answers yes or no.";
        }
        for (RootPipelineManifest.Input input : m.inputs) {
            if (!scalar(input.type())) {
                return input.name() + " is a " + input.type() + ": the uncertainty moves plain numbers.";
            }
        }
        if (varied(m).isEmpty()) {
            return "No input is a double or a float, so there is nothing to move by an error.";
        }
        return null;
    }

    /** Why the pipeline cannot be monitored, or null when it can. */
    public static String whyNotMonitor(RootPipelineManifest m) {
        if (!perEntry(m)) {
            return "Only a transform or a filter is called once per entry and can be monitored.";
        }
        return null;
    }

    public static boolean perObject(RootPipelineManifest m) {
        return m != null && m.each && whyNotPerObject(m) == null;
    }

    public static boolean sigma(RootPipelineManifest m) {
        return m != null && m.sigma && whyNotSigma(m) == null;
    }

    public static boolean monitored(RootPipelineManifest m) {
        return m != null && m.monitor && whyNotMonitor(m) == null;
    }

    /** True when any extra is both asked for and possible. */
    public static boolean any(RootPipelineManifest m) {
        return perObject(m) || sigma(m) || monitored(m);
    }

    // ---- names ----------------------------------------------------------------

    public static String sigmaName(RootPipelineManifest m) {
        return m.name + "_sigma";
    }

    public static String reportName(RootPipelineManifest m) {
        return m.name + "_report";
    }

    public static String resetName(RootPipelineManifest m) {
        return m.name + "_report_reset";
    }

    /** The name the user's function is compiled under when it is monitored. */
    static String innerName(RootPipelineManifest m) {
        return "sphere_inner_" + m.name;
    }

    private static String space(RootPipelineManifest m) {
        return "sphere_monitor_" + m.name;
    }

    /**
     * What Sphere's own code in the file calls: the user's function itself.
     * Timing, the health check and the inspector measure the pipeline, not
     * the counting around it, and leave the engine's score alone.
     */
    static String callee(RootPipelineManifest m) {
        return monitored(m) ? innerName(m) : m.name;
    }

    /** The functions the extras add beside the pipeline's own, which no other pipeline may be called. */
    public static List<String> namesAdded(RootPipelineManifest m) {
        final List<String> out = new ArrayList<>();
        if (sigma(m)) {
            out.add(sigmaName(m));
        }
        if (monitored(m)) {
            out.add(reportName(m));
            out.add(resetName(m));
        }
        return out;
    }

    // ---- the uncertainty --------------------------------------------------------

    /** One input the uncertainty moves, the name its error goes by, and whether that is already an input. */
    record Varied(String input, String error, boolean given) { }

    /**
     * The inputs the uncertainty moves: every double or float that is not an
     * error itself. An input called pt_err beside pt is taken as pt's error
     * rather than asked for a second time.
     */
    static List<Varied> varied(RootPipelineManifest m) {
        final Set<String> names = new HashSet<>();
        for (RootPipelineManifest.Input input : m.inputs) {
            names.add(input.name());
        }
        final List<Varied> out = new ArrayList<>();
        for (RootPipelineManifest.Input input : m.inputs) {
            if (!floating(input.type()) || isError(input.name())) {
                continue;
            }
            final String error = input.name() + "_err";
            out.add(new Varied(input.name(), error, names.contains(error)));
        }
        return out;
    }

    private static boolean isError(String name) {
        final List<String> w = words(name);
        if (w.size() < 2) {
            return false;
        }
        final String last = w.get(w.size() - 1);
        return last.equals("err") || last.equals("error") || last.equals("unc") || last.equals("sigma");
    }

    /** The uncertainty's call, as the usage lines show it. */
    public static String sigmaCall(RootPipelineManifest m) {
        final StringBuilder out = new StringBuilder(sigmaName(m)).append('(');
        boolean first = true;
        for (RootPipelineManifest.Input input : m.inputs) {
            out.append(first ? "" : ", ").append(input.name());
            first = false;
        }
        for (Varied v : varied(m)) {
            if (!v.given()) {
                out.append(", ").append(v.error());
            }
        }
        return out.append(')').toString();
    }

    // ---- what an input's name says it measures ----------------------------------

    static final int OTHER = 0;
    static final int ENERGY = 1;
    static final int COMPONENT = 2;
    static final int ANGLE = 3;
    static final int RAPIDITY = 4;

    /**
     * What a name says the input measures, for the report's hints: an energy
     * or a mass is never negative and never above what the LHC delivers, an
     * angle stays within two pi, a pseudorapidity within ten. A word of the
     * name decides, the angle first: dphi_met is an angle, met_pt an energy.
     */
    static int measures(String name) {
        final List<String> w = words(name);
        int found = OTHER;
        for (String one : w) {
            final int kind = switch (one) {
                case "phi", "dphi", "deltaphi", "angle", "theta" -> ANGLE;
                case "eta", "deta", "deltaeta", "rapidity", "rap" -> RAPIDITY;
                case "px", "py", "pz", "mex", "mey", "metx", "mety" -> COMPONENT;
                case "pt", "met", "e", "en", "energy", "et", "m", "mass", "ht", "mht", "sumet",
                     "p", "mom", "momentum", "mt" -> ENERGY;
                default -> OTHER;
            };
            if (kind == ANGLE) {
                return ANGLE;
            }
            if (kind == RAPIDITY || (kind == COMPONENT && found != RAPIDITY)
                    || (kind == ENERGY && found == OTHER)) {
                found = kind;
            }
        }
        return found;
    }

    /** pt_lead -> [pt, lead]; muonPt -> [muon, pt]; Jet_eta -> [jet, eta]; MET -> [met]. */
    static List<String> words(String name) {
        final List<String> out = new ArrayList<>();
        if (name == null) {
            return out;
        }
        final StringBuilder one = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            final char ch = name.charAt(i);
            final boolean cut = ch == '_'
                || (i > 0 && Character.isUpperCase(ch)
                    && (Character.isLowerCase(name.charAt(i - 1)) || Character.isDigit(name.charAt(i - 1))));
            if (cut && one.length() > 0) {
                out.add(one.toString().toLowerCase(Locale.ROOT));
                one.setLength(0);
            }
            if (ch != '_') {
                one.append(ch);
            }
        }
        if (one.length() > 0) {
            out.add(one.toString().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    // ---- the source ---------------------------------------------------------------

    /** The lines of the header comment that say how to use what was added. */
    static String usage(RootPipelineManifest m) {
        final StringBuilder out = new StringBuilder();
        final String call = call(m);
        if (perObject(m)) {
            out.append("//\n");
            out.append("// Per object: ").append(call).append(" also takes a collection -- a\n");
            out.append("//   ROOT::RVec or a std::vector -- for any of its inputs, and answers ");
            if (m.kind == RootPipelineManifest.Kind.FILTER) {
                out.append("1 or 0\n");
                out.append("//   for each object: a mask, as in Muon_pt[").append(m.name).append("(...)].\n");
            } else {
                out.append("one\n");
                out.append("//   value per object. An input left a number is shared by every object.\n");
            }
        }
        if (sigma(m)) {
            out.append("//\n");
            out.append("// Uncertainty: ").append(sigmaCall(m)).append('\n');
            out.append("//   moves each input by its error, the others held, and adds the swings in\n");
            out.append("//   quadrature. A move that leaves the domain gives way to the one on the\n");
            out.append("//   other side, so pt - pt_err below zero does not make it NaN.\n");
        }
        if (monitored(m)) {
            out.append("//\n");
            out.append("// Monitored: :root pipeline report ").append(m.name)
               .append(" -- calls, numbers that were not,\n");
            out.append("//   where each input lived, the cost of a call, and the cutflow of every\n");
            out.append("//   SPHERE_CUT(...) in the code.\n");
        }
        return out.toString();
    }

    private static String call(RootPipelineManifest m) {
        final StringBuilder out = new StringBuilder(m.name).append('(');
        for (int i = 0; i < m.inputs.size(); i++) {
            out.append(i > 0 ? ", " : "").append(m.inputs.get(i).name());
        }
        return out.append(')').toString();
    }

    /**
     * What the header defines for the cuts, and, for a monitored pipeline,
     * the score kept per thread. SPHERE_CUT is always defined, so code that
     * marks its cuts compiles with the monitor off -- where a cut is simply
     * the condition it wraps.
     */
    static String header(RootPipelineManifest m) {
        if (!perEntry(m)) {
            return "";
        }
        if (!monitored(m)) {
            return "\n"
                 + "// SPHERE_CUT(condition) is the condition itself; with the monitor on,\n"
                 + "// each one is counted, and :root pipeline report shows the cutflow.\n"
                 + "#undef SPHERE_CUT\n"
                 + "#define SPHERE_CUT(...) (static_cast<bool>(__VA_ARGS__))\n";
        }
        final List<RootPipelineManifest.Input> tracked = tracked(m);
        return ("""

            // Monitored: :root pipeline report @name@ says how often it was called,
            // which inputs and answers were not numbers (the first of each kept),
            // where each input lived and what a call cost; every SPHERE_CUT(...) is
            // counted as one step of a cutflow. Your function below is compiled as
            // @inner@, and the @name@ the engine calls, at the end of this file,
            // keeps the score around it: per thread, without a lock.
            #include <atomic>
            #include <cstdio>
            #include <limits>
            #include <mutex>
            #include <utility>
            #include <vector>
            namespace @space@ {
            constexpr int kInputs = @tracked@;
            constexpr int kCuts = 32;
            struct Tally {
              std::atomic<unsigned long long> calls, timed, nanos, badIn, badOut, kept;
              std::atomic<double> low[kInputs > 0 ? kInputs : 1], high[kInputs > 0 ? kInputs : 1];
              std::atomic<unsigned long long> seen[kCuts], passed[kCuts];
              Tally() { clear(); }
              void clear() {
                for (std::atomic<unsigned long long> *n : {&calls, &timed, &nanos, &badIn, &badOut, &kept}) {
                  n->store(0, std::memory_order_relaxed);
                }
                for (int i = 0; i < kInputs; ++i) {
                  low[i].store(std::numeric_limits<double>::infinity(), std::memory_order_relaxed);
                  high[i].store(-std::numeric_limits<double>::infinity(), std::memory_order_relaxed);
                }
                for (int k = 0; k < kCuts; ++k) {
                  seen[k].store(0, std::memory_order_relaxed);
                  passed[k].store(0, std::memory_order_relaxed);
                }
              }
            };
            inline std::mutex lock;
            inline std::vector<Tally *> tallies;
            // One tally per thread: no two threads ever write the same counter.
            inline Tally &mine() {
              thread_local Tally *one = nullptr;
              if (one == nullptr) {
                one = new Tally();
                std::lock_guard<std::mutex> hold(lock);
                tallies.push_back(one);
              }
              return *one;
            }
            inline void bump(std::atomic<unsigned long long> &n, unsigned long long by = 1) {
              n.store(n.load(std::memory_order_relaxed) + by, std::memory_order_relaxed);
            }
            inline void range(Tally &t, int i, double v) {
              if (v < t.low[i].load(std::memory_order_relaxed)) {
                t.low[i].store(v, std::memory_order_relaxed);
              }
              if (v > t.high[i].load(std::memory_order_relaxed)) {
                t.high[i].store(v, std::memory_order_relaxed);
              }
            }
            inline std::atomic<const char *> cutText[kCuts];
            inline std::atomic<int> cutCount{0};
            inline int sphere_site(const char *text) {
              const int k = cutCount.fetch_add(1);
              if (k < kCuts) {
                cutText[k].store(text);
              }
              return k;
            }
            inline bool sphere_cut(int k, bool pass) {
              if (k >= 0 && k < kCuts) {
                Tally &t = mine();
                bump(t.seen[k]);
                if (pass) {
                  bump(t.passed[k]);
                }
              }
              return pass;
            }
            inline std::atomic<bool> haveIn{false}, haveOut{false};
            inline std::string firstIn, firstOut;
            inline void keep(std::atomic<bool> &have, std::string &into, const char *text) {
              std::lock_guard<std::mutex> hold(lock);
              if (!have.load(std::memory_order_relaxed)) {
                into = text;
                have.store(true, std::memory_order_relaxed);
              }
            }
            // What a range says about the input it was seen on: a unit, a marker.
            inline const char *hint(int kind, double low, double high) {
              if (low == -999.0 || low == -9999.0 || low == -99999.0 || low == -99.0
                  || (kind == 1 && low == -1.0)) {
                return "   <- the lowest looks like a 'missing' marker: filter those entries first?";
              }
              switch (kind) {
                case 1:
                  if (high > 14000.0) {
                    return "   <- above what the LHC delivers: MeV rather than GeV?";
                  }
                  if (low < 0.0) {
                    return "   <- negative, for a quantity that never is";
                  }
                  break;
                case 2:
                  if (high > 14000.0 || low < -14000.0) {
                    return "   <- beyond what the LHC delivers: MeV rather than GeV?";
                  }
                  break;
                case 3:
                  if (high > 6.2832 || low < -6.2832) {
                    return high <= 360.0 && low >= -360.0 ? "   <- beyond 2 pi: degrees rather than radians?"
                                                          : "   <- beyond 2 pi: not an angle in radians";
                  }
                  break;
                case 4:
                  if (high > 10.0 || low < -10.0) {
                    return "   <- beyond 10: no detector measures there; a placeholder?";
                  }
                  break;
                default:
                  break;
              }
              return "";
            }
            }  // namespace @space@
            #undef SPHERE_CUT
            #define SPHERE_CUT(...) @space@::sphere_cut([] { static const int sphere_k = @space@::sphere_site(#__VA_ARGS__); return sphere_k; }(), static_cast<bool>(__VA_ARGS__))
            // The entry point the engine calls, written at the end of this file.
            @signature@;
            """)
            .replace("@name@", m.name)
            .replace("@inner@", innerName(m))
            .replace("@space@", space(m))
            .replace("@tracked@", Integer.toString(tracked.size()))
            .replace("@signature@", m.signature());
    }

    /** The inputs whose range is kept: the numbers, not the yes-or-no ones. */
    private static List<RootPipelineManifest.Input> tracked(RootPipelineManifest m) {
        final List<RootPipelineManifest.Input> out = new ArrayList<>();
        for (RootPipelineManifest.Input input : m.inputs) {
            if (floating(input.type()) || integral(input.type())) {
                out.add(input);
            }
        }
        return out;
    }

    /**
     * The entry point the engine calls, and the report: written after the
     * user's code, which it calls under its inner name.
     */
    static String tail(RootPipelineManifest m) {
        if (!monitored(m)) {
            return "";
        }
        final String space = space(m);
        final boolean filter = m.kind == RootPipelineManifest.Kind.FILTER;
        final List<RootPipelineManifest.Input> tracked = tracked(m);
        final StringBuilder out = new StringBuilder(4096);

        // The entry point.
        out.append("\n// The entry point the engine calls: ").append(innerName(m))
           .append(", with the score kept around it.\n");
        out.append(m.signature()).append(" {\n");
        out.append("  ").append(space).append("::Tally &sphere_t = ").append(space).append("::mine();\n");
        out.append("  const unsigned long long sphere_n = sphere_t.calls.load(std::memory_order_relaxed);\n");
        out.append("  sphere_t.calls.store(sphere_n + 1, std::memory_order_relaxed);\n");
        out.append("  bool sphere_odd = false;\n");
        for (int i = 0; i < tracked.size(); i++) {
            final RootPipelineManifest.Input input = tracked.get(i);
            if (floating(input.type())) {
                out.append("  if (std::isfinite(").append(input.name()).append(")) {\n");
                out.append("    ").append(space).append("::range(sphere_t, ").append(i).append(", (double) ")
                   .append(input.name()).append(");\n");
                out.append("  } else {\n    sphere_odd = true;\n  }\n");
            } else {
                out.append("  ").append(space).append("::range(sphere_t, ").append(i).append(", (double) ")
                   .append(input.name()).append(");\n");
            }
        }
        for (RootPipelineManifest.Input input : m.inputs) {
            if (collection(input.type())) {
                out.append("  const std::size_t sphere_size_").append(input.name()).append(" = ")
                   .append(input.name()).append(".size();\n");
            }
        }
        // One call in 1024, never a thread's first: that one pays for cold
        // caches and the tally's creation, and would weigh on every mean.
        out.append("  const bool sphere_clocked = (sphere_n & 1023u) == 1023u;\n");
        out.append("  std::chrono::steady_clock::time_point sphere_began;\n");
        out.append("  if (sphere_clocked) {\n    sphere_began = std::chrono::steady_clock::now();\n  }\n");
        out.append("  const ").append(m.kind.returnType).append(" sphere_answer = ").append(innerName(m)).append('(');
        for (int i = 0; i < m.inputs.size(); i++) {
            final RootPipelineManifest.Input input = m.inputs.get(i);
            out.append(i > 0 ? ", " : "");
            if (scalar(input.type()) || bare(input.type()).equals("char *")) {
                out.append(input.name());
            } else {
                out.append("std::move(").append(input.name()).append(')');
            }
        }
        out.append(");\n");
        out.append("  if (sphere_clocked) {\n");
        out.append("    ").append(space).append("::bump(sphere_t.timed);\n");
        out.append("    ").append(space).append("::bump(sphere_t.nanos, (unsigned long long)\n");
        out.append("        std::chrono::duration_cast<std::chrono::nanoseconds>(\n");
        out.append("            std::chrono::steady_clock::now() - sphere_began).count());\n");
        out.append("  }\n");
        final String[] described = describe(m);
        out.append("  if (sphere_odd) {\n");
        out.append("    ").append(space).append("::bump(sphere_t.badIn);\n");
        out.append("    if (!").append(space).append("::haveIn.load(std::memory_order_relaxed)) {\n");
        out.append("      char sphere_text[512];\n");
        out.append("      std::snprintf(sphere_text, sizeof sphere_text, \"").append(described[0]).append('"')
           .append(described[1]).append(");\n");
        out.append("      ").append(space).append("::keep(").append(space).append("::haveIn, ")
           .append(space).append("::firstIn, sphere_text);\n");
        out.append("    }\n");
        if (filter) {
            out.append("  }\n");
            out.append("  if (sphere_answer) {\n");
            out.append("    ").append(space).append("::bump(sphere_t.kept);\n");
            out.append("  }\n");
        } else {
            out.append("  } else if (!std::isfinite(sphere_answer)) {\n");
            out.append("    ").append(space).append("::bump(sphere_t.badOut);\n");
            out.append("    if (!").append(space).append("::haveOut.load(std::memory_order_relaxed)) {\n");
            out.append("      char sphere_text[512];\n");
            out.append("      std::snprintf(sphere_text, sizeof sphere_text, \"").append(described[0])
               .append(" -> %g\"").append(described[1]).append(", (double) sphere_answer);\n");
            out.append("      ").append(space).append("::keep(").append(space).append("::haveOut, ")
               .append(space).append("::firstOut, sphere_text);\n");
            out.append("    }\n");
            out.append("  }\n");
        }
        out.append("  return sphere_answer;\n");
        out.append("}\n");

        // The report.
        int width = 6;
        for (RootPipelineManifest.Input input : tracked) {
            width = Math.max(width, input.name().length());
        }
        out.append("\n// What :root pipeline report ").append(m.name).append(" prints.\n");
        out.append("std::string ").append(reportName(m)).append("() {\n");
        out.append("  constexpr int sphere_inputs = ").append(space).append("::kInputs > 0 ? ")
           .append(space).append("::kInputs : 1;\n");
        out.append("  unsigned long long calls = 0, timed = 0, nanos = 0, badIn = 0, badOut = 0, kept = 0;\n");
        out.append("  double low[sphere_inputs], high[sphere_inputs];\n");
        out.append("  for (int i = 0; i < sphere_inputs; ++i) {\n");
        out.append("    low[i] = std::numeric_limits<double>::infinity();\n");
        out.append("    high[i] = -std::numeric_limits<double>::infinity();\n");
        out.append("  }\n");
        out.append("  unsigned long long seen[").append(space).append("::kCuts] = {};\n");
        out.append("  unsigned long long passed[").append(space).append("::kCuts] = {};\n");
        out.append("  std::size_t threads = 0;\n");
        out.append("  std::string firstIn, firstOut;\n");
        out.append("  {\n");
        out.append("    std::lock_guard<std::mutex> hold(").append(space).append("::lock);\n");
        out.append("    for (").append(space).append("::Tally *t : ").append(space).append("::tallies) {\n");
        out.append("      const unsigned long long c = t->calls.load(std::memory_order_relaxed);\n");
        out.append("      if (c == 0) {\n        continue;\n      }\n");
        out.append("      ++threads;\n");
        out.append("      calls += c;\n");
        for (String field : new String[]{"timed", "nanos", "badIn", "badOut", "kept"}) {
            out.append("      ").append(field).append(" += t->").append(field)
               .append(".load(std::memory_order_relaxed);\n");
        }
        out.append("      for (int i = 0; i < ").append(space).append("::kInputs; ++i) {\n");
        out.append("        const double lo = t->low[i].load(std::memory_order_relaxed);\n");
        out.append("        const double hi = t->high[i].load(std::memory_order_relaxed);\n");
        out.append("        low[i] = lo < low[i] ? lo : low[i];\n");
        out.append("        high[i] = hi > high[i] ? hi : high[i];\n");
        out.append("      }\n");
        out.append("      for (int k = 0; k < ").append(space).append("::kCuts; ++k) {\n");
        out.append("        seen[k] += t->seen[k].load(std::memory_order_relaxed);\n");
        out.append("        passed[k] += t->passed[k].load(std::memory_order_relaxed);\n");
        out.append("      }\n");
        out.append("    }\n");
        out.append("    firstIn = ").append(space).append("::firstIn;\n");
        out.append("    firstOut = ").append(space).append("::firstOut;\n");
        out.append("  }\n");
        out.append("  std::string out;\n");
        out.append("  char line[640];\n");
        out.append("  std::snprintf(line, sizeof line, \"").append(m.name)
           .append(": %llu call%s on %zu thread%s\\n\", calls, calls == 1 ? \"\" : \"s\", threads,\n");
        out.append("                threads == 1 ? \"\" : \"s\");\n");
        out.append("  out += line;\n");
        out.append("  if (calls == 0) {\n");
        out.append("    out += \"  not called since the library was loaded, or since the last reset\\n\";\n");
        out.append("    return out;\n");
        out.append("  }\n");
        out.append("  const double all = (double) calls;\n");
        if (filter) {
            out.append("  std::snprintf(line, sizeof line, \"  kept %llu of them (%.4g%%)\\n\", kept, 100.0 * kept / all);\n");
            out.append("  out += line;\n");
        }
        out.append("  if (badIn > 0) {\n");
        out.append("    std::snprintf(line, sizeof line, \"  %llu call%s given an input that is not a number"
                 + " (%.3g%%); the first: %s\\n\",\n");
        out.append("                  badIn, badIn == 1 ? \"\" : \"s\", 100.0 * badIn / all, firstIn.c_str());\n");
        out.append("    out += line;\n");
        out.append("  }\n");
        if (!filter) {
            out.append("  if (badOut > 0) {\n");
            out.append("    std::snprintf(line, sizeof line, \"  %llu answer%s not a number from inputs that were"
                     + " (%.3g%%); the first: %s\\n\",\n");
            out.append("                  badOut, badOut == 1 ? \"\" : \"s\", 100.0 * badOut / all, firstOut.c_str());\n");
            out.append("    out += line;\n");
            out.append("  }\n");
            out.append("  if (badIn == 0 && badOut == 0) {\n");
            out.append("    out += \"  every input and every answer was a number\\n\";\n");
            out.append("  }\n");
        } else {
            out.append("  if (badIn == 0) {\n");
            out.append("    out += \"  every input was a number\\n\";\n");
            out.append("  }\n");
        }
        if (!tracked.isEmpty()) {
            out.append("  out += \"  inputs, lowest and highest seen:\\n\";\n");
            for (int i = 0; i < tracked.size(); i++) {
                final RootPipelineManifest.Input input = tracked.get(i);
                out.append("  if (low[").append(i).append("] <= high[").append(i).append("]) {\n");
                out.append("    std::snprintf(line, sizeof line, \"    %-").append(width)
                   .append("s %13.6g %13.6g%s\\n\", \"").append(input.name()).append("\", low[").append(i)
                   .append("], high[").append(i).append("],\n");
                out.append("                  ").append(space).append("::hint(").append(measures(input.name()))
                   .append(", low[").append(i).append("], high[").append(i).append("]));\n");
                out.append("    out += line;\n");
                out.append("  }\n");
            }
        }
        out.append("  if (timed > 0) {\n");
        out.append("    // A timed call also pays for one reading of the clock: measured here, and taken off.\n");
        out.append("    const auto sphere_from = std::chrono::steady_clock::now();\n");
        out.append("    for (int k = 0; k < 1000; ++k) {\n");
        out.append("      (void) std::chrono::steady_clock::now();\n");
        out.append("    }\n");
        out.append("    const double clock = std::chrono::duration<double, std::nano>(\n");
        out.append("        std::chrono::steady_clock::now() - sphere_from).count() / 1001.0;\n");
        out.append("    const double each = (double) nanos / (double) timed - clock;\n");
        out.append("    std::snprintf(line, sizeof line, \"  about %.3g ns a call (%llu call%s timed, one in 1024"
                 + " on each thread, the clock's own %.2g ns taken off)\\n\",\n");
        out.append("                  each > 0.0 ? each : 0.0, timed, timed == 1 ? \"\" : \"s\", clock);\n");
        out.append("    out += line;\n");
        out.append("  }\n");
        out.append("  const int cuts = ").append(space).append("::cutCount.load() < ").append(space)
           .append("::kCuts ? ").append(space).append("::cutCount.load() : ").append(space).append("::kCuts;\n");
        out.append("  if (cuts > 0) {\n");
        out.append("    out += \"  cutflow                                    seen       passed   of seen  of calls\\n\";\n");
        out.append("    for (int k = 0; k < cuts; ++k) {\n");
        out.append("      const char *text = ").append(space).append("::cutText[k].load();\n");
        out.append("      std::snprintf(line, sizeof line, \"    %-36.36s %10llu %12llu %8.2f%% %8.2f%%\\n\",\n");
        out.append("                    text == nullptr ? \"?\" : text, seen[k], passed[k],\n");
        out.append("                    seen[k] > 0 ? 100.0 * passed[k] / seen[k] : 0.0, 100.0 * passed[k] / all);\n");
        out.append("      out += line;\n");
        out.append("    }\n");
        out.append("  }\n");
        out.append("#ifdef __FAST_MATH__\n");
        out.append("  out += \"  built with fast math: a value that is not a number may have gone unseen\\n\";\n");
        out.append("#endif\n");
        out.append("  return out;\n");
        out.append("}\n");

        out.append("\n// Starts the score again, for :root pipeline report ").append(m.name).append(" reset.\n");
        out.append("void ").append(resetName(m)).append("() {\n");
        out.append("  std::lock_guard<std::mutex> hold(").append(space).append("::lock);\n");
        out.append("  for (").append(space).append("::Tally *t : ").append(space).append("::tallies) {\n");
        out.append("    t->clear();\n");
        out.append("  }\n");
        out.append("  ").append(space).append("::haveIn.store(false);\n");
        out.append("  ").append(space).append("::haveOut.store(false);\n");
        out.append("  ").append(space).append("::firstIn.clear();\n");
        out.append("  ").append(space).append("::firstOut.clear();\n");
        out.append("}\n");
        return out.toString();
    }

    /** The printf format that shows a call's inputs, and its arguments after a comma each. */
    private static String[] describe(RootPipelineManifest m) {
        final StringBuilder format = new StringBuilder();
        final StringBuilder values = new StringBuilder();
        for (int i = 0; i < m.inputs.size(); i++) {
            final RootPipelineManifest.Input input = m.inputs.get(i);
            final String name = input.name();
            format.append(i > 0 ? ", " : "").append(name).append('=');
            if (floating(input.type())) {
                format.append("%g");
                values.append(", (double) ").append(name);
            } else if (integral(input.type())) {
                format.append("%lld");
                values.append(", (long long) ").append(name);
            } else if (bare(input.type()).equals("bool")) {
                format.append("%s");
                values.append(", ").append(name).append(" ? \"true\" : \"false\"");
            } else if (bare(input.type()).equals("char *")) {
                format.append("\\\"%.40s\\\"");
                values.append(", ").append(name).append(" == nullptr ? \"(null)\" : ").append(name);
            } else if (collection(input.type())) {
                format.append("(%zu values)");
                values.append(", sphere_size_").append(name);
            } else {
                format.append("(...)");
            }
        }
        return new String[]{format.toString(), values.toString()};
    }

    // ---- the interpreter --------------------------------------------------------

    /**
     * What every pipeline's per-object and uncertainty templates share: which
     * argument is a collection, its length, its i-th value, and the swing of
     * a value moved up and down. Declared once, whoever comes first.
     */
    static final String HELPERS = """
        #ifndef SPHERE_FORGE_HELPERS
        #define SPHERE_FORGE_HELPERS
        #include <ROOT/RVec.hxx>
        #include <cmath>
        #include <cstddef>
        #include <limits>
        #include <stdexcept>
        #include <string>
        #include <type_traits>
        #include <utility>
        namespace sphere_forge {
        template <typename T, typename = void> struct is_seq : std::false_type {};
        template <typename T>
        struct is_seq<T, std::void_t<decltype(std::declval<const T &>().size()),
                                     decltype(std::declval<const T &>()[0])>> : std::true_type {};
        template <> struct is_seq<std::string, void> : std::false_type {};
        template <typename T> constexpr bool is_seq_v = is_seq<std::decay_t<T>>::value;
        template <typename... A> constexpr bool any_seq_v = (is_seq_v<A> || ...);
        template <typename T> decltype(auto) at(const T &a, std::size_t i) {
          if constexpr (is_seq_v<T>) {
            return a[i];
          } else {
            return (a);
          }
        }
        template <typename T>
        void size_into(const char *fn, const char *arg, const T &a, std::size_t &n, const char *&first) {
          if constexpr (is_seq_v<T>) {
            if (first == nullptr) {
              n = a.size();
              first = arg;
            } else if (a.size() != n) {
              throw std::length_error(std::string(fn) + ": " + arg + " holds " + std::to_string(a.size())
                                      + " values where " + first + " holds " + std::to_string(n));
            }
          }
        }
        inline double swing1(double up, double mid, double down) {
          const bool u = std::isfinite(up), d = std::isfinite(down);
          if (u && d) {
            return 0.5 * (up - down);
          }
          if (u) {
            return up - mid;
          }
          if (d) {
            return mid - down;
          }
          return std::numeric_limits<double>::quiet_NaN();
        }
        template <typename U, typename M, typename D> auto swing(const U &up, const M &mid, const D &down) {
          if constexpr (any_seq_v<U, M, D>) {
            std::size_t n = 0;
            const char *first = nullptr;
            size_into("sigma", "the move up", up, n, first);
            size_into("sigma", "the value", mid, n, first);
            size_into("sigma", "the move down", down, n, first);
            ROOT::RVec<double> out(n);
            for (std::size_t i = 0; i < n; ++i) {
              out[i] = swing1((double) at(up, i), (double) at(mid, i), (double) at(down, i));
            }
            return out;
          } else {
            return swing1((double) up, (double) mid, (double) down);
          }
        }
        template <typename... S> auto quadrature(const S &...s) {
          using std::sqrt;
          return sqrt(((s * s) + ...));
        }
        }  // namespace sphere_forge
        #endif
        """;

    /**
     * What has to be declared, beside the entry point, for the extras to be
     * called by name: one block each, so one refused cannot take the others.
     * Guarded, so a pipeline loaded twice in one session declares them once.
     */
    public static List<String> declarations(RootPipelineManifest m) {
        final List<String> out = new ArrayList<>();
        if (m == null) {
            return out;
        }
        if (perObject(m) || sigma(m)) {
            out.add(HELPERS);
        }
        if (perObject(m)) {
            out.add(perObjectTemplate(m));
        }
        if (sigma(m)) {
            out.add(sigmaTemplate(m));
        }
        if (monitored(m)) {
            out.add("#include <string>\nstd::string " + reportName(m) + "();\nvoid " + resetName(m) + "();\n");
        }
        return out;
    }

    /** The overload that takes collections: one call of the pipeline per object. */
    private static String perObjectTemplate(RootPipelineManifest m) {
        final boolean filter = m.kind == RootPipelineManifest.Kind.FILTER;
        final String element = filter ? "int" : "double";
        final List<RootPipelineManifest.Input> in = m.inputs;
        final StringBuilder t = new StringBuilder(1024);
        t.append("#ifndef SPHERE_EACH_").append(m.name).append('\n');
        t.append("#define SPHERE_EACH_").append(m.name).append('\n');
        t.append("template <");
        final StringBuilder all = new StringBuilder();
        for (int i = 0; i < in.size(); i++) {
            t.append("typename SphereA").append(i).append(", ");
            all.append(i > 0 ? ", " : "").append("SphereA").append(i);
        }
        t.append("typename = std::enable_if_t<sphere_forge::any_seq_v<").append(all).append(">>>\n");
        t.append("ROOT::RVec<").append(element).append("> ").append(m.name).append('(');
        for (int i = 0; i < in.size(); i++) {
            t.append(i > 0 ? ", " : "").append("const SphereA").append(i).append(" &").append(in.get(i).name());
        }
        t.append(") {\n");
        t.append("  std::size_t sphere_n = 0;\n");
        t.append("  const char *sphere_first = nullptr;\n");
        for (RootPipelineManifest.Input input : in) {
            t.append("  sphere_forge::size_into(\"").append(m.name).append("\", \"").append(input.name())
             .append("\", ").append(input.name()).append(", sphere_n, sphere_first);\n");
        }
        t.append("  ROOT::RVec<").append(element).append("> sphere_out(sphere_n);\n");
        t.append("  for (std::size_t sphere_i = 0; sphere_i < sphere_n; ++sphere_i) {\n");
        t.append("    sphere_out[sphere_i] = ").append(m.name).append('(');
        for (int i = 0; i < in.size(); i++) {
            t.append(i > 0 ? ",\n        " : "").append("static_cast<").append(bare(in.get(i).type()))
             .append(">(sphere_forge::at(").append(in.get(i).name()).append(", sphere_i))");
        }
        t.append(')').append(filter ? " ? 1 : 0" : "").append(";\n");
        t.append("  }\n");
        t.append("  return sphere_out;\n");
        t.append("}\n");
        t.append("#endif\n");
        return t.toString();
    }

    /** The uncertainty: each input moved by its error, the others held, the swings in quadrature. */
    private static String sigmaTemplate(RootPipelineManifest m) {
        final List<RootPipelineManifest.Input> in = m.inputs;
        final List<Varied> moved = varied(m);
        final List<String> extra = new ArrayList<>();
        for (Varied v : moved) {
            if (!v.given()) {
                extra.add(v.error());
            }
        }
        final StringBuilder t = new StringBuilder(1024);
        t.append("#ifndef SPHERE_SIGMA_").append(m.name).append('\n');
        t.append("#define SPHERE_SIGMA_").append(m.name).append('\n');
        t.append("template <");
        for (int i = 0; i < in.size(); i++) {
            t.append(i > 0 ? ", " : "").append("typename SphereA").append(i);
        }
        for (int i = 0; i < extra.size(); i++) {
            t.append(", typename SphereE").append(i);
        }
        t.append(">\nauto ").append(sigmaName(m)).append('(');
        for (int i = 0; i < in.size(); i++) {
            t.append(i > 0 ? ", " : "").append("const SphereA").append(i).append(" &").append(in.get(i).name());
        }
        for (int i = 0; i < extra.size(); i++) {
            t.append(", const SphereE").append(i).append(" &").append(extra.get(i));
        }
        t.append(") {\n");
        t.append("  const auto sphere_at = ").append(call(m)).append(";\n");
        t.append("  return sphere_forge::quadrature(");
        for (int k = 0; k < moved.size(); k++) {
            final Varied v = moved.get(k);
            t.append(k > 0 ? "," : "").append("\n      sphere_forge::swing(")
             .append(shifted(m, v, " + ")).append(", sphere_at, ").append(shifted(m, v, " - ")).append(')');
        }
        t.append(");\n");
        t.append("}\n");
        t.append("#endif\n");
        return t.toString();
    }

    /** The pipeline called with one input moved by its error. */
    private static String shifted(RootPipelineManifest m, Varied v, String sign) {
        final StringBuilder out = new StringBuilder(m.name).append('(');
        for (int i = 0; i < m.inputs.size(); i++) {
            final String name = m.inputs.get(i).name();
            out.append(i > 0 ? ", " : "");
            out.append(name.equals(v.input()) ? name + sign + v.error() : name);
        }
        return out.append(')').toString();
    }

    // ---- the cuts -----------------------------------------------------------------

    /** Where the user's function is in a source, and the conditions of its one return. */
    private record Found(String text, int[] starts, int[] ends) { }

    /**
     * The conditions a filter returns, joined by &&, when its body ends on
     * that one return: the steps of a cutflow, which only need marking. Empty
     * when the body returns more than once, mixes || or ?: with them, or
     * already marks them all.
     */
    public static List<String> cutsIn(String source, RootPipelineManifest m) {
        final List<String> out = new ArrayList<>();
        final Found found = find(source, m);
        if (found == null) {
            return out;
        }
        boolean unmarked = false;
        for (int k = 0; k < found.starts().length; k++) {
            final String part = found.text().substring(found.starts()[k], found.ends()[k]).strip();
            // Shown without its comments, as the preprocessor will label it.
            out.add(part.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ")
                        .replaceAll("\\s+", " ").strip());
            unmarked |= !part.startsWith("SPHERE_CUT");
        }
        return unmarked ? out : new ArrayList<>();
    }

    /** The source with every condition of that return wrapped in SPHERE_CUT, or null when there is none. */
    public static String markCuts(String source, RootPipelineManifest m) {
        final Found found = find(source, m);
        if (found == null) {
            return null;
        }
        final StringBuilder out = new StringBuilder(found.text());
        boolean changed = false;
        for (int k = found.starts().length - 1; k >= 0; k--) {
            final int from = found.starts()[k];
            final int to = found.ends()[k];
            final String part = found.text().substring(from, to);
            final String stripped = part.strip();
            if (stripped.startsWith("SPHERE_CUT")) {
                continue;
            }
            final int lead = part.indexOf(stripped);
            out.replace(from + lead, from + lead + stripped.length(), "SPHERE_CUT(" + stripped + ")");
            changed = true;
        }
        return changed ? out.toString() : null;
    }

    /** The return to split, located in the whole source. */
    private static Found find(String source, RootPipelineManifest m) {
        if (source == null || m == null || !perEntry(m)) {
            return null;
        }
        final int mark = source.indexOf(RootPipelineTemplates.BODY_MARK);
        final int from = mark < 0 ? 0 : source.indexOf('\n', mark) + 1;
        int to = source.indexOf(RootPipelineTemplates.TAIL_MARK, Math.max(from, 0));
        if (to < 0) {
            to = source.length();
        }
        final String code = RootPipelineStarts.blanked(source);
        final String opening = m.writtenSignature() + " {";
        final int at = source.indexOf(opening, from);
        if (at < 0 || at >= to) {
            return null;
        }
        final int open = at + opening.length() - 1;
        final int close = RootPipelineStarts.matching(code, open);
        if (close < 0 || close > to) {
            return null;
        }
        // One return, the last statement of the body.
        final java.util.regex.Matcher word = java.util.regex.Pattern.compile("\\breturn\\b")
            .matcher(code.substring(open + 1, close));
        int returns = 0;
        int where = -1;
        while (word.find()) {
            returns++;
            where = open + 1 + word.start();
        }
        if (returns != 1) {
            return null;
        }
        final int start = where + "return".length();
        final int semicolon = code.indexOf(';', start);
        if (semicolon < 0 || semicolon > close || !code.substring(semicolon + 1, close).isBlank()) {
            return null;
        }
        // Split on the && at depth zero; || ?: , or an assignment there would change
        // the meaning of a split, so the return is left alone.
        final List<Integer> starts = new ArrayList<>();
        final List<Integer> ends = new ArrayList<>();
        int depth = 0;
        int partFrom = start;
        for (int k = start; k < semicolon; k++) {
            final char ch = code.charAt(k);
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
            } else if (depth == 0) {
                if (ch == '&' && k + 1 < semicolon && code.charAt(k + 1) == '&') {
                    starts.add(partFrom);
                    ends.add(k);
                    partFrom = k + 2;
                    k++;
                } else if (ch == '|' && k + 1 < semicolon && code.charAt(k + 1) == '|') {
                    return null;
                } else if (ch == '?' || ch == ',') {
                    return null;
                }
            }
        }
        starts.add(partFrom);
        ends.add(semicolon);
        if (starts.size() < 2) {
            return null;
        }
        final int[] s = new int[starts.size()];
        final int[] e = new int[ends.size()];
        for (int k = 0; k < s.length; k++) {
            s[k] = starts.get(k);
            e[k] = ends.get(k);
            if (source.substring(s[k], e[k]).isBlank()) {
                return null;
            }
        }
        return new Found(source, s, e);
    }

    // ---- what the form suggests -------------------------------------------------

    /** What a suggestion would do, when the user takes it. */
    public enum Act { PER_OBJECT, SIGMA, COUNT_CUTS, MARK_CUTS }

    /** One thing worth saying about the extras -- in a line, then in full -- and what taking it does. */
    public record Suggestion(String text, String detail, String button, Act act) { }

    /**
     * What the form suggests from the inputs and the code, the most useful
     * first: an input named like a collection branch asks for per object, an
     * input with its error beside it for the uncertainty, and a filter's
     * && return for a cutflow. Null when there is nothing to say.
     *
     * @param source the pipeline's source as it is on disk, or null for one not saved yet
     */
    public static Suggestion suggest(RootPipelineManifest m, String source) {
        if (m == null || !perEntry(m)) {
            return null;
        }
        if (!m.each && whyNotPerObject(m) == null) {
            for (RootPipelineManifest.Input input : m.inputs) {
                final java.util.regex.Matcher branch =
                    java.util.regex.Pattern.compile("^([A-Z][A-Za-z0-9]*)_[A-Za-z]\\w*$").matcher(input.name());
                if (branch.matches() && !SCALAR_BRANCHES.contains(branch.group(1))
                        && !branch.group(1).endsWith("MET")) {
                    final String object = branch.group(1).toLowerCase(Locale.ROOT);
                    final String it = m.name == null || m.name.isBlank() ? "it" : m.name;
                    return new Suggestion(input.name() + " reads like one value per " + object
                        + ": per object calls " + it + " on each " + object + " at once.",
                        "<html>A branch such as " + input.name() + " holds one value per " + object
                        + ". With per object,<br><b>" + escape(call(m)) + "</b> takes those collections"
                        + " and answers one value per " + object + ", as a ROOT::RVec.</html>",
                        "per object", Act.PER_OBJECT);
                }
            }
        }
        if (!m.sigma && whyNotSigma(m) == null) {
            for (Varied v : varied(m)) {
                if (v.given()) {
                    return new Suggestion(v.error() + " sits beside " + v.input() + ": the uncertainty moves "
                        + v.input() + " by it, in " + sigmaName(m) + ".",
                        "<html><b>" + escape(sigmaCall(m)) + "</b><br>moves each input by its error, the others"
                        + " held, and adds the swings in quadrature.</html>",
                        "uncertainty", Act.SIGMA);
                }
            }
        }
        if (m.kind == RootPipelineManifest.Kind.FILTER && source != null) {
            final List<String> cuts = cutsIn(source, m);
            if (!cuts.isEmpty()) {
                final StringBuilder list = new StringBuilder("<html>");
                for (String cut : cuts) {
                    list.append("SPHERE_CUT(").append(escape(cut)).append(")<br>");
                }
                list.append("each wrapped where it stands in the code: the version before is kept.</html>");
                return monitored(m)
                    ? new Suggestion(cuts.size() + " cuts in its return: marked, the report gives their cutflow.",
                        list.toString(), "mark the cuts", Act.MARK_CUTS)
                    : new Suggestion(cuts.size() + " cuts in its return: the monitor can count them, as a cutflow.",
                        list.toString(), "count the cuts", Act.COUNT_CUTS);
            }
        }
        return null;
    }

    /** NanoAOD's scalar branches: a name like PV_npvs or HLT_IsoMu24 is one value per event. */
    private static final Set<String> SCALAR_BRANCHES = Set.of(
        "PV", "HLT", "Flag", "L1", "LHE", "Pileup", "Generator", "Rho", "GenVtx", "BeamSpot");

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
