package com.sphere.components.g4editor;

import com.sphere.core.geant4.Advisor;
import com.sphere.core.geant4.Budget;
import com.sphere.core.geant4.Geometry;
import com.sphere.core.geant4.Mass;
import com.sphere.core.geant4.Overlaps;
import com.sphere.core.geant4.PhysicalVolume;
import com.sphere.core.geant4.Units;
import com.sphere.core.geant4.Vec3;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** The analyses: the design assistant, overlaps, material budget and masses. */
final class AnalysisPanel extends JTabbedPane {

    private static final long serialVersionUID = 1L;

    private final EditorModel model;
    private final Viewport vp;
    private final Consumer<String> run;

    final JPanel assistant = new JPanel();
    private final JPanel assistantList = new JPanel();
    private List<Overlaps.Fault> faults = List.of();
    private final JLabel overlapStatus = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final AbstractTableModel faultModel;
    private final Heatmap heatmap = new Heatmap();
    private final JTextArea rayInfo = new JTextArea(6, 40);
    private final AbstractTableModel massModel;
    private List<Mass.Line> masses = List.of();
    private final Timer adviceTimer;

    AnalysisPanel(EditorModel model, Viewport vp, Consumer<String> runCommand) {
        this.model = model;
        this.vp = vp;
        this.run = runCommand;

        // --- Assistant
        assistant.setLayout(new BorderLayout());
        assistantList.setLayout(new BoxLayout(assistantList, BoxLayout.Y_AXIS));
        final JScrollPane as = new JScrollPane(assistantList);
        as.getVerticalScrollBar().setUnitIncrement(16);
        assistant.add(as, BorderLayout.CENTER);
        addTab("Assistant", Icons.advise(14), assistant);

        // --- Overlaps
        final JPanel ov = new JPanel(new BorderLayout());
        final JPanel ovTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        final JSpinner res = new JSpinner(new SpinnerNumberModel(1000, 100, 100000, 500));
        final JButton runOv = new JButton("Check overlaps", Icons.check(16));
        ovTop.add(new JLabel("Points per surface"));
        ovTop.add(res);
        ovTop.add(runOv);
        ovTop.add(progress);
        ovTop.add(overlapStatus);
        progress.setPreferredSize(new Dimension(120, 14));
        progress.setVisible(false);
        faultModel = new AbstractTableModel() {
            private static final long serialVersionUID = 1L;
            final String[] cols = {"Kind", "Volume", "With", "In", "Depth", "Points"};

            public int getRowCount() {
                return faults.size();
            }

            public int getColumnCount() {
                return cols.length;
            }

            @Override
            public String getColumnName(int c) {
                return cols[c];
            }

            public Object getValueAt(int r, int c) {
                final Overlaps.Fault f = faults.get(r);
                return switch (c) {
                    case 0 -> f.kind() == Overlaps.Kind.PROTRUSION ? "sticks out" : "overlap";
                    case 1 -> f.daughter().name() + (f.daughter().isReplica() ? "[" + f.daughterCopy() + "]" : "");
                    case 2 -> f.sister() == null ? "(mother)" : f.sister().name();
                    case 3 -> f.mother().name();
                    case 4 -> Units.best(f.depth(), Units.Category.LENGTH);
                    default -> f.hits();
                };
            }
        };
        final JTable ft = new JTable(faultModel);
        ft.getSelectionModel().addListSelectionListener(e -> {
            final int r = ft.getSelectedRow();
            if (e.getValueIsAdjusting() || r < 0 || r >= faults.size()) {
                return;
            }
            final Overlaps.Fault f = faults.get(r);
            final List<PhysicalVolume> sel = new ArrayList<>();
            sel.add(f.daughter());
            if (f.sister() != null) {
                sel.add(f.sister());
            }
            model.selectAll(sel);
            if (f.worldPoint() != null) {
                vp.markers.clear();
                vp.markers.add(f.worldPoint());
                vp.fitSelection(true);
            }
        });
        runOv.addActionListener(e -> checkOverlaps((Integer) res.getValue()));
        ov.add(ovTop, BorderLayout.NORTH);
        ov.add(new JScrollPane(ft), BorderLayout.CENTER);
        addTab("Overlaps", Icons.check(14), ov);

        // --- Budget
        final JPanel bp = new JPanel(new BorderLayout());
        final JPanel bTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        final JComboBox<String> axis = new JComboBox<>(new String[] {"eta", "theta"});
        final JComboBox<String> qty = new JComboBox<>(new String[] {"X/X0", "lambda/lambdaI"});
        final JSpinner etaMax = new JSpinner(new SpinnerNumberModel(4.0, 0.5, 10.0, 0.5));
        final JSpinner bins = new JSpinner(new SpinnerNumberModel(80, 10, 400, 10));
        final JButton scan = new JButton("Scan from origin", Icons.budget(16));
        bTop.add(new JLabel("Axis"));
        bTop.add(axis);
        bTop.add(new JLabel("|eta| max"));
        bTop.add(etaMax);
        bTop.add(new JLabel("bins"));
        bTop.add(bins);
        bTop.add(qty);
        bTop.add(scan);
        scan.addActionListener(e -> scanBudget("eta".equals(axis.getSelectedItem()), (Double) etaMax.getValue(),
            (Integer) bins.getValue(), qty.getSelectedIndex() == 1));
        qty.addActionListener(e -> {
            heatmap.lambda = qty.getSelectedIndex() == 1;
            heatmap.repaint();
        });
        rayInfo.setEditable(false);
        rayInfo.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        bp.add(bTop, BorderLayout.NORTH);
        final JPanel center = new JPanel(new BorderLayout());
        center.add(heatmap, BorderLayout.CENTER);
        center.add(new JScrollPane(rayInfo), BorderLayout.EAST);
        bp.add(center, BorderLayout.CENTER);
        addTab("Material budget", Icons.budget(14), bp);

        // --- Masses
        massModel = new AbstractTableModel() {
            private static final long serialVersionUID = 1L;
            final String[] cols = {"Volume", "Material", "Copies", "Own volume", "Own mass", "Mass with daughters", "Total (all copies)"};

            public int getRowCount() {
                return masses.size();
            }

            public int getColumnCount() {
                return cols.length;
            }

            @Override
            public String getColumnName(int c) {
                return cols[c];
            }

            public Object getValueAt(int r, int c) {
                final Mass.Line l = masses.get(r);
                return switch (c) {
                    case 0 -> l.lv().name();
                    case 1 -> l.lv().material();
                    case 2 -> l.placements();
                    case 3 -> Units.best(l.ownVolume(), Units.Category.VOLUME);
                    case 4 -> Units.best(l.ownMass(), Units.Category.MASS);
                    case 5 -> Units.best(l.totalMass(), Units.Category.MASS);
                    default -> Units.best(l.ownMass() * l.placements(), Units.Category.MASS);
                };
            }
        };
        final JPanel mp = new JPanel(new BorderLayout());
        final JButton mrun = new JButton("Compute masses");
        mrun.addActionListener(e -> computeMasses());
        final JPanel mtop = new JPanel(new FlowLayout(FlowLayout.LEFT));
        mtop.add(mrun);
        mtop.add(new JLabel("Masses as G4LogicalVolume::GetMass: own material over the solid minus the daughters' solids."));
        mp.add(mtop, BorderLayout.NORTH);
        mp.add(new JScrollPane(new JTable(massModel)), BorderLayout.CENTER);
        addTab("Masses", mp);

        adviceTimer = new Timer(600, e -> refreshAdvice());
        adviceTimer.setRepeats(false);
        model.addListener(new EditorModel.Listener() {
            public void documentChanged(Geometry.Change c) {
                adviceTimer.restart();
            }
        });
        refreshAdvice();
    }

