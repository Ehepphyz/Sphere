package com.sphere.components;

import java.awt.Color;
import java.nio.file.Path;

/**
 * An editor other than text living in a SphereCoder tab: :spdf (PDF), Sphere
 * Image (pictures). SphereCoder gives it a tab header with a colored badge,
 * routes File > Save, Save As, Save All, Undo and Redo to it, asks it about
 * unsaved work before closing, and keeps it through layout changes.
 * Implementations are Swing components.
 */
public interface CoderPanel {

    /** The tab's title (a dot or a star in it while unsaved). */
    String title();

    /** The file shown, or null. */
    Path file();

    boolean isDirty();

    /** Saves; false when it did not (canceled, failed). */
    boolean save();

    boolean saveAs();

    /** Asks about unsaved work; false when the user cancels. */
    boolean confirmDiscard();

    /** Releases what the editor holds (threads, live previews). */
    void dispose();

    /** Gives the keyboard to the editor's main view. */
    void focusView();

    void setTitleListener(Runnable r);

    void undo();

    void redo();

    /** The short word on the tab's badge ("PDF", "IMG"). */
    String badge();

    Color badgeColor();
}
