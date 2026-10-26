package com.sphere.components.g4editor;

import com.sphere.core.geant4.Gdml;
import com.sphere.core.geant4.Geometry;
import com.sphere.core.geant4.GeometryFiles;
import com.sphere.core.geant4.GeometryException;
import com.sphere.core.geant4.LogicalVolume;
import com.sphere.core.geant4.PhysicalVolume;
import com.sphere.core.geant4.Units;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The editor's state around a geometry: the file, the selection, undo and
 * redo, and the console log where every action is echoed as the command that
 * would redo it, like FreeCAD's macro recording.
 *
 * Undo keeps the document as GDML text: one snapshot per edit. A detector of
 * a thousand volumes writes in a few milliseconds, and the snapshots double
 * as a permanent test that whatever the editor holds survives GDML.
 */
final class EditorModel {

    interface Listener {
        default void selectionChanged() {
        }

        default void documentChanged(Geometry.Change change) {
        }

        default void fileChanged() {
        }
    }

    final Geometry geometry;
    private Path file;
    private GeometryFiles.Format format = GeometryFiles.Format.GDML;
    private boolean dirty;
    private final Set<PhysicalVolume> selection = new LinkedHashSet<>();
    /** What the property panel shows when nothing is placed: a volume, a solid, a material or a define. */
    private Object focus;
    private PhysicalVolume hover;
    /** The snapshots, deflated: GDML shrinks tenfold, and a ROOT geometry's is megabytes. */
    private final Deque<byte[]> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final Deque<String> undoLabels = new ArrayDeque<>(), redoLabels = new ArrayDeque<>();
    private String current;
    private String checkpoint;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> log = new CopyOnWriteArrayList<>();
    /** The unit lengths are typed and shown in when none is given. */
    String lengthUnit = "mm";
    /** Where new volumes go; null lets the editor find the deepest volume that holds them. */
    LogicalVolume container;
    /** The material of new volumes. */
    String material = "G4_Al";
    private static final int MAX_UNDO = 200;
    /** What the undo and redo snapshots may hold together; the oldest steps go first past it. */
    private static final long MAX_UNDO_BYTES = 64L << 20;

