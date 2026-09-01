package com.sphere.components;

import javax.swing.*;
import java.util.prefs.Preferences;

/**
 * A JSplitPane that automatically saves and restores its divider location.
 */
public class PersistentSplitPane extends JSplitPane {
    public PersistentSplitPane(int orientation, java.awt.Component left, java.awt.Component right, 
                               Preferences prefs, String key, int defaultLocation) {
        super(orientation, left, right);
        setDividerLocation(prefs.getInt(key, defaultLocation));
        addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            prefs.putInt(key, getDividerLocation());
        });
    }
}
