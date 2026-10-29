package com.sphere.core.clhep.validation;

import com.sphere.core.clhep.vector.Hep2Vector;
import com.sphere.core.clhep.vector.Hep3Vector;
import com.sphere.core.clhep.vector.HepAxisAngle;
import com.sphere.core.clhep.vector.HepBoost;
import com.sphere.core.clhep.vector.HepBoostX;
import com.sphere.core.clhep.vector.HepBoostY;
import com.sphere.core.clhep.vector.HepBoostZ;
import com.sphere.core.clhep.vector.HepEulerAngles;
import com.sphere.core.clhep.vector.HepLorentzRotation;
import com.sphere.core.clhep.vector.HepLorentzVector;
import com.sphere.core.clhep.vector.HepRotation;
import com.sphere.core.clhep.vector.HepRotationX;
import com.sphere.core.clhep.vector.HepRotationY;
import com.sphere.core.clhep.vector.HepRotationZ;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * The Java twin of vector_ref.cc: the same 400 rounds of vectors, rotations, boosts and Lorentz
 * transformations on the same pseudo-random inputs, every double written as its 64 bits, so that the
 * output is the C++ one line for line when the port computes as CLHEP does.
 */
public final class VectorReference extends Reference {

    @Override
    public String name() {
        return "vector";
    }

