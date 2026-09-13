package com.sphere.components.rootview;

import java.util.ArrayList;
import java.util.List;

/** One entry in the file's tree: a directory, or an object with its key. */
public final class RootNode {

    public final String name;
    public final String title;
    public final String className;
    public final RootKey key;
    public final List<RootNode> children = new ArrayList<>();

    public boolean directory;
    public String note;

    /** Set on a node that stands for a branch of a tree rather than a key. */
    public boolean branch;
    /** The job id the engine bound this node's tree to, 0 when none. */
    public int jobId;

    public RootNode(String name, String title, String className, RootKey key) {
        this.name = name == null ? "" : name;
        this.title = title == null ? "" : title;
        this.className = className == null ? "" : className;
        this.key = key;
    }

    public boolean isHistogram() {
        // TH2Poly shares the prefix but not the layout: its bins are shapes.
        if (className.startsWith("TH2Poly")) {
            return false;
        }
        return className.startsWith("TH1") || className.startsWith("TH2")
            || className.startsWith("TH3") || className.startsWith("TProfile");
    }

    public boolean isGraph() {
        // A TGraph2D is not built on TGraph and has three arrays, not two.
        if (isGraph2D()) {
            return false;
        }
        return className.startsWith("TGraph");
    }

    public boolean isGraph2D() {
        return className.startsWith("TGraph2D");
    }

    public boolean isTree() {
        return className.equals("TTree") || className.equals("TNtuple")
            || className.equals("TNtupleD") || className.equals("TChain");
    }

    public boolean isNtuple() {
        return className.startsWith("RNTuple");
    }

    /** True when this reader can decode the object without ROOT. */
    public boolean isReadableHere() {
        return isHistogram() || isGraph() || isGraph2D() || isTree();
    }

    /** The path from the file down to this node, for the status bar. */
    public String pathFrom(RootNode root) {
        List<String> parts = new ArrayList<>();
        collectPath(root, this, parts);
        return parts.isEmpty() ? name : String.join("/", parts);
    }

    private static boolean collectPath(RootNode from, RootNode target, List<String> into) {
        if (from == target) {
            into.add(from.name);
            return true;
        }
        for (RootNode child : from.children) {
            if (collectPath(child, target, into)) {
                into.add(0, from.name);
                return true;
            }
        }
        return false;
    }

    public int countObjects() {
        int total = 0;
        for (RootNode child : children) {
            total += child.directory ? child.countObjects() : 1;
        }
        return total;
    }

    @Override
    public String toString() {
        return name;
    }
}
