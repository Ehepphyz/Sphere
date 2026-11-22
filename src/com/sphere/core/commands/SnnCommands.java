package com.sphere.core.commands;

import com.sphere.core.bridge.Bridge;
import com.sphere.core.snn.Snn;
import com.sphere.utils.AppLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Sphere Neural Network: the {@code :snn} commands. Every module of the machine is a neuron, the ways data goes
 * between them are synapses; the cortex (sphere_snn.py) finds them on both sides of WSL, chooses installations that
 * can share a program, writes how C++, Python, Julia and ROOT reach each one, and runs every neuron and synapse for real.
 */
final class SnnCommands {

    private SnnCommands() {
    }

    static void register() {
        CommandDefinitions.register(":snn", "The Sphere Neural Network: every module (ROOT, Geant4, FastJet, fjcontrib, HepMC3, LHAPDF, Pythia8, Rivet, PyTorch, TensorFlow, JAX, pyhf...) a neuron wired to every other, reached from C++, Python and Julia. What it is and how to use it", SnnCommands::overview);
        CommandDefinitions.register(":snn status", "The neurons of the machine, side by side of WSL: version, installation, the languages that reach each", SnnCommands::status);
        CommandDefinitions.register(":snn detect", "Find the neurons again (both sides of WSL), write every way in inside Sphere (snn-config, CMake, pkg-config, snn_load.C, what its terminals read) and settings.conf [SNN]", SnnCommands::detect);
        CommandDefinitions.register(":snn check", "Run every neuron and synapse for real, in C++, Cling, Python, Julia and through the gateway, numbers held across languages. Usage: :snn check [--quick] [--only cpp,python,cling,julia,gateway]", SnnCommands::check);
        CommandDefinitions.register(":snn matrix", "The last check: each neuron in each language, each synapse, what shares one program", SnnCommands::matrix);
        CommandDefinitions.register(":snn env", "The environment a program holding some neurons is given (all of them by default). Usage: :snn env [bash|cmd|powershell] [neurons]", SnnCommands::env);
        CommandDefinitions.register(":snn config", "The C++ flags of a program holding neurons together, the installations chosen and why. Usage: :snn config <neurons>", SnnCommands::config);
        CommandDefinitions.register(":snn route", "The synapses carrying data from one neuron to another. Usage: :snn route <from> <to>", SnnCommands::route);
        CommandDefinitions.register(":snn code", "A program to start from, using several neurons at once. Usage: :snn code <cpp|cmake|python|julia|root> [file]", SnnCommands::code);
        CommandDefinitions.register(":snn run", "Run a program with the neurons it uses, read from its source: C++ built with them (a failed build read, fixed and built again), a ROOT macro given them, Python and Julia run where they are, on either side of WSL. Usage: :snn run [--why] <file> [arguments]", SnnCommands::run);
        CommandDefinitions.register(":snn declare", "Write settings.conf [SNN] again from what Sphere found: every module, the installation it is taken from and the others found; your own lines are kept as they are", SnnCommands::declare);
        CommandDefinitions.register(":snn index", "Read the API of every neuron (headers, classes, namespaces, Python modules) on both sides of WSL: what heals builds and what the neural model learns from", SnnCommands::index);
        CommandDefinitions.register(":snn train", "Train the network's neural model on every API indexed (an identifier's spelling in, its module out), exported as a graph Sphere runs itself", SnnCommands::train);
        CommandDefinitions.register(":snn learn", "Fine-tune the neural model on your own programs (your classes deriving from a module's, the fixes the healer made) with Sphere's own trainer", SnnCommands::learn);
        CommandDefinitions.register(":snn predict", "What the neural model reads in identifiers or in a file: the module each one belongs to. Usage: :snn predict <file|identifiers>", SnnCommands::predict);
        CommandDefinitions.register(":snn suggest", "What a program holds and will likely want next: the modules it names, those the model reads in it, the neighbours they fired with before, and the code joining them. Usage: :snn suggest <file>", SnnCommands::suggest);
        CommandDefinitions.register(":snn memory", "What the network learned from what it ran: its strongest synapses, the fixes it remembers", SnnCommands::memory);
    }

