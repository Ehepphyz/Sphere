package com.sphere.core.hepmc3.cxx;

import java.util.function.Consumer;

/**
 * std::cout and std::cerr as the port sees them. HepMC3 prints its warnings
 * on cout, its errors on cerr, and its examples print their results with
 * printf; where that text goes depends on who runs the code: the console of
 * Sphere (warnings as warnings), a validation that compares it with the C++
 * output to the character, or the terminal of a command-line run.
 *
 * <p>Each thread has its own pair, so a validation running beside a command
 * keeps its text to itself. Text is collected and handed over line by line.
 */
public final class StdStreams {

    /** One stream: text in, whole lines out. */
    public static final class Stream {
        private final StringBuilder pending = new StringBuilder();
        private final Consumer<String> lines;

        public Stream(Consumer<String> lines) {
            this.lines = lines;
        }

        public synchronized Stream print(CharSequence s) {
            if (lines == null) return this;
            for (int i = 0; i < s.length(); i++) {
                final char c = s.charAt(i);
                if (c == '\n') {
                    lines.accept(pending.toString());
                    pending.setLength(0);
                } else {
                    pending.append(c);
                }
            }
            return this;
        }

        public Stream println(CharSequence s) {
            return print(s).print("\n");
        }

        /** printf to this stream. */
        public Stream printf(String fmt, Object... args) {
            return print(CFormat.sprintf(fmt, args));
        }

        /** What was printed without a final newline, handed over. */
        public synchronized void flush() {
            if (lines != null && pending.length() > 0) {
                lines.accept(pending.toString());
                pending.setLength(0);
            }
        }
    }

    private final Stream out;
    private final Stream err;

    public StdStreams(Consumer<String> out, Consumer<String> err) {
        this.out = new Stream(out);
        this.err = new Stream(err);
    }

    /** The process's System.out and System.err. */
    public static final StdStreams SYSTEM = new StdStreams(System.out::println, System.err::println);

    /** Everything dropped. */
    public static final StdStreams NONE = new StdStreams(null, null);

    private static volatile StdStreams global = SYSTEM;
    private static final ThreadLocal<StdStreams> LOCAL = new ThreadLocal<>();

    /** The pair for this thread: its own if one was set, else the global one. */
    public static StdStreams current() {
        final StdStreams local = LOCAL.get();
        return local != null ? local : global;
    }

    public static Stream cout() {
        return current().out;
    }

    public static Stream cerr() {
        return current().err;
    }

    /** The pair every thread without its own uses. */
    public static void setGlobal(StdStreams streams) {
        global = streams == null ? SYSTEM : streams;
    }

    /** Runs code with this thread's cout and cerr sent elsewhere, then puts them back. */
    public static <T> T with(StdStreams streams, java.util.concurrent.Callable<T> body) throws Exception {
        final StdStreams before = LOCAL.get();
        LOCAL.set(streams);
        try {
            return body.call();
        } finally {
            streams.out.flush();
            streams.err.flush();
            if (before == null) LOCAL.remove();
            else LOCAL.set(before);
        }
    }

    public Stream out() {
        return out;
    }

    public Stream err() {
        return err;
    }
}
