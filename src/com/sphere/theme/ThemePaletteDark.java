package com.sphere.theme;

import java.awt.Color;

/**
 * Dark theme palette, IntelliJ-style.
 */
public final class ThemePaletteDark implements ThemePalette {

    public static final ThemePaletteDark INSTANCE = new ThemePaletteDark();

    private static final Color BG_MAIN      = new Color(30, 31, 34);
    private static final Color BG_SURFACE   = new Color(43, 45, 48);
    private static final Color BG_TRACK     = new Color(26, 26, 28);
    private static final Color BORDER       = new Color(57, 59, 64);
    private static final Color TEXT_PRIMARY = new Color(223, 225, 229);
    private static final Color TEXT_SECOND  = new Color(160, 162, 166);
    private static final Color ACCENT       = new Color(60, 90, 120);
    private static final Color MOUSE_HOVER  = new Color(70, 73, 78);
    private static final Color BTN_BASE     = new Color(59, 61, 64);
    private static final Color BTN_HOVER    = new Color(70, 73, 78);
    private static final Color BTN_PRESSED  = new Color(53, 116, 220);
    private static final Color SCROLL_THUMB = new Color(85, 88, 93);
    private static final Color OVERLAY      = new Color(0, 0, 0, 120);
    private static final Color ERROR        = new Color(200, 80, 80);
    private static final Color SUCCESS      = new Color(0, 148, 25);

    //ConsoleUI
    private static final Color TERMINAL_BG          = new Color(30, 30, 30);
    private static final Color TERMINAL_FG          = new Color(220, 220, 220);
    private static final Color TERMINAL_SELECTION   = new Color(75, 110, 175);
    private static final Color TERMINAL_BORDER      = new Color(55, 55, 55);

    private static final Color LOG_ERROR_PREFIX     = new Color(200, 80, 80);
    private static final Color LOG_SUCCESS_PREFIX   = new Color(0, 148, 25);
    private static final Color LOG_INFO_PREFIX      = new Color(89, 166, 255);
    private static final Color LOG_PROMPT_PREFIX    = new Color(145, 155, 165);
    private static final Color CLEAR_CLEAN_PREFIX   = new Color(145, 155, 165);
    private static final Color LOG_WARN_PREFIX      = new Color(227, 168, 18);

    private static final Color LOG_ERROR_TEXT       = new Color(200, 80, 80);
    private static final Color LOG_WARN_TEXT        = new Color(227, 168, 18);
    private static final Color LOG_SUCCESS_TEXT     = new Color(0, 148, 25);
    private static final Color LOG_DEFAULT_TEXT     = new Color(220, 220, 220);

    private static final Color POPUP_BORDER         = new Color(255, 255, 255, 30);
    private static final Color POPUP_BACKGROUND     = new Color(51, 54, 57);
    private static final Color POPUP_HOVER_FALLBACK = new Color(58, 58, 58);
    private static final Color LOCKED_MOD           = new Color(230, 162, 0);

    //GenericEnvManagerDialog
    private static final Color HEADER_BACKGROUND = new Color(60, 63, 65);
    private static final Color SCROLL_BORDER     = new Color(50, 50, 50);

    //PyEnvManagerDialog
    private static final Color PYTHON_UPDATE_COLOR = new Color(255, 0, 0);
    private static final Color AMBER_BACKGROUND = new Color(255, 243, 205);
    private static final Color AMBER_ACTIVE_BORDER = new Color(227, 163, 0);
    private static final Color AMBER_FOREGROUND = new Color(133, 100, 4);

    //Traditonal (e.g: color.COLOR) used in text, and foreground
    private static final Color TEXT_WHITE = new Color(255, 255, 255);
    private static final Color TEXT_LIGHT_GRAY = new Color(192, 192, 192);
    private static final Color COL_FILL_BLUE = new Color(0, 133, 208);

    // Tags cells
    private static final Color TAGS_CELL_BG     = new Color(90, 62, 133);
    private static final Color TAGS_CELL_BORDER = new Color(122, 91, 177);
    private static final Color TAGS_CELL_TEXT   = new Color(242, 232, 255);

    // Tabs Editor
    private static final Color TABS_EDIT_ACTIVE     = new Color(30, 30, 30);
    private static final Color TABS_EDIT_HIDDEN     = new Color(45, 45, 45);
    private static final Color SELECTED_TABS_BG     = new Color(0, 122, 204);
    // Editor border accent highlights matching individual text formatting modes
    private static final Color EDITOR_BORDER_MARKD  = new Color(225, 110, 0);
    private static final Color EDITOR_BORDER_LATEX  = new Color(34, 139, 34);
    private static final Color EDITOR_BORDER_PTEXT  = new Color(218, 165, 32);
    private static final Color EDITOR_BORDER_DEFAU  = new Color(90, 90, 90);
    // JupyLabXeditor
    private static final Color JUPYED_NORM_BORDER   = new Color(191, 191, 191);
    private static final Color JUPYED_ACTIV_BORDER  = new Color(30, 144, 255);
    private static final Color JUPYED_LEFT_EBUTTON  = new Color(34, 139, 34);
    private static final Color JUPY_UNILAYOUT_CODE  = new Color(248, 249, 250);
    private static final Color JUPY_UNILAYOUT_MARK  = new Color(240, 244, 248);
    private static final Color JUPY_UNILAYOUT_DEFO  = new Color(245, 245, 245);
    private static final Color JUPY_MATTE_SEP       = new Color(222, 226, 230);
    private static final Color JUPY_DARK_TRANST     = new Color(0, 0, 0, 15);
    // JupyLabXeditr Python Colors
    private static final Color JUPY_PY_KEYWORDS       = new Color(197, 134, 192);
    private static final Color JUPY_PY_BUILTIN        = new Color(86, 156, 214);
    private static final Color JUPY_PY_STRING         = new Color(214, 157, 133);
    private static final Color JUPY_PY_COMMENT        = new Color(106, 153, 85);
    private static final Color JUPY_PY_NUMBERS        = new Color(181, 206, 168);
    private static final Color JUPY_PY_OPERATOR       = new Color(212, 212, 212);
    private static final Color JUPY_PY_DECORATOR      = new Color(255, 215, 0);
    private static final Color JUPY_PY_MAGIC          = new Color(255, 125, 125);
    private static final Color JUPY_PY_EXCEPTION      = new Color(255, 85, 85);
    private static final Color JUPY_PY_SELF           = new Color(86, 156, 214);
    private static final Color JUPY_PY_IMPORT         = new Color(0, 180, 255);
    private static final Color JUPY_PY_CLASS          = new Color(78, 201, 176);
    private static final Color JUPY_PY_FUNCTION       = new Color(220, 220, 170);

    private static final Color JUPY_PY_ATTRIBUTE_LEFT  = new Color(130, 200, 255);
    private static final Color JUPY_PY_ATTRIBUTE_RIGHT = new Color(120, 220, 255);
    private static final Color JUPY_PY_IDENTIFIER      = new Color(200, 200, 200);
    private static final Color JUPY_PY_VARIABLE        = new Color(240, 220, 130);
    private static final Color JUPY_PY_CONSTANT        = new Color(255, 180, 80);
    private static final Color JUPY_PY_FUNCTION_CALL   = new Color(160, 220, 255);
    private static final Color JUPY_PY_CLASS_INSTANCE  = new Color(200, 160, 255);
    private static final Color JUPY_PY_PARAMETER       = new Color(255, 210, 140);
    private static final Color JUPY_PY_ANNOTATION      = new Color(140, 200, 255);

    private static final Color JUPY_CELL_COUNTER       = new Color(160, 160, 160);
    private static final Color JUPY_PY_IMAGE_BG        = new Color(30, 30, 30);
    private static final Color JUPY_PY_ATTRIBUTE       = new Color(120, 220, 255);











