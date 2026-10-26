package com.sphere.core.rootlab.hist;

import com.sphere.core.rootio.RObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A THnSparse: only the filled cells are kept, each under its coordinates.
 *
 * On file, the cells sit in chunks (THnSparseArrayChunk): one buffer of
 * compact coordinates -- for each axis its bin number, under- and overflow
 * included, in just enough bits for nbins + 2 values, packed least
 * significant bit first (THnSparseCompactBinCoord) -- and the contents and
 * squared weights in the same order. Decoding that buffer is what reads the
 * sparse histogram of stressHistogram's reference file.
 */
public final class LabSparse {

    public final String name;
    public final LabAxis[] axes;
    /** Content and sum of squared weights per cell, keyed by the packed coordinates. */
    private final Map<Long, double[]> cells = new HashMap<>();
    public boolean errors = true;
    public double entries;

    public LabSparse(String name, LabAxis[] axes) {
        this.name = name;
        this.axes = axes;
    }

    private long key(int[] bins) {
        long k = 0;
        for (int d = axes.length - 1; d >= 0; d--) {
            k = k * (axes[d].nbins + 2) + bins[d];
        }
        return k;
    }

    /** THnSparse::Fill(x, w): the cell of the point, created when first met. */
    public void fill(double[] x, double w) {
        final int[] bins = new int[axes.length];
        for (int d = 0; d < axes.length; d++) {
            bins[d] = axes[d].findBin(x[d]);
        }
        final double[] c = cells.computeIfAbsent(key(bins), k -> new double[2]);
        c[0] += w;
        c[1] += w * w;
        entries++;
    }

    public double content(int[] bins) {
        final double[] c = cells.get(key(bins));
        return c == null ? 0 : c[0];
    }

    /** THnSparse::GetBinError: from the squared weights when they are kept, else the root of the content. */
    public double error(int[] bins) {
        final double[] c = cells.get(key(bins));
        if (c == null) {
            return 0;
        }
        return errors ? Math.sqrt(c[1]) : Math.sqrt(Math.abs(c[0]));
    }

    public int filledCells() {
        return cells.size();
    }

    /** The number of bits THnSparseCompactBinCoord gives an axis of n values. */
    static int numBits(int n) {
        int r = n > 0 ? 1 : 0;
        while ((n /= 2) != 0) {
            ++r;
        }
        return r;
    }

    /** A THnSparse read by Sphere's object reader. */
    public static LabSparse fromObject(RObject o) {
        final List<Object> axisObjects = o.list("fAxes");
        final LabAxis[] axes = new LabAxis[axisObjects.size()];
        for (int i = 0; i < axes.length; i++) {
            axes[i] = LabAxis.of((RObject) axisObjects.get(i));
        }
        final LabSparse s = new LabSparse(o.string("fName"), axes);
        s.entries = o.real("fEntries");
        s.errors = o.real("fTsumw2") >= 0;
        final int[] offsets = new int[axes.length + 1];
        int shift = 0;
        for (int d = 0; d < axes.length; d++) {
            offsets[d] = shift;
            shift += numBits(axes[d].nbins + 2);
        }
        offsets[axes.length] = shift;
        final long filled = o.number("fFilledBins");
        long seen = 0;
        for (Object c : o.list("fBinContent")) {
            if (!(c instanceof RObject chunk)) {
                continue;
            }
            final int size = chunk.integer("fSingleCoordinateSize");
            final byte[] coords = chunk.get("fCoordinates") instanceof byte[] b ? b : new byte[0];
            final double[] content = LabHist.doubles(chunk.get("fContent"));
            final double[] sumw2 = LabHist.doubles(chunk.get("fSumw2"));
            final int n = size > 0 ? coords.length / size : 0;
            for (int i = 0; i < n && seen < filled; i++, seen++) {
                final int[] bins = new int[axes.length];
                for (int d = 0; d < axes.length; d++) {
                    bins[d] = (int) bitsAt(coords, i * size, offsets[d], offsets[d + 1] - offsets[d]);
                }
                final double[] cell = s.cells.computeIfAbsent(s.key(bins), k -> new double[2]);
                cell[0] += i < content.length ? content[i] : 0;
                cell[1] += i < sumw2.length ? sumw2[i] : (i < content.length ? content[i] : 0);
            }
        }
        return s;
    }

    /** Reads nbits from the bit position `from` of a record, least significant bit first. */
    static long bitsAt(byte[] buf, int recordStart, int from, int nbits) {
        long value = 0;
        for (int b = 0; b < nbits; b++) {
            final int bit = from + b;
            final int at = recordStart + bit / 8;
            if (((buf[at] >> (bit % 8)) & 1) != 0) {
                value |= 1L << b;
            }
        }
        return value;
    }

    /** stressHistogram's equals(msg, THnBase*, THnBase*): every cell of a 3-dimensional sparse histogram. */
    public static HistCompare.Outcome equals(LabSparse h1, LabSparse h2, boolean compareError, double errorLimit) {
        final HistCompare.Outcome out = new HistCompare.Outcome();
        if (h1.axes.length != 3 || h2.axes.length != 3) {
            out.note("not three-dimensional");
            return out;
        }
        for (int d = 0; d < 3; d++) {
            if (h1.axes[d].nbins != h2.axes[d].nbins) {
                out.note("axis " + d + " has " + h1.axes[d].nbins + " vs " + h2.axes[d].nbins + " bins");
                return out;
            }
        }
        for (int i = 0; i <= h1.axes[0].nbins + 1; ++i) {
            for (int j = 0; j <= h1.axes[1].nbins + 1; ++j) {
                for (int h = 0; h <= h1.axes[2].nbins + 1; ++h) {
                    final int[] bin = {i, j, h};
                    final String cell = "cell (" + i + "," + j + "," + h + ")";
                    if (HistCompare.differ(h1.axes[0].binCenter(i), h2.axes[0].binCenter(i), errorLimit)
                        || HistCompare.differ(h1.axes[1].binCenter(j), h2.axes[1].binCenter(j), errorLimit)
                        || HistCompare.differ(h1.axes[2].binCenter(h), h2.axes[2].binCenter(h), errorLimit)) {
                        out.note(cell + " center");
                    }
                    if (HistCompare.differ(h1.content(bin), h2.content(bin), errorLimit)) {
                        out.note(cell + " content " + h1.content(bin) + " vs " + h2.content(bin));
                    }
                    if (compareError && HistCompare.differ(h1.error(bin), h2.error(bin), errorLimit)) {
                        out.note(cell + " error " + h1.error(bin) + " vs " + h2.error(bin));
                    }
                }
            }
        }
        return out;
    }
}
