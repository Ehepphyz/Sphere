package com.sphere.core.commands;

import com.sphere.utils.AppLogger;

/**
 * TTree, TChain, entry lists, RNTuple and RDataFrame.
 *
 * Split out of Handlers, which had grown to hold every backend at once. The
 * helpers these handlers share -- the argument readers, the interpreter call,
 * the named-handle builders -- stay in Handlers and are called through it.
 */
public final class RootTreeCommands {

    private RootTreeCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static void rootChainAdd(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root chain add");
        final String name = Handlers.head(a);
        final String file = Handlers.head(Handlers.tail(a));
        if (name.isEmpty() || file.isEmpty()) {
            Handlers.usage(":root chain add <chain> <file>");
            return;
        }
        Handlers.cling(c, Handlers.held(name, "TChain") + "->Add(\"" + file + "\")");
    }

    public static void rootChainEntries(String i, CommandExecutionContext c) {
        final String name = Handlers.head(Handlers.args(i, ":root chain entries"));
        if (name.isEmpty()) {
            Handlers.usage(":root chain entries <chain>");
            return;
        }
        Handlers.cling(c, Handlers.held(name, "TChain") + "->GetEntries()");
    }

    public static void rootChainNew(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root chain new");
        final String name = Handlers.head(a);
        final String tree = Handlers.head(Handlers.tail(a));
        if (name.isEmpty() || tree.isEmpty()) {
            Handlers.usage(":root chain new <name> <tree>");
            return;
        }
        Handlers.cling(c, Handlers.keep(name, "TChain", "new TChain(\"" + tree + "\")"));
    }

    public static void rootEntrylistApply(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root entrylist apply"));
        if (w.length < 2) {
            Handlers.usage(":root entrylist apply <tree> <name>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TTree", w[0]) + "->SetEntryList(" + Handlers.obj("TEntryList", w[1]) + "), std::string(\"applied\"))");
    }

    public static void rootEntrylistClear(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root entrylist clear"));
        if (w.length < 1) {
            Handlers.usage(":root entrylist clear <tree>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TTree", w[0]) + "->SetEntryList(nullptr), std::string(\"cleared\"))");
    }

    public static void rootEntrylistCount(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root entrylist count"));
        if (w.length < 1) {
            Handlers.usage(":root entrylist count <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TEntryList", w[0]) + "->GetN()");
    }

    public static void rootEntrylistMake(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root entrylist make"));
        if (w.length < 3) {
            Handlers.usage(":root entrylist make <tree> <name> <selection>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TTree", w[0]) + "->Draw(\">>" + w[1] + "\", \"" + Handlers.join(w, 2) + "\", \"entrylist\")");
    }

    public static void rootFriendAdd(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root friend add"));
        if (w.length < 3) {
            Handlers.usage(":root friend add <tree> <friend> <file>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TTree", w[0]) + "->AddFriend(\"" + w[1] + "\", \"" + w[2] + "\"), std::string(\"attached\"))");
    }

    public static void rootFriendList(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root friend list"));
        if (w.length < 1) {
            Handlers.usage(":root friend list <tree>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TTree", w[0]) + "->GetListOfFriends()->Print()");
    }

    public static void rootFriendRemove(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root friend remove"));
        if (w.length < 2) {
            Handlers.usage(":root friend remove <tree> <friend>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TTree", w[0]) + "->RemoveFriend(" + Handlers.obj("TTree", w[1]) + "), std::string(\"detached\"))");
    }

    public static void rootGetBranch(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root tree branch");
        if (a.isEmpty()) {
            Handlers.usage(":root tree branch <name> <b>");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "" + Handlers.obj("TTree", a0) + "->GetBranch(\"" + a1 + "\")->Print()");
    }

    public static void rootGetTree(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root tree open");
        if (a.isEmpty()) {
            Handlers.usage(":root tree open <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "" + Handlers.obj("TTree", a0) + "->GetEntries()");
    }

    public static void rootNtupleAttach(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root ntuple attach");
        final String[] w = Handlers.words(a);
        if (w.length < 3) {
            Handlers.usage(":root ntuple attach <id> <file> <ntuple>".trim());
            return;
        }
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_NTUPLE_ATTACH,
             Handlers.asInt(w[0], 0), w[1] + "\t" + w[2]);
    }

    public static void rootNtupleColumn(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root ntuple column");
        final String[] w = Handlers.words(a);
        if (w.length < 2) {
            Handlers.usage(":root ntuple column <id> <field>".trim());
            return;
        }
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_NTUPLE_COLUMN,
             Handlers.asInt(w[0], 0), w[1]);
    }