    private static void declare(String input, CommandExecutionContext ctx) {
        MlCommands.background("snn-declare", () -> {
            try {
                Snn.declare(SnnCommands::line);
            } catch (IOException e) {
                AppLogger.error("snn declare: " + e.getMessage());
            }
        });
    }

    private static void index(String input, CommandExecutionContext ctx) {
        MlCommands.background("snn-index", () -> {
            try {
                for (String d : Snn.distros()) {
                    Snn.cortex(List.of("index", "--force"), d, 1800, SnnCommands::line);
                }
                Snn.cortex(List.of("index", "--force"), null, 900, SnnCommands::line);
            } catch (IOException e) {
                AppLogger.error("snn index: " + e.getMessage());
            }
        });
    }

    private static void train(String input, CommandExecutionContext ctx) {
        MlCommands.background("snn-train", () -> {
            try {
                AppLogger.info("Training the network's neural model on every API indexed (a few minutes)...");
                final int status = Snn.cortex(List.of("train"), cppSide(), 3600, SnnCommands::line);
                if (status == 0) {
                    AppLogger.success("Trained: ':snn predict <file>' reads code with it, Java's NeuralGraph running it.");
                }
            } catch (IOException e) {
                AppLogger.error("snn train: " + e.getMessage());
            }
        });
    }

    private static void learn(String input, CommandExecutionContext ctx) {
        MlCommands.background("snn-learn", () -> {
            try {
                if (Snn.cortex(List.of("learn-data"), null, 900, SnnCommands::line) != 0) {
                    return;
                }
                final com.sphere.core.ml.GraphTrainer.Result r = com.sphere.core.snn.SnnModel.learn(SnnCommands::line);
                AppLogger.success("Fine-tuned by Sphere on your programs: " + r.path() + " (" + r.rows() + " rows, best epoch "
                    + (r.best() + 1) + ")");
            } catch (IOException | RuntimeException e) {
                AppLogger.error("snn learn: " + e.getMessage());
            }
        });
    }

    private static void predict(String input, CommandExecutionContext ctx) {
        final String rest = args(input, ":snn predict").trim();
        if (rest.isEmpty()) {
            Handlers.usage(":snn predict <file|identifiers>   e.g. :snn predict G4MyCalorimeter TH3Poly ClusterSequenceArea");
            return;
        }
        MlCommands.background("snn-predict", () -> {
            try {
                final java.io.File f = Handlers.resolve(rest);
                final boolean file = !rest.contains(" ") && f.isFile();
                final List<String> tokens = file ? com.sphere.core.snn.SnnModel.tokens(Files.readString(f.toPath()))
                    : List.of(rest.split("\\s+"));
                AppLogger.result("The network's neural model (" + com.sphere.core.snn.SnnModel.which() + ", run by Sphere's NeuralGraph):");
                if (file) {
                    for (Map.Entry<String, List<String>> e : com.sphere.core.snn.SnnModel.read(Files.readString(f.toPath()), 0.8).entrySet()) {
                        line(String.format(Locale.ROOT, "  %-12s %s", e.getKey(), String.join(", ", e.getValue().subList(0, Math.min(8, e.getValue().size())))));
                    }
                    return;
                }
                for (Map.Entry<String, List<com.sphere.core.snn.SnnModel.Guess>> e : com.sphere.core.snn.SnnModel.predict(tokens, 3).entrySet()) {
                    final StringBuilder s = new StringBuilder();
                    for (com.sphere.core.snn.SnnModel.Guess g : e.getValue()) {
                        s.append(String.format(Locale.ROOT, "%-12s %.3f   ", g.neuron(), g.p()));
                    }
                    line(String.format(Locale.ROOT, "  %-26s %s", e.getKey(), s));
                }
            } catch (IOException | RuntimeException e) {
                AppLogger.error("snn predict: " + e.getMessage());
            }
        });
    }

