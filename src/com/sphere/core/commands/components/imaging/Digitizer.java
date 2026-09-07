package com.sphere.components.imaging;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads numbers back out of a plot that only exists as a picture.
 *
 * Four reference clicks fix the axes, then every click on the curve becomes a
 * data point in the plot's own units. A published figure with no data behind it
 * is the common case in this field, and this is the way back.
 */
public final class Digitizer {

    /** One axis reference: a point on the image and the value it stands for. */
    public static final class Reference {
        public final Point2D.Double point;
        public final double value;

        public Reference(double x, double y, double value) {
            this.point = new Point2D.Double(x, y);
            this.value = value;
        }
    }

    public enum Axis { X, Y }

    public enum Scale { LINEAR, LOG10 }

    private Reference x1;
    private Reference x2;
    private Reference y1;
    private Reference y2;
    private Scale xScale = Scale.LINEAR;
    private Scale yScale = Scale.LINEAR;

    private final List<Point2D.Double> picked = new ArrayList<>();
    private String seriesName = "series";

    public void setReference(Axis axis, boolean second, double px, double py, double value) {
        Reference ref = new Reference(px, py, value);
        if (axis == Axis.X) {
            if (second) {
                x2 = ref;
            } else {
                x1 = ref;
            }
        } else {
            if (second) {
                y2 = ref;
            } else {
                y1 = ref;
            }
        }
    }

    public Reference getReference(Axis axis, boolean second) {
        if (axis == Axis.X) {
            return second ? x2 : x1;
        }
        return second ? y2 : y1;
    }

    public void setScale(Axis axis, Scale scale) {
        if (axis == Axis.X) {
            xScale = scale;
        } else {
            yScale = scale;
        }
    }

    public Scale getScale(Axis axis) {
        return axis == Axis.X ? xScale : yScale;
    }

    public String getSeriesName() {
        return seriesName;
    }

    public void setSeriesName(String name) {
        this.seriesName = name == null || name.isBlank() ? "series" : name.trim();
    }

    /** True once both axes have two references that are usable. */
    public boolean isCalibrated() {
        return usable(x1, x2, Axis.X) && usable(y1, y2, Axis.Y);
    }

    private boolean usable(Reference a, Reference b, Axis axis) {
        if (a == null || b == null) {
            return false;
        }
        final double pixelSpan = axis == Axis.X
            ? b.point.x - a.point.x : b.point.y - a.point.y;
        if (Math.abs(pixelSpan) < 1e-6) {
            return false;
        }
        final Scale scale = axis == Axis.X ? xScale : yScale;
        if (scale == Scale.LOG10 && (a.value <= 0 || b.value <= 0)) {
            return false;
        }
        return Math.abs(b.value - a.value) > 0;
    }

    /** What is still missing, phrased for the panel that guides the user. */
    public String missing() {
        if (x1 == null) {
            return "Click the first point on the x axis, then give its value.";
        }
        if (x2 == null) {
            return "Click a second point on the x axis, further along.";
        }
        if (y1 == null) {
            return "Click the first point on the y axis, then give its value.";
        }
        if (y2 == null) {
            return "Click a second point on the y axis, further along.";
        }
        if (!usable(x1, x2, Axis.X)) {
            return xScale == Scale.LOG10 && (x1.value <= 0 || x2.value <= 0)
                ? "A logarithmic x axis needs two positive values."
                : "The two x references must differ, in both position and value.";
        }
        if (!usable(y1, y2, Axis.Y)) {
            return yScale == Scale.LOG10 && (y1.value <= 0 || y2.value <= 0)
                ? "A logarithmic y axis needs two positive values."
                : "The two y references must differ, in both position and value.";
        }
        return "";
    }

    // ---- conversion --------------------------------------------------------

    public Point2D.Double toData(double px, double py) {
        return new Point2D.Double(axisValue(Axis.X, px), axisValue(Axis.Y, py));
    }

    private double axisValue(Axis axis, double pixel) {
        final Reference a = axis == Axis.X ? x1 : y1;
        final Reference b = axis == Axis.X ? x2 : y2;
        final Scale scale = axis == Axis.X ? xScale : yScale;
        if (a == null || b == null) {
            return Double.NaN;
        }
        final double pa = axis == Axis.X ? a.point.x : a.point.y;
        final double pb = axis == Axis.X ? b.point.x : b.point.y;
        if (Math.abs(pb - pa) < 1e-9) {
            return Double.NaN;
        }
        final double t = (pixel - pa) / (pb - pa);

        if (scale == Scale.LOG10) {
            if (a.value <= 0 || b.value <= 0) {
                return Double.NaN;
            }
            final double la = Math.log10(a.value);
            final double lb = Math.log10(b.value);
            return Math.pow(10.0, la + t * (lb - la));
        }
        return a.value + t * (b.value - a.value);
    }

    // ---- picked points -----------------------------------------------------

    public List<Point2D.Double> getPicked() {
        return picked;
    }

    public void pick(double px, double py) {
        picked.add(new Point2D.Double(px, py));
    }

    public void removeLast() {
        if (!picked.isEmpty()) {
            picked.remove(picked.size() - 1);
        }
    }

    public void clearPicked() {
        picked.clear();
    }

    public void reset() {
        x1 = null;
        x2 = null;
        y1 = null;
        y2 = null;
        picked.clear();
    }

    /** The picked points converted to data, in the order they were clicked. */
    public List<Point2D.Double> toDataSeries() {
        List<Point2D.Double> out = new ArrayList<>(picked.size());
        for (Point2D.Double p : picked) {
            out.add(toData(p.x, p.y));
        }
        return out;
    }

    public String toCsv() {
        StringBuilder sb = new StringBuilder("x,y\n");
        for (Point2D.Double p : toDataSeries()) {
            sb.append(format(p.x)).append(',').append(format(p.y)).append('\n');
        }
        return sb.toString();
    }

    /** The series as a Python assignment, ready to paste into the notebook. */
    public String toPython() {
        List<Point2D.Double> data = toDataSeries();
        StringBuilder xs = new StringBuilder();
        StringBuilder ys = new StringBuilder();
        for (int i = 0; i < data.size(); i++) {
            if (i > 0) {
                xs.append(", ");
                ys.append(", ");
            }
            xs.append(format(data.get(i).x));
            ys.append(format(data.get(i).y));
        }
        return seriesName + "_x = [" + xs + "]\n"
             + seriesName + "_y = [" + ys + "]\n";
    }

    private static String format(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "nan";
        }
        final double magnitude = Math.abs(v);
        if (magnitude != 0 && (magnitude < 1e-3 || magnitude >= 1e6)) {
            return String.format(Locale.ROOT, "%.6g", v);
        }
        return String.format(Locale.ROOT, "%.6f", v)
                     .replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
