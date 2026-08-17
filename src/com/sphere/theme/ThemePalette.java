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



}

