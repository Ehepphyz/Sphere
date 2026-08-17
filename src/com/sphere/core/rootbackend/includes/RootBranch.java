package com.sphere.core.rootbackend.includes;

import com.sphere.core.rootbackend.RootProcessBridge;
import com.sphere.utils.AppLogger;

/**
 * High-level object wrapper representing a specific TBranch inside a TTree.
 * Operates asynchronously over the FFM lock-free SHM command ring.
 */
public final class RootBranch {

    private final RootProcessBridge bridge;
    private final String treeId;
    private final String branchName;

    public RootBranch(RootProcessBridge bridge, String treeId, String branchName) {
        this.bridge = bridge;
        this.treeId = treeId;
        this.branchName = branchName;
    }

    /**
     * Pushes an instruction to print the structure and metadata of this branch via Cling.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean print() {
        String clingCmd = String.format(
            "if (auto* t = (TTree*)%s) { " +
            "  if (auto* b = t->GetBranch(\"%s\")) { b->Print(); } " +
            "  else { std::cerr << \"ERROR: Branch '%s' not found.\\n\"; } " +
            "} else { std::cerr << \"ERROR: Tree handle '%s' is invalid.\\n\"; }",
            treeId, branchName, branchName, treeId
        );
        return bridge.pushCommand("CLING_EXEC " + clingCmd);
    }

    /**
     * Queues an asynchronous query to retrieve the total number of entries recorded in this branch.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean requestEntries() {
        String clingCmd = String.format(
            "{ auto* t = (TTree*)%s; " +
            "  if (t) { " +
            "    if (auto* b = t->GetBranch(\"%s\")) { " +
            "      std::cout << \"OK \" << b->GetEntries() << \"\\n\"; " +
            "    } else { std::cout << \"ERROR BranchNotFound\\n\"; } " +
            "  } else { std::cout << \"ERROR InvalidTree\\n\"; } }",
            treeId, branchName
        );

        boolean queued = bridge.pushCommand("CLING_EXEC " + clingCmd);
        if (!queued) {
            AppLogger.error("Failed to queue entry count request for branch: " + branchName);
        }
        return queued;
    }

    /**
     * Queues an asynchronous query to inspect the branch class name representation
     * (e.g., "Double_t", "Float_t", "Int_t").
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean requestClassName() {
        String clingCmd = String.format(
            "{ auto* t = (TTree*)%s; " +
            "  if (t) { " +
            "    if (auto* b = t->GetBranch(\"%s\")) { " +
            "      std::cout << \"OK \" << b->GetClassName() << \"\\n\"; " +
            "    } else { std::cout << \"ERROR BranchNotFound\\n\"; } " +
            "  } else { std::cout << \"ERROR InvalidTree\\n\"; } }",
            treeId, branchName
        );

        boolean queued = bridge.pushCommand("CLING_EXEC " + clingCmd);
        if (!queued) {
            AppLogger.error("Failed to queue class name request for branch: " + branchName);
        }
        return queued;
    }

    public String getBranchName() {
        return branchName;
    }

    public String getTreeId() {
        return treeId;
    }
}