package com.sphere.utils.settingsmanager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads settings.conf into a SettingsFile, keeping the original lines so a later
 * save can put back exactly what it did not change.
 */
public final class SettingsParser {

    private SettingsParser() {}

    public static SettingsFile parse(Path file) throws IOException {
        SettingsFile settings = new SettingsFile();
        // Explicit charset on both sides of the round trip.
        settings.load(Files.readAllLines(file, StandardCharsets.UTF_8));
        return settings;
    }
}
