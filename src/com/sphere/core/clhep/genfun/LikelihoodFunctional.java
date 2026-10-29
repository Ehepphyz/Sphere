package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.COStream;

import java.util.ArrayList;
import java.util.List;

/**
 * CLHEP's Genfun::LikelihoodFunctional (LikelihoodFunctional.hh, .cc): -2 log of the likelihood of the
 * arguments under a density; a density not positive at one of them is an error.
 */
public class LikelihoodFunctional extends AbsFunctional {

    private final List<Argument> _aList = new ArrayList<>();

    public LikelihoodFunctional(List<Argument> aList) {
        for (Argument a : aList) _aList.add(new Argument(a));
    }

    @Override
    public double apply(AbsFunction function) {
        double logLikelihood = 0;
        for (int i = 0; i < _aList.size(); i++) {
            final Argument a = _aList.get(i);
            final double f = function.call(a);
            if (f <= 0.0) {
                final COStream stream = new COStream().put("Negative likelihood arg[").put(i).put("]=");
                throw new RuntimeException(a.print(stream).str());
            }
            logLikelihood -= CMath.log(f);
        }
        return 2.0 * logLikelihood;
    }
}
