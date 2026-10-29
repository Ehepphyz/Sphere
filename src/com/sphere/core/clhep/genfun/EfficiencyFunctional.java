package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.ArrayList;
import java.util.List;

/**
 * CLHEP's Genfun::EfficiencyFunctional (EfficiencyFunctional.hh, .cc): -2 log of the likelihood of
 * pass/fail outcomes, each argument's last coordinate (pass above 0.5) given an efficiency function of
 * the others. As in CLHEP, the last argument of the list is left out.
 */
public class EfficiencyFunctional extends AbsFunctional {

    private final List<Argument> _aList = new ArrayList<>();

    public EfficiencyFunctional(List<Argument> aList) {
        for (Argument a : aList) _aList.add(new Argument(a));
    }

    @Override
    public double apply(AbsFunction function) {
        double logEfficiency = 0;
        for (int i = 0; i < _aList.size() - 1; i++) {
            final Argument a = _aList.get(i);
            final Argument b = new Argument(a.dimension() - 1);
            for (int j = 0; j < b.dimension(); j++) b.set(j, a.get(j));
            final double f = function.call(b);
            if (f < 0 || f > 1) {
                final COStream os = new COStream().put("Warning.. negative likelihood arg[").put(i).put("]=");
                a.print(os).put("f=").put(f);
                StdStreams.cerr().print(os.str() + "\n");
            }
            logEfficiency -= (a.get(a.dimension() - 1) > 0.5) ? CMath.log(f) : CMath.log(1 - f);
        }
        return 2.0 * logEfficiency;
    }
}
