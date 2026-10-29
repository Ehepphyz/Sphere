package com.sphere.core.clhep.validation;

import static com.sphere.core.clhep.validation.ClhepTests.say;

import com.sphere.core.clhep.matrix.HepDiagMatrix;
import com.sphere.core.clhep.matrix.HepGenMatrix;
import com.sphere.core.clhep.matrix.HepMatrix;
import com.sphere.core.clhep.matrix.HepSymMatrix;
import com.sphere.core.clhep.matrix.HepVector;
import com.sphere.core.clhep.matrix.MatrixLinear;
import com.sphere.core.clhep.random.HepJamesRandom;
import com.sphere.core.clhep.random.HepRandom;
import com.sphere.core.clhep.random.RandFlat;
import com.sphere.core.clhep.validation.ClhepTests.Io;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's Matrix tests (Matrix/test), as CTest builds them: INSTALLATION_CHECK defined, which leaves out of
 * testInversion the products printed in full precision and makes its timing loops 10 inversions long.
 */
final class MatrixTests {

    private MatrixTests() {
    }

    private static final HepMatrix.ElementFunction NEG = (f, r, c) -> -f;
    private static final HepMatrix.ElementFunction ABSF = (f, r, c) -> Math.abs(f);
    private static final HepVector.ElementFunction NEGV = (f, r) -> -f;

    /** HepRandom r; r.setTheSeed(31); the default engine, reseeded. */
    private static HepRandom seeded31() {
        final HepRandom r = new HepRandom();
        HepRandom.setTheSeed(31);
        return r;
    }

    /* ---- testMatrix ---- */

