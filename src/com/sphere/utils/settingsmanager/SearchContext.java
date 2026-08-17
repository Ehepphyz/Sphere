package com.sphere.utils.settingsmanager;

import javax.swing.tree.TreePath;

public record SearchContext(String query, TreePath activeMatch) {}
