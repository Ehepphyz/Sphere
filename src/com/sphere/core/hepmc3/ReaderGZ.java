package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CInput;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * A reader of a compressed input: the input decompressed on the fly and
 * handed to a reader of the format inside (ReaderGZ&lt;T&gt; of HepMC3), e.g.
 * {@code new ReaderGZ(path, ReaderAscii::new)}.
 */
public class ReaderGZ extends Reader {

    private final Reader reader;
    private final InputStream zstr;

    public ReaderGZ(Path filename, Function<CInput, ? extends Reader> inner) {
        InputStream s;
        try {
            s = Compression.open(filename);
        } catch (IOException e) {
            Setup.error(100, "ReaderGZ: could not open input file: " + filename + " (" + e.getMessage() + ")");
            s = null;
        }
        zstr = s;
        reader = s == null ? null : inner.apply(new CInput(s));
    }

    public ReaderGZ(InputStream is, Function<CInput, ? extends Reader> inner) {
        InputStream s;
        try {
            s = Compression.decompressing(is);
        } catch (IOException e) {
            Setup.error(100, "ReaderGZ: could not open input stream (" + e.getMessage() + ")");
            s = null;
        }
        zstr = s;
        reader = s == null ? null : inner.apply(new CInput(s));
    }

    @Override
    public boolean skip(int i) {
        return reader != null && reader.skip(i);
    }

    @Override
    public boolean readEvent(GenEvent evt) {
        return reader != null && reader.readEvent(evt);
    }

    @Override
    public boolean failed() {
        return reader != null && reader.failed();
    }

    @Override
    public void close() {
        if (reader != null) {
            reader.close();
            return;
        }
        if (zstr != null) {
            try {
                zstr.close();
            } catch (IOException ignored) {
                // nothing left to read either way
            }
        }
    }

    @Override
    public void setRunInfo(GenRunInfo run) {
        if (reader != null) reader.setRunInfo(run);
    }

    @Override
    public GenRunInfo runInfo() {
        return reader != null ? reader.runInfo() : null;
    }

    @Override
    public void setOptions(Map<String, String> opts) {
        if (reader != null) reader.setOptions(opts);
    }

    @Override
    public Map<String, String> getOptions() {
        return reader != null ? reader.getOptions() : super.getOptions();
    }

    /** The reader of the format inside. */
    public Reader reader() {
        return reader;
    }
}
