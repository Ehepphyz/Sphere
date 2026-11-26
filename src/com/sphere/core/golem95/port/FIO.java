package com.sphere.core.golem95.port;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Where the translated library writes: its WRITE statements to units 0 (errors), 6 (output) and the
 * information file unit 12. Each engine keeps the records of the current computation, so what Golem95
 * said about it (a warning on the accuracy, the reason it stopped) is attached to the result instead
 * of being lost on a terminal nobody reads.
 */
public final class FIO {

    /** One record written by the library. */
    public record Line(int unit, String text) {
    }

    private final List<Line> lines = new ArrayList<>();
    private BiConsumer<Integer, String> echo;

    public synchronized void write(int unit, String text) {
        if (lines.size() < 10_000) {
            lines.add(new Line(unit, text));
        }
        if (echo != null) {
            echo.accept(unit, text);
        }
    }

    /** Also hands every record to a listener as it is written (the console, a log). */
    public synchronized void echo(BiConsumer<Integer, String> to) {
        this.echo = to;
    }

    public synchronized List<Line> lines() {
        return List.copyOf(lines);
    }

    /** The records written since the last clear, then forgets them. */
    public synchronized List<Line> drain() {
        final List<Line> out = List.copyOf(lines);
        lines.clear();
        return out;
    }
}