    @Override
    protected void run() {
        for (int k = 0; k < 400; k++) {
            final Hep3Vector a = rv(10);
            final Hep3Vector b = rv(5);
            if (k % 50 == 7) {
                final double zz = sym(3);
                a.set(0, 0, zz);
            }
            if (k % 50 == 9) b.set(0, 0, 0);
            v3("a", a);
            d("mag", a.mag());
            d("theta", a.theta());
            d("phi", a.phi());
            d("eta", a.eta());
            d("perp", a.perp());
            d("cosTheta", a.cosTheta());
            d("cos2Theta", a.cos2Theta());
            d("perpb", a.perp(b));
            d("angle", a.angle(b));
            d("cosThetab", a.cosTheta(b));
            d("cos2Thetab", a.cos2Theta(b));
            d("deltaR", a.deltaR(b));
            d("deltaPhi", a.deltaPhi(b));
            d("howNear", a.howNear(b));
            d("howParallel", a.howParallel(b));
            d("howOrthogonal", a.howOrthogonal(b));
            i("isParallel", a.isParallel(b, 0.3));
            i("isOrthogonal", a.isOrthogonal(b, 0.3));
            i("compare", a.compare(b));
            d("polarAngle", a.polarAngle(b));
            d("dot", a.dot(b));
            v3("cross", a.cross(b));
            v3("unit", a.unit());
            v3("orth", a.orthogonal());
            if (b.mag2() > 0) {
                v3("project", a.project(b));
                v3("perpPart", a.perpPart(b));
                d("etaRef", a.eta(b));
                d("azimRef", a.azimAngle(b, new Hep3Vector(1, 2, 3)));
                d("polarRef", a.polarAngle(b, new Hep3Vector(0.3, -1, 2)));
            }
            Hep3Vector c = new Hep3Vector(a);
            c.rotateX(sym(4));
            c.rotateY(sym(4));
            c.rotateZ(sym(4));
            v3("rotXYZ", c);
            final Hep3Vector u = b.mag2() > 0 ? b.unit() : new Hep3Vector(0, 0, -1);
            c = new Hep3Vector(a);
            c.rotateUz(u);
            v3("rotateUz", c);
            c = new Hep3Vector(a);
            {
                final double t1 = sym(3);
                final Hep3Vector t2 = rv(1);
                c.rotate(t1, t2);
            }
            v3("rotateAngleAxis", c);
            c = new Hep3Vector(a);
            {
                final Hep3Vector t2 = rv(1);
                final double t1 = sym(3);
                c.rotate(t2, t1);
            }
            v3("rotateAxisDelta", c);
            c = new Hep3Vector(a);
            {
                final double t1 = sym(3), t2 = sym(3), t3 = sym(3);
                c.rotate(t1, t2, t3);
            }
            v3("rotateEuler", c);
            {
                final double t1 = sym(4) + 5, t2 = sym(3), t3 = sym(3);
                c.setREtaPhi(t1, t2, t3);
            }
            v3("setREtaPhi", c);
            {
                final double t1 = pos(5, 0), t2 = pos(3, 0), t3 = sym(3);
                c.setRThetaPhi(t1, t2, t3);
            }
            v3("setRThetaPhi", c);
            {
                final double t1 = pos(5, 0), t2 = sym(3), t3 = sym(3);
                c.setRhoPhiZ(t1, t2, t3);
            }
            v3("setRhoPhiZ", c);
            {
                final double t1 = pos(5, 0.1), t2 = sym(3), t3 = sym(3);
                c.setRhoPhiEta(t1, t2, t3);
            }
            v3("setRhoPhiEta", c);
            c = new Hep3Vector(a);
            c.setTheta(pos(3, 0));
            v3("setTheta", c);
            c.setPhi(sym(3));
            v3("setPhi", c);
            c.setMag(pos(4, 0.5));
            v3("setMag", c);
            c.setPerp(pos(3, 0));
            v3("setPerp", c);
            c.setEta(sym(3));
            v3("setEta", c);
            c.setCylEta(sym(2));
            v3("setCylEta", c);
            c.setCylTheta(pos(3, 0.01));
            v3("setCylTheta", c);
            final Hep3Vector small = rv(0.5);
            d("beta", small.beta());
            d("gamma", small.gamma());
            d("rapidity", small.rapidity());
            d("coLinear", small.coLinearRapidity());
            if (b.mag2() > 0) d("rapidityRef", small.rapidity(b));
            v3("div", a.div(pos(1, 0.5)));
            {
                final String s = a.print(new COStream()).str();
                text("print3", s);
                final Hep3Vector back = new Hep3Vector();
                Hep3Vector.read(new CIStream(s), back);
                v3("read3", back);
            }

            // two-vectors
            final double p1 = sym(4), p2y = sym(4), q1 = sym(4), q2 = sym(4);
            final Hep2Vector p = new Hep2Vector(p1, p2y), q = new Hep2Vector(q1, q2);
            d("2.phi", p.phi());
            d("2.mag", p.mag());
            d("2.angle", p.angle(q));
            d("2.howParallel", p.howParallel(q));
            i("2.isOrthogonal", p.isOrthogonal(q, 0.2));
            final Hep2Vector p2 = new Hep2Vector(p);
            p2.rotate(sym(3));
            d("2.rx", p2.x());
            d("2.ry", p2.y());
            {
                final double t1 = pos(3, 0), t2 = sym(3);
                p2.setPolar(t1, t2);
            }
            d("2.px", p2.x());
            d("2.py", p2.y());
            final Hep2Vector uu = q.unit();
            d("2.ux", uu.x());
            d("2.uy", uu.y());

            // rotations
            final double phi = sym(3), theta = pos(3.1, 0), psi = sym(3);
            final HepRotation R = new HepRotation(phi, theta, psi);
            r3("R", R);
            final HepEulerAngles ea = R.eulerAngles();
            d("ea.phi", ea.phi());
            d("ea.theta", ea.theta());
            d("ea.psi", ea.psi());
            d("R.phi", R.phi());
            d("R.theta", R.theta());
            d("R.psi", R.psi());
            d("R.delta", R.delta());
            v3("R.axis", R.axis());
            d("R.phiX", R.phiX());
            d("R.thetaY", R.thetaY());
            d("R.norm2", R.norm2());
            final Hep3Vector ax = new Hep3Vector();
            final double ang = R.getAngleAxis(ax);
            d("R.angle", ang);
            v3("R.angleAxis", ax);
            final Hep3Vector sax = rv(1);
            final double sang = sym(3);
            final HepRotation S = new HepRotation(sax, sang);
            r3("S", S);
            r3("RS", R.times(S));
            r3("Rinv", R.inverse());
            d("RSdist", R.distance2(S));
            i("RScompare", R.compare(S));
            final HepRotation T = new HepRotation(R);
            T.rotateX(sym(2));
            T.rotateY(sym(2));
            T.rotateZ(sym(2));
            {
                final double t1 = sym(2);
                final Hep3Vector t2 = rv(1);
                T.rotate(t1, t2);
            }
            r3("T", T);
            final double wa = sym(1e-9), wb = sym(1e-9), wc = sym(1e-9);
            final HepRotation W = new HepRotation(new Hep3Vector(1, 1e-9, wa), new Hep3Vector(wb, 1, 0),
                new Hep3Vector(0, wc, 1));
            r3("Wcols", W);
            final HepRotation Q = new HepRotation(R);
            Q.rotateX(1e-7);
            Q.setPhi(sym(3));
            Q.setTheta(pos(3, 0));
            Q.setPsi(sym(3));
            r3("Qset", Q);
            {
                final double t1 = sym(1), t2 = sym(1);
                Q.setAxis(new Hep3Vector(t1, t2, 1));
            }
            r3("Qaxis", Q);
            Q.setDelta(sym(3));
            r3("Qdelta", Q);
            final HepRotation E = new HepRotation(R);
            E.rectify();
            r3("Erect", E);
            final double rxd = sym(7), ryd = sym(7), rzd = sym(7);
            final HepRotationX RX = new HepRotationX(rxd);
            final HepRotationY RY = new HepRotationY(ryd);
            final HepRotationZ RZ = new HepRotationZ(rzd);
            r3("RX", new HepRotation(RX));
            r3("RY", new HepRotation(RY));
            r3("RZ", new HepRotation(RZ));
            d("RX.d", RX.delta());
            d("RX.phi", RX.phi());
            d("RY.psi", RY.psi());
            d("RZ.phi", RZ.phi());
            r3("R*RX", R.times(RX));
            r3("R*RY", R.times(RY));
            r3("R*RZ", R.times(RZ));
            r3("RX*R", RX.times(R));
            r3("RY*R", RY.times(R));
            r3("RZ*R", RZ.times(R));
            final HepRotationX RX2 = RX.times(new HepRotationX(sym(4)));
            d("RX2.d", RX2.delta());
            d("RX2.yz", RX2.yz());
            d("RX.dist", RX.distance2(R));
            d("RY.dist", RY.distance2(R));
            d("RZ.dist", RZ.distance2(R));
            v3("Ra", R.times(a));
            v3("RXa", RX.times(a));
            v3("RYa", RY.times(a));
            v3("RZa", RZ.times(a));
            final Hep3Vector aax = rv(1);
            final double aad = sym(3);
            final HepAxisAngle aa = new HepAxisAngle(aax, aad);
            final HepAxisAngle bb = R.axisAngle();
            d("aa.dist", aa.howNear(bb));
            {
                final double t1 = sym(3), t2 = pos(1, 0), t3 = sym(3);
                d("ea.dist", ea.howNear(new HepEulerAngles(t1, t2, t3)));
            }
            text("printR", R.print(new COStream()).str());
            text("printRY", RY.print(new COStream()).str());
            {
                final COStream os = new COStream();
                aa.print(os);
                os.put(" ");
                ea.print(os);
                text("printAA", os.str());
            }

            // four-vectors and boosts
            final Hep3Vector wv = rv(5);
            final double wt = pos(10, 9);
            final Hep3Vector zv = rv(5);
            final double zt = pos(10, 9);
            final HepLorentzVector w = new HepLorentzVector(wv, wt), z = new HepLorentzVector(zv, zt);
            v4("w", w);
            d("m", w.m());
            d("m2", w.m2());
            d("mt", w.mt());
            d("et", w.et());
            d("rap", w.rapidity());
            d("prap", w.pseudoRapidity());
            d("beta4", w.beta());
            d("gamma4", w.gamma());
            d("plus", w.plus());
            d("minus", w.minus());
            d("invmass", w.invariantMass(z));
            d("dot4", w.dot(z));
            d("howNear4", w.howNear(z));
            d("howNearCM", w.howNearCM(z));
            i("isNearCM", w.isNearCM(z, 0.5));
            d("deltaR4", w.deltaR(z));
            d("howParallel4", w.howParallel(z));
            d("coLin4", w.coLinearRapidity());
            d("et2ref", w.et2(b.mag2() > 0 ? b : new Hep3Vector(1, 0, 0)));
            v3("boostVector", w.boostVector());
            v3("toCM", w.findBoostToCM(z));
            final HepLorentzVector w2 = new HepLorentzVector(w);
            {
                final Hep3Vector t = rv(0.4);
                w2.boost(t.x(), t.y(), t.z());
            }
            v4("boost", w2);
            w2.boostX(sym(0.9));
            w2.boostY(sym(0.9));
            w2.boostZ(sym(0.9));
            v4("boostXYZ", w2);
            {
                final Hep3Vector t2 = rv(1);
                final double t1 = sym(0.9);
                w2.boost(t2, t1);
            }
            v4("boostAxis", w2);
            w2.rotateX(sym(3));
            w2.rotateUz(u);
            v4("rot4", w2);
            v4("rest", w.rest4Vector());
            v4("div4", w.div(pos(1, 1)));
            {
                final String s = w.print(new COStream()).str();
                text("print4", s);
                final HepLorentzVector back = new HepLorentzVector();
                HepLorentzVector.read(new CIStream(s), back);
                v4("read4", back);
            }
            final Hep3Vector bv = rv(0.5);
            final Hep3Vector cdir = rv(1);
            final double cb = sym(0.9);
            final HepBoost B = new HepBoost(bv.x(), bv.y(), bv.z()), C = new HepBoost(cdir, cb);
            v4("Bw", B.times(w));
            v3("B.bv", B.boostVector());
            d("B.beta", B.beta());
            v3("B.dir", B.direction());
            r4("BC", B.times(C));
            r4("BR", B.times(R));
            d("B.dist", B.distance2(C));
            d("B.distR", B.distance2(R));
            d("B.norm2", B.norm2());
            final HepBoost Bi = B.inverse();
            v4("Biw", Bi.times(w));
            final HepBoost Br = new HepBoost(B);
            Br.rectify();
            v3("Brect", Br.boostVector());
            final double bxb = sym(0.95), byb = sym(0.95), bzb = sym(0.95);
            final HepBoostX BX = new HepBoostX(bxb);
            final HepBoostY BY = new HepBoostY(byb);
            final HepBoostZ BZ = new HepBoostZ(bzb);
            v4("BXw", BX.times(w));
            v4("BYw", BY.times(w));
            v4("BZw", BZ.times(w));
            d("BX2", BX.times(new HepBoostX(sym(0.9))).beta());
            r4("BX*R", BX.times(R));
            d("B.distBY", B.distance2(BY));
            d("BY.distB", BY.distance2(B));
            final HepLorentzRotation L = new HepLorentzRotation(B, R);
            r4("L", L);
            final HepBoost lb = new HepBoost();
            final HepRotation lr = new HepRotation();
            L.decompose(lb, lr);
            v3("L.boost", lb.boostVector());
            r3("L.rot", lr);
            final HepRotation lr2 = new HepRotation();
            final HepBoost lb2 = new HepBoost();
            L.decompose(lr2, lb2);
            v3("L.boost2", lb2.boostVector());
            r3("L.rot2", lr2);
            final HepLorentzRotation M = new HepLorentzRotation(R, C);
            r4("M", M);
            r4("LM", L.times(M));
            r4("Linv", L.inverse());
            d("L.dist", L.distance2(M));
            d("L.norm2", L.norm2());
            d("L.distB", L.distance2(B));
            d("L.distR", L.distance2(R));
            final HepLorentzRotation N = new HepLorentzRotation(L);
            N.rotateX(sym(2));
            N.rotateY(sym(2));
            N.rotateZ(sym(2));
            N.boostX(sym(0.5));
            N.boostY(sym(0.5));
            N.boostZ(sym(0.5));
            N.rotate(sym(2), new Hep3Vector(1, 2, 3));
            {
                final Hep3Vector t = rv(0.3);
                N.boost(t.x(), t.y(), t.z());
            }
            r4("N", N);
            final HepLorentzRotation P = new HepLorentzRotation(N);
            P.rectify();
            r4("Prect", P);
            final HepLorentzRotation Cols = new HepLorentzRotation(L.col1(), L.col2(), L.col3(), L.col4());
            r4("Cols", Cols);
            v4("Lw", L.times(w));
            final HepLorentzVector w3 = new HepLorentzVector(w);
            w3.timesAssign(L);
            v4("w*=L", w3);
            r4("R*L", HepLorentzRotation.times(R, L));
            {
                final COStream os = new COStream();
                L.print(os);
                B.print(os);
                BX.print(os);
                text("printL", os.str());
            }
        }
    }

    private Hep3Vector rv(double s) {
        final double x = sym(s), y = sym(s), z = sym(s);
        return new Hep3Vector(x, y, z);
    }

    private void v3(String n, Hep3Vector v) {
        d(n + ".x", v.x());
        d(n + ".y", v.y());
        d(n + ".z", v.z());
    }

    private void v4(String n, HepLorentzVector v) {
        v3(n, v.vect());
        d(n + ".t", v.t());
    }

    private void r3(String n, HepRotation r) {
        d(n + ".xx", r.xx());
        d(n + ".xy", r.xy());
        d(n + ".xz", r.xz());
        d(n + ".yx", r.yx());
        d(n + ".yy", r.yy());
        d(n + ".yz", r.yz());
        d(n + ".zx", r.zx());
        d(n + ".zy", r.zy());
        d(n + ".zz", r.zz());
    }

    private void r4(String n, HepLorentzRotation r) {
        for (int a = 0; a < 4; a++) {
            for (int b = 0; b < 4; b++) {
                d(n + "." + a + b, r.get(a, b));
            }
        }
    }
}
