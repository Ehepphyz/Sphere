package com.sphere.components.rootview;

import com.sphere.components.rootview.RootPadPainter.View;
import com.sphere.components.rootview.RootScene.Hist;
import com.sphere.components.rootview.RootScene.Item;
import com.sphere.components.rootview.RootScene.Pad;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The panels ROOT opens from a context menu: TH1::DrawPanel and
 * TGraph::DrawPanel (the draw options, the range, the scales), the window
 * that TH2::SetShowProjectionX/Y fills as the mouse moves, and for
 * TH3::SetShowProjection a slider through the third axis whose slice is
 * drawn live.
 */
final class RootPanels {

    private RootPanels() {
    }

    /* ------------------------------------------------------------------ */
    /* DrawPanel                                                           */
    /* ------------------------------------------------------------------ */

    static void drawPanel(RootTarget t) {
        final Item main = t.item();
        final Pad pad = t.pad;
        final View v = t.canvas.view(pad);
        final Window owner = SwingUtilities.getWindowAncestor(t.canvas);
        final JDialog d = new JDialog(owner, "DrawPanel — " + t.title(), java.awt.Dialog.ModalityType.MODELESS);
        final String[] options = RootCanvasView.options(main);
        final JPanel choices = new JPanel(new GridLayout(0, 4, 4, 2));
        choices.setBorder(BorderFactory.createTitledBorder("Draw option"));
        final ButtonGroup group = new ButtonGroup();
        final String current = t.canvas.optionOf(pad);
        final JTextField other = new JTextField(current, 14);
        for (String o : options) {
            final JRadioButton r = new JRadioButton(o, o.equalsIgnoreCase(current));
            r.addActionListener(e -> other.setText(o));
            group.add(r);
            choices.add(r);
        }
        final JPanel flags = new JPanel(new GridLayout(0, 3, 4, 2));
        flags.setBorder(BorderFactory.createTitledBorder("Pad"));
        final JCheckBox logx = new JCheckBox("Log X", RootPadPainter.log(v.logx, pad.logx));
        final JCheckBox logy = new JCheckBox("Log Y", RootPadPainter.log(v.logy, pad.logy));
        final JCheckBox logz = new JCheckBox("Log Z", RootPadPainter.log(v.logz, pad.logz));
        final JCheckBox gridx = new JCheckBox("Grid X", RootPadPainter.log(v.gridx, pad.gridx));
        final JCheckBox gridy = new JCheckBox("Grid Y", RootPadPainter.log(v.gridy, pad.gridy));
        final JCheckBox stats = new JCheckBox("Statistics", !v.statsOff);
        for (JCheckBox c : new JCheckBox[]{logx, logy, logz, gridx, gridy, stats}) flags.add(c);
        final JPanel range = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        range.setBorder(BorderFactory.createTitledBorder("X range"));
        final JTextField lo = new JTextField(String.format(Locale.ROOT, "%.6g", v.xr != null ? v.xr[0] : v.x0), 9);
        final JTextField hi = new JTextField(String.format(Locale.ROOT, "%.6g", v.xr != null ? v.xr[1] : v.x1), 9);
        range.add(new JLabel("from"));
        range.add(lo);
        range.add(new JLabel("to"));
        range.add(hi);
        final JPanel optionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        optionRow.add(new JLabel("Option"));
        optionRow.add(other);
        final Runnable apply = () -> {
            t.canvas.checkpoint();
            v.logx = logx.isSelected();
            v.logy = logy.isSelected();
            v.logz = logz.isSelected();
            v.gridx = gridx.isSelected();
            v.gridy = gridy.isSelected();
            v.statsOff = !stats.isSelected();
            try {
                final double a = Double.parseDouble(lo.getText().strip());
                final double b = Double.parseDouble(hi.getText().strip());
                if (b > a) v.xr = new double[]{a, b};
            } catch (NumberFormatException ignored) {
                // The range stays as it was.
            }
            final String o = other.getText().strip();
            if (!o.equalsIgnoreCase(t.canvas.optionOf(pad))) t.canvas.setOption(pad, o);
            t.canvas.changed();
        };
        final JButton draw = new JButton("Draw");
        draw.addActionListener(e -> apply.run());
        final JButton defaults = new JButton("Defaults");
        defaults.addActionListener(e -> {
            t.canvas.checkpoint();
            v.reset();
            t.canvas.setOption(pad, null);
            t.canvas.changed();
        });
        final JButton close = new JButton("Close");
        close.addActionListener(e -> d.dispose());
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(defaults);
        buttons.add(draw);
        buttons.add(close);
        final JPanel body = new JPanel();
        body.setLayout(new javax.swing.BoxLayout(body, javax.swing.BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 8));
        body.add(choices);
        body.add(optionRow);
        body.add(flags);
        body.add(range);
        d.getContentPane().setLayout(new BorderLayout());
        d.getContentPane().add(body, BorderLayout.CENTER);
        d.getContentPane().add(buttons, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(draw);
        d.pack();
        d.setLocationRelativeTo(t.canvas);
        d.setVisible(true);
    }

    /* ------------------------------------------------------------------ */
    /* TH2::SetShowProjectionX/Y                                           */
    /* ------------------------------------------------------------------ */

    /** The windows a 2D pad's projections are drawn in, one per pad and axis. */
    private static final Map<Pad, ProjectionWindow[]> PROJECTIONS = new IdentityHashMap<>();

    static final class ProjectionWindow {
        final JDialog dialog;
        final RootCanvasView view = new RootCanvasView();
        final boolean onX;

        ProjectionWindow(RootCanvasView parent, Pad pad, boolean onX) {
            this.onX = onX;
            dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), (onX ? "ProjectionX of " : "ProjectionY of ")
                + (pad.main() == null ? pad.name : pad.main().name), java.awt.Dialog.ModalityType.MODELESS);
            view.setPreferredSize(new Dimension(520, 360));
            view.setHost(parent.host());
            dialog.getContentPane().add(view);
            dialog.pack();
            final java.awt.Point at = parent.getLocationOnScreen();
            dialog.setLocation(at.x + parent.getWidth() + 8 - (onX ? 0 : 40), at.y + (onX ? 0 : 380));
            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    if (onX) pad.showProjectionX = 0;
                    else pad.showProjectionY = 0;
                    final ProjectionWindow[] pair = PROJECTIONS.get(pad);
                    if (pair != null) pair[onX ? 0 : 1] = null;
                }
            });
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.setVisible(true);
        }
    }

    /** Opens or closes the windows the pad asks for. */
    static void projections(RootCanvasView canvas, Pad pad) {
        final ProjectionWindow[] pair = PROJECTIONS.computeIfAbsent(pad, p -> new ProjectionWindow[2]);
        if (pad.showProjectionX > 0 && pair[0] == null) pair[0] = new ProjectionWindow(canvas, pad, true);
        if (pad.showProjectionY > 0 && pair[1] == null) pair[1] = new ProjectionWindow(canvas, pad, false);
        if (pad.showProjectionX <= 0 && pair[0] != null) {
            pair[0].dialog.dispose();
            pair[0] = null;
        }
        if (pad.showProjectionY <= 0 && pair[1] != null) {
            pair[1].dialog.dispose();
            pair[1] = null;
        }
    }

    /** The mouse is over the bin (ix, iy) of the pad's TH2: its projections are drawn for the bins around it. */
    static void follow(Pad pad, Hist h, int ix, int iy) {
        final ProjectionWindow[] pair = PROJECTIONS.get(pad);
        if (pair == null || h == null || h.dim != 2) return;
        if (pair[0] != null && pad.showProjectionX > 0) {
            final int half = (pad.showProjectionX - 1) / 2;
            final int first = Math.max(1, iy + 1 - half);
            final int last = Math.min(h.ny(), first + pad.showProjectionX - 1);
            final Hist p = RootDataActions.project(h, true, h.name + "_px", first, last);
            p.title = String.format(Locale.ROOT, "%s: y bins %d-%d [%.4g, %.4g]", h.name, first, last, h.y.edge(first - 1),
                h.y.edge(last));
            p.line = RootColors.color(4);
            show(pair[0].view, p);
        }
        if (pair[1] != null && pad.showProjectionY > 0) {
            final int half = (pad.showProjectionY - 1) / 2;
            final int first = Math.max(1, ix + 1 - half);
            final int last = Math.min(h.nx(), first + pad.showProjectionY - 1);
            final Hist p = RootDataActions.project(h, false, h.name + "_py", first, last);
            p.title = String.format(Locale.ROOT, "%s: x bins %d-%d [%.4g, %.4g]", h.name, first, last, h.x.edge(first - 1),
                h.x.edge(last));
            p.line = RootColors.color(2);
            show(pair[1].view, p);
        }
    }

    static void show(RootCanvasView view, Item item) {
        final RootScene s = new RootScene();
        s.name = item.name;
        s.title = item.title;
        s.width = 600;
        s.height = 400;
        s.pad.lm = 0.12;
        s.pad.bm = 0.12;
        s.pad.rm = item instanceof Hist h && h.dim == 2 ? 0.15 : 0.05;
        s.pad.items.add(item);
        view.setScene(s);
    }

    /* ------------------------------------------------------------------ */
    /* TH3::SetShowProjection                                              */
    /* ------------------------------------------------------------------ */

    /**
     * A TH3 sliced along the axis its option leaves out: the slider walks
     * through it, nbins at a time, and the slice is drawn as the option asks
     * ("xy": a TH2 of y against x), with the profile of the slices in the
     * status line.
     */
    static void explore3D(RootTarget t, Hist h, String option, int nbins) {
        final int[] axes = RootDataActions.axesOf(option);
        int third = 0;
        while (third < 3 && (third == axes[0] || axes.length > 1 && third == axes[1])) third++;
        final int sliceAxis = third;
        final RootScene.Axis along = RootDataActions.axisOf(h, Math.min(2, third));
        final JDialog d = new JDialog(SwingUtilities.getWindowAncestor(t.canvas), "Slices of " + h.name + " (" + option + ")",
            java.awt.Dialog.ModalityType.MODELESS);
        final RootCanvasView view = new RootCanvasView();
        view.setHost(t.canvas.host());
        view.setPreferredSize(new Dimension(640, 480));
        final JSlider slider = new JSlider(1, Math.max(1, along.n), 1);
        final JSpinner width = new JSpinner(new SpinnerNumberModel(Math.max(1, nbins), 1, Math.max(1, along.n), 1));
        final JLabel where = new JLabel(" ");
        final JCheckBox all = new JCheckBox("Whole axis");
        final Runnable update = () -> {
            final int w = (Integer) width.getValue();
            final int first = all.isSelected() ? 0 : slider.getValue() - 1;
            final int last = all.isSelected() ? along.n - 1 : Math.min(along.n - 1, first + w - 1);
            final Hist slice = RootDataActions.slice(h, option, Math.min(2, sliceAxis), first, last);
            slice.title = String.format(Locale.ROOT, "%s, %s in [%.4g, %.4g)", h.title, "xyz".charAt(Math.min(2, axisIndex(h, along))),
                along.edge(first), along.edge(last + 1));
            double sum = 0;
            for (double c : slice.v) sum += c;
            where.setText(String.format(Locale.ROOT, "bins %d-%d of %d   [%.4g, %.4g)   integral %.6g", first + 1, last + 1,
                along.n, along.edge(first), along.edge(last + 1), sum));
            final RootScene s = new RootScene();
            s.name = slice.name;
            s.title = slice.title;
            s.palette = t.scene.palette;
            s.pad.lm = 0.12;
            s.pad.bm = 0.12;
            s.pad.rm = slice.dim == 2 ? 0.15 : 0.05;
            slice.option = slice.dim == 2 ? "COLZ" : "HIST";
            s.pad.items.add(slice);
            view.setScene(s);
        };
        slider.addChangeListener(e -> update.run());
        width.addChangeListener(e -> update.run());
        all.addActionListener(e -> {
            slider.setEnabled(!all.isSelected());
            update.run();
        });
        final JButton play = new JButton("Play");
        final javax.swing.Timer timer = new javax.swing.Timer(120, e -> slider.setValue(slider.getValue() >= slider.getMaximum()
            ? slider.getMinimum() : slider.getValue() + 1));
        play.addActionListener(e -> {
            if (timer.isRunning()) {
                timer.stop();
                play.setText("Play");
            } else {
                timer.start();
                play.setText("Stop");
            }
        });
        final JButton keep = new JButton("Open slice");
        keep.addActionListener(e -> {
            final RootScene s = view.getScene();
            if (s != null) t.canvas.host().open(RootSceneJson.copy(s), s.title);
        });
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                timer.stop();
            }
        });
        final JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        controls.add(new JLabel("xyz".charAt(Math.min(2, axisIndex(h, along))) + " bin"));
        controls.add(slider);
        controls.add(new JLabel("width"));
        controls.add(width);
        controls.add(all);
        controls.add(play);
        controls.add(keep);
        final JPanel south = new JPanel(new BorderLayout());
        south.add(controls, BorderLayout.NORTH);
        where.setBorder(BorderFactory.createEmptyBorder(0, 8, 6, 8));
        south.add(where, BorderLayout.SOUTH);
        d.getContentPane().setLayout(new BorderLayout());
        d.getContentPane().add(view, BorderLayout.CENTER);
        d.getContentPane().add(south, BorderLayout.SOUTH);
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        update.run();
        d.pack();
        d.setLocationRelativeTo(t.canvas);
        d.setVisible(true);
    }

    private static int axisIndex(Hist h, RootScene.Axis a) {
        return a == h.x ? 0 : a == h.y ? 1 : 2;
    }
}
