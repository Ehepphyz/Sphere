package com.sphere.core.clhep.validation;

import static com.sphere.core.clhep.units.SystemOfUnits.pi;
import static com.sphere.core.clhep.validation.ClhepTests.check;
import static com.sphere.core.clhep.validation.ClhepTests.say;

import com.sphere.core.clhep.CMath;
import com.sphere.core.clhep.geometry.BasicVector3D;
import com.sphere.core.clhep.geometry.BasicVector3DF;
import com.sphere.core.clhep.geometry.Normal3D;
import com.sphere.core.clhep.geometry.Normal3DF;
import com.sphere.core.clhep.geometry.Point3D;
import com.sphere.core.clhep.geometry.Point3DF;
import com.sphere.core.clhep.geometry.Rotate3D;
import com.sphere.core.clhep.geometry.Transform3D;
import com.sphere.core.clhep.geometry.Vector3D;
import com.sphere.core.clhep.geometry.Vector3DF;
import com.sphere.core.clhep.validation.ClhepTests.Io;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * CLHEP's testBasicVector3D (Geometry/test): its CHECK(point,type) macro for the points, vectors and normals of
 * floats and of doubles, each reading a vector from the input and printing it.
 */
final class GeometryTests {

    private GeometryTests() {
    }

    /** The asserts of CHECK, in order, as the preprocessor writes them: "point" and "type" its arguments. */
    private static final String[] TEXTS = {
        "p00.x() == 0 && p00.y() == 0 && p00.z() == 0", "p01.x() == 1 && p01.y() == 2 && p01.z() == 3",
        "p02.x() == 4 && p02.y() == 5 && p02.z() == 6", "p03 == point(1,2,3)", "p04 == point(4,5,6)",
        "a[0] == 1 && a[1] == 2 && a[2] == 3", "a[0] == 4 && a[1] == 5 && a[2] == 6", "p06 == point(7,8,9)",
        "p10 == point(1,2,3)", "p11 == point(1,2,3)", "p12 == point(4,5,6)", "p13 == point(7,8,9)", "p14 == point(1,2,3)",
        "p15 == point(4,5,6)", "p16 == point(7,8,9)", "p20 == point(1,2,3)", "p20 == point(4,5,6)", "p20 == point(7,8,9)",
        "p20 == point(1,2,3)", "p20 == point(4,5,6)", "p20 == point(7,8,9)", "p21 == point(2,4,6)", "p21 == point(3,5,7)",
        "p21 == point(4,6,8)", "p21 == point(5,7,9)", "p21 == point(4,5,6)", "p21 == point(3,4,5)", "p21 == point(2,3,4)",
        "p21 == point(1,2,3)", "p21 == point(2,4,6)", "p21 == point(1,2,3)", "p21 == point(2,4,6)", "p21 == point(1,2,3)",
        "p21 == point(2,4,6)", "p21 == point(1,2,3)", "p22(0) == 1 && p22(1) == 2 && p22(2) == 3",
        "p22[0] == 1 && p22[1] == 2 && p22[2] == 3", "p22 == point(4,5,6)", "p22 == point(7,8,9)", "p30 == point(1,2,3)",
        "p30.x() == 4 && p30.y() == 5 && p30.z() == 6", "p40.perp2() == 400", "p40.perp() == 20", "p40.rho() == 20",
        "p40 == point(3,4,1)", "p40 == point(0,0,1)", "p50.mag2() == 49", "p50.mag() == 7", "p50.r() == 7", "p50.getR() == 7",
        "p51.phi() == 0", "EQUAL(p52.phi() ,std::atan2(2.,1.))", "EQUAL(p52.getPhi() ,std::atan2(2.,1.))", "p53.theta() == 0",
        "p53.cosTheta() == 1", "EQUAL(p54.theta() ,std::atan2(1.,2.))", "EQUAL(p54.getTheta(),std::atan2(1.,2.))",
        "EQUAL(p54.cosTheta(),std::sqrt(0.8))", "p55 == point(4,6,12)", "p55 == point(2,3,6)", "EQUAL(p56.getPhi(),CLHEP::pi/6)",
        "EQUAL(p56.mag() ,p55.mag())", "EQUAL(p56.theta() ,p55.theta())", "EQUAL(p57.cosTheta(),0.5)",
        "EQUAL(p57.mag() ,p55.mag())", "EQUAL(p57.phi() ,p55.phi())", "EQUAL(p61.pseudoRapidity(),2)", "EQUAL(p61.getEta() ,2)",
        "EQUAL(p61.eta() ,2)", "EQUAL(p61.mag() ,7)", "EQUAL(p61.phi(),p60.phi())", "p70.dot(p70) == p70.mag2()",
        "p71.cross(p72) == point(-8,-8,8)", "p73.perp2(point(0,1,0)) == 9", "p73.perp (point(1,0,0)) == 4",
        "EQUAL(p74.angle(p75),CLHEP::pi/4)", "EQUAL(p75.angle(p00),CLHEP::pi/2)", "EQUAL(p81.mag() ,1)", "EQUAL(p82.dot(p81),0)",
        "p90.x() == 2", "EQUAL(p90.y(),0)", "EQUAL(p90.z(),1)", "EQUAL(p91.x(),1)", "p91.y() == 2", "EQUAL(p91.z(),0)",
        "EQUAL(p92.x(),0)", "EQUAL(p92.y(),1)", "p92.z() == 2", "EQUAL(p93.x(),-1)", "EQUAL(p93.y(),-1)",
        "EQUAL(p93.z(),-std::sqrt(2.))", "EQUAL(p100.x(),-1)", "EQUAL(p100.y(),-1)", "EQUAL(p100.z(),-std::sqrt(2.))",
        "p120 == point(-1,-2,-3)", "p120 == point(1,2,3)", "p121 == point(2,3,4)", "p121 == point(3,4,5)", "p121 == point(4,5,6)",
        "p121 == point(3,4,5)", "p121 == point(2,3,4)", "p121 == point(1,2,3)", "p121 == point(2,4,6)", "p121 == point(1,2,3)",
        "p121 == point(2,4,6)", "p121 == point(1,2,3)", "p121 == point(2,4,6)", "p121 == point(1,2,3)", "p121 == point(2,4,6)",
        "p121 == point(1,2,3)", "p121 == point(2,4,6)", "p121 * p121 == p121.mag2()", "p121 * Point3D <float>(1,1,1) == 12",
        "p121 * Vector3D<float>(1,1,1) == 12", "p121 * Normal3D<float>(1,1,1) == 12", "p121 == Point3D <float>(2,4,6)",
        "p121 == Vector3D<float>(2,4,6)", "p121 == Normal3D<float>(2,4,6)", "p121 != Point3D <type> (3,4,6)",
        "p121 != Vector3D<type> (2,5,6)", "p121 != Normal3D<type> (2,4,7)"};

