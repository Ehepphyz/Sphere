package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFormat;
import com.sphere.core.hepmc3.cxx.CStr;

/**
 * The partons and parton distributions of the hard process: PDG ids, x,
 * scale (GeV), x f(x), and the LHAPDF ids of the sets. Written as
 * "%i %i %.8e %.8e %.8e %.8e %.8e %i %i".
 */
public final class GenPdfInfo extends Attribute {

    public final int[] partonId = new int[2];
    public final int[] pdfId = new int[2];
    public double scale;
    public final double[] x = new double[2];
    public final double[] xf = new double[2];

    public GenPdfInfo() {
    }

    @Override
    public boolean fromString(String att) {
        int cursor = 0;
        partonId[0] = CStr.atoi(att, cursor);
        if ((cursor = next(att, cursor)) < 0) return false;
        partonId[1] = CStr.atoi(att, cursor);
        if ((cursor = next(att, cursor)) < 0) return false;
        x[0] = CStr.atof(att, cursor);
        if ((cursor = next(att, cursor)) < 0) return false;
        x[1] = CStr.atof(att, cursor);
        if ((cursor = next(att, cursor)) < 0) return false;
        scale = CStr.atof(att, cursor);
        if ((cursor = next(att, cursor)) < 0) return false;
        xf[0] = CStr.atof(att, cursor);
        if ((cursor = next(att, cursor)) < 0) return false;
        xf[1] = CStr.atof(att, cursor);
        if ((cursor = next(att, cursor)) < 0) return false;
        pdfId[0] = CStr.atoi(att, cursor);
        if ((cursor = next(att, cursor)) < 0) return false;
        pdfId[1] = CStr.atoi(att, cursor);
        return true;
    }

    private static int next(String att, int cursor) {
        if (cursor + 1 > att.length()) return -1;
        return CStr.strchr(att, cursor + 1, ' ');
    }

    @Override
    public String serialize() {
        return CFormat.sprintf("%i %i %.8e %.8e %.8e %.8e %.8e %i %i", partonId[0], partonId[1], x[0], x[1], scale,
            xf[0], xf[1], pdfId[0], pdfId[1]);
    }

    public void set(int partonId1, int partonId2, double x1, double x2, double scaleIn, double xf1, double xf2,
                    int pdfId1, int pdfId2) {
        partonId[0] = partonId1;
        partonId[1] = partonId2;
        x[0] = x1;
        x[1] = x2;
        scale = scaleIn;
        xf[0] = xf1;
        xf[1] = xf2;
        pdfId[0] = pdfId1;
        pdfId[1] = pdfId2;
    }

    public void set(int partonId1, int partonId2, double x1, double x2, double scaleIn, double xf1, double xf2) {
        set(partonId1, partonId2, x1, x2, scaleIn, xf1, xf2, 0, 0);
    }

    /** Some field is not zero. */
    public boolean isValid() {
        return partonId[0] != 0 || partonId[1] != 0 || x[0] != 0 || x[1] != 0 || scale != 0 || xf[0] != 0
            || xf[1] != 0 || pdfId[0] != 0 || pdfId[1] != 0;
    }
}
