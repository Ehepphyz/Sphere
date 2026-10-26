package com.sphere.components.g4editor;

import com.sphere.core.geant4.Advisor;
import com.sphere.core.geant4.Budget;
import com.sphere.core.geant4.Expr;
import com.sphere.core.geant4.Gdml;
import com.sphere.core.geant4.GeometryException;
import com.sphere.core.geant4.Inputs;
import com.sphere.core.geant4.LogicalVolume;
import com.sphere.core.geant4.Material;
import com.sphere.core.geant4.Units;
import com.sphere.core.geant4.Vec3;
import com.sphere.core.geantbackend.G4Json;
import com.sphere.core.geantbackend.Geant4Bridge;
import com.sphere.core.geantbackend.Geant4BridgeCompiler;
import com.sphere.core.geantbackend.Geant4Locator;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs the design in Geant4 through geant4-bridge: a particle gun, a number of
 * events, a reference physics list; back come the energy deposited in every
 * volume, the dose, and the trajectories of the first events, drawn in the 3D
 * view. Geant4 also checks the editor: its own overlap check, its own NIST
 * materials and its own navigator against G4editor's.
 */
final class RunPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final EditorModel model;
    private final Viewport vp;
    private final Function<String, String> settings;
    private final Consumer<String> console;
    private Geant4Bridge bridge;
    private String loadedRevision;

    private final JLabel status = new JLabel("geant4-bridge: not started");
    private final JComboBox<String> physics = new JComboBox<>(new String[] {"FTFP_BERT", "FTFP_BERT_EMZ", "QGSP_BERT",
        "QGSP_BIC", "QGSP_BIC_EMZ", "QGSP_BIC_HP", "QBBC", "Shielding", "FTFP_BERT_HP", "QGSP_BERT_HP"});
    private final JComboBox<String> particle = new JComboBox<>(new String[] {"e-", "e+", "gamma", "mu-", "mu+", "pi+",
        "pi-", "proton", "neutron", "alpha", "kaon+", "kaon-", "geantino", "chargedgeantino"});
    private final JTextField energy = new JTextField("1 GeV", 8);
    private final JSpinner events = new JSpinner(new SpinnerNumberModel(100, 1, 100_000_000, 100));
    private final JTextField[] pos = {new JTextField("0", 6), new JTextField("0", 6), new JTextField("", 6)};
    private final JTextField[] dir = {new JTextField("0", 4), new JTextField("0", 4), new JTextField("1", 4)};
    private final JTextField spread = new JTextField("0", 5);
    private final JTextField spot = new JTextField("0", 5);
    private final JSpinner trackEvents = new JSpinner(new SpinnerNumberModel(10, 0, 1000, 5));
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final DefaultTableModel results = new DefaultTableModel(new Object[] {"Volume", "Material", "Mean energy",
        "RMS", "Events hit", "Dose / event"}, 0);
    private final JButton runButton = new JButton("Run in Geant4", Icons.run(16));
    private final JButton cancelButton = new JButton("Cancel");

    RunPanel(EditorModel model, Viewport vp, Function<String, String> settings, Consumer<String> console) {
        super(new BorderLayout());
        this.model = model;
        this.vp = vp;
        this.settings = settings;
        this.console = console;
        physics.setEditable(true);
        particle.setEditable(true);
        physics.setSelectedItem(model.geometry.settings().getOrDefault("physicsList", Advisor.recommendedPhysicsList(model.geometry)));
        physics.setToolTipText("Reference physics list (G4PhysListFactory); the assistant recommends one for your materials");
        restoreGun();

        final JPanel form = new JPanel(new GridBagLayout());
        final GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;
        row = line(form, c, row, "Particle", particle, "Energy", energy, "Events", events);
        row = line(form, c, row, "Position x y z", group(pos), "Direction", group(dir), "Physics list", physics);
        row = line(form, c, row, "Cone half-angle", spread, "Spot radius", spot, "Events drawn", trackEvents);
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 6;
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        final JButton build = new JButton("Build engine");
        final JButton info = new JButton("Engine info");
        final JButton scan = new JButton("Cross-check navigation", Icons.geantino(16));
        final JButton mats = new JButton("Cross-check materials");
        final JButton ov = new JButton("Geant4 overlap check", Icons.check(16));
        final JButton clear = new JButton("Clear tracks");
        cancelButton.setEnabled(false);
        buttons.add(runButton);
        buttons.add(cancelButton);
        buttons.add(build);
        buttons.add(info);
        buttons.add(scan);
        buttons.add(mats);
        buttons.add(ov);
        buttons.add(clear);
        form.add(buttons, c);
        c.gridy = row + 1;
        final JPanel st = new JPanel(new BorderLayout(6, 0));
        st.add(status, BorderLayout.CENTER);
        progress.setPreferredSize(new java.awt.Dimension(200, 14));
        st.add(progress, BorderLayout.EAST);
        form.add(st, c);
        form.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, form, new JScrollPane(new JTable(results)));
        split.setResizeWeight(0);
        add(split, BorderLayout.CENTER);

        runButton.addActionListener(e -> run());
        cancelButton.addActionListener(e -> {
            if (bridge != null) {
                bridge.cancel();
            }
        });
        build.addActionListener(e -> background(() -> {
            final Geant4BridgeCompiler.Result r = Geant4BridgeCompiler.getOrBuild(settings, this::out, true);
            out(r.message());
            later(() -> status.setText("geant4-bridge: " + r.message()));
        }));
        info.addActionListener(e -> background(this::engineInfo));
        scan.addActionListener(e -> background(this::crossCheckNavigation));
        mats.addActionListener(e -> background(this::crossCheckMaterials));
        ov.addActionListener(e -> background(this::geant4Overlaps));
        clear.addActionListener(e -> {
            vp.tracks.clear();
            vp.trackColors.clear();
            vp.repaint();
        });
    }

    private static JPanel group(JTextField[] f) {
        final JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        for (JTextField t : f) {
            p.add(t);
        }
        return p;
    }

    private static int line(JPanel p, GridBagConstraints c, int row, Object... labelled) {
        c.gridy = row;
        c.gridwidth = 1;
        for (int i = 0; i < labelled.length; i += 2) {
            c.gridx = i;
            p.add(new JLabel((String) labelled[i]), c);
            c.gridx = i + 1;
            p.add((java.awt.Component) labelled[i + 1], c);
        }
        return row + 1;
    }

    private void restoreGun() {
        final Map<String, String> s = model.geometry.settings();
        particle.setSelectedItem(s.getOrDefault("gun.particle", "e-"));
        energy.setText(s.getOrDefault("gun.energy", "1 GeV").replace("*", " "));
        final String[] p = s.getOrDefault("gun.position", "").split("\\s+");
        if (p.length == 3) {
            for (int i = 0; i < 3; i++) {
                pos[i].setText(p[i]);
            }
        }
        final String[] d = s.getOrDefault("gun.direction", "0 0 1").split("\\s+");
        if (d.length == 3) {
            for (int i = 0; i < 3; i++) {
                dir[i].setText(d[i]);
            }
        }
    }

    private void out(String s) {
        console.accept(s);
    }

    private static void later(Runnable r) {
        SwingUtilities.invokeLater(r);
    }

    private static void background(Runnable r) {
        final Thread t = new Thread(r, "g4editor-run");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------
    // The engine
    // ------------------------------------------------------------------

    /** A running engine, built and started first when needed. */
    private synchronized Geant4Bridge engine() throws Exception {
        if (bridge != null && bridge.alive()) {
            return bridge;
        }
        later(() -> status.setText("geant4-bridge: preparing..."));
        final Geant4BridgeCompiler.Result r = Geant4BridgeCompiler.getOrBuild(settings, this::out, false);
        if (!r.ok()) {
            throw new IllegalStateException(r.message());
        }
        // Here through shared memory, or inside WSL through its standard streams
        bridge = Geant4BridgeCompiler.start(r, settings, this::out);
        loadedRevision = null;
        final String d = bridge.description();
        final String how = bridge.transport() == Geant4Bridge.Transport.SHM ? "shared memory" : "inside WSL";
        later(() -> status.setText("geant4-bridge: " + d + " (" + how + ")"));
        out("geant4-bridge started: " + d);
        return bridge;
    }

    private void restart() {
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
    }

    /** Sends the current design to the engine, restarting it when Geant4 cannot swap geometries. */
    private Geant4Bridge withDesign() throws Exception {
        Geant4Bridge b = engine();
        final String gdml = Gdml.write(model.geometry);
        final String rev = Integer.toHexString(gdml.hashCode()) + ":" + gdml.length();
        if (rev.equals(loadedRevision)) {
            return b;
        }
        final Path file = Geant4BridgeCompiler.runtimeDir().resolve("design.gdml");
        Files.writeString(file, gdml);
        try {
            b.call("load", Map.of("path", b.enginePath(file))).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Geant4Bridge.BridgeException be && be.needsRestart()) {
                out("geant4-bridge: new design after a run, restarting the engine");
                restart();
                b = engine();
                b.call("load", Map.of("path", b.enginePath(file))).get();
            } else {
                throw e;
            }
        }
        loadedRevision = rev;
        return b;
    }

    private void engineInfo() {
        try {
            final Map<String, Object> v = engine().call("version", Map.of()).get();
            out("Geant4: " + G4Json.str(v, "geant4", "?") + " " + G4Json.str(v, "date", ""));
            final List<?> missing = G4Json.list(v, "missingDatasets");
            out(missing.isEmpty() ? "All physics data sets are found." : "Missing data sets: " + missing
                + " (runs need G4LEDATA, G4LEVELGAMMADATA, G4ENSDFSTATEDATA, G4PARTICLEXSDATA)");
            out("Physics lists: " + G4Json.list(v, "physicsLists"));
        } catch (Exception e) {
            report(e);
        }
    }

    private void report(Exception e) {
        final Throwable c = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        out("# geant4-bridge: " + c.getMessage());
        later(() -> {
            status.setText("geant4-bridge: " + c.getMessage());
            runButton.setEnabled(true);
            cancelButton.setEnabled(false);
        });
    }

    // ------------------------------------------------------------------
    // Run
    // ------------------------------------------------------------------

    private double length(JTextField f, double fallback) throws GeometryException {
        final String t = f.getText().trim();
        return t.isEmpty() ? fallback : Expr.eval(Inputs.normalize(t, Units.Category.LENGTH, model.lengthUnit),
            model.geometry.scope());
    }

    private void run() {
        final Map<String, Object> args = new LinkedHashMap<>();
        try {
            final double e = Expr.eval(Inputs.normalize(energy.getText(), Units.Category.ENERGY, "mm"), model.geometry.scope());
            final double zDefault = -0.99 * model.geometry.world().solid().extent().maxZ();
            final double[] p = {length(pos[0], 0), length(pos[1], 0), length(pos[2], zDefault)};
            final double[] d = {Expr.eval(dir[0].getText(), null), Expr.eval(dir[1].getText(), null), Expr.eval(dir[2].getText(), null)};
            args.put("events", ((Number) events.getValue()).intValue());
            args.put("particle", String.valueOf(particle.getSelectedItem()).trim());
            args.put("energy_MeV", e);
            args.put("position_mm", List.of(p[0], p[1], p[2]));
            args.put("direction", List.of(d[0], d[1], d[2]));
            args.put("spread_rad", Expr.eval(Inputs.normalize(spread.getText(), Units.Category.ANGLE, "mm"), null));
            args.put("spot_mm", length(spot, 0));
            args.put("physics", String.valueOf(physics.getSelectedItem()).trim());
            args.put("trackEvents", ((Number) trackEvents.getValue()).intValue());
            args.put("maxPoints", 300_000);
            // The gun is part of the design: the exported C++ uses it too
            final Map<String, String> s = model.geometry.settings();
            s.put("gun.particle", (String) args.get("particle"));
            s.put("gun.energy", Inputs.normalize(energy.getText(), Units.Category.ENERGY, "mm"));
            s.put("gun.position", Units.num(p[0]) + "*mm " + Units.num(p[1]) + "*mm " + Units.num(p[2]) + "*mm");
            s.put("gun.direction", Units.num(d[0]) + " " + Units.num(d[1]) + " " + Units.num(d[2]));
            s.put("physicsList", (String) args.get("physics"));
            s.put("run.events", String.valueOf(args.get("events")));
        } catch (GeometryException | RuntimeException ex) {
            out("# run: " + ex.getMessage());
            return;
        }
        runButton.setEnabled(false);
        cancelButton.setEnabled(true);
        progress.setValue(0);
        model.log("run " + args.get("events") + " " + args.get("particle") + " " + energy.getText().replace(" ", ""));
        background(() -> {
            try {
                final Geant4Bridge b = withDesign();
                later(() -> status.setText("running " + args.get("events") + " " + args.get("particle") + " ..."));
                final CompletableFuture<Map<String, Object>> f = b.call("run", args,
                    (fr, msg) -> later(() -> {
                        progress.setValue((int) (fr * 1000));
                        status.setText(msg);
                    }));
                Map<String, Object> r;
                try {
                    r = f.get();
                } catch (ExecutionException ex) {
                    if (ex.getCause() instanceof Geant4Bridge.BridgeException be && be.needsRestart()) {
                        out("geant4-bridge: another physics list, restarting the engine");
                        restart();
                        r = withDesign().call("run", args, (fr, msg) -> later(() -> progress.setValue((int) (fr * 1000)))).get();
                    } else {
                        throw ex;
                    }
                }
                final Map<String, Object> res = r;
                later(() -> show(res));
            } catch (Exception e) {
                report(e);
            }
        });
    }

    private void show(Map<String, Object> r) {
        runButton.setEnabled(true);
        cancelButton.setEnabled(false);
        progress.setValue(1000);
        final int n = (int) G4Json.num(r, "events", 0);
        status.setText(String.format(Locale.ROOT, "%d events of %s at %s in %.1f s (%s)", n, G4Json.str(r, "particle", "?"),
            Units.best(G4Json.num(r, "energy_MeV", 0), Units.Category.ENERGY), G4Json.num(r, "time_ms", 0) / 1000,
            G4Json.str(r, "physics", "")));
        results.setRowCount(0);
        final Map<String, Object> edep = G4Json.obj(r, "edep");
        final List<Map.Entry<String, Object>> rows = new ArrayList<>(edep.entrySet());
        rows.sort((a, b) -> Double.compare(G4Json.num(b.getValue(), "mean_MeV", 0), G4Json.num(a.getValue(), "mean_MeV", 0)));
        for (Map.Entry<String, Object> e : rows) {
            final LogicalVolume lv = model.geometry.volume(e.getKey());
            final Object v = e.getValue();
            final double dose = G4Json.num(v, "dose_Gy_per_event", -1);
            results.addRow(new Object[] {e.getKey(), lv == null ? "" : lv.material(),
                Units.best(G4Json.num(v, "mean_MeV", 0), Units.Category.ENERGY),
                Units.best(G4Json.num(v, "rms_MeV", 0), Units.Category.ENERGY), (int) G4Json.num(v, "events", 0),
                dose < 0 ? "" : String.format(Locale.ROOT, "%.4g Gy", dose)});
        }
        // Trajectories: negative red, positive blue, neutral green, as Geant4's drawByCharge
        vp.tracks.clear();
        vp.trackColors.clear();
        for (Object o : G4Json.list(r, "tracks")) {
            final List<?> pts = G4Json.list(o, "points_mm");
            if (pts.size() < 6) {
                continue;
            }
            final float[] seg = new float[(pts.size() / 3 - 1) * 6];
            int k = 0;
            for (int i = 0; i + 5 < pts.size(); i += 3) {
                for (int j = 0; j < 6; j++) {
                    seg[k++] = ((Number) pts.get(i + j)).floatValue();
                }
            }
            final double q = G4Json.num(o, "charge", 0);
            vp.tracks.add(seg);
            vp.trackColors.add(q < 0 ? 0xFFE03C32 : q > 0 ? 0xFF3C64E6 : 0xB028B450);
        }
        vp.repaint();
        if (Boolean.TRUE.equals(r.get("truncated"))) {
            out("# trajectories truncated at 300000 points; fewer 'events drawn' shows them whole");
        }
        for (Object w : G4Json.list(r, "warnings")) {
            out("# Geant4: " + w);
        }
        out(String.format(Locale.ROOT, "run done: %d events, %d trajectories drawn", n, vp.tracks.size()));
    }

    // ------------------------------------------------------------------
    // Cross-checks: Geant4 against G4editor
    // ------------------------------------------------------------------

    private void crossCheckMaterials() {
        try {
            final Geant4Bridge b = withDesign();
            out("Materials, Geant4 against G4editor (radiation length, interaction length):");
            final java.util.Set<String> names = new java.util.LinkedHashSet<>();
            for (LogicalVolume lv : model.geometry.volumes()) {
                names.add(lv.material());
            }
            double worst = 0;
            for (String n : names) {
                final Material m = model.geometry.materials().material(n);
                final Map<String, Object> g = b.call("material", Map.of("name", n)).get();
                final double gx = G4Json.num(g, "radiationLength_mm", 0), gl = G4Json.num(g, "nuclearInteractionLength_mm", 0);
                final double dx = Math.abs(gx - m.radiationLength()) / gx, dl = Math.abs(gl - m.nuclearInteractionLength()) / gl;
                worst = Math.max(worst, Math.max(dx, dl));
                out(String.format(Locale.ROOT, "  %-28s X0 %s / %s (%.2g)   lambdaI %s / %s (%.2g)", n,
                    Units.best(gx, Units.Category.LENGTH), Units.best(m.radiationLength(), Units.Category.LENGTH), dx,
                    Units.best(gl, Units.Category.LENGTH), Units.best(m.nuclearInteractionLength(), Units.Category.LENGTH), dl));
            }
            out(String.format(Locale.ROOT, "  largest relative difference: %.2g", worst));
        } catch (Exception e) {
            report(e);
        }
    }

    private void crossCheckNavigation() {
        try {
            final Geant4Bridge b = withDesign();
            final int na = 24, np = 8;
            final Map<String, Object> g = b.call("scan", Map.of("axis", "eta", "min", -3, "max", 3, "n", na, "nphi", np)).get();
            final Budget.Map2D mine = Budget.scan(model.geometry, Vec3.ZERO, Budget.Axis.ETA, -3, 3, na, -Math.PI, Math.PI, np);
            final List<?> gx = G4Json.list(g, "x0");
            double worst = 0, sum = 0;
            int worstAt = 0;
            for (int i = 0; i < gx.size() && i < mine.x0().length; i++) {
                final double a = ((Number) gx.get(i)).doubleValue(), m = mine.x0()[i];
                final double d = Math.abs(a - m) / Math.max(1e-6, Math.max(a, m));
                sum += d;
                if (d > worst) {
                    worst = d;
                    worstAt = i;
                }
            }
            out(String.format(Locale.ROOT, "Geantino scan, Geant4's G4Navigator against G4editor's navigator, %d rays: "
                + "mean relative difference %.2g, worst %.2g (ray %d: %.5g vs %.5g X0)", gx.size(), sum / Math.max(1, gx.size()),
                worst, worstAt, gx.isEmpty() ? 0 : ((Number) gx.get(worstAt)).doubleValue(), mine.x0()[worstAt]));
        } catch (Exception e) {
            report(e);
        }
    }

    private void geant4Overlaps() {
        try {
            final Geant4Bridge b = withDesign();
            out("Geant4 is checking overlaps (G4VPhysicalVolume::CheckOverlaps)...");
            final Map<String, Object> r = b.call("overlaps", Map.of("resolution", 1000),
                (f, m) -> later(() -> progress.setValue((int) (f * 1000)))).get();
            final List<?> faults = G4Json.list(r, "faults");
            out(String.format(Locale.ROOT, "Geant4 checked %d placements: %s", (int) G4Json.num(r, "checked", 0),
                faults.isEmpty() ? "no overlap" : faults.size() + " with overlaps"));
            for (Object f : faults) {
                out("  " + G4Json.str(f, "volume", "?") + ": " + G4Json.str(f, "message", "").replaceAll("\\s+", " ")
                    .replaceAll("-{5,}", "").trim());
            }
        } catch (Exception e) {
            report(e);
        }
    }

    void shutdown() {
        restart();
    }
}
