package com.sphere.components.spdf.edit;

import com.sphere.components.spdf.cos.PdfDocument;
import com.sphere.components.spdf.cos.PdfNull;
import com.sphere.components.spdf.cos.PdfObject;
import com.sphere.components.spdf.cos.PdfRef;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Undo and redo for every change to a document. An edit opens a step, names
 * each object it is about to change ({@link #touch}) and adds objects through
 * {@link #add}; the step keeps the objects as they were before and after.
 * Undoing puts the "before" copies back, redoing the "after" ones: any edit,
 * text, pages, annotations, fonts, comes back the same way.
 */
public final class EditHistory {

    public static final class Step {
        public final String label;
        final Map<Integer, PdfObject> before = new LinkedHashMap<>();
        final Map<Integer, PdfObject> after = new LinkedHashMap<>();
        final Set<Integer> created = new HashSet<>();
        public final long time = System.currentTimeMillis();
        /** Pages the step changed (for thumbnails to redraw), by index at the time. */
        public final Set<Integer> pages = new HashSet<>();

        Step(String label) {
            this.label = label;
        }
    }

    private final PdfDocument doc;
    private final Deque<Step> undo = new ArrayDeque<>();
    private final Deque<Step> redo = new ArrayDeque<>();
    private Step current;
    private int depth;
    private final List<Runnable> listeners = new ArrayList<>();
    private int savedMark;
    private int changes;
    private Step last;

    /** The step committed, undone or redone last. */
    public Step last() {
        return last;
    }

    public EditHistory(PdfDocument doc) {
        this.doc = doc;
    }

    public void addListener(Runnable r) {
        listeners.add(r);
    }

    private void fire() {
        for (Runnable r : listeners) {
            r.run();
        }
    }

    /** Opens a step; nested begins join the outer step. */
    public void begin(String label) {
        if (depth++ == 0) {
            current = new Step(label);
        }
    }

    /** Notes an object before it changes. */
    public void touch(int num) {
        if (current == null || current.before.containsKey(num) || current.created.contains(num)) {
            return;
        }
        PdfObject o = doc.exists(num) ? doc.getObject(num) : PdfNull.NULL;
        current.before.put(num, o == PdfNull.NULL ? PdfNull.NULL : o.copy());
    }

    public void touch(PdfRef r) {
        if (r != null) {
            touch(r.num);
        }
    }

    public void touchPage(int pageIndex, int objNum) {
        touch(objNum);
        if (current != null) {
            current.pages.add(pageIndex);
        }
    }

    /** Adds an object as part of the step. */
    public PdfRef add(PdfObject o) {
        PdfRef r = doc.addObject(o);
        if (current != null) {
            current.created.add(r.num);
        }
        return r;
    }

    /** Closes the step; an empty one is dropped. */
    public void commit() {
        if (depth == 0) {
            return;
        }
        if (--depth > 0) {
            return;
        }
        Step s = current;
        current = null;
        if (s == null || s.before.isEmpty() && s.created.isEmpty()) {
            return;
        }
        for (int num : s.before.keySet()) {
            PdfObject o = doc.exists(num) ? doc.getObject(num) : PdfNull.NULL;
            s.after.put(num, o == PdfNull.NULL ? PdfNull.NULL : o.copy());
        }
        for (int num : s.created) {
            PdfObject o = doc.getObject(num);
            s.after.put(num, o == PdfNull.NULL ? PdfNull.NULL : o.copy());
        }
        undo.push(s);
        redo.clear();
        changes++;
        last = s;
        doc.changed();
        fire();
    }

    /** Abandons the open step, putting back what it had touched. */
    public void rollback() {
        if (current == null) {
            depth = 0;
            return;
        }
        Step s = current;
        current = null;
        depth = 0;
        for (Map.Entry<Integer, PdfObject> e : s.before.entrySet()) {
            restore(e.getKey(), e.getValue());
        }
        for (int num : s.created) {
            doc.putObject(num, null);
        }
        doc.changed();
    }

    private void restore(int num, PdfObject o) {
        doc.putObject(num, o == PdfNull.NULL ? null : o.copy());
    }

    public boolean canUndo() {
        return !undo.isEmpty() && current == null;
    }

    public boolean canRedo() {
        return !redo.isEmpty() && current == null;
    }

    public String undoLabel() {
        return undo.isEmpty() ? null : undo.peek().label;
    }

    public String redoLabel() {
        return redo.isEmpty() ? null : redo.peek().label;
    }

    public Step undo() {
        if (!canUndo()) {
            return null;
        }
        Step s = undo.pop();
        for (Map.Entry<Integer, PdfObject> e : s.before.entrySet()) {
            restore(e.getKey(), e.getValue());
        }
        for (int num : s.created) {
            if (!s.before.containsKey(num)) {
                doc.putObject(num, null);
            }
        }
        redo.push(s);
        changes--;
        last = s;
        doc.changed();
        fire();
        return s;
    }

    public Step redo() {
        if (!canRedo()) {
            return null;
        }
        Step s = redo.pop();
        for (Map.Entry<Integer, PdfObject> e : s.after.entrySet()) {
            restore(e.getKey(), e.getValue());
        }
        undo.push(s);
        changes++;
        last = s;
        doc.changed();
        fire();
        return s;
    }

    /** The steps, most recent first (the history panel shows them). */
    public List<Step> steps() {
        return new ArrayList<>(undo);
    }

    public boolean isDirty() {
        return changes != savedMark;
    }

    public void markSaved() {
        savedMark = changes;
        fire();
    }

    public PdfDocument document() {
        return doc;
    }
}
