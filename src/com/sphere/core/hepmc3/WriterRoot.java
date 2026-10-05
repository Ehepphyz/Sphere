package com.sphere.core.hepmc3;

import com.sphere.core.rootio.ObjectWriter;
import com.sphere.core.rootio.RFileWriter;
import com.sphere.core.rootio.RObject;
import com.sphere.core.rootio.WBuffer;

import java.io.IOException;

/**
 * Writes events into a ROOT file as HepMC3's WriterRoot does: each event a
 * GenEventData object under its own key, named by its number on fifteen
 * characters ("%15i"), the run information a GenRunInfoData object under
 * the key "GenRunInfoData", with Sphere's own ROOT writer: no ROOT needed.
 */
public class WriterRoot extends Writer {

    /** The title TDirectoryFile::WriteObjectAny gives an object without one. */
    private static final String OBJECT_TITLE = "object title";

    private RFileWriter file;
    private final ObjectWriter writer;
    private int eventsCount;
    private boolean open;
    private boolean runWritten;

    public WriterRoot(String filename) {
        this(filename, null);
    }

    public WriterRoot(String filename, GenRunInfo run) {
        this.writer = RootStreamerInfo.objectWriter();
        setRunInfo(run);
        try {
            file = RootStreamerInfo.create(filename);
            open = true;
        } catch (IOException | RuntimeException e) {
            open = false;
        }
        if (!open) {
            Setup.error(100, "WriterRoot: problem opening file: " + filename);
            return;
        }
        if (runInfo() != null) writeRunInfo();
    }

    @Override
    public void writeEvent(GenEvent evt) {
        if (!open) return;
        if (runInfo() == null) {
            setRunInfo(evt.runInfo());
            writeRunInfo();
        } else if (evt.runInfo() != null && runInfo() != evt.runInfo()) {
            Setup.warning(100, "WriterRoot::write_event: GenEvents contain different GenRunInfo objects from - only the first such object will be serialized.");
        }
        final GenEventData data = new GenEventData();
        evt.writeData(data);
        if (!write(RootData.object(data), String.format("%15d", ++eventsCount))) {
            Setup.error(100, "WriterRoot: error writing event");
            closeFile();
        }
    }

    /** The run information, once, under the key "GenRunInfoData". */
    public void writeRunInfo() {
        if (!open || runInfo() == null) return;
        final GenRunInfoData data = new GenRunInfoData();
        runInfo().writeData(data);
        if (write(RootData.object(data), "GenRunInfoData")) {
            runWritten = true;
        } else {
            Setup.error(100, "WriterRoot: error writing GenRunInfo");
            closeFile();
        }
    }

    /** TDirectoryFile::WriteObjectAny: the object streamed for its key, the object itself mapped at 1. */
    private boolean write(RObject object, String name) {
        try {
            file.writeObject(object.className, name, OBJECT_TITLE, keylen -> {
                final WBuffer b = new WBuffer(keylen);
                b.mapObject(object, 1L);
                writer.writeClass(b, object.className, object);
                return b.toByteArray();
            });
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public void close() {
        closeFile();
    }

    private void closeFile() {
        if (file == null) return;
        try {
            // TFile::WriteStreamerInfo: the descriptions of the classes written, none if none was
            final java.util.List<String> used = new java.util.ArrayList<>();
            if (runWritten) used.add(RootData.RUN_CLASS);
            if (eventsCount > 0) used.addAll(java.util.List.of(RootData.EVENT_CLASS, "HepMC3::GenParticleData", "HepMC3::FourVector",
                "HepMC3::GenVertexData"));
            file.replaceStreamerInfo(used.isEmpty() ? null : RootStreamerInfo.list(used.toArray(new String[0])), RootStreamerInfo.KEYLEN);
            file.close();
        } catch (IOException e) {
            Setup.error(100, "WriterRoot: problem closing file: " + e.getMessage());
        }
        file = null;
        open = false;
    }

    @Override
    public boolean failed() {
        return !open;
    }
}
