package com.sphere.components.editor;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Breakpoint lines for one buffer. Holds no protocol: a debug adapter reads it
 * when a session starts and listens for changes while one is running.
 */
public final class BreakpointModel {

    public interface Listener {
        void breakpointsChanged(BreakpointModel model);
    }

    private final Set<Integer> lines = new LinkedHashSet<>();
    private final Set<Integer> disabled = new LinkedHashSet<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /** 1-based line numbers, as every debugger spells them. */
    public synchronized Set<Integer> lines() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(lines));
    }

    public synchronized boolean has(int line) {
        return lines.contains(line);
    }

    public synchronized boolean isEnabled(int line) {
        return lines.contains(line) && !disabled.contains(line);
    }

    public void toggle(int line) {
        if (line < 1) {
            return;
        }
        synchronized (this) {
            if (!lines.remove(line)) {
                lines.add(line);
            } else {
                disabled.remove(line);
            }
        }
        fire();
    }

    public void setEnabled(int line, boolean enabled) {
        synchronized (this) {
            if (!lines.contains(line)) {
                return;
            }
            if (enabled) {
                disabled.remove(line);
            } else {
                disabled.add(line);
            }
        }
        fire();
    }

    public void clear() {
        synchronized (this) {
            lines.clear();
            disabled.clear();
        }
        fire();
    }

    /**
     * Shifts breakpoints when lines are inserted or removed above them, so they
     * stay on the statement they were set on rather than on a line number.
     */
    public void shift(int fromLine, int delta) {
        if (delta == 0) {
            return;
        }
        synchronized (this) {
            Set<Integer> moved = new LinkedHashSet<>();
            Set<Integer> movedDisabled = new LinkedHashSet<>();
            for (int line : lines) {
                int target = line >= fromLine ? line + delta : line;
                if (target >= 1) {
                    moved.add(target);
                    if (disabled.contains(line)) {
                        movedDisabled.add(target);
                    }
                }
            }
            lines.clear();
            lines.addAll(moved);
            disabled.clear();
            disabled.addAll(movedDisabled);
        }
        fire();
    }

    public void addListener(Listener l) {
        if (l != null) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void fire() {
        for (Listener l : listeners) {
            l.breakpointsChanged(this);
        }
    }
}
