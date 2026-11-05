package com.sphere.fonts;

import com.sphere.utils.OSValidator;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized High-Velocity Font Loading and Typography Subsystem for Sphere.
 * Implements strict functional isolation for standard user interfaces, developer terminals,
 * and high-fidelity mathematical data displays with comprehensive native Greek and math symbol support.
 */
public class FontLoader {

    // =========================================================================
    // WINDOWS FONT ASSETS (Custom Proportional UI & Monospace Suites)
    // =========================================================================
    // FIXED: Swapped core Windows UI hooks to Inter to perfectly match the clean proportional layout of Linux
    public static final String WIN_UI_FONT_R         = "Inter-Regular.ttf";
    public static final String WIN_UI_FONT_B         = "Inter-Bold.ttf";
    public static final String WIN_UI_FONT_I         = "Inter-Italic.ttf";
    public static final String WIN_UI_FONT_BI        = "Inter-BoldItalic.ttf";

    public static final String WIN_TERMINAL_FONT_R  = "NotoSansMono-Regular.ttf";
    public static final String WIN_TERMINAL_FONT_B  = "NotoSansMono-Bold.ttf";

    public static final String WIN_MATH_FONT_R      = "NotoSansMono-Regular.ttf";
    public static final String WIN_MATH_FONT_B      = "NotoSansMono-Bold.ttf";

    public static final String WIN_ACCENT_FONT_B    = "NotoSansMono-Bold.ttf";

    // =========================================================================
    // LINUX FONT ASSETS (Inter & Noto Sans Mono Functional Suite)
    // =========================================================================
    public static final String LINUX_UI_FONT_R      = "Inter-Regular.ttf";
    public static final String LINUX_UI_FONT_B      = "Inter-Bold.ttf";
    public static final String LINUX_UI_FONT_I      = "Inter-Italic.ttf";
    public static final String LINUX_UI_FONT_BI     = "Inter-BoldItalic.ttf";

    public static final String LINUX_TERMINAL_FONT_R = "NotoSansMono-Regular.ttf";
    public static final String LINUX_TERMINAL_FONT_B = "NotoSansMono-Bold.ttf";

    public static final String LINUX_MATH_FONT_R     = "NotoSansMono-Regular.ttf";
    public static final String LINUX_MATH_FONT_B     = "NotoSansMono-Bold.ttf";

    public static final String LINUX_ACCENT_FONT_B   = "NotoSansMono-Bold.ttf";

    /* -------------------------------------------------------------------------
     * Global Accessor Pipelines
     * ------------------------------------------------------------------------- */

    /**
     * Retrieves the proportional UI layout font for structural components (Menus, Buttons, Lists).
     * Applies a 1.15x metric multiplier for Linux to balance point-to-pixel scaling.
     */
    public static Font getGlobalFont(int style, int size) {
        boolean isLinux = OSValidator.isLinux();
        int finalSize = isLinux ? Math.round(size * 1.15f) : size;
        
        String fontFile = isLinux ? LINUX_UI_FONT_R : WIN_UI_FONT_R;
        if (style == (Font.BOLD | Font.ITALIC)) {
            fontFile = isLinux ? LINUX_UI_FONT_BI : WIN_UI_FONT_BI;
        } else if (style == Font.BOLD) {
            fontFile = isLinux ? LINUX_UI_FONT_B : WIN_UI_FONT_B;
        } else if (style == Font.ITALIC) {
            fontFile = isLinux ? LINUX_UI_FONT_I : WIN_UI_FONT_I;
        }
        return loadEngineFont(fontFile, (float) finalSize, style);
    }

    /**
     * Retrieves the monospace programming font specialized for developer logs, inputs, and shell displays.
     */
    public static Font getTerminalFont(int style, int size) {
        boolean isLinux = OSValidator.isLinux();
        int finalSize = isLinux ? Math.round(size * 1.15f) : size;
        
        String fontFile = isLinux ? ((style == Font.BOLD) ? LINUX_TERMINAL_FONT_B : LINUX_TERMINAL_FONT_R)
                                  : ((style == Font.BOLD) ? WIN_TERMINAL_FONT_B : WIN_TERMINAL_FONT_R);
        return loadEngineFont(fontFile, (float) finalSize, style);
    }

    /**
     * Retrieves the dedicated mathematical font optimized for formulas, matrix grids, and scientific notation.
     */
    public static Font getMathFont(int style, int size) {
        boolean isLinux = OSValidator.isLinux();
        int finalSize = isLinux ? Math.round(size * 1.15f) : size;
        
        String fontFile = isLinux ? ((style == Font.BOLD) ? LINUX_MATH_FONT_B : LINUX_MATH_FONT_R)
                                  : ((style == Font.BOLD) ? WIN_MATH_FONT_B : WIN_MATH_FONT_R);
        return loadEngineFont(fontFile, (float) finalSize, style);
    }