    static int testMatrix(Io io) {
        final COStream cout = io.cout;
        cout.fixed().width(10);
        matrixTest(cout);
        symmatrixTest(cout);
        diagmatrixTest(cout);
        vectorTest(cout);
        // Test of default constructor
        final HepMatrix m = new HepMatrix();
        // Test of constructors.
        final HepMatrix d = new HepMatrix(3, 3, 1);
        final HepMatrix e = new HepMatrix(3, 3, 0);
        final HepMatrix f = new HepMatrix(5, 3);
        new HepMatrix(f);
        // Test of constructor with a Random object.
        final HepRandom r = seeded31();
        final HepMatrix a = new HepMatrix(3, 3, r);
        say(cout, a, "\n");
        final HepMatrix b = new HepMatrix(3, 5, r);
        say(cout, b, "\n");
        final HepSymMatrix dds = new HepSymMatrix(3, r);
        new HepMatrix(dds);
        final HepDiagMatrix ddd = new HepDiagMatrix(3, r);
        new HepMatrix(ddd);
        final HepVector ddv = new HepVector(3, r);
        final HepMatrix ddmv = new HepMatrix(ddv);
        say(cout, "nraw=", b.num_row(), " ncol=", b.num_col(), "\n");
        say(cout, "b(1,1)=", b.get(1, 1), " b(2,1)=", b.get(2, 1), "\n");
        b.set(2, 3, 1.0);
        b.divAssign(3.0);
        b.timesAssign(6.0);
        final HepSymMatrix sm = new HepSymMatrix(3, r);
        final HepDiagMatrix dm = new HepDiagMatrix(3, r);
        final HepVector vvm = new HepVector(3, r);
        d.plusAssign(e);
        d.plusAssign(sm);
        d.plusAssign(dm);
        ddmv.plusAssign(vvm);
        a.minusAssign(e);
        a.minusAssign(sm);
        a.minusAssign(dm);
        ddmv.minusAssign(vvm);
        d.assign(sm);
        d.assign(dm);
        d.assign(a);
        ddmv.assign(vvm);
        e.assign(d.apply(NEG));
        a.assign(d.T());
        e.assign(b.sub(1, 2, 3, 2));
        b.sub(1, 3, e);
        HepMatrix.swap(a, d);
        m.assign(d.times(a));
        m.assign(d.times(sm));
        m.assign(d.times(dm));
        m.assign(sm.times(d));
        say(cout, "Dm=", dm, " d = ", d, "\n");
        m.assign(dm.times(d));
        m.assign(sm.times(sm));
        m.assign(dm.times(dm));
        // SymMatrix
        final HepSymMatrix s = new HepSymMatrix();
        final HepSymMatrix ss = new HepSymMatrix(6);
        final HepSymMatrix si = new HepSymMatrix(6, 1);
        new HepSymMatrix(6, 0);
        final HepSymMatrix sr = new HepSymMatrix(6, r);
        final HepSymMatrix sc = new HepSymMatrix(sr);
        new HepSymMatrix(dm);
        say(cout, "nraw=", sr.num_row(), " ncol=", sr.num_col(), "\n");
        say(cout, "sr(1,1)=", sr.get(1, 1), " sr(2,1)=", sr.get(2, 1), "\n");
        sr.set(4, 3, r.call());
        sr.setFast(3, 1, r.call());
        s.assign(d);
        say(cout, "d=", d, "s=", s, "\n");
        ss.assign(sc);
        ss.timesAssign(0.5);
        ss.divAssign(0.2);
        final HepDiagMatrix ddds = new HepDiagMatrix(ss.num_row(), r);
        ss.plusAssign(si);
        ss.plusAssign(ddds);
        ss.minusAssign(sr);
        ss.minusAssign(ddds);
        ss.assign(ddds);
        s.assign(sr);
        s.assign(ss.T());
        s.apply(NEG);
        final HepMatrix mm = new HepMatrix(6, 4, r);
        ss.assign(s.similarityT(mm));
        final HepMatrix mt = new HepMatrix(8, 6, r);
        ss.assign(s.similarity(mt));
        s.assign(d);
        s.assign(sr.sub(2, 5));
        ss.sub(2, s);
        s.assign(ss.sub(2, 7));
        m.assign(s.times(sr));
        m.assign(s.times(m));
        m.assign(m.times(s));
        final HepVector v = new HepVector(6, r);
        s.assign(HepSymMatrix.vT_times_v(v));
        // Test of HepDiagMatrix
        final HepDiagMatrix dt = new HepDiagMatrix();
        new HepDiagMatrix(6);
        final HepDiagMatrix di = new HepDiagMatrix(6, 1);
        final HepDiagMatrix dz = new HepDiagMatrix(6, 0);
        final HepDiagMatrix dr = new HepDiagMatrix(6, r);
        final HepDiagMatrix dc = new HepDiagMatrix(dr);
        say(cout, "Nr=", di.num_row(), " Nc=", dz.num_col(), "\n");
        say(cout, "dr(1,1)=", dr.get(1, 1), "\n");
        dr.set(4, 4, r.call());
        dr.setFast(2, 2, r.call());
        dt.assign(m);
        dt.assign(s);
        dt.assign(dc);
        dr.timesAssign(3.0);
        dr.divAssign(3.0);
        dr.plusAssign(dt);
        dr.minusAssign(di);
        dt.assign(dz);
        dz.assign(dt.T());
        di.assign(dt.apply(NEG));
        s.assign(dr.similarityT(mm));
        s.assign(dr.similarity(mt));
        say(cout, "sim=", dr.similarity(v));
        dt.assign(dr.sub(2, 5));
        dz.sub(3, dt);
        // Test of HepVector
        final HepVector vt = new HepVector();
        new HepVector(6);
        final HepVector vz = new HepVector(6, 0);
        final HepVector vi = new HepVector(6, 1);
        final HepVector vr = new HepVector(6, r);
        final HepVector vc = new HepVector(vr);
        say(cout, "vz=", vz, "vi=", vi, "\n");
        // HepVector has no constructor, +=, -= or = from the square matrices: they convert to HepMatrix first
        final HepVector vm = new HepVector(new HepMatrix(6, 1, r));
        final HepVector vs = new HepVector(new HepMatrix(new HepSymMatrix(1, r)));
        final HepVector vd = new HepVector(new HepMatrix(new HepDiagMatrix(1, r)));
        say(cout, "Nr=", vr.num_row(), "\n");
        say(cout, "vr(3)=", vr.get(3), "\n");
        vr.set(2, r.call());
        vi.timesAssign(2.5);
        vi.divAssign(2.5);
        vm.plusAssign(new HepMatrix(6, 1, r));
        vs.plusAssign(new HepMatrix(new HepSymMatrix(1, r)));
        vd.plusAssign(new HepMatrix(new HepDiagMatrix(1, r)));
        vi.plusAssign(vr);
        vm.minusAssign(new HepMatrix(6, 1, r));
        vs.minusAssign(new HepMatrix(new HepSymMatrix(1, r)));
        vd.minusAssign(new HepMatrix(new HepDiagMatrix(1, r)));
        vi.minusAssign(vc);
        vm.assign(new HepMatrix(6, 1, r));
        vs.assign(new HepMatrix(new HepSymMatrix(1, r)));
        vd.assign(new HepMatrix(new HepDiagMatrix(1, r)));
        vt.assign(vc);
        vt.assign(vc.apply(NEGV));
        vt.assign(vc.sub(2, 5));
        vc.sub(3, vt);
        say(cout, "Normsq=", vc.normsq(), "Norm=", vc.norm(), "\n");
        m.assign(vc.T());
        HepVector.swap(vc, vr);
        vt.assign(sr.times(vr));
        vt.assign(dr.times(vr));
        vt.assign(mt.times(vr));
        f.assign(vr.times(m));
        // Test of other operators
        f.assign(HepMatrix.times(3.5, m));
        s.assign(HepSymMatrix.times(3.5, ss));
        dt.assign(HepDiagMatrix.times(3.5, dr));
        vt.assign(HepVector.times(3.5, vr));
        f.assign(m.times(3.6));
        s.assign(ss.times(3.6));
        dt.assign(dr.times(3.6));
        vt.assign(vr.times(3.6));
        f.assign(m.div(3.6));
        s.assign(ss.div(3.6));
        dt.assign(dr.div(3.6));
        vt.assign(vr.div(3.6));
        d.assign(new HepMatrix(6, 3, r));
        e.assign(new HepMatrix(6, 3, r));
        m.assign(d.plus(e));
        d.assign(new HepMatrix(6, 6, r));
        m.assign(d.plus(sr));
        m.assign(d.plus(dr));
        m.assign(sr.plus(d));
        m.assign(dr.plus(d));
        s.assign(sr.plus(si));
        dt.assign(dr.plus(di));
        s.assign(dr.plus(sr));
        s.assign(sr.plus(dr));
        e.assign(new HepMatrix(6, 1, r));
        v.assign(e.plus(vr));
        v.assign(vr.plus(e));
        v.assign(vr.plus(vz));
        d.assign(new HepMatrix(6, 3, r));
        e.assign(new HepMatrix(6, 3, r));
        m.assign(d.minus(e));
        d.assign(new HepMatrix(6, 6, r));
        dr.assign(new HepDiagMatrix(6, r));
        m.assign(d.minus(sr));
        m.assign(d.minus(dr));
        m.assign(sr.minus(d));
        m.assign(dr.minus(d));
        s.assign(sr.minus(si));
        dt.assign(dr.minus(di));
        s.assign(dr.minus(sr));
        s.assign(sr.minus(dr));
        e.assign(new HepMatrix(6, 1, r));
        v.assign(e.minus(vr));
        v.assign(vr.minus(e));
        v.assign(vr.minus(vz));
        // Test of Matrix inversion
        m.assign(new HepMatrix(6, 6, r));
        final int[] inv = new int[1];
        a.assign(m.inverse(inv));
        say(cout, "Inv=", inv[0], "\n");
        if (inv[0] == 0) {
            final HepMatrix am = a.times(m);
            final HepMatrix ma = m.times(a);
            say(cout, "a*m=", am.apply(ABSF), "m*a=", ma.apply(ABSF), "\n");
        }
        v.assign(new HepVector(6, r));
        vt.assign(HepVector.solve(m, v));
        say(cout, "m*vt=", m.times(vt), "v=", v, "\n");
        ss.assign(new HepSymMatrix(6, r));
        s.assign(ss.inverse(inv));
        say(cout, "Inv=", inv[0], "\n");
        if (inv[0] == 0) {
            final HepMatrix sss = s.times(ss);
            final HepMatrix ssss = ss.times(s);
            say(cout, sss.apply(ABSF), ssss.apply(ABSF), "\n");
        }
        say(cout, "Before diag:ss=", ss, "\n");
        s.assign(ss);
        m.assign(MatrixLinear.diagonalize(ss));
        say(cout, "m=", m, "\n");
        say(cout, "ss=", ss, "\n");
        say(cout, "Sim", ss.similarity(m), "\n");
        final HepSymMatrix diff = new HepSymMatrix(ss.similarity(m).minus(s));
        say(cout, "Diff", diff.apply(ABSF), "\n");
        m.assign(new HepMatrix(6, 6, r));
        a.assign(MatrixLinear.qr_inverse(m));
        final HepMatrix am = a.times(m);
        final HepMatrix ma = m.times(a);
        say(cout, am.apply(ABSF), ma.apply(ABSF), "\n");
        say(cout, "Norm 1 =", HepGenMatrix.norm1(m), "\n");
        say(cout, "Norm 2 =", HepGenMatrix.norm(m), "\n");
        say(cout, "Norm i =", HepGenMatrix.norm_infinity(m), "\n");
        m.assign(new HepMatrix(8, 6, r));
        say(cout, "m=", m, "\n");
        a.assign(MatrixLinear.qr_decomp(m));
        say(cout, "a=", a, "m=", m, "\n");
        say(cout, "a*m=", a.times(m), "\n");
        m.assign(new HepMatrix(8, 8, r));
        v.assign(new HepVector(8, r));
        vt.assign(MatrixLinear.qr_solve(m, v));
        say(cout, "v=", v, " m*vt=", m.times(vt), "\n");
        m.assign(new HepMatrix(8, 8, r));
        a.assign(new HepMatrix(8, 3, r));
        b.assign(MatrixLinear.qr_solve(m, a));
        say(cout, "v=", a, " m*b=", m.times(b), "\n");
        say(cout, "Test was successful", "\n");
        // Spherisity
        final HepJamesRandom myengine = new HepJamesRandom();
        final RandFlat ab = new RandFlat(myengine);
        final HepVector[] p = new HepVector[10];
        int i;
        for (i = 0; i < 10; i++) {
            p[i] = new HepVector(3, ab);
            p[i].timesAssign(2.0);
            p[i].set(1, p[i].get(1) - 1);
            p[i].set(2, p[i].get(2) - 1);
            p[i].set(3, p[i].get(3) - 1);
        }
        final HepSymMatrix sp = new HepSymMatrix(3, 0);
        for (i = 0; i < 10; i++) {
            final double psq = p[i].normsq();
            sp.set(1, 1, sp.get(1, 1) + (psq - p[i].get(1) * p[i].get(1)));
            sp.set(2, 2, sp.get(2, 2) + (psq - p[i].get(2) * p[i].get(2)));
            sp.set(3, 3, sp.get(3, 3) + (psq - p[i].get(3) * p[i].get(3)));
            sp.set(2, 1, sp.get(2, 1) - p[i].get(2) * p[i].get(1));
            sp.set(3, 1, sp.get(3, 1) - p[i].get(3) * p[i].get(1));
            sp.set(3, 2, sp.get(3, 2) - p[i].get(3) * p[i].get(2));
        }
        final HepMatrix spd = MatrixLinear.diagonalize(sp);
        say(cout, "sp=", sp, " spd=", spd, "\n");
        final HepSymMatrix spps = new HepSymMatrix(7, r);
        final HepSymMatrix sppss = new HepSymMatrix(spps);
        say(cout, "condition = ", MatrixLinear.condition(spps), "\n");
        final HepMatrix sb = MatrixLinear.diagonalize(spps);
        final HepSymMatrix sppssdiff = new HepSymMatrix(sppss.minus(spps.similarity(sb)));
        say(cout, "spps=", spps, "sppss - sim = ", sppssdiff.apply(ABSF), "\n");
        return 0;
    }

