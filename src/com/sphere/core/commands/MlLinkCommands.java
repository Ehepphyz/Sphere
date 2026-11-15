package com.sphere.core.commands;

import com.sphere.core.bridge.Bridge;
import com.sphere.core.bridge.BridgeMlinkCheck;
import com.sphere.core.geantbackend.Geant4Locator;
import com.sphere.core.ml.MlLink;
import com.sphere.core.ml.MlLocator;
import com.sphere.core.rootbackend.RootBackend;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * TensorFlow, PyTorch and pyhf connected to the rest: the {@code :ml link} commands. What sphere_mlink.py serves is
 * for programs (ROOT macros, Geant4 applications, C++, Fortran, Julia, Python); these say what is connected, run code
 * there by hand, print the program to start from, and check that every language gets the same numbers.
 */
final class MlLinkCommands {

    private MlLinkCommands() {
    }

    static void register() {
        CommandDefinitions.register(":ml link", "TensorFlow, PyTorch, JAX and pyhf for every program: ROOT macros, Geant4 applications, C++, Fortran, Julia, Python. What is connected, and how to use it", MlLinkCommands::overview);
        CommandDefinitions.register(":ml link status", "The gateway to TensorFlow, PyTorch and pyhf: running or not, its Python and frameworks, and each engine that can reach it", MlLinkCommands::status);
        CommandDefinitions.register(":ml link start", "Start the gateway (a program that needs it starts it by itself)", MlLinkCommands::start);
        CommandDefinitions.register(":ml link stop", "Stop the gateway, giving back the memory the frameworks hold", MlLinkCommands::stop);
        CommandDefinitions.register(":ml link eval", "Run Python in the gateway, torch, tf, keras, jax, jnp, pyhf and np at hand, and show its value. Usage: :ml link eval <python>", MlLinkCommands::eval);
        CommandDefinitions.register(":ml link env", "What to set for a program Sphere did not start. Usage: :ml link env [cmd|powershell|bash|wsl]", MlLinkCommands::env);
        CommandDefinitions.register(":ml link code", "A program to start from, using TensorFlow, PyTorch and pyhf. Usage: :ml link code <root|geant4|cpp|fortran|julia|python> [file]", MlLinkCommands::code);
        CommandDefinitions.register(":ml link check", "The same requests to TensorFlow, PyTorch and pyhf from every language, the answers held to Java's bit by bit. Usage: :ml link check [--engines java,python,c++,fortran,julia,wsl,root]", MlLinkCommands::check);
    }

    private static String f(String pattern, Object... values) {
        return String.format(Locale.ROOT, pattern, values);
    }

    private static String word(String input, String command) {
        return Handlers.args(input, command);
    }

    private static void overview(String input, CommandExecutionContext ctx) {
        AppLogger.result("TensorFlow, PyTorch, JAX and pyhf, connected to every program Sphere runs:");
        AppLogger.raw("  C++, ROOT, Geant4  #include \"sphere_mlink.hpp\"     sphere::mlink::link().number(\"torch.as_tensor(x).sum().item()\", {{\"x\", x}})");
        AppLogger.raw("  Fortran            use sphere_mlink                 call mlink_put('x', x); call mlink_eval('tf.reduce_sum(x).numpy()', v, ok)");
        AppLogger.raw("  Julia              include(... \"SphereMLink.jl\")    SphereMLink.py(\"torch.as_tensor(x).sum().item()\"; x)");
        AppLogger.raw("  Python             import sphere_mlink as link      link.link().eval(\"tf.reduce_sum(x).numpy()\", x=x)   (PyROOT, a Python without torch)");
        AppLogger.raw("  Any of them        predict(model, arrays): TorchScript, torch.export, Keras, a SavedModel; pyhf(s, b, db, n): CLs, limits");
        AppLogger.raw("  In a program, what is there: detect(); in a shell or CMake: $SPHERE_ML_FRAMEWORKS");
        AppLogger.raw("  :ml link status                         the gateway, its frameworks, each engine that can reach it");
        AppLogger.raw("  :ml link start | stop                   by hand (a program that needs it starts it)");
        AppLogger.raw("  :ml link eval <python>                  Python in the gateway: :ml link eval torch.__version__");
        AppLogger.raw("  :ml link code <root|geant4|cpp|fortran|julia|python> [file]   a program to start from");
        AppLogger.raw("  :ml link env [cmd|powershell|bash|wsl]  what to set for a program Sphere did not start");
        AppLogger.raw("  :ml link check                          every language asked the same, the answers held to Java's bit by bit");
        AppLogger.raw("  Nothing to set up: the gateway starts when needed and is replaced if it dies, a framework this Python lacks is served by the one");
        AppLogger.raw("  that has it, and a model called often runs inside the C++ or Python program once it is proven the same and measured faster.");
    }

