package com.sphere.utils;

import javax.swing.*;
import java.io.File;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * High-velocity Icon Management and HiDPI Scaling Facility for the Sphere UI ecosystem.
 * Resolves scientific extensions, custom user theme profiles, and runtime file hierarchies.
 * Fully cross-platform compliant across Windows, Linux, and macOS environments.
 */
public class IconManager {

    private static final Map<String, Icon> ICON_CACHE = new HashMap<>();

    private static final String BASE_PATH = "src/com/sphere/icons/";
    private static final String RESOURCE_PATH = "/com/sphere/icons/";
    private static final String DEFAULT_ICON = BASE_PATH + "default.png";

    /* -------------------------------------------------------------------------
     * Extension Normalization Groups
     * (Maps multiple file extensions to a singular icon resource name)
     * ------------------------------------------------------------------------- */
    private static final Map<String, String> EXT_GROUPS = Map.ofEntries(

        /* ---------------------------------------------------------
         * IMAGE FORMATS
         * --------------------------------------------------------- */
        Map.entry("jpeg", "jpg"),
        Map.entry("jpe", "jpg"),
        Map.entry("jfif", "jpg"),
        Map.entry("tiff", "tif"),

        /* ---------------------------------------------------------
         * WEB / MARKUP
         * --------------------------------------------------------- */
        Map.entry("htm", "html"),
        Map.entry("xhtml", "html"),
        Map.entry("mdown", "md"),
        Map.entry("markdown", "md"),

        /* ---------------------------------------------------------
         * YAML / JSON / CONFIG
         * --------------------------------------------------------- */
        Map.entry("yml", "yaml"),
        Map.entry("json5", "json"),
        Map.entry("toml", "cfg"),
        Map.entry("conf", "cfg"),
        Map.entry("config", "cfg"),

        /* ---------------------------------------------------------
         * PYTHON
         * ----------------------------------------- */
        Map.entry("pyw", "py"),
        Map.entry("pyi", "py"),
        Map.entry("pyd", "py"),
        Map.entry("pyc", "py"),

        /* ---------------------------------------------------------
         * C / C++ SOURCE FILES
         * --------------------------------------------------------- */
        Map.entry("cxx", "cpp"),
        Map.entry("cc", "cpp"),
        Map.entry("c++", "cpp"),
        Map.entry("cp", "cpp"),
        Map.entry("c", "cpp"),

        /* ---------------------------------------------------------
         * C / C++ HEADERS
         * --------------------------------------------------------- */
        Map.entry("hpp", "h"),
        Map.entry("hxx", "h"),
        Map.entry("hh", "h"),
        Map.entry("h++", "h"),

        /* ---------------------------------------------------------
         * JAVA / JVM
         * --------------------------------------------------------- */
        Map.entry("class", "java"),
        Map.entry("jar", "java"),
        Map.entry("war", "zip"),

        /* ---------------------------------------------------------
         * SHELL / SCRIPTS
         * --------------------------------------------------------- */
        Map.entry("bash", "sh"),
        Map.entry("zsh", "sh"),
        Map.entry("ksh", "sh"),
        Map.entry("csh", "sh"),
        Map.entry("tcsh", "sh"),
        Map.entry("cmd", "bat"),

        /* ---------------------------------------------------------
         * LATEX
         * --------------------------------------------------------- */
        Map.entry("sty", "tex"),
        Map.entry("cls", "tex"),
        Map.entry("ltx", "tex"),

        /* ---------------------------------------------------------
         * JUPYTER
         * --------------------------------------------------------- */
        Map.entry("ipynb", "jupyter"),

        /* ---------------------------------------------------------
         * ARCHIVES
         * --------------------------------------------------------- */
        Map.entry("tar.gz", "archive"),
        Map.entry("tgz", "archive"),
        Map.entry("tar.bz2", "archive"),
        Map.entry("tbz", "archive"),
        Map.entry("tar.xz", "archive"),
        Map.entry("txz", "archive"),

        /* ---------------------------------------------------------
         * GEANT4 / HIGH ENERGY PHYSICS
         * --------------------------------------------------------- */
        Map.entry("mac", "geant4"),
        Map.entry("gdml", "geant4"),

        /* ---------------------------------------------------------
         * MISCELLANEOUS
         * --------------------------------------------------------- */
        Map.entry("text", "txt"),
        Map.entry("lock", "txt")
    );

    /* -------------------------------------------------------------------------
     * ROOT Specific Mapping Registry
     * ------------------------------------------------------------------------- */
    private static final Map<String, String> ROOT_EXT = Map.ofEntries(
        Map.entry("root", "root"),
        Map.entry("pcm", "rootmodule"),
        Map.entry("c", "cpp"),
        Map.entry("cxx", "cpp"),
        Map.entry("c++", "cpp")
    );

    /* -------------------------------------------------------------------------
     * Generic Fallback Paths for Abstract Document Types
     * ------------------------------------------------------------------------- */
    private static final Map<String, String> EXT_FALLBACK = Map.ofEntries(
        Map.entry("txt", "text"),
        Map.entry("json", "code"),
        Map.entry("xml", "code"),
        Map.entry("cfg", "settings"),
        Map.entry("ini", "settings")
    );