    // ------------------------------------------------------------------
    // Assistant
    // ------------------------------------------------------------------

    /** Cards shown at most: past that a list is read by its kinds, and each card is an HTML label to lay out. */
    private static final int MAX_CARDS = 150;
    /** Placements by name, made once per refresh: looking each card's target up through the document was quadratic. */
    private Map<String, PhysicalVolume> placementsByName = Map.of();

    /** The review in progress, so a newer one replaces an older one still running. */
    private SwingWorker<?, ?> reviewing;

    /**
     * Reviews the design aside and shows the findings: the review reads the whole geometry, a quarter of a
     * second for a big one, which the window does not wait for. A document changed meanwhile (the review then
     * fails, or is out of date) is reviewed again.
     */
    void refreshAdvice() {
        if (reviewing != null) {
            reviewing.cancel(false);
        }
        final long revision = model.geometry.revision();
        reviewing = new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() {
                final List<Advisor.Advice> list = Advisor.review(model.geometry);
                final Map<String, PhysicalVolume> byName = new HashMap<>();
                for (PhysicalVolume pv : model.geometry.placements()) {
                    byName.putIfAbsent(pv.name(), pv);
                }
                return new Object[] {list, byName};
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void done() {
                if (isCancelled()) {
                    return;
                }
                try {
                    final Object[] r = get();
                    if (model.geometry.revision() != revision) {
                        adviceTimer.restart();
                        return;
                    }
                    showAdvice((List<Advisor.Advice>) r[0], (Map<String, PhysicalVolume>) r[1]);
                } catch (Exception e) {
                    adviceTimer.restart(); // edited while it was read
                }
            }
        };
        reviewing.execute();
    }