    private static void matrixTest(COStream cout) {
        say(cout, "Starting Matrix tests", "\n");
        new HepMatrix();
        {
            new HepMatrix(3, 5);
            final HepMatrix b = new HepMatrix(4, 5, 0);
            say(cout, "4x5 matrix initialized to zero ", b);
            final HepMatrix c = new HepMatrix(3, 3, 1);
            say(cout, "3x3 matrix initialized to identity ", c);
        }
        {
            final HepRandom r = seeded31();
            final HepMatrix a = new HepMatrix(3, 3, r);
            say(cout, "3x3 matrix initialized with Random ", a);
            final HepMatrix b = new HepMatrix(a);
            say(cout, "matrix initialized to the previous matrix ", b);
        }
        {
            final HepRandom r = seeded31();
            final HepMatrix a = new HepMatrix(8, 5, r);
            final HepMatrix b = a.sub(2, 6, 3, 5);
            say(cout, "8x5 matrix", a);
            say(cout, "sub matrix (2-6 x 3-5 of the previous matrix.", b);
            final HepMatrix c = new HepMatrix(2, 3, 0);
            a.sub(4, 3, c);
            say(cout, "embedding sub matrix at 4,3", a);
            final HepMatrix d = new HepMatrix(3, 2, r);
            say(cout, "dsum", HepMatrix.dsum(a, d));
        }
        {
            final HepRandom r = seeded31();
            final HepMatrix a = new HepMatrix(5, 5, r);
            final HepMatrix b = new HepMatrix(5, 5, r);
            say(cout, "a", a);
            say(cout, "b", b);
            say(cout, "a += b", a.plusAssign(b));
            final HepMatrix c = new HepMatrix();
            c.assign(a.plus(b));
            say(cout, "a + b", c);
        }
        {
            final HepRandom r = seeded31();
            final HepMatrix a = new HepMatrix(5, 3, r);
            final HepMatrix b = a.T();
            say(cout, "a", a);
            say(cout, "a.T", b);
        }
        say(cout, "End of Matrix tests", "\n");
    }

