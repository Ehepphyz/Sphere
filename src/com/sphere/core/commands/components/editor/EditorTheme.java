package com.sphere.components.editor;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;

import java.awt.Color;

/**
 * Single source of color for the code surface. Everything below the seed block
 * is derived, so moving one seed moves every color that depends on it instead
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
    private static final Color SEED_VARIABLE     = new Color(156, 220, 254);
    private static final Color SEED_CONSTANT     = new Color( 79, 193, 255);

    /** Bracket pairs by depth, the three hues VS Code settled on. */
    private static final Color SEED_BRACKET_1    = new Color(255, 215,   0);
    private static final Color SEED_BRACKET_2    = new Color(218, 112, 214);
    private static final Color SEED_BRACKET_3    = new Color( 23, 159, 255);

    /** Gutter change bars, as source control paints them. */
    private static final Color SEED_ADDED        = new Color( 46, 160,  67);
    private static final Color SEED_MODIFIED     = new Color( 32, 128, 214);
    private static final Color SEED_DELETED      = new Color(248,  81,  73);

    private static final Color SEED_FIND         = new Color(234, 140,  40);
    private static final Color SEED_LINK         = new Color( 78, 160, 255);
    private static final Color SEED_EXECUTION    = new Color(255, 204,   0);

    /** How far the derived tones move from their base, 0 to 1. */
    private static final float LIFT_CURRENT_LINE = 0.05f;
    private static final float FADE_GUTTER_TEXT  = 0.42f;
    private static final float FADE_BORDER       = 0.80f;

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

    /** The bright blue of focus rings and active indicators. */
    public static Color focus() {
        return palette().getTabEditorSelectBg();
    }

    public static Color selection() {
        Color s = palette().getTerminalSelection();
        return isDarkSurface() ? mix(s, background(), 0.62f) : mix(s, background(), 0.85f);
    }

    public static Color caret() {
        return isDarkSurface() ? new Color(174, 175, 173) : new Color(40, 40, 40);
    }

    /** True when the surface is dark, which decides how derived tones move. */
    public static boolean isDarkSurface() {
        return luminance(background()) < 0.5f;
    }

    // =======================================================================
    // Derived: the code surface
    // =======================================================================

    public static Color currentLine() {
        return shift(background(), isDarkSurface() ? LIFT_CURRENT_LINE : -0.035f);
    }

    public static Color currentLineBorder() {
        return shift(background(), isDarkSurface() ? 0.09f : -0.07f);
    }

    public static Color gutterBackground() {
        return background();
    }

    public static Color gutterForeground() {
        return mix(foreground(), background(), FADE_GUTTER_TEXT);
    }

    public static Color gutterCurrentForeground() {
        return mix(foreground(), background(), 0.92f);
    }

    public static Color gutterBorder() {
        return mix(foreground(), background(), 1f - FADE_BORDER);
    }

    public static Color bracketMatch() {
        return mix(foreground(), background(), 0.16f);
    }

    public static Color bracketMatchBorder() {
        return mix(foreground(), background(), 0.45f);
    }

    public static Color executionLine() {
        return mix(SEED_EXECUTION, background(), 0.16f);
    }

    public static Color executionArrow() {
        return SEED_EXECUTION;
    }

    public static Color indentGuide() {
        return mix(foreground(), background(), isDarkSurface() ? 0.15f : 0.16f);
    }

    public static Color indentGuideActive() {
        return mix(foreground(), background(), isDarkSurface() ? 0.40f : 0.42f);
    }

    public static Color occurrence() {
        return alpha(mix(foreground(), background(), 0.55f), isDarkSurface() ? 64 : 48);
    }

    public static Color occurrenceWrite() {
        return alpha(SEED_BRACKET_3, isDarkSurface() ? 48 : 40);
    }

    public static Color findMatch() {
        return alpha(SEED_FIND, isDarkSurface() ? 70 : 60);
    }

    public static Color findMatchCurrent() {
        return alpha(SEED_FIND, isDarkSurface() ? 150 : 120);
    }

    public static Color findMatchBorder() {
        return readable(SEED_FIND);
    }

    public static Color link() {
        return readable(SEED_LINK);
    }

    public static Color placeholder() {
        return alpha(focus(), 46);
    }

    public static Color placeholderBorder() {
        return alpha(focus(), 170);
    }

    public static Color whitespace() {
        return mix(foreground(), background(), 0.20f);
    }

    public static Color watermark() {
        return mix(foreground(), background(), 0.30f);
    }

    public static Color added() {
        return SEED_ADDED;
    }

    public static Color modified() {
        return SEED_MODIFIED;
    }

    public static Color deleted() {
        return SEED_DELETED;
    }

    // ---- Markers: severity colors come from the palette, so an application
    // ---- wide change of "error red" reaches the editor too.

    public static Color errorSquiggle() {
        return palette().getError();
    }

    public static Color warningSquiggle() {
        return palette().getLogWarnPrefix();
    }

    public static Color infoSquiggle() {
        return palette().getLogInfoPrefix();
    }

    public static Color breakpoint() {
        return new Color(229, 20, 0);
    }

    public static Color breakpointDisabled() {
        return mix(breakpoint(), background(), 0.45f);
    }

    // =======================================================================
    // Derived: chrome around the code (tabs, bars, popups)
    // =======================================================================

    /** The tab strip and the status bar, a step below the code surface. */
    public static Color chrome() {
        return shift(background(), isDarkSurface() ? -0.26f : -0.045f);
    }

    public static Color chromeBorder() {
        return mix(foreground(), chrome(), isDarkSurface() ? 0.09f : 0.12f);
    }

    public static Color chromeForeground() {
        return mix(foreground(), chrome(), 0.62f);
    }

    public static Color chromeHover() {
        return shift(chrome(), isDarkSurface() ? 0.07f : -0.05f);
    }

    public static Color tabInactive() {
        return mix(background(), chrome(), 0.35f);
    }

    public static Color tabHover() {
        return mix(background(), chrome(), 0.70f);
    }

    public static Color textMuted() {
        return mix(foreground(), background(), 0.55f);
    }

    public static Color textFaint() {
        return mix(foreground(), background(), 0.38f);
    }

    /** Floating widgets: find, palette, completion. */
    public static Color widgetBackground() {
        return isDarkSurface() ? shift(background(), 0.075f) : shift(background(), 0.6f);
    }

    public static Color widgetBorder() {
        return mix(foreground(), widgetBackground(), isDarkSurface() ? 0.14f : 0.16f);
    }

    public static Color widgetInput() {
        return isDarkSurface() ? shift(background(), 0.02f) : Color.WHITE;
    }

    public static Color widgetHover() {
        return mix(foreground(), widgetBackground(), 0.08f);
    }

    public static Color widgetSelection() {
        return isDarkSurface() ? mix(focus(), widgetBackground(), 0.42f) : mix(focus(), widgetBackground(), 0.20f);
    }

    public static Color shadow() {
        return new Color(0, 0, 0, isDarkSurface() ? 110 : 45);
    }

    public static Color minimapSlider(int state) {
        int a = state == 2 ? 58 : state == 1 ? 40 : 24;
        return alpha(foreground(), isDarkSurface() ? a : a + 6);
    }

    // ---- Popups

    public static Color popupBackground() {
        return widgetBackground();
    }

    public static Color popupBorder() {
        return widgetBorder();
    }

    public static Color popupSelection() {
        return widgetSelection();
    }

    // ---- Languages

    /**
     * The color a language is known by on its tab. Markdown and LaTeX keep the
     * palette's mode colors, which they wore before tabs were colored by language.
     */
    public static Color languageColor(LanguageSpec spec) {
        if (spec == null) {
            spec = LanguageSpec.NONE;
        }
        if (spec == LanguageSpec.MARKDOWN) {
            return palette().getEdBorderMarkd();
        }
        if (spec == LanguageSpec.LATEX) {
            return palette().getEdBorderLatex();
        }
        return readable(spec.color);
    }

    /** Dark or light text, whichever reads on a fill. */
    public static Color onColor(Color fill) {
        return luminance(fill) > 0.55f ? new Color(24, 24, 28) : Color.WHITE;
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
            case VARIABLE:     return readable(SEED_VARIABLE);
            case CONSTANT:     return readable(SEED_CONSTANT);
            case BRACKET_1:    return readable(SEED_BRACKET_1);
            case BRACKET_2:    return readable(SEED_BRACKET_2);
            case BRACKET_3:    return readable(SEED_BRACKET_3);
            case BRACKET_BAD:  return errorSquiggle();
            case DEFAULT:
            default:           return foreground();
        }
    }

    // =======================================================================
    // Color arithmetic
    // =======================================================================

    /** Darkens a seed stated for a dark surface when the surface turns light. */
    public static Color readable(Color seed) {
        return isDarkSurface() ? seed : shift(seed, -0.45f);
    }

    /** Moves a color toward white for a positive amount, toward black otherwise. */
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

    public static float luminance(Color c) {
        return (0.2126f * c.getRed() + 0.7152f * c.getGreen() + 0.0722f * c.getBlue()) / 255f;
    }

    private static float clamp(float v) {
        float a = Math.abs(v);
        return a > 1f ? 1f : a;
    }
}
