package com.sphere.core.mathcore;

import com.sphere.core.hepmc3.cxx.CFormat;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * ROOT::Math::GenAlgoOptions: the options of an algorithm kept in three maps
 * (ordered by name, as std::map), and the default options of each algorithm
 * by its upper-case name ({@link #Default}, {@link #FindDefault}).
 */
public class GenAlgoOptions extends IOptions {

    private final TreeMap<String, Integer> fIntOpts = new TreeMap<>();
    private final TreeMap<String, Double> fRealOpts = new TreeMap<>();
    private final TreeMap<String, String> fNamOpts = new TreeMap<>();

    public GenAlgoOptions() {
    }

    public GenAlgoOptions(GenAlgoOptions o) {
        fIntOpts.putAll(o.fIntOpts);
        fRealOpts.putAll(o.fRealOpts);
        fNamOpts.putAll(o.fNamOpts);
    }

    @Override
    public IOptions Clone() {
        return new GenAlgoOptions(this);
    }

    @Override
    public boolean GetRealValue(String name, double[] val) {
        final Double v = fRealOpts.get(name);
        if (v == null) return false;
        val[0] = v;
        return true;
    }

    @Override
    public boolean GetIntValue(String name, int[] val) {
        final Integer v = fIntOpts.get(name);
        if (v == null) return false;
        val[0] = v;
        return true;
    }

    @Override
    public boolean GetNamedValue(String name, String[] val) {
        final String v = fNamOpts.get(name);
        if (v == null) return false;
        val[0] = v;
        return true;
    }

    @Override
    public void SetRealValue(String name, double val) {
        fRealOpts.put(name, val);
    }

    @Override
    public void SetIntValue(String name, int val) {
        fIntOpts.put(name, val);
    }

    @Override
    public void SetNamedValue(String name, String val) {
        fNamOpts.put(name, val);
    }

    public List<String> GetAllNamedKeys() {
        return new ArrayList<>(fNamOpts.keySet());
    }

    public List<String> GetAllRealKeys() {
        return new ArrayList<>(fRealOpts.keySet());
    }

    public List<String> GetAllIntKeys() {
        return new ArrayList<>(fIntOpts.keySet());
    }

    @Override
    public void Print(PrintStream os) {
        print(fNamOpts, os);
        print(fIntOpts, os);
        print(fRealOpts, os);
    }

    private static void print(Map<String, ?> opts, PrintStream os) {
        for (Map.Entry<String, ?> e : opts.entrySet()) {
            final Object v = e.getValue();
            final String value = v instanceof Double d ? CFormat.sprintf("%15g", d) : CFormat.sprintf("%15s", v);
            os.print(CFormat.sprintf("%25s", e.getKey()) + " : " + value + "\n");
        }
    }

    /* ------------------------------------------------------------------ */
    /* The default options of each algorithm                               */
    /* ------------------------------------------------------------------ */

    private static final TreeMap<String, GenAlgoOptions> gAlgoOptions = new TreeMap<>();

    /** The default options of the algorithm (by its name, case ignored), null when it has none. */
    public static synchronized IOptions FindDefault(String algo) {
        return gAlgoOptions.get(algo.toUpperCase(Locale.ROOT));
    }

    /** The default options of the algorithm, created empty when it has none. */
    public static synchronized IOptions Default(String algo) {
        return gAlgoOptions.computeIfAbsent(algo.toUpperCase(Locale.ROOT), k -> new GenAlgoOptions());
    }

    public static synchronized void PrintAllDefault(PrintStream os) {
        for (Map.Entry<String, GenAlgoOptions> e : gAlgoOptions.entrySet()) {
            os.print("Default specific options for algorithm " + e.getKey() + " : \n");
            e.getValue().Print(os);
        }
    }
}