    /**
     * Resolves and returns a scaled icon matching the parsed file extension variant.
     * Guaranteed to return a valid icon object (never null).
     */
    public static Icon getIconForFile(String fileName) {
        String ext = extractExtension(fileName);

        // 1. Process ROOT simulation telemetry configurations first
        if (ROOT_EXT.containsKey(ext)) {
            String mapped = ROOT_EXT.get(ext) + ".png";
            Icon icon = tryLoadIcon(mapped);
            if (icon != null) return icon;
        }

        // 2. Normalize standard language and layout syntax groups
        ext = EXT_GROUPS.getOrDefault(ext, ext);

        // 3. Attempt direct structural icon evaluation: ext.png
        String directIcon = ext + ".png";
        Icon icon = tryLoadIcon(directIcon);
        if (icon != null) return icon;

        // 4. Process secondary fallback assignments for general code/text profiles
        if (EXT_FALLBACK.containsKey(ext)) {
            String fb = EXT_FALLBACK.get(ext) + ".png";
            icon = tryLoadIcon(fb);
            if (icon != null) return icon;
        }

        // 5. Final fallback to system default icon asset
        return getIcon("default.png");
    }

    /**
     * Public accessor to resolve an explicit icon file name directly out of caching contexts.
     * Guaranteed to return a valid Icon container instance (never null).
     */
    public static Icon getIcon(String iconFileName) {
        if (iconFileName == null || iconFileName.isBlank()) {
            return getEmergencyPlaceholderIcon("Invalid/Null filename argument passed to getIcon()");
        }
        return getCachedIcon(BASE_PATH + iconFileName, iconFileName);
    }

    /**
     * Internal lookup helper that yields null explicitly to support conditional mapping pipelines.
     */
    private static Icon tryLoadIcon(String iconFileName) {
        if (iconFileName == null || iconFileName.isBlank()) return null;
        
        String iconPath = BASE_PATH + iconFileName;
        String key = iconPath.toLowerCase();

        // Check cache straight away to avoid repetitive disk metrics hits
        if (ICON_CACHE.containsKey(key)) {
            return ICON_CACHE.get(key);
        }

        Icon icon = loadIcon(iconPath, iconFileName);
        if (icon == null) return null;

        // Apply high-density UI rendering modifications
        icon = scaleForHiDPI(icon);
        ICON_CACHE.put(key, icon);
        return icon;
    }

    /**
     * Centralized cache processor, file system supervisor, and binary classpath manager.
     * Guaranteed to return a valid icon container instance (never null).
     */
    private static Icon getCachedIcon(String iconPath, String resourceFileName) {
        String key = iconPath.toLowerCase();

        if (ICON_CACHE.containsKey(key)) {
            return ICON_CACHE.get(key);
        }

        Icon icon = loadIcon(iconPath, resourceFileName);

        // If the icon is missing from disk, classpath, and standard defaults, generate a placeholder
        if (icon == null) {
            return getEmergencyPlaceholderIcon(iconPath);
        }

        icon = scaleForHiDPI(icon);
        ICON_CACHE.put(key, icon);
        return icon;
    }

    /**
     * Traverses host file systems and packaged JAR assembly files.
     * Prioritizes Embedded Classpath (JAR) execution to eliminate resource failures on Linux/macOS.
     */
    private static Icon loadIcon(String iconPath, String resourceFileName) {
        // Phase 1: Embedded Classpath Binary File Lookup (Primary for JAR distribution)
        String sanitizedResourceFolder = RESOURCE_PATH.endsWith("/") 
                ? RESOURCE_PATH.substring(0, RESOURCE_PATH.length() - 1) 
                : RESOURCE_PATH;
        
        URL url = IconManager.class.getResource(sanitizedResourceFolder + "/" + resourceFileName);
        if (url != null) {
            return new ImageIcon(url);
        }

        // Phase 2: Local Hard Disk Drive Validation Context (Fallback for IDE development workflows)
        File file = new File(iconPath);
        if (file.exists() && file.isFile()) {
            return new ImageIcon(file.getAbsolutePath());
        }

        // Phase 3: Project Architecture Default Suffix Asset Validation
        // First attempt fallback default icon within Classpath context
        URL defaultUrl = IconManager.class.getResource(sanitizedResourceFolder + "/default.png");
        if (defaultUrl != null) {
            return new ImageIcon(defaultUrl);
        }

        // Final fallback to localized disk tracking
        File def = new File(DEFAULT_ICON);
        if (def.exists() && def.isFile()) {
            return new ImageIcon(def.getAbsolutePath());
        }

        return null;
    }

    /**
     * Safely isolates file extensions out of workspace path indicators.
     */
    private static String extractExtension(String fileName) {
        if (fileName == null) return "";
        int i = fileName.lastIndexOf('.');
        return (i > 0) ? fileName.substring(i + 1).toLowerCase().trim() : "";
    }

    /**
     * Constructs a solid, concrete emergency memory buffer icon to safeguard the JVM from NullPointerExceptions.
     */
    private static Icon getEmergencyPlaceholderIcon(String debugContext) {
        String placeholderKey = "internal://emergency.placeholder";
        if (ICON_CACHE.containsKey(placeholderKey)) {
            return ICON_CACHE.get(placeholderKey);
        }

        AppLogger.error("System critical icon resources unreachable. Generating dynamic emergency placeholder. Context: " + debugContext);
        
        BufferedImage fallbackImage = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = fallbackImage.createGraphics();
        g2.setColor(Color.RED);
        g2.fillRect(0, 0, 16, 16);
        g2.dispose();

        Icon emergencyIcon = new ImageIcon(fallbackImage);
        ICON_CACHE.put(placeholderKey, emergencyIcon);
        return emergencyIcon;
    }

    /**
     * Rescales vector images leveraging smooth anti-aliasing operations for sharp rendering on 4K monitors.
     */
    private static Icon scaleForHiDPI(Icon icon) {
        if (!(icon instanceof ImageIcon)) return icon;

        Image img = ((ImageIcon) icon).getImage();
        int scale = Toolkit.getDefaultToolkit().getScreenResolution() / 96;

        if (scale <= 1) return icon;

        int w = icon.getIconWidth() * scale;
        int h = icon.getIconHeight() * scale;

        Image scaled = img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
        return new ImageIcon(scaled);
    }
}
