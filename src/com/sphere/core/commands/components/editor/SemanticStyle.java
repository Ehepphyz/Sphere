package com.sphere.components.editor;

import com.sphere.theme.Contrast;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How a resolved name looks: the hue says what it is, the face how it is bound,
 * the strokes how it is used, and a cap over it the family of the value the
 * kernel holds under it.
 *
 *   hue    -- data in blues (global variable, paler for a local, greyer for an
 *             attribute), inputs in violet (parameters, keyword arguments),
 *             self in the keyword blue, callables in yellow, types in teal,
 *             modules in amber, constants in azure;
 *   face   -- bold where the name is defined, italic for what a function
 *             receives (parameters, keyword arguments, self, type parameters);
 *   stroke -- underlined when it is bound more than once, dashed when it hides
 *             a builtin, dotted when the kernel's value is stale, faded when it
 *             is never read;
 *   chip   -- a translucent chip behind a live variable in its family's color:
 *             tensor orange, array cyan, data frame green, jagged array magenta,
 *             histogram gold, ROOT red, model violet, figure pink. (A bar over
 *             the name was tried first: with lines set tight it read as the
 *             underline of the line above.)
 *
 * Every color is solved against the current background, like the token colors:
 * readable (WCAG AA), and apart from the colors already on the surface.
 */
public final class SemanticStyle {

    /** Underlined: bound more than once. */
    public static final int D_UNDERLINE = 1;
    /** Dashed in the warning color: hides a builtin. */
    public static final int D_DASHED = 1 << 1;
    /** Dotted in amber: the kernel's value was computed from one that changed since. */
    public static final int D_DOTTED = 1 << 2;
    /** A chip behind the name in its runtime family's color (Look.cap is its translucent fill). */
    public static final int D_CAP = 1 << 3;
    /** Faint dots: no cell, builtin or kernel value defines it. */
    public static final int D_UNRESOLVED = 1 << 4;

    /** Everything the painter needs for one packed role. */
    public record Look(Color color, int fontStyle, int decorations, Color cap) {
    }

    private SemanticStyle() {
    }

    // ---- Seeds, stated for a dark surface ----------------------------------

    private static final Color SEED_LOCAL     = new Color(206, 226, 242);
    private static final Color SEED_PARAMETER = new Color(198, 160, 255);
    private static final Color SEED_SELF      = new Color( 86, 156, 214);
    private static final Color SEED_ATTRIBUTE = new Color(166, 182, 202);
    private static final Color SEED_STALE     = new Color(255, 166,  41);

    private static final Color[] FAMILY_SEEDS = new Color[PySemantics.Family.values().length];

    static {
        FAMILY_SEEDS[PySemantics.Family.NONE.ordinal()]       = new Color(180, 184, 192);
        FAMILY_SEEDS[PySemantics.Family.SCALAR.ordinal()]     = new Color(181, 206, 168);
        FAMILY_SEEDS[PySemantics.Family.TEXT.ordinal()]       = new Color(206, 145, 120);
        FAMILY_SEEDS[PySemantics.Family.COLLECTION.ordinal()] = new Color(143, 163, 191);
        FAMILY_SEEDS[PySemantics.Family.ARRAY.ordinal()]      = new Color( 61, 214, 255);
        FAMILY_SEEDS[PySemantics.Family.TENSOR.ordinal()]     = new Color(255, 145,  64);
        FAMILY_SEEDS[PySemantics.Family.FRAME.ordinal()]      = new Color( 82, 210, 115);
        FAMILY_SEEDS[PySemantics.Family.JAGGED.ordinal()]     = new Color(224,  92, 255);
        FAMILY_SEEDS[PySemantics.Family.HIST.ordinal()]       = new Color(255, 213,  74);
        FAMILY_SEEDS[PySemantics.Family.ROOT.ordinal()]       = new Color(255,  92,  92);
        FAMILY_SEEDS[PySemantics.Family.MODEL.ordinal()]      = new Color(157, 124, 255);
        FAMILY_SEEDS[PySemantics.Family.FIGURE.ordinal()]     = new Color(255, 111, 174);
        FAMILY_SEEDS[PySemantics.Family.OBJECT.ordinal()]     = new Color(180, 184, 192);
    }

    /** Below this OKLab distance a derived color is pulled away from the ones on the surface. */
    private static final double DISTINCT = 0.055;
    /** How far an unused name fades toward the background. */
    private static final float FADE_UNUSED = 0.48f;
    /** Less for a notebook's global: a result kept to look at in the Variables tab is not dead code. */
    private static final float FADE_UNUSED_GLOBAL = 0.30f;

    // ---- Solved colors, per background ---------------------------------------

    private static volatile Color solvedFor;
    private static volatile Color[] derived;
    private static volatile Color[] families;
    private static final Map<Integer, Look> LOOKS = new ConcurrentHashMap<>();

