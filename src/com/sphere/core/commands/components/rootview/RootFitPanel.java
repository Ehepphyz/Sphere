package com.sphere.components.rootview;

import com.sphere.components.rootview.RootPadPainter.View;
import com.sphere.components.rootview.RootScene.Graph;
import com.sphere.components.rootview.RootScene.Graph2D;
import com.sphere.components.rootview.RootScene.Group;
import com.sphere.components.rootview.RootScene.Hist;
import com.sphere.components.rootview.RootScene.Item;
import com.sphere.components.rootview.RootScene.Pad;
import com.sphere.components.rootview.RootScene.Segment;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleBinaryOperator;

/**
 * ROOT's Fit Panel (TH1::FitPanel, TGraph::FitPanel, TGraph2D::FitPanel,
 * TMultiGraph::FitPanel): the data set, the function (ROOT's predefined
 * ones or any formula), its parameters with their first values, the range,
 * chi2 or likelihood and the options of TH1::Fit. After each fit the
 * parameters and their errors fill the table, and the pulls are drawn below:
 * against x, and as a distribution with its mean and width, which is how a
 * physicist sees at once whether the model describes the data.
 */
final class RootFitPanel {

    private RootFitPanel() {
    }

    static void open(RootTarget start) {
        final RootTarget[] target = {dataTarget(start)};
        final Item data = target[0].item();
        final boolean twoD = data instanceof Graph2D || data instanceof Hist h && h.dim == 2;
        final JDialog d = new JDialog(SwingUtilities.getWindowAncestor(start.canvas), "Fit Panel",
            java.awt.Dialog.ModalityType.MODELESS);

        final JComboBox<String> dataSet = new JComboBox<>();
        final List<RootTarget> sets = dataSets(start);
        for (RootTarget s : sets) dataSet.addItem(s.title());
        dataSet.setSelectedItem(target[0].title());
        dataSet.addActionListener(e -> {
            final int k = dataSet.getSelectedIndex();
            if (k >= 0 && k < sets.size()) target[0] = sets.get(k);
        });

        final JComboBox<String> function = new JComboBox<>(twoD
            ? new String[]{"xygaus", "bigaus", "xyexpo", "xylandau", "[0]+[1]*x+[2]*y", "[0]*exp(-0.5*((x-[1])^2+(y-[2])^2)/[3]^2)"}
            : new String[]{"gaus", "gausn", "expo", "landau", "landaun", "breitwigner", "crystalball", "pol0", "pol1", "pol2",
                "pol3", "pol4", "pol5", "pol6", "chebyshev3", "chebyshev5", "gaus(0)+pol1(3)", "gaus(0)+pol2(3)",
                "gaus(0)+expo(3)", "gaus(0)+gaus(3)", "landau(0)+expo(3)", "[0]*TMath::Power(x,[1])"});
        function.setEditable(true);
        final View v = start.canvas.view(start.pad);
        final double[] shown = data instanceof Hist h && h.dim == 1 ? h.x.shown() : new double[]{v.x0, v.x1};
        final double lo0 = v.xr != null ? v.xr[0] : shown[0];
        final double hi0 = v.xr != null ? v.xr[1] : shown[1];
        final JTextField from = new JTextField(String.format(Locale.ROOT, "%.6g", lo0), 9);
        final JTextField to = new JTextField(String.format(Locale.ROOT, "%.6g", hi0), 9);
        final JRadioButton chi2 = new JRadioButton("Chi-square", true);
        final JRadioButton likelihood = new JRadioButton("Binned likelihood");
        likelihood.setEnabled(data instanceof Hist);
        final ButtonGroup method = new ButtonGroup();
        method.add(chi2);
        method.add(likelihood);
        final JCheckBox ignore = new JCheckBox("Ignore errors (W)");
        final JCheckBox add = new JCheckBox("Add to list (+)");
        final JCheckBox quiet = new JCheckBox("Quiet (Q)", true);
        final JCheckBox draw = new JCheckBox("Draw the function", true);
        final JCheckBox minosBox = new JCheckBox("Minos errors (E)");
        final JCheckBox gradBox = new JCheckBox("Gradient (G)");
        gradBox.setToolTipText("The formula's derivatives by automatic differentiation, instead of finite differences");
        final JComboBox<String> algorithm = new JComboBox<>(new String[]{"Migrad", "Simplex", "Minimize", "Scan", "BFGS", "Fumili"});
        algorithm.setSelectedItem(RootFitter.Options.defaultAlgorithm());
        algorithm.setToolTipText("Minuit2's algorithm (Sphere's Java Minuit2, bit for bit ROOT's); kept for the next fits");
        final JComboBox<String> strategy = new JComboBox<>(new String[]{"0", "1", "2", "3"});
        strategy.setSelectedItem(Integer.toString(RootFitter.Options.defaultStrategy()));
        strategy.setToolTipText("0 fast, 1 default, 2 careful (full Hessian), 3 central differences in Hesse");

        final DefaultTableModel table = new DefaultTableModel(new Object[]{"Parameter", "Start", "Value", "Error"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 1;
            }
        };
        final JTable params = new JTable(table);
        params.setPreferredScrollableViewportSize(new Dimension(420, 120));
        final Runnable refresh = () -> {
            table.setRowCount(0);
            try {
                final RootFormula f = RootFormula.parse(String.valueOf(function.getEditor().getItem()).strip());
                final double[][] xy = points(target[0]);
                final double[] guess = xy.length == 0 ? new double[f.parameters()] : RootFitter.guess(f, xyOf(xy), yOf(xy));
                for (int k = 0; k < f.parameters(); k++) {
                    table.addRow(new Object[]{f.parameterName(k), String.format(Locale.ROOT, "%.6g", k < guess.length ? guess[k] : 1), "", ""});
                }
            } catch (RuntimeException bad) {
                table.addRow(new Object[]{"(" + bad.getMessage() + ")", "", "", ""});
            }
        };
        function.addActionListener(e -> refresh.run());

        final JTextArea report = new JTextArea(9, 60);
        report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        report.setEditable(false);
        final RootCanvasView pulls = new RootCanvasView();
        pulls.setPreferredSize(new Dimension(600, 230));
        pulls.setHost(start.canvas.host());

        final JButton fit = new JButton("Fit");
        fit.addActionListener(e -> {
            final RootTarget t = target[0];
            try {
                final String formula = String.valueOf(function.getEditor().getItem()).strip();
                final double[] p0 = new double[table.getRowCount()];
                boolean given = table.getRowCount() > 0;
                for (int k = 0; k < p0.length; k++) {
                    try {
                        p0[k] = Double.parseDouble(String.valueOf(table.getValueAt(k, 1)).strip());
                    } catch (NumberFormatException blank) {
                        given = false;
                    }
                }
                String option = (likelihood.isSelected() ? "L" : "") + (ignore.isSelected() ? "W" : "") + (add.isSelected() ? "+" : "")
                    + "Q" + (draw.isSelected() ? "" : "N") + (minosBox.isSelected() ? "E" : "") + (gradBox.isSelected() ? "G" : "");
                RootFitter.Options.setDefaults(String.valueOf(algorithm.getSelectedItem()),
                    Integer.parseInt(String.valueOf(strategy.getSelectedItem())), -1);
                double a = Double.NaN;
                double b = Double.NaN;
                try {
                    a = Double.parseDouble(from.getText().strip());
                    b = Double.parseDouble(to.getText().strip());
                } catch (NumberFormatException ignored) {
                    // The pad's range.
                }
                t.canvas.checkpoint();
                final RootDataActions.Fitted r = RootDataActions.fit(t, formula, option, "", Double.isFinite(a) ? a : 0,
                    Double.isFinite(b) ? b : 0, given ? p0 : null);
                t.canvas.changed();
                for (int k = 0; k < r.result().p().length && k < table.getRowCount(); k++) {
                    table.setValueAt(String.format(Locale.ROOT, "%.6g", r.result().p()[k]), k, 2);
                    table.setValueAt(String.format(Locale.ROOT, "%.3g", r.result().err()[k]), k, 3);
                }
                report.setText(r.result().report(t.title(), formula));
                report.setCaretPosition(0);
                pulls.setScene(pullScene(t, r.function()));
                if (!quiet.isSelected()) t.canvas.host().show("Fit of " + t.name(), report.getText());
                t.canvas.host().status(r.message());
            } catch (RuntimeException bad) {
                report.setText("The fit failed: " + bad.getMessage());
            }
        });
        final JButton reset = new JButton("Reset");
        reset.addActionListener(e -> refresh.run());
        final JButton close = new JButton("Close");
        close.addActionListener(e -> d.dispose());

        final JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
        final GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;
        form.add(new JLabel("Data set"), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(dataSet, c);
        c.gridy++;
        c.gridx = 0;
        c.weightx = 0;
        form.add(new JLabel("Function"), c);
        c.gridx = 1;
        form.add(function, c);
        c.gridy++;
        c.gridx = 0;
        form.add(new JLabel("Range"), c);
        final JPanel range = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        range.add(from);
        range.add(new JLabel("to"));
        range.add(to);
        c.gridx = 1;
        form.add(range, c);
        c.gridy++;
        c.gridx = 0;
        form.add(new JLabel("Method"), c);
        final JPanel methods = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        methods.add(chi2);
        methods.add(likelihood);
        c.gridx = 1;
        form.add(methods, c);
        c.gridy++;
        c.gridx = 0;
        form.add(new JLabel("Options"), c);
        final JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        options.add(ignore);
        options.add(add);
        options.add(quiet);
        options.add(draw);
        c.gridx = 1;
        form.add(options, c);
        c.gridy++;
        c.gridx = 0;
        form.add(new JLabel("Minimizer"), c);
        final JPanel minimizer = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        minimizer.add(new JLabel("Minuit2"));
        minimizer.add(algorithm);
        minimizer.add(new JLabel("strategy"));
        minimizer.add(strategy);
        minimizer.add(minosBox);
        minimizer.add(gradBox);
        c.gridx = 1;
        form.add(minimizer, c);
        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.BOTH;
        c.weighty = 1;
        form.add(new JScrollPane(params), c);

        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(reset);
        buttons.add(fit);
        buttons.add(close);
        final JPanel top = new JPanel(new BorderLayout());
        top.add(form, BorderLayout.CENTER);
        top.add(buttons, BorderLayout.SOUTH);
        final JSplitPane results = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(report), pulls);
        results.setResizeWeight(0.4);
        final JSplitPane all = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, results);
        all.setResizeWeight(0.45);
        d.getContentPane().add(all);
        d.getRootPane().setDefaultButton(fit);
        refresh.run();
        d.pack();
        d.setLocationRelativeTo(start.canvas);
        d.setVisible(true);
    }

    /** What a fit panel opened on a stack or a multigraph fits: its first member; else the target itself. */
    static RootTarget dataTarget(RootTarget t) {
        final Item i = t.item();
        if (i instanceof Group g) {
            for (Item m : g.items) {
                if (m instanceof Hist || m instanceof Graph) {
                    return new RootTarget(m.className, m, t.pad, t.scene, t.canvas, t.at, t.x, t.y);
                }
            }
        }
        return t;
    }

    /** Every histogram and graph of the canvas that can be fitted, the target first. */
    static List<RootTarget> dataSets(RootTarget t) {
        final List<RootTarget> out = new ArrayList<>();
        out.add(dataTarget(t));
        for (Pad p : t.scene.pad.flatten()) {
            for (Item i : p.items) {
                final List<Item> members = new ArrayList<>();
                if (i instanceof Group g) members.addAll(g.items);
                else members.add(i);
                for (Item m : members) {
                    if (m == out.get(0).item()) continue;
                    if (m instanceof Hist h && !h.isFunction() && h.dim <= 2 || m instanceof Graph || m instanceof Graph2D) {
                        out.add(new RootTarget(m.className, m, p, t.scene, t.canvas, null, Double.NaN, Double.NaN));
                    }
                }
            }
        }
        return out;
    }

    /** The points of the data: x (and y), value, error. */
    static double[][] points(RootTarget t) {
        final List<double[]> out = new ArrayList<>();
        final Item i = t.item();
        if (i instanceof Hist h && h.dim == 1) {
            for (int k = 0; k < h.nx(); k++) out.add(new double[]{h.x.center(k), 0, h.at(k, 0), RootDataActions.err(h, k)});
        } else if (i instanceof Hist h && h.dim == 2) {
            for (int iy = 0; iy < h.ny(); iy++) {
                for (int ix = 0; ix < h.nx(); ix++) {
                    final int k = iy * h.nx() + ix;
                    out.add(new double[]{h.x.center(ix), h.y.center(iy), h.v[k], RootDataActions.err(h, k)});
                }
            }
        } else if (i instanceof Graph g) {
            for (int k = 0; k < g.x.length; k++) {
                out.add(new double[]{g.x[k], 0, g.y[k], g.eyl == null ? 1 : 0.5 * (g.eyl[k] + g.eyh[k])});
            }
        } else if (i instanceof Graph2D g) {
            for (int k = 0; k < g.x.length; k++) out.add(new double[]{g.x[k], g.y[k], g.z[k], 1});
        }
        return out.toArray(new double[0][]);
    }

    private static double[][] xyOf(double[][] pts) {
        final double[][] out = new double[pts.length][];
        for (int k = 0; k < pts.length; k++) out[k] = new double[]{pts[k][0], pts[k][1]};
        return out;
    }

    private static double[] yOf(double[][] pts) {
        final double[] out = new double[pts.length];
        for (int k = 0; k < pts.length; k++) out[k] = pts[k][2];
        return out;
    }

    /** The pulls of a fit: (data - function) / error against x, and their distribution. */
    static RootScene pullScene(RootTarget t, Hist function) {
        final DoubleBinaryOperator f = RootDataActions.evaluator(function);
        final RootScene s = new RootScene();
        s.width = 900;
        s.height = 300;
        s.optStat = 1110;
        if (f == null) return s;
        final double lo = function.x.lo;
        final double hi = function.x.hi;
        final List<Double> xs = new ArrayList<>();
        final List<Double> ps = new ArrayList<>();
        for (double[] p : points(t)) {
            if (p[3] <= 0 || p[0] < lo || p[0] > hi) continue;
            if (function.dim == 2 && (p[1] < function.y.lo || p[1] > function.y.hi)) continue;
            xs.add(p[0]);
            ps.add((p[2] - f.applyAsDouble(p[0], p[1])) / p[3]);
        }
        final Graph g = new Graph();
        g.kind = "g";
        g.className = "TGraph";
        g.name = "pulls";
        g.title = "Pulls of " + t.name();
        g.x = xs.stream().mapToDouble(Double::doubleValue).toArray();
        g.y = ps.stream().mapToDouble(Double::doubleValue).toArray();
        g.markerStyle = 20;
        g.markerSize = 0.7;
        g.option = "AP";
        g.xTitle = "x";
        g.yTitle = "(data - fit) / error";
        final RootScene.Axis axis = RootDataActions.axis(40, -5, 5, "pull");
        final double[] counts = new double[40];
        double sum = 0;
        double sum2 = 0;
        for (double p : g.y) {
            final int b = axis.bin(p);
            if (b >= 0) counts[b]++;
            sum += p;
            sum2 += p * p;
        }
        final Hist dist = RootDataActions.newHist("TH1D", "pull", "Pull distribution", axis, counts, null);
        dist.entries = g.y.length;
        dist.fill = RootColors.color(38);
        dist.fillStyle = 1001;
        final int n = Math.max(1, g.y.length);
        dist.mean = sum / n;
        dist.std = Math.sqrt(Math.max(0, sum2 / n - dist.mean * dist.mean));

        final Pad left = new Pad();
        left.name = "pulls_x";
        left.pw = 0.62;
        left.lm = 0.12;
        left.rm = 0.03;
        left.bm = 0.18;
        left.items.add(g);
        for (double y : new double[]{-2, 0, 2}) {
            final Segment zero = new Segment();
            zero.kind = "line";
            zero.className = "TLine";
            zero.x1 = g.x.length == 0 ? lo : java.util.Arrays.stream(g.x).min().getAsDouble();
            zero.x2 = g.x.length == 0 ? hi : java.util.Arrays.stream(g.x).max().getAsDouble();
            zero.y1 = y;
            zero.y2 = y;
            zero.line = RootColors.color(y == 0 ? 2 : 15);
            zero.lineStyle = y == 0 ? 1 : 2;
            left.items.add(zero);
        }
        final Pad right = new Pad();
        right.name = "pulls_dist";
        right.px = 0.62;
        right.pw = 0.38;
        right.lm = 0.14;
        right.rm = 0.05;
        right.bm = 0.18;
        right.items.add(dist);
        s.pad.pads.add(left);
        s.pad.pads.add(right);
        s.name = "pulls";
        s.title = String.format(Locale.ROOT, "pulls: mean %.3f, width %.3f (1 for a good model)", dist.mean, dist.std);
        return s;
    }
}