    private static void status(String input, CommandExecutionContext ctx) {
        MlCommands.background("ml-link-status", () -> {
            Map<String, String> found = MlLink.detect();
            if (found.isEmpty()) {
                try {
                    MlLink.install(true);
                    found = MlLink.detect();
                } catch (IOException e) {
                    AppLogger.warn("ml link: " + e.getMessage());
                }
            }
            final boolean up = MlLink.alive();
            final Map<String, String> state = MlLink.state();
            if (up) {
                AppLogger.result("Gateway running, pid " + state.get("Pid") + ": " + state.getOrDefault("Python", "?"));
            } else {
                AppLogger.result("Gateway not running: the first program that needs it starts it (or ':ml link start').");
            }
            if (found.isEmpty()) {
                AppLogger.warn("Nothing probed yet: ':ml locate' shows the Pythons; ML_PYTHON in settings.conf chooses one.");
            } else {
                AppLogger.raw("  Python     " + found.getOrDefault("python", "?") + " (" + found.getOrDefault("python_version", "?") + ")");
                AppLogger.raw("  Frameworks " + MlLink.describe(found));
                for (String want : List.of("torch", "tensorflow", "pyhf")) {
                    if (!found.containsKey(want)) {
                        AppLogger.warn(want + " is not in that Python: pip install " + want + " there, or name another as ML_PYTHON in settings.conf.");
                    }
                }
            }
            AppLogger.raw("  Folder     " + MlLink.folder());
            final SettingsManager s = new SettingsManager();
            AppLogger.raw("  Engines that reach it:");
            line("java", "this Sphere", "com.sphere.core.ml.MlLink");
            line("python", Bridge.python(s), "import sphere_mlink");
            line("c++", Bridge.cppCompiler(s), "#include \"sphere_mlink.hpp\"");
            line("fortran", Bridge.fortranCompiler(s), "use sphere_mlink");
            line("julia", Bridge.julia(s), "include(joinpath(ENV[\"SPHERE_BRIDGE_LIB\"], \"SphereMLink.jl\"))");
            line("root", rootState(s), "#include \"sphere_mlink.hpp\" in a macro, or import sphere_mlink in PyROOT");
            line("geant4", geant4State(), "#include \"sphere_mlink.hpp\" in an application (:g4 build gives it the path)");
        });
    }

    private static void line(String engine, String where, String how) {
        AppLogger.raw(f("    %-8s %-52s %s", engine, where == null ? "not found (settings.conf)" : where, how));
    }

    private static String rootState(SettingsManager s) {
        final RootBackend root = RootBackend.getInstance();
        if (root != null && root.isAvailable()) {
            return "engine running";
        }
        return s.isDeclaredEmpty("ROOT_DIR") ? "not set (ROOT_DIR is empty in settings.conf)" : "not running (':root' starts it)";
    }

    private static String geant4State() {
        final Function<String, String> settings = G4Commands.settings();
        try {
            final Geant4Locator.Install g4 = Geant4Locator.best(settings);
            return g4 == null ? "not found (GEANT4_DIR in settings.conf)" : g4.toString();
        } catch (RuntimeException e) {
            return "not found (" + e.getMessage() + ")";
        }
    }

    private static void start(String input, CommandExecutionContext ctx) {
        MlCommands.background("ml-link-start", () -> {
            try {
                AppLogger.info("Starting the gateway...");
                MlLink.install(true);      // settings.conf may have named another Python since
                MlLink.start();
                AppLogger.success("Gateway running: " + MlLink.describe(MlLink.detect()));
            } catch (IOException e) {
                AppLogger.error("ml link: " + e.getMessage());
            }
        });
    }

    private static void stop(String input, CommandExecutionContext ctx) {
        MlLink.stop();
        AppLogger.info("gateway asked to stop");
    }

    private static void eval(String input, CommandExecutionContext ctx) {
        final String code = word(input, ":ml link eval");
        if (code.isBlank()) {
            Handlers.usage(":ml link eval <python>   (torch, tf, keras, jax, jnp, pyhf, np at hand; variables stay for the next one)");
            return;
        }
        MlCommands.background("ml-link-eval", () -> {
            try {
                final MlLink.Reply r = MlLink.eval(code, Map.of());
                if (!r.output().isEmpty()) {
                    AppLogger.raw(r.output().stripTrailing());
                }
                show(r);
            } catch (IOException e) {
                AppLogger.error(e.getMessage());
            }
        });
    }

