package com.sphere.core.hepmc3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What is shared by the events of a run: the tools that made them, the names
 * of their weights, and run-wide attributes (the LHEF init block, for one).
 */
public final class GenRunInfo {

    /** A tool used to produce the run. */
    public static final class ToolInfo {
        public String name = "";
        public String version = "";
        public String description = "";

        public ToolInfo() {
        }

        public ToolInfo(String name, String version, String description) {
            this.name = name;
            this.version = version;
            this.description = description;
        }
    }

    private final List<ToolInfo> tools = new ArrayList<>();
    private final TreeMap<String, Integer> weightIndices = new TreeMap<>();
    private final List<String> weightNames = new ArrayList<>();
    private final TreeMap<String, Attribute> attributes = new TreeMap<>();
    private final Object lock = new Object();

    public GenRunInfo() {
    }

    /** A copy, through the stored form as in C++. */
    public GenRunInfo(GenRunInfo r) {
        if (r != null) {
            final GenRunInfoData d = new GenRunInfoData();
            r.writeData(d);
            readData(d);
        }
    }

    /** The tools (the list itself, to add to). */
    public List<ToolInfo> tools() {
        return tools;
    }

    public boolean hasWeight(String name) {
        return weightIndices.containsKey(name);
    }

    /** A copy of the name-to-index map. */
    public Map<String, Integer> weightIndices() {
        return new TreeMap<>(weightIndices);
    }

    /** The index of a weight name, -1 when absent. */
    public int weightIndex(String name) {
        final Integer i = weightIndices.get(name);
        return i == null ? -1 : i;
    }

    public List<String> weightNames() {
        return java.util.Collections.unmodifiableList(weightNames);
    }

    /**
     * Sets the weight names; an empty name becomes its index, a duplicate
     * name is refused (IllegalStateException, C++'s std::logic_error).
     */
    public void setWeightNames(List<String> names) {
        weightIndices.clear();
        weightNames.clear();
        weightNames.addAll(names);
        for (int i = 0, n = names.size(); i < n; ++i) {
            String name = names.get(i);
            if (name.isEmpty()) {
                name = Integer.toString(i);
                weightNames.set(i, name);
            }
            if (hasWeight(name)) {
                throw new IllegalStateException("GenRunInfo::set_weight_names: Duplicate weight name '" + name + "' found.");
            }
            weightIndices.put(name, i);
        }
    }

    /** Adds an attribute, replacing one of the same name. */
    public void addAttribute(String name, Attribute att) {
        synchronized (lock) {
            if (att != null) attributes.put(name, att);
        }
    }

    public void removeAttribute(String name) {
        synchronized (lock) {
            attributes.remove(name);
        }
    }

    /** The attribute parsed as the given type, or null. */
    public <T extends Attribute> T attribute(String name, Class<T> type) {
        synchronized (lock) {
            final Attribute a = attributes.get(name);
            if (a == null) return null;
            if (!a.isParsed()) {
                final T att = Attribute.make(type);
                if (att.fromString(a.unparsedString()) && att.init(this)) {
                    attributes.put(name, att);
                    return att;
                }
                return null;
            }
            return type.isInstance(a) ? type.cast(a) : null;
        }
    }

    public String attributeAsString(String name) {
        synchronized (lock) {
            final Attribute a = attributes.get(name);
            if (a == null) return "";
            final String s = a.serialize();
            return s == null ? "" : s;
        }
    }

    public List<String> attributeNames() {
        synchronized (lock) {
            return new ArrayList<>(attributes.keySet());
        }
    }

    /** A copy of the name-to-attribute map. */
    public Map<String, Attribute> attributes() {
        synchronized (lock) {
            return new TreeMap<>(attributes);
        }
    }

    public void writeData(GenRunInfoData data) {
        data.weightNames.addAll(weightNames);
        synchronized (lock) {
            for (Map.Entry<String, Attribute> vt : attributes.entrySet()) {
                final String att = vt.getValue().serialize();
                data.attributeName.add(vt.getKey());
                data.attributeString.add(att == null ? "" : att);
            }
        }
        for (ToolInfo tool : tools) {
            data.toolName.add(tool.name);
            data.toolVersion.add(tool.version);
            data.toolDescription.add(tool.description);
        }
    }

    public void readData(GenRunInfoData data) {
        setWeightNames(data.weightNames);
        for (int i = 0; i < data.attributeName.size(); ++i) {
            addAttribute(data.attributeName.get(i), new StringAttribute(data.attributeString.get(i)));
        }
        for (int i = 0; i < data.toolName.size(); ++i) {
            tools.add(new ToolInfo(data.toolName.get(i), data.toolVersion.get(i), data.toolDescription.get(i)));
        }
    }

    @Override
    public String toString() {
        return Print.line(this, false);
    }
}
