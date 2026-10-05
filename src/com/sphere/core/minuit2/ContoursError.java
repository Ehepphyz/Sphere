package com.sphere.core.minuit2;

import com.sphere.core.hepmc3.cxx.COStream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A contour of two parameters: its points, with the Minos errors of each parameter. */
public final class ContoursError implements MnPrint.Printable {

    private final int parX;
    private final int parY;
    private final List<MnPrint.Point> points;
    private final MinosError xMinos;
    private final MinosError yMinos;
    private final int nfcn;

    public ContoursError(int parX, int parY, List<MnPrint.Point> points, MinosError xmnos, MinosError ymnos, int nfcn) {
        this.parX = parX;
        this.parY = parY;
        this.points = new ArrayList<>(points);
        this.xMinos = xmnos;
        this.yMinos = ymnos;
        this.nfcn = nfcn;
    }

    public List<MnPrint.Point> points() {
        return Collections.unmodifiableList(points);
    }

    public double[] xMinos() {
        return xMinos.pair();
    }

    public double[] yMinos() {
        return yMinos.pair();
    }

    public int xpar() {
        return parX;
    }

    public int ypar() {
        return parY;
    }

    public MinosError xMinosError() {
        return xMinos;
    }

    public MinosError yMinosError() {
        return yMinos;
    }

    public int nfcn() {
        return nfcn;
    }

    public double xMin() {
        return xMinos.min();
    }

    public double yMin() {
        return yMinos.min();
    }

    /**
     * operator&lt;&lt;: the Minos errors, the plot (which the C++ printf's on
     * standard output, where its stream also writes) and the points.
     */
    @Override
    public void print(COStream os) {
        os.put("Contours # of function calls: ").put(nfcn()).put('\n');
        os.put("MinosError in x: ").put('\n');
        xMinosError().print(os);
        os.put('\n');
        os.put("MinosError in y: ").put('\n');
        yMinosError().print(os);
        os.put('\n');
        os.put(new MnPlot().render(xMin(), yMin(), points));
        for (int i = 0; i < points.size(); i++) {
            os.put(i).put("  ").put(points.get(i).x()).put("  ").put(points.get(i).y()).put('\n');
        }
        os.put('\n');
    }

    @Override
    public String toString() {
        final COStream os = new COStream();
        print(os);
        return os.str();
    }
}