    private static void show(MlLink.Reply r) throws IOException {
        switch (r.type()) {
            case "none" -> { }
            case "number" -> AppLogger.result(Double.toString(r.number()));
            case "text" -> AppLogger.result(r.text());
            case "array" -> {
                final MlLink.Array a = r.array();
                final int n = Math.min(a.data().length, 12);
                AppLogger.result("shape " + Arrays.toString(a.shape()) + ": " + Arrays.toString(Arrays.copyOf(a.data(), n))
                    + (a.data().length > n ? " ..." : ""));
            }
            default -> r.meta().forEach((k, v) -> {
                if (k.startsWith("R.") || k.startsWith("S.")) {
                    AppLogger.raw("  " + k.substring(2) + " = " + v);
                } else if (k.startsWith("Array.")) {
                    AppLogger.raw("  " + v + " = array of shape [" + r.meta().getOrDefault("Shape." + k.substring(6), "") + "]");
                }
            });
        }
        AppLogger.raw(f("  (%.1f ms)", r.seconds() * 1e3));
    }

    private static void env(String input, CommandExecutionContext ctx) {
        final String shell = word(input, ":ml link env").toLowerCase(Locale.ROOT);
        final Path lib;
        try {
            lib = Bridge.libraries();
        } catch (IOException e) {
            AppLogger.error(e.getMessage());
            return;
        }
        final Path bridge = Bridge.folder(), link = MlLink.folder();
        switch (shell) {
            case "bash" -> {
                AppLogger.raw("export SPHERE_BRIDGE='" + bridge + "' SPHERE_BRIDGE_LIB='" + lib + "' SPHERE_MLINK='" + link + "'");
                AppLogger.raw("export CPLUS_INCLUDE_PATH='" + lib + "' ROOT_INCLUDE_PATH='" + lib + "' PYTHONPATH='" + lib + "'");
            }
            case "wsl" -> {
                final String l = MlLink.wslPath(lib);
                AppLogger.raw("export SPHERE_BRIDGE='" + MlLink.wslPath(bridge) + "' SPHERE_BRIDGE_LIB='" + l + "' SPHERE_MLINK='" + MlLink.wslPath(link) + "'");
                AppLogger.raw("export CPLUS_INCLUDE_PATH='" + l + "' ROOT_INCLUDE_PATH='" + l + "' PYTHONPATH='" + l + "'");
            }
            case "powershell" -> {
                AppLogger.raw("$env:SPHERE_BRIDGE='" + bridge + "'; $env:SPHERE_BRIDGE_LIB='" + lib + "'; $env:SPHERE_MLINK='" + link + "'");
                AppLogger.raw("$env:CPLUS_INCLUDE_PATH='" + lib + "'; $env:ROOT_INCLUDE_PATH='" + lib + "'; $env:PYTHONPATH='" + lib + "'");
            }
            default -> {
                AppLogger.raw("set SPHERE_BRIDGE=" + bridge);
                AppLogger.raw("set SPHERE_BRIDGE_LIB=" + lib);
                AppLogger.raw("set SPHERE_MLINK=" + link);
                AppLogger.raw("set CPLUS_INCLUDE_PATH=" + lib);
                AppLogger.raw("set ROOT_INCLUDE_PATH=" + lib);
                AppLogger.raw("set PYTHONPATH=" + lib);
            }
        }
    }