    EditorModel(Geometry g) {
        this.geometry = g;
        this.current = Gdml.write(g);
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void addLog(Consumer<String> sink) {
        log.add(sink);
    }

    /** Echoes the console command equivalent to what was just done. */
    void log(String command) {
        for (Consumer<String> c : log) {
            c.accept(command);
        }
    }

    // ------------------------------------------------------------------
    // File
    // ------------------------------------------------------------------

    Path file() {
        return file;
    }

    boolean dirty() {
        return dirty;
    }

    String title() {
        return (file == null ? geometry.title() + ".gdml" : file.getFileName().toString()) + (dirty ? " *" : "");
    }

    /** The format of the file the design came from or was last saved to: Ctrl+S writes that format again. */
    GeometryFiles.Format format() {
        return format;
    }

    /** Opens a GDML design or the geometry of a ROOT file (rootName picks one; null, the first). */
    GeometryFiles.Loaded open(Path p, String rootName) throws IOException, GeometryException {
        final GeometryFiles.Loaded r = GeometryFiles.read(p, rootName);
        adopt(p, r);
        return r;
    }

    /** Makes a design read elsewhere (off the event thread, say) the document, as open does. */
    void adopt(Path p, GeometryFiles.Loaded r) {
        adopt(p, r, null);
    }

    /**
     * Same, with the design's GDML (the undo history's first state) written where it was read: for a big
     * ROOT geometry, writing it here held the event thread for seconds. The design, evaluated by the reader
     * and touched by nothing since, is not evaluated again either.
     */
    void adopt(Path p, GeometryFiles.Loaded r, String gdml) {
        if (gdml != null) {
            geometry.replaceWithEvaluated(r.geometry());
        } else {
            geometry.replaceWith(r.geometry());
        }
        file = p;
        format = r.format();
        dirty = false;
        undo.clear();
        redo.clear();
        undoLabels.clear();
        redoLabels.clear();
        current = gdml != null ? gdml : Gdml.write(geometry);
        selection.clear();
        focus = null;
        for (String w : r.warnings()) {
            log("# warning: " + w);
        }
        fireDocument(Geometry.Change.ALL);
        fireSelection();
        fireFile();
    }

    /**
     * Writes the design in a format and returns what had to be approximated.
     * GDML and ROOT become the design's file; portable GDML is an export, the
     * design keeps its file.
     */
    List<String> save(Path p, GeometryFiles.Format f) throws IOException {
        final List<String> notes = GeometryFiles.write(geometry, p, f);
        if (f != GeometryFiles.Format.GDML_PORTABLE) {
            file = p;
            format = f;
            dirty = false;
            fireFile();
        }
        return notes;
    }

    void newDocument() {
        geometry.replaceWith(Geometry.blank());
        file = null;
        format = GeometryFiles.Format.GDML;
        dirty = false;
        undo.clear();
        redo.clear();
        undoLabels.clear();
        redoLabels.clear();
        current = Gdml.write(geometry);
        selection.clear();
        focus = null;
        fireDocument(Geometry.Change.ALL);
        fireSelection();
        fireFile();
    }

    // ------------------------------------------------------------------
    // Edits
    // ------------------------------------------------------------------

    /** Runs a change as one undoable step. */
    void edit(String label, Runnable change) {
        final String before = current;
        change.run();
        geometry.rebuild();
        current = Gdml.write(geometry);
        if (!current.equals(before)) {
            push(before, label);
        }
        fireDocument(Geometry.Change.ALL);
    }

    /** Marks the start of a live change (a drag): commit() makes it one undo step. */
    void checkpoint() {
        checkpoint = current;
    }

    /** Applies a live change without recording it. */
    void live(Runnable change) {
        change.run();
        geometry.rebuild();
        fireDocument(Geometry.Change.PROPERTIES);
    }

    void commit(String label) {
        if (checkpoint == null) {
            return;
        }
        final String before = checkpoint;
        checkpoint = null;
        current = Gdml.write(geometry);
        if (!current.equals(before)) {
            push(before, label);
        }
        fireDocument(Geometry.Change.ALL);
    }

    void cancelLive() {
        if (checkpoint != null) {
            restore(checkpoint);
            checkpoint = null;
        }
    }

    private void push(String before, String label) {
        undo.push(pack(before));
        undoLabels.push(label);
        redo.clear();
        redoLabels.clear();
        trim();
        dirty = true;
        fireFile();
    }

    /** Drops the oldest undo steps, then the farthest redo steps, past the count or the memory allowed. */
    private void trim() {
        long bytes = 0;
        for (byte[] b : undo) {
            bytes += b.length;
        }
        for (byte[] b : redo) {
            bytes += b.length;
        }
        while (undo.size() > MAX_UNDO || (bytes > MAX_UNDO_BYTES && undo.size() > 1)) {
            bytes -= undo.removeLast().length;
            undoLabels.removeLast();
        }
        while (bytes > MAX_UNDO_BYTES && redo.size() > 1) {
            bytes -= redo.removeLast().length;
            redoLabels.removeLast();
        }
    }

    private static byte[] pack(String gdml) {
        final java.util.zip.Deflater d = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED);
        try {
            final byte[] in = gdml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            d.setInput(in);
            d.finish();
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(64, in.length / 8));
            final byte[] buf = new byte[1 << 16];
            while (!d.finished()) {
                out.write(buf, 0, d.deflate(buf));
            }
            return out.toByteArray();
        } finally {
            d.end();
        }
    }

    private static String unpack(byte[] packed) {
        final java.util.zip.Inflater f = new java.util.zip.Inflater();
        try {
            f.setInput(packed);
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(packed.length * 8);
            final byte[] buf = new byte[1 << 16];
            while (!f.finished()) {
                final int n = f.inflate(buf);
                if (n == 0 && (f.needsInput() || f.needsDictionary())) {
                    break;
                }
                out.write(buf, 0, n);
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.util.zip.DataFormatException e) {
            throw new IllegalStateException("undo snapshot unreadable", e);
        } finally {
            f.end();
        }
    }

    boolean canUndo() {
        return !undo.isEmpty();
    }

    boolean canRedo() {
        return !redo.isEmpty();
    }

    String undoLabel() {
        return undoLabels.isEmpty() ? "" : undoLabels.peek();
    }

    String redoLabel() {
        return redoLabels.isEmpty() ? "" : redoLabels.peek();
    }

    void undo() {
        if (undo.isEmpty()) {
            return;
        }
        redo.push(pack(current));
        redoLabels.push(undoLabels.pop());
        restore(unpack(undo.pop()));
        trim();
        dirty = true;
        fireFile();
    }

    void redo() {
        if (redo.isEmpty()) {
            return;
        }
        undo.push(pack(current));
        undoLabels.push(redoLabels.pop());
        restore(unpack(redo.pop()));
        trim();
        dirty = true;
        fireFile();
    }

    private void restore(String snapshot) {
        final List<String> selected = new ArrayList<>();
        for (PhysicalVolume pv : selection) {
            selected.add(pv.name());
        }
        try {
            geometry.replaceWith(Gdml.read(snapshot, file == null ? null : file.getParent()).geometry());
        } catch (GeometryException e) {
            log("# could not restore: " + e.getMessage());
            return;
        }
        current = snapshot;
        selection.clear();
        for (String n : selected) {
            final PhysicalVolume pv = geometry.placement(n);
            if (pv != null) {
                selection.add(pv);
            }
        }
        focus = null;
        fireDocument(Geometry.Change.ALL);
        fireSelection();
    }

    // ------------------------------------------------------------------
    // Selection
    // ------------------------------------------------------------------

    Set<PhysicalVolume> selection() {
        return selection;
    }

    PhysicalVolume primary() {
        PhysicalVolume last = null;
        for (PhysicalVolume pv : selection) {
            last = pv;
        }
        return last;
    }

    void select(PhysicalVolume pv, boolean add) {
        if (!add) {
            selection.clear();
        }
        if (pv != null) {
            if (add && selection.contains(pv)) {
                selection.remove(pv);
            } else {
                selection.add(pv);
            }
        }
        focus = null;
        fireSelection();
    }

    void selectAll(List<PhysicalVolume> pvs) {
        selection.clear();
        selection.addAll(pvs);
        focus = null;
        fireSelection();
    }

    void clearSelection() {
        if (!selection.isEmpty() || focus != null) {
            selection.clear();
            focus = null;
            fireSelection();
        }
    }

    Object focus() {
        return focus;
    }

    /** Shows a non-placed object in the property panel. */
    void focus(Object o) {
        selection.clear();
        focus = o;
        fireSelection();
    }

    PhysicalVolume hover() {
        return hover;
    }

    void hover(PhysicalVolume pv) {
        hover = pv;
    }

    /** Drops placements that no longer exist from the selection. */
    void pruneSelection() {
        final Set<PhysicalVolume> live = new java.util.HashSet<>(geometry.placements());
        if (selection.removeIf(pv -> !live.contains(pv))) {
            fireSelection();
        }
    }

    // ------------------------------------------------------------------

    /** A length typed without unit, in the editor's unit; formatted back the same way. */
    String len(double mm) {
        final double u = Units.Category.LENGTH.unitValue(lengthUnit);
        return Units.num(mm / u) + "*" + lengthUnit;
    }

    static String deg(double rad) {
        return Units.num(Math.toDegrees(rad)) + "*deg";
    }

    void fireSelection() {
        for (Listener l : listeners) {
            l.selectionChanged();
        }
    }

    void fireDocument(Geometry.Change c) {
        pruneSelectionQuiet();
        for (Listener l : listeners) {
            l.documentChanged(c);
        }
        geometry.fire(c);
    }

    private void pruneSelectionQuiet() {
        final Set<PhysicalVolume> live = new java.util.HashSet<>(geometry.placements());
        selection.removeIf(pv -> !live.contains(pv));
    }

    void fireFile() {
        for (Listener l : listeners) {
            l.fileChanged();
        }
    }
}
