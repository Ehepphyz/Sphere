package com.sphere.core.hepmc3;

import java.util.Map;
import java.util.TreeMap;

/** Base of every writer: events one at a time, a run information, options by name. */
public abstract class Writer implements AutoCloseable {

    protected Map<String, String> options = new TreeMap<>();
    private GenRunInfo runInfo;

    public abstract void writeEvent(GenEvent evt);

    public abstract boolean failed();

    @Override
    public abstract void close();

    public void setRunInfo(GenRunInfo run) {
        runInfo = run;
    }

    public GenRunInfo runInfo() {
        return runInfo;
    }

    public void setOptions(Map<String, String> opts) {
        options = new TreeMap<>(opts);
    }

    public Map<String, String> getOptions() {
        return new TreeMap<>(options);
    }
}
