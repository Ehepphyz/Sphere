package com.sphere.core.hepmc3;

import com.sphere.components.rootview.RootKey;
import com.sphere.core.hepmc3.cxx.CFiles;
import com.sphere.core.rootio.RFileWriter;
import com.sphere.core.rootio.RObject;
import com.sphere.core.rootio.RootIO;
import com.sphere.core.rootio.TreeWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes events into a ROOT tree as HepMC3's WriterRootTree does (tree
 * "hepmc3_tree" titled "hepmc3_tree", branch "hepmc3_event" of GenEventData
 * and branch "GenRunInfo" of GenRunInfoData, both split at level 99, one
 * entry per event), with Sphere's own ROOT writer: no ROOT needed. The file is
 * the one ROOT 6.20/04 writes: same records, same baskets, same bookkeeping.
 *
 * <p>With append, the file is opened as TFile's "UPDATE" mode opens it: what it
 * holds stays, and events go on after those of its tree when it has one.
 */
public class WriterRootTree extends Writer {

    private final String treeName;
    private final String branchName;
    private RFileWriter file;
    private TreeWriter tree;
    private GenRunInfoData runInfoData;
    private RObject runInfoObject;
    private int eventsCount;
    private boolean open;

    public WriterRootTree(String filename) {
        this(filename, null);
    }

    public WriterRootTree(String filename, GenRunInfo run) {
        this(filename, "hepmc3_tree", "hepmc3_event", run, false);
    }

    public WriterRootTree(String filename, GenRunInfo run, boolean append) {
        this(filename, "hepmc3_tree", "hepmc3_event", run, append);
    }

    public WriterRootTree(String filename, String treename, String branchname, GenRunInfo run) {
        this(filename, treename, branchname, run, false);
    }

    public WriterRootTree(String filename, String treename, String branchname, GenRunInfo run, boolean append) {
        this.treeName = treename;
        this.branchName = branchname;
        final Path path = CFiles.path(filename);
        if (append && Files.isRegularFile(path)) {
            update(path, filename, run);
        } else {
            try {
                file = RootStreamerInfo.create(path, filename);
                open = true;
            } catch (IOException | RuntimeException e) {
                open = false;
            }
            if (!init(filename, run)) return;
            tree = new TreeWriter(file, RootStreamerInfo.streamers(), RootStreamerInfo.objectWriter(), treeName, "hepmc3_tree");
            tree.branch(branchName, RootData.EVENT_CLASS);
            tree.branch("GenRunInfo", RootData.RUN_CLASS);
        }
    }

    /** The run information as the entries will carry it. */
    private boolean init(String filename, GenRunInfo run) {
        if (!open) {
            Setup.error(100, "WriterRootTree: problem opening file: " + filename);
            return false;
        }
        runInfoData = new GenRunInfoData();
        setRunInfo(run);
        if (runInfo() != null) runInfo().writeData(runInfoData);
        runInfoObject = RootData.object(runInfoData);
        return true;
    }

    /**
     * TFile::Open with "UPDATE": the tree of that name, if the file has one,
     * must have the branches written here, and is filled on; otherwise the
     * name must be free, and the tree is made.
     */
    private void update(Path path, String filename, GenRunInfo run) {
        RObject existing = null;
        String other = null;
        String eventClass = null;
        String runClass = null;
        byte[] merged = null;
        try (RootIO io = RootIO.open(path)) {
            final RootKey key = io.key(treeName);
            if (key != null && key.className.equals("TTree")) {
                existing = (RObject) io.read(key);
                for (Object b : existing.list("fBranches")) {
                    final RObject br = (RObject) b;
                    if (br.string("fName").equals(branchName)) eventClass = br.string("fClassName");
                    if (br.string("fName").equals("GenRunInfo")) runClass = br.string("fClassName");
                }
            } else if (key != null) {
                other = key.className;
            }
            // the class descriptions: kept when the file has those written here, else completed
            merged = io.file().streamerInfoKey() == null
                ? RootStreamerInfo.list()
                : RootStreamerInfo.merged(io.streamers());
            file = RFileWriter.update(path, filename);
            open = true;
        } catch (IOException | RuntimeException e) {
            open = false;
        }
        if (!init(filename, run)) return;
        if (existing != null) {
            final String[][] required = {{branchName, eventClass, RootData.EVENT_CLASS}, {"GenRunInfo", runClass, RootData.RUN_CLASS}};
            for (String[] r : required) {
                if (r[1] == null) {
                    Setup.error(100, "WriterRootTree: existing tree '" + treeName + "' missing required branch '" + r[0] + "'");
                    abandon();
                    return;
                }
                if (!r[1].equals(r[2])) {
                    Setup.error(100, "WriterRootTree: branch '" + r[0] + "' has incompatible type: " + r[1] + " (expected " + r[2] + ")");
                    abandon();
                    return;
                }
            }
        } else if (other != null) {
            Setup.error(100, "WriterRootTree: object named '" + treeName + "' already exists in file but is not a TTree (type: "
                + other + ")");
            abandon();
            return;
        }
        if (merged != null) file.replaceStreamerInfo(merged, RootStreamerInfo.KEYLEN);
        tree = new TreeWriter(file, RootStreamerInfo.streamers(), RootStreamerInfo.objectWriter(), treeName, "hepmc3_tree");
        tree.branch(branchName, RootData.EVENT_CLASS);
        tree.branch("GenRunInfo", RootData.RUN_CLASS);
        if (existing != null) {
            try {
                tree.resume(existing, file.key(treeName));
            } catch (IOException | RuntimeException e) {
                Setup.error(100, "WriterRootTree: existing tree '" + treeName + "' cannot be continued: " + e.getMessage());
                abandon();
            }
        }
    }

    /** The file stays as it was found. */
    private void abandon() {
        if (file != null) file.abandon();
        file = null;
        open = false;
    }

    @Override
    public void writeEvent(GenEvent evt) {
        if (!open) return;
        boolean refill = false;
        if (evt.runInfo() != null && (runInfo() == null || runInfo() != evt.runInfo())) {
            setRunInfo(evt.runInfo());
            refill = true;
        }
        if (refill) {
            runInfoData.clear();
            runInfo().writeData(runInfoData);
            runInfoObject = RootData.object(runInfoData);
        }
        final GenEventData data = new GenEventData();
        evt.writeData(data);
        try {
            tree.fill(RootData.object(data), runInfoObject);
        } catch (IOException | RuntimeException e) {
            Setup.error(100, "WriterRootTree: error writing event: " + e.getMessage());
            abandon();
            return;
        }
        ++eventsCount;
    }

    /** The run information goes with every entry of the tree: nothing to write on its own. */
    public void writeRunInfo() {
    }

    @Override
    public void close() {
        if (file == null) return;
        try {
            if (tree != null) tree.write();
            file.close();
        } catch (IOException | RuntimeException e) {
            Setup.error(100, "WriterRootTree: problem closing file: " + e.getMessage());
        }
        open = false;
        file = null;
    }

    @Override
    public boolean failed() {
        return !open;
    }

    /** The events written by this writer. */
    public int eventsCount() {
        return eventsCount;
    }
}