    private static void suggest(String input, CommandExecutionContext ctx) {
        final String rest = args(input, ":snn suggest").trim();
        if (rest.isEmpty()) {
            Handlers.usage(":snn suggest <file>");
            return;
        }
        final java.io.File f = Handlers.resolve(rest);
        final String name = f.getName().toLowerCase(Locale.ROOT);
        final boolean here = name.endsWith(".py") || name.endsWith(".jl");
        MlCommands.background("snn-suggest", () -> {
            try {
                AppLogger.result("What " + f.getName() + " holds and will likely want next:");
                Snn.cortex(List.of("suggest", here || cppSide() == null ? f.getAbsolutePath() : com.sphere.core.ml.MlLink.wslPath(f.toPath())),
                    here ? null : cppSide(), 300, SnnCommands::line);
            } catch (IOException e) {
                AppLogger.error("snn suggest: " + e.getMessage());
            }
        });
    }

    private static void memory(String input, CommandExecutionContext ctx) {
        MlCommands.background("snn-memory", () -> {
            try {
                for (String d : Snn.distros()) {
                    AppLogger.result("Learned in WSL " + d + ":");
                    Snn.cortex(List.of("memory"), d, 120, SnnCommands::line);
                }
                AppLogger.result("Learned on " + Snn.side() + ":");
                Snn.cortex(List.of("memory"), null, 120, SnnCommands::line);
            } catch (IOException e) {
                AppLogger.error("snn memory: " + e.getMessage());
            }
        });
    }

    private static void run(String input, CommandExecutionContext ctx) {
        final String rest = args(input, ":snn run").trim();
        if (rest.isEmpty()) {
            Handlers.usage(":snn run [--why] <program.cpp|macro.C|script.py|script.jl> [arguments]");
            return;
        }
        final List<String> cmd = new ArrayList<>(List.of("run"));
        for (String w : rest.split("\\s+")) {
            cmd.add(w.startsWith("--") || cmd.size() > 1 && !cmd.get(cmd.size() - 1).startsWith("--") ? w
                : Handlers.resolve(w).getAbsolutePath());
        }
        MlCommands.background("snn-run", () -> {
            try {
                final int status = Snn.cortex(cmd, null, 86_400, SnnCommands::line);
                if (status != 0) {
                    AppLogger.warn("snn run: the program ended with status " + status);
                }
            } catch (IOException e) {
                AppLogger.error("snn run: " + e.getMessage());
            }
        });
    }

    private static String args(String input, String command) {
        return Handlers.args(input, command);
    }

    private static void line(String s) {
        AppLogger.raw(s);
    }

    /** The side the C++ neurons are on: WSL's first distribution from Windows, this one elsewhere. */
    private static String cppSide() {
        final List<String> d = Snn.distros();
        return d.isEmpty() ? null : d.get(0);
    }

