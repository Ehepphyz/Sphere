package com.sphere.components.rootview;

import com.sphere.components.rootview.RootScene.AxisStyle;
import com.sphere.components.rootview.RootScene.Pad;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sphere's gStyle: what TPad::UseCurrentStyle, TCanvas::UseCurrentStyle
 * and TPaveStats::SaveStyle read and write, and the styles ROOT ships
 * (Modern, Plain, Pub, ATLAS...) to choose from in Edit > Style.
 */
public final class RootStyle {

    public String name = "Modern";
    public int optStat = 1111;
    public int optFit;
    public int optTitle = 1;
    public String statFormat = "6.4g";
    public String fitFormat = "5.4g";
    public String statOption = "br";
    public int tickx;
    public int ticky;
    public boolean gridx;
    public boolean gridy;
    public boolean logx;
    public boolean logy;
    public boolean logz;
    public double padLeft = 0.1;
    public double padRight = 0.1;
    public double padBottom = 0.1;
    public double padTop = 0.1;
    public final AxisStyle[] axes = {new AxisStyle(), new AxisStyle(), new AxisStyle()};

    private static RootStyle current = named("Modern");

    private RootStyle() {
    }

    public static RootStyle get() {
        return current;
    }

    /** A copy of the style's axis, for a pad that takes it. */
    public static AxisStyle axis(int k) {
        return current.axes[Math.max(0, Math.min(2, k))].copy();
    }

    /** What a canvas takes from the style: the statistics and fit boxes, the title. */
    public static void apply(RootScene s) {
        s.optStat = current.optStat;
        s.optFit = current.optFit;
        s.optTitle = current.optTitle;
        s.statFormat = current.statFormat;
        s.fitFormat = current.fitFormat;
        s.statOption = current.statOption;
    }

    /** The pad's margins too, as TPad::UseCurrentStyle sets them. */
    public static void applyMargins(Pad p) {
        p.lm = current.padLeft;
        p.rm = current.padRight;
        p.bm = current.padBottom;
        p.tm = current.padTop;
    }

    /** The styles ROOT ships, by name. */
    public static String[] names() {
        return new String[]{"Modern", "Classic", "Plain", "Pub", "Bold", "ATLAS", "BELLE2"};
    }

    public static RootStyle named(String name) {
        final RootStyle s = new RootStyle();
        s.name = name;
        switch (name) {
            case "Classic" -> {
                for (AxisStyle a : s.axes) {
                    a.labelFont = 62;
                    a.titleFont = 62;
                    a.labelSize = 0.04;
                    a.titleSize = 0.04;
                }
                s.optStat = 1;
            }
            case "Plain" -> {
                for (AxisStyle a : s.axes) a.labelFont = 42;
                s.optStat = 1111;
            }
            case "Pub" -> {
                for (AxisStyle a : s.axes) {
                    a.labelFont = 132;
                    a.titleFont = 132;
                    a.labelSize = 0.05;
                    a.titleSize = 0.06;
                }
                s.optStat = 0;
                s.optTitle = 0;
                s.padLeft = 0.15;
                s.padBottom = 0.15;
            }
            case "Bold" -> {
                for (AxisStyle a : s.axes) {
                    a.labelFont = 62;
                    a.titleFont = 62;
                    a.labelSize = 0.05;
                    a.titleSize = 0.05;
                    a.lineWidth = 2;
                }
            }
            case "ATLAS" -> {
                for (AxisStyle a : s.axes) {
                    a.labelFont = 42;
                    a.titleFont = 42;
                    a.labelSize = 0.05;
                    a.titleSize = 0.05;
                }
                s.axes[1].titleOffset = 1.4;
                s.axes[0].titleOffset = 1.4;
                s.optStat = 0;
                s.optTitle = 0;
                s.optFit = 0;
                s.tickx = 1;
                s.ticky = 1;
                s.padLeft = 0.16;
                s.padRight = 0.05;
                s.padBottom = 0.16;
                s.padTop = 0.05;
            }
            case "BELLE2" -> {
                for (AxisStyle a : s.axes) {
                    a.labelFont = 42;
                    a.titleFont = 42;
                    a.labelSize = 0.05;
                    a.titleSize = 0.05;
                }
                s.optStat = 0;
                s.optTitle = 0;
                s.tickx = 1;
                s.ticky = 1;
                s.padLeft = 0.15;
                s.padRight = 0.05;
                s.padBottom = 0.15;
                s.padTop = 0.05;
            }
            default -> {
            }
        }
        return s;
    }

    /** What the canvas shows becomes the style (TCanvas's "Save style" in Edit > Style). */
    public static void capture(RootScene s, Pad p) {
        current.optStat = s.optStat;
        current.optFit = s.optFit;
        current.optTitle = s.optTitle;
        current.statFormat = s.statFormat;
        current.fitFormat = s.fitFormat;
        current.statOption = s.statOption;
        if (p != null) {
            for (int k = 0; k < 3; k++) current.axes[k] = p.axes[k].copy();
            current.tickx = p.tickx;
            current.ticky = p.ticky;
            current.padLeft = p.lm;
            current.padRight = p.rm;
            current.padBottom = p.bm;
            current.padTop = p.tm;
        }
    }

