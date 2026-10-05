package com.sphere.core.hepmc3;

import com.sphere.components.rootview.RootKey;
import com.sphere.core.hepmc3.cxx.CFiles;
import com.sphere.core.rootio.RObject;
import com.sphere.core.rootio.RootIO;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * Reads events from a ROOT file holding one GenEventData object per key, as
 * HepMC3's ReaderRoot does (the format WriterRoot writes), with Sphere's own
 * ROOT reader. Keys are visited in the order the file lists them.
 */
public class ReaderRoot extends Reader {

    private RootIO file;
    private boolean open;
    private Iterator<RootKey> next;

    public ReaderRoot(String filename) {
        try {
            file = RootIO.open(CFiles.path(filename));
            open = true;
            final List<RootKey> keys = file.keys();
            next = keys.iterator();
        } catch (IOException | RuntimeException e) {
            open = false;
        }
        if (!open) {
            Setup.error(100, "ReaderRoot: problem opening file: " + filename);
            return;
        }
        final GenRunInfo ri = new GenRunInfo();
        try {
            if (file.key("GenRunInfoData") != null && file.read("GenRunInfoData") instanceof RObject run) {
                ri.readData(RootData.run(run));
            }
        } catch (IOException | RuntimeException unreadable) {
            // the run information stays empty, as when the object is absent
        }
        setRunInfo(ri);
    }

    @Override
    public boolean skip(int n) {
        final GenEvent evt = new GenEvent();
        for (int nn = n; nn > 0; --nn) {
            if (!readEvent(evt)) return false;
            evt.clear();
        }
        return !failed();
    }

    @Override
    public boolean readEvent(GenEvent evt) {
        if (!open) return false;
        RObject data = null;
        while (true) {
            if (!next.hasNext()) {
                close();
                return false;
            }
            final RootKey key = next.next();
            final String cl = key.className;
            if (cl == null) continue;
            final boolean geneventdata30 = cl.startsWith("HepMC::GenEventData");
            final boolean geneventdata31 = cl.startsWith("HepMC3::GenEventData");
            if (geneventdata31 || geneventdata30) {
                if (geneventdata30) {
                    Setup.warning(900, "ReaderRoot::read_event: The object was written with HepMC3 version 3.0");
                }
                try {
                    if (file.read(key) instanceof RObject o) data = o;
                } catch (IOException | RuntimeException unreadable) {
                    data = null;
                }
                break;
            }
        }
        if (data == null) {
            Setup.error(100, "ReaderRoot: could not read event from root file");
            close();
            return false;
        }
        evt.readData(RootData.event(data));
        evt.setRunInfo(runInfo());
        return true;
    }

    @Override
    public void close() {
        if (!open) return;
        open = false;
        try {
            file.close();
        } catch (IOException ignored) {
            // closing a file read to its end
        }
    }

    @Override
    public boolean failed() {
        return !open;
    }
}
