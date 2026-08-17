package com.sphere.utils.settingsmanager;

import javax.swing.tree.DefaultMutableTreeNode;

/**
 * Custom tree node that encapsulates configuration objects and tracks
 * their structural type within the settings management panel.
 */
public class SettingsTreeNode extends DefaultMutableTreeNode {

    private final SettingsNodeType type;

    public SettingsTreeNode(Object userObject, SettingsNodeType type) {
        super(userObject);
        this.type = type;
    }

    public SettingsNodeType getType() {
        return type;
    }

    @Override
    public String toString() {
        Object uo = getUserObject();
        // For configuration variables, only display the key text in the visual tree layout
        if (type == SettingsNodeType.KEY && uo instanceof KeyValue kv) {
            return kv.key();
        }
        return String.valueOf(uo);
    }
}
