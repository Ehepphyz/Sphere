package com.sphere.core;

import com.sphere.utils.SettingsManager;
import java.util.ArrayList;
import java.util.List;

public enum EnvBackend {
    C("C Compiler", "gcc --version", "ENV_CC"),
    CPP("C++ Compiler", "g++ --version", "ENV_CXX"),
    FORTRAN("Fortran Compiler", "gfortran --version", "ENV_FC"),
    PYTHON("Python Environment", "python --version", "VIRTUAL_ENV"),
    NODEJS("Node.js Runtime", "node --version", "NODE_PATH"),
    ROOT_FWORK("Root", "root-config --version", "ROOTSYS"),
    GEANT4("Geant4 Simulation", "geant4-config --version", "G4INSTALL"),
    MADGRAPH("MadGraph5_aMC@NLO", "mg5_aMC --version", "MADGRAPH_DATA"),
    HERWIG("Herwig Generator", "Herwig --version", "HERWIGPATH"),
    JULIA("Julia Runtime", "julia --version", "JULIA_NUM_THREADS");

    private final String displayName;
    private final String checkCommand;
    private final String coreEnvVar;

    EnvBackend(String displayName, String checkCommand, String coreEnvVar) {
        this.displayName = displayName;
        this.checkCommand = checkCommand;
        this.coreEnvVar = coreEnvVar;
    }

    public String getDisplayName() { return displayName; }
    public String getCheckCommand() { return checkCommand; }
    public String getCoreEnvVar() { return coreEnvVar; }

    public String getConfigKey() {
        return switch (this) {
            case C -> "GCC_DIR";
            case CPP -> "GPP_DIR";
            case FORTRAN -> "FORTRAN_DIR";
            case PYTHON -> "PYTHON_EXEC";
            case NODEJS -> "NODE_DIR";
            case JULIA -> "JULIA_DIR";
            case ROOT_FWORK -> "ROOT_DIR";
            case GEANT4 -> "GEANT4_DIR";
            case MADGRAPH -> "MG5_DIR";
            case HERWIG -> "HERWIG_DIR";
        };
    }

    /**
     * Scans settings.conf dynamically to extract and return only the backends
     * that contain active, non-blank operational paths
     */
    public static EnvBackend[] getActiveValues(SettingsManager settings) {
        List<EnvBackend> activeList = new ArrayList<>();
        
        for (EnvBackend backend : EnvBackend.values()) {
            String key = backend.getConfigKey();
            
            // Sequential cross-check matching your dialog architecture
            String path = settings.getProperty("SYSTEM_PATH", key);
            if (path == null || path.trim().isEmpty()) {
                path = settings.getProperty("GENERAL", key);
            }
            
            // If the path contains data, it is active and should be rendered in the UI
            if (path != null && !path.trim().isEmpty()) {
                activeList.add(backend);
            }
        }
        
        return activeList.toArray(new EnvBackend[0]);
    }

    /**
     * Checks whether this specific backend has an active configuration path
     * defined in the provided SettingsManager instance
     */
    public boolean isConfigured(com.sphere.utils.SettingsManager settings) {
        if (settings == null) {
            return false;
        }
        String key = this.getConfigKey();
        String path = settings.getProperty("SYSTEM_PATH", key);
        if (path == null || path.trim().isEmpty()) {
            path = settings.getProperty("GENERAL", key);
        }
        return path != null && !path.trim().isEmpty();
    }
}