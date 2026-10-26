package com.sphere.core.geant4;

import java.util.Locale;

/**
 * A colour for a material, so a new volume looks like what it is made of:
 * metals grey, lead dark, copper orange, silicon slate blue, scintillators
 * cyan, water and gases translucent. Users override it per volume.
 */
public final class MaterialColors {

    private MaterialColors() {
    }

    public static int colorFor(String material) {
        if (material == null) {
            return 0xFF9AA7B8;
        }
        final String m = material.toUpperCase(Locale.ROOT).replace("G4_", "");
        if (m.equals("GALACTIC") || m.contains("VACUUM")) {
            return 0x18C0C8D8;
        }
        if (m.equals("AIR")) {
            return 0x30B0C4DE;
        }
        if (m.contains("AIR") || m.equals("H") || m.equals("HE") || m.equals("N") || m.equals("O")
            || m.equals("AR") || m.equals("NE") || m.contains("GAS") || m.contains("CO2") || m.contains("METHANE")) {
            return 0x40A8C8E8;
        }
        if (m.contains("WATER")) {
            return 0x803C8DD8;
        }
        if (m.equals("PB") || m.contains("LEAD")) {
            return 0xFF5A6070;
        }
        if (m.equals("W") || m.contains("TUNGSTEN")) {
            return 0xFF6E6A78;
        }
        if (m.equals("CU") || m.contains("COPPER") || m.contains("BRASS") || m.contains("BRONZE")) {
            return 0xFFC8743C;
        }
        if (m.equals("AU")) {
            return 0xFFE0B830;
        }
        if (m.equals("FE") || m.contains("STEEL") || m.contains("IRON")) {
            return 0xFF8C96A4;
        }
        if (m.equals("AL") || m.contains("ALUMIN")) {
            return 0xFFC0C8D2;
        }
        if (m.equals("TI") || m.equals("NI") || m.equals("CR") || m.equals("MO") || m.equals("TA")) {
            return 0xFFA0A8B0;
        }
        if (m.equals("SI") || m.contains("SILICON")) {
            return 0xFF6C78C8;
        }
        if (m.equals("GE")) {
            return 0xFF8890B8;
        }
        if (m.contains("SCINT") || m.contains("VINYLTOLUENE") || m.contains("POLYSTYRENE")) {
            return 0xC040C8D8;
        }
        if (m.contains("PBWO4") || m.contains("BGO") || m.contains("CESIUM_IODIDE") || m.contains("SODIUM_IODIDE")
            || m.contains("LYSO") || m.contains("CRYSTAL") || m.contains("GLASS")) {
            return 0xC07CD8B0;
        }
        if (m.equals("LAR") || m.equals("LXE") || m.equals("LKR") || m.equals("LH2") || m.equals("LN2")
            || m.equals("LO2") || m.equals("LBR")) {
            return 0x9070B0F0;
        }
        if (m.contains("CONCRETE") || m.contains("BRICK")) {
            return 0xFFA49E92;
        }
        if (m.equals("C") || m.contains("GRAPHITE") || m.contains("CARBON")) {
            return 0xFF484C54;
        }
        if (m.contains("KAPTON") || m.contains("MYLAR") || m.contains("POLYIMIDE")) {
            return 0xD0E0A040;
        }
        if (m.contains("POLYETHYLENE") || m.contains("PLASTIC") || m.contains("LUCITE") || m.contains("PLEXIGLASS")
            || m.contains("NYLON") || m.contains("TEFLON")) {
            return 0xD0E8E8E0;
        }
        if (m.contains("TISSUE") || m.contains("MUSCLE") || m.contains("BLOOD") || m.contains("SKIN")) {
            return 0xD0E89080;
        }
        if (m.contains("BONE")) {
            return 0xFFEEE6D0;
        }
        if (m.equals("U") || m.equals("PU")) {
            return 0xFF6A8A50;
        }
        // Anything else: a stable hue from the name
        final int h = Math.abs(m.hashCode());
        final float hue = (h % 360) / 360f;
        return 0xFF000000 | (java.awt.Color.HSBtoRGB(hue, 0.35f, 0.82f) & 0xFFFFFF);
    }
}
