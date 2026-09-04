package com.sphere.utils.settingsmanager;

import com.sphere.utils.SettingsManager;

import java.util.regex.Pattern;

/**
 * Checks a value before the editor writes it.
 *
 * The rule is to let the user through unless the value is provably wrong. The
 * previous version refused a blank value, which is settings.conf's own way of
 * disabling a backend; it refused a Windows path whenever Sphere ran on Linux,
 * and the reverse; it refused $VAR references, which are the shell-profile
 * semantics the file is built on; and it matched key names by substring, so
 * EXPORT_DIR was treated as a port number and CPP_DEBUG_FLAGS as a boolean.
 */
public final class SettingsValidator {

    private static final Pattern PORT_PATTERN = Pattern.compile("^\\d{1,5}$");
    private static final Pattern BOOL_PATTERN =
        Pattern.compile("^(?i)(true|false|yes|no|on|off|1|0)$");

    /** A key named exactly PORT, or ending in _PORT: not EXPORT_DIR. */
    private static final Pattern PORT_KEY = Pattern.compile("^(.*_)?PORT$");
    private static final Pattern BOOL_KEY = Pattern.compile("^(.*_)?(ENABLED|DEBUG)$|^USE_.*$");

    /** Anything holding a $NAME or ${NAME} reference is resolved at read time. */
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{?[A-Za-z_][A-Za-z0-9_]*\\}?");

    private SettingsValidator() {}

    /**
     * True when the text names a location Sphere can understand on some platform:
     * a drive path, a WSL mount, an MSYS2 chain or a POSIX path.
     */
    public static boolean isValidAbsoluteSystemPath(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = unquote(value.trim());
        if (trimmed.isEmpty()) {
            return true;                        // blank disables the backend
        }
        if (REFERENCE.matcher(trimmed).find()) {
            return true;                        // resolved later against the file and the environment
        }
        // Judged against every platform, not the one Sphere happens to run on: the
        // same settings.conf is meant to travel between Windows, WSL, Linux and macOS.
        for (com.sphere.utils.OSValidator.Platform target
                : com.sphere.utils.OSValidator.Platform.values()) {
            if (SettingsManager.toNativePath(trimmed, target) != null) {
                return true;
            }
        }
        return false;
    }

    public static boolean validate(String category, String key, String value) {
        if (value == null || key == null) {
            return false;
        }
        String cleanValue = value.trim();
        String upperKey = key.toUpperCase().trim();
        String upperCategory = category != null ? category.toUpperCase().trim() : "";

        // A key emptied on purpose is always legal: the file says that disables it.
        if (cleanValue.isEmpty()) {
            return true;
        }
        // A reference is only known once the file and the environment are read.
        if (REFERENCE.matcher(cleanValue).find()) {
            return true;
        }
        if (PORT_KEY.matcher(upperKey).matches()) {
            if (!PORT_PATTERN.matcher(cleanValue).matches()) {
                return false;
            }
            int port = Integer.parseInt(cleanValue);
            return port >= 0 && port <= 65535;
        }
        if (BOOL_KEY.matcher(upperKey).matches()) {
            return BOOL_PATTERN.matcher(cleanValue).matches();
        }
        if ("SYSTEM".equals(upperCategory) || "SYSTEM_PATH".equals(upperCategory)) {
            return isValidAbsoluteSystemPath(cleanValue);
        }
        return true;
    }

    /**
     * Why a value was refused, for the message the editor shows. Null when it is
     * accepted.
     */
    public static String explain(String category, String key, String value) {
        if (validate(category, key, value)) {
            return null;
        }
        String upperKey = key == null ? "" : key.toUpperCase().trim();
        if (PORT_KEY.matcher(upperKey).matches()) {
            return "A port must be a number between 0 and 65535.";
        }
        if (BOOL_KEY.matcher(upperKey).matches()) {
            return "This key expects true or false.";
        }
        return "This does not read as a path. Expected forms: C:\\dir\\tool.exe, "
             + "C:/dir/tool, /c/dir/tool, /mnt/c/dir/tool or /usr/bin/tool. "
             + "Leave it empty to disable this backend.";
    }

    private static String unquote(String text) {
        if (text.length() > 1
                && ((text.startsWith("\"") && text.endsWith("\""))
                 || (text.startsWith("'") && text.endsWith("'")))) {
            return text.substring(1, text.length() - 1).trim();
        }
        return text;
    }
}
