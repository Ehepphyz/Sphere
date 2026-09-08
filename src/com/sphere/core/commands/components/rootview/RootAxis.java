package com.sphere.components.rootview;

/** A TAxis: how many bins, over what range, and their labels if uneven. */
public final class RootAxis {

    public String name = "";
    public String title = "";
    public int bins;
    public double min;
    public double max;
    /** Bin edges when the binning is not uniform; empty when it is. */
    public double[] edges = new double[0];

    public boolean isVariable() {
        return edges.length == bins + 1;
    }

    public double edge(int bin) {
        if (isVariable()) {
            return edges[Math.max(0, Math.min(bins, bin))];
        }
        if (bins <= 0) {
            return min;
        }
        return min + (max - min) * bin / bins;
    }

    public double center(int bin) {
        return (edge(bin) + edge(bin + 1)) / 2.0;
    }

    public double width(int bin) {
        return edge(bin + 1) - edge(bin);
    }

    /**
     * Reads a TAxis. The members up to the bin edges are the ones a viewer
     * needs; the attributes and the label list that follow are stepped over
     * through the byte count.
     */
    static RootAxis read(RootBuffer b) {
        RootAxis axis = new RootAxis();
        RootBuffer.Header header = b.beginObject();

        String[] named = b.readTNamed();
        axis.name = named[0];
        axis.title = named[1];

        // TAttAxis, whose own byte count says where it ends
        RootBuffer.Header attributes = b.beginObject();
        b.endObject(attributes);

        axis.bins = b.i32();
        axis.min = b.f64();
        axis.max = b.f64();
        axis.edges = b.readArrayD();

        b.endObject(header);
        return axis;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "%d bins, %.4g to %.4g",
                             bins, min, max);
    }
}