    private static void symmatrixTest(COStream cout) {
        new HepSymMatrix();
        {
            new HepSymMatrix(3);
            final HepSymMatrix b = new HepSymMatrix(4, 0);
            say(cout, "4x4 Symmetric matrix initialuzed to zero ", b);
            final HepSymMatrix c = new HepSymMatrix(3, 1);
            say(cout, "3x3 Symmetric matrix initialized to identity ", c);
        }
        {
            final HepRandom r = seeded31();
            final HepSymMatrix a = new HepSymMatrix(3, r);
            say(cout, "3x3 symmetric matrix initialized with Random ", a, "\n");
            final HepSymMatrix b = new HepSymMatrix(a);
            say(cout, "symmetric matrix initialized to the previous matrix ", b);
            final HepMatrix c = new HepMatrix(b);
            say(cout, "matrix initialized to the previous symmetric matrix ", c);
            // Access to elements: c(i,j) from 1, c[i][j] from 0
            final double f = 3.8;
            final double g = 22.5;
            say(cout, c.get(1, 1), " ", c.get(1, 1), "\n");
            c.set(1, 2, f);
            c.set(3, 2, g);
            c.set(2, 3, f);
            c.set(2, 3, g);
            say(cout, c, "\n");
            final HepMatrix d = c;
            say(cout, d.get(1, 1), " ", d.get(1, 1), "\n");
        }
        {
            final HepRandom r = seeded31();
            final HepSymMatrix a = new HepSymMatrix(5, r);
            final HepSymMatrix b = a.sub(2, 5);
            say(cout, "5x5 sym matrix", a);
            say(cout, "sub sym matrix (2-5 x 2-5 of the previous sub matrix.", b);
            final HepSymMatrix c = new HepSymMatrix(3, 0);
            a.sub(2, c);
            say(cout, "embedding sub matrix at 2", a);
        }
        {
            final HepRandom r = seeded31();
            final HepMatrix a = new HepMatrix(5, 5, r);
            final HepSymMatrix b = new HepSymMatrix(5, r);
            say(cout, "a", a);
            say(cout, "b(sym)", b);
            say(cout, "a += b", a.plusAssign(b));
            final HepMatrix c = a.plus(b);
            say(cout, "a + b", c);
        }
        {
            final HepRandom r = seeded31();
            final HepMatrix a = new HepMatrix(5, 3, r);
            final HepSymMatrix b = new HepSymMatrix(3, r);
            final HepSymMatrix c = b.similarity(a);
            say(cout, "a", a);
            say(cout, "b", b);
            say(cout, "c", c);
        }
        {
            final HepRandom r = seeded31();
            final HepMatrix a = new HepMatrix(3, 5, r);
            final HepSymMatrix b = new HepSymMatrix(3, r);
            final HepSymMatrix c = b.similarityT(a);
            say(cout, "a", a);
            say(cout, "b", b);
            say(cout, "c", c);
        }
        {
            final HepRandom r = seeded31();
            final HepSymMatrix a = new HepSymMatrix(3, r);
            final HepSymMatrix b = new HepSymMatrix(3, r);
            final HepSymMatrix c = b.similarity(a);
            say(cout, "a", a);
            say(cout, "b", b);
            say(cout, "c", c);
        }
        {
            final HepRandom r = seeded31();
            final HepVector a = new HepVector(3, r);
            final HepSymMatrix b = new HepSymMatrix(3, r);
            final double c = b.similarity(a);
            final HepSymMatrix cc = b.similarity(new HepMatrix(a.T()));
            say(cout, "a", a);
            say(cout, "b", b);
            say(cout, "c\t", c, "\n");
            say(cout, "c", cc);
        }
        say(cout, "End of SymMatrix tests", "\n");
    }

