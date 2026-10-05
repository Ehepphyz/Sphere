package com.sphere.core.hepmc3.cxx;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * std::ostream for the writers: text written byte for byte (ISO-8859-1, the
 * one char per byte the readers produce), a state that turns bad when the
 * system refuses a write, and a close that may be called twice.
 *
 * <p>An instance over a StringBuilder is std::ostringstream.
 */
public class COutput implements AutoCloseable {

    private final OutputStream out;
    private final StringBuilder text;
    private boolean bad;
    private boolean open;
    private final byte[] chunk = new byte[1 << 15];

    public COutput(OutputStream out) {
        this.out = out instanceof BufferedOutputStream ? out : new BufferedOutputStream(out, 1 << 16);
        this.text = null;
        this.open = true;
    }

    /** An ostringstream: what is written can be read back with {@link #str()}. */
    public COutput(StringBuilder into) {
        this.out = null;
        this.text = into;
        this.open = true;
    }

    public COutput() {
        this(new StringBuilder());
    }

    /** std::ofstream(filename): bad and closed when the file cannot be created. */
    public static COutput create(Path file) {
        try {
            if (file.toAbsolutePath().getParent() != null) Files.createDirectories(file.toAbsolutePath().getParent());
            return new COutput(Files.newOutputStream(file));
        } catch (IOException | RuntimeException e) {
            final COutput failed = new COutput(new StringBuilder());
            failed.bad = true;
            failed.open = false;
            return failed;
        }
    }

    public boolean isOpen() {
        return open;
    }

    public boolean good() {
        return !bad;
    }

    /** rdstate() != 0. */
    public boolean failed() {
        return bad;
    }

    public String str() {
        return text == null ? "" : text.toString();
    }

    public COutput write(CharSequence s) {
        if (!open) {
            bad = true;
            return this;
        }
        if (text != null) {
            text.append(s);
            return this;
        }
        try {
            final int n = s.length();
            int i = 0;
            while (i < n) {
                final int m = Math.min(chunk.length, n - i);
                for (int k = 0; k < m; k++) chunk[k] = (byte) s.charAt(i + k);
                out.write(chunk, 0, m);
                i += m;
            }
        } catch (IOException e) {
            bad = true;
        }
        return this;
    }

    public COutput write(char c) {
        if (!open) {
            bad = true;
            return this;
        }
        if (text != null) {
            text.append(c);
            return this;
        }
        try {
            out.write((byte) c);
        } catch (IOException e) {
            bad = true;
        }
        return this;
    }

    /** Raw bytes, for the binary formats. */
    public COutput writeBytes(byte[] b, int off, int len) {
        if (!open) {
            bad = true;
            return this;
        }
        if (text != null) {
            for (int k = 0; k < len; k++) text.append((char) (b[off + k] & 0xFF));
            return this;
        }
        try {
            out.write(b, off, len);
        } catch (IOException e) {
            bad = true;
        }
        return this;
    }

    /** This stream as an OutputStream, for a writer of a binary format (protobuf in WriterGZ). */
    public OutputStream asStream() {
        return new OutputStream() {
            @Override
            public void write(int b) {
                COutput.this.write((char) (b & 0xFF));
            }

            @Override
            public void write(byte[] b, int off, int len) {
                writeBytes(b, off, len);
            }

            @Override
            public void flush() {
                COutput.this.flush();
            }

            @Override
            public void close() {
                COutput.this.close();
            }
        };
    }

    public void flush() {
        if (out == null || !open) return;
        try {
            out.flush();
        } catch (IOException e) {
            bad = true;
        }
    }

    @Override
    public void close() {
        if (!open) return;
        open = false;
        if (out == null) return;
        try {
            out.close();
        } catch (IOException e) {
            bad = true;
        }
    }
}