    public static void rootNtupleFields(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root ntuple fields");
        final String[] w = Handlers.words(a);
        if (w.length < 1) {
            Handlers.usage(":root ntuple fields <id>".trim());
            return;
        }
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_NTUPLE_FIELDS,
             Handlers.asInt(w[0], 0), null);
    }

    public static void rootNtupleInfo(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root ntuple info");
        final String[] w = Handlers.words(a);
        if (w.length < 1) {
            Handlers.usage(":root ntuple info <id>".trim());
            return;
        }
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_NTUPLE_INFO,
             Handlers.asInt(w[0], 0), null);
    }

    public static void rootNtupleList(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root ntuple list");
        final String[] w = Handlers.words(a);
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_NTUPLE_LIST,
             0, Handlers.head(a));
    }

    public static void rootRdfAlias(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf alias"));
        if (w.length < 3) {
            Handlers.usage(":root rdf alias <name> <alias> <column>".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "ROOT::RDF::RNode", "new ROOT::RDF::RNode(" + Handlers.held(w[0], "ROOT::RDF::RNode") + "->Alias(\"" + w[1] + "\", \"" + w[2] + "\")" + ")"));
    }

    public static void rootRdfCache(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf cache"));
        if (w.length < 1) {
            Handlers.usage(":root rdf cache <name> [column ...]".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "ROOT::RDF::RNode", "new ROOT::RDF::RNode(" + Handlers.held(w[0], "ROOT::RDF::RNode") + "->Cache(" + (w.length > 1 ? "{" + Handlers.quotedCsv(Handlers.join(w, 1)) + "}" : "\"\"") + "))"));
    }

    public static void rootRdfColumns(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf columns"));
        if (w.length < 1) {
            Handlers.usage(":root rdf columns <name>".trim());
            return;
        }
        Handlers.cling(c, "[]{ std::string out; for (const auto &c : " + Handlers.held(w[0], "ROOT::RDF::RNode") + "->GetColumnNames()) { if (!out.empty()) { out += \"\\n\"; } out += c; } return out; }()");
    }

    public static void rootRdfCount(String i, CommandExecutionContext c) {
        final String name = Handlers.head(Handlers.args(i, ":root rdf count"));
        Handlers.cling(c, Handlers.held(name.isEmpty() ? "df" : name, "ROOT::RDF::RNode") + "->Count().GetValue()");
    }

    public static void rootRdfDefine(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf define"));
        if (w.length < 3) {
            Handlers.usage(":root rdf define <name> <column> <expression>".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "ROOT::RDF::RNode", "new ROOT::RDF::RNode(" + Handlers.held(w[0], "ROOT::RDF::RNode") + "->Define(\"" + w[1] + "\", \"" + Handlers.join(w, 2) + "\")" + ")"));
    }

    public static void rootRdfDescribe(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf describe"));
        if (w.length < 1) {
            Handlers.usage(":root rdf describe <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "ROOT::RDF::RNode") + "->Describe().AsString()");
    }

    public static void rootRdfDisplay(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf display"));
        if (w.length < 1) {
            Handlers.usage(":root rdf display <name> [rows] [column ...]".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "ROOT::RDF::RNode") + "->Display(" + (w.length > 2 ? "{" + Handlers.quotedCsv(Handlers.join(w, 2)) + "}" : "\"\"") + ", " + Handlers.asInt((w.length > 1 ? w[1] : "5"), 5) + ")->AsString()");
    }

    public static void rootRdfFilter(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root rdf filter");
        final String name = Handlers.head(a);
        final String expr = Handlers.tail(a);
        if (name.isEmpty() || expr.isEmpty()) {
            Handlers.usage(":root rdf filter <name> <expr>");
            return;
        }
        // The filtered frame replaces what the name held, so a chain of filters
        // reads as a chain of commands.
        Handlers.cling(c, Handlers.keep(name, "ROOT::RDF::RNode",
            "new ROOT::RDF::RNode(" + Handlers.held(name, "ROOT::RDF::RNode") + "->Filter(\"" + expr + "\"))"));
    }

    public static void rootRdfHisto(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf histo"));
        if (w.length < 6) {
            Handlers.usage(":root rdf histo <name> <hist> <bins> <low> <high> <column>".trim());
            return;
        }
        Handlers.cling(c, "[]{ auto *h = new TH1D(" + Handlers.held(w[0], "ROOT::RDF::RNode") + "->Histo1D({\"" + w[1] + "\", \"" + w[1] + "\", " + Handlers.csv(w[2] + " " + w[3] + " " + w[4]) + "}, \"" + w[5] + "\").GetValue()); h->SetName(\"" + w[1] + "\"); h->SetDirectory(gDirectory); return std::string(\"" + w[1] + " filled\"); }()");
    }

    public static void rootRdfHisto2(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf histo2"));
        if (w.length < 10) {
            Handlers.usage(":root rdf histo2 <name> <hist> <xbins> <xlow> <xhigh> <ybins> <ylow> <yhigh> <xcolumn> <ycolumn>".trim());
            return;
        }
        Handlers.cling(c, "[]{ auto *h = new TH2D(" + Handlers.held(w[0], "ROOT::RDF::RNode") + "->Histo2D({\"" + w[1] + "\", \"" + w[1] + "\", " + Handlers.csv(w[2] + " " + w[3] + " " + w[4] + " " + w[5] + " " + w[6] + " " + w[7]) + "}, \"" + w[8] + "\", \"" + w[9] + "\").GetValue()); h->SetName(\"" + w[1] + "\"); h->SetDirectory(gDirectory); return std::string(\"" + w[1] + " filled\"); }()");
    }

    public static void rootRdfMax(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf max"));
        if (w.length < 2) {
            Handlers.usage(":root rdf max <name> <column>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "ROOT::RDF::RNode") + "->Max(\"" + w[1] + "\").GetValue()");
    }

    public static void rootRdfMean(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf mean"));
        if (w.length < 2) {
            Handlers.usage(":root rdf mean <name> <column>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "ROOT::RDF::RNode") + "->Mean(\"" + w[1] + "\").GetValue()");
    }

    public static void rootRdfMin(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf min"));
        if (w.length < 2) {
            Handlers.usage(":root rdf min <name> <column>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "ROOT::RDF::RNode") + "->Min(\"" + w[1] + "\").GetValue()");
    }

    public static void rootRdfOpen(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf open"));
        if (w.length < 2) {
            Handlers.usage(":root rdf open [<name>] <tree> <file>");
            return;
        }
        final String name = (w.length >= 3) ? w[0] : "df";
        final String tree = w[w.length - 2];
        final String file = w[w.length - 1];
        Handlers.cling(c, Handlers.keep(name, "ROOT::RDF::RNode",
            "new ROOT::RDF::RNode(ROOT::RDataFrame(\"" + tree + "\",\"" + file + "\"))"));
    }

    public static void rootRdfProfile(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf profile"));
        if (w.length < 7) {
            Handlers.usage(":root rdf profile <name> <profile> <bins> <low> <high> <xcolumn> <ycolumn>".trim());
            return;
        }
        Handlers.cling(c, "[]{ auto *p = new TProfile(" + Handlers.held(w[0], "ROOT::RDF::RNode") + "->Profile1D({\"" + w[1] + "\", \"" + w[1] + "\", " + Handlers.csv(w[2] + " " + w[3] + " " + w[4]) + "}, \"" + w[5] + "\", \"" + w[6] + "\").GetValue()); p->SetName(\"" + w[1] + "\"); p->SetDirectory(gDirectory); return std::string(\"" + w[1] + " filled\"); }()");
    }

    public static void rootRdfRange(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf range"));
        if (w.length < 2) {
            Handlers.usage(":root rdf range <name> <begin> [end]".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "ROOT::RDF::RNode", "new ROOT::RDF::RNode(" + Handlers.held(w[0], "ROOT::RDF::RNode") + "->Range(" + Handlers.asInt(w[1], 0) + ", " + Handlers.asInt((w.length > 2 ? w[2] : "0"), 0) + ")" + ")"));
    }

    public static void rootRdfRedefine(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf redefine"));
        if (w.length < 3) {
            Handlers.usage(":root rdf redefine <name> <column> <expression>".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "ROOT::RDF::RNode", "new ROOT::RDF::RNode(" + Handlers.held(w[0], "ROOT::RDF::RNode") + "->Redefine(\"" + w[1] + "\", \"" + Handlers.join(w, 2) + "\")" + ")"));
    }

    public static void rootRdfReport(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf report"));
        if (w.length < 1) {
            Handlers.usage(":root rdf report <name>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "ROOT::RDF::RNode") + "->Report()->Print(), std::string(\"\"))");
    }

    public static void rootRdfSnapshot(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf snapshot"));
        if (w.length < 3) {
            Handlers.usage(":root rdf snapshot <name> <tree> <file> [column ...]".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "ROOT::RDF::RNode") + "->Snapshot(\"" + w[1] + "\", \"" + w[2] + "\", " + (w.length > 3 ? "{" + Handlers.quotedCsv(Handlers.join(w, 3)) + "}" : "\"\"") + "), std::string(\"written to " + w[2] + "\"))");
    }

    public static void rootRdfStdDev(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf stddev"));
        if (w.length < 2) {
            Handlers.usage(":root rdf stddev <name> <column>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "ROOT::RDF::RNode") + "->StdDev(\"" + w[1] + "\").GetValue()");
    }

    public static void rootRdfSum(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf sum"));
        if (w.length < 2) {
            Handlers.usage(":root rdf sum <name> <column>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "ROOT::RDF::RNode") + "->Sum(\"" + w[1] + "\").GetValue()");
    }

    public static void rootRdfType(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf type"));
        if (w.length < 2) {
            Handlers.usage(":root rdf type <name> <column>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "ROOT::RDF::RNode") + "->GetColumnType(\"" + w[1] + "\")");
    }

    public static void rootSchemaDiscover(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root schema discover");
        if (a.isEmpty()) {
            Handlers.usage(":root schema discover <tree_id>");
            return;
        }
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_SCHEMA_DISCOVER, Handlers.asInt(a, 0), null);
    }

    /**
     * Binds a tree to an id, which every other tree command then works on.
     *
     * Nothing else creates that binding: without it the engine answers every
     * tree command by saying it has no tree.
     */
    public static void rootTreeAttach(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root tree attach");
        String id = Handlers.head(a);
        String rest = Handlers.tail(a);
        if (id.isEmpty() || rest.isEmpty()) {
            Handlers.usage(":root tree attach <tree_id> <file_id|name> <tree_path>");
            return;
        }
        String fileToken = Handlers.head(rest);
        String treePath = Handlers.tail(rest);
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_TTREE_INSPECT,
             Handlers.asInt(id, 0), fileToken + "\t" + treePath);
    }

    public static void rootTreeBranches(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root tree branches");
        if (a.isEmpty()) {
            Handlers.usage(":root tree branches <tree_id>");
            return;
        }
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_TTREE_SCAN_BRANCHES, Handlers.asInt(a, 0), null);
    }

    /**
     * A column comes back as typed numbers, so it is read as numbers.
     * Printing the block as text would show the raw bytes.
     */
    public static void rootTreeColumn(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root tree column");
        if (a.isEmpty() || Handlers.tail(a).isEmpty()) {
            Handlers.usage(":root tree column <tree_id> <branch>");
            return;
        }
        com.sphere.core.rootbackend.RootBackend b = Handlers.backend(c);
        if (b == null) {
            return;
        }
        String branch = Handlers.tail(a);
        double[] values = b.treeColumnAwait(Handlers.asInt(Handlers.head(a), 0), branch, Handlers.TIMEOUT_MS);
        if (values.length == 0) {
            AppLogger.error(Handlers.whyNoColumn(b, Handlers.asInt(Handlers.head(a), 0), branch));
            return;
        }
        com.sphere.components.rootview.RootPlotsPanel.instance()
            .showColumn(branch, values);
        AppLogger.info(Handlers.summarize(branch, values));
    }

    public static void rootTreeCopytree(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root tree copytree");
        if (a.isEmpty()) {
            Handlers.usage(":root tree copytree <name> <cut>");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "" + Handlers.obj("TTree", a0) + "->CopyTree(\"" + a1 + "\")");
    }

    public static void rootTreeDraw(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root tree draw");
        if (a.isEmpty()) {
            Handlers.usage(":root tree draw <name> <expr>");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "" + Handlers.obj("TTree", a0) + "->Draw(\"" + a1 + "\")");
    }

    public static void rootTreeEntries(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root tree entries");
        if (a.isEmpty()) {
            Handlers.usage(":root tree entries <tree_id>");
            return;
        }
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_TTREE_QUERY_ENTRIES, Handlers.asInt(a, 0), null);
    }

    public static void rootTreeFilter(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root tree filter");
        if (a.isEmpty() || Handlers.tail(a).isEmpty()) {
            Handlers.usage(":root tree filter <tree_id> <expression>");
            return;
        }
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_TTREE_APPLY_FILTER,
             Handlers.asInt(Handlers.head(a), 0), Handlers.tail(a));
    }

    public static void rootTreeGetentry(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root tree getentry");
        if (a.isEmpty()) {
            Handlers.usage(":root tree getentry <tree_id> <entry>");
            return;
        }
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_TTREE_GET_ENTRY,
             Handlers.asInt(Handlers.head(a), 0), Handlers.tail(a));
    }

    public static void rootTreeLeaves(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root tree leaves");
        if (a.isEmpty()) {
            Handlers.usage(":root tree leaves <tree_id>");
            return;
        }
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_TTREE_SCAN_BRANCHES, Handlers.asInt(a, 0), null);
    }

    /** Draws one branch against another in the Plots tab. */
    public static void rootTreePlot(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root tree plot");
        String id = Handlers.head(a);
        String rest = Handlers.tail(a);
        if (id.isEmpty() || rest.isEmpty() || Handlers.tail(rest).isEmpty()) {
            Handlers.usage(":root tree plot <tree_id> <x_branch> <y_branch>");
            return;
        }
        com.sphere.core.rootbackend.RootBackend b = Handlers.backend(c);
        if (b == null) {
            return;
        }
        final int jobId = Handlers.asInt(id, 0);
        final String xName = Handlers.head(rest);
        final String yName = Handlers.head(Handlers.tail(rest));
        double[] xValues = b.treeColumnAwait(jobId, xName, Handlers.TIMEOUT_MS);
        double[] yValues = b.treeColumnAwait(jobId, yName, Handlers.TIMEOUT_MS);
        if (xValues.length == 0 || yValues.length == 0) {
            AppLogger.error(Handlers.whyNoColumn(b, jobId,
                xValues.length == 0 ? xName : yName));
            return;
        }
        com.sphere.components.rootview.RootPlotsPanel.instance()
            .showCurve(xName, yName, xValues, yValues);
        AppLogger.info(String.format(java.util.Locale.ROOT,
            "%s vs %s drawn in the Plots tab, %d points",
            yName, xName, Math.min(xValues.length, yValues.length)));
    }

    public static void rootTreePrint(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root tree print");
        if (a.isEmpty()) {
            Handlers.usage(":root tree print <tree_id>");
            return;
        }
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_TTREE_INSPECT, Handlers.asInt(a, 0), null);
    }

    public static void rootTreeProcess(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root tree process");
        if (a.isEmpty()) {
            Handlers.usage(":root tree process <name> <macro>");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "" + Handlers.obj("TTree", a0) + "->Process(\"" + a1 + "\")");
    }

    public static void rootTreeProject(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root tree project");
        final String name = Handlers.head(a);
        final String rest = Handlers.quotedCsv(Handlers.tail(a));
        if (name.isEmpty() || rest.isEmpty()) {
            Handlers.usage(":root tree project <tree> <hist> <expr> [selection]");
            return;
        }
        Handlers.cling(c, Handlers.obj("TTree", name) + "->Project(" + rest + ")");
    }

    public static void rootTreeScan(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root tree scan");
        if (a.isEmpty()) {
            Handlers.usage(":root tree scan <name> [expr]");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "" + Handlers.obj("TTree", a0) + "->Scan(\"" + a1 + "\")");
    }

    public static void rootTreeStats(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root tree stats");
        if (a.isEmpty() || Handlers.tail(a).isEmpty()) {
            Handlers.usage(":root tree stats <tree_id> <branch>");
            return;
        }
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_TTREE_COMPUTE_STATS,
             Handlers.asInt(Handlers.head(a), 0), Handlers.tail(a));
    }

    // --- TTreeReader, the selector generators, and writing an RNTuple ---

    public static void rootNtupleFromTree(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root ntuple from-tree");
        final String[] w = Handlers.words(a);
        if (w.length < 4) {
            Handlers.usage(":root ntuple from-tree <file> <tree> <output> <ntuple>".trim());
            return;
        }
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_NTUPLE_WRITE,
             0, String.join("\t", w));
    }

    public static void rootReaderNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root reader new"));
        if (w.length < 2) {
            Handlers.usage(":root reader new <name> <tree>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<TTreeReader>(\"" + w[0] + "\", new TTreeReader(" + Handlers.obj("TTree", w[1]) + "), \"TTreeReader\")");
    }

    public static void rootReaderNext(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root reader next"));
        if (w.length < 1) {
            Handlers.usage(":root reader next <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TTreeReader>(\"" + w[0] + "\")" + "->Next()");
    }

    public static void rootReaderRestart(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root reader restart"));
        if (w.length < 1) {
            Handlers.usage(":root reader restart <name>".trim());
            return;
        }
        Handlers.cling(c, "(" + "SphereBridge::Held<TTreeReader>(\"" + w[0] + "\")" + "->Restart(), std::string(\"restarted\"))");
    }

    public static void rootReaderEntry(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root reader entry"));
        if (w.length < 1) {
            Handlers.usage(":root reader entry <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TTreeReader>(\"" + w[0] + "\")" + "->GetCurrentEntry()");
    }

    public static void rootReaderEntries(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root reader entries"));
        if (w.length < 1) {
            Handlers.usage(":root reader entries <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TTreeReader>(\"" + w[0] + "\")" + "->GetEntries(false)");
    }

    public static void rootReaderValue(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root reader value"));
        if (w.length < 4) {
            Handlers.usage(":root reader value <reader> <name> <type> <branch>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<TTreeReaderValue<" + w[2] + ">>(\"" + w[1] + "\", new TTreeReaderValue<" + w[2] + ">(*" + "SphereBridge::Held<TTreeReader>(\"" + w[0] + "\")" + ", \"" + w[3] + "\"), \"TTreeReaderValue<" + w[2] + ">\")");
    }

    public static void rootReaderRead(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root reader read"));
        if (w.length < 2) {
            Handlers.usage(":root reader read <name> <type>".trim());
            return;
        }
        Handlers.cling(c, "*(*SphereBridge::Held<TTreeReaderValue<" + w[1] + ">>(\"" + w[0] + "\"))");
    }

    public static void rootReaderArray(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root reader array"));
        if (w.length < 4) {
            Handlers.usage(":root reader array <reader> <name> <type> <branch>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<TTreeReaderArray<" + w[2] + ">>(\"" + w[1] + "\", new TTreeReaderArray<" + w[2] + ">(*" + "SphereBridge::Held<TTreeReader>(\"" + w[0] + "\")" + ", \"" + w[3] + "\"), \"TTreeReaderArray<" + w[2] + ">\")");
    }

    public static void rootReaderSize(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root reader size"));
        if (w.length < 2) {
            Handlers.usage(":root reader size <name> <type>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TTreeReaderArray<" + w[1] + ">>(\"" + w[0] + "\")->GetSize()");
    }

    public static void rootReaderAt(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root reader at"));
        if (w.length < 3) {
            Handlers.usage(":root reader at <name> <type> <index>".trim());
            return;
        }
        Handlers.cling(c, "(*SphereBridge::Held<TTreeReaderArray<" + w[1] + ">>(\"" + w[0] + "\"))[" + Handlers.asInt(w[2], 0) + "]");
    }

    public static void rootSelectorMake(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root selector make"));
        if (w.length < 2) {
            Handlers.usage(":root selector make <tree> <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TTree", w[0]) + "->MakeSelector(\"" + w[1] + "\")");
    }

    public static void rootSelectorClass(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root selector class"));
        if (w.length < 2) {
            Handlers.usage(":root selector class <tree> <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TTree", w[0]) + "->MakeClass(\"" + w[1] + "\")");
    }

    public static void rootSelectorCode(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root selector code"));
        if (w.length < 2) {
            Handlers.usage(":root selector code <tree> <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TTree", w[0]) + "->MakeCode(\"" + w[1] + "\")");
    }

    public static void rootRdfTake(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf take"));
        if (w.length < 3) {
            Handlers.usage(":root rdf take <name> <column> <type>".trim());
            return;
        }
        Handlers.cling(c, "[]{ auto values = " + "SphereBridge::Held<ROOT::RDF::RNode>(\"" + w[0] + "\")" + "->Take<" + w[2] + ">(\"" + w[1] + "\").GetValue(); std::string out = std::to_string(values.size()) + \" values\"; for (std::size_t i = 0; i < values.size() && i < 10; ++i) { out += \" \" + std::to_string(values[i]); } if (values.size() > 10) { out += \" ...\"; } return out; }()");
    }

    public static void rootRdfVary(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf vary"));
        if (w.length < 4) {
            Handlers.usage(":root rdf vary <name> <column> <expression> <tag> [tag ...]".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "ROOT::RDF::RNode", "new ROOT::RDF::RNode(" + "SphereBridge::Held<ROOT::RDF::RNode>(\"" + w[0] + "\")" + "->Vary(\"" + w[1] + "\", \"" + w[2] + "\", {" + Handlers.quotedCsv(Handlers.join(w, 3)) + "}))"));
    }

    public static void rootRdfVaried(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf varied"));
        if (w.length < 6) {
            Handlers.usage(":root rdf varied <name> <hist> <bins> <low> <high> <column>".trim());
            return;
        }
        Handlers.cling(c, "[]{ auto nominal = " + "SphereBridge::Held<ROOT::RDF::RNode>(\"" + w[0] + "\")" + "->Histo1D({\"" + w[1] + "\", \"" + w[1] + "\", " + Handlers.csv(w[2] + ' ' + w[3] + ' ' + w[4]) + "}, \"" + w[5] + "\"); auto all = ROOT::RDF::Experimental::VariationsFor(nominal); std::string out; for (const auto &key : all.GetKeys()) { auto *copy = new TH1D(all[key]); copy->SetName((std::string(\"" + w[1] + "\") + \"_\" + key).c_str()); copy->SetDirectory(gDirectory); if (!out.empty()) { out += \" \"; } out += key; } return out; }()");
    }

    // --- reading fits back, TF1 parameters, applying a TMVA model, canvas layout and output, geometry, RooFit datasets and results, tree caches and indices, the rest of RDataFrame, the host system, regular expressions, file internals and more of TMath ---

    public static void rootTreeCache(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root tree cache"));
        if (w.length < 2) {
            Handlers.usage(":root tree cache <tree> <bytes>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TTree", w[0]) + "->SetCacheSize(" + w[1] + "), std::string(\"set\"))");
    }

    public static void rootTreeCacheBranch(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root tree cachebranch"));
        if (w.length < 2) {
            Handlers.usage(":root tree cachebranch <tree> <branch>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TTree", w[0]) + "->AddBranchToCache(\"" + w[1] + "\", true), std::string(\"cached\"))");
    }

    public static void rootTreeCacheStats(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root tree cachestats"));
        if (w.length < 1) {
            Handlers.usage(":root tree cachestats <tree>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TTree", w[0]) + "->PrintCacheStats(), std::string(\"\"))");
    }

    public static void rootTreeAlias(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root tree alias"));
        if (w.length < 3) {
            Handlers.usage(":root tree alias <tree> <alias> <expression>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TTree", w[0]) + "->SetAlias(\"" + w[1] + "\", \"" + Handlers.join(w, 2) + "\"), std::string(\"set\"))");
    }

    public static void rootTreeIndex(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root tree index"));
        if (w.length < 2) {
            Handlers.usage(":root tree index <tree> <major> [minor]".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TTree", w[0]) + "->BuildIndex(\"" + w[1] + "\", \"" + (w.length > 2 ? w[2] : "0") + "\")");
    }

    public static void rootTreeSeek(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root tree seek"));
        if (w.length < 2) {
            Handlers.usage(":root tree seek <tree> <major> [minor]".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TTree", w[0]) + "->GetEntryWithIndex(" + Handlers.asInt(w[1], 0) + ", " + Handlers.asInt((w.length > 2 ? w[2] : "0"), 0) + ")");
    }

    public static void rootTreeBranchStatus(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root tree branchstatus"));
        if (w.length < 3) {
            Handlers.usage(":root tree branchstatus <tree> <branch> <0|1>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TTree", w[0]) + "->SetBranchStatus(\"" + w[1] + "\", " + Handlers.asInt(w[2], 1) + "), std::string(\"set\"))");
    }

    public static void rootTreeClone(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root tree clone"));
        if (w.length < 2) {
            Handlers.usage(":root tree clone <tree> <name>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TTree *copy = " + Handlers.obj("TTree", w[0]) + "->CloneTree(0); if (copy == nullptr) { return std::string(\"ERROR: the tree refused to clone\"); } copy->SetName(\"" + w[1] + "\"); return std::string(\"" + w[1] + " created\"); }()");
    }

    public static void rootTreeCopyEntries(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root tree copyentries"));
        if (w.length < 2) {
            Handlers.usage(":root tree copyentries <into> <from>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TTree", w[0]) + "->CopyEntries(" + Handlers.obj("TTree", w[1]) + ")");
    }

    public static void rootTreeOptimize(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root tree optimize"));
        if (w.length < 1) {
            Handlers.usage(":root tree optimize <tree>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TTree", w[0]) + "->OptimizeBaskets(), std::string(\"done\"))");
    }

    public static void rootTreeAutoSave(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root tree autosave"));
        if (w.length < 2) {
            Handlers.usage(":root tree autosave <tree> <bytes>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TTree", w[0]) + "->SetAutoSave(" + w[1] + "), std::string(\"set\"))");
    }

    public static void rootTreeFile(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root tree file"));
        if (w.length < 1) {
            Handlers.usage(":root tree file <tree>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TFile *f = " + Handlers.obj("TTree", w[0]) + "->GetCurrentFile(); return (f == nullptr) ? std::string(\"not on a file\") : std::string(f->GetName()); }()");
    }

    public static void rootRdfHisto3(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf histo3"));
        if (w.length < 14) {
            Handlers.usage(":root rdf histo3 <name> <hist> <xb> <xlo> <xhi> <yb> <ylo> <yhi> <zb> <zlo> <zhi> <x> <y> <z>".trim());
            return;
        }
        Handlers.cling(c, "[]{ auto *h = new TH3D(" + "SphereBridge::Held<ROOT::RDF::RNode>(\"" + w[0] + "\")" + "->Histo3D({\"" + w[1] + "\", \"" + w[1] + "\", " + Handlers.csv(w[2] + " " + w[3] + " " + w[4] + " " + w[5] + " " + w[6] + " " + w[7] + " " + w[8] + " " + w[9] + " " + w[10]) + "}, \"" + w[11] + "\", \"" + w[12] + "\", \"" + w[13] + "\").GetValue()); h->SetName(\"" + w[1] + "\"); h->SetDirectory(gDirectory); return std::string(\"" + w[1] + " filled\"); }()");
    }

    public static void rootRdfGraph(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf graph"));
        if (w.length < 4) {
            Handlers.usage(":root rdf graph <name> <graph> <x> <y>".trim());
            return;
        }
        Handlers.cling(c, "[]{ auto *g = new TGraph(" + "SphereBridge::Held<ROOT::RDF::RNode>(\"" + w[0] + "\")" + "->Graph(\"" + w[2] + "\", \"" + w[3] + "\").GetValue()); g->SetName(\"" + w[1] + "\"); gDirectory->Append(g); return std::string(\"" + w[1] + " created\"); }()");
    }

    public static void rootRdfRuns(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf runs"));
        if (w.length < 1) {
            Handlers.usage(":root rdf runs <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<ROOT::RDF::RNode>(\"" + w[0] + "\")->GetNRuns()");
    }

    public static void rootRdfSlots(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf slots"));
        if (w.length < 1) {
            Handlers.usage(":root rdf slots <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<ROOT::RDF::RNode>(\"" + w[0] + "\")->GetNSlots()");
    }

    public static void rootRdfStats(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rdf stats"));
        if (w.length < 2) {
            Handlers.usage(":root rdf stats <name> <column>".trim());
            return;
        }
        Handlers.cling(c, "[]{ auto s = " + "SphereBridge::Held<ROOT::RDF::RNode>(\"" + w[0] + "\")" + "->Stats(\"" + w[1] + "\"); return std::string(\"mean \") + std::to_string(s->GetMean()) + \"  rms \" + std::to_string(s->GetRMS()) + \"  entries \" + std::to_string(s->GetN()); }()");
    }

}
