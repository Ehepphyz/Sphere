package com.sphere.core.hepmc3;

import java.util.ArrayList;
import java.util.List;

/** The run information flattened for storage. */
public final class GenRunInfoData {

    public final List<String> weightNames = new ArrayList<>();
    public final List<String> toolName = new ArrayList<>();
    public final List<String> toolVersion = new ArrayList<>();
    public final List<String> toolDescription = new ArrayList<>();
    public final List<String> attributeName = new ArrayList<>();
    public final List<String> attributeString = new ArrayList<>();

    public void clear() {
        weightNames.clear();
        toolName.clear();
        toolVersion.clear();
        toolDescription.clear();
        attributeName.clear();
        attributeString.clear();
    }
}
