package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFormat;
import com.sphere.core.hepmc3.cxx.CStr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The cross section of the run so far, one per event weight, with its error
 * and the numbers of accepted and attempted events. Written as
 * "xs err accepted attempted [xs_i err_i ...]" in %.8e.
 */
public final class GenCrossSection extends Attribute {

    private long acceptedEvents;
    private long attemptedEvents;
    private List<Double> crossSections = new ArrayList<>();
    private List<Double> crossSectionErrors = new ArrayList<>();

    public GenCrossSection() {
    }

    @Override
    public boolean fromString(String att) {
        int cursor = 0;
        crossSections.clear();
        crossSectionErrors.clear();
        final double crossSection = CStr.atof(att, cursor);
        crossSections.add(crossSection);
        if ((cursor = next(att, cursor)) < 0) return false;
        final double crossSectionError = CStr.atof(att, cursor);
        crossSectionErrors.add(crossSectionError);
        if ((cursor = next(att, cursor)) < 0) {
            acceptedEvents = -1;
            attemptedEvents = -1;
        } else {
            acceptedEvents = CStr.atoi(att, cursor);
            final int c2 = next(att, cursor);
            if (c2 < 0) {
                attemptedEvents = -1;
            } else {
                attemptedEvents = CStr.atoi(att, c2);
            }
            cursor = c2;
        }
        final int nweights = event() != null ? Math.max(event().weights().size(), 1) : 1;
        // where C++ would follow a null cursor (fewer than four fields) the reading simply ends
        while (cursor >= 0) {
            if ((cursor = next(att, cursor)) < 0) break;
            crossSections.add(CStr.atof(att, cursor));
            if ((cursor = next(att, cursor)) < 0) break;
            crossSectionErrors.add(CStr.atof(att, cursor));
        }
        if (crossSections.size() != crossSectionErrors.size()) {
            Setup.warning(800, "GenCrossSection::from_string: number of cross-sections and errors differ "
                + crossSections.size() + " vs  " + crossSectionErrors.size() + "). Ill-formed input:" + att);
        }
        final int oldxsecsize = crossSections.size();
        if (oldxsecsize > 1 && oldxsecsize != nweights) {
            Setup.warning(800, "GenCrossSection::from_string: the number of cross-sections (N = " + crossSections.size()
                + ") does not match the number of weights (Nw = " + (event() == null ? 0 : event().weights().size()) + ")");
        }
        for (int i = oldxsecsize; i < nweights; i++) {
            crossSections.add(crossSection);
            crossSectionErrors.add(crossSectionError);
        }
        return true;
    }

    /** strchr(cursor + 1, ' '): the next blank after the cursor, -1 when none. */
    private static int next(String att, int cursor) {
        if (cursor + 1 > att.length()) return -1;
        return CStr.strchr(att, cursor + 1, ' ');
    }

    @Override
    public String serialize() {
        final StringBuilder os = new StringBuilder();
        os.append(CFormat.sprintf("%.8e", crossSections.isEmpty() ? 0.0 : crossSections.get(0))).append(' ')
            .append(CFormat.sprintf("%.8e", crossSectionErrors.isEmpty() ? 0.0 : crossSectionErrors.get(0))).append(' ')
            .append(acceptedEvents).append(' ').append(attemptedEvents);
        if (event() != null && !event().weights().isEmpty() && crossSections.size() > 1
                && event().weights().size() != crossSections.size()) {
            Setup.warning(800, "GenCrossSection::to_string: the number of cross-sections (N = " + crossSections.size()
                + ") does not match the number of weights (Nw = " + event().weights().size() + ")");
        }
        for (int i = 1; i < crossSections.size(); ++i) {
            os.append(' ').append(CFormat.sprintf("%.8e", crossSections.get(i))).append(' ')
                .append(CFormat.sprintf("%.8e", crossSectionErrors.size() > i ? crossSectionErrors.get(i) : 0.0));
        }
        return os.toString();
    }

