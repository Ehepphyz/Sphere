package com.sphere.core.hepmc3;

import java.util.Map;
import java.util.TreeMap;

/**
 * Base of every reader: one event at a time into a GenEvent, a run
 * information shared by the events read, options by name.
 */
public abstract class Reader implements AutoCloseable {

    /** Reader options, as HepMC3's std::map&lt;string, string&gt;. */
    protected Map<String, String> options = new TreeMap<>();
    private GenRunInfo runInfo;

    /** Skips n events; true when the reader can go on. */
    public boolean skip(int n) {
        return !failed();
    }

    /** Fills the next event; false when there is none or it could not be read. */
    public abstract boolean readEvent(GenEvent evt);

    /** Whether the input is exhausted or broken. */
    public abstract boolean failed();

    @Override
    public abstract void close();

    public GenRunInfo runInfo() {
        return runInfo;
    }

    public void setRunInfo(GenRunInfo run) {
        runInfo = run;
    }

    public void setOptions(Map<String, String> opts) {
        options = new TreeMap<>(opts);
    }

    public Map<String, String> getOptions() {
        return new TreeMap<>(options);
    }
}