    private ThemePaletteDark() {}

    @Override public Color getBackgroundMain()    { return BG_MAIN; }
    @Override public Color getBackgroundSurface() { return BG_SURFACE; }
    @Override public Color getBackgroundTrack()   { return BG_TRACK; }
    @Override public Color getBorder()            { return BORDER; }
    @Override public Color getTextPrimary()       { return TEXT_PRIMARY; }
    @Override public Color getTextSecondary()     { return TEXT_SECOND; }
    @Override public Color getAccent()            { return ACCENT; }
    @Override public Color getMouseHover()        { return MOUSE_HOVER; }
    @Override public Color getButtonBase()        { return BTN_BASE; }
    @Override public Color getButtonHover()       { return BTN_HOVER; }
    @Override public Color getButtonPressed()     { return BTN_PRESSED; }
    @Override public Color getScrollbarThumb()    { return SCROLL_THUMB; }
    @Override public Color getOverlay() { return OVERLAY; }
    @Override public Color getError()   { return ERROR; }
    @Override public Color getSuccess() { return SUCCESS; }

    //ConsoleUI
    @Override public Color getTerminalBackground()     { return TERMINAL_BG; }
    @Override public Color getTerminalForeground()     { return TERMINAL_FG; }
    @Override public Color getTerminalSelection()      { return TERMINAL_SELECTION; }
    @Override public Color getTerminalBorder()         { return TERMINAL_BORDER; }

    @Override public Color getLogErrorPrefix()         { return LOG_ERROR_PREFIX; }
    @Override public Color getLogSuccessPrefix()       { return LOG_SUCCESS_PREFIX; }
    @Override public Color getLogInfoPrefix()          { return LOG_INFO_PREFIX; }
    @Override public Color getLogPromptPrefix()        { return LOG_PROMPT_PREFIX; }
    @Override public Color getClearCleanPrefix()       { return CLEAR_CLEAN_PREFIX; }
    @Override public Color getLogWarnPrefix()          { return LOG_WARN_PREFIX; }

    @Override public Color getLogErrorText()           { return LOG_ERROR_TEXT; }
    @Override public Color getLogWarnText()            { return LOG_WARN_TEXT; }
    @Override public Color getLogSuccessText()         { return LOG_SUCCESS_TEXT; }
    @Override public Color getLogDefaultText()         { return LOG_DEFAULT_TEXT; }

    @Override public Color getPopupBorder()            { return POPUP_BORDER; }
    @Override public Color getPopupBackground()        { return POPUP_BACKGROUND; }
    @Override public Color getPopupHoverFallback()     { return POPUP_HOVER_FALLBACK; }
    @Override public Color getlockedmode()             { return LOCKED_MOD; }


    //GenericEnvManagerDialog
    @Override public Color getHeaderBackground() { return HEADER_BACKGROUND; }
    @Override public Color getScrollBorder()     { return SCROLL_BORDER; }

    //PyEnvManagerDialog
    @Override public Color getPythonUpdateColor() { return PYTHON_UPDATE_COLOR; }
    @Override public Color getAmberBackground() { return AMBER_BACKGROUND; }
    @Override public Color getAmberActiveBorder() { return AMBER_ACTIVE_BORDER; }
    @Override public Color getAmberForeground() { return AMBER_FOREGROUND; }

    //Tradtional
    @Override public Color getTextWhite() { return TEXT_WHITE; }
    @Override public Color getTextLightGray() { return TEXT_LIGHT_GRAY; }
    @Override public Color getColFillBlue() { return COL_FILL_BLUE; }

    //TAG Cells
    @Override public Color getTagsCellBackground() { return TAGS_CELL_BG; }
    @Override public Color getTagsCellBorder()     { return TAGS_CELL_BORDER; }
    @Override public Color getTagsCellText()       { return TAGS_CELL_TEXT; }

    // TABS Editor
    @Override public Color getTabsEditorActive()   { return TABS_EDIT_ACTIVE; }
    @Override public Color getTabEditorHidden()    { return TABS_EDIT_HIDDEN; }
    @Override public Color getTabEditorSelectBg()  { return SELECTED_TABS_BG; }
    // Editor border accent highlights matching individual text formatting modes
    @Override public Color getEdBorderMarkd()      { return EDITOR_BORDER_MARKD; }
    @Override public Color getEdBorderLatex()      { return EDITOR_BORDER_LATEX; }
    @Override public Color getEdBorderPtext()      { return EDITOR_BORDER_PTEXT; }
    @Override public Color getEdBorderDefault()    { return EDITOR_BORDER_DEFAU; }
    // JupyLabXEditor
    @Override public Color getJupyLabXedNBorder()  { return JUPYED_NORM_BORDER; }
    @Override public Color getJupyLabXedActive()   { return JUPYED_ACTIV_BORDER; }
    @Override public Color getJupyLabXedLbutton()  { return JUPYED_LEFT_EBUTTON; }
    @Override public Color getJupyUniLayoutCode()  { return JUPY_UNILAYOUT_CODE; }
    @Override public Color getJupyUniLayoutMark()  { return JUPY_UNILAYOUT_MARK; }
    @Override public Color getJupyUnilayoutDefo()  { return JUPY_UNILAYOUT_DEFO; }
    @Override public Color getJupyMatteSep()       { return JUPY_MATTE_SEP; }
    @Override public Color getJupyDarkTranslus()   { return JUPY_DARK_TRANST; }
    // JupyLabXeditr Python Colors
    @Override public Color getJupyPyKeywords()       { return JUPY_PY_KEYWORDS; }
    @Override public Color getJupyPyBuiltin()        { return JUPY_PY_BUILTIN; }
    @Override public Color getJupyPyString()         { return JUPY_PY_STRING; }
    @Override public Color getJupyPyComment()        { return JUPY_PY_COMMENT; }
    @Override public Color getJupyPyNumbers()        { return JUPY_PY_NUMBERS; }
    @Override public Color getJupyPyOperator()       { return JUPY_PY_OPERATOR; }
    @Override public Color getJupyPyDecorator()      { return JUPY_PY_DECORATOR; }
    @Override public Color getJupyPyMagic()          { return JUPY_PY_MAGIC; }
    @Override public Color getJupyPyException()      { return JUPY_PY_EXCEPTION; }
    @Override public Color getJupyPySelf()           { return JUPY_PY_SELF; }
    @Override public Color getJupyPyImport()         { return JUPY_PY_IMPORT; }
    @Override public Color getJupyPyClass()          { return JUPY_PY_CLASS; }
    @Override public Color getJupyPyFunction()       { return JUPY_PY_FUNCTION; }

    @Override public Color getJupyPyAttributeLeft()  { return JUPY_PY_ATTRIBUTE_LEFT; }
    @Override public Color getJupyPyAttributeRight() { return JUPY_PY_ATTRIBUTE_RIGHT; }
    @Override public Color getJupyPyIdentifier()     { return JUPY_PY_IDENTIFIER; }
    @Override public Color getJupyPyVariable()       { return JUPY_PY_VARIABLE; }
    @Override public Color getJupyPyConstant()       { return JUPY_PY_CONSTANT; }
    @Override public Color getJupyPyFunctionCall()   { return JUPY_PY_FUNCTION_CALL; }
    @Override public Color getJupyPyClassInstance()  { return JUPY_PY_CLASS_INSTANCE; }
    @Override public Color getJupyPyParameter()      { return JUPY_PY_PARAMETER; }
    @Override public Color getJupyPyAnnotation()     { return JUPY_PY_ANNOTATION; }

    @Override public Color getJupyCellCounter() { return JUPY_CELL_COUNTER; }
    @Override public Color getJupyPyImageBg()   { return JUPY_PY_IMAGE_BG; }
    @Override public Color getJupyPyAttribute() { return JUPY_PY_ATTRIBUTE; }




}

