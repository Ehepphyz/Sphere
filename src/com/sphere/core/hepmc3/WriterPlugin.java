package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFiles;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;

/**
 * A writer made by a plugin, as HepMC3's WriterPlugin: a class (or
 * "Class#method") of a jar built with the file name and the run
 * information. See {@link ReaderPlugin}.
 */
public class WriterPlugin extends Writer {

    private Writer writer;

    public WriterPlugin(String filename, String libname, String newwriter, GenRunInfo run) {
        writer = make(libname, newwriter, String.class, filename, run);
        if (writer == null) writer = make(libname, newwriter, Path.class, CFiles.path(filename), run);
    }

    public WriterPlugin(OutputStream stream, String libname, String newwriter, GenRunInfo run) {
        writer = make(libname, newwriter, OutputStream.class, stream, run);
    }

    private static Writer make(String libname, String factory, Class<?> argType, Object arg, GenRunInfo run) {
        final ClassLoader loader;
        try {
            loader = ReaderPlugin.loader(libname);
        } catch (Exception e) {
            StdStreams.cout().printf("Error  while loading library %s: %s\n", libname, String.valueOf(e.getMessage()));
            return null;
        }
        try {
            final int hash = factory.indexOf('#');
            final Class<?> c = Class.forName(hash < 0 ? factory : factory.substring(0, hash), true, loader);
            if (hash >= 0) {
                final Method m = c.getMethod(factory.substring(hash + 1), argType, GenRunInfo.class);
                return (Writer) m.invoke(null, arg, run);
            }
            final Constructor<?> k = c.getConstructor(argType, GenRunInfo.class);
            return (Writer) k.newInstance(arg, run);
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Exception | LinkageError e) {
            StdStreams.cout().printf("Error  while loading function %s from  library %s: %s\n", factory, libname,
                String.valueOf(e.getMessage()));
            return null;
        }
    }

    @Override
    public void writeEvent(GenEvent ev) {
        if (writer != null) writer.writeEvent(ev);
    }

    @Override
    public void close() {
        if (writer != null) writer.close();
    }

    @Override
    public boolean failed() {
        return writer == null || writer.failed();
    }

    @Override
    public GenRunInfo runInfo() {
        return writer != null ? writer.runInfo() : null;
    }

    @Override
    public void setRunInfo(GenRunInfo run) {
        if (writer != null) writer.setRunInfo(run);
    }

    @Override
    public void setOptions(Map<String, String> opts) {
        if (writer != null) writer.setOptions(opts);
    }

    public Writer writer() {
        return writer;
    }
}