    private static void ensure() {
        Color bg = EditorTheme.background();
        if (bg.equals(solvedFor) && derived != null) {
            return;
        }
        synchronized (SemanticStyle.class) {
            if (bg.equals(solvedFor) && derived != null) {
                return;
            }
            List<Color> avoid = new ArrayList<>();
            for (TokenKind k : new TokenKind[] { TokenKind.KEYWORD, TokenKind.TYPE, TokenKind.FUNCTION, TokenKind.NUMBER,
                                                 TokenKind.STRING, TokenKind.COMMENT, TokenKind.VARIABLE,
                                                 TokenKind.CONSTANT, TokenKind.NAMESPACE, TokenKind.OPERATOR }) {
                avoid.add(EditorTheme.token(k));
            }
            Color[] d = new Color[5];
            Color[] seeds = { SEED_LOCAL, SEED_PARAMETER, SEED_SELF, SEED_ATTRIBUTE, SEED_STALE };
            for (int i = 0; i < seeds.length; i++) {
                d[i] = solve(seeds[i], avoid, bg);
                avoid.add(d[i]);
            }
            Color[] f = new Color[FAMILY_SEEDS.length];
            for (int i = 0; i < f.length; i++) {
                f[i] = Contrast.readable(FAMILY_SEEDS[i], bg, 3.0);
            }
            LOOKS.clear();
            derived = d;
            families = f;
            solvedFor = bg;
        }
    }

    /**
     * The seed made readable on bg, then moved in lightness, away from bg (which
     * only raises its contrast), until it stands apart from the colors in avoid.
     */
    private static Color solve(Color seed, List<Color> avoid, Color bg) {
        Color c = Contrast.readable(seed, bg, Contrast.AA);
        double[] lab = Contrast.oklab(seed);
        double lightness = Contrast.oklab(c)[0];
        double away = Contrast.ratio(Color.WHITE, bg) >= Contrast.ratio(Color.BLACK, bg) ? 1 : -1;
        for (int step = 0; step < 40; step++) {
            double closest = Double.MAX_VALUE;
            for (Color a : avoid) {
                closest = Math.min(closest, Contrast.deltaE(c, a));
            }
            if (closest >= DISTINCT) {
                break;
            }
            double next = lightness + away * 0.01;
            if (next < 0.05 || next > 0.98) {
                break;
            }
            lightness = next;
            c = Contrast.fromOklab(lightness, lab[1], lab[2]);
        }
        return c;
    }

    // ---- Public colors ---------------------------------------------------------

    /** The hue of a role on the current surface. */
    public static Color roleColor(PySemantics.Role role) {
        ensure();
        switch (role) {
            case VARIABLE:     return EditorTheme.token(TokenKind.VARIABLE);
            case LOCAL:        return derived[0];
            case PARAMETER:
            case KWARG:        return derived[1];
            case SELF:         return derived[2];
            case ATTRIBUTE:
            case FIELD:        return derived[3];
            case FUNCTION:
            case METHOD:
            case BUILTIN:      return EditorTheme.token(TokenKind.FUNCTION);
            case CLASS:
            case BUILTIN_TYPE:
            case EXCEPTION:
            case TYPE_PARAM:   return EditorTheme.token(TokenKind.TYPE);
            case MODULE:       return EditorTheme.token(TokenKind.NAMESPACE);
            case CONSTANT:     return EditorTheme.token(TokenKind.CONSTANT);
            default:           return EditorTheme.foreground();
        }
    }

    /** The color of a runtime family, as caps and the legend show it. */
    public static Color familyColor(PySemantics.Family family) {
        ensure();
        return families[family.ordinal()];
    }

    /** The dots under a stale name. */
    public static Color staleColor() {
        ensure();
        return derived[4];
    }

    /** The font style a packed role is drawn in. */
    public static int fontStyle(int sem) {
        PySemantics.Role r = PySemantics.role(sem);
        int style = Font.PLAIN;
        if (PySemantics.has(sem, PySemantics.DEF)) {
            style |= Font.BOLD;
        }
        if (r == PySemantics.Role.PARAMETER || r == PySemantics.Role.KWARG || r == PySemantics.Role.SELF
            || r == PySemantics.Role.TYPE_PARAM) {
            style |= Font.ITALIC;
        }
        return style;
    }

    /** How a packed role is drawn on the current surface; cached until the background changes. */
    public static Look look(int sem) {
        ensure();
        Look l = LOOKS.get(sem);
        if (l == null) {
            l = compute(sem);
            LOOKS.put(sem, l);
        }
        return l;
    }

    private static Look compute(int sem) {
        PySemantics.Role role = PySemantics.role(sem);
        Color color = roleColor(role);
        if (PySemantics.has(sem, PySemantics.UNUSED)) {
            float fade = role == PySemantics.Role.VARIABLE || role == PySemantics.Role.CONSTANT
                         ? FADE_UNUSED_GLOBAL : FADE_UNUSED;
            color = EditorTheme.mix(color, EditorTheme.background(), 1f - fade);
        }
        int deco = 0;
        if (PySemantics.has(sem, PySemantics.REASSIGNED)) {
            deco |= D_UNDERLINE;
        }
        if (PySemantics.has(sem, PySemantics.SHADOWS)) {
            deco |= D_DASHED;
        }
        if (PySemantics.has(sem, PySemantics.STALE)) {
            deco |= D_DOTTED;
        }
        if (PySemantics.has(sem, PySemantics.UNRESOLVED)) {
            deco |= D_UNRESOLVED;
        }
        PySemantics.Family family = PySemantics.family(sem);
        Color cap = null;
        if (family.isMarked() && role.isData()) {
            deco |= D_CAP;
            cap = EditorTheme.alpha(familyColor(family), EditorTheme.isDarkSurface() ? 58 : 46);
        }
        return new Look(color, fontStyle(sem), deco, cap);
    }
}
