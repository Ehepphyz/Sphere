package com.sphere.components.editor;

import javax.swing.event.DocumentEvent;
import javax.swing.event.UndoableEditEvent;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.CompoundEdit;
import javax.swing.undo.UndoManager;
import javax.swing.undo.UndoableEdit;

/**
 * Undo in steps a person would name: a word typed, a line moved, a Replace All.
 * Consecutive keystrokes are merged until a pause, a newline or a jump of the
 * caret, and multi-step edits (Enter with its indentation, a snippet) are
 * grouped explicitly through {@link #begin()} and {@link #end()}.
 */
public final class SmartUndoManager extends UndoManager {

    private enum Kind { INSERT, DELETE, OTHER }

    private static final long MERGE_WINDOW_MS = 1200;

    private final Document doc;
    private CompoundEdit typing;
    private Kind typingKind = Kind.OTHER;
    private int typingEdge = -1;
    private long lastTime;

    private CompoundEdit explicit;
    private int explicitDepth;

    /** Offset of the change the last undo or redo restored, for the caret. */
    private int lastChangeOffset = -1;

    public SmartUndoManager(Document doc) {
        this.doc = doc;
        setLimit(2000);
    }

    @Override
    public synchronized void undoableEditHappened(UndoableEditEvent e) {
        UndoableEdit edit = e.getEdit();
        if (isAttributeOnly(edit)) {
            return;
        }
        if (explicitDepth > 0 && explicit != null) {
            explicit.addEdit(edit);
            return;
        }
        Kind kind = Kind.OTHER;
        int offset = -1;
        int length = 0;
        if (edit instanceof AbstractDocument.DefaultDocumentEvent dde) {
            offset = dde.getOffset();
            length = dde.getLength();
            if (dde.getType() == DocumentEvent.EventType.INSERT && length == 1 && !isNewlineAt(offset)) {
                kind = Kind.INSERT;
            } else if (dde.getType() == DocumentEvent.EventType.REMOVE && length == 1) {
                kind = Kind.DELETE;
            }
        }
        long now = System.currentTimeMillis();
        if (typing != null && kind != Kind.OTHER && kind == typingKind
                && now - lastTime < MERGE_WINDOW_MS && contiguous(kind, offset)) {
            typing.addEdit(edit);
            typingEdge = kind == Kind.INSERT ? offset + length : offset;
            lastTime = now;
            return;
        }
        closeTyping();
        if (kind != Kind.OTHER) {
            typing = new Group();
            typing.addEdit(edit);
            typingKind = kind;
            typingEdge = kind == Kind.INSERT ? offset + length : offset;
            lastTime = now;
            super.addEdit(typing);
        } else {
            super.addEdit(edit);
        }
    }

    private boolean contiguous(Kind kind, int offset) {
        if (kind == Kind.INSERT) {
            return offset == typingEdge;
        }
        // Backspace walks left, Delete stays put.
        return offset == typingEdge - 1 || offset == typingEdge;
    }

    private boolean isNewlineAt(int offset) {
        try {
            return "\n".equals(doc.getText(offset, 1));
        } catch (BadLocationException ex) {
            return false;
        }
    }

    private static boolean isAttributeOnly(UndoableEdit edit) {
        return edit instanceof AbstractDocument.DefaultDocumentEvent dde
               && dde.getType() == DocumentEvent.EventType.CHANGE;
    }

    /** Ends the word being typed, so the next keystroke starts a new undo step. */
    public synchronized void closeTyping() {
        if (typing != null) {
            typing.end();
            typing = null;
            typingKind = Kind.OTHER;
        }
    }

    /** Opens a group: every edit until the matching end() undoes as one. */
    public synchronized void begin() {
        if (explicitDepth++ == 0) {
            closeTyping();
            explicit = new Group();
        }
    }

    public synchronized void end() {
        if (explicitDepth == 0) {
            return;
        }
        if (--explicitDepth == 0 && explicit != null) {
            CompoundEdit done = explicit;
            explicit = null;
            done.end();
            if (done.isSignificant()) {
                super.addEdit(done);
            }
        }
    }

    /** Runs an edit as one undo step. */
    public void group(Runnable edit) {
        begin();
        try {
            edit.run();
        } finally {
            end();
        }
    }

    @Override
    public synchronized boolean canUndo() {
        closeTyping();
        return super.canUndo();
    }

    @Override
    public synchronized boolean canRedo() {
        closeTyping();
        return super.canRedo();
    }

    @Override
    public synchronized void undo() throws CannotUndoException {
        closeTyping();
        UndoableEdit next = editToBeUndone();
        lastChangeOffset = firstOffset(next);
        super.undo();
    }

    @Override
    public synchronized void redo() throws CannotRedoException {
        closeTyping();
        UndoableEdit next = editToBeRedone();
        lastChangeOffset = firstOffset(next);
        super.redo();
    }

    @Override
    public synchronized void discardAllEdits() {
        typing = null;
        explicit = null;
        explicitDepth = 0;
        super.discardAllEdits();
    }

    public int lastChangeOffset() {
        return lastChangeOffset;
    }

    private static int firstOffset(UndoableEdit edit) {
        if (edit instanceof AbstractDocument.DefaultDocumentEvent dde) {
            return dde.getOffset();
        }
        if (edit instanceof Group g) {
            return g.firstOffset;
        }
        return -1;
    }

    /** A compound edit that remembers where its first change happened. */
    private static final class Group extends CompoundEdit {
        int firstOffset = -1;

        @Override
        public boolean addEdit(UndoableEdit anEdit) {
            if (firstOffset < 0 && isInProgress()) {
                if (anEdit instanceof AbstractDocument.DefaultDocumentEvent dde) {
                    firstOffset = dde.getOffset();
                } else if (anEdit instanceof Group g) {
                    firstOffset = g.firstOffset;
                }
            }
            return super.addEdit(anEdit);
        }
    }
}
