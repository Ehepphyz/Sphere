package com.sphere.core.geant4;

import com.sphere.core.ml.GraphCpp;
import com.sphere.core.ml.NeuralGraph;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The networks attached to sensitive volumes in G4editor, in the application it exports. A network attached to a
 * volume reads the energy of that detector's cells at the end of each event -- one input of one row and as many
 * numbers as there are cells -- and its outputs are columns of the application's ntuple, beside the energies.
 *
 * <p>The attachment is the volume's GDML auxiliary {@value #AUX}, so that it is saved with the design and undone as
 * any other edit. Each is checked against the detector when it is made, and again at export, before anything is
 * written: a network of the wrong size, or cells that would read as one, are refused then.
 */
public final class NetworkExport {

    private NetworkExport() {
    }

    public static final String AUX = "SphereNetwork";

    /** The cells of a detector, in the order the network reads them; what makes them unreadable goes to {@code problems}. */
    public static List<FastSimPlanner.Cell> cellsOf(Geometry g, String detector, List<String> problems) {
        return FastSimPlanner.cells(g, detector, problems);
    }

    /** Why this graph cannot read this detector, or null when it can. */
    public static String mismatch(Geometry g, LogicalVolume volume, NeuralGraph graph) {
        if (volume.sensitive() == null) {
            return volume.name() + " is not sensitive: mark it first, the network reads what it scores";
        }
        final List<String> problems = new ArrayList<>();
        final int cells = cellsOf(g, volume.sensitive(), problems).size();
        if (!problems.isEmpty()) {
            return String.join("; ", problems);
        }
        final int[][] shapes = graph.exampleShapes();
        if (shapes.length != 1) {
            return graph.name + " takes " + shapes.length + " inputs; a network on a detector takes one, its cells";
        }
        final int[] s = shapes[0].clone();
        if (s.length > 0) {
            s[0] = 1;
        }
        final int size = Arrays.stream(s).reduce(1, (a, b) -> a * b);
        return size == cells ? null : graph.name + " reads " + size + " numbers a row (" + Arrays.toString(shapes[0]) + "), and " + volume.sensitive() + " has " + cells + " cells";
    }

    /** Every volume with a network attached. */
    public static List<LogicalVolume> attached(Geometry g) {
        return g.volumes().stream().filter(v -> v.aux().containsKey(AUX)).toList();
    }

    /**
     * Adds the attached networks to an application's files before they are written: their headers, and their call at
     * the end of each event. Throws, with nothing written, when one cannot be added. The number added.
     */
    public static int apply(Geometry g, Map<String, String> files, Consumer<String> log) throws IOException {
        final List<LogicalVolume> volumes = attached(g);
        if (volumes.isEmpty()) {
            return 0;
        }
        final String actions = "src/ActionInitialization.cc";
        FastSimCode.readCells(files);
        final StringBuilder includes = new StringBuilder(), columns = new StringBuilder(), fill = new StringBuilder();
        // the ntuple's columns so far: one per detector
        int column = (int) g.volumes().stream().map(LogicalVolume::sensitive).filter(s -> s != null).distinct().count();
        int index = 0;
        for (LogicalVolume v : volumes) {
            final String name = v.aux().get(AUX);
            final NeuralGraph graph = NeuralGraph.load(GraphCpp.models().resolve(name + ".sng.spx"));
            final String wrong = mismatch(g, v, graph);
            if (wrong != null) {
                throw new IOException(wrong);
            }
            final String cls = GraphCpp.classNameFor(name);
            final GraphCpp.Transcript t = GraphCpp.transcribe(graph, cls, 1);
            if (t.outShapes().length != 1) {
                throw new IOException(name + " has " + t.outShapes().length + " outputs: a network on a detector gives one, its numbers becoming columns");
            }
            files.put("include/" + t.fileName(), t.text());
            log.accept(t.fileName() + ": " + name + " on " + v.sensitive());
            includes.append("#include \"").append(t.fileName()).append("\"\n");
            final List<FastSimPlanner.Cell> cells = cellsOf(g, v.sensitive(), null);
            final int outputs = Arrays.stream(t.outShapes()[0]).reduce(1, (a, b) -> a * b);
            final String input = "nnInput" + index, output = "nnOutput" + index, keys = "nnCells" + index;
            for (int k = 0; k < outputs; k++) {
                columns.append("      am->CreateNtupleDColumn(\"").append(CppExport.cppId(name)).append('_').append(k).append("\");\n");
            }
            fill.append("      {\n        static const long long ").append(keys).append("[] = {");
            for (int k = 0; k < cells.size(); k++) {
                fill.append(k > 0 ? ", " : "").append(cells.get(k).key()).append("LL");
            }
            fill.append("};\n        std::vector<double> ").append(input).append("(").append(cls).append("::kInput0, 0.), ").append(output).append("(").append(cls).append("::kOutput0);\n");
            fill.append("        auto* sd = static_cast<SensitiveDetector*>(sdm->FindSensitiveDetector(\"").append(v.sensitive()).append("\", false));\n");
            fill.append("        if (sd != nullptr) for (std::size_t c = 0; c < ").append(input).append(".size(); ++c) {\n");
            fill.append("          const auto it = sd->Cells().find(").append(keys).append("[c]);\n");
            fill.append("          if (it != sd->Cells().end()) ").append(input).append("[c] = it->second / MeV;\n        }\n");
            fill.append("        ").append(cls).append("::Evaluate(").append(input).append(".data(), ").append(output).append(".data());\n");
            fill.append("        for (std::size_t k = 0; k < ").append(output).append(".size(); ++k) am->FillNtupleDColumn(").append(column).append(" + static_cast<G4int>(k), ").append(output).append("[k]);\n      }\n");
            column += outputs;
            index++;
        }
        String a = files.get(actions);
        a = require(a, "#include \"SensitiveDetector.hh\"\n", "#include \"SensitiveDetector.hh\"\n" + includes);
        a = require(a, "      am->FinishNtuple();\n", columns + "      am->FinishNtuple();\n");
        a = require(a, "      if (!kDetectors.empty()) am->AddNtupleRow();\n", fill + "      if (!kDetectors.empty()) am->AddNtupleRow();\n");
        files.put(actions, a);
        log.accept(String.format(Locale.ROOT, "%d network(s) in the application: their outputs are columns of its ntuple", volumes.size()));
        return volumes.size();
    }

    private static String require(String text, String anchor, String replacement) {
        if (!text.contains(anchor)) {
            throw new IllegalStateException("the generated actions no longer hold: " + anchor.strip());
        }
        return text.replace(anchor, replacement);
    }
}