    /** Edit > Style: a style of ROOT's, then its numbers; OK makes it the current style. */
    public static boolean edit(Component parent) {
        final JComboBox<String> base = new JComboBox<>(names());
        base.setSelectedItem(current.name);
        final RootStyle start = current;
        final Map<String, JComponent> fields = new LinkedHashMap<>();
        fields.put("OptStat (ksiourmen)", new JTextField(Integer.toString(start.optStat), 10));
        fields.put("OptFit (pcev)", new JTextField(Integer.toString(start.optFit), 10));
        fields.put("OptTitle", new JTextField(Integer.toString(start.optTitle), 10));
        fields.put("StatFormat", new JTextField(start.statFormat, 10));
        fields.put("FitFormat", new JTextField(start.fitFormat, 10));
        fields.put("Label size", new JTextField(Double.toString(start.axes[0].labelSize), 10));
        fields.put("Title size", new JTextField(Double.toString(start.axes[0].titleSize), 10));
        fields.put("Label font", new JTextField(Integer.toString(start.axes[0].labelFont), 10));
        fields.put("Y title offset", new JTextField(Double.toString(start.axes[1].titleOffset), 10));
        fields.put("Divisions (x)", new JTextField(Integer.toString(start.axes[0].ndivisions), 10));
        final JCheckBox ticks = new JCheckBox("Ticks on all four sides", start.tickx != 0);
        final JCheckBox grid = new JCheckBox("Grid", start.gridx);
        base.addActionListener(e -> {
            final RootStyle n = named(String.valueOf(base.getSelectedItem()));
            ((JTextField) fields.get("OptStat (ksiourmen)")).setText(Integer.toString(n.optStat));
            ((JTextField) fields.get("OptFit (pcev)")).setText(Integer.toString(n.optFit));
            ((JTextField) fields.get("OptTitle")).setText(Integer.toString(n.optTitle));
            ((JTextField) fields.get("Label size")).setText(Double.toString(n.axes[0].labelSize));
            ((JTextField) fields.get("Title size")).setText(Double.toString(n.axes[0].titleSize));
            ((JTextField) fields.get("Label font")).setText(Integer.toString(n.axes[0].labelFont));
            ((JTextField) fields.get("Y title offset")).setText(Double.toString(n.axes[1].titleOffset));
            ticks.setSelected(n.tickx != 0);
        });
        final JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        final GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridy = 0;
        c.gridx = 0;
        panel.add(new JLabel("Style"), c);
        c.gridx = 1;
        panel.add(base, c);
        for (Map.Entry<String, JComponent> e : fields.entrySet()) {
            c.gridy++;
            c.gridx = 0;
            panel.add(new JLabel(e.getKey()), c);
            c.gridx = 1;
            panel.add(e.getValue(), c);
        }
        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 2;
        panel.add(ticks, c);
        c.gridy++;
        panel.add(grid, c);
        if (JOptionPane.showConfirmDialog(parent, panel, "Style (gStyle)", JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return false;
        }
        try {
            final RootStyle s = named(String.valueOf(base.getSelectedItem()));
            s.optStat = Integer.parseInt(text(fields, "OptStat (ksiourmen)"));
            s.optFit = Integer.parseInt(text(fields, "OptFit (pcev)"));
            s.optTitle = Integer.parseInt(text(fields, "OptTitle"));
            s.statFormat = text(fields, "StatFormat");
            s.fitFormat = text(fields, "FitFormat");
            final double ls = Double.parseDouble(text(fields, "Label size"));
            final double ts = Double.parseDouble(text(fields, "Title size"));
            final int lf = Integer.parseInt(text(fields, "Label font"));
            for (AxisStyle a : s.axes) {
                a.labelSize = ls;
                a.titleSize = ts;
                a.labelFont = lf;
                a.titleFont = lf;
            }
            s.axes[1].titleOffset = Double.parseDouble(text(fields, "Y title offset"));
            s.axes[0].ndivisions = Integer.parseInt(text(fields, "Divisions (x)"));
            s.tickx = ticks.isSelected() ? 1 : 0;
            s.ticky = s.tickx;
            s.gridx = grid.isSelected();
            s.gridy = grid.isSelected();
            current = s;
            return true;
        } catch (NumberFormatException bad) {
            JOptionPane.showMessageDialog(parent, "Not a number: " + bad.getMessage(), "Style", JOptionPane.WARNING_MESSAGE);
            return false;
        }
    }

    private static String text(Map<String, JComponent> fields, String key) {
        return ((JTextField) fields.get(key)).getText().strip();
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s: OptStat %d, OptFit %d", name, optStat, optFit);
    }
}
