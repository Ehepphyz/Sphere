package com.sphere.components.editor;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;

import java.awt.Color;

/**
 * Single source of colour for the code surface. Everything below the seed block
 * is derived, so moving one seed moves every colour that depends on it instead
 * of leaving the rest behind.
 */
public final class EditorTheme {

    private EditorTheme() {
    }

    // =======================================================================
    // Seeds. This block is the only place to edit.
    // =======================================================================

    /** Set to override the palette's background; null follows the application theme. */
    private static Color backgroundOverride = null;

    /** Token hues, stated for a dark surface and rebalanced when it is light. */
    private static final Color SEED_KEYWORD      = new Color(197, 134, 192);
    private static final Color SEED_TYPE         = new Color( 78, 201, 176);
    private static final Color SEED_FUNCTION     = new Color(220, 220, 170);
    private static final Color SEED_NUMBER       = new Color(181, 206, 168);
    private static final Color SEED_STRING       = new Color(206, 145, 120);
    private static final Color SEED_COMMENT      = new Color(106, 153,  85);
    private static final Color SEED_PREPROCESSOR = new Color(155, 155, 214);
    private static final Color SEED_OPERATOR     = new Color(180, 184, 192);

    /** How far the derived tones move from their base, 0 to 1. */
    private static final float LIFT_CURRENT_LINE = 0.05f;
    private static final float DROP_GUTTER       = 0.35f;
    private static final float FADE_GUTTER_TEXT  = 0.45f;
    private static final float FADE_BORDER       = 0.75f;

    // =======================================================================
    // Base, taken from the application palette so the editor follows the theme
    // =======================================================================

    private static ThemePalette palette() {
        return ThemeManager.getCurrentPalette();
    }

    public static Color background() {
        return backgroundOverride != null ? backgroundOverride : palette().getTerminalBackground();
    }

    public static void setBackgroundOverride(Color color) {
        backgroundOverride = color;
    }

    public static Color foreground() {
        return palette().getTerminalForeground();
    }

    public static Color accent() {
        return palette().getAccent();
    }

    public static Color selection() {
        return palette().getTerminalSelection();
    }

    public static Color caret() {
        return foreground();
    }

    /** True when the surface is dark, which decides how derived tones move. */
    public static boolean isDarkSurface() {
        return luminance(background()) < 0.5f;
    }

    // =======================================================================
    // Derived
    // =======================================================================

    public static Color currentLine() {
        return shift(background(), LIFT_CURRENT_LINE);
    }

    public static Color gutterBackground() {
        return shift(background(), -DROP_GUTTER * 0.35f);
    }

    public static Color gutterForeground() {
        return mix(foreground(), background(), FADE_GUTTER_TEXT);
    }

    public static Color gutterCurrentForeground() {
        return foreground();
    }

    public static Color gutterBorder() {
        return mix(foreground(), background(), FADE_BORDER);
    }

    public static Color bracketMatch() {
        return mix(accent(), background(), 0.35f);
    }

    public static Color executionLine() {
        return mix(new Color(120, 150, 60), background(), 0.72f);
    }

    // ---- Markers: severity colours come from the palette, so an application
    // ---- wide change of "error red" reaches the editor too.

    public static Color errorSquiggle() {
        return palette().getError();
    }

    public static Color warningSquiggle() {
        return palette().getLogWarnPrefix();
    }

    public static Color infoSquiggle() {
        return palette().getAccent();
    }

    public static Color breakpoint() {
        return palette().getError();
    }

    public static Color breakpointDisabled() {
        return mix(palette().getError(), background(), 0.55f);
    }

    // ---- Popups

    public static Color popupBackground() {
        return palette().getPopupBackground();
    }

    public static Color popupBorder() {
        return palette().getPopupBorder();
    }

    public static Color popupSelection() {
        return mix(accent(), popupBackground(), 0.25f);
    }

    // ---- Tokens

    public static Color token(TokenKind kind) {
        switch (kind) {
            case KEYWORD:      return readable(SEED_KEYWORD);
            case TYPE:         return readable(SEED_TYPE);
            case FUNCTION:     return readable(SEED_FUNCTION);
            case DECORATOR:    return readable(SEED_FUNCTION);
            case NUMBER:       return readable(SEED_NUMBER);
            case STRING:       return readable(SEED_STRING);
            case CHARACTER:    return readable(SEED_STRING);
            case COMMENT:      return readable(SEED_COMMENT);
            case PREPROCESSOR: return readable(SEED_PREPROCESSOR);
            case OPERATOR:     return readable(SEED_OPERATOR);
            case DEFAULT:
            default:           return foreground();
        }
    }

    // =======================================================================
    // Colour arithmetic
    // =======================================================================

    /** Darkens a seed stated for a dark surface when the surface turns light. */
    private static Color readable(Color seed) {
        return isDarkSurface() ? seed : shift(seed, -0.45f);
    }

    /** Moves a colour toward white for a positive amount, toward black otherwise. */
    public static Color shift(Color base, float amount) {
        Color pole = amount >= 0f ? Color.WHITE : Color.BLACK;
        return mix(pole, base, clamp(amount));
    }

    /** ratio 0 returns b, ratio 1 returns a. */
    public static Color mix(Color a, Color b, float ratio) {
        float r = clamp(ratio);
        return new Color(
                Math.round(a.getRed()   * r + b.getRed()   * (1f - r)),
                Math.round(a.getGreen() * r + b.getGreen() * (1f - r)),
                Math.round(a.getBlue()  * r + b.getBlue()  * (1f - r)));
    }

    public static Color alpha(Color base, int a) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(),
                         Math.max(0, Math.min(255, a)));
    }

    private static float luminance(Color c) {
        return (0.2126f * c.getRed() + 0.7152f * c.getGreen() + 0.0722f * c.getBlue()) / 255f;
    }

    private static float clamp(float v) {
        float a = Math.abs(v);
        return a > 1f ? 1f : a;
    }
}
