package com.sphere.theme;

import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Applies UIManager defaults for a given theme palette.
 * Acts as the engine bridge, implementing a hybrid caching system
 * for seamless runtime theme swapping.
 */
public final class ThemeDefaults {

    // Cache to prevent object recreation during dynamic updates
    private static final Map<String, javax.swing.Painter<JComponent>> painterCache = new HashMap<>();

    private ThemeDefaults() {}

    /**
     * Retrieves a cached painter instance or creates a new one if missing.
     */
    @SuppressWarnings("unchecked")
    private static <T extends javax.swing.Painter<JComponent>> T getOrCreatePainter(
            String key,
            java.util.function.Supplier<T> supplier
    ) {
        return (T) painterCache.computeIfAbsent(key, k -> supplier.get());
    }

    /**
     * Clears the internal painter cache context.
     */
    public static void clearCache() {
        painterCache.clear();
    }

    /**
     * Utility method to hard-lock a UI property across all Swing contexts
     * to prevent Nimbus from dropping values during look-and-feel cache resets.
     */
    private static void forcePut(UIDefaults lookAndFeelDefaults, String key, Object value) {
        UIManager.put(key, value);
        UIManager.getDefaults().put(key, value);
        lookAndFeelDefaults.put(key, value);
    }

    public static void apply(ThemePalette palette) {

        // Clear old painter instances before populating new ones to prevent memory leaks
        clearCache();

        UIDefaults d = UIManager.getLookAndFeelDefaults();

        Color bgMain    = palette.getBackgroundMain();
        Color bgSurface = palette.getBackgroundSurface();
        Color bgTrack   = palette.getBackgroundTrack();
        Color border    = palette.getBorder();
        Color text      = palette.getTextPrimary();
        Color accent    = palette.getAccent();
        Color btnBase   = palette.getButtonBase();
        Color btnHover  = palette.getButtonHover();
        Color btnPress  = palette.getButtonPressed();
        Color thumb     = palette.getScrollbarThumb();

        ColorUIResource resBg      = new ColorUIResource(bgMain);
        ColorUIResource resSurface = new ColorUIResource(bgSurface);
        ColorUIResource resTrack   = new ColorUIResource(bgTrack);
        ColorUIResource resText    = new ColorUIResource(text);
        ColorUIResource resWhite   = new ColorUIResource(Color.WHITE);

        // Core Nimbus tokens locking
        forcePut(d, "control", bgMain);
        forcePut(d, "nimbusBase", bgMain);
        forcePut(d, "nimbusBlueGrey", border);
        forcePut(d, "nimbusLightBackground", bgSurface);
        forcePut(d, "text", text);
        forcePut(d, "nimbusSelectedText", Color.WHITE);
        forcePut(d, "nimbusSelectionBackground", accent);

        // Global text foregrounds configuration
        String[] fgComps = {
                "Label", "CheckBox", "RadioButton", "Button", "ToggleButton",
                "Table", "TableHeader", "Tree", "List", "Panel", "FileChooser", "ComboBox"
        };
        for (String c : fgComps) {
            forcePut(d, c + ".foreground", resText);
            forcePut(d, c + "[Enabled].textForeground", resText);
        }

        // =================================================================
        // 1. DYNAMIC REGISTRATION SPUI DELEGATES
        // =================================================================
        forcePut(d, "ButtonUI", "com.sphere.ui.SPButtonUI");
        forcePut(d, "ComboBoxUI", "com.sphere.ui.SPComboBoxUI");
        forcePut(d, "ScrollBarUI", "com.sphere.ui.SPScrollBarUI");
        forcePut(d, "TextFieldUI", "com.sphere.ui.SPTextFieldUI");
        forcePut(d, "TreeUI", "com.sphere.ui.SPTreeUI");

        // Neutralize Nimbus button painters to let SPButtonUI handle state rendering
        String[] buttonStates = {
                "", "[Enabled]", "[Focused]", "[MouseOver]", "[Pressed]", "[Disabled]",
                "[Default]", "[Default+Focused]", "[Default+MouseOver]", "[Default+Pressed]"
        };
        for (String state : buttonStates) {
            forcePut(d, "Button" + state + ".backgroundPainter", null);
            forcePut(d, "Button" + state + ".borderPainter", null);
        }

        // Hybrid System / Cached Painters Initialization
        javax.swing.Painter<JComponent> bgPainter = getOrCreatePainter(
                "bgMain",
                () -> new ThemePainters.FlatBackgroundPainter(bgMain)
        );
        javax.swing.Painter<JComponent> surfacePainter = getOrCreatePainter(
                "bgSurface",
                () -> new ThemePainters.FlatBackgroundPainter(bgSurface)
        );
        javax.swing.Painter<JComponent> inputBgPainter = getOrCreatePainter(
                "bgTrack",
                () -> new ThemePainters.FlatBackgroundPainter(bgTrack)
        );
        javax.swing.Painter<JComponent> btnPainter = getOrCreatePainter(
                "btnBase",
                () -> new ThemePainters.FlatBackgroundPainter(btnBase)
        );
        javax.swing.Painter<JComponent> btnHoverPainter = getOrCreatePainter(
                "btnHover",
                () -> new ThemePainters.FlatBackgroundPainter(btnHover)
        );
        javax.swing.Painter<JComponent> btnPressedPainter = getOrCreatePainter(
                "btnPress",
                () -> new ThemePainters.FlatBackgroundPainter(btnPress)
        );
        javax.swing.Painter<JComponent> inputBorderPainter = getOrCreatePainter(
                "border",
                () -> new ThemePainters.FlatBorderPainter(border)
        );

        // --- ADDED FOR ACTIVE TAB FIX ---
        javax.swing.Painter<JComponent> activeTabPainter = getOrCreatePainter(
                "activeTab",
                () -> new ThemePainters.ActiveTabPainter(bgSurface, accent)
        );
        /* ADDED FOR ACTIVE TAB FIX */
        // Table styling
        forcePut(d, "Table.background", resSurface);
        forcePut(d, "Table.gridColor", border);
        forcePut(d, "Table[Enabled].backgroundPainter", surfacePainter);
        forcePut(d, "TableHeader.background", resBg);
        forcePut(d, "TableHeader[Enabled].backgroundPainter", bgPainter);
        forcePut(d, "TableHeader[Enabled].borderPainter", null);
        forcePut(d, "TableHeader:\"TableHeader.renderer\"[Enabled].backgroundPainter", bgPainter);
        forcePut(d, "TableHeader:\"TableHeader.renderer\"[Pressed].backgroundPainter", btnHoverPainter);

        // Viewport / ScrollPane configurations
        forcePut(d, "Viewport.background", resSurface);
        forcePut(d, "Viewport[Enabled].backgroundPainter", surfacePainter);
        forcePut(d, "Viewport[Enabled].borderPainter", null);

        // Force inputs background for complex panels layout
        forcePut(d, "ScrollPane[Enabled].borderPainter", null);
        forcePut(d, "ScrollPane[Enabled].backgroundPainter", surfacePainter);

        // ComboBox configurations
        forcePut(d, "ComboBox.background", resTrack);
        forcePut(d, "ComboBox.foreground", resText);
        forcePut(d, "PopupMenu.borderPainter", null);
        forcePut(d, "PopupMenu[Enabled].backgroundPainter", surfacePainter);

        String[] comboStates = {
                "", "[Enabled]", "[Focused]", "[MouseOver]", "[Pressed]", "[Disabled]", "[Pressed+Focused]"
        };
        for (String s : comboStates) {
            forcePut(d, "ComboBox" + s + ".backgroundPainter", null);
            forcePut(d, "ComboBox" + s + ".editableBackgroundPainter", null);
            forcePut(d, "ComboBox" + s + ".textForeground", resText);
        }

        // ToggleButton configurations
        String togglePrefix = "ToggleButton";
        String[] toggleStates = {
                "[Enabled]", "[MouseOver]", "[Pressed]", "[Focused]", "[Selected]",
                "[Disabled]", "[Default]", "[Default+Focused]", "[Default+MouseOver]",
                "[Default+Pressed]", "[Default+Selected]"
        };
        forcePut(d, togglePrefix + ".FocusPainter", null);
        for (String s : toggleStates) {
            forcePut(d, togglePrefix + s + ".borderPainter", null);
            forcePut(d, togglePrefix + s + ".focusPainter", null);

            boolean sel = s.contains("Selected") || s.contains("Pressed");
            boolean hov = s.contains("MouseOver") || s.contains("Focused");

            forcePut(d, togglePrefix + s + ".textForeground", sel ? resWhite : resText);

            if (sel) {
                forcePut(d, togglePrefix + s + ".backgroundPainter", btnPressedPainter);
            } else if (hov) {
                forcePut(d, togglePrefix + s + ".backgroundPainter", btnHoverPainter);
            } else {
                forcePut(d, togglePrefix + s + ".backgroundPainter", btnPainter);
            }
        }

        // MenuBar / Menu / MenuItem setups
        forcePut(d, "MenuBar.background", bgMain);
        forcePut(d, "MenuBar[Enabled].backgroundPainter", bgPainter);
        forcePut(d, "MenuBar[Enabled].borderPainter", null);

        // Arrow configuration
        forcePut(d, "Menu:\"Menu.arrowIcon\"[Enabled].arrowColor", resText);
        forcePut(d, "Menu:\"Menu.arrowIcon\"[Selected].arrowColor", resWhite);

        String[] menuItems = {"Menu", "MenuItem", "RadioButtonMenuItem"};
        for (String item : menuItems) {
            forcePut(d, item + ".foreground", resText);
            forcePut(d, item + "[Enabled].backgroundPainter", item.equals("Menu") ? bgPainter : surfacePainter);
            forcePut(d, item + "[Enabled].textForeground", resText);

            forcePut(d, item + "[Selected].backgroundPainter", btnPressedPainter);
            forcePut(d, item + "[Selected].textForeground", resWhite);
            forcePut(d, item + "[Selected].borderPainter", null);

            forcePut(d, item + "[MouseOver].backgroundPainter", btnPressedPainter);
            forcePut(d, item + "[MouseOver].textForeground", resWhite);
        }

        forcePut(d, "Menu[Enabled+Selected].backgroundPainter", btnPressedPainter);
        forcePut(d, "Menu[Enabled+MouseOver].backgroundPainter", btnPressedPainter);
        // =================================================================
        // 2. TABBEDPANE STYLING & FOCUS ARTIFACT CLEANUP
        // =================================================================
        forcePut(d, "TabbedPane.contentBorderPainter", null);
        forcePut(d, "TabbedPane.tabBackground", bgMain);
        forcePut(d, "TabbedPane.background", bgMain);
        forcePut(d, "TabbedPane.tabsOpaque", true);
        forcePut(d, "TabbedPane.opaque", true);

        // Wipe structural focus colors, inner margins, and input map highlights
        forcePut(d, "TabbedPane.focus", new Color(0, 0, 0, 0));
        forcePut(d, "TabbedPane.tabFillPainter", null);
        forcePut(d, "TabbedPane.focusInputMap", null);
        forcePut(d, "TabbedPane.contentBorderInsets", new Insets(0, 0, 0, 0));

        // --- REMOVE GRAY STRUCTURAL LINES ---
        forcePut(d, "TabbedPane.shadow", new Color(0, 0, 0, 0));
        forcePut(d, "TabbedPane.darkShadow", new Color(0, 0, 0, 0));
        forcePut(d, "TabbedPane.light", new Color(0, 0, 0, 0));
        forcePut(d, "TabbedPane.highlight", new Color(0, 0, 0, 0));
        forcePut(d, "TabbedPane.focusHeight", 0);
        forcePut(d, "TabbedPane.tabAreaInsets", new Insets(0, 0, 0, 0));
        forcePut(d, "TabbedPane.contentAreaColor", bgMain);

        // --- ADDED FOR ACTIVE TAB FIX ---
        // Kill Nimbus structural content border and its painter to remove the gray line
        forcePut(d, "TabbedPane.contentBorder", null);
        forcePut(d, "TabbedPane:TabbedPaneContent.borderPainter", null);
        /* ADDED FOR ACTIVE TAB FIX */

        String[] tabStates = {
                "[Enabled]", "[MouseOver]", "[Pressed]", "[Selected]", "[Focused]",
                "[Enabled+MouseOver]", "[Focused+MouseOver]", "[Enabled+Selected]",
                "[Focused+Selected]", "[Pressed+Selected]", "[Focused+Pressed+Selected]",
                "[Selected+MouseOver]", "[Enabled+Selected+MouseOver]", "[MouseOver+Selected]",
                "[Focused+Selected+MouseOver]"
        };

        for (String s : tabStates) {
            String base = "TabbedPane:TabbedPaneTab" + s;

            // Hard-lock structural borders and multi-state focus rings to null
            forcePut(d, base + ".borderPainter", null);
            forcePut(d, base + ".focusPainter", null);
            forcePut(d, base + ".FocusPainter", null);

            boolean sel = s.contains("Selected");
            boolean hov = s.contains("MouseOver") || s.contains("Pressed");

            if (sel) {
                // Active tab: full background + bottom accent bar via ActiveTabPainter
                forcePut(d, base + ".backgroundPainter", activeTabPainter);
                forcePut(d, base + ".textForeground", resWhite);
                forcePut(d, "TabbedPane:TabbedPaneContent[Enabled].backgroundPainter", surfacePainter);
                /* FIXED: content area painter */

            } else if (hov) {
                forcePut(d, base + ".backgroundPainter", btnHoverPainter);
                forcePut(d, base + ".textForeground", resWhite);

            } else {
                forcePut(d, base + ".backgroundPainter", bgPainter);
                forcePut(d, base + ".textForeground", resText);
            }
        }
        // =================================================================
        // 3. CRITICAL SCROLLBAR LAYOUT METRICS ENFORCEMENT
        // =================================================================
        // Forces Layout Managers to adhere exactly to ThemeMetrics dimensions.
        forcePut(d, "ScrollBar.width", ThemeMetrics.SCROLLBAR_THICKNESS);
        forcePut(d, "ScrollBar.thumbInsets", new Insets(0, 0, 0, 0));
        forcePut(d, "ScrollBar.trackInsets", new Insets(0, 0, 0, 0));
        forcePut(d, "ScrollBar:\"ScrollBar.button\".size", 0);
        forcePut(d, "ScrollBar.decrementButtonGap", 0);
        forcePut(d, "ScrollBar.incrementButtonGap", 0);

        // Cached fallback painters for custom configurations
        forcePut(d, "ScrollBar:ScrollBarThumb[Enabled].backgroundPainter",
                getOrCreatePainter("thumbNormal",
                        () -> new ThemePainters.RectThumbPainter(thumb)));

        forcePut(d, "ScrollBar:ScrollBarThumb[MouseOver].backgroundPainter",
                getOrCreatePainter("thumbHover",
                        () -> new ThemePainters.RectThumbPainter(thumb.brighter())));

        forcePut(d, "ScrollBar:ScrollBarThumb[Pressed].backgroundPainter",
                getOrCreatePainter("thumbPressed",
                        () -> new ThemePainters.RectThumbPainter(accent)));

        forcePut(d, "ScrollBar:ScrollBarTrack[Enabled].backgroundPainter",
                getOrCreatePainter("trackNormal",
                        () -> new ThemePainters.TrackPainter(bgTrack)));

        // =================================================================
        // TEXT INPUTS CONFIGURATION (FIXED FOR CONTRAST)
        // =================================================================
        String[] textComps = {"TextField", "TextArea", "PasswordField", "FormattedTextField"};
        String[] textStates = {"[Enabled]", "[Focused]", "[Selected]"};

        for (String comp : textComps) {
            forcePut(d, comp + ".background", resTrack);
            forcePut(d, comp + ".foreground", resText);

            for (String s : textStates) {
                forcePut(d, comp + s + ".borderPainter", inputBorderPainter);
                forcePut(d, comp + s + ".backgroundPainter", inputBgPainter);
                forcePut(d, comp + s + ".textForeground", resText);
            }
        }

        // =================================================================
        // LIST CUSTOMIZATIONS
        // =================================================================
        forcePut(d, "List.background", resSurface);
        forcePut(d, "List.foreground", resText);
        forcePut(d, "List.selectionBackground", new ColorUIResource(accent));
        forcePut(d, "List.selectionForeground", resWhite);

        forcePut(d, "List[Selected].textBackground", new ColorUIResource(accent));
        forcePut(d, "List[Selected].textForeground", resWhite);
        

        String[] listPaths = {
                "List:\"List.cellRenderer\"",
                "List:ListCell",
                "\"List.cellRenderer\""
        };

        String[] listStates = {
                "[Enabled]", "[Enabled+Selected]", "[Focused+Selected]", "[Selected]"
        };

        for (String pth : listPaths) {
            for (String s : listStates) {
                boolean sel = s.contains("Selected");

                forcePut(d, pth + s + ".borderPainter", null);
                forcePut(d, pth + s + ".backgroundPainter",
                        sel ? btnPressedPainter : surfacePainter);
                forcePut(d, pth + s + ".textForeground",
                        sel ? resWhite : resText);
            }
        }

        // =================================================================
        // TOOLTIP CONFIGURATION
        // =================================================================
        forcePut(d, "ToolTip.background", resSurface);
        forcePut(d, "ToolTip.foreground", resText);
        forcePut(d, "ToolTip[Enabled].backgroundPainter", surfacePainter);

        // =================================================================
        // TREE CONFIGURATION
        // =================================================================
        forcePut(d, "Tree.hash", border);
        forcePut(d, "Tree.expandedIcon", ThemeIcons.createTreeArrowIcon(text, true));
        forcePut(d, "Tree.collapsedIcon", ThemeIcons.createTreeArrowIcon(text, false));

        // =================================================================
        // HYBRID SYSTEM PIPELINE (ACTIVE VALUES)
        // =================================================================
        // Expose dynamic system properties via ActiveValue triggers.
        // Custom non-Swing components can query these safely to resolve themes in real time.

        forcePut(d, "Sphere.Flat.background",
                (UIDefaults.ActiveValue) table -> palette.getBackgroundSurface());

        forcePut(d, "Sphere.Flat.foreground",
                (UIDefaults.ActiveValue) table -> palette.getTextPrimary());

        forcePut(d, "Sphere.Flat.accent",
                (UIDefaults.ActiveValue) table -> palette.getAccent());
    }
}
