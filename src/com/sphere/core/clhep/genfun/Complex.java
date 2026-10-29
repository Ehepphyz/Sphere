package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * std::complex&lt;double&gt; as GenericFunctions computes with it under GCC: products as (ac - bd, ad + bc),
 * products and quotients by a real number component by component, norm as re² + im², and exp as
 * exp(re)·cos(im), exp(re)·sin(im), which is what MinGW's cexp returns for finite arguments.
 */
public record Complex(double re, double im) {

    public static final Complex ZERO = new Complex(0, 0);

    /** A real number, complex(x). */
    public Complex(double re) {
        this(re, 0.0);
    }

    public Complex plus(Complex o) {
        return new Complex(re + o.re, im + o.im);
    }

    /** complex + double: the real part only. */
    public Complex plus(double d) {
        return new Complex(re + d, im);
    }

    public Complex minus(Complex o) {
        return new Complex(re - o.re, im - o.im);
    }

    public Complex times(Complex o) {
        return new Complex(re * o.re - im * o.im, re * o.im + im * o.re);
    }

    public Complex times(double d) {
        return new Complex(re * d, im * d);
    }

    public Complex div(double d) {
        return new Complex(re / d, im / d);
    }

    public Complex conj() {
        return new Complex(re, -im);
    }

    public Complex negate() {
        return new Complex(-re, -im);
    }

    /** std::norm: re² + im². */
    public double norm() {
        return re * re + im * im;
    }

    /** std::abs: the C library's cabs, hypot(re, im). */
    public double abs() {
        return CMath.hypot(re, im);
    }

    /** std::exp of a complex number. */
    public Complex exp() {
        final double ex = CMath.exp(re);
        return new Complex(ex * CMath.cos(im), ex * CMath.sin(im));
    }

    /** operator&lt;&lt;: (re,im) in the stream's format. */
    public COStream print(COStream os) {
        final COStream s = new COStream().flags(os.flags()).precision(os.precision());
        s.put("(").put(re).put(",").put(im).put(")");
        return os.put(s.str());
    }

    @Override
    public String toString() {
        return print(new COStream()).str();
    }
}
