package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.COutput;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * A writer to a compressed output (WriterGZ&lt;T, C&gt; of HepMC3): a writer
 * of some format into a stream that compresses, gzip by default, e.g.
 * {@code new WriterGZ(path, WriterAscii::new, run)}.
 */
public class WriterGZ extends Writer {

    private final Writer writer;
    private final OutputStream zstr;

    public WriterGZ(Path filename, BiFunction<COutput, GenRunInfo, ? extends Writer> inner, GenRunInfo run) {
        this(filename, inner, run, Compression.Z);
    }

    public WriterGZ(Path filename, BiFunction<COutput, GenRunInfo, ? extends Writer> inner, GenRunInfo run, Compression c) {
        OutputStream s;
        try {
            s = c.compressing(Files.newOutputStream(filename));
        } catch (IOException e) {
            Setup.error(100, "WriterGZ: could not open output file: " + filename + " (" + e.getMessage() + ")");
            s = null;
        }
        zstr = s;
        writer = s == null ? null : inner.apply(new COutput(s), run);
    }

    public WriterGZ(OutputStream stream, BiFunction<COutput, GenRunInfo, ? extends Writer> inner, GenRunInfo run, Compression c) {
        OutputStream s;
        try {
            s = c.compressing(stream);
        } catch (IOException e) {
            Setup.error(100, "WriterGZ: " + e.getMessage());
            s = null;
        }
        zstr = s;
        writer = s == null ? null : inner.apply(new COutput(s), run);
    }

    @Override
    public void writeEvent(GenEvent evt) {
        if (writer != null) writer.writeEvent(evt);
    }

    @Override
    public boolean failed() {
        return writer == null || writer.failed();
    }

    @Override
    public void close() {
        if (writer != null) writer.close();
        if (zstr != null) {
            try {
                zstr.close();
            } catch (IOException e) {
                Setup.error(100, "WriterGZ: " + e.getMessage());
            }
        }
    }

    @Override
    public void setRunInfo(GenRunInfo run) {
        if (writer != null) writer.setRunInfo(run);
    }

    @Override
    public GenRunInfo runInfo() {
        return writer != null ? writer.runInfo() : null;
    }

    @Override
    public void setOptions(Map<String, String> opts) {
        if (writer != null) writer.setOptions(opts);
    }

    public Writer writer() {
        return writer;
    }
}
