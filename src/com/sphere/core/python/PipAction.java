package com.sphere.core.python;

public enum PipAction {
    UPDATE("install --upgrade"),
    REINSTALL("install --force-reinstall"),
    UNINSTALL("uninstall -y"),
    INSTALL("install"),
    REQUIREMENTS("install -r"); // Add this

    public final String cmd;

    PipAction(String cmd) {
        this.cmd = cmd;
    }
}

