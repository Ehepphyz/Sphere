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

    /* -------------------------------------------------------------------------
     * Application Icon
     * ------------------------------------------------------------------------- */

    private static final String APP_ICON = "sphere_logo.png";

    /** Sizes Windows, GNOME and KDE pick from for the title bar, task bar and alt-tab. */
    private static final int[] APP_ICON_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    /** Up to this size the logo is condensed for a title bar rather than shrunk. */
    private static final int TITLE_BAR_ICON_MAX = 48;

    /**
     * The largest icon handed to WSLg. It puts the largest of the list in the
     * title bar and Windows shrinks it there: 24 is a title bar's icon at 150 %,
     * and shrinks to the 16 and 20 of 100 and 125 % without losing the logo.
     */
    private static final int WSL_ICON_MAX = 24;

    private static java.util.List<Image> appIconImages;
    private static boolean appIconMissingReported;

    /**
     * The application icon in every size a window manager may ask for. Handing over
     * a single 256 pixel image leaves the system to shrink it to 16, which it does
     * in one crude step; these are reduced by halving, so the small ones stay sharp.
     *
     * The sizes a title bar shows are condensed instead: shrunk plainly, the logo
     * is only a dark disk there. Under WSL the list stops at a title bar's size,
     * because WSLg puts the largest image of the list in the title bar and leaves
     * Windows to shrink it, which brings the dark disk back.
     */
    public static synchronized java.util.List<Image> getAppIconImages() {
        if (appIconImages != null) {
            return appIconImages;
        }
        BufferedImage master = readAppIcon();
        if (master == null) {
            // Not kept: an empty list kept here left every window of the session
            // without the logo, while the next window may well find the file.
            if (!appIconMissingReported) {
                appIconMissingReported = true;
                AppLogger.error("Application icon " + APP_ICON + " not found: windows keep the Java default.");
            }
            return java.util.List.of();
        }
        boolean wsl = OSValidator.isWsl();
        java.util.List<Image> images = new java.util.ArrayList<>();
        for (int size : APP_ICON_SIZES) {
            if (wsl && size > WSL_ICON_MAX) {
                break;
            }
            if (size <= master.getWidth() || size == APP_ICON_SIZES[0]) {
                images.add(size <= TITLE_BAR_ICON_MAX ? condense(master, size) : resample(master, size));
            }
        }
        if (images.isEmpty()) {
            images.add(master);
        }
        appIconImages = java.util.List.copyOf(images);
        return appIconImages;
    }

    /**
     * sphere_logo.png itself, never a stand-in: loadIcon answers default.png for a
     * file it cannot find, and a window carrying that has lost the logo all the
     * same. Read whole with ImageIO, from the jar first, then from the source and
     * build folders for a run outside the jar. Not through the icon cache either:
     * it stretches icons for HiDPI screens, and reducing a stretched image only
     * softens it.
     */
    private static BufferedImage readAppIcon() {
        try (java.io.InputStream packed = IconManager.class.getResourceAsStream(RESOURCE_PATH + APP_ICON)) {
            if (packed != null) {
                BufferedImage image = javax.imageio.ImageIO.read(packed);
                if (image != null) {
                    return toBufferedImage(image);
                }
            }
        } catch (java.io.IOException | RuntimeException unreadable) {
            // the folders below
        }
        for (String folder : new String[] {BASE_PATH, "bin/com/sphere/icons/"}) {
            File file = new File(folder + APP_ICON);
            if (!file.isFile()) {
                continue;
            }
            try {
                BufferedImage image = javax.imageio.ImageIO.read(file);
                if (image != null) {
                    return toBufferedImage(image);
                }
            } catch (java.io.IOException | RuntimeException unreadable) {
                // the next folder
            }
        }
        return null;
    }

    /**
     * Puts the application icon on one window and on the windows that own it.
     *
     * The owner matters as much as the window: a dialog opened with no parent
     * is owned by a hidden frame Swing shares between all of them, and the task
     * bar takes its entry from that frame. It is never opened, so nothing that
     * waits for a window to open ever reaches it.
     */
    public static void applyAppIcon(Window window) {
        java.util.List<Image> images = getAppIconImages();
        if (images.isEmpty()) {
            return;
        }
        for (Window w = window; w != null; w = w.getOwner()) {
            // The images themselves, not their count: a window holding any other
            // list of nine images would otherwise be left with it.
            if (!images.equals(w.getIconImages())) {
                w.setIconImages(images);
            }
        }
    }

    /**
     * Installs the icon for the whole application: the dock entry where the system
     * has one, then every window as it opens. A listener covers windows created
     * anywhere, including the dialogs built inline, and any added later.
     */
    public static void installApplicationIcon() {
        java.util.List<Image> images = getAppIconImages();

        // macOS and some Linux shells take the dock icon from here, not from the
        // window: setIconImage alone leaves the Dock showing the Java default.
        try {
            if (!images.isEmpty() && Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.setIconImage(images.get(images.size() - 1));
                }
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // no dock on this platform
        }

        // The listener goes in even when the logo could not be read just now:
        // returning early here left every window bare for the whole session.
        for (Window window : Window.getWindows()) {
            applyAppIcon(window);
        }

        // A window is given its icon while it is being built, not when it opens.
        // Waiting for WINDOW_OPENED means the window is already on screen: the
        // manager maps it bare, and the ones that read the icon once keep the
        // bare version for the life of the window. Every JFrame and JDialog adds
        // its root pane in its constructor, which is this container event, so the
        // icon is in place before anyone can see the window.
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            // Only real windows: heavyweight popups and tooltips are Windows too,
            // and giving them an icon serves nothing.
            if (!(event.getSource() instanceof Window window)
                    || !(window instanceof Frame || window instanceof Dialog)) {
                return;
            }
            switch (event.getID()) {
                case java.awt.event.ContainerEvent.COMPONENT_ADDED,
                     java.awt.event.WindowEvent.WINDOW_OPENED,
                     java.awt.event.WindowEvent.WINDOW_ACTIVATED -> applyAppIcon(window);
                default -> { }
            }
        }, AWTEvent.CONTAINER_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK);
    }

    private static BufferedImage toBufferedImage(Image image) {
        if (image instanceof BufferedImage buffered
                && buffered.getType() == BufferedImage.TYPE_INT_ARGB) {
            return buffered;
        }
        int w = Math.max(1, image.getWidth(null));
        int h = Math.max(1, image.getHeight(null));
        BufferedImage copy = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = copy.createGraphics();
        g2.drawImage(image, 0, 0, null);
        g2.dispose();
        return copy;
    }

    /**
     * The logo at a title bar's size. There its tracks, rings and segments are
     * narrower than a pixel, and an average over the patch of logo each pixel
     * covers lets the dark disk swallow them. Each pixel is put halfway between
     * that average and the most vivid color of its patch, so every stroke keeps
     * the pixels it crosses and the logo stays readable on a light or dark bar.
     */
    private static BufferedImage condense(BufferedImage source, int size) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);
        double stepX = (double) width / size;
        double stepY = (double) height / size;
        BufferedImage target = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int ty = 0; ty < size; ty++) {
            int y0 = (int) (ty * stepY);
            int y1 = Math.min(height, (int) Math.ceil((ty + 1) * stepY));
            for (int tx = 0; tx < size; tx++) {
                int x0 = (int) (tx * stepX);
                int x1 = Math.min(width, (int) Math.ceil((tx + 1) * stepX));
                double alpha = 0, red = 0, green = 0, blue = 0, best = -1;
                int vivid = 0, count = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int c = pixels[y * width + x];
                        int a = c >>> 24, r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                        alpha += a;
                        red += r * a;
                        green += g * a;
                        blue += b * a;
                        count++;
                        // Color counts as well as light: the orange segments are
                        // no lighter than the blue rings, only more saturated.
                        int max = Math.max(r, Math.max(g, b));
                        int min = Math.min(r, Math.min(g, b));
                        double score = (0.2126 * r + 0.7152 * g + 0.0722 * b + 0.5 * (max - min)) * a;
                        if (score > best) {
                            best = score;
                            vivid = c;
                        }
                    }
                }
                if (alpha < count) {
                    continue; // less than one step of opacity on average: left clear
                }
                long a = Math.round(alpha / count);
                long r = Math.round((red / alpha + ((vivid >> 16) & 0xFF)) / 2);
                long g = Math.round((green / alpha + ((vivid >> 8) & 0xFF)) / 2);
                long b = Math.round((blue / alpha + (vivid & 0xFF)) / 2);
                target.setRGB(tx, ty, (int) ((a << 24) | (r << 16) | (g << 8) | b));
            }
        }
        return target;
    }

    /** Halves the image until the target is close, then lands on it exactly. */
    private static BufferedImage resample(BufferedImage source, int size) {
        BufferedImage current = source;
        int width = current.getWidth();
        while (width / 2 > size) {
            width /= 2;
            current = draw(current, width);
        }
        return draw(current, size);
    }

    private static BufferedImage draw(BufferedImage source, int size) {
        BufferedImage target = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = target.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                            RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
        g2.drawImage(source, 0, 0, size, size, null);
        g2.dispose();
        return target;
    }
}
