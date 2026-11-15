package com.sphere.components.g4editor;

import com.sphere.core.bridge.Bridge;
import com.sphere.core.ml.GraphCompiler;
import com.sphere.core.ml.GraphCpp;
import com.sphere.core.ml.NeuralGraph;
import com.sphere.utils.SettingsManager;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * G4code's Insert menu, for a network: a graph exported from PyTorch or TensorFlow, written into the application as C++
 * source ({@link GraphCpp}), compiled and held to Sphere's interpreter before anything of the application is touched.
 */
final class ModelInsertDialog extends JDialog {

    private static final long serialVersionUID = 1L;
    private static final String SUFFIX = ".sng.spx";

    private final Path application;
    private final BiConsumer<String, String> inserted;
    private final DefaultListModel<String> names = new DefaultListModel<>();
    private final JList<String> list = new JList<>(names);
    private final JTextArea details = new JTextArea(7, 56);
    private final JTextField className = new JTextField(22);
    private final JSpinner batch = new JSpinner(new SpinnerNumberModel(1, 1, 100000, 1));
    private final JButton go = new JButton("Transcribe and insert");
    private final JLabel state = new JLabel(" ");

    /** @param inserted told the header's file name and the code to put at the caret */
    ModelInsertDialog(JFrame owner, Path application, BiConsumer<String, String> inserted) {
        super(owner, "Insert a neural network", true);
        this.application = application;
        this.inserted = inserted;
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                describe();
            }
        });
        details.setEditable(false);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        details.setLineWrap(true);
        details.setWrapStyleWord(true);

        final JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        options.add(new JLabel("C++ class"));
        options.add(className);
        options.add(new JLabel("Batch"));
        options.add(batch);

        final JPanel south = new JPanel(new BorderLayout());
        south.add(options, BorderLayout.NORTH);
        south.add(new JScrollPane(details), BorderLayout.CENTER);
        final JPanel buttons = new JPanel(new BorderLayout());
        buttons.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));
        buttons.add(state, BorderLayout.CENTER);
        final JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        final JButton cancel = new JButton("Close");
        cancel.addActionListener(e -> dispose());
        go.addActionListener(e -> transcribe());
        right.add(cancel);
        right.add(go);
        buttons.add(right, BorderLayout.EAST);
        south.add(buttons, BorderLayout.SOUTH);

        final JScrollPane models = new JScrollPane(list);
        models.setPreferredSize(new Dimension(620, 150));
        models.setBorder(BorderFactory.createTitledBorder("Graphs exported (:torch export, :tf export, :tf import)"));
        add(models, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        load();
        pack();
        setLocationRelativeTo(owner);
    }

    private void load() {
        try {
            for (Path p : GraphCpp.graphs()) {
                final String f = p.getFileName().toString();
                names.addElement(f.substring(0, f.length() - SUFFIX.length()));
            }
        } catch (java.io.IOException e) {
            details.setText("Cannot read " + GraphCpp.models() + ": " + e.getMessage());
        }
        if (names.isEmpty()) {
            go.setEnabled(false);
            details.setText("No graph exported yet. In Sphere's console:\n"
                + "  :torch export model.py        (a script defining `model` and `example`)\n"
                + "  :torch export model.pt        (TorchScript)\n"
                + "  :tf export model.keras        (Keras)\n"
                + "  :tf import saved_model_dir    (a SavedModel or a frozen graph)\n"
                + "then open this again.");
        } else {
            list.setSelectedIndex(0);
        }
    }

    private Path selected() {
        final String n = list.getSelectedValue();
        return n == null ? null : GraphCpp.models().resolve(n + SUFFIX);
    }

    private void describe() {
        final Path file = selected();
        if (file == null) {
            return;
        }
        className.setText(GraphCpp.classNameFor(list.getSelectedValue()));
        details.setText("Reading...");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                final NeuralGraph g = NeuralGraph.load(file);
                final StringBuilder b = new StringBuilder(g.toString()).append('\n');
                b.append("framework: ").append(g.framework).append('\n');
                b.append("inputs: ").append(String.join(", ", g.inputNames));
                for (int[] s : g.exampleShapes()) {
                    b.append("  ").append(java.util.Arrays.toString(s));
                }
                return b.toString();
            }

            @Override
            protected void done() {
                try {
                    details.setText(get());
                } catch (Exception e) {
                    details.setText("Cannot read this graph: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void transcribe() {
        final Path file = selected();
        if (file == null) {
            return;
        }
        final String name = className.getText().trim();
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            state.setText("The class name must be a C++ identifier");
            return;
        }
        final Path target = GraphCpp.target(name, application);
        if (Files.exists(target) && JOptionPane.showConfirmDialog(this, target + (GraphCpp.generated(target) ? " exists (a network Sphere wrote)"
                : " exists and is not a network Sphere wrote") + ": write over it?", "Insert a neural network",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        final int rows = (Integer) batch.getValue();
        go.setEnabled(false);
        state.setText("Transcribing, then compiling to check it...");
        new SwingWorker<GraphCpp.Transcript, Void>() {
            private GraphCpp.Verdict verdict;
            private final StringBuilder log = new StringBuilder();

            @Override
            protected GraphCpp.Transcript doInBackground() throws Exception {
                final GraphCpp.Transcript t = GraphCpp.transcribe(NeuralGraph.load(file), name, rows);
                final String compiler = Bridge.cppCompiler(new SettingsManager());
                if (compiler != null) {
                    verdict = GraphCpp.verify(t, compiler, Bridge.buildFolder().resolve("g4model"), l -> log.append(l).append('\n'));
                    if (verdict.compiled() && !(verdict.error() <= 1e-9)) {
                        throw new GraphCompiler.NotCompiledReport("the C++ differs from Sphere's interpreter: " + verdict.detail());
                    }
                    if (!verdict.compiled() && t.selfTest()) {
                        throw new GraphCompiler.NotCompiledReport(verdict.detail() + "\n" + log);
                    }
                }
                GraphCpp.write(t, application);
                return t;
            }

            @Override
            protected void done() {
                go.setEnabled(true);
                try {
                    final GraphCpp.Transcript t = get();
                    inserted.accept(t.fileName(), t.usage());
                    final String checked = verdict == null ? "no C++ compiler to check it with" : verdict.detail();
                    state.setText(t.fileName() + " written");
                    JOptionPane.showMessageDialog(ModelInsertDialog.this, String.format(Locale.ROOT, "%s written in the application and its call inserted."
                        + "%n%nChecked: %s.%n%nFill the inputs in the inserted code: their shapes are in its comments.", t.fileName(), checked),
                        "Insert a neural network", JOptionPane.INFORMATION_MESSAGE);
                    dispose();
                } catch (Exception e) {
                    final Throwable c = e.getCause() != null ? e.getCause() : e;
                    state.setText("Not inserted");
                    details.setText("Nothing was changed in the application.\n\n" + c.getMessage());
                }
            }
        }.execute();
    }

    /** The graphs' names, for tests. */
    List<String> graphNames() {
        return java.util.Collections.list(names.elements());
    }
}
