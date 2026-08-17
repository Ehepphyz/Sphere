package com.sphere.utils.settingsmanager;

/**
 * An immutable data container mapping a single key-value configuration pair
 * along with its corresponding structural INI section.
 */
public record KeyValue(String section, String key, String value) {}