    private static void diagmatrixTest(COStream cout) {
        new HepDiagMatrix();
        {
            new HepDiagMatrix(3);
            final HepDiagMatrix b = new HepDiagMatrix(4, 0);
            say(cout, "4x4 diagonal matrix initialized to zero ", b);
            final HepDiagMatrix c = new HepDiagMatrix(3, 1);
            say(cout, "3x3 diagonal matrix initialized to identity ", c);
        }
        {
            final HepRandom r = seeded31();
            final HepDiagMatrix a = new HepDiagMatrix(3, r);
            say(cout, "3x3 diagonal matrix initialized to Random ", a);
            final HepDiagMatrix b = new HepDiagMatrix(a);
            say(cout, "diagonal matrix initialized to the previous matrix ", b);
            final HepMatrix c = new HepMatrix(b);
            say(cout, "matrix initialized to the previous diagonal matrix ", c);
            final HepSymMatrix d = new HepSymMatrix(b);
            say(cout, "Symmetric matrix initialized to the previous diagonal matrix ", d);
        }
        {
            final HepRandom r = seeded31();
            final HepDiagMatrix a = new HepDiagMatrix(8, r);
            final HepDiagMatrix b = a.sub(2, 5);
            say(cout, "8x8 diag matrix", a);
            say(cout, "sub diag matrix (2-5 x 2-5 of the previous diag matrix.", b);
            final HepDiagMatrix c = new HepDiagMatrix(3, 0);
            a.sub(2, c);
            say(cout, "embedding sub matrix at 2", a);
        }
        {
            final HepRandom r = seeded31();
            final HepMatrix a = new HepMatrix(5, 5, r);
            final HepDiagMatrix b = new HepDiagMatrix(5, r);
            say(cout, "a", a);
            say(cout, "b(diag)", b);
            say(cout, "a += b", a.plusAssign(b));
            final HepMatrix c = a.plus(b);
            say(cout, "a + b", c);
        }
        say(cout, "End of DiagMatrix tests", "\n");
    }

