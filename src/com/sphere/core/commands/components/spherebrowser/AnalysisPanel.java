package com.sphere.components.spherebrowser;

import com.sphere.components.imaging.ImagingTheme;
import com.sphere.components.rootview.RootScene;
import com.sphere.components.rootview.RootScene.Hist;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * The analysis beside a plot: what a physicist does next with a histogram.
 *
 * Fit it (fifteen shapes of the trade, least squares or Poisson likelihood),
 * or let every shape be fitted and ranked by AICc; find its peaks and fit
 * them all at once over a background; let Bayesian Blocks choose its binning;
 * give its moments and quantiles; compare it with another histogram open in
 * the browser. A TH2 is projected, profiled and correlated. Each curve lands
 * on the plot; each new histogram opens in a tab of its own.
 */
final class AnalysisPanel extends JPanel {

    private final Hist hist;
    private final Runnable changed;
    private final BiConsumer<RootScene, String> open;
    private final Supplier<List<Hist>> others;
    private final JTextArea out = new JTextArea();
    private final JTextField lo = new JTextField(8);
    private final JTextField hi = new JTextField(8);
    private final JComboBox<HepAnalysis.Model> model = new JComboBox<>(HepAnalysis.MODELS.toArray(new HepAnalysis.Model[0]));
    private final JComboBox<String> method = new JComboBox<>(new String[]{"χ² (least squares)", "Poisson likelihood"});
    private final JTextField smooth = new JTextField("1.5", 4);
    private final JTextField threshold = new JTextField("5", 4);
    private final JTextField p0 = new JTextField("0.05", 5);
    private final JComboBox<String> background = new JComboBox<>(new String[]{"pol2", "pol3", "expo", "powerlaw", "tsallis", "pol1"});
    private final JTextField resolution = new JTextField(6);
    private final JTextField steps = new JTextField("80", 4);
    private List<HepAnalysis.Peak> lastPeaks = List.of();
    private HepAnalysis.Fit lastFit;
    private static final Color[] FIT_COLORS = {new Color(0xE03030), new Color(0x20A040), new Color(0xE08020),
        new Color(0x9040C0), new Color(0x10A0B0)};
    private int fits;