    private static void overview(String input, CommandExecutionContext ctx) {
        AppLogger.result("The Sphere Neural Network: every module of this machine a neuron, wired to every other.");
        line("  Neurons   ROOT (RDataFrame, RooFit, TMVA, Minuit2, GenVector, TGeo), Geant4, CLHEP, VecGeom, FastJet, fjcontrib, HepMC3,");
        line("            LHAPDF, Pythia8, YODA, Rivet, Herwig, MadGraph, Delphes, PyTorch/libtorch, TensorFlow/C API, Keras, JAX, pyhf,");
        line("            iminuit, uproot, awkward, vector..., Julia's JetReconstruction, Sphere's Java translations and graphs");
        line("  Synapses  HepMC3 -> FastJet -> fjcontrib -> ROOT -> PyTorch / TensorFlow / JAX, histograms -> pyhf, Geant4 -> TGeo (GDML),");
        line("            HepMC3 -> Geant4 primaries, Pythia8 -> HepMC3, LHAPDF -> Pythia8, HepMC3 -> Rivet -> YODA, CLHEP <-> HepMC3/FastJet...");
        line("  From C++       g++ app.cpp $(snn-config --cflags --libs hepmc3 fastjet root)   #include \"sphere_snn.hpp\"");
        line("                 find_package(SphereNN COMPONENTS fastjet root geant4)  ->  SNN::fastjet, SNN::all");
        line("  From ROOT      .x $SPHERE_SNN/<side>/snn_load.C(\"fastjet fjcontrib geant4\")");
        line("  From Python    import sphere_snn as snn; ROOT, fj, torch = snn.use(\"root\", \"fastjet\", \"torch\"); g = snn.cpp(\"geant4\")");
        line("  From Julia     include(joinpath(ENV[\"SPHERE_BRIDGE_LIB\"], \"SphereSNN.jl\")); SphereSNN.jets(px, py, pz, E; R = 0.4)");
        line("  Anywhere       the gateway: code naming a neuron its Python lacks goes to the Python that has it, either side of WSL");
        line("  Configured     in settings.conf [SNN] alone: one key a module, the installation it is taken from (a path, a tree's");
        line("                 name, a version, $ROOT to follow ROOT); empty switches it off. Nothing is installed on the machine:");
        line("                 what Sphere starts is given the network, the machine's shells, Pythons and Julias are left as they are.");
        line("  It learns      programs that work strengthen the synapses of their modules (Hebb); a failed build is read, fixed");
        line("                 and built again, the fix remembered; a neural model trained on every API reads code in any language.");
        line("  :snn status | detect | declare | check [--quick] | matrix | config <neurons> | route <a> <b> | code <language>");
        line("  :snn run <file> | suggest <file> | predict <file|names> | index | train | learn | memory");
        line("  One program holds one copy of a library: the installations are chosen together (Rivet brings MadGraph's FastJet,");
        line("  Geant4 its own CLHEP), and a second copy of a library in a Python process is refused with the way round it.");
        line("  Folder: " + Snn.folder() + "   Settings: " + Path.of("settings.conf").toAbsolutePath());
    }

    private static void status(String input, CommandExecutionContext ctx) {
        final List<String> sides = Snn.sides();
        if (sides.isEmpty()) {
            AppLogger.warn("The network has not been seen yet: ':snn detect' (it also runs by itself when an engine starts).");
            return;
        }
        for (String side : sides) {
            final List<Snn.Neuron> all = Snn.neurons(side);
            final Map<String, List<Snn.Neuron>> by = new LinkedHashMap<>();
            for (Snn.Neuron n : all) {
                by.computeIfAbsent(n.side(), k -> new ArrayList<>()).add(n);
            }
            if (!by.containsKey(side)) {
                continue;
            }
            final List<Snn.Neuron> mine = by.get(side);
            AppLogger.result("Side " + side + ": " + mine.size() + " neurons");
            for (Snn.Neuron n : mine) {
                line(String.format(Locale.ROOT, "  %-18s %-22s %-28s %s", n.id(), cut(n.version(), 22), n.languages(), n.family()));
            }
        }
        line("  ':snn check' runs them all; ':snn matrix' shows the last run.");
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "~";
    }

    private static void detect(String input, CommandExecutionContext ctx) {
        MlCommands.background("snn-detect", () -> {
            try {
                AppLogger.info("Finding the neurons (" + Snn.side() + (Snn.distros().isEmpty() ? "" : " and WSL " + String.join(", ", Snn.distros())) + ")...");
                Snn.refresh(true, SnnCommands::line);
                AppLogger.success("The network is written in " + Snn.folder() + ": ':snn status' lists it.");
            } catch (IOException e) {
                AppLogger.error("snn: " + e.getMessage());
            }
        });
    }