    private static void vectorTest(COStream cout) {
        new HepVector();
        {
            new HepVector(3);
            final HepVector b = new HepVector(4, 0);
            say(cout, "4 vector initialized to zero ", b);
            final HepVector c = new HepVector(3, 1);
            say(cout, "3 vector initialized to identity ", c);
        }
        {
            final HepRandom r = seeded31();
            final HepVector a = new HepVector(3, r);
            say(cout, "3 vector initialized to Random ", a);
            final HepVector b = new HepVector(a);
            say(cout, "Vector initialized to the previous vector ", b);
            final HepMatrix c = new HepMatrix(b);
            say(cout, "matrix initialized to the previous vector ", c);
            final HepVector d = new HepVector(c);
            say(cout, "Vector initialized to the previous matrix ", d);
        }
        {
            final HepRandom r = seeded31();
            final HepVector a = new HepVector(8, r);
            final HepVector b = a.sub(2, 5);
            say(cout, "8 vector", a);
            say(cout, "sub vector (2-5 of the previous vector.", b);
            final HepVector c = new HepVector(3, 0);
            a.sub(2, c);
            say(cout, "embedding sub vector at 2 ", a);
        }
        {
            final HepRandom r = seeded31();
            final HepMatrix a = new HepMatrix(5, 1, r);
            final HepVector b = new HepVector(5, r);
            say(cout, "a", a);
            say(cout, "b(vector)", b);
            say(cout, "a += b", a.plusAssign(b));
            // HepMatrix + HepVector is a HepVector, printed here as the HepMatrix it initializes
            final HepMatrix c = new HepMatrix(a.plus(b));
            say(cout, "a + b", c);
        }
        say(cout, "End of Vector tests", "\n");
    }

