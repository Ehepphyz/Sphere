package com.sphere.core.clhep.fields;

import com.sphere.core.clhep.genfun.AbsFunction;
import com.sphere.core.clhep.genfun.Argument;
import com.sphere.core.clhep.geometry.Rotate3D;
import com.sphere.core.clhep.geometry.Transform3D;
import com.sphere.core.clhep.geometry.Translate3D;
import com.sphere.core.clhep.geometry.Vector3D;
import com.sphere.core.clhep.vector.Hep3Vector;
import com.sphere.core.clhep.vector.HepAxisAngle;
import com.sphere.core.clhep.vector.HepRotation;
import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's Fields package, namespace XF (Fields/XF.h, XF.cc): functions whose values are transformations,
 * Transform3D-valued functions of one or several variables. {@link Pow} raises a transformation to a power
 * that is a function (its rotation angle and translation scaled), and functions compose by products, by a
 * fixed transformation before or after.
 */
public final class XF {

    private XF() {
    }

    /** XF::Function: a transformation for each value of its argument. */
    public abstract static class Function {

        public int dimensionality() {
            return 1;
        }

        public abstract Transform3D call(double argument);

        public abstract Transform3D call(Argument argument);

        public abstract Function copy();

        /** operator*: the product of the two transformations, this one first. */
        public Product times(Function b) {
            return new Product(this, b);
        }

        /** operator*(Function, Transform3D): this transformation times xf. */
        public PostMult times(Transform3D xf) {
            return new PostMult(this, xf);
        }
    }

    /** operator*(Transform3D, Function): xf times the function's transformation. */
    public static PreMult times(Transform3D xf, Function b) {
        return new PreMult(xf, b);
    }

    /** A transformation to the power of a function: the angle of its rotation and its translation times f(x). */
    public static class Pow extends Function {

        private final Transform3D xf;
        private final AbsFunction function;

        public Pow(Transform3D xform, AbsFunction f) {
            xf = new Transform3D(xform);
            function = f.copy();
        }

        public Pow(Pow right) {
            xf = new Transform3D(right.xf);
            function = right.function.copy();
        }

        @Override
        public Transform3D call(double x) {
            final HepRotation rotate = xf.getRotation();
            final Hep3Vector translate = xf.getTranslation();
            final HepAxisAngle aa = rotate.axisAngle();
            final double nTimes = function.call(x);
            translate.timesAssign(nTimes);
            aa.setDelta(aa.delta() * nTimes);
            return new Translate3D(translate).times(new Rotate3D(aa.delta(), new Vector3D(aa.axis())));
        }

        @Override
        public Transform3D call(Argument argument) {
            return call(argument.get(0));
        }

        @Override
        public Pow copy() {
            return new Pow(this);
        }
    }

    /** The product of two transformation functions. */
    public static class Product extends Function {

        private final Function _arg1;
        private final Function _arg2;

        public Product(Function arg1, Function arg2) {
            _arg1 = arg1.copy();
            _arg2 = arg2.copy();
            if (arg1.dimensionality() != arg2.dimensionality()) {
                StdStreams.cout().print("Warning:  dimension mismatch in XF::Product\n");
            }
        }

        public Product(Product right) {
            _arg1 = right._arg1.copy();
            _arg2 = right._arg2.copy();
        }

        @Override
        public Product copy() {
            return new Product(this);
        }

        @Override
        public int dimensionality() {
            return _arg1.dimensionality();
        }

        @Override
        public Transform3D call(double x) {
            return _arg1.call(x).times(_arg2.call(x));
        }

        @Override
        public Transform3D call(Argument x) {
            return _arg1.call(x).times(_arg2.call(x));
        }
    }

    /** A fixed transformation times a function's. */
    public static class PreMult extends Function {

        private final Transform3D _arg1;
        private final Function _arg2;

        public PreMult(Transform3D arg1, Function arg2) {
            _arg1 = new Transform3D(arg1);
            _arg2 = arg2.copy();
        }

        public PreMult(PreMult right) {
            _arg1 = new Transform3D(right._arg1);
            _arg2 = right._arg2.copy();
        }

        @Override
        public PreMult copy() {
            return new PreMult(this);
        }

        @Override
        public int dimensionality() {
            return _arg2.dimensionality();
        }

        @Override
        public Transform3D call(double x) {
            return _arg1.times(_arg2.call(x));
        }

        @Override
        public Transform3D call(Argument x) {
            return _arg1.times(_arg2.call(x));
        }
    }

    /** A function's transformation times a fixed one. */
    public static class PostMult extends Function {

        private final Function _arg1;
        private final Transform3D _arg2;

        public PostMult(Function arg1, Transform3D arg2) {
            _arg1 = arg1.copy();
            _arg2 = new Transform3D(arg2);
        }

        public PostMult(PostMult right) {
            _arg1 = right._arg1.copy();
            _arg2 = new Transform3D(right._arg2);
        }

        @Override
        public PostMult copy() {
            return new PostMult(this);
        }

        @Override
        public int dimensionality() {
            return _arg1.dimensionality();
        }

        @Override
        public Transform3D call(double x) {
            return _arg1.call(x).times(_arg2);
        }

        @Override
        public Transform3D call(Argument x) {
            return _arg1.call(x).times(_arg2);
        }
    }
}
