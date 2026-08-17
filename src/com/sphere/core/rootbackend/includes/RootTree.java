package com.sphere.core.rootbackend.includes;

import com.sphere.core.rootbackend.RootProcessBridge;
import com.sphere.utils.AppLogger;

/**
 * High-level object wrapper representing a TTree dataset structure in ROOT.
 * Operates asynchronously over the FFM lock-free SHM command ring.
 */
public final class RootTree {

    private final RootProcessBridge bridge;
    private final String handleId;

    public RootTree(RootProcessBridge bridge, String handleId) {
        this.bridge = bridge;
        this.handleId = handleId;
    }

    public String getHandleId() {
        return handleId;
    }

    /**
     * Instantiates a high-level representation of a specific branch.
     * Use {@link #requestBranchVerification(String)} beforehand if you need to guarantee existence.
     */
    public RootBranch getBranch(String name) {
        return new RootBranch(bridge, handleId, name);
    }

    /**
     * Queues a query to verify if a branch exists within this tree in the native process.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean requestBranchVerification(String name) {
        String clingCmd = String.format(
            "{ TTree* t = (TTree*)gROOT->FindObject(\"%s\"); " +
            "  if (t && t->GetBranch(\"%s\")) { " +
            "    std::cout << \"OK true\\n\"; " +
            "  } else { " +
            "    std::cout << \"OK false\\n\"; " +
            "  } }",
            handleId, name
        );
        return pushClingCommand(clingCmd, "verify branch existence: " + name);
    }

    /**
     * Safely executes a TTree::Scan on the given C++ expressions/leaves.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean scan(String expression) {
        String clingCmd = String.format(
            "{ TTree* t = (TTree*)gROOT->FindObject(\"%s\"); " +
            "  if (t) { " +
            "    t->Scan(\"%s\"); " +
            "  } else { " +
            "    std::cerr << \"ERROR TreeNotFound: '%s' is invalid.\\n\"; " +
            "  } }",
            handleId, expression, handleId
        );
        return pushClingCommand(clingCmd, "scan tree expression: " + expression);
    }

    /**
     * Inspects the TTree layout and queues a request to dump all available branch names.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean requestBranchNames() {
        String clingCmd = String.format(
            "{ TTree* t = (TTree*)gROOT->FindObject(\"%s\"); " +
            "  if (t) { " +
            "    TObjArray* branches = t->GetListOfBranches(); " +
            "    std::cout << \"OK\"; " +
            "    for (int i = 0; i < branches->GetEntries(); ++i) { " +
            "      std::cout << \" \" << branches->At(i)->GetName(); " +
            "    } " +
            "    std::cout << \"\\n\"; " +
            "  } else { " +
            "    std::cout << \"ERROR TreeNotFound\\n\"; " +
            "  } }",
            handleId
        );
        return pushClingCommand(clingCmd, "request branch names for tree: " + handleId);
    }

    private boolean pushClingCommand(String clingCmd, String actionDescription) {
        boolean queued = bridge.pushCommand("CLING_EXEC " + clingCmd);
        if (!queued) {
            AppLogger.error("Failed to queue SHM command to " + actionDescription);
        }
        return queued;
    }
}