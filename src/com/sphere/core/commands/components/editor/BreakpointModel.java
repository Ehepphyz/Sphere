package com.sphere.components.editor;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Breakpoint lines for one buffer. Holds no protocol: a debug adapter reads it
 * when a session starts and listens for changes while one is running.
 *
 * A breakpoint can carry a {@link Spec}: a condition it stops on only when true,
 * a hit count it waits for, or a log message, which makes it a logpoint that
 * prints and never stops.
 */
public final class BreakpointModel {

    public interface Listener {
        void breakpointsChanged(BreakpointModel model);
    }

    /**
     * What a breakpoint asks beyond its line. A plain breakpoint has none of it.
     *
     * @param condition  an expression of the debugged language; the breakpoint stops only when it is true
     * @param hitCount   stop on this hit and the ones after it; 0 or 1 for every hit
     * @param logMessage when set, a logpoint: the message is printed, {expressions} filled in, and the program goes on
     */
    public record Spec(String condition, int hitCount, String logMessage) {

        public static final Spec PLAIN = new Spec(null, 0, null);

        public Spec {
            condition = condition == null || condition.isBlank() ? null : condition.strip();
            logMessage = logMessage == null || logMessage.isBlank() ? null : logMessage.strip();
            hitCount = Math.max(0, hitCount);
        }

        public boolean isPlain() {
            return condition == null && hitCount <= 1 && logMessage == null;
        }

        public boolean isLogpoint() {
            return logMessage != null;
        }

        public boolean hasCondition() {
            return condition != null || hitCount > 1;
        }

        /** "x > 5 · 3rd hit · logs ..." for tooltips and the breakpoint list. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (condition != null) {
                sb.append("when ").append(condition);
            }
            if (hitCount > 1) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append("from hit ").append(hitCount);
            }
            if (logMessage != null) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append("logs \"").append(logMessage).append('"');
            }
            return sb.toString();
        }
    }

    private final Set<Integer> lines = new LinkedHashSet<>();
    private final Set<Integer> disabled = new LinkedHashSet<>();
    private final Map<Integer, Spec> specs = new HashMap<>();
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

    /** The condition, hit count or message of a breakpoint; {@link Spec#PLAIN} for none or no breakpoint. */
    public synchronized Spec spec(int line) {
        Spec s = specs.get(line);
        return s == null ? Spec.PLAIN : s;
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
                specs.remove(line);
            }
        }
        fire();
    }

    /**
     * Sets what a breakpoint asks, adding the breakpoint when the line has none.
     * A plain spec leaves an ordinary breakpoint.
     */
    public void set(int line, Spec spec) {
        if (line < 1) {
            return;
        }
        synchronized (this) {
            lines.add(line);
            disabled.remove(line);
            if (spec == null || spec.isPlain()) {
                specs.remove(line);
            } else {
                specs.put(line, spec);
            }
        }
        fire();
    }

    /** Moves a breakpoint, with its spec and state, to another line (gdb binds to the next line with code). */
    public void move(int from, int to) {
        if (from == to || to < 1) {
            return;
        }
        synchronized (this) {
            if (!lines.contains(from) || lines.contains(to)) {
                return;
            }
            lines.remove(from);
            lines.add(to);
            if (disabled.remove(from)) {
                disabled.add(to);
            }
            Spec s = specs.remove(from);
            if (s != null) {
                specs.put(to, s);
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
            specs.clear();
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
            Map<Integer, Spec> movedSpecs = new HashMap<>();
            for (int line : lines) {
                int target = line >= fromLine ? line + delta : line;
                if (target >= 1) {
                    moved.add(target);
                    if (disabled.contains(line)) {
                        movedDisabled.add(target);
                    }
                    Spec s = specs.get(line);
                    if (s != null) {
                        movedSpecs.put(target, s);
                    }
                }
            }
            lines.clear();
            lines.addAll(moved);
            disabled.clear();
            disabled.addAll(movedDisabled);
            specs.clear();
            specs.putAll(movedSpecs);
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
