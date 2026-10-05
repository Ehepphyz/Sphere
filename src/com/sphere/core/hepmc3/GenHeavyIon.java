package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFormat;
import com.sphere.core.hepmc3.cxx.CIStream;

import java.util.Map;
import java.util.TreeMap;

/**
 * What a heavy-ion generator says about the collision: participants,
 * collisions, impact parameter, centrality, spectators, participant plane
 * angles and eccentricities by order. A negative value means "not known".
 * Written in the "v0" format (with the deprecated fields), as HepMC3 does
 * unless it is built without them.
 */
public final class GenHeavyIon extends Attribute {

    public int nCollHard = -1;
    public int nPartProj = -1;
    public int nPartTarg = -1;
    public int nColl = -1;
    /** Deprecated: Nspec_proj_n and Nspec_targ_n instead. */
    public int spectatorNeutrons = -1;
    /** Deprecated: Nspec_proj_p and Nspec_targ_p instead. */
    public int spectatorProtons = -1;
    public int nNwoundedCollisions = -1;
    public int nwoundedNCollisions = -1;
    public int nwoundedNwoundedCollisions = -1;
    /** In femtometres. */
    public double impactParameter = -1.0;
    public double eventPlaneAngle = -1.0;
    /** Deprecated: eccentricities instead. */
    public double eccentricity = -1.0;
    /** Millibarn. */
    public double sigmaInelNN = -1.0;
    /** Percentile, 0 most central. */
    public double centrality = -1.0;
    public double userCentEstimate = -1.0;
    public int nSpecProjN = -1;
    public int nSpecTargN = -1;
    public int nSpecProjP = -1;
    public int nSpecTargP = -1;
    public final TreeMap<Integer, Double> participantPlaneAngles = new TreeMap<>();
    public final TreeMap<Integer, Double> eccentricities = new TreeMap<>();
    /** Write the old format (without "v0"), for compatibility. */
    public boolean forceOldFormat;

    public GenHeavyIon() {
    }

    @Override
    public boolean fromString(String att) {
        final CIStream is = new CIStream(att);
        if (att.isEmpty() || att.charAt(0) != 'v') {
            nCollHard = readInt(is, nCollHard);
            nPartProj = readInt(is, nPartProj);
            nPartTarg = readInt(is, nPartTarg);
            nColl = readInt(is, nColl);
            spectatorNeutrons = readInt(is, spectatorNeutrons);
            spectatorProtons = readInt(is, spectatorProtons);
            nNwoundedCollisions = readInt(is, nNwoundedCollisions);
            nwoundedNCollisions = readInt(is, nwoundedNCollisions);
            nwoundedNwoundedCollisions = readInt(is, nwoundedNwoundedCollisions);
            impactParameter = readDouble(is, impactParameter);
            eventPlaneAngle = readDouble(is, eventPlaneAngle);
            eccentricity = readDouble(is, eccentricity);
            sigmaInelNN = readDouble(is, sigmaInelNN);
            centrality = readDouble(is, centrality);
            return !is.fail();
        }
        final String version = is.nextWord();
        nCollHard = readInt(is, nCollHard);
        nPartProj = readInt(is, nPartProj);
        nPartTarg = readInt(is, nPartTarg);
        nColl = readInt(is, nColl);
        if (version.equals("v0")) {
            spectatorNeutrons = readInt(is, spectatorNeutrons);
            spectatorProtons = readInt(is, spectatorProtons);
        }
        nNwoundedCollisions = readInt(is, nNwoundedCollisions);
        nwoundedNCollisions = readInt(is, nwoundedNCollisions);
        nwoundedNwoundedCollisions = readInt(is, nwoundedNwoundedCollisions);
        impactParameter = readDouble(is, impactParameter);
        eventPlaneAngle = readDouble(is, eventPlaneAngle);
        if (version.equals("v0")) eccentricity = readDouble(is, eccentricity);
        sigmaInelNN = readDouble(is, sigmaInelNN);
        centrality = readDouble(is, centrality);
        if (!version.equals("v0")) userCentEstimate = readDouble(is, userCentEstimate);
        nSpecProjN = readInt(is, nSpecProjN);
        nSpecTargN = readInt(is, nSpecTargN);
        nSpecProjP = readInt(is, nSpecProjP);
        nSpecTargP = readInt(is, nSpecTargP);
        int n = readInt(is, 0);
        for (int i = 0; i < n; ++i) {
            final int ord = readInt(is, 0);
            participantPlaneAngles.put(ord, readDouble(is, participantPlaneAngles.getOrDefault(ord, 0.0)));
        }
        n = readInt(is, 0);
        for (int i = 0; i < n; ++i) {
            final int ord = readInt(is, 0);
            eccentricities.put(ord, readDouble(is, eccentricities.getOrDefault(ord, 0.0)));
        }
        return !is.fail();
    }