    private void showAdvice(List<Advisor.Advice> list, Map<String, PhysicalVolume> byName) {
        placementsByName = byName;
        assistantList.removeAll();
        if (list.isEmpty()) {
            assistantList.add(new JLabel("  Nothing to say: the design looks sound."));
        }
        for (int i = 0; i < Math.min(list.size(), MAX_CARDS); i++) {
            assistantList.add(card(list.get(i)));
        }
        if (list.size() > MAX_CARDS) {
            assistantList.add(new JLabel("  ... and " + (list.size() - MAX_CARDS) + " more, of the kinds above:"
                + " the fixes shown usually clear them together."));
        }
        assistantList.add(Box.createVerticalGlue());
        assistantList.revalidate();
        assistantList.repaint();
        int errors = 0;
        for (Advisor.Advice a : list) {
            if (a.level() == Advisor.Level.ERROR) {
                errors++;
            }
        }
        setTitleAt(0, errors > 0 ? "Assistant (" + errors + " error" + (errors > 1 ? "s" : "") + ")" : "Assistant");
    }

    private JComponent card(Advisor.Advice a) {
        final JPanel p = new JPanel(new BorderLayout(6, 2));
        final Color c = switch (a.level()) {
            case ERROR -> new Color(0xD64545);
            case WARNING -> new Color(0xE0912F);
            case IDEA -> new Color(0x3C8DDC);
            default -> new Color(0x7D8796);
        };
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4),
            BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 4, 0, 0, c),
                BorderFactory.createEmptyBorder(4, 8, 4, 6))));
        // Two plain labels, not one HTML one: Swing parses and lays out HTML each time, and a review of a big
        // ROOT geometry made a hundred cards, some with long texts, the window frozen for seconds
        final JLabel title = new JLabel(a.title());
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        final String d = a.detail() == null ? "" : a.detail();
        final JLabel detail = new JLabel(d.length() > 220 ? d.substring(0, 217) + "..." : d);
        detail.setForeground(new Color(0x7D8796));
        if (d.length() > 220) {
            detail.setToolTipText(d);
        }
        final JPanel text = new JPanel(new java.awt.GridLayout(0, 1));
        text.setOpaque(false);
        text.add(title);
        text.add(detail);
        p.add(text, BorderLayout.CENTER);
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        if (a.target() != null && placementsByName.get(a.target()) != null) {
            final JButton show = new JButton("Show");
            show.addActionListener(e -> {
                model.select(model.geometry.placement(a.target()), false);
                vp.fitSelection(true);
            });
            buttons.add(show);
        } else if (a.target() != null && model.geometry.volume(a.target()) != null) {
            final JButton show = new JButton("Show");
            show.addActionListener(e -> {
                model.selectAll(model.geometry.placementsOf(model.geometry.volume(a.target())));
                vp.fitSelection(true);
            });
            buttons.add(show);
        }
        for (Advisor.Fix f : a.fixes()) {
            final JButton b = new JButton(f.label());
            b.setToolTipText(f.command());
            b.addActionListener(e -> run.accept(f.command()));
            buttons.add(b);
        }
        p.add(buttons, BorderLayout.SOUTH);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height + 40));
        return p;
    }

    // ------------------------------------------------------------------
    // Overlaps
    // ------------------------------------------------------------------

    void checkOverlaps(int resolution) {
        setSelectedIndex(1);
        progress.setVisible(true);
        progress.setValue(0);
        overlapStatus.setText("checking...");
        final long t0 = System.nanoTime();
        new SwingWorker<List<Overlaps.Fault>, Integer>() {
            protected List<Overlaps.Fault> doInBackground() {
                return Overlaps.check(model.geometry, resolution, 0, p -> publish(p));
            }

            @Override
            protected void process(List<Integer> chunks) {
                progress.setValue(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                progress.setVisible(false);
                try {
                    faults = get();
                } catch (Exception e) {
                    faults = List.of();
                    overlapStatus.setText("failed: " + e.getMessage());
                    return;
                }
                faultModel.fireTableDataChanged();
                final String msg = faults.isEmpty() ? "no overlap found" : faults.size() + " problem" + (faults.size() > 1 ? "s" : "");
                overlapStatus.setText(msg + String.format("  (%.0f ms)", (System.nanoTime() - t0) / 1e6));
                setTitleAt(1, faults.isEmpty() ? "Overlaps" : "Overlaps (" + faults.size() + ")");
                model.log("check " + resolution + "   # " + msg);
                vp.markers.clear();
                for (Overlaps.Fault f : faults) {
                    if (f.worldPoint() != null) {
                        vp.markers.add(f.worldPoint());
                    }
                }
                vp.repaint();
            }
        }.execute();
    }

    // ------------------------------------------------------------------
    // Material budget
    // ------------------------------------------------------------------

    void scanBudget(boolean eta, double max, int bins, boolean lambda) {
        setSelectedIndex(2);
        heatmap.message = "scanning...";
        heatmap.repaint();
        final long t0 = System.nanoTime();
        new SwingWorker<Budget.Map2D, Void>() {
            protected Budget.Map2D doInBackground() {
                final int nphi = Math.max(8, bins / 2);
                return eta ? Budget.scan(model.geometry, Vec3.ZERO, Budget.Axis.ETA, -max, max, bins, -Math.PI, Math.PI, nphi)
                    : Budget.scan(model.geometry, Vec3.ZERO, Budget.Axis.THETA, 0, Math.PI, bins, -Math.PI, Math.PI, nphi);
            }

            @Override
            protected void done() {
                try {
                    heatmap.map = get();
                    heatmap.lambda = lambda;
                    heatmap.message = String.format("%d rays in %.0f ms", heatmap.map.na() * heatmap.map.nphi(),
                        (System.nanoTime() - t0) / 1e6);
                    model.log("budget " + (eta ? "eta" : "theta") + "   # max " + String.format("%.3g", heatmap.map.maxX0()) + " X0");
                } catch (Exception e) {
                    heatmap.message = "scan failed: " + e.getMessage();
                }
                heatmap.repaint();
            }
        }.execute();
    }

    /** A cell of the map clicked: trace that ray, show it and its breakdown. */
    private void inspectRay(double a, double phi) {
        final Budget.Map2D m = heatmap.map;
        if (m == null) {
            return;
        }
        final Vec3 dir = Budget.direction(m.axis(), a, phi);
        final Budget.RayReport r = Budget.ray(model.geometry, Vec3.ZERO, dir, true);
        final StringBuilder b = new StringBuilder();
        b.append(String.format("%s = %.3f, phi = %.1f deg%n", m.axis() == Budget.Axis.ETA ? "eta" : "theta",
            m.axis() == Budget.Axis.THETA ? Math.toDegrees(a) : a, Math.toDegrees(phi)));
        b.append(String.format("total %.4g X0, %.4g lambdaI over %s%n%n", r.x0(), r.lambda(), Units.best(r.path(), Units.Category.LENGTH)));
        b.append(String.format("%-18s %10s %10s%n", "volume", "X/X0", "path"));
        for (Map.Entry<String, double[]> e : r.byVolume().entrySet()) {
            b.append(String.format("%-18s %10.4g %10s%n", e.getKey(), e.getValue()[0], Units.best(e.getValue()[2], Units.Category.LENGTH)));
        }
        b.append(String.format("%n%-18s %10s%n", "material", "X/X0"));
        for (Map.Entry<String, double[]> e : r.byMaterial().entrySet()) {
            b.append(String.format("%-18s %10.4g%n", e.getKey(), e.getValue()[0]));
        }
        rayInfo.setText(b.toString());
        rayInfo.setCaretPosition(0);
        // Draw the ray in the 3D view, coloured per segment
        vp.tracks.clear();
        vp.trackColors.clear();
        final double len = model.geometry.world().solid().extent().diagonal();
        vp.tracks.add(new float[] {0, 0, 0, (float) (dir.x() * len), (float) (dir.y() * len), (float) (dir.z() * len)});
        vp.trackColors.add(0xFFE8B030);
        vp.repaint();
    }

    final class Heatmap extends JComponent {
        private static final long serialVersionUID = 1L;
        Budget.Map2D map;
        boolean lambda;
        String message = "Scan the material budget: geantinos from the origin in every direction.";
        private int hx = -1, hy = -1;

        Heatmap() {
            setPreferredSize(new Dimension(520, 220));
            final MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    hx = e.getX();
                    hy = e.getY();
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    final double[] c = cell(e.getX(), e.getY());
                    if (c != null) {
                        inspectRay(c[0], c[1]);
                    }
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        private final int left = 48, right = 70, top = 14, bottom = 34;

        private int plotW() {
            return Math.max(10, (getWidth() - left - right) * 2 / 3);
        }

        double[] cell(int x, int y) {
            if (map == null) {
                return null;
            }
            final int pw = plotW(), ph = getHeight() - top - bottom;
            if (x < left || x >= left + pw || y < top || y >= top + ph) {
                return null;
            }
            final double a = map.aMin() + (map.aMax() - map.aMin()) * (x - left + 0.5) / pw;
            final double phi = map.phiMax() - (map.phiMax() - map.phiMin()) * (y - top + 0.5) / ph;
            return new double[] {a, phi};
        }

        @Override
        protected void paintComponent(Graphics g0) {
            final Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            final Color fg = getForeground();
            g.setColor(fg);
            if (map == null) {
                g.drawString(message, 12, 24);
                return;
            }
            final int pw = plotW(), ph = getHeight() - top - bottom;
            final double max = Math.max(1e-12, lambda ? map.maxLambda() : map.maxX0());
            final BufferedImage img = new BufferedImage(map.na(), map.nphi(), BufferedImage.TYPE_INT_RGB);
            for (int ip = 0; ip < map.nphi(); ip++) {
                for (int ia = 0; ia < map.na(); ia++) {
                    final double v = (lambda ? map.lambda(ia, ip) : map.x0(ia, ip)) / max;
                    img.setRGB(ia, map.nphi() - 1 - ip, viridis(v));
                }
            }
            g.drawImage(img, left, top, pw, ph, null);
            g.setColor(fg);
            g.drawRect(left, top, pw, ph);
            g.setFont(getFont().deriveFont(10f));
            final boolean eta = map.axis() == Budget.Axis.ETA;
            for (int k = 0; k <= 4; k++) {
                final double a = map.aMin() + (map.aMax() - map.aMin()) * k / 4;
                final int x = left + pw * k / 4;
                g.drawLine(x, top + ph, x, top + ph + 3);
                g.drawString(eta ? String.format("%.1f", a) : String.format("%.0f", Math.toDegrees(a)), x - 8, top + ph + 14);
            }
            g.drawString(eta ? "pseudorapidity" : "theta (deg)", left + pw / 2 - 30, top + ph + 27);
            g.drawString("180", 18, top + 9);
            g.drawString("-180", 14, top + ph);
            g.drawString("phi", 18, top + ph / 2);
            // Colour bar
            final int cbx = left + pw + 8;
            for (int y = 0; y < ph; y++) {
                g.setColor(new Color(viridis(1 - (double) y / ph)));
                g.drawLine(cbx, top + y, cbx + 10, top + y);
            }
            g.setColor(fg);
            g.drawString(String.format("%.3g", max), cbx + 13, top + 9);
            g.drawString("0", cbx + 13, top + ph);
            g.drawString(lambda ? "lambda_I" : "X0", cbx + 13, top + ph / 2);
            // Profile: average over phi
            final int px = cbx + 50, pwid = getWidth() - px - 10;
            if (pwid > 60) {
                final double[] prof = lambda ? map.profileLambda() : map.profileX0();
                double pm = 1e-12;
                for (double v : prof) {
                    pm = Math.max(pm, v);
                }
                g.drawRect(px, top, pwid, ph);
                g.setColor(new Color(0x3C8DDC));
                g.setStroke(new BasicStroke(1.6f));
                int lx = -1, ly = -1;
                for (int i = 0; i < prof.length; i++) {
                    final int x = px + (int) ((i + 0.5) * pwid / prof.length), y = top + ph - (int) (prof[i] / pm * (ph - 4));
                    if (lx >= 0) {
                        g.drawLine(lx, ly, x, y);
                    }
                    lx = x;
                    ly = y;
                }
                g.setColor(fg);
                g.drawString(String.format("mean over phi, max %.3g", pm), px + 4, top + 11);
            }
            if (hx >= 0) {
                final double[] c = cell(hx, hy);
                if (c != null) {
                    final int ia = (int) Math.min(map.na() - 1, Math.max(0, (c[0] - map.aMin()) / (map.aMax() - map.aMin()) * map.na()));
                    final int ip = (int) Math.min(map.nphi() - 1, Math.max(0, (c[1] - map.phiMin()) / (map.phiMax() - map.phiMin()) * map.nphi()));
                    final String s = String.format("%s %.2f  phi %.0f  :  %.4g X0, %.4g lambdaI   (click: trace this ray)",
                        eta ? "eta" : "theta", eta ? c[0] : Math.toDegrees(c[0]), Math.toDegrees(c[1]), map.x0(ia, ip),
                        map.lambda(ia, ip));
                    g.drawString(s, left, getHeight() - 4);
                } else {
                    g.drawString(message, left, getHeight() - 4);
                }
            }
        }
    }

    /** The viridis colour map, a few anchors interpolated. */
    static int viridis(double t) {
        final double[][] c = {{68, 1, 84}, {59, 82, 139}, {33, 145, 140}, {94, 201, 98}, {253, 231, 37}};
        t = Math.max(0, Math.min(1, t));
        final double x = t * (c.length - 1);
        final int i = Math.min(c.length - 2, (int) x);
        final double f = x - i;
        final int r = (int) (c[i][0] + (c[i + 1][0] - c[i][0]) * f);
        final int gg = (int) (c[i][1] + (c[i + 1][1] - c[i][1]) * f);
        final int b = (int) (c[i][2] + (c[i + 1][2] - c[i][2]) * f);
        return (r << 16) | (gg << 8) | b;
    }

    // ------------------------------------------------------------------

    void computeMasses() {
        setSelectedIndex(3);
        new SwingWorker<List<Mass.Line>, Void>() {
            protected List<Mass.Line> doInBackground() {
                return Mass.report(model.geometry);
            }

            @Override
            protected void done() {
                try {
                    masses = get();
                    massModel.fireTableDataChanged();
                    model.log("mass");
                } catch (Exception ignored) {
                    // the table stays as it was
                }
            }
        }.execute();
    }

    void showAssistant() {
        setSelectedIndex(0);
        refreshAdvice();
    }

    static void later(Runnable r) {
        SwingUtilities.invokeLater(r);
    }
}
