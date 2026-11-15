package com.sphere.components.g4editor;

import com.sphere.core.geant4.CppExport;
import com.sphere.core.geant4.FastSimPipeline;
import com.sphere.core.geant4.FastSimPlanner;
import com.sphere.core.geant4.FastSimStats;
import com.sphere.core.geant4.Gdml;
import com.sphere.core.geant4.Geometry;
import com.sphere.core.geant4.LogicalVolume;
import com.sphere.core.geant4.NetworkExport;
import com.sphere.core.geantbackend.Geant4AppBuilder;
import com.sphere.core.ml.GraphCpp;
import com.sphere.core.ml.NeuralGraph;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * G4editor's machine learning: the plan for a learned fast simulation of the design, kept up to date as the design
 * changes, the chain that builds and validates it, the comparison with Geant4 it ends on, and the networks attached
 * to the design's sensitive volumes.
 */
final class FastSimPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final EditorModel model;
    private final Function<String, String> settings;
    private final Consumer<String> console;
    private final JTextArea plan = new JTextArea();
    private final JTextArea log = new JTextArea();
    private final JTextField folder = new JTextField(30);
    private final JSpinner minutes = new JSpinner(new SpinnerNumberModel(20, 2, 600, 1));
    private final JButton run = new JButton("Build the fast simulation");
    private final JButton stop = new JButton("Stop");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final Chart chart = new Chart();
    private final JComboBox<String> volumes = new JComboBox<>();
    private final JComboBox<String> graphs = new JComboBox<>();
    private final JLabel attached = new JLabel(" ");
    private final Timer replan = new Timer(400, e -> replan());
    private volatile boolean canceled;
    private Thread worker;
    private String lastSuggested;

    FastSimPanel(EditorModel model, Function<String, String> settings, Consumer<String> console) {
        super(new BorderLayout());
        this.model = model;
        this.settings = settings;
        this.console = console;
        replan.setRepeats(false);
        plan.setEditable(false);
        plan.setLineWrap(true);
        plan.setWrapStyleWord(true);
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        lastSuggested = Path.of("fastsim", "detector").toAbsolutePath().toString();
        folder.setText(lastSuggested);
        progress.setStringPainted(true);
        progress.setString("");
        stop.setEnabled(false);
        run.addActionListener(e -> start());
        stop.addActionListener(e -> {
            canceled = true;
            Geant4AppBuilder.stop();
            stop.setEnabled(false);
        });

        final JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        controls.add(new JLabel("Application"));
        controls.add(folder);
        final JButton browse = new JButton("...");
        browse.addActionListener(e -> {
            final JFileChooser c = new JFileChooser();
            c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (c.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                folder.setText(c.getSelectedFile().toString());
            }
        });
        controls.add(browse);
        controls.add(new JLabel("Time (min)"));
        controls.add(minutes);
        controls.add(run);
        controls.add(stop);

        final JPanel north = new JPanel(new BorderLayout());
        north.add(new JScrollPane(plan), BorderLayout.CENTER);
        north.add(controls, BorderLayout.SOUTH);
        north.setPreferredSize(new Dimension(600, 130));

        final JSplitPane results = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(log), chart);
        results.setResizeWeight(0.5);

        final JPanel fast = new JPanel(new BorderLayout());
        fast.add(north, BorderLayout.NORTH);
        fast.add(results, BorderLayout.CENTER);
        fast.add(progress, BorderLayout.SOUTH);

        final JTabbedPane tabs = new JTabbedPane(JTabbedPane.LEFT);
        tabs.addTab("Fast simulation", fast);
        tabs.addTab("Networks", networks());
        add(tabs, BorderLayout.CENTER);

        model.addListener(new EditorModel.Listener() {
            @Override
            public void documentChanged(Geometry.Change change) {
                replan.restart();
            }

            @Override
            public void fileChanged() {
                replan.restart();
            }
        });
        replan();
    }

    private JComponent networks() {
        final JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.add(new JLabel("Sensitive volume"));
        row.add(volumes);
        row.add(new JLabel("Network"));
        row.add(graphs);
        final JButton attach = new JButton("Attach"), detach = new JButton("Detach");
        attach.addActionListener(e -> attach());
        detach.addActionListener(e -> detach());
        row.add(attach);
        row.add(detach);
        final JTextArea help = new JTextArea("""
            A network attached here reads the energy of each cell of that detector at the end of every event, and its outputs become
            columns of the ntuple of the application G4editor exports (File > Export). It is compiled into the application as C++:
            neither PyTorch nor TensorFlow are needed to build or run it. Export models with :torch export or :tf export.""");
        help.setEditable(false);
        help.setOpaque(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        p.add(row, BorderLayout.NORTH);
        p.add(help, BorderLayout.CENTER);
        p.add(attached, BorderLayout.SOUTH);
        return p;
    }

    private void refreshNetworks() {
        final Object v = volumes.getSelectedItem(), n = graphs.getSelectedItem();
        final DefaultComboBoxModel<String> vm = new DefaultComboBoxModel<>(), gm = new DefaultComboBoxModel<>();
        for (LogicalVolume lv : model.geometry.volumes()) {
            if (lv.sensitive() != null) {
                vm.addElement(lv.name());
            }
        }
        try {
            for (Path p : GraphCpp.graphs()) {
                gm.addElement(p.getFileName().toString().replace(".sng.spx", ""));
            }
        } catch (java.io.IOException e) {
            console.accept("cannot read the graphs: " + e.getMessage());
        }
        volumes.setModel(vm);
        graphs.setModel(gm);
        if (v != null) {
            volumes.setSelectedItem(v);
        }
        if (n != null) {
            graphs.setSelectedItem(n);
        }
        final StringBuilder b = new StringBuilder();
        for (LogicalVolume lv : NetworkExport.attached(model.geometry)) {
            b.append(b.length() == 0 ? "Attached: " : ",  ").append(lv.name()).append(" <- ").append(lv.aux().get(NetworkExport.AUX));
        }
        attached.setText(b.length() == 0 ? "No network attached." : b.toString());
    }

    private LogicalVolume selectedVolume() {
        final Object v = volumes.getSelectedItem();
        return v == null ? null : model.geometry.volumes().stream().filter(l -> l.name().equals(v)).findFirst().orElse(null);
    }

    private void attach() {
        final LogicalVolume v = selectedVolume();
        final Object n = graphs.getSelectedItem();
        if (v == null || n == null) {
            attached.setText("Choose a sensitive volume and a network (export one with :torch export or :tf export).");
            return;
        }
        try {
            final String wrong = NetworkExport.mismatch(model.geometry, v, NeuralGraph.load(GraphCpp.models().resolve(n + ".sng.spx")));
            if (wrong != null) {
                attached.setText("Refused: " + wrong);
                return;
            }
        } catch (java.io.IOException | RuntimeException e) {
            attached.setText("Cannot read this network: " + e.getMessage());
            return;
        }
        model.edit("attach network " + n, () -> v.aux().put(NetworkExport.AUX, n.toString()));
        console.accept("network " + n + " attached to " + v.name());
        refreshNetworks();
    }

    private void detach() {
        final LogicalVolume v = selectedVolume();
        if (v != null && v.aux().containsKey(NetworkExport.AUX)) {
            model.edit("detach network", () -> v.aux().remove(NetworkExport.AUX));
            console.accept("network detached from " + v.name());
        }
        refreshNetworks();
    }

    private CppExport.Options options() {
        return CppExport.Options.defaults(model.geometry);
    }

    private void replan() {
        // the application's folder follows the design's title until it is typed in
        final String suggested = Path.of("fastsim", CppExport.cppId(model.geometry.title())).toAbsolutePath().toString();
        if (folder.getText().equals(lastSuggested)) {
            folder.setText(suggested);
        }
        lastSuggested = suggested;
        refreshNetworks();
        try {
            final FastSimPlanner.Plan p = FastSimPlanner.plan(model.geometry, options());
            plan.setText(p.describe().stripTrailing());
            run.setEnabled(worker == null && p.runnable());
        } catch (RuntimeException e) {
            plan.setText("No plan for this design yet: " + e.getMessage());
            run.setEnabled(false);
        }
        plan.setCaretPosition(0);
    }

    private void start() {
        final Path target = Path.of(folder.getText().trim());
        // the chain reads its own copy of the design: the editor goes on while it runs
        final Geometry copy;
        try {
            copy = Gdml.read(Gdml.write(model.geometry), null).geometry();
        } catch (Exception e) {
            say("The design cannot be read back: " + e.getMessage());
            return;
        }
        if (Files.exists(target.resolve("fastsim_report.md"))) {
            say("Writing over the application in " + target);
        }
        canceled = false;
        log.setText("");
        chart.show(null);
        run.setEnabled(false);
        stop.setEnabled(true);
        final CppExport.Options o = options();
        final FastSimPipeline.Settings s = new FastSimPipeline.Settings(((Number) minutes.getValue()).doubleValue(), 20261109L, 0, 4);
        worker = new Thread(() -> {
            String outcome;
            FastSimPipeline.Result r = null;
            try {
                r = FastSimPipeline.run(copy, o, target, settings, s, new FastSimPipeline.Listener() {
                    public void log(String line) {
                        say(line);
                    }

                    public void stage(String name, double fraction) {
                        SwingUtilities.invokeLater(() -> {
                            progress.setValue((int) (1000 * fraction));
                            progress.setString(name);
                        });
                    }

                    public boolean canceled() {
                        return canceled;
                    }
                });
                outcome = r.verdict();
            } catch (Exception e) {
                outcome = canceled ? "Stopped." : "Failed: " + e.getMessage();
                say(outcome);
            }
            final FastSimPipeline.Result result = r;
            final String last = outcome;
            SwingUtilities.invokeLater(() -> {
                worker = null;
                stop.setEnabled(false);
                progress.setString(last.length() > 90 ? last.substring(0, 90) + "..." : last);
                chart.show(result);
                console.accept("fast simulation: " + last);
                replan();
            });
        }, "g4-fastsim");
        worker.setDaemon(true);
        worker.start();
    }

    private void say(String line) {
        SwingUtilities.invokeLater(() -> {
            log.append(line + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    /** The mean shower of the middle energy, full against fast, and the response at each energy. */
    private static final class Chart extends JComponent {
        private static final long serialVersionUID = 1L;
        private static final Color FULL = new Color(0x4C8BF5), FAST = new Color(0xE8833A);
        private FastSimPipeline.Result result;

        Chart() {
            setPreferredSize(new Dimension(420, 220));
        }

        void show(FastSimPipeline.Result r) {
            result = r;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            final Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            final Color fg = UIManager.getColor("Label.foreground") != null ? UIManager.getColor("Label.foreground") : Color.GRAY;
            final int w = getWidth(), h = getHeight();
            g.setColor(fg);
            if (result == null || result.points().isEmpty()) {
                g.drawString("Full simulation against the fast one appears here.", 16, 24);
                g.dispose();
                return;
            }
            final List<FastSimStats.Point> points = result.points();
            final FastSimStats.Point mid = points.get(points.size() / 2);
            final int left = 46, right = w - 14, top = 28, bottom = h - 54;
            double max = 0;
            for (double v : mid.profileFull()) {
                max = Math.max(max, v);
            }
            for (double v : mid.profileFast()) {
                max = Math.max(max, v);
            }
            final int n = mid.profileFull().length;
            g.drawLine(left, bottom, right, bottom);
            g.drawLine(left, top, left, bottom);
            g.drawString(String.format(Locale.ROOT, "Mean share per cell at %.0f MeV", mid.energy()), left, 16);
            g.drawString(String.format(Locale.ROOT, "%.2f", max), 6, top + 10);
            g.setColor(FULL);
            for (int i = 0; i < n; i++) {
                final int x = left + (right - left) * i / n, wide = Math.max(2, (right - left) / n - 1);
                final int y = (int) (bottom - (bottom - top) * mid.profileFull()[i] / max);
                g.fillRect(x, y, wide, bottom - y);
            }
            g.setColor(FAST);
            g.setStroke(new BasicStroke(2f));
            for (int i = 1; i < n; i++) {
                final int wide = (right - left) / n;
                g.drawLine(left + (right - left) * (i - 1) / n + wide / 2, (int) (bottom - (bottom - top) * mid.profileFast()[i - 1] / max),
                    left + (right - left) * i / n + wide / 2, (int) (bottom - (bottom - top) * mid.profileFast()[i] / max));
            }
            g.setColor(FULL);
            g.fillRect(left, h - 44, 10, 10);
            g.setColor(fg);
            g.drawString("Geant4", left + 14, h - 35);
            g.setColor(FAST);
            g.fillRect(left + 80, h - 44, 10, 10);
            g.setColor(fg);
            g.drawString("network", left + 94, h - 35);
            g.drawString(String.format(Locale.ROOT, "%s   %.0f x faster, %.0f%% of showers replaced", result.validated() ? "Validated" : "NOT validated", result.speedup(),
                100 * result.firedFraction()), left + 170, h - 35);
            final StringBuilder b = new StringBuilder("Response fast/full:");
            for (FastSimStats.Point p : points) {
                b.append(String.format(Locale.ROOT, "  %.0f MeV %+.2f%%", p.energy(), 100 * p.responseDelta()));
            }
            g.drawString(b.toString(), left, h - 14);
            g.dispose();
        }
    }
}
