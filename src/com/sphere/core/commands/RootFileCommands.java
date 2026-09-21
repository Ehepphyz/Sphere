package com.sphere.core.commands;

import com.sphere.utils.AppLogger;

/**
 * Files, caches, JSON, SQL and sockets.
 *
 * Split out of Handlers, which had grown to hold every backend at once. The
 * helpers these handlers share -- the argument readers, the interpreter call,
 * the named-handle builders -- stay in Handlers and are called through it.
 */
public final class RootFileCommands {

    private RootFileCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static void rootCacheClear(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gROOT->GetListOfFiles()->Print()");
    }

    public static void rootCacheStats(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gEnv->Print()");
    }

    public static void rootClose(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root file close");
        if (a.isEmpty()) {
            Handlers.usage(":root file close <id|name>");
            return;
        }
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_CLOSE_FILE, 0, Handlers.head(a));
    }

    public static void rootCloseAll(String i, CommandExecutionContext c) {
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_CLOSE_ALL_FILES, 0, null);
    }

    public static void rootFileCd(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root file cd");
        if (a.isEmpty()) {
            Handlers.usage(":root file cd <path>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gDirectory->cd(\"" + a0 + "\")");
    }

    public static void rootFileCopy(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root file copy");
        if (a.isEmpty()) {
            Handlers.usage(":root file copy <src> <dst>");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "gDirectory->Get(\"" + a0 + "\")->Clone(\"" + a1 + "\")");
    }

    public static void rootFileDelete(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root file delete");
        if (a.isEmpty()) {
            Handlers.usage(":root file delete <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gDirectory->Delete(\"" + a0 + "\")");
    }

    public static void rootFileDir(String i, CommandExecutionContext c) {
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_FILE_KEYS, 0,
             Handlers.head(Handlers.args(i, ":root file dir")));
    }

    public static void rootFileGet(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root file get");
        if (a.isEmpty()) {
            Handlers.usage(":root file get <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gDirectory->Get(\"" + a0 + "\")->ClassName()");
    }

    public static void rootFileInfo(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gFile->Print()");
    }

    public static void rootFileKeys(String i, CommandExecutionContext c) {
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_FILE_KEYS, 0,
             Handlers.head(Handlers.args(i, ":root file keys")));
    }

    public static void rootFileList(String i, CommandExecutionContext c) {
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_FILE_LIST, 0, null);
    }

    public static void rootFileMerge(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root file merge");
        final String[] w = Handlers.words(a);
        if (w.length < 3) {
            Handlers.usage(":root file merge <output> <input> <input> ...".trim());
            return;
        }
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_FILE_MERGE,
             0, String.join("\n", w));
    }

    public static void rootFileMkdir(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root file mkdir");
        if (a.isEmpty()) {
            Handlers.usage(":root file mkdir <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gDirectory->mkdir(\"" + a0 + "\")");
    }

    public static void rootFileMove(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root file move");
        if (a.isEmpty()) {
            Handlers.usage(":root file move <src> <dst>");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        // Two statements cannot be sent as one expression: the engine wraps
        // what it is given in a return, and a semicolon ends it there. The move
        // also has to refuse a missing object rather than clone a null pointer,
        // and it deletes only once the copy is really written.
        Handlers.cling(c, "[]{ TObject *o = gDirectory->Get(\"" + a0 + "\");"
            + " if (o == nullptr) { return std::string(\"no object named " + a0 + "\"); }"
            + " TObject *copy = o->Clone(\"" + a1 + "\");"
            + " if (copy == nullptr) { return std::string(\"could not copy " + a0 + "\"); }"
            + " copy->Write();"
            + " gDirectory->Delete(\"" + a0 + ";*\");"
            + " return std::string(\"" + a0 + " is now " + a1 + "\"); }()");
    }

    public static void rootFileOpenUpdate(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root file open-update");
        if (a.isEmpty()) {
            Handlers.usage(":root file open-update <path>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "TFile::Open(\"" + a0 + "\",\"UPDATE\")");
    }

    public static void rootFilePwd(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gDirectory->pwd()");
    }

    public static void rootFileRecreate(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root file recreate");
        if (a.isEmpty()) {
            Handlers.usage(":root file recreate <path>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "TFile::Open(\"" + a0 + "\",\"RECREATE\")");
    }

    public static void rootFileRmdir(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root file rmdir");
        if (a.isEmpty()) {
            Handlers.usage(":root file rmdir <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gDirectory->rmdir(\"" + a0 + "\")");
    }

    public static void rootFileScan(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root file scan");
        if (a.isEmpty()) {
            Handlers.usage(":root file scan <path> [--json]");
            return;
        }
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_FILE_SCAN, 0, a);
    }

    public static void rootFileWrite(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root file write");
        if (a.isEmpty()) {
            Handlers.usage(":root file write <id|name>");
            return;
        }
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_SAVE_FILE, 0, Handlers.head(a));
    }

    public static void rootJsonHist(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root json hist"));
        if (w.length < 1) {
            Handlers.usage(":root json hist <name>".trim());
            return;
        }
        Handlers.cling(c, "TBufferJSON::ToJSON(" + Handlers.obj("TH1", w[0]) + ").Data()");
    }

    public static void rootJsonObject(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root json object"));
        if (w.length < 1) {
            Handlers.usage(":root json object <name> [compact]".trim());
            return;
        }
        Handlers.cling(c, "TBufferJSON::ToJSON(" + Handlers.obj("TObject", w[0]) + ", " + Handlers.asInt((w.length > 1 ? w[1] : "0"), 0) + ").Data()");
    }

    public static void rootJsonSave(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root json save"));
        if (w.length < 2) {
            Handlers.usage(":root json save <name> <file>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TString text = TBufferJSON::ToJSON(" + Handlers.obj("TObject", w[0]) + "); std::ofstream out(\"" + w[1] + "\"); out << text.Data(); return std::string(\"written to " + w[1] + "\"); }()");
    }

    public static void rootLs(String i, CommandExecutionContext c) {
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_FILE_KEYS, 0,
             Handlers.head(Handlers.args(i, ":root file ls")));
    }

    public static void rootNetConnect(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root net connect"));
        if (w.length < 3) {
            Handlers.usage(":root net connect <name> <host> <port>");
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "TSocket",
            "new TSocket(\"" + w[1] + "\", " + Handlers.asInt(w[2], 0) + ")"));
    }

    public static void rootNetSend(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root net send");
        final String name = Handlers.head(a);
        final String text = Handlers.tail(a);
        if (name.isEmpty() || text.isEmpty()) {
            Handlers.usage(":root net send <name> <text>");
            return;
        }
        Handlers.cling(c, Handlers.held(name, "TSocket") + "->Send(\"" + text + "\")");
    }

    public static void rootNetServer(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root net server");
        final String name = Handlers.head(a);
        final String port = Handlers.head(Handlers.tail(a));
        if (name.isEmpty() || port.isEmpty()) {
            Handlers.usage(":root net server <name> <port>");
            return;
        }
        Handlers.cling(c, Handlers.keep(name, "TServerSocket",
            "new TServerSocket(" + Handlers.asInt(port, 0) + ", kTRUE)"));
    }

    public static void rootOpenFile(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root file open");
        if (a.isEmpty()) {
            Handlers.usage(":root file open <path>");
            return;
        }
        Handlers.send(c, com.sphere.core.rootbackend.RootBackend.CMD_OPEN_FILE, 0, Handlers.head(a));
    }

    public static void rootOpenRemoteFile(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root file open-remote");
        if (a.isEmpty()) {
            Handlers.usage(":root file open-remote <url>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "TFile::Open(\"" + a0 + "\")");
    }

    public static void rootSetCachePolicy(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root cache policy");
        if (a.isEmpty()) {
            Handlers.usage(":root cache policy <n>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gEnv->SetValue(\"TFile.CachePolicy\"," + a0 + ")");
    }

    public static void rootSetCacheSize(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root cache size");
        if (a.isEmpty()) {
            Handlers.usage(":root cache size <n>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gEnv->SetValue(\"TFile.CacheSize\"," + a0 + ")");
    }

    public static void rootSetMaxAge(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root limits age");
        if (a.isEmpty()) {
            Handlers.usage(":root limits age <n>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gEnv->SetValue(\"TFile.MaxAge\"," + a0 + ")");
    }

    public static void rootSetMaxHandles(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root limits handles");
        if (a.isEmpty()) {
            Handlers.usage(":root limits handles <n>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gEnv->SetValue(\"TFile.MaxHandles\"," + a0 + ")");
    }

    public static void rootSetMaxObjSize(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root limits obj-size");
        if (a.isEmpty()) {
            Handlers.usage(":root limits obj-size <n>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gEnv->SetValue(\"TFile.MaxSize\"," + a0 + ")");
    }

    public static void rootSqlConnect(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sql connect"));
        if (w.length < 2) {
            Handlers.usage(":root sql connect <name> <url> [user] [password]");
            return;
        }
        final String user = (w.length > 2) ? w[2] : "";
        final String password = (w.length > 3) ? w[3] : "";
        Handlers.cling(c, Handlers.keep(w[0], "TSQLServer", "TSQLServer::Connect(\"" + w[1]
               + "\",\"" + user + "\",\"" + password + "\")"));
    }

    public static void rootSqlDisconnect(String i, CommandExecutionContext c) {
        final String name = Handlers.head(Handlers.args(i, ":root sql disconnect"));
        if (name.isEmpty()) {
            Handlers.usage(":root sql disconnect <name>");
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(name, "TSQLServer")
               + "->Close(), SphereBridge::HandleDrop(\"" + name + "\"))");
    }

    public static void rootSqlQuery(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root sql query");
        final String name = Handlers.head(a);
        final String sql = Handlers.tail(a);
        if (name.isEmpty() || sql.isEmpty()) {
            Handlers.usage(":root sql query <name> <sql>");
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(name, "TSQLServer") + "->Query(\"" + sql
               + "\") != nullptr) ? \"query accepted\" : \"ERROR: the server refused it\"");
    }

    // --- RooStats, sparse histograms, splines, density estimation, principal components, unfolding, decompositions, integrators, interpolators, FFT, XML and compression ---

    public static void rootXmlWrite(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root xml write"));
        if (w.length < 2) {
            Handlers.usage(":root xml write <file> <object>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TXMLFile f(\"" + w[0] + "\", \"RECREATE\"); if (f.IsZombie()) { return std::string(\"ERROR: cannot write " + w[0] + "\"); } " + Handlers.obj("TObject", w[1]) + "->Write(); f.Close(); return std::string(\"written to " + w[0] + "\"); }()");
    }

    public static void rootXmlKeys(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root xml keys"));
        if (w.length < 1) {
            Handlers.usage(":root xml keys <file>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TXMLFile f(\"" + w[0] + "\", \"READ\"); if (f.IsZombie()) { return std::string(\"ERROR: cannot read " + w[0] + "\"); } f.ls(); f.Close(); return std::string(\"\"); }()");
    }

    public static void rootXmlGet(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root xml get"));
        if (w.length < 2) {
            Handlers.usage(":root xml get <file> <object>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TXMLFile f(\"" + w[0] + "\", \"READ\"); if (f.IsZombie()) { return std::string(\"ERROR: cannot read " + w[0] + "\"); } TObject *o = f.Get(\"" + w[1] + "\"); if (o == nullptr) { return std::string(\"ERROR: no object called " + w[1] + "\"); } TObject *copy = o->Clone(); gDirectory->Append(copy); f.Close(); return std::string(\"" + w[1] + " read back\"); }()");
    }

    public static void rootCompressLevel(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root compress level"));
        if (w.length < 1) {
            Handlers.usage(":root compress level <n>".trim());
            return;
        }
        Handlers.cling(c, "[]{ if (gFile == nullptr) { return std::string(\"ERROR: no file is current\"); } gFile->SetCompressionLevel(" + Handlers.asInt(w[0], 1) + "); return std::string(\"set\"); }()");
    }

    public static void rootCompressAlgorithm(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root compress algorithm"));
        if (w.length < 1) {
            Handlers.usage(":root compress algorithm <n>".trim());
            return;
        }
        Handlers.cling(c, "[]{ if (gFile == nullptr) { return std::string(\"ERROR: no file is current\"); } gFile->SetCompressionAlgorithm((ROOT::RCompressionSetting::EAlgorithm::EValues)" + Handlers.asInt(w[0], 1) + "); return std::string(\"set\"); }()");
    }

    public static void rootCompressShow(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root compress show"));
        Handlers.cling(c, "[]{ if (gFile == nullptr) { return std::string(\"ERROR: no file is current\"); } return std::string(\"level \") + std::to_string(gFile->GetCompressionLevel()) + \"  algorithm \" + std::to_string(gFile->GetCompressionAlgorithm()); }()");
    }

    public static void rootCompressFactor(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root compress factor"));
        Handlers.cling(c, "[]{ if (gFile == nullptr) { return std::string(\"ERROR: no file is current\"); } return std::to_string(gFile->GetCompressionFactor()); }()");
    }

    // --- reading fits back, TF1 parameters, applying a TMVA model, canvas layout and output, geometry, RooFit datasets and results, tree caches and indices, the rest of RDataFrame, the host system, regular expressions, file internals and more of TMath ---

    public static void rootFileMakeProject(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root file makeproject"));
        if (w.length < 2) {
            Handlers.usage(":root file makeproject <file> <directory>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TFile *f = TFile::Open(\"" + w[0] + "\"); if (f == nullptr || f->IsZombie()) { return std::string(\"ERROR: cannot read " + w[0] + "\"); } f->MakeProject(\"" + w[1] + "\", \"*\", \"recreate++\"); f->Close(); return std::string(\"written to " + w[1] + "\"); }()");
    }

    public static void rootFileStreamers(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root file streamers"));
        if (w.length < 1) {
            Handlers.usage(":root file streamers <file>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TFile *f = TFile::Open(\"" + w[0] + "\"); if (f == nullptr || f->IsZombie()) { return std::string(\"ERROR: cannot read " + w[0] + "\"); } f->ShowStreamerInfo(); f->Close(); return std::string(\"\"); }()");
    }

    public static void rootFileVersion(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root file version"));
        if (w.length < 1) {
            Handlers.usage(":root file version <file>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TFile *f = TFile::Open(\"" + w[0] + "\"); if (f == nullptr || f->IsZombie()) { return std::string(\"ERROR: cannot read " + w[0] + "\"); } std::string out = std::to_string(f->GetVersion()) + \"  \" + std::to_string(f->GetSize()) + \" bytes  compression \" + std::to_string(f->GetCompressionLevel()); f->Close(); return out; }()");
    }

    /** <name> <url> */
    public static void rootXrootdOpen(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root xrootd open"));
        if (w.length < 2) {
            Handlers.usage(":root xrootd open <name> <url>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<TFile>(\"" + w[0] + "\", TFile::Open(\"" + w[1] + "\"), \"TFile\")");
    }

    /** <url> */
    public static void rootXrootdLs(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root xrootd ls"));
        if (w.length < 1) {
            Handlers.usage(":root xrootd ls <url>");
            return;
        }
        Handlers.cling(c, "[]{ void *d = gSystem->OpenDirectory(\"" + w[0] + "\"); if (d == nullptr) { return std::string(\"cannot open " + w[0] + "\"); } std::string out; const char *e = nullptr; while ((e = gSystem->GetDirEntry(d)) != nullptr) { if (e[0] != 0 && strcmp(e, \".\") != 0 && strcmp(e, \"..\") != 0) { out += e; out += '\\n'; } } gSystem->FreeDirectory(d); return out; }()");
    }

    /** <from> <to> */
    public static void rootXrootdCopy(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root xrootd copy"));
        if (w.length < 2) {
            Handlers.usage(":root xrootd copy <from> <to>");
            return;
        }
        Handlers.cling(c, "TFile::Cp(\"" + w[0] + "\", \"" + w[1] + "\")");
    }

    /** <url> */
    public static void rootXrootdExists(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root xrootd exists"));
        if (w.length < 1) {
            Handlers.usage(":root xrootd exists <url>");
            return;
        }
        Handlers.cling(c, "(bool) (!gSystem->AccessPathName(\"" + w[0] + "\"))");
    }

    /** <url> */
    public static void rootXrootdStat(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root xrootd stat"));
        if (w.length < 1) {
            Handlers.usage(":root xrootd stat <url>");
            return;
        }
        Handlers.cling(c, "[]{ FileStat_t s; if (gSystem->GetPathInfo(\"" + w[0] + "\", s) != 0) { return std::string(\"cannot stat " + w[0] + "\"); } return std::string(\"bytes \") + std::to_string((long long) s.fSize) + \"  modified \" + std::to_string((long long) s.fMtime); }()");
    }

    /** <host> */
    public static void rootXrootdRedirector(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root xrootd redirector"));
        if (w.length < 1) {
            Handlers.usage(":root xrootd redirector <host>");
            return;
        }
        Handlers.cling(c, "[]{ gEnv->SetValue(\"XNet.Redirector\", \"" + w[0] + "\"); return std::string(gEnv->GetValue(\"XNet.Redirector\", \"\")); }()");
    }

    /** <name> */
    public static void rootFileRecover(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root file recover"));
        if (w.length < 1) {
            Handlers.usage(":root file recover <name>");
            return;
        }
        Handlers.cling(c, "(long) SphereBridge::Held<TFile>(\"" + w[0] + "\")->Recover()");
    }

    /** <name> */
    public static void rootFileCompressionInfo(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root file compression"));
        if (w.length < 1) {
            Handlers.usage(":root file compression <name>");
            return;
        }
        Handlers.cling(c, "[]{ TFile *f = SphereBridge::Held<TFile>(\"" + w[0] + "\"); return std::string(\"factor \") + std::to_string(f->GetCompressionFactor()) + \"  setting \" + std::to_string(f->GetCompressionSettings()) + \"  bytes \" + std::to_string((long long) f->GetSize()); }()");
    }

    /** <name> */
    public static void rootFileTreeList(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root file trees"));
        if (w.length < 1) {
            Handlers.usage(":root file trees <name>");
            return;
        }
        Handlers.cling(c, "[]{ TFile *f = SphereBridge::Held<TFile>(\"" + w[0] + "\"); std::string out; TIter next(f->GetListOfKeys()); TKey *k = nullptr; while ((k = (TKey *) next()) != nullptr) { if (strcmp(k->GetClassName(), \"TTree\") != 0) { continue; } TTree *t = (TTree *) f->Get(k->GetName()); if (t == nullptr) { continue; } out += k->GetName(); out += \"  \"; out += std::to_string((long long) t->GetEntries()); out += \" entries\\n\"; } return out.empty() ? std::string(\"no tree in this file\") : out; }()");
    }

    /** <name> */
    public static void rootFileFree(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root file free"));
        if (w.length < 1) {
            Handlers.usage(":root file free <name>");
            return;
        }
        Handlers.cling(c, "(long) SphereBridge::Held<TFile>(\"" + w[0] + "\")->GetNbytesFree()");
    }

}
