package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFiles;
import com.sphere.core.hepmc3.cxx.CInput;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A reader made by a plugin, as HepMC3's ReaderPlugin loads one from a
 * shared library by the name of its factory function. In Java the library
 * is a jar (or the class path when it is empty) and the factory either a
 * class name, built with a constructor taking the file name (or a stream),
 * or "Class#method", a static method doing the same.
 */
public class ReaderPlugin extends Reader {

    private Reader reader;

    public ReaderPlugin(String filename, String libname, String newreader) {
        reader = make(libname, newreader, String.class, filename);
        if (reader == null) reader = make(libname, newreader, Path.class, CFiles.path(filename));
    }

    public ReaderPlugin(InputStream stream, String libname, String newreader) {
        reader = make(libname, newreader, InputStream.class, stream);
        if (reader == null) reader = make(libname, newreader, CInput.class, new CInput(stream));
    }

    private static Reader make(String libname, String factory, Class<?> argType, Object arg) {
        final ClassLoader loader;
        try {
            loader = loader(libname);
        } catch (Exception e) {
            StdStreams.cout().printf("Error  while loading library %s: %s\n", libname, String.valueOf(e.getMessage()));
            return null;
        }
        try {
            final int hash = factory.indexOf('#');
            final Class<?> c = Class.forName(hash < 0 ? factory : factory.substring(0, hash), true, loader);
            if (hash >= 0) {
                final Method m = c.getMethod(factory.substring(hash + 1), argType);
                return (Reader) m.invoke(null, arg);
            }
            final Constructor<?> k = c.getConstructor(argType);
            return (Reader) k.newInstance(arg);
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Exception | LinkageError e) {
            StdStreams.cout().printf("Error  while loading function %s from  library %s: %s\n", factory, libname,
                String.valueOf(e.getMessage()));
            return null;
        }
    }

    /** The class loader of a jar, or of the class path for an empty name. */
    static ClassLoader loader(String libname) throws Exception {
        if (libname == null || libname.isBlank()) return ReaderPlugin.class.getClassLoader();
        final Path jar = CFiles.path(libname);
        if (!Files.isRegularFile(jar)) throw new java.io.FileNotFoundException(libname + " not found");
        return new URLClassLoader(new URL[]{jar.toUri().toURL()}, ReaderPlugin.class.getClassLoader());
    }

    @Override
    public boolean skip(int n) {
        return reader != null && reader.skip(n);
    }

    @Override
    public boolean readEvent(GenEvent ev) {
        return reader != null && reader.readEvent(ev);
    }

    @Override
    public void close() {
        if (reader != null) reader.close();
    }

    @Override
    public boolean failed() {
        return reader == null || reader.failed();
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
        return reader != null ? reader.getOptions() : new java.util.TreeMap<>();
    }

    /** The reader the plugin made, or null. */
    public Reader reader() {
        return reader;
    }
}