    private static void check(String input, CommandExecutionContext ctx) {
        final String rest = args(input, ":snn check");
        final List<String> flags = new ArrayList<>(List.of("check"));
        for (String w : rest.split("\\s+")) {
            if (!w.isBlank()) {
                flags.add(w);
            }
        }
        MlCommands.background("snn-check", () -> {
            try {
                final String wsl = cppSide();
                if (wsl != null) {
                    AppLogger.info("Generators, C++, Cling, Python and Java in WSL " + wsl + " (programs compile one at a time, MadGraph and Herwig run once: minutes the first time)...");
                    Snn.cortex(only(flags, "tool,cpp,python,cling,java"), wsl, 7200, SnnCommands::line);
                    AppLogger.info("From Windows: its Python, Julia and the gateway into WSL...");
                    Snn.cortex(only(flags, "python,julia,gateway"), null, 3600, SnnCommands::line);
                } else {
                    Snn.cortex(flags, null, 7200, SnnCommands::line);
                }
                AppLogger.success("Checked: ':snn matrix' shows it again.");
            } catch (IOException e) {
                AppLogger.error("snn check: " + e.getMessage());
            }
        });
    }

    /** The flags of a check, its --only kept to what a side can run. */
    private static List<String> only(List<String> flags, String allowed) {
        final List<String> out = new ArrayList<>();
        String asked = null;
        for (int k = 0; k < flags.size(); k++) {
            final String f = flags.get(k);
            if (f.equals("--only") && k + 1 < flags.size()) {
                asked = flags.get(++k);
            } else if (f.startsWith("--only=")) {
                asked = f.substring(7);
            } else {
                out.add(f);
            }
        }
        final List<String> keep = new ArrayList<>();
        for (String a : allowed.split(",")) {
            if (asked == null || List.of(asked.split(",")).contains(a)) {
                keep.add(a);
            }
        }
        out.add("--only");
        out.add(keep.isEmpty() ? "none" : String.join(",", keep));
        return out;
    }

    private static void matrix(String input, CommandExecutionContext ctx) {
        MlCommands.background("snn-matrix", () -> {
            try {
                final String wsl = cppSide();
                if (wsl != null) {
                    Snn.cortex(List.of("matrix"), wsl, 300, SnnCommands::line);
                }
                Snn.cortex(List.of("matrix"), null, 300, SnnCommands::line);
            } catch (IOException e) {
                AppLogger.error("snn matrix: " + e.getMessage());
            }
        });
    }

    private static void env(String input, CommandExecutionContext ctx) {
        final String[] words = args(input, ":snn env").split("\\s+");
        final String shell = words.length > 0 && !words[0].isBlank() ? words[0].toLowerCase(Locale.ROOT) : "";
        final List<String> cmd = new ArrayList<>(List.of("env"));
        final boolean inWsl = cppSide() != null && (shell.equals("bash") || shell.equals("wsl") || shell.equals("sh"));
        if (!shell.isEmpty()) {
            cmd.add(inWsl ? "sh" : shell);
        }
        for (int k = 1; k < words.length; k++) {
            cmd.add(words[k]);
        }
        MlCommands.background("snn-env", () -> {
            try {
                Snn.cortex(cmd, inWsl ? cppSide() : null, 300, SnnCommands::line);
            } catch (IOException e) {
                AppLogger.error("snn env: " + e.getMessage());
            }
        });
    }

    private static void config(String input, CommandExecutionContext ctx) {
        final String rest = args(input, ":snn config").trim();
        if (rest.isEmpty()) {
            Handlers.usage(":snn config <neurons>   e.g. :snn config hepmc3 fastjet fjcontrib root geant4");
            return;
        }
        final List<String> names = List.of(rest.split("\\s+"));
        MlCommands.background("snn-config", () -> {
            try {
                final List<String> why = new ArrayList<>(List.of("config", "--why"));
                why.addAll(names);
                final List<String> flags = new ArrayList<>(List.of("config", "--cflags", "--libs"));
                flags.addAll(names);
                AppLogger.result("Installations chosen together for: " + rest);
                Snn.cortex(why, cppSide(), 300, s -> line("  " + s));
                AppLogger.result("Flags (snn-config --cflags --libs " + rest + "):");
                Snn.cortex(flags, cppSide(), 300, SnnCommands::line);
            } catch (IOException e) {
                AppLogger.error("snn config: " + e.getMessage());
            }
        });
    }