    /* ---- testInversion ---- */

    static int testInversion(Io io) {
        final COStream cout = io.cout;
        int n;
        int j;
        final int[] ierr1 = new int[1];
        final int[] ierr2 = new int[1];
        long zeit1;
        long zeit2;
        final HepSymMatrix A = new HepSymMatrix(5, 1);
        A.set(1, 1, 6);
        A.set(1, 2, .8545);
        A.set(1, 3, -.684651);
        A.set(1, 4, .372547);
        A.set(1, 5, .68151);
        A.set(2, 2, 4);
        A.set(2, 3, .5151);
        A.set(2, 4, .5151);
        A.set(2, 5, .651651);
        A.set(3, 3, 5);
        A.set(3, 4, .3);
        A.set(3, 5, .363);
        A.set(4, 4, -3);
        A.set(4, 5, -.23753);
        A.set(5, 5, -5);
        final HepSymMatrix B = new HepSymMatrix(A);
        final HepSymMatrix D = new HepSymMatrix(A);
        final HepSymMatrix C = new HepSymMatrix(5, 0);
        final HepMatrix M = new HepMatrix(A);
        say(cout, "M inverse", M.inverse(ierr2), "\n");
        C.assign(B.inverse(ierr1));
        D.invert(ierr2);
        say(cout, "B ", B, "\n");
        say(cout, "B inverse", C, "\n");
        say(cout, "ierr1: ", ierr1[0], "\n");
        say(cout, "D * inverse", D.times(C), "\n");
        say(cout, "ierr2: ", ierr2[0], "\n");
        say(cout, "M inverse", M.inverse(ierr2), "\n");
        say(cout, "ierr2: ", ierr2[0], "\n");
        n = 10;
        zeit1 = time();
        say(cout, "Executing ", n, " inversions ...", "\n");
        for (j = 0; j < n; j++) {
            B.invert(ierr1);
            if (ierr1[0] != 0) say(cout, "B: error in invert", "\n");
        }
        zeit2 = time();
        say(cout, "B: duration of inversion: ", zeit2 - zeit1, " secs", "\n");
        zeit1 = time();
        say(cout, "Executing ", n, " inversions ...", "\n");
        for (j = 0; j < n; j++) {
            D.invert(ierr2);
            if (ierr2[0] != 0) say(cout, "D: error in invert", "\n");
        }
        zeit2 = time();
        say(cout, D, "\n");
        say(cout, "D: duration of inversion: ", zeit2 - zeit1, " secs", "\n");
        return 0;
    }

    /** time(NULL). */
    private static long time() {
        return Math.floorDiv(System.currentTimeMillis(), 1000);
    }

    /* ---- testBug6181 ---- */