    /**
     * Centralized accessor for status bar highlights, badges, and metric dashboards tags.
     */
    public static Font getAccentFont(int style, int size) {
        boolean isLinux = OSValidator.isLinux();
        int finalSize = isLinux ? Math.round(size * 1.15f) : size;
        
        String fontFile = isLinux ? LINUX_ACCENT_FONT_B : WIN_ACCENT_FONT_B;
        return loadEngineFont(fontFile, (float) finalSize, style);
    }

    /* -------------------------------------------------------------------------
     * Core Binary Stream File Loader
     * ------------------------------------------------------------------------- */

    /**
     * Each font file read once. Reading one is costly: Font.createFont copies
     * the stream into a temporary file and parses it, and the font is then
     * registered with the graphics environment. Callers ask for fonts while
     * painting (a list renderer asks for one per cell it draws), so doing
     * that on every call froze the interface as soon as a list was long.
     */
    private static final Map<String, Font> BASE = new ConcurrentHashMap<>();
    /** The styles and sizes handed out, by file, style and size (fonts are immutable, so they are shared). */
    private static final Map<String, Font> DERIVED = new ConcurrentHashMap<>();
    /** Stands for a file that could not be read, so it is not tried (and reported) again on every call. */
    private static final Font MISSING = new Font(Font.SANS_SERIF, Font.PLAIN, 1);

    /**
     * Traverses embedded resource paths and local src disk structures to build concrete fonts.
     * Fixed the blurry/fallback Linux bug by safely deriving directly from the loaded binary stream instance.
     * Each file is read once and each style and size derived once; later calls are a map lookup.
     */
    public static Font loadEngineFont(String fontFileName, float size, int style) {
        String key = fontFileName + '|' + style + '|' + Float.floatToIntBits(size);
        Font done = DERIVED.get(key);
        if (done != null) {
            return done;
        }
        Font base = BASE.get(fontFileName);
        if (base == null) {
            // read outside any lock of the map: reading may log, and logging may ask for a font in turn
            Font read = readFont(fontFileName);
            Font first = BASE.putIfAbsent(fontFileName, read);
            base = first != null ? first : read;
        }
        // FIXED: Bypassed baseFont.getFamily() under Linux which resolves to incorrect fallback aliases.
        // Calling deriveFont directly guarantees Java uses the true binary font file contents.
        Font font = base == MISSING ? new Font(Font.SANS_SERIF, style, (int) size) : base.deriveFont(style, size);
        if (DERIVED.size() > 1024) {
            // sizes asked for by a continuous zoom: the oldest go rather than the map growing for ever
            DERIVED.clear();
        }
        DERIVED.put(key, font);
        return font;
    }

    /** Reads a font file (from the jar, else from src/ while developing) and registers it; MISSING when it cannot. */
    private static Font readFont(String fontFileName) {
        String internalPath = "/com/sphere/fonts/ttf/" + fontFileName;
        try {
            Font baseFont = null;

            // 1. First Attempt: Standard internal JAR resource look-up (Safe Try-With-Resources)
            try (InputStream is = FontLoader.class.getResourceAsStream(internalPath)) {
                if (is != null) {
                    baseFont = Font.createFont(Font.TRUETYPE_FONT, is);
                }
            }

            // 2. Second Attempt: Fallback to local src directory structure for IDE development
            if (baseFont == null) {
                File localFile = new File("src/com/sphere/fonts/ttf/" + fontFileName);
                if (localFile.exists()) {
                    baseFont = Font.createFont(Font.TRUETYPE_FONT, localFile);
                }
            }

            if (baseFont != null) {
                // Register the raw font file directly into the local graphics subsystem context
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(baseFont);
                return baseFont;
            }

            // FIXED: Using Font.SANS_SERIF token instead of the arbitrary string literal "Dialog"
            // to bypass faulty host mapping tables and prevent NullPointerException crashes on headless environments.
            System.err.println("Font resource not found in JAR or local disk: " + fontFileName
                               + " -> Falling back to system default.");
            return MISSING;
        } catch (Exception e) {
            com.sphere.utils.AppLogger.error("Error loading font " + fontFileName + ": " + e.getMessage());
            // FIXED: Hard fallback routing onto raw native system constant to guarantee safe pipeline initialization
            return MISSING;
        }
    }
}