    private static void route(String input, CommandExecutionContext ctx) {
        final String[] w = args(input, ":snn route").trim().split("\\s+");
        if (w.length != 2 || w[0].isBlank()) {
            Handlers.usage(":snn route <from> <to>   e.g. :snn route hepmc3 pyhf");
            return;
        }
        MlCommands.background("snn-route", () -> {
            try {
                Snn.cortex(List.of("route", w[0], w[1]), null, 300, SnnCommands::line);
            } catch (IOException e) {
                AppLogger.error("snn route: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------
    // Programs to start from
    // ------------------------------------------------------------------

    private static final String CPP = """
        // snn_app.cpp -- the Sphere Neural Network from C++: HepMC3 -> FastJet -> fjcontrib -> ROOT, Geant4 and pyhf beside.
        // Build where the neurons are (WSL), with the installations chosen together:
        //     g++ snn_app.cpp -o snn_app $(snn-config --cflags --libs hepmc3 fastjet fjcontrib root geant4)
        //     env $(snn-config --env geant4) ./snn_app
        #include "sphere_snn.hpp"

        #include <G4NistManager.hh>
        #include <G4SystemOfUnits.hh>
        #include <HepMC3/GenVertex.h>
        #include <TFile.h>
        #include <fastjet/ClusterSequence.hh>
        #include <fastjet/contrib/SoftDrop.hh>
        #include <cmath>
        #include <iostream>
        #include <memory>

        int main() {
            namespace snn = sphere::snn;
            std::cout << snn::neurons().size() << " neurons on " << snn::side() << "\\n";

            // An event of HepMC3: two sprays of pions and a neutrino
            HepMC3::GenEvent event(HepMC3::Units::GEV, HepMC3::Units::MM);
            auto v = std::make_shared<HepMC3::GenVertex>();
            for (int k = 0; k < 40; ++k) {
                const double s = k % 2 ? 1.0 : -1.0, px = s * (30 + k % 5), py = s * (8 + k % 3), pz = s * (20 + k % 7);
                v->add_particle_out(std::make_shared<HepMC3::GenParticle>(HepMC3::FourVector(px, py, pz, std::sqrt(px * px + py * py + pz * pz + 0.0195)), 211, 1));
            }
            v->add_particle_out(std::make_shared<HepMC3::GenParticle>(HepMC3::FourVector(5, 5, 5, std::sqrt(75.0)), 12, 1));
            event.add_vertex(v);

            // HepMC3 -> FastJet -> fjcontrib
            const auto particles = snn::pseudojets(event);               // final state, visible: the neutrino is left out
            fastjet::ClusterSequence cs(particles, fastjet::JetDefinition(fastjet::antikt_algorithm, 0.4));
            const auto jets = fastjet::sorted_by_pt(cs.inclusive_jets(5.0));
            fastjet::contrib::SoftDrop sd(0.0, 0.1);
            std::vector<double> msd;
            for (const auto& j : jets) msd.push_back(sd(j).m());

            // FastJet -> ROOT
            TFile file("snn_jets.root", "RECREATE");
            snn::JetTree tree("jets", {"msd"});
            tree.fill(jets, {{"msd", msd}});
            file.Write();

            // Geant4 in the same program
            const auto* lead = G4NistManager::Instance()->FindOrBuildMaterial("G4_Pb");
            std::cout << jets.size() << " jets; G4_Pb X0 = " << lead->GetRadlen() / cm << " cm\\n";

            // pyhf through the gateway (it stays in its Python)
            std::cout << "CLs = " << snn::cls({5, 10}, {50, 60}, {7, 8}, {53, 65}) << "\\n";
        }
        """;

    private static final String CMAKE = """
        # CMakeLists.txt -- the Sphere Neural Network from CMake. The installations of the components are chosen together.
        #     cmake -B build -DCMAKE_PREFIX_PATH=$SPHERE_SNN/wsl-Debian/cmake && cmake --build build
        cmake_minimum_required(VERSION 3.16)
        project(snn_app CXX)
        find_package(SphereNN REQUIRED COMPONENTS hepmc3 fastjet fjcontrib root geant4)
        add_executable(snn_app snn_app.cpp)
        target_link_libraries(snn_app PRIVATE SNN::all)
        set_property(TARGET snn_app PROPERTY CXX_STANDARD ${SphereNN_CXX_STANDARD})
        message(STATUS "Run with: ${SphereNN_RUNTIME_ENV}")
        """;

    private static final String PYTHON = """
        \"\"\"snn_app.py -- the Sphere Neural Network from Python: ROOT, FastJet and fjcontrib, Geant4, PyTorch, TensorFlow and pyhf
        in one process. Run it with the Python that holds them (the network's Python, ':snn status').\"\"\"
        import sphere_snn as snn

        ROOT, fastjet, torch, tf, pyhf = snn.use("root", "fastjet", "torch", "tensorflow", "pyhf")
        gbl = snn.cpp("fjcontrib", "geant4")                       # C++ neurons through Cling, on the same libraries

        # Particles -> FastJet (Python) -> fjcontrib (C++, through Cling)
        particles = [fastjet.PseudoJet(30.0 + k % 5, 8.0 + k % 3, 20.0 + k % 7, 40.0 + k % 9) for k in range(20)]
        jets = fastjet.sorted_by_pt(fastjet.ClusterSequence(particles, fastjet.JetDefinition(fastjet.antikt_algorithm, 0.4)).inclusive_jets())
        fj = gbl.fastjet
        v = gbl.std.vector[fj.PseudoJet]()
        for p in particles:
            v.push_back(fj.PseudoJet(p.px(), p.py(), p.pz(), p.E()))
        cs = fj.ClusterSequence(v, fj.JetDefinition(fj.antikt_algorithm, 0.4))
        groomed = fj.contrib.SoftDrop(0.0, 0.1)(fj.sorted_by_pt(cs.inclusive_jets())[0])
        print(len(jets), "jets; SoftDrop mass", groomed.m())

        # Geant4
        print(snn.g4_material("G4_Pb"))

        # FastJet -> ROOT -> PyTorch and TensorFlow
        snn.jets_to_root("snn_jets.root", [jets])
        x = snn.root_to_torch("snn_jets.root", "jets", ["pt", "eta", "m"])
        y = snn.tensor(x, "tensorflow")
        print(x.shape, float(tf.reduce_sum(y)))

        # ROOT histograms -> pyhf
        hs, hb, hn = (ROOT.TH1D(n, "", 2, 0, 2) for n in ("s", "b", "n"))
        for k, (s, b, db, n) in enumerate(((5, 50, 7, 53), (10, 60, 8, 65))):
            hs.SetBinContent(k + 1, s); hb.SetBinContent(k + 1, b); hb.SetBinError(k + 1, db); hn.SetBinContent(k + 1, n)
        print("CLs", snn.hist_to_pyhf(hs, hb, hn)["cls_obs"])
        """;

    private static final String JULIA = """
        # snn_app.jl -- the Sphere Neural Network from Julia: Julia's own jets held to FastJet's, ROOT, Geant4 and pyhf
        # through the gateway (in the Python that holds them, on either side of WSL).
        include(joinpath(ENV["SPHERE_BRIDGE_LIB"], "SphereSNN.jl"))
        using .SphereSNN

        for n in SphereSNN.neurons()
            println(rpad(n.id, 20), rpad(n.version, 24), n.side)
        end

        px = [30.0 + k % 5 for k in 0:19]; py = [8.0 + k % 3 for k in 0:19]; pz = [20.0 + k % 7 for k in 0:19]
        E = sqrt.(px .^ 2 .+ py .^ 2 .+ pz .^ 2 .+ 0.0195)
        mine = SphereSNN.jets(px, py, pz, E; R = 0.4)                    # JetReconstruction.jl when installed
        theirs = SphereSNN.jets(px, py, pz, E; R = 0.4, via = :fastjet)  # FastJet
        println(length(mine), " jets; leading pt ", mine[1].pt, " against FastJet's ", theirs[1].pt)

        println("ROOT ", SphereSNN.hub("ROOT\\nROOT.gROOT.GetVersion()"))
        println("G4_Pb ", SphereSNN.material("G4_Pb"))
        println("CLs ", SphereSNN.pyhf([5.0, 10.0], [50.0, 60.0], [7.0, 8.0], [53.0, 65.0])["backends"]["numpy"]["cls_obs"])
        println("route: ", [s.id for s in SphereSNN.route("hepmc3", "pyhf")])
        """;

    private static final String ROOT = """
        // snn_macro.C -- the Sphere Neural Network at ROOT's prompt. Give the neurons first, then run it:
        //     root -l -e '.x $SPHERE_SNN/wsl-Debian/snn_load.C("fastjet fjcontrib hepmc3")' snn_macro.C
        void snn_macro() {
            std::vector<fastjet::PseudoJet> particles;
            for (int k = 0; k < 20; ++k) particles.emplace_back(30.0 + k % 5, 8.0 + k % 3, 20.0 + k % 7, 40.0 + k % 9);
            fastjet::ClusterSequence cs(particles, fastjet::JetDefinition(fastjet::antikt_algorithm, 0.4));
            auto jets = fastjet::sorted_by_pt(cs.inclusive_jets());
            fastjet::contrib::SoftDrop sd(0.0, 0.1);
            TH1D h("msd", "SoftDrop mass;m [GeV]", 20, 0, 40);
            for (auto& j : jets) h.Fill(sd(j).m());
            std::cout << jets.size() << " jets, " << sphere::snn::neurons().size() << " neurons on " << sphere::snn::side() << std::endl;
        }
        """;

    private static void code(String input, CommandExecutionContext ctx) {
        final String[] w = args(input, ":snn code").trim().split("\\s+");
        final String what = w.length > 0 ? w[0].toLowerCase(Locale.ROOT) : "";
        final Map<String, String[]> kinds = Map.of(
            "cpp", new String[] {"snn_app.cpp", CPP}, "c++", new String[] {"snn_app.cpp", CPP},
            "cmake", new String[] {"CMakeLists.txt", CMAKE}, "python", new String[] {"snn_app.py", PYTHON},
            "julia", new String[] {"snn_app.jl", JULIA}, "root", new String[] {"snn_macro.C", ROOT});
        if (!kinds.containsKey(what)) {
            Handlers.usage(":snn code <cpp|cmake|python|julia|root> [file]");
            return;
        }
        final String[] k = kinds.get(what);
        final Path target = w.length > 1 ? Handlers.resolve(w[1]).toPath() : Path.of(k[0]).toAbsolutePath();
        try {
            if (Files.exists(target)) {
                AppLogger.warn(target + " exists: it was left as it is (name another file).");
                return;
            }
            Files.writeString(target, k[1], StandardCharsets.UTF_8);
            if (what.equals("cmake") && !Files.exists(target.resolveSibling("snn_app.cpp"))) {
                Files.writeString(target.resolveSibling("snn_app.cpp"), CPP, StandardCharsets.UTF_8);
            }
            AppLogger.success("Written: " + target + (what.equals("cmake") ? " (and snn_app.cpp)" : ""));
            AppLogger.raw("  The bridge's libraries (sphere_snn.hpp, sphere_snn.py, SphereSNN.jl): " + Bridge.libraries());
        } catch (IOException e) {
            AppLogger.error("snn code: " + e.getMessage());
        }
    }
}