    private static int testInversion(COStream cout, int N) {
        int i;
        int j;
        final HepMatrix M = new HepMatrix(N, N, 0);
        final HepSymMatrix S = new HepSymMatrix(N, 0);
        for (i = 1; i <= N; ++i) {
            for (j = 1; j <= N; ++j) {
                if (i <= j) {
                    S.set(i, j, 10 * i + j);
                    M.set(i, j, 10 * i + j);
                    M.set(j, i, M.get(i, j) + .1 * (i - j) * (i - j));
                }
            }
        }
        final HepMatrix MM = new HepMatrix(N, N, 0);
        final HepSymMatrix SS = new HepSymMatrix(N, 0);
        MM.assign(M);
        SS.assign(S);
        final int[] ierr = {0};
        MM.invert(ierr);
        if (ierr[0] != 0) {
            say(cout, "MM.invert failed!!!!  N = ", N, " ierr =  ", ierr[0], "\n");
            return 1 + 100 * N;
        }
        ierr[0] = 0;
        SS.invert(ierr);
        if (ierr[0] != 0) {
            say(cout, "SS.invert failed!!!!  N = ", N, " ierr =  ", ierr[0], "\n");
            return 2 + 100 * N;
        }
        final HepMatrix MI = new HepMatrix(N, N, 0);
        final HepMatrix SI = new HepMatrix(N, N, 0);
        final HepMatrix MS = new HepMatrix(N, N, 0);
        final HepMatrix MSS = new HepMatrix(N, N, 0);
        MI.assign(MM.times(M));
        MS.assign(S);
        MSS.assign(SS);
        SI.assign(MSS.times(MS));
        for (i = 1; i <= N; ++i) {
            for (j = 1; j <= N; ++j) {
                if (i != j) {
                    if (Math.abs(MI.get(i, j)) > 1.0e-12) {
                        say(cout, "MM.invert incorrect  N = ", N, " error =  ", Math.abs(MI.get(i, j)), "\n");
                        return 3 + 100 * N;
                    }
                    if (Math.abs(SI.get(i, j)) > 1.0e-12) {
                        say(cout, "SS.invert incorrect  N = ", N, " error =  ", Math.abs(SI.get(i, j)), "\n");
                        return 4 + 100 * N;
                    }
                }
                if (i == j) {
                    if (Math.abs(1 - MI.get(i, j)) > 1.0e-12) {
                        say(cout, "MM.invert incorrect  N = ", N, " error =  ", Math.abs(1 - MI.get(i, j)), "\n");
                        return 3 + 100 * N;
                    }
                    if (Math.abs(1 - SI.get(i, j)) > 1.0e-12) {
                        say(cout, "SS.invert incorrect  N = ", N, " error =  ", Math.abs(1 - SI.get(i, j)), "\n");
                        return 4 + 100 * N;
                    }
                }
            }
        }
        // Finally, the case that actually (for N=7) triggered bug report 6181:
        M.assign(S);
        MM.assign(M);
        MM.invert(ierr);
        if (ierr[0] != 0) {
            say(cout, "MM.invert for symmetric case failed!!!!  N = ", N, " ierr =  ", ierr[0], "\n");
            return 5 + 100 * N;
        }
        MI.assign(MM.times(M));
        for (i = 1; i <= N; ++i) {
            for (j = 1; j <= N; ++j) {
                if (i != j) {
                    if (Math.abs(MI.get(i, j)) > 1.0e-12) {
                        say(cout, "MM.invert incorrect  N = ", N, " error =  ", Math.abs(MI.get(i, j)), "\n");
                        return 6 + 100 * N;
                    }
                }
                if (i == j) {
                    if (Math.abs(1 - MI.get(i, j)) > 1.0e-12) {
                        say(cout, "MM.invert incorrect  N = ", N, " error =  ", Math.abs(1 - MI.get(i, j)), "\n");
                        return 7 + 100 * N;
                    }
                }
            }
        }
        return 0;
    }

    static int testBug6181(Io io) {
        int ret = 0;
        for (int i = 1; i < 10; i++) {
            ret = testInversion(io.cout, i);
            if (ret != 0) return ret;
        }
        return ret;
    }
}