    /** One instantiation's asserts, taken in turn: all on the line of its function, CheckPointFloat()... */
    private static final class Asserts {
        private final Io io;
        private final String point;
        private final String type;
        private final int line;
        private final String function;
        private int next;

        Asserts(Io io, String point, String type, int line, String function) {
            this.io = io;
            this.point = point;
            this.type = type;
            this.line = line;
            this.function = function;
        }

        void ok(boolean cond) {
            final String text = TEXTS[next++].replaceAll("\\bpoint\\b", point).replaceAll("\\btype\\b", type);
            check(io, cond, text, line, function);
        }

        void done() {
            if (next != TEXTS.length) throw new IllegalStateException(next + " of the " + TEXTS.length + " asserts made");
        }
    }

    private static boolean EQUAL(double a, double b) {
        double del = a - b;
        if (del < 0) del = -del;
        return del < 0.000001;
    }

    /* ---- the vectors of doubles ---- */

    private interface MakeD {
        BasicVector3D make(double x, double y, double z);
    }

    /** Point3D, Vector3D or Normal3D of doubles: its constructors and its transform. */
    private record KindD(String point, MakeD make, Function<float[], BasicVector3D> fromF, Function<double[], BasicVector3D> fromD,
                         Function<BasicVector3DF, BasicVector3D> convF, Function<BasicVector3D, BasicVector3D> convD,
                         BiConsumer<BasicVector3D, Transform3D> transform) {
    }

    private static final KindD POINT_D = new KindD("Point3D<double>", Point3D::new, Point3D::new, Point3D::new, Point3D::new,
        Point3D::new, (p, m) -> ((Point3D) p).transform(m));
    private static final KindD VECTOR_D = new KindD("Vector3D<double>", Vector3D::new, Vector3D::new, Vector3D::new, Vector3D::new,
        Vector3D::new, (p, m) -> ((Vector3D) p).transform(m));
    private static final KindD NORMAL_D = new KindD("Normal3D<double>", Normal3D::new, Normal3D::new, Normal3D::new, Normal3D::new,
        Normal3D::new, (p, m) -> ((Normal3D) p).transform(m));

    /** A vector of floats as one of doubles (BasicVector3D&lt;double&gt;'s constructor from floats). */
    private static BasicVector3D d(BasicVector3DF v) {
        return new BasicVector3D(v);
    }