    private static void code(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":ml link code");
        final String text = switch (a.word(0, "").toLowerCase(Locale.ROOT)) {
            case "root" -> ROOT;
            case "geant4", "g4" -> GEANT4;
            case "cpp", "c++" -> CPP;
            case "fortran" -> FORTRAN;
            case "julia" -> JULIA;
            case "python", "pyroot" -> PYTHON;
            default -> null;
        };
        if (text == null) {
            Handlers.usage(":ml link code <root|geant4|cpp|fortran|julia|python> [file]");
            return;
        }
        if (a.words().size() > 1) {
            final Path file = Handlers.resolve(a.word(1, "")).toPath();
            try {
                if (file.toAbsolutePath().getParent() != null) {
                    Files.createDirectories(file.toAbsolutePath().getParent());
                }
                Files.writeString(file, text);
                AppLogger.success("written: " + file);
            } catch (IOException e) {
                AppLogger.error("cannot write " + file + ": " + e.getMessage());
            }
            return;
        }
        AppLogger.raw(text);
    }

    private static void check(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":ml link check");
        final List<String> engines = a.flags().containsKey("engines")
            ? Arrays.asList(a.flags().get("engines").toLowerCase(Locale.ROOT).split(",")) : BridgeMlinkCheck.ENGINES;
        MlCommands.background("ml-link-check", () -> {
            final RootBackend root = RootBackend.getInstance();
            final Function<String, String> cling = root != null && root.isAvailable()
                ? expression -> root.executeClingAwait(expression, 60000) : null;
            try {
                AppLogger.info("Asking PyTorch, TensorFlow and pyhf the same from " + String.join(", ", engines) + " (the first asks wait for their imports)...");
                final List<BridgeMlinkCheck.Verdict> verdicts = BridgeMlinkCheck.run(engines, new SettingsManager(), cling);
                AppLogger.raw(f("  %-8s %-10s %12s %12s %10s", "engine", "", "identical", "max rel", "ms / call"));
                boolean all = true;
                int ran = 0;
                for (BridgeMlinkCheck.Verdict v : verdicts) {
                    if (!v.ran()) {
                        AppLogger.raw(f("  %-8s not run: %s", v.engine(), v.note()));
                        continue;
                    }
                    ran++;
                    all &= v.identical() == v.values();
                    AppLogger.raw(f("  %-8s %-10s %8d/%-3d %12.2e %10.2f", v.engine(), v.note().length() > 10 ? v.note().substring(0, 10) : v.note(),
                        v.identical(), v.values(), v.maxRelative(), v.ms()));
                }
                if (ran == 0) {
                    AppLogger.warn("no client could be run");
                } else if (all) {
                    AppLogger.success("every client that ran got Java's numbers to the bit");
                } else {
                    AppLogger.error("a client got other numbers than Java's: see above");
                }
            } catch (IOException | RuntimeException e) {
                AppLogger.error("ml link check: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------
    // Programs to start from
    // ------------------------------------------------------------------

    private static final String ROOT = """
        // mlink_demo.C -- PyTorch, TensorFlow and pyhf from a ROOT macro: .x mlink_demo.C
        #include "sphere_mlink.hpp"
        using namespace sphere::mlink;

        void mlink_demo() {
            Frameworks found = detect();
            cout << "reachable: " << found.describe() << endl;

            // pyhf on histograms: bin contents and the background's bin errors
            TH1D sig("sig", "", 2, 0, 2), bkg("bkg", "", 2, 0, 2), dat("dat", "", 2, 0, 2);
            sig.SetBinContent(1, 5);   sig.SetBinContent(2, 10);
            bkg.SetBinContent(1, 50);  bkg.SetBinContent(2, 60);
            bkg.SetBinError(1, 7);     bkg.SetBinError(2, 8);
            dat.SetBinContent(1, 53);  dat.SetBinContent(2, 65);
            Limit l = link().pyhf(sig, bkg, dat, 1.0, 21);          // 21 points of mu: the upper limit too
            cout << "CLs(mu=1) " << l.cls_obs << ", 95% CL limit on mu " << l.upper_limit << endl;

            // any tensor computation, the bins as an array
            Array x(contents(sig));
            cout << "PyTorch: " << link().number("torch.as_tensor(x).sum().item()", {{"x", x}}) << endl;
            cout << "TensorFlow: " << link().number("float(tf.reduce_sum(tf.constant(x)))", {{"x", x}}) << endl;

            // a model trained elsewhere, on a batch of a TTree's rows:
            // Array out = link().predict("model.pt", {{"x", Array(rows, {n, 7})}});
        }
        """;

    private static final String GEANT4 = """
        // A Geant4 event action asking PyTorch at the end of each event. Works from Geant4's worker threads as it is.
        // ':g4 build' gives the compiler the path to sphere_mlink.hpp; for a build by hand, ':ml link env' says what to set.
        #include "G4UserEventAction.hh"
        #include "G4Event.hh"
        #include "G4ios.hh"
        #include "sphere_mlink.hpp"

        class TaggerAction : public G4UserEventAction {
        public:
            void EndOfEventAction(const G4Event* event) override {
                std::vector<double> features = Features(event);     // your own: one row of numbers per event
                using namespace sphere::mlink;
                Array p = link().predict("tagger.pt", {{"x", Array(features, {1, (std::int64_t)features.size()})}});
                G4cout << "event " << event->GetEventID() << ": score " << p[0] << G4endl;
            }

        private:
            std::vector<double> Features(const G4Event*) { return {0, 0, 0}; }
        };

        // A call costs milliseconds at first. predict() then moves the network into the program by itself, once it has been
        // made a graph and proven the same: a SavedModel, a Keras model, a TorchScript or torch.export program.
        """;

    private static final String CPP = """
        // PyTorch, TensorFlow and pyhf from C++: g++ -std=c++17 mlink_demo.cpp -o mlink_demo
        #include "sphere_mlink.hpp"
        #include <iostream>
        using namespace sphere::mlink;

        int main() {
            try {
                std::cout << "reachable: " << detect().describe() << "\\n";
                Array x({1, 2, 3, 4, 5, 6}, {2, 3});
                std::cout << link().number("torch.linalg.norm(torch.as_tensor(x)).item()", {{"x", x}}) << "\\n";
                Array g = link().array("t = torch.tensor(x, requires_grad=True); (t**3).sum().backward(); t.grad", {{"x", x}});
                std::cout << "gradient at (1, 2): " << g.at(1, 2) << "\\n";
                Limit l = link().pyhf(std::vector<double>{5, 10}, std::vector<double>{50, 60}, std::vector<double>{7, 8},
                                      std::vector<double>{53, 65}, 1.0, 21);
                std::cout << "CLs " << l.cls_obs << ", limit " << l.upper_limit << "\\n";
            } catch (const LinkError& e) {
                std::cerr << e.what() << "\\n";
                return 1;
            }
        }
        """;

    private static final String FORTRAN = """
        ! PyTorch, TensorFlow and pyhf from Fortran. Sphere links sphere_mlink with the bridge.
        program mlink_demo
          use, intrinsic :: iso_fortran_env, only: real64
          use sphere_mlink
          implicit none
          type(mlink_info) :: found
          real(real64) :: x(2, 3), v, cls, limit
          logical :: ok

          call mlink_detect(found)
          print *, 'PyTorch ', found%torch, ', TensorFlow ', found%tensorflow, ', pyhf ', found%pyhf

          x = reshape([1.0_real64, 4.0_real64, 2.0_real64, 5.0_real64, 3.0_real64, 6.0_real64], [2, 3])
          call mlink_put('x', x)                    ! x(i, j) is x[i-1, j-1] in the Python
          call mlink_eval('torch.linalg.norm(torch.as_tensor(x)).item()', v, ok)
          if (.not. ok) stop mlink_error()
          print *, 'norm ', v

          call mlink_pyhf([5.0_real64, 10.0_real64], [50.0_real64, 60.0_real64], [7.0_real64, 8.0_real64], &
                          [53.0_real64, 65.0_real64], cls, ok, points=21, upper_limit=limit)
          print *, 'CLs ', cls, ' limit ', limit
        end program mlink_demo
        """;

    private static final String JULIA = """
        # PyTorch, TensorFlow and pyhf from Julia
        include(joinpath(ENV["SPHERE_BRIDGE_LIB"], "SphereMLink.jl"))
        using .SphereMLink

        println(SphereMLink.detect())
        x = [1.0 2.0 3.0; 4.0 5.0 6.0]
        println(SphereMLink.py("torch.linalg.norm(torch.as_tensor(x)).item()"; x))
        println(SphereMLink.py("tf.nn.softmax(tf.constant(x)).numpy()"; x))
        r = SphereMLink.pyhf([5.0, 10.0], [50.0, 60.0], [7.0, 8.0], [53.0, 65.0]; points = 21)
        println(r["backends"]["numpy"]["upper_limit"])
        """;

    private static final String PYTHON = """
        # PyTorch, TensorFlow and pyhf from a Python that does not hold them: PyROOT's, a Geant4 binding's, WSL's
        import numpy as np
        import sphere_mlink

        print(sphere_mlink.detect())
        k = sphere_mlink.link()
        x = np.array([[1.0, 2.0, 3.0], [4.0, 5.0, 6.0]])
        print(k.eval("torch.linalg.norm(torch.as_tensor(x)).item()", x=x))
        print(k.eval("tf.nn.softmax(tf.constant(x)).numpy()", x=x))
        print(k.pyhf([5.0, 10.0], [50.0, 60.0], [7.0, 8.0], [53.0, 65.0], points=21)["backends"]["numpy"]["upper_limit"])
        print(k.predict("model.pt", x))   # TorchScript, torch.export, Keras, a SavedModel
        """;
}
