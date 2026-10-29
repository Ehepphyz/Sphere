package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's Genfun::AnalyticConvolution (AnalyticConvolution.hh, .cc): exponential decays, alone or with
 * mixing oscillations, convolved analytically with a Gaussian resolution.
 */
public class AnalyticConvolution extends AbsFunction {

    /** The kind of convolution, CLHEP's Type. */
    public enum Type {
        MIXED, UNMIXED, SMEARED_EXP, SMEARED_COS_EXP, SMEARED_SIN_EXP, SMEARED_NEG_EXP
    }

    private final Parameter _lifetime;
    private final Parameter _frequency;
    private final Parameter _sigma;
    private final Parameter _offset;
    private final Type _type;

    public AnalyticConvolution() {
        this(Type.SMEARED_EXP);
    }

    public AnalyticConvolution(Type type) {
        _lifetime = new Parameter("Lifetime", 1.0, 0.0);
        _frequency = new Parameter("Frequency", 0.0, 0.0);
        _sigma = new Parameter("Sigma", 1.0, 0.0);
        _offset = new Parameter("Offset", 0.0);
        _type = type;
    }

    public AnalyticConvolution(AnalyticConvolution right) {
        _lifetime = new Parameter(right._lifetime);
        _frequency = new Parameter(right._frequency);
        _sigma = new Parameter(right._sigma);
        _offset = new Parameter(right._offset);
        _type = right._type;
    }

    @Override
    public AnalyticConvolution copy() {
        return new AnalyticConvolution(this);
    }

    public Parameter frequency() {
        return _frequency;
    }

    public Parameter lifetime() {
        return _lifetime;
    }

    public Parameter sigma() {
        return _sigma;
    }

    public Parameter offset() {
        return _offset;
    }

    @Override
    public double call(double argument) {
        final double sqrtTwo = Math.sqrt(2.0);
        final double xsigma = _sigma.getValue();
        final double tau = _lifetime.getValue();
        final double xoffset = _offset.getValue();
        final double x = argument - xoffset;
        final double freq = _frequency.getValue();

        double expG;
        double asymm = 0.0;

        if (_type == Type.SMEARED_NEG_EXP) {
            expG = CMath.exp((xsigma * xsigma + 2 * tau * (x)) / (2.0 * tau * tau))
                * ErrorFunctions.erfc((xsigma * xsigma + tau * (x)) / (sqrtTwo * xsigma * tau)) / (2.0 * tau);
            if (!Double.isFinite(expG)) expG = 0.0;
            return expG;
        } else {
            expG = CMath.exp((xsigma * xsigma + 2 * tau * (-x)) / (2.0 * tau * tau))
                * ErrorFunctions.erfc((xsigma * xsigma + tau * (-x)) / (sqrtTwo * xsigma * tau)) / (2.0 * tau);
        }

        if (_type == Type.SMEARED_EXP) {
            if (!Double.isFinite(expG)) expG = 0.0;
            return expG;
        }

        final boolean cosine = _type == Type.SMEARED_COS_EXP || _type == Type.MIXED || _type == Type.UNMIXED;
        if (xsigma > 6.0 * tau) {
            asymm = expG * (1 / (1 + tau * tau * freq * freq));
        } else if (xsigma == 0.0) {
            if (cosine) {
                if (x >= 0) asymm = (expG * CMath.cos(freq * x));
            } else if (_type == Type.SMEARED_SIN_EXP) {
                if (x >= 0) asymm = (expG * CMath.sin(freq * x));
            }
        } else {
            final Complex z = new Complex(freq * xsigma / sqrtTwo, (xsigma / tau - x / xsigma) / sqrtTwo);
            if (x < 0) {
                if (cosine) {
                    asymm = 2.0 * ErrorFunctions.nwwerf(z).re() / tau / 4.0
                        * CMath.exp(-x * x / 2.0 / xsigma / xsigma);
                } else if (_type == Type.SMEARED_SIN_EXP) {
                    asymm = 2.0 * ErrorFunctions.nwwerf(z).im() / tau / 4.0
                        * CMath.exp(-x * x / 2.0 / xsigma / xsigma);
                }
            } else {
                if (cosine) {
                    asymm = -2.0 * ErrorFunctions.nwwerf(z.conj()).re() / tau / 4
                        * CMath.exp(-x * x / 2.0 / xsigma / xsigma)
                        + CMath.exp(xsigma * xsigma / 2 * (1 / tau / tau - freq * freq) - x / tau) * (1. / tau)
                        * CMath.cos(freq * x - freq / tau * xsigma * xsigma);
                } else if (_type == Type.SMEARED_SIN_EXP) {
                    asymm = +2.0 * ErrorFunctions.nwwerf(z.conj()).im() / tau / 4
                        * CMath.exp(-x * x / 2.0 / xsigma / xsigma)
                        + CMath.exp(xsigma * xsigma / 2 * (1 / tau / tau - freq * freq) - x / tau) * (1. / tau)
                        * CMath.sin(freq * x - freq / tau * xsigma * xsigma);
                }
            }
        }

        if (_type == Type.UNMIXED || _type == Type.MIXED) {
            final double retVal = _type == Type.UNMIXED ? (expG + asymm) / 2.0 : (expG - asymm) / 2.0;
            if (retVal < 0) {
                StdStreams.cerr().print("Warning in AnalyticConvolution:  negative probablity\n");
                StdStreams.cerr().print(new COStream().put(xsigma).put(' ').put(tau).put(' ').put(xoffset)
                    .put(' ').put(freq).put(' ').put(argument).str() + "\n");
                StdStreams.cerr().print(new COStream().put(retVal).str() + "\n");
            }
            return retVal;
        } else if (_type == Type.SMEARED_COS_EXP || _type == Type.SMEARED_SIN_EXP) {
            return asymm;
        } else {
            StdStreams.cerr().print("Unknown sign parity.  State is not allowed\n");
            throw new IllegalStateException("AnalyticConvolution: unknown sign parity");
        }
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }
}
