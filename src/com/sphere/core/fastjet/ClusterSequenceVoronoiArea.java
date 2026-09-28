package com.sphere.core.fastjet;

import java.util.ArrayList;
import java.util.List;

/**
 * Areas from the Voronoi cells of the particles, fastjet::ClusterSequenceVoronoiArea:
 * each particle owns the part of its cell within a circle of radius
 * effective_Rfact * R, and a jet the sum over its constituents. For the kt
 * algorithm this is its passive area.
 */
public class ClusterSequenceVoronoiArea extends ClusterSequenceAreaBase {

    private final double effectiveRfact;
    private final List<Double> voronoiArea = new ArrayList<>();
    private final List<PseudoJet> voronoiArea4vector = new ArrayList<>();

    public ClusterSequenceVoronoiArea(List<? extends PseudoJet> particles, JetDefinition jetDef,
                                      AreaDefinition.VoronoiAreaSpec spec) {
        this.effectiveRfact = spec.effectiveRfact();
        this.jetDef = jetDef;
        transferInputJets(particles);
        initialiseAndRun(jetDef, false);
        initializeVA();
    }

    public ClusterSequenceVoronoiArea(List<? extends PseudoJet> particles, JetDefinition jetDef) {
        this(particles, jetDef, new AreaDefinition.VoronoiAreaSpec());
    }

    private void initializeVA() {
        final double[] areas = voronoiAreas(jets.subList(0, nParticles()), effectiveRfact * jetDef.R());
        for (int i = 0; i < nParticles(); i++) {
            voronoiArea.add(areas[i]);
            final PseudoJet j = jets.get(i);
            if (j.perp2() > 0) {
                voronoiArea4vector.add(j.times(areas[i] / j.perp()));
            } else {
                voronoiArea4vector.add(new PseudoJet(0.0, 0.0, 0.0, 0.0, precision()));
            }
        }
        for (int i = nParticles(); i < history.size(); i++) {
            final HistoryElement h = history.get(i);
            double a;
            PseudoJet a4;
            if (h.parent2 >= 0) {
                a = voronoiArea.get(h.parent1) + voronoiArea.get(h.parent2);
                a4 = voronoiArea4vector.get(h.parent1).plus(voronoiArea4vector.get(h.parent2));
            } else {
                a = voronoiArea.get(h.parent1);
                a4 = voronoiArea4vector.get(h.parent1);
            }
            voronoiArea.add(a);
            voronoiArea4vector.add(a4);
        }
    }

    @Override
    public double area(PseudoJet jet) {
        return voronoiArea.get(jet.clusterHistIndex());
    }

    @Override
    public PseudoJet area4vector(PseudoJet jet) {
        return voronoiArea4vector.get(jet.clusterHistIndex()).copy();
    }

    @Override
    public double areaError(PseudoJet jet) {
        return 0.0;
    }

    /* ------------------------------------------------------------------ */
    /* The area calculation, ClusterSequenceVoronoiArea::VoronoiAreaCalc   */
    /* ------------------------------------------------------------------ */

