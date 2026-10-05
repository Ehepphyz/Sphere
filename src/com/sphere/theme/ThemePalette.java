package com.sphere.theme;

import java.awt.Color;

/**
 * Base color palette contract for Sphere themes.
 * Implementations provide a complete set of UI colors.
 */
public interface ThemePalette {

    // Base theme
    Color getBackgroundMain();
    Color getBackgroundSurface();
    Color getBackgroundTrack();
    Color getBorder();
    Color getTextPrimary();
    Color getTextSecondary();
    Color getAccent();
    Color getButtonBase();
    Color getButtonHover();
    Color getButtonPressed();
    Color getScrollbarThumb();
    Color getMouseHover();

    // Additional colors
    Color getOverlay();
    Color getError();
    Color getSuccess();

    // ConsoleUI
    Color getTerminalBackground();
    Color getTerminalForeground();
    Color getTerminalSelection();
    Color getTerminalBorder();

    Color getLogErrorPrefix();
    Color getLogSuccessPrefix();
    Color getLogInfoPrefix();
    Color getLogPromptPrefix();
    Color getLogWarnPrefix();
    Color getLogDebugPrefix();

    Color getLogErrorText();
    Color getLogWarnText();
    Color getLogSuccessText();
    Color getLogDefaultText();

    Color getPopupBorder();
    Color getPopupBackground();
    Color getPopupHoverFallback();

    Color getHeaderBackground();
    Color getScrollBorder();
    Color getPythonUpdateColor();
    Color getAmberBackground();
    Color getAmberActiveBorder();
    Color getAmberForeground();
    Color getTextWhite();
    Color getTextLightGray();
    Color getColFillBlue();
    Color getlockedmode();
    Color getClearCleanPrefix();

    // SectionTagsPanel
    Color getTagsCellBackground();
    Color getTagsCellBorder();
    Color getTagsCellText();

    //tabs editor
    Color getTabsEditorActive();
    Color getTabEditorHidden();
    Color getTabEditorSelectBg();
    Color getEdBorderMarkd();
    Color getEdBorderLatex();
    Color getEdBorderPtext();
    Color getEdBorderDefault();
    Color getJupyLabXedNBorder();
    Color getJupyLabXedActive();
    Color getJupyLabXedLbutton();
    Color getJupyUniLayoutCode();
    Color getJupyUniLayoutMark();
    Color getJupyUnilayoutDefo();
    Color getJupyMatteSep();
    Color getJupyDarkTranslus();

    Color getJupyCellCounter();
    Color getJupyPyImageBg();
    Color getJupyPyAttribute();
    
    Color getJupyPyKeywords();
    Color getJupyPyBuiltin();
    Color getJupyPyString();
    Color getJupyPyComment();
    Color getJupyPyNumbers();
    Color getJupyPyOperator();
    Color getJupyPyDecorator();
    Color getJupyPyMagic();
    Color getJupyPyException();
    Color getJupyPySelf();
    Color getJupyPyImport();
    Color getJupyPyClass();
    Color getJupyPyFunction();

    Color getJupyPyAttributeLeft();
    Color getJupyPyAttributeRight();
    Color getJupyPyIdentifier();
    Color getJupyPyVariable();
    Color getJupyPyConstant();
    Color getJupyPyFunctionCall();
    Color getJupyPyClassInstance();
    Color getJupyPyParameter();
    Color getJupyPyAnnotation();

    // ROOT canvas (RootCanvasView, RootPadPainter, RootPainter3D): what ROOT draws
    // in white and black. The light palette keeps ROOT's own colours, which are also
    // those of a picture exported from a canvas.
    Color getCanvasGround();        // around the canvas
    Color getCanvasPaper();         // pads and frames (ROOT's kWhite)
    Color getCanvasInk();           // axes, ticks, labels, titles, black lines (ROOT's kBlack)
    Color getCanvasInkMuted();      // messages and hints
    Color getCanvasGrid();          // grid lines
    Color getCanvasBoxFill();       // statistics box, legends, TPaveText
    Color getCanvasBoxBorder();     // their border
    Color getCanvasSelection();     // the range dragged over to zoom
    Color getCanvasHistLine();      // a histogram's line when it has none of its own
    Color getCanvasFillDefault();   // a fill when the object has none of its own
    Color getCanvas3DWall();        // the back walls of LEGO, SURF, TH3, TGraph2D
    Color getCanvas3DWallLine();    // their edges
    Color getCanvas3DWallGrid();    // their grid
    Color getCanvas3DMesh();        // the mesh lines over a surface
    Color getCanvas3DDefaultFill(); // LEGO bars of a histogram without a fill colour

    // Sphere's 3D space (Renderer3D, Space3DPanel)
    Color getSpace3DGroundTop();
    Color getSpace3DGroundBottom();
    Color getSpace3DGrid();
    Color getSpace3DEdge();
    Color getSpace3DLabel();
    Color getSpace3DTitle();

    // Python environment manager (PyEnvManagerDialog): states of packages and findings
    Color getPyOk();            // up to date, healthy, a safe change
    Color getPyPatch();         // a patch update
    Color getPyMinor();         // a minor update, a warning
    Color getPyMajor();         // a major update
    Color getPyDanger();        // a vulnerability, a conflict, a broken install
    Color getPyInfo();          // information, a dependency, a hint
    Color getPyBadgeText();     // text on the coloured badges above
    Color getPyChip();          // filter chips and the details card
    Color getPyChipSelected();  // a chip turned on
    Color getPyRowStripe();     // every other row of the package table



}