    AnalysisPanel(Hist hist, Runnable changed, BiConsumer<RootScene, String> open, Supplier<List<Hist>> others) {
        super(new BorderLayout());
        this.hist = hist;
        this.changed = changed;
        this.open = open;
        this.others = others;
        setBackground(ImagingTheme.panel());
        for (JTextField f : new JTextField[]{lo, hi, smooth, threshold, p0, resolution, steps}) SphereBrowser.readable(f);
        out.setEditable(false);
        out.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        out.setBackground(ImagingTheme.surface());
        out.setForeground(ImagingTheme.text());
        out.setCaretColor(ImagingTheme.text());
        out.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        final JPanel top = ImagingTheme.stack(false);
        top.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        if (hist == null) {
            final JLabel none = label("<html>This view holds no histogram to analyse: open one from a .root file, "
                + "or choose the pad that holds it in the bar above.</html>");
            none.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            none.setAlignmentX(Component.LEFT_ALIGNMENT);
            top.add(none);
        } else if (hist.dim == 1) {
            oneD(top);
        } else if (hist.dim == 2) {
            twoD(top);
        } else {
            top.add(row(label("A TH3: its 3D view shows iso-surfaces, voxels and clouds; "
                + "project it in ROOT to analyse a slice.")));
        }
        add(top, BorderLayout.NORTH);
        final JScrollPane scroll = new JScrollPane(out);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ImagingTheme.border()));
        add(scroll, BorderLayout.CENTER);
        if (hist != null) {
            out.setText(hist.dim == 1 ? HepAnalysis.statistics(hist, hist.x.lo, hist.x.hi)
                : hist.dim == 2 ? HepAnalysis.statistics2(hist) : "");
        }
    }

    private void oneD(JPanel top) {
        lo.setText(String.format(Locale.ROOT, "%.6g", hist.x.shown()[0]));
        hi.setText(String.format(Locale.ROOT, "%.6g", hist.x.shown()[1]));
        top.add(row(label(hist.name + "  —  range"), lo, label("to"), hi,
            button("Statistics", "Moments, quantiles, effective entries in the range", () ->
                show(HepAnalysis.statistics(hist, from(), to())))));
        top.add(row(label("Model"), model, method,
            button("Fit", "Levenberg-Marquardt fit of the model in the range", this::fit),
            button("Rank all models", "Fit every model and rank them by AICc (Akaike weights)", this::rank),
            button("Pulls panel", "The data and the last fit above, (data - fit)/σ below", this::pulls),
            button("Clear fits", "Remove the curves drawn by the analysis", () -> {
                hist.fits.removeIf(f -> f.name.startsWith("sphere_fit"));
                changed.run();
            })));
        top.add(row(label("Peaks: smoothing (bins)"), smooth, label("threshold %"), threshold,
            button("Find peaks", "Local maxima standing out of their surroundings", this::peaks),
            button("Fit the peaks", "All peaks found, as Gaussians over a pol2 background, fitted together",
                this::fitPeaks)));
        top.add(row(label("Bayesian Blocks p₀"), p0,
            button("Blocks", "Scargle's optimal binning: edges only where the rate truly changes", this::blocks),
            button("Compare with…", "Kolmogorov-Smirnov and χ² homogeneity with another open histogram", this::compare),
            button("Ratio with…", "The ratio to another open histogram, normalised", this::ratio)));
        final double r0 = (hist.x.hi - hist.x.lo) / 60;
        resolution.setText(String.format(Locale.ROOT, "%.4g", r0));
        top.add(row(label("Search: background"), background, label("resolution σ"), resolution, label("masses"), steps,
            button("Bump hunt", "Local p₀ scan, look-elsewhere effect (Gross-Vitells), 95% CLs limits (Brazil band)",
                this::hunt),
            button("Efficiency vs…", "This histogram as 'passed' over another as 'total': Clopper-Pearson, erf fit",
                this::efficiency)));
    }

    private void hunt() {
        final HepAnalysis.Data d = HepAnalysis.data(hist, from(), to());
        final String bkg = (String) background.getSelectedItem();
        final double sigma = Math.max(1e-12, parse(resolution, (hist.x.hi - hist.x.lo) / 60));
        final int n = (int) Math.max(5, Math.min(400, parse(steps, 80)));
        background("Scanning " + n + " masses over " + bkg + " ...", () -> {
            final HepAnalysis.Scan scan = HepAnalysis.bumpHunt(d, bkg, sigma, n);
            return () -> {
                show(HepAnalysis.scanReport(scan));
                if (scan.best == null) return;
                final RootScene.Group p0 = HepAnalysis.p0Plot(scan);
                final RootScene.Group limits = HepAnalysis.brazil(scan);
                final RootScene s = Scenes.stacked(p0, limits, hist.name + " — bump hunt");
                final RootScene.Pad upper = s.pad.pads.get(0);
                upper.items.addAll(HepAnalysis.sigmaLabels(p0));
                final RootScene.Pad lower = s.pad.pads.get(1);
                upper.logy = true;
                upper.gridx = true;
                upper.title = "local p₀";
                lower.ph = 0.45;
                upper.py = 0.45;
                upper.ph = 0.55;
                lower.bm = 0.16;
                upper.bm = 0.08;
                open.accept(s, hist.name + " bump hunt");
            };
        });
    }

    private void efficiency() {
        final Hist total = choose("Efficiency of");
        if (total == null) return;
        final RootScene.Graph eff = HepAnalysis.efficiency(hist, total);
        final StringBuilder text = new StringBuilder(eff.title).append("\n\n");
        final RootScene s = Scenes.single(eff, eff.title);
        if (eff.x.length >= 4) {
            final HepAnalysis.Fit f = HepAnalysis.fit(HepAnalysis.model("erf turn-on"), HepAnalysis.data(eff), false);
            text.append(HepAnalysis.report(f));
            if (f.converged) {
                text.append(String.format(Locale.ROOT, "%nplateau ε = %.4f ± %.4f   50%% point x₅₀ = %.5g ± %.3g   "
                    + "99%% of plateau at x = %.5g%n", f.p[0], f.err[0], f.p[1], f.err[1], f.p[1] + 2.326 * Math.abs(f.p[2])));
                final Hist curve = HepAnalysis.curve(f, "sphere_fit_eff", new Color(0xE03030));
                curve.option = "C SAME";
                s.pad.items.add(curve);
            }
        }
        open.accept(s, eff.name);
        show(text.toString());
    }

    private void twoD(JPanel top) {
        top.add(row(label(hist.name),
            button("Projection X", "Sum over y: a TH1 on x", () -> openHist(HepAnalysis.projection(hist, true))),
            button("Projection Y", "Sum over x: a TH1 on y", () -> openHist(HepAnalysis.projection(hist, false))),
            button("Profile X", "The mean of y in each x bin", () -> {
                final RootScene.Graph g = HepAnalysis.profile(hist);
                open.accept(Scenes.single(g, g.title), g.name);
            }),
            button("Correlation", "Means, RMS, covariance and correlation", () -> show(HepAnalysis.statistics2(hist)))));
    }

    private void openHist(Hist h) {
        open.accept(Scenes.single(h, h.title), h.name);
    }

    private double from() {
        return parse(lo, hist.x.lo);
    }

    private double to() {
        return parse(hi, hist.x.hi);
    }

    private static double parse(JTextField f, double fallback) {
        try {
            return Double.parseDouble(f.getText().strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean poisson() {
        return method.getSelectedIndex() == 1;
    }

    private void fit() {
        final HepAnalysis.Model m = (HepAnalysis.Model) model.getSelectedItem();
        final HepAnalysis.Data d = HepAnalysis.data(hist, from(), to());
        background("Fitting " + m.name + " ...", () -> {
            final HepAnalysis.Fit f = HepAnalysis.fit(m, d, poisson());
            return () -> {
                lastFit = f;
                draw(f);
                show(HepAnalysis.report(f));
            };
        });
    }

    private void rank() {
        final HepAnalysis.Data d = HepAnalysis.data(hist, from(), to());
        background("Fitting " + HepAnalysis.MODELS.size() + " models ...", () -> {
            final List<HepAnalysis.Fit> all = HepAnalysis.rank(d, poisson());
            return () -> {
                if (!all.isEmpty()) {
                    lastFit = all.get(0);
                    draw(lastFit);
                    model.setSelectedItem(lastFit.model);
                }
                show(HepAnalysis.rankReport(all));
            };
        });
    }

    private void draw(HepAnalysis.Fit f) {
        if (!f.converged && f.cov == null && f.p == null) return;
        hist.fits.add(HepAnalysis.curve(f, "sphere_fit_" + (++fits), FIT_COLORS[(fits - 1) % FIT_COLORS.length]));
        changed.run();
    }

    private void pulls() {
        if (lastFit == null) {
            show("Fit first: the pulls are the data against the last fit.");
            return;
        }
        final Hist pulls = HepAnalysis.pulls(hist, lastFit);
        open.accept(Scenes.stacked(hist, pulls, hist.name + " — " + lastFit.model.name + " and pulls"),
            hist.name + " pulls");
    }

    private void peaks() {
        final HepAnalysis.Data d = HepAnalysis.data(hist, from(), to());
        lastPeaks = HepAnalysis.findPeaks(d, parse(smooth, 1.5), parse(threshold, 5) / 100);
        show(HepAnalysis.peaksReport(lastPeaks));
    }

    private void fitPeaks() {
        if (lastPeaks.isEmpty()) peaks();
        if (lastPeaks.isEmpty()) return;
        final HepAnalysis.Model m = HepAnalysis.peaks(lastPeaks);
        final HepAnalysis.Data d = HepAnalysis.data(hist, from(), to());
        background("Fitting " + m.name + " ...", () -> {
            final HepAnalysis.Fit f = HepAnalysis.fit(m, d, poisson());
            return () -> {
                lastFit = f;
                draw(f);
                show(HepAnalysis.report(f));
            };
        });
    }

    private void blocks() {
        final Hist b = HepAnalysis.bayesianBlocks(hist, Math.max(1e-6, Math.min(0.5, parse(p0, 0.05))));
        show(String.format(Locale.ROOT, "Bayesian Blocks: %d blocks from %d bins (p₀ = %s).%n"
            + "Each block is a stretch of constant rate; an edge says the rate changed there.%n", b.nx(), hist.nx(),
            p0.getText().strip()));
        final Hist over = copyForOverlay(hist);
        final RootScene s = Scenes.single(over, b.title);
        s.pad.items.add(b);
        b.option = "HIST SAME";
        open.accept(s, b.name);
    }

    /** The histogram again, lighter, under what the analysis draws over it. */
    private static Hist copyForOverlay(Hist h) {
        final Hist c = new Hist();
        c.dim = 1;
        c.kind = "h1";
        c.className = h.className;
        c.name = h.name;
        c.title = h.title;
        c.x = h.x;
        c.v = h.v;
        c.err = h.err;
        c.yTitle = h.yTitle;
        c.option = "E1";
        c.marker = Color.BLACK;
        c.markerStyle = 20;
        c.markerSize = 0.6;
        c.line = new Color(0x404040);
        c.stats = false;
        return c;
    }

    private Hist choose(String what) {
        final List<Hist> list = others.get().stream().filter(h -> h != hist && h.dim == 1).toList();
        if (list.isEmpty()) {
            show("Open another 1D histogram in the browser first: " + what + " needs two.");
            return null;
        }
        final String[] names = list.stream().map(h -> h.name + "  (" + h.nx() + " bins)").toArray(String[]::new);
        final Object chosen = javax.swing.JOptionPane.showInputDialog(this, what + " " + hist.name + " with:",
            "Sphere TBrowser", javax.swing.JOptionPane.PLAIN_MESSAGE, null, names, names[0]);
        if (chosen == null) return null;
        for (int i = 0; i < names.length; i++) if (names[i].equals(chosen)) return list.get(i);
        return null;
    }

    private void compare() {
        final Hist other = choose("Compare");
        if (other != null) show(HepAnalysis.compare(hist, other));
    }

    private void ratio() {
        final Hist other = choose("Divide");
        if (other == null) return;
        final Hist r = HepAnalysis.ratio(hist, other);
        open.accept(Scenes.stacked(hist, r, r.title), r.name);
        show(HepAnalysis.compare(hist, other));
    }

    private void show(String text) {
        out.setText(text);
        out.setCaretPosition(0);
    }

    private interface Job {
        Runnable run();
    }

    private void background(String what, Job job) {
        show(what);
        new SwingWorker<Runnable, Void>() {
            @Override
            protected Runnable doInBackground() {
                return job.run();
            }

            @Override
            protected void done() {
                try {
                    get().run();
                } catch (Exception e) {
                    show("failed: " + (e.getCause() == null ? e.getMessage() : e.getCause().toString()));
                }
            }
        }.execute();
    }

    private static JLabel label(String s) {
        final JLabel l = new JLabel(s);
        l.setForeground(ImagingTheme.text());
        l.setFont(ImagingTheme.uiFont(Font.PLAIN, 12f));
        return l;
    }

    private static JButton button(String label, String tip, Runnable run) {
        final JButton b = ImagingTheme.textButton(label, tip);
        b.addActionListener(e -> run.run());
        return b;
    }

    private static JPanel row(Component... cs) {
        final JPanel p = ImagingTheme.panelOf(new FlowLayout(FlowLayout.LEFT, 5, 2));
        for (Component c : cs) p.add(c);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        return p;
    }
}