    /** The area of each particle's Voronoi cell inside a circle of radius effectiveR. */
    static double[] voronoiAreas(List<PseudoJet> particles, double effectiveR) {
        if (!(effectiveR < 0.5 * Math.PI)) {
            throw new FastJetException("Voronoi areas need effective_Rfact * R < pi/2");
        }
        final double r2 = effectiveR * effectiveR;
        final double[] areas = new double[particles.size()];
        final List<VoronoiDiagramGenerator.VPoint> vps = new ArrayList<>();
        final List<Integer> vIndices = new ArrayList<>();
        double minrap = Double.MAX_VALUE;
        double maxrap = -minrap;
        int nTot = 0;
        int nAdded = 0;
        for (PseudoJet j : particles) {
            if (j.perp2() != 0.0 || j.E() != j.pz()) {
                final double rap = j.rap();
                final double phi = j.phi();
                vps.add(new VoronoiDiagramGenerator.VPoint(rap, phi));
                vIndices.add(nTot);
                nAdded++;
                if (phi < 2 * effectiveR) {
                    vps.add(new VoronoiDiagramGenerator.VPoint(rap, phi + PseudoJet.TWOPI));
                    vIndices.add(-1);
                    nAdded++;
                } else if (PseudoJet.TWOPI - phi < 2 * effectiveR) {
                    vps.add(new VoronoiDiagramGenerator.VPoint(rap, phi - PseudoJet.TWOPI));
                    vIndices.add(-1);
                    nAdded++;
                }
                maxrap = Math.max(maxrap, rap);
                minrap = Math.min(minrap, rap);
            }
            nTot++;
        }
        if (nAdded == 0) {
            return areas;
        }
        final double maxExtend = 2 * Math.max(maxrap - minrap + 4 * effectiveR, PseudoJet.TWOPI + 8 * effectiveR);
        final double mid = 0.5 * (minrap + maxrap);
        vps.add(new VoronoiDiagramGenerator.VPoint(mid - maxExtend, Math.PI));
        vps.add(new VoronoiDiagramGenerator.VPoint(mid + maxExtend, Math.PI));
        vps.add(new VoronoiDiagramGenerator.VPoint(mid, Math.PI - maxExtend));
        vps.add(new VoronoiDiagramGenerator.VPoint(mid, Math.PI + maxExtend));
        final VoronoiDiagramGenerator vdg = new VoronoiDiagramGenerator();
        vdg.generateVoronoi(vps.toArray(new VoronoiDiagramGenerator.VPoint[0]),
            mid - maxExtend, mid + maxExtend, Math.PI - maxExtend, Math.PI + maxExtend);
        vdg.resetIterator();
        VoronoiDiagramGenerator.GraphEdge e;
        while ((e = vdg.next()) != null) {
            int v = e.point1;
            if (v < nAdded) {
                final int p = vIndices.get(v);
                if (p != -1) {
                    areas[p] += edgeCircleIntersection(vps.get(v), e, r2);
                }
            }
            v = e.point2;
            if (v < nAdded) {
                final int p = vIndices.get(v);
                if (p != -1) {
                    areas[p] += edgeCircleIntersection(vps.get(v), e, r2);
                }
            }
        }
        return areas;
    }

    private static double circleArea(double r2, double d12sq, double d01sq, double d02sq) {
        return 0.5 * r2 * CRMath.acos(Math.min(1.0, (d01sq + d02sq - d12sq) / (2 * Math.sqrt(d01sq * d02sq))));
    }

    /** The area of the triangle (p0, edge) inside the circle of radius sqrt(r2) around p0. */
    private static double edgeCircleIntersection(VoronoiDiagramGenerator.VPoint p0,
                                                 VoronoiDiagramGenerator.GraphEdge edge, double r2) {
        final double p1x = edge.x1 - p0.x, p1y = edge.y1 - p0.y;
        final double p2x = edge.x2 - p0.x, p2y = edge.y2 - p0.y;
        final double pdx = p2x - p1x, pdy = p2y - p1y;
        final double cross = p1x * p2y - p1y * p2x;
        final double d12 = pdx * pdx + pdy * pdy;
        final double d01 = p1x * p1x + p1y * p1y;
        final double d02 = p2x * p2x + p2y * p2y;
        double delta = d12 * r2 - cross * cross;
        if (delta <= 0) {
            return circleArea(r2, d12, d01, d02);
        }
        delta = Math.sqrt(delta);
        final double b = pdx * p1x + pdy * p1y;
        final double tp = (delta - b) / d12;
        if (tp < 0) {
            return circleArea(r2, d12, d01, d02);
        }
        final double tm = -(delta + b) / d12;
        if (tp < 1) {
            if (tm < 0) {
                return tp * 0.5 * Math.abs(cross) + circleArea(r2, (1 - tp) * (1 - tp) * d12, r2, d02);
            }
            return (tp - tm) * 0.5 * Math.abs(cross)
                + circleArea(r2, tm * tm * d12, d01, r2)
                + circleArea(r2, (1 - tp) * (1 - tp) * d12, r2, d02);
        }
        if (tm > 1) {
            return circleArea(r2, d12, d01, d02);
        }
        if (tm < 0) {
            return 0.5 * Math.abs(cross);
        }
        return (1 - tm) * 0.5 * Math.abs(cross) + circleArea(r2, tm * tm * d12, d01, r2);
    }
}