    private static void checkDouble(Io io, KindD K, int line, String function) {
        final Asserts c = new Asserts(io, K.point(), "double", line, function);
        final MakeD point = K.make();
        /* Check default constructor */
        final BasicVector3D p00 = point.make(0, 0, 0);
        c.ok(p00.x() == 0 && p00.y() == 0 && p00.z() == 0);
        /* Check constructor from three numbers */
        final BasicVector3D p01 = point.make(1, 2, 3);
        final BasicVector3D p02 = point.make(4., 5., 6.);
        c.ok(p01.x() == 1 && p01.y() == 2 && p01.z() == 3);
        c.ok(p02.x() == 4 && p02.y() == 5 && p02.z() == 6);
        /* Check constructor from array */
        final float[] farray = {1, 2, 3};
        final double[] darray = {4, 5, 6};
        final BasicVector3D p03 = K.fromF().apply(farray);
        c.ok(p03.isEqual(point.make(1, 2, 3)));
        final BasicVector3D p04 = K.fromD().apply(darray);
        c.ok(p04.isEqual(point.make(4, 5, 6)));
        /* Check conversion to array */
        final BasicVector3D p05 = point.make(1, 2, 3);
        double[] a = p05.data();
        c.ok(a[0] == 1 && a[1] == 2 && a[2] == 3);
        final BasicVector3D p06 = point.make(4, 5, 6);
        a = p06.data();
        c.ok(a[0] == 4 && a[1] == 5 && a[2] == 6);
        final double[] b = p06.data();
        b[0] = 7;
        b[1] = 8;
        b[2] = 9;
        c.ok(p06.isEqual(point.make(7, 8, 9)));
        /* Check copy constructor */
        c.ok(K.convD().apply(p01).isEqual(point.make(1, 2, 3)));
        c.ok(K.convF().apply(new Point3DF(1, 2, 3)).isEqual(point.make(1, 2, 3)));
        c.ok(K.convF().apply(new Vector3DF(4, 5, 6)).isEqual(point.make(4, 5, 6)));
        c.ok(K.convF().apply(new Normal3DF(7, 8, 9)).isEqual(point.make(7, 8, 9)));
        c.ok(K.convD().apply(new Point3D(1, 2, 3)).isEqual(point.make(1, 2, 3)));
        c.ok(K.convD().apply(new Vector3D(4, 5, 6)).isEqual(point.make(4, 5, 6)));
        c.ok(K.convD().apply(new Normal3D(7, 8, 9)).isEqual(point.make(7, 8, 9)));
        /* Check assignment */
        final BasicVector3D p20 = point.make(0, 0, 0);
        p20.assign(d(new Point3DF(1, 2, 3)));
        c.ok(p20.isEqual(point.make(1, 2, 3)));
        p20.assign(d(new Vector3DF(4, 5, 6)));
        c.ok(p20.isEqual(point.make(4, 5, 6)));
        p20.assign(d(new Normal3DF(7, 8, 9)));
        c.ok(p20.isEqual(point.make(7, 8, 9)));
        p20.assign(new Point3D(1, 2, 3));
        c.ok(p20.isEqual(point.make(1, 2, 3)));
        p20.assign(new Vector3D(4, 5, 6));
        c.ok(p20.isEqual(point.make(4, 5, 6)));
        p20.assign(new Normal3D(7, 8, 9));
        c.ok(p20.isEqual(point.make(7, 8, 9)));
        /* Check arithmetic operations */
        final BasicVector3D p21 = point.make(1, 2, 3);
        p21.plusAssign(point.make(1, 2, 3));
        c.ok(p21.isEqual(point.make(2, 4, 6)));
        p21.plusAssign(d(new Point3DF(1, 1, 1)));
        c.ok(p21.isEqual(point.make(3, 5, 7)));
        p21.plusAssign(d(new Vector3DF(1, 1, 1)));
        c.ok(p21.isEqual(point.make(4, 6, 8)));
        p21.plusAssign(d(new Normal3DF(1, 1, 1)));
        c.ok(p21.isEqual(point.make(5, 7, 9)));
        p21.minusAssign(point.make(1, 2, 3));
        c.ok(p21.isEqual(point.make(4, 5, 6)));
        p21.minusAssign(new Point3D(1, 1, 1));
        c.ok(p21.isEqual(point.make(3, 4, 5)));
        p21.minusAssign(new Vector3D(1, 1, 1));
        c.ok(p21.isEqual(point.make(2, 3, 4)));
        p21.minusAssign(new Normal3D(1, 1, 1));
        c.ok(p21.isEqual(point.make(1, 2, 3)));
        p21.timesAssign(2);
        c.ok(p21.isEqual(point.make(2, 4, 6)));
        p21.divAssign(2);
        c.ok(p21.isEqual(point.make(1, 2, 3)));
        p21.timesAssign(2.0f);
        c.ok(p21.isEqual(point.make(2, 4, 6)));
        p21.divAssign(2.0f);
        c.ok(p21.isEqual(point.make(1, 2, 3)));
        p21.timesAssign(2.0);
        c.ok(p21.isEqual(point.make(2, 4, 6)));
        p21.divAssign(2.0);
        c.ok(p21.isEqual(point.make(1, 2, 3)));
        /* Check subscripting: p22(i) and p22[i] alike */
        final BasicVector3D p22 = point.make(1, 2, 3);
        c.ok(p22.get(0) == 1 && p22.get(1) == 2 && p22.get(2) == 3);
        c.ok(p22.get(0) == 1 && p22.get(1) == 2 && p22.get(2) == 3);
        p22.set(0, 4);
        p22.set(1, 5);
        p22.set(2, 6);
        c.ok(p22.isEqual(point.make(4, 5, 6)));
        p22.set(0, 7);
        p22.set(1, 8);
        p22.set(2, 9);
        c.ok(p22.isEqual(point.make(7, 8, 9)));
        /* Check carthesian coordinate system */
        final BasicVector3D p30 = point.make(0, 0, 0);
        p30.setX(1);
        p30.setY(2);
        p30.setZ(3);
        c.ok(p30.isEqual(point.make(1, 2, 3)));
        p30.set(4, 5, 6);
        c.ok(p30.x() == 4 && p30.y() == 5 && p30.z() == 6);
        /* Check cylindrical coordinate system */
        final BasicVector3D p40 = point.make(12, 16, 1);
        c.ok(p40.perp2() == 400);
        c.ok(p40.perp() == 20);
        c.ok(p40.rho() == 20);
        p40.setPerp(5);
        c.ok(p40.isEqual(point.make(3, 4, 1)));
        p40.set(0, 0, 1);
        p40.setPerp(5);
        c.ok(p40.isEqual(point.make(0, 0, 1)));
        /* Check spherical coordinate system */
        final BasicVector3D p50 = point.make(2, 3, 6);
        c.ok(p50.mag2() == 49);
        c.ok(p50.mag() == 7);
        c.ok(p50.r() == 7);
        c.ok(p50.getR() == 7);
        final BasicVector3D p51 = point.make(0, 0, 1);
        c.ok(p51.phi() == 0);
        final BasicVector3D p52 = point.make(2, 4, 5);
        c.ok(EQUAL(p52.phi(), CMath.atan2(2., 1.)));
        c.ok(EQUAL(p52.getPhi(), CMath.atan2(2., 1.)));
        final BasicVector3D p53 = point.make(0, 0, 0);
        c.ok(p53.theta() == 0);
        c.ok(p53.cosTheta() == 1);
        final BasicVector3D p54 = point.make(3, 4, 10);
        c.ok(EQUAL(p54.theta(), CMath.atan2(1., 2.)));
        c.ok(EQUAL(p54.getTheta(), CMath.atan2(1., 2.)));
        c.ok(EQUAL(p54.cosTheta(), Math.sqrt(0.8)));
        final BasicVector3D p55 = point.make(2, 3, 6);
        p55.setMag(14);
        c.ok(p55.isEqual(point.make(4, 6, 12)));
        p55.setR(7);
        c.ok(p55.isEqual(point.make(2, 3, 6)));
        final BasicVector3D p56 = K.convD().apply(p55);
        p56.setPhi(pi / 6);
        c.ok(EQUAL(p56.getPhi(), pi / 6));
        c.ok(EQUAL(p56.mag(), p55.mag()));
        c.ok(EQUAL(p56.theta(), p55.theta()));
        final BasicVector3D p57 = K.convD().apply(p55);
        p57.setTheta(pi / 3);
        c.ok(EQUAL(p57.cosTheta(), 0.5));
        c.ok(EQUAL(p57.mag(), p55.mag()));
        c.ok(EQUAL(p57.phi(), p55.phi()));
        /* Check pseudo-rapidity */
        final BasicVector3D p60 = point.make(2, 3, 6);
        final BasicVector3D p61 = K.convD().apply(p60);
        p61.setEta(2);
        c.ok(EQUAL(p61.pseudoRapidity(), 2));
        c.ok(EQUAL(p61.getEta(), 2));
        c.ok(EQUAL(p61.eta(), 2));
        c.ok(EQUAL(p61.mag(), 7));
        c.ok(EQUAL(p61.phi(), p60.phi()));
        /* Check combination of two vectors */
        final BasicVector3D p70 = point.make(1, 2, 3);
        c.ok(p70.dot(p70) == p70.mag2());
        final BasicVector3D p71 = point.make(1, 2, 3);
        final BasicVector3D p72 = point.make(-3, 2, -1);
        c.ok(p71.cross(p72).isEqual(point.make(-8, -8, 8)));
        final BasicVector3D p73 = point.make(3, 4, 0);
        c.ok(p73.perp2(point.make(0, 1, 0)) == 9);
        c.ok(p73.perp(point.make(1, 0, 0)) == 4);
        final BasicVector3D p74 = point.make(1, 0, 0);
        final BasicVector3D p75 = point.make(1, 1, 0);
        c.ok(EQUAL(p74.angle(p75), pi / 4));
        c.ok(EQUAL(p75.angle(p00), pi / 2));
        /* Check related vectors */
        final BasicVector3D p80 = point.make(1, 2, 3);
        final BasicVector3D p81 = K.convD().apply(p80.unit());
        c.ok(EQUAL(p81.mag(), 1));
        final BasicVector3D p82 = K.convD().apply(p80.orthogonal());
        c.ok(EQUAL(p82.dot(p81), 0));
        /* Check rotations */
        final BasicVector3D p90 = point.make(2, 0.5, Math.sqrt(3.) / 2);
        p90.rotateX(pi / 6);
        c.ok(p90.x() == 2);
        c.ok(EQUAL(p90.y(), 0));
        c.ok(EQUAL(p90.z(), 1));
        final BasicVector3D p91 = point.make(Math.sqrt(3.) / 2, 2, 0.5);
        p91.rotateY(pi / 6);
        c.ok(EQUAL(p91.x(), 1));
        c.ok(p91.y() == 2);
        c.ok(EQUAL(p91.z(), 0));
        final BasicVector3D p92 = point.make(0.5, Math.sqrt(3.) / 2, 2);
        p92.rotateZ(pi / 6);
        c.ok(EQUAL(p92.x(), 0));
        c.ok(EQUAL(p92.y(), 1));
        c.ok(p92.z() == 2);
        final BasicVector3D p93 = point.make(1, 1, Math.sqrt(2.));
        p93.rotate(pi, d(new Vector3DF(-1, -1, (float) Math.sqrt(2.))));
        c.ok(EQUAL(p93.x(), -1));
        c.ok(EQUAL(p93.y(), -1));
        c.ok(EQUAL(p93.z(), -Math.sqrt(2.)));
        /* Check transformations */
        final BasicVector3D p100 = point.make(1, 1, Math.sqrt(2.));
        final Transform3D m = new Transform3D();
        m.assign(new Rotate3D(pi, new Vector3D(new Vector3DF(-1, -1, (float) Math.sqrt(2.)))));
        K.transform().accept(p100, m);
        c.ok(EQUAL(p100.x(), -1));
        c.ok(EQUAL(p100.y(), -1));
        c.ok(EQUAL(p100.z(), -Math.sqrt(2.)));
        /* Check input/output */
        final BasicVector3D p110 = point.make(0, 0, 0);
        BasicVector3D.read(io.cin(), p110);
        say(io.cout, p110, "\n");
        /* Check non-member arithmetics */
        final BasicVector3D p120 = point.make(-1, -2, -3);
        p120.assign(p120.plus());
        c.ok(p120.isEqual(point.make(-1, -2, -3)));
        p120.assign(p120.negate());
        c.ok(p120.isEqual(point.make(1, 2, 3)));
        final BasicVector3D p121 = point.make(1, 2, 3);
        p121.assign(p121.plus(d(new Point3DF(1, 1, 1))));
        c.ok(p121.isEqual(point.make(2, 3, 4)));
        p121.assign(p121.plus(d(new Vector3DF(1, 1, 1))));
        c.ok(p121.isEqual(point.make(3, 4, 5)));
        p121.assign(p121.plus(d(new Normal3DF(1, 1, 1))));
        c.ok(p121.isEqual(point.make(4, 5, 6)));
        p121.assign(p121.minus(new Point3D(1, 1, 1)));
        c.ok(p121.isEqual(point.make(3, 4, 5)));
        p121.assign(p121.minus(new Vector3D(1, 1, 1)));
        c.ok(p121.isEqual(point.make(2, 3, 4)));
        p121.assign(p121.minus(new Normal3D(1, 1, 1)));
        c.ok(p121.isEqual(point.make(1, 2, 3)));
        p121.assign(p121.times(2));
        c.ok(p121.isEqual(point.make(2, 4, 6)));
        p121.assign(p121.div(2));
        c.ok(p121.isEqual(point.make(1, 2, 3)));
        p121.assign(p121.times(2.0f));
        c.ok(p121.isEqual(point.make(2, 4, 6)));
        p121.assign(p121.div(2.0f));
        c.ok(p121.isEqual(point.make(1, 2, 3)));
        p121.assign(p121.times(2.0));
        c.ok(p121.isEqual(point.make(2, 4, 6)));
        p121.assign(p121.div(2.0));
        c.ok(p121.isEqual(point.make(1, 2, 3)));
        p121.assign(BasicVector3D.times(2, p121));
        c.ok(p121.isEqual(point.make(2, 4, 6)));
        p121.assign(BasicVector3D.times(0.5f, p121));
        c.ok(p121.isEqual(point.make(1, 2, 3)));
        p121.assign(BasicVector3D.times(2.0, p121));
        c.ok(p121.isEqual(point.make(2, 4, 6)));
        c.ok(p121.times(p121) == p121.mag2());
        c.ok(p121.times(d(new Point3DF(1, 1, 1))) == 12);
        c.ok(p121.times(d(new Vector3DF(1, 1, 1))) == 12);
        c.ok(p121.times(d(new Normal3DF(1, 1, 1))) == 12);
        c.ok(p121.isEqual(d(new Point3DF(2, 4, 6))));
        c.ok(p121.isEqual(d(new Vector3DF(2, 4, 6))));
        c.ok(p121.isEqual(d(new Normal3DF(2, 4, 6))));
        c.ok(p121.notEqual(new Point3D(3, 4, 6)));
        c.ok(p121.notEqual(new Vector3D(2, 5, 6)));
        c.ok(p121.notEqual(new Normal3D(2, 4, 7)));
        c.done();
    }

