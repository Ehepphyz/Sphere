package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFiles;
import com.sphere.core.rootio.RObject;
import com.sphere.core.rootio.RTree;
import com.sphere.core.rootio.RootIO;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Reads events from a ROOT tree (tree "hepmc3_tree", branch "hepmc3_event" of
 * GenEventData split into its members, branch "GenRunInfo"), as HepMC3's
 * ReaderRootTree does, with Sphere's own ROOT reader: no ROOT needed. Files
 * of HepMC3 3.0, whose run information is a GenRunInfoData object beside the
 * tree, are read too.
 */
public class ReaderRootTree extends Reader {

    private final String treeName;
    private final String branchName;
    private RootIO file;
    private boolean open;
    private RTree tree;
    private RootData.EventBranches event;
    private RootData.RunBranches runBranches;
    private long eventsCount;

    public ReaderRootTree(String filename) {
        this(filename, "hepmc3_tree", "hepmc3_event");
    }

    public ReaderRootTree(String filename, String treename, String branchname) {
        this.treeName = treename;
        this.branchName = branchname;
        try {
            file = RootIO.open(CFiles.path(filename));
            open = true;
        } catch (IOException | RuntimeException e) {
            open = false;
        }
        if (!init(filename)) return;
    }

    /** A reader for a ROOT file of events (deduce_reader's), or null when it cannot be had. */
    public static Reader open(String filename) {
        if (!filename.contains("://") && !Files.isRegularFile(CFiles.path(filename))) {
            Setup.error(100, "ReaderRootTree: problem opening file: " + filename);
            return null;
        }
        return new ReaderRootTree(filename);
    }

    private boolean init(String filename) {
        if (!open) {
            Setup.error(100, "ReaderRootTree: problem opening file: " + filename);
            return false;
        }
        try {
            if (file.key(treeName) == null) {
                Setup.error(100, "ReaderRootTree: problem opening tree:  " + treeName);
                return false;
            }
            tree = file.tree(treeName);
        } catch (IOException | RuntimeException e) {
            Setup.error(100, "ReaderRootTree: problem opening tree:  " + treeName);
            return false;
        }
        final RTree.RBranch top = tree.branch(branchName);
        if (top == null || !RootData.isEventData(top.elementClass())) {
            Setup.error(100, "ReaderRootTree: problem reading branch tree:  " + treeName);
            return false;
        }
        event = new RootData.EventBranches(top);
        final RTree.RBranch run = tree.branch("GenRunInfo");
        if (run == null) {
            Setup.warning(100, "ReaderRootTree: problem reading branch tree: GenRunInfo. Will attempt to read GenRunInfoData object.");
            final GenRunInfo ri = new GenRunInfo();
            RObject data = null;
            try {
                if (file.key("GenRunInfoData") != null && file.read("GenRunInfoData") instanceof RObject o) data = o;
            } catch (IOException | RuntimeException unreadable) {
                data = null;
            }
            if (data != null) {
                ri.readData(RootData.run(data));
                setRunInfo(ri);
                Setup.warning(900, "ReaderRootTree::init The object was written with HepMC3 version 3.0");
            } else {
                Setup.error(100, "ReaderRootTree: problem reading object GenRunInfoData");
                return false;
            }
        } else {
            runBranches = new RootData.RunBranches(run);
        }
        // as HepMC3 does: the run information is then the tree's, entry by entry
        setRunInfo(new GenRunInfo());
        return true;
    }

    private long entries() {
        return tree == null ? 0 : tree.entries();
    }

    @Override
    public boolean skip(int n) {
        eventsCount += n;
        return eventsCount < entries();
    }

    @Override
    public boolean readEvent(GenEvent evt) {
        if (eventsCount >= entries()) {
            eventsCount++;
            return false;
        }
        return readAt(evt, eventsCount++);
    }

    /** read_event_at_index: the event of that entry. */
    public boolean readEventAtIndex(GenEvent evt, long index) {
        if (index >= entries()) return false;
        return readAt(evt, index);
    }

    private boolean readAt(GenEvent evt, long index) {
        final GenRunInfoData runData = new GenRunInfoData();
        GenEventData data;
        try {
            data = event.read(index);
            if (runBranches != null) runBranches.read(index, runData);
        } catch (IOException | RuntimeException e) {
            Setup.error(100, "ReaderRootTree: problem reading entry " + index + ": " + e.getMessage());
            data = new GenEventData();
        }
        evt.readData(data);
        runInfo().readData(runData);
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
            // nothing more to read anyway
        }
    }

    @Override
    public boolean failed() {
        if (!open) return true;
        return eventsCount > entries();
    }
}
