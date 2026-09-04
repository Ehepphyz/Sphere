package com.sphere.utils.settingsmanager;

/**
 * One key-value pair with its section and its rank inside it. The rank is what
 * tells three PATH= declarations apart, the way a shell profile stacks them.
 */
public record KeyValue(String section, String key, String value, int index) {}
