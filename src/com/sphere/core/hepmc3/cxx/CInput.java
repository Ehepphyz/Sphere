package com.sphere.core.hepmc3.cxx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * std::istream as HepMC3's readers drive it: getline into a fixed buffer,
 * peek, and the state bits they test after every call. Whether a reader
 * says failed() after the last event, whether an over-long line stops it,
 * what the buffer holds after a failed call: all of it comes from these
 * bits, so they are kept as libstdc++ keeps them.
 *
 * <p>Bytes are read as ISO-8859-1 characters, one char per byte, so that any
 * text goes through the readers and back out of the writers unchanged. In
 * text mode (the default) "\r\n" is read as "\n", as a file opened in text
 * mode on Windows is, so that files written there read as everywhere else.
 */
public final class CInput implements AutoCloseable {

    public static final int GOOD = 0;
    public static final int BAD = 1;
    public static final int EOF = 2;
    public static final int FAIL = 4;

    private final InputStream in;
    private final byte[] buf = new byte[1 << 16];
    private int pos;
    private int lim;
    private int state;
    private boolean open;
    private final boolean text;
    /** A '\r' ending the last chunk read, kept until the next byte tells whether it ends a line. */
    private boolean pendingCr;
    /** Characters given back by unget, read again before the stream. */
    private final StringBuilder pushback = new StringBuilder();

    public CInput(InputStream in) {
        this(in, true);
    }

    /** With text false the bytes are read as they are ("\r\n" kept). */
    public CInput(InputStream in, boolean text) {
        this.in = in;
        this.text = text;
        this.open = in != null;
        if (in == null) state = FAIL;
    }

    /** std::ifstream(filename): failed and closed when the file cannot be opened. */
    public static CInput open(Path file) {
        try {
            return new CInput(Files.newInputStream(file));
        } catch (IOException | RuntimeException e) {
            return new CInput(null);
        }
    }

    public boolean isOpen() {
        return open;
    }

    public int rdstate() {
        return state;
    }

    public boolean good() {
        return state == GOOD;
    }

    public boolean eof() {
        return (state & EOF) != 0;
    }

    public boolean fail() {
        return (state & (FAIL | BAD)) != 0;
    }

    public boolean bad() {
        return (state & BAD) != 0;
    }

    /** clear(): goodbit. */
    public void clear() {
        state = GOOD;
    }

    /** clear(state): exactly that state. */
    public void clear(int newState) {
        state = newState;
    }

    public void setstate(int bits) {
        state |= bits;
    }

    @Override
    public void close() {
        if (!open) return;
        open = false;
        try {
            in.close();
        } catch (IOException ignored) {
            // a stream closing badly changes nothing for the reader
        }
    }

    /** The next byte, or -1 at the end; -2 when an I/O error happened. */
    private int next() {
        if (pushback.length() > 0) {
            final char c = pushback.charAt(pushback.length() - 1);
            pushback.setLength(pushback.length() - 1);
            return c;
        }
        if (pos >= lim) {
            if (!fill()) return -1;
        }
        return buf[pos++] & 0xFF;
    }

    private int peekRaw() {
        if (pushback.length() > 0) return pushback.charAt(pushback.length() - 1);
        if (pos >= lim) {
            if (!fill()) return -1;
        }
        return buf[pos] & 0xFF;
    }

    private boolean fill() {
        if (!open) return false;
        try {
            while (true) {
                final int start = pendingCr ? 1 : 0;
                if (pendingCr) buf[0] = '\r';
                pendingCr = false;
                final int n = in.read(buf, start, buf.length - start);
                if (n <= 0) {
                    if (start == 0) return false;
                    // a '\r' that was the last byte of the input
                    pos = 0;
                    lim = 1;
                    return true;
                }
                final int end = text ? translate(start + n) : start + n;
                if (end == 0) continue;
                pos = 0;
                lim = end;
                return true;
            }
        } catch (IOException e) {
            state |= BAD;
            return false;
        }
    }

    /** Drops the '\r' of every "\r\n" in buf[0, end); a final '\r' waits for the next chunk. */
    private int translate(int end) {
        int w = 0;
        for (int r = 0; r < end; r++) {
            final byte b = buf[r];
            if (b == '\r') {
                if (r + 1 == end) {
                    pendingCr = true;
                    continue;
                }
                if (buf[r + 1] == '\n') continue;
            }
            buf[w++] = b;
        }
        return w;
    }

    /** The sentry: a stream not good gets failbit and refuses the operation. */
    private boolean sentry() {
        if (state != GOOD) {
            state |= FAIL;
            return false;
        }
        return true;
    }

    /**
     * istream::getline(char* s, n): up to n-1 characters or the newline,
     * which is consumed and not stored. Returns what the buffer holds after
     * the call (empty when it failed). Sets eofbit when the end came before a
     * newline, failbit when nothing was extracted or the buffer filled first.
     */
    public String getline(int n) {
        final StringBuilder s = new StringBuilder(128);
        int extracted = 0;
        if (sentry()) {
            while (true) {
                final int c = peekRaw();
                if (c < 0) {
                    state |= EOF;
                    break;
                }
                if (c == '\n') {
                    next();
                    extracted++;
                    break;
                }
                if (s.length() >= n - 1) {
                    state |= FAIL;
                    break;
                }
                next();
                s.append((char) c);
                extracted++;
            }
        }
        if (extracted == 0) state |= FAIL;
        return s.toString();
    }

    /**
     * std::getline(is, string): the whole line however long. Null with
     * failbit when nothing could be extracted.
     */
    public String getline() {
        if (!sentry()) return null;
        final StringBuilder s = new StringBuilder(128);
        int extracted = 0;
        while (true) {
            final int c = next();
            if (c < 0) {
                state |= EOF;
                break;
            }
            extracted++;
            if (c == '\n') break;
            s.append((char) c);
        }
        if (extracted == 0) {
            state |= FAIL;
            return null;
        }
        return s.toString();
    }

    /** istream::peek(): the next character, or -1 with eofbit at the end. */
    public int peek() {
        if (!sentry()) return -1;
        final int c = peekRaw();
        if (c < 0) state |= EOF;
        return c;
    }

    /** istream::read(s, n): what could be read; eofbit and failbit when fewer than n. */
    public String read(int n) {
        final StringBuilder s = new StringBuilder(n);
        if (sentry()) {
            while (s.length() < n) {
                final int c = next();
                if (c < 0) {
                    state |= EOF | FAIL;
                    break;
                }
                s.append((char) c);
            }
        }
        return s.toString();
    }

    /** Gives characters back, last first, so that they are read again: what sungetc does on a file buffer. */
    public void unread(String chars) {
        // pushback is a stack read from its end: the first of these must end on top
        for (int i = chars.length() - 1; i >= 0; i--) pushback.append(chars.charAt(i));
    }

    /** Reads everything left, for a reader that needs the whole input (LHEF). */
    public byte[] readAllRemaining() throws IOException {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = pushback.length() - 1; i >= 0; i--) out.write(pushback.charAt(i));
        pushback.setLength(0);
        if (pos < lim) out.write(buf, pos, lim - pos);
        pos = lim;
        if (pendingCr) out.write('\r');
        pendingCr = false;
        if (open) out.write(in.readAllBytes());
        final byte[] all = out.toByteArray();
        if (!text) return all;
        int w = 0;
        for (int r = 0; r < all.length; r++) {
            if (all[r] == '\r' && r + 1 < all.length && all[r + 1] == '\n') continue;
            all[w++] = all[r];
        }
        return java.util.Arrays.copyOf(all, w);
    }
}