    /** Deprecated form: one value for every weight of the event. */
    public void setCrossSection(double xs, double xsErr, long nAcc, long nAtt) {
        acceptedEvents = nAcc;
        attemptedEvents = nAtt;
        final int n = Math.max(event() != null ? event().weights().size() : 0, 1);
        crossSections = new ArrayList<>(Collections.nCopies(n, xs));
        crossSectionErrors = new ArrayList<>(Collections.nCopies(n, xsErr));
    }

    public void setCrossSection(double xs, double xsErr) {
        setCrossSection(xs, xsErr, -1, -1);
    }

    public void setCrossSection(List<Double> xs, List<Double> xsErr, long nAcc, long nAtt) {
        crossSections = new ArrayList<>(xs);
        crossSectionErrors = new ArrayList<>(xsErr);
        acceptedEvents = nAcc;
        attemptedEvents = nAtt;
    }

    public void setCrossSection(List<Double> xs, List<Double> xsErr) {
        setCrossSection(xs, xsErr, -1, -1);
    }

    public List<Double> xsecs() {
        return Collections.unmodifiableList(crossSections);
    }

    public List<Double> xsecErrs() {
        return Collections.unmodifiableList(crossSectionErrors);
    }

    public void setAcceptedEvents(long n) {
        acceptedEvents = n;
    }

    public void setAttemptedEvents(long n) {
        attemptedEvents = n;
    }

    public long getAcceptedEvents() {
        return acceptedEvents;
    }

    public long getAttemptedEvents() {
        return attemptedEvents;
    }

    public void setXsec(String wName, double xs) {
        final int pos = windx(wName);
        if (pos < 0) throw new IllegalArgumentException("GenCrossSection::set_xsec(const std::string&,const double&): no weight with given name in this run");
        setXsec(pos, xs);
    }

    public void setXsec(int index, double xs) {
        if (index < 0 || index >= crossSections.size()) {
            throw new IndexOutOfBoundsException("GenCrossSection::set_xsec(const unsigned long&): index outside of range");
        }
        crossSections.set(index, xs);
    }

    public void setXsecErr(String wName, double xsErr) {
        final int pos = windx(wName);
        if (pos < 0) throw new IllegalArgumentException("GenCrossSection::set_xsec_err(const std::string&,const double&): no weight with given name in this run");
        setXsecErr(pos, xsErr);
    }

    public void setXsecErr(int index, double xsErr) {
        if (index < 0 || index >= crossSectionErrors.size()) {
            throw new IndexOutOfBoundsException("GenCrossSection::set_xsec_err(const unsigned long&): index outside of range");
        }
        crossSectionErrors.set(index, xsErr);
    }

    public double xsec(String wName) {
        final int pos = windx(wName);
        if (pos < 0) throw new IllegalArgumentException("GenCrossSection::xsec(const std::string&): no weight with given name in this run");
        return xsec(pos);
    }

    public double xsec() {
        return xsec(0);
    }

    public double xsec(int index) {
        if (index >= 0 && index < crossSections.size()) return crossSections.get(index);
        throw new IndexOutOfBoundsException("GenCrossSection::xsec(const unsigned long&): index outside of range");
    }

    public double xsecErr(String wName) {
        final int pos = windx(wName);
        if (pos < 0) throw new IllegalArgumentException("GenCrossSection::xsec_err(const std::string&): no weight with given name in this run");
        return xsecErr(pos);
    }

    public double xsecErr() {
        return xsecErr(0);
    }

    public double xsecErr(int index) {
        if (index >= 0 && index < crossSectionErrors.size()) return crossSectionErrors.get(index);
        throw new IndexOutOfBoundsException("GenCrossSection::xsec_err(const unsigned long&): index outside of range");
    }

    /** Non-zero information present. */
    public boolean isValid() {
        if (crossSections.isEmpty()) return false;
        if (crossSectionErrors.isEmpty()) return false;
        if (crossSectionErrors.size() != crossSections.size()) return false;
        if (crossSections.get(0) != 0) return true;
        return crossSectionErrors.get(0) != 0;
    }

    /** The index of a weight name; 0 when the attribute has no event or the event no run, as in C++. */
    private int windx(String wName) {
        if (event() == null || event().runInfo() == null) return 0;
        return event().runInfo().weightIndex(wName);
    }
}
