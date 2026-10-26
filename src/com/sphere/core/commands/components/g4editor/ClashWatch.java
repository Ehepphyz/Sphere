package com.sphere.components.g4editor;

import com.sphere.core.geant4.Clashes;
import com.sphere.core.geant4.Geometry;
import com.sphere.core.geant4.PhysicalVolume;
import com.sphere.core.geant4.Units;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Interference checking as the selection is edited, CAD-style: after each edit, and while a volume is
 * dragged, the selected placements are sampled against their mothers and sisters aside (Clashes), and what
 * is found shows in the view where it is, with the remedy, which F6 (or `resolve`) applies as one undoable
 * move. The whole-document check (F7) stays the reference; this one is about what the hand is doing now.
 */
final class ClashWatch {

    /** Points per copy: enough to catch a volume pushed into another, cheap enough to run on every drag step. */
    private static final int POINTS = 300;
    /** Past this many selected placements the check would lag the hand: the first ones are checked. */
    private static final int MAX_CHECKED = 24;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "g4editor-clashes");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private final EditorModel model;
    private final Viewport vp;
    private final Consumer<String> console;
    private final Timer timer;
    private List<Clashes.Clash> current = List.of();
    private boolean enabled = true;
    /** Bumped by every check asked for: a result of an older one is dropped. */
    private int generation;
    /** The next result follows a finished edit (not a drag step): new findings go to the console too. */
    private boolean report;
    private String reported = "";

    ClashWatch(EditorModel model, Viewport vp, Consumer<String> console) {
        this.model = model;
        this.vp = vp;
        this.console = console;
        timer = new Timer(90, e -> check());
        timer.setRepeats(false);
        model.addListener(new EditorModel.Listener() {
            public void selectionChanged() {
                timer.restart();
            }

            public void documentChanged(Geometry.Change c) {
                if (c != Geometry.Change.PROPERTIES) {
                    report = true;
                }
                timer.restart();
            }
        });
    }

    boolean enabled() {
        return enabled;
    }

    void setEnabled(boolean on) {
        enabled = on;
        if (on) {
            check();
        } else {
            generation++;
            show(List.of());
        }
    }

    List<Clashes.Clash> current() {
        return current;
    }

    private void check() {
        final int gen = ++generation;
        if (!enabled || model.selection().isEmpty()) {
            show(List.of());
            return;
        }
        final List<PhysicalVolume> pvs = new ArrayList<>(model.selection());
        final List<PhysicalVolume> checked = pvs.subList(0, Math.min(MAX_CHECKED, pvs.size()));
        final long revision = model.geometry.revision();
        final boolean tell = report;
        report = false;
        WORKER.execute(() -> {
            if (gen != generation) {
                return; // a newer check is queued
            }
            List<Clashes.Clash> found;
            try {
                found = Clashes.of(model.geometry, checked, POINTS);
            } catch (RuntimeException e) {
                found = null; // the document changed under the check
            }
            final List<Clashes.Clash> r = found;
            SwingUtilities.invokeLater(() -> {
                if (gen != generation) {
                    return;
                }
                if (r == null || model.geometry.revision() != revision) {
                    report |= tell;
                    timer.restart();
                    return;
                }
                show(r);
                if (tell) {
                    tell(r);
                }
            });
        });
    }

    private void show(List<Clashes.Clash> r) {
        current = r;
        vp.setClashes(r, r.isEmpty() ? null : banner(r));
    }

    /** The line across the view: the deepest fault, how many more, and what F6 would do. */
    private static String banner(List<Clashes.Clash> r) {
        final Clashes.Clash c = r.get(0);
        final String more = r.size() > 1 ? "  (+" + (r.size() - 1) + " more)" : "";
        if (c.move() == null) {
            return c.fault().describe() + more;
        }
        return c.fault().describe() + more + "   -   F6 moves it " + Units.best(c.move().length(), Units.Category.LENGTH)
            + " clear";
    }

    /** Findings of a finished edit, in the console once each, with the command that fixes them. */
    private void tell(List<Clashes.Clash> r) {
        final StringBuilder key = new StringBuilder();
        for (Clashes.Clash c : r) {
            key.append(c.fault().describe()).append('\n');
        }
        if (key.toString().equals(reported)) {
            return;
        }
        reported = key.toString();
        for (Clashes.Clash c : r.subList(0, Math.min(5, r.size()))) {
            console.accept("# clash: " + c.fault().describe() + (c.command() == null ? "" : "; resolve runs: " + c.command()));
        }
    }

    /** Applies the remedy of the deepest clash that has one; false when there is none. */
    boolean resolve(Consumer<String> run) {
        for (Clashes.Clash c : current) {
            if (c.command() != null) {
                run.accept(c.command());
                return true;
            }
        }
        return false;
    }
}