    /* ---- the vectors of floats ---- */

    private interface MakeF {
        BasicVector3DF make(float x, float y, float z);
    }

    /** Point3D, Vector3D or Normal3D of floats: its constructors and its transform. */
    private record KindF(String point, MakeF make, Function<float[], BasicVector3DF> fromF,
                         Function<BasicVector3DF, BasicVector3DF> conv, BiConsumer<BasicVector3DF, Transform3D> transform) {
    }

    private static final KindF POINT_F = new KindF("Point3D<float>", Point3DF::new, Point3DF::new, Point3DF::new,
        (p, m) -> ((Point3DF) p).transform(m));
    private static final KindF VECTOR_F = new KindF("Vector3D<float>", Vector3DF::new, Vector3DF::new, Vector3DF::new,
        (p, m) -> ((Vector3DF) p).transform(m));
    private static final KindF NORMAL_F = new KindF("Normal3D<float>", Normal3DF::new, Normal3DF::new, Normal3DF::new,
        (p, m) -> ((Normal3DF) p).transform(m));

    private static void checkFloat(Io io, KindF K, int line, String function) {
        final Asserts c = new Asserts(io, K.point(), "float", line, function);
        final MakeF point = K.make();
        /* Check default constructor */
        final BasicVector3DF p00 = point.make(0, 0, 0);
        c.ok(p00.x() == 0 && p00.y() == 0 && p00.z() == 0);
        /* Check constructor from three numbers */
        final BasicVector3DF p01 = point.make(1, 2, 3);
        final BasicVector3DF p02 = point.make((float) 4., (float) 5., (float) 6.);
        c.ok(p01.x() == 1 && p01.y() == 2 && p01.z() == 3);
        c.ok(p02.x() == 4 && p02.y() == 5 && p02.z() == 6);
        /* Check constructor from array */
        final float[] farray = {1, 2, 3};
        final float[] darray = {4, 5, 6};
        final BasicVector3DF p03 = K.fromF().apply(farray);
        c.ok(p03.isEqual(point.make(1, 2, 3)));
        final BasicVector3DF p04 = K.fromF().apply(darray);
        c.ok(p04.isEqual(point.make(4, 5, 6)));
        /* Check conversion to array */
        final BasicVector3DF p05 = point.make(1, 2, 3);
        float[] a = p05.data();
        c.ok(a[0] == 1 && a[1] == 2 && a[2] == 3);
        final BasicVector3DF p06 = point.make(4, 5, 6);
        a = p06.data();
        c.ok(a[0] == 4 && a[1] == 5 && a[2] == 6);
        final float[] b = p06.data();
        b[0] = 7;
        b[1] = 8;
        b[2] = 9;
        c.ok(p06.isEqual(point.make(7, 8, 9)));
        /* Check copy constructor */
        c.ok(K.conv().apply(p01).isEqual(point.make(1, 2, 3)));
        c.ok(K.conv().apply(new Point3DF(1, 2, 3)).isEqual(point.make(1, 2, 3)));
        c.ok(K.conv().apply(new Vector3DF(4, 5, 6)).isEqual(point.make(4, 5, 6)));
        c.ok(K.conv().apply(new Normal3DF(7, 8, 9)).isEqual(point.make(7, 8, 9)));
        c.ok(K.conv().apply(new Point3DF(1, 2, 3)).isEqual(point.make(1, 2, 3)));
        c.ok(K.conv().apply(new Vector3DF(4, 5, 6)).isEqual(point.make(4, 5, 6)));
        c.ok(K.conv().apply(new Normal3DF(7, 8, 9)).isEqual(point.make(7, 8, 9)));
        /* Check assignment */
        final BasicVector3DF p20 = point.make(0, 0, 0);
        for (int pass = 0; pass < 2; pass++) {
            p20.assign(new Point3DF(1, 2, 3));
            c.ok(p20.isEqual(point.make(1, 2, 3)));
            p20.assign(new Vector3DF(4, 5, 6));
            c.ok(p20.isEqual(point.make(4, 5, 6)));
            p20.assign(new Normal3DF(7, 8, 9));
            c.ok(p20.isEqual(point.make(7, 8, 9)));
        }
        /* Check arithmetic operations */
        final BasicVector3DF p21 = point.make(1, 2, 3);
        p21.plusAssign(point.make(1, 2, 3));
        c.ok(p21.isEqual(point.make(2, 4, 6)));
        p21.plusAssign(new Point3DF(1, 1, 1));
        c.ok(p21.isEqual(point.make(3, 5, 7)));
        p21.plusAssign(new Vector3DF(1, 1, 1));
        c.ok(p21.isEqual(point.make(4, 6, 8)));
        p21.plusAssign(new Normal3DF(1, 1, 1));
        c.ok(p21.isEqual(point.make(5, 7, 9)));
        p21.minusAssign(point.make(1, 2, 3));
        c.ok(p21.isEqual(point.make(4, 5, 6)));
        p21.minusAssign(new Point3DF(1, 1, 1));
        c.ok(p21.isEqual(point.make(3, 4, 5)));
        p21.minusAssign(new Vector3DF(1, 1, 1));
        c.ok(p21.isEqual(point.make(2, 3, 4)));
        p21.minusAssign(new Normal3DF(1, 1, 1));
        c.ok(p21.isEqual(point.make(1, 2, 3)));
        p21.timesAssign(2);
        c.ok(p21.isEqual(point.make(2, 4, 6)));
        p21.divAssign(2);
        c.ok(p21.isEqual(point.make(1, 2, 3)));
        p21.timesAssign(2.0f);
        c.ok(p21.isEqual(point.make(2, 4, 6)));
        p21.divAssign(2.0f);
        c.ok(p21.isEqual(point.make(1, 2, 3)));
        p21.timesAssign(2.0);
        c.ok(p21.isEqual(point.make(2, 4, 6)));
        p21.divAssign(2.0);
        c.ok(p21.isEqual(point.make(1, 2, 3)));
        /* Check subscripting: p22(i) and p22[i] alike */
        final BasicVector3DF p22 = point.make(1, 2, 3);
        c.ok(p22.get(0) == 1 && p22.get(1) == 2 && p22.get(2) == 3);
        c.ok(p22.get(0) == 1 && p22.get(1) == 2 && p22.get(2) == 3);
        p22.set(0, 4);
        p22.set(1, 5);
        p22.set(2, 6);
        c.ok(p22.isEqual(point.make(4, 5, 6)));
        p22.set(0, 7);
        p22.set(1, 8);
        p22.set(2, 9);
        c.ok(p22.isEqual(point.make(7, 8, 9)));
        /* Check carthesian coordinate system */
        final BasicVector3DF p30 = point.make(0, 0, 0);
        p30.setX(1);
        p30.setY(2);
        p30.setZ(3);
        c.ok(p30.isEqual(point.make(1, 2, 3)));
        p30.set(4, 5, 6);
        c.ok(p30.x() == 4 && p30.y() == 5 && p30.z() == 6);
        /* Check cylindrical coordinate system */
        final BasicVector3DF p40 = point.make(12, 16, 1);
        c.ok(p40.perp2() == 400);
        c.ok(p40.perp() == 20);
        c.ok(p40.rho() == 20);
        p40.setPerp(5);
        c.ok(p40.isEqual(point.make(3, 4, 1)));
        p40.set(0, 0, 1);
        p40.setPerp(5);
        c.ok(p40.isEqual(point.make(0, 0, 1)));
        /* Check spherical coordinate system */
        final BasicVector3DF p50 = point.make(2, 3, 6);
        c.ok(p50.mag2() == 49);
        c.ok(p50.mag() == 7);
        c.ok(p50.r() == 7);
        c.ok(p50.getR() == 7);
        final BasicVector3DF p51 = point.make(0, 0, 1);
        c.ok(p51.phi() == 0);
        final BasicVector3DF p52 = point.make(2, 4, 5);
        c.ok(EQUAL(p52.phi(), CMath.atan2(2., 1.)));
        c.ok(EQUAL(p52.getPhi(), CMath.atan2(2., 1.)));
        final BasicVector3DF p53 = point.make(0, 0, 0);
        c.ok(p53.theta() == 0);
        c.ok(p53.cosTheta() == 1);
        final BasicVector3DF p54 = point.make(3, 4, 10);
        c.ok(EQUAL(p54.theta(), CMath.atan2(1., 2.)));
        c.ok(EQUAL(p54.getTheta(), CMath.atan2(1., 2.)));
        c.ok(EQUAL(p54.cosTheta(), Math.sqrt(0.8)));
        final BasicVector3DF p55 = point.make(2, 3, 6);
        p55.setMag(14);
        c.ok(p55.isEqual(point.make(4, 6, 12)));
        p55.setR(7);
        c.ok(p55.isEqual(point.make(2, 3, 6)));
        final BasicVector3DF p56 = K.conv().apply(p55);
        p56.setPhi((float) (pi / 6));
        c.ok(EQUAL(p56.getPhi(), pi / 6));
        c.ok(EQUAL(p56.mag(), p55.mag()));
        c.ok(EQUAL(p56.theta(), p55.theta()));
        final BasicVector3DF p57 = K.conv().apply(p55);
        p57.setTheta((float) (pi / 3));
        c.ok(EQUAL(p57.cosTheta(), 0.5));
        c.ok(EQUAL(p57.mag(), p55.mag()));
        c.ok(EQUAL(p57.phi(), p55.phi()));
        /* Check pseudo-rapidity */
        final BasicVector3DF p60 = point.make(2, 3, 6);
        final BasicVector3DF p61 = K.conv().apply(p60);
        p61.setEta(2);
        c.ok(EQUAL(p61.pseudoRapidity(), 2));
        c.ok(EQUAL(p61.getEta(), 2));
        c.ok(EQUAL(p61.eta(), 2));
        c.ok(EQUAL(p61.mag(), 7));
        c.ok(EQUAL(p61.phi(), p60.phi()));
        /* Check combination of two vectors */
        final BasicVector3DF p70 = point.make(1, 2, 3);
        c.ok(p70.dot(p70) == p70.mag2());
        final BasicVector3DF p71 = point.make(1, 2, 3);
        final BasicVector3DF p72 = point.make(-3, 2, -1);
        c.ok(p71.cross(p72).isEqual(point.make(-8, -8, 8)));
        final BasicVector3DF p73 = point.make(3, 4, 0);
        c.ok(p73.perp2(point.make(0, 1, 0)) == 9);
        c.ok(p73.perp(point.make(1, 0, 0)) == 4);
        final BasicVector3DF p74 = point.make(1, 0, 0);
        final BasicVector3DF p75 = point.make(1, 1, 0);
        c.ok(EQUAL(p74.angle(p75), pi / 4));
        c.ok(EQUAL(p75.angle(p00), pi / 2));
        /* Check related vectors */
        final BasicVector3DF p80 = point.make(1, 2, 3);
        final BasicVector3DF p81 = K.conv().apply(p80.unit());
        c.ok(EQUAL(p81.mag(), 1));
        final BasicVector3DF p82 = K.conv().apply(p80.orthogonal());
        c.ok(EQUAL(p82.dot(p81), 0));
        /* Check rotations */
        final BasicVector3DF p90 = point.make(2, 0.5f, (float) (Math.sqrt(3.) / 2));
        p90.rotateX((float) (pi / 6));
        c.ok(p90.x() == 2);
        c.ok(EQUAL(p90.y(), 0));
        c.ok(EQUAL(p90.z(), 1));
        final BasicVector3DF p91 = point.make((float) (Math.sqrt(3.) / 2), 2, 0.5f);
        p91.rotateY((float) (pi / 6));
        c.ok(EQUAL(p91.x(), 1));
        c.ok(p91.y() == 2);
        c.ok(EQUAL(p91.z(), 0));
        final BasicVector3DF p92 = point.make(0.5f, (float) (Math.sqrt(3.) / 2), 2);
        p92.rotateZ((float) (pi / 6));
        c.ok(EQUAL(p92.x(), 0));
        c.ok(EQUAL(p92.y(), 1));
        c.ok(p92.z() == 2);
        final BasicVector3DF p93 = point.make(1, 1, (float) Math.sqrt(2.));
        p93.rotate((float) pi, new Vector3DF(-1, -1, (float) Math.sqrt(2.)));
        c.ok(EQUAL(p93.x(), -1));
        c.ok(EQUAL(p93.y(), -1));
        c.ok(EQUAL(p93.z(), -Math.sqrt(2.)));
        /* Check transformations */
        final BasicVector3DF p100 = point.make(1, 1, (float) Math.sqrt(2.));
        final Transform3D m = new Transform3D();
        m.assign(new Rotate3D(pi, new Vector3D(new Vector3DF(-1, -1, (float) Math.sqrt(2.)))));
        K.transform().accept(p100, m);
        c.ok(EQUAL(p100.x(), -1));
        c.ok(EQUAL(p100.y(), -1));
        c.ok(EQUAL(p100.z(), -Math.sqrt(2.)));
        /* Check input/output */
        final BasicVector3DF p110 = point.make(0, 0, 0);
        BasicVector3DF.read(io.cin(), p110);
        say(io.cout, p110, "\n");
        /* Check non-member arithmetics */
        final BasicVector3DF p120 = point.make(-1, -2, -3);
        p120.assign(p120.plus());
        c.ok(p120.isEqual(point.make(-1, -2, -3)));
        p120.assign(p120.negate());
        c.ok(p120.isEqual(point.make(1, 2, 3)));
        final BasicVector3DF p121 = point.make(1, 2, 3);
        p121.assign(p121.plus(new Point3DF(1, 1, 1)));
        c.ok(p121.isEqual(point.make(2, 3, 4)));
        p121.assign(p121.plus(new Vector3DF(1, 1, 1)));
        c.ok(p121.isEqual(point.make(3, 4, 5)));
        p121.assign(p121.plus(new Normal3DF(1, 1, 1)));
        c.ok(p121.isEqual(point.make(4, 5, 6)));
        p121.assign(p121.minus(new Point3DF(1, 1, 1)));
        c.ok(p121.isEqual(point.make(3, 4, 5)));
        p121.assign(p121.minus(new Vector3DF(1, 1, 1)));
        c.ok(p121.isEqual(point.make(2, 3, 4)));
        p121.assign(p121.minus(new Normal3DF(1, 1, 1)));
        c.ok(p121.isEqual(point.make(1, 2, 3)));
        p121.assign(p121.times(2));
        c.ok(p121.isEqual(point.make(2, 4, 6)));
        p121.assign(p121.div(2));
        c.ok(p121.isEqual(point.make(1, 2, 3)));
        p121.assign(p121.times(2.0f));
        c.ok(p121.isEqual(point.make(2, 4, 6)));
        p121.assign(p121.div(2.0f));
        c.ok(p121.isEqual(point.make(1, 2, 3)));
        p121.assign(p121.times(2.0));
        c.ok(p121.isEqual(point.make(2, 4, 6)));
        p121.assign(p121.div(2.0));
        c.ok(p121.isEqual(point.make(1, 2, 3)));
        p121.assign(BasicVector3DF.times(2, p121));
        c.ok(p121.isEqual(point.make(2, 4, 6)));
        p121.assign(BasicVector3DF.times(0.5f, p121));
        c.ok(p121.isEqual(point.make(1, 2, 3)));
        p121.assign(BasicVector3DF.times(2.0, p121));
        c.ok(p121.isEqual(point.make(2, 4, 6)));
        c.ok(p121.times(p121) == p121.mag2());
        c.ok(p121.times(new Point3DF(1, 1, 1)) == 12);
        c.ok(p121.times(new Vector3DF(1, 1, 1)) == 12);
        c.ok(p121.times(new Normal3DF(1, 1, 1)) == 12);
        c.ok(p121.isEqual(new Point3DF(2, 4, 6)));
        c.ok(p121.isEqual(new Vector3DF(2, 4, 6)));
        c.ok(p121.isEqual(new Normal3DF(2, 4, 6)));
        c.ok(p121.notEqual(new Point3DF(3, 4, 6)));
        c.ok(p121.notEqual(new Vector3DF(2, 5, 6)));
        c.ok(p121.notEqual(new Normal3DF(2, 4, 7)));
        c.done();
    }

    /** The six instantiations, each on the line of its function (228 to 233), reading one vector each. */
    static int testBasicVector3D(Io io) {
        checkFloat(io, POINT_F, 228, "void CheckPointFloat()");
        checkFloat(io, VECTOR_F, 229, "void CheckVectorFloat()");
        checkFloat(io, NORMAL_F, 230, "void CheckNormalFloat()");
        checkDouble(io, POINT_D, 231, "void CheckPointDouble()");
        checkDouble(io, VECTOR_D, 232, "void CheckVectorDouble()");
        checkDouble(io, NORMAL_D, 233, "void CheckNormalDouble()");
        return 0;
    }
}