    /**
     * A failed extraction sets the value to 0 when it was attempted on a good
     * stream, and leaves it alone on an already failed one (C++11 rules).
     */
    private static int readInt(CIStream is, int current) {
        if (is.fail()) return current;
        return is.nextInt();
    }

    private static double readDouble(CIStream is, double current) {
        if (is.fail()) return current;
        return is.nextDouble();
    }

    @Override
    public String serialize() {
        final StringBuilder os = new StringBuilder();
        final String version = forceOldFormat ? "" : "v0";
        os.append(version).append(version.isEmpty() ? "" : " ");
        os.append(nCollHard).append(' ').append(nPartProj).append(' ').append(nPartTarg).append(' ').append(nColl).append(' ');
        if (version.equals("v0")) os.append(spectatorNeutrons).append(' ').append(spectatorProtons).append(' ');
        os.append(nNwoundedCollisions).append(' ').append(nwoundedNCollisions).append(' ')
            .append(nwoundedNwoundedCollisions).append(' ').append(g8(impactParameter)).append(' ')
            .append(g8(eventPlaneAngle)).append(' ');
        if (version.equals("v0")) os.append(g8(eccentricity)).append(' ');
        os.append(g8(sigmaInelNN)).append(' ').append(g8(centrality)).append(' ');
        if (!version.equals("v0")) os.append(g8(userCentEstimate)).append(' ');
        os.append(nSpecProjN).append(' ').append(nSpecTargN).append(' ').append(nSpecProjP).append(' ').append(nSpecTargP).append(' ');
        os.append(participantPlaneAngles.size());
        for (Map.Entry<Integer, Double> it : participantPlaneAngles.entrySet()) {
            os.append(' ').append(it.getKey()).append(' ').append(g8(it.getValue()));
        }
        os.append(' ').append(eccentricities.size());
        for (Map.Entry<Integer, Double> it : eccentricities.entrySet()) {
            os.append(' ').append(it.getKey()).append(' ').append(g8(it.getValue()));
        }
        return os.toString();
    }

    /** ostream << double at setprecision(8). */
    private static String g8(double v) {
        return CFormat.sprintf("%.8g", v);
    }

    /** Deprecated: sets every field at once. */
    public void set(int nh, int np, int nt, int nc, int ns, int nsp, int nnw, int nwn, int nwnw,
                    double im, double pl, double ec, double s, double cent, double usrcent) {
        nCollHard = nh;
        nPartProj = np;
        nPartTarg = nt;
        nColl = nc;
        spectatorNeutrons = ns;
        spectatorProtons = nsp;
        nNwoundedCollisions = nnw;
        nwoundedNCollisions = nwn;
        nwoundedNwoundedCollisions = nwnw;
        impactParameter = im;
        eventPlaneAngle = pl;
        eccentricity = ec;
        sigmaInelNN = s;
        centrality = cent;
        userCentEstimate = usrcent;
    }

    public void set(int nh, int np, int nt, int nc, int ns, int nsp) {
        set(nh, np, nt, nc, ns, nsp, 0, 0, 0, 0., 0., 0., 0., 0., 0.);
    }

    /** Deprecated: some field is not zero. */
    public boolean isValid() {
        return nCollHard != 0 || nPartProj != 0 || nPartTarg != 0 || nColl != 0 || spectatorNeutrons != 0
            || spectatorProtons != 0 || nNwoundedCollisions != 0 || nwoundedNCollisions != 0
            || nwoundedNwoundedCollisions != 0 || impactParameter != 0 || eventPlaneAngle != 0
            || eccentricity != 0 || sigmaInelNN != 0 || centrality != 0;
    }
}
