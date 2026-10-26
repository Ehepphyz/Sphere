"""Completes the static extraction with the commands Geant4 builds from templates.

Usage: python -I g4post.py <geant4-source-root> <raw.json> <g4commands.json> [version label]

The two steps that make Sphere's catalog, from a Geant4 source tree:
  python -I g4cmds.py <geant4> raw.json
  python -I g4post.py <geant4> raw.json g4commands.json "geant4-11-05 ..."

- /analysis/: G4AnalysisMessenger, G4NtupleMessenger, G4PlotMessenger and, for
  each of h1 h2 h3 p1 p2, G4HnMessenger and G4THnMessenger<DIM, HT>;
- /vis/modeling/trajectories/ and /vis/filtering/{trajectories,hits,digi}/:
  list/select/mode/create and the commands of every model a factory makes,
  named <factory>-0 as /vis/.../create/<factory> names the first one;
- /field/<volume>/..., /persistency/... loops, unnamed parameters.
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from g4cmds import strip_comments, call_args, Strings  # noqa: E402

S = Strings({})


def P(name, t, omittable=False, **kw):
    p = {"name": name, "type": t, "omittable": omittable}
    p.update({k: v for k, v in kw.items() if v is not None})
    return p


def C(path, cls, guidance, params, source, **kw):
    c = {"path": path, "class": cls, "guidance": [g for g in guidance if g], "params": params, "source": source}
    c.update(kw)
    return c


# ----------------------------------------------------------------------
# /analysis/
# ----------------------------------------------------------------------
AM = "source/analysis/management/src/G4AnalysisMessenger.cc"
NM = "source/analysis/management/src/G4NtupleMessenger.cc"
PM = "source/analysis/hntools/src/G4PlotMessenger.cc"
HM = "source/analysis/management/src/G4HnMessenger.cc"
TM = "source/analysis/management/include/G4THnMessenger.icc"
PI = ["PreInit", "Idle"]
ACTIVE = ["Idle", "GeomClosed", "EventProc"]


def analysis():
    out, dirs = [], [("/analysis/", "Analysis control commands"), ("/analysis/ntuple/", "ntuple control"),
                     ("/analysis/plot/", "Analysis batch plotting control")]
    a = lambda name, cls, g, pname, omit=False, **kw: out.append(
        C("/analysis/" + name, cls, [g], [P(pname, {"G4UIcmdWithAString": "s", "G4UIcmdWithABool": "b",
                                                   "G4UIcmdWithAnInteger": "i"}[cls], omit, **kw)], AM, states=PI))
    a("openFile", "G4UIcmdWithAString", "Open analysis file", "FileName", True, default="")
    out.append(C("/analysis/write", "G4UIcmdWithoutParameter", ["Write analysis data."], [], AM, states=PI))
    out.append(C("/analysis/reset", "G4UIcmdWithoutParameter", ["Reset analysis data."], [], AM, states=PI))
    a("closeFile", "G4UIcmdWithABool", "Close analysis file and (optionally) reset data.", "IsReset", True, default="true")
    a("list", "G4UIcmdWithABool", "List all/activate analysis objects.", "OnlyIfActive", True, default="true")
    a("setDefaultFileType", "G4UIcmdWithAString", "Set default output file type", "DefaultFileType",
      candidates="csv hdf5 root xml")
    a("setActivation", "G4UIcmdWithABool", "Set activation. \nWhen this option is enabled, only the histograms marked as "
      "activated\nare returned, filled or saved on file.\nNo warning is issued when Get or Fill is called on inactive "
      "histogram.", "Activation")
    a("verbose", "G4UIcmdWithAnInteger", "Set verbose level", "VerboseLevel")
    out[-1]["range"] = "VerboseLevel>=0 && VerboseLevel<=4"
    a("compression", "G4UIcmdWithAnInteger", "Set compression level", "CompressionLevel")
    out[-1]["range"] = "CompressionLevel>=0 && CompressionLevel<=4"
    a("setFileName", "G4UIcmdWithAString", "Set name for the histograms & ntuple file", "Filename")
    a("setHistoDirName", "G4UIcmdWithAString", "Set name for the histograms directory", "HistoDirName")
    a("setNtupleDirName", "G4UIcmdWithAString", "Set name for the ntuple directory", "NtupleDirName")

    nid = P("NtupleId", "i", range="NtupleId>=0", guidance=["Ntuple id"])
    n = lambda name, cls, g, params, states=PI: out.append(C("/analysis/ntuple/" + name, cls, [g], params, NM, states=states))
    n("create", "G4UIcommand", "Create ntuple", [P("name", "s", guidance=["Ntuple name"]),
                                                 P("title", "s", guidance=["Ntuple title"])])
    for t in "IFDS":
        out.append(C("/analysis/ntuple/create" + t + "Column", "G4UIcmdWithAString", ["Create " + t + " ntuple column"],
                     [P("", "s")], NM, states=PI))
    n("finish", "G4UIcmdWithoutParameter", "Finish creating ntuple", [])
    n("delete", "G4UIcommand", "Delete ntuple with given id", [dict(nid), P(
        "keepSetting", "b", True, default="false", guidance=["If set true, activation, file name, etc. options will be "
                                                            "kept\nand applied when a new object with the same id is created."])])
    n("setActivation", "G4UIcommand", "Set activation for the ntuple with given id",
      [dict(nid), P("NtupleActivation", "b", True, default="true", guidance=["Ntuple activation"])])
    n("setActivationToAll", "G4UIcmdWithABool", "Set activation to all ntuples", [P("AllNtupleActivation", "b")])
    n("setFileName", "G4UIcommand", "Set file name for the ntuple with given id",
      [dict(nid), P("NtupleFileName", "s", guidance=["Ntuple file name"])])
    n("setFileNameToAll", "G4UIcmdWithAString", "Set file name to all ntuples", [P("AllNtupleFileName", "s")])
    n("list", "G4UIcommand", "List all/active ntuples",
      [P("onlyIfActive", "b", True, default="true", guidance=["Option whether to list only active objects"])], ACTIVE)

    p = lambda name, cls, g, params: out.append(C("/analysis/plot/" + name, cls, [g], params, PM, states=PI))
    p("setStyle", "G4UIcmdWithAString", "Set plotting style from: \n  ROOT_default:  ROOT style with high resolution fonts\n"
      "  hippodraw:     hippodraw style with high resolution fonts\n  inlib_default: PAW style with low resolution fonts\n"
      "(only inlib_default without FreeType)", [P("Style", "s", candidates="inlib_default")])
    p("setLayout", "G4UIcommand", "Set page layout (number of columns and rows per page).\n   Supported layouts:\n"
      "   columns = 1 .. maxValueAllowed\n   rows    = 1 .. maxValueAllowed, and >= columns",
      [P("columns", "i", guidance=["The number of columns in the page layout."], range="columns>=1"),
       P("rows", "i", guidance=["The number of rows in the page layout."], range="rows>=1")])
    p("setDimensions", "G4UIcommand", "Set the plotter window size (width and height) in pixels.",
      [P("width", "i", guidance=["The page width."]), P("height", "i", guidance=["The page height."])])

    # h1 h2 h3 p1 p2: (DIM of G4THnMessenger, profile?)
    for hn, dim, prof in (("h1", 1, False), ("h2", 2, False), ("h3", 3, False), ("p1", 2, True), ("p2", 3, True)):
        d = "/analysis/" + hn + "/"
        tobj = (str(dim - 1) + "D profile ") if prof else (str(dim) + "D histogram")
        hobj = hn[1] + ("D histogram" if hn[0] == "h" else "D profile")
        dirs.append((d, tobj + " control"))
        hid = lambda: P("id", "i", guidance=["Histogram id"], range="id>=0")

        def dimpars(idim, first_omittable=False):
            ax = "xyz"[idim]
            last_prof = prof and idim == dim - 1
            ps = []
            if not last_prof:
                ps.append(P(ax + "nBins", "i", default="100", guidance=["Number of " + ax + "-bins (default = 100)\n"
                                                                         "Can be reset with /analysis/hn/set command"]))
            ps.append(P(ax + "valMin", "d", default="0.", guidance=["Minimum " + ax + "-value, expressed in unit "
                                                                     "(default = 0.)\nCan be reset with /analysis/hn/set command"]))
            ps.append(P(ax + "valMax", "d", default="1.", guidance=["Maximum " + ax + "-value, expressed in unit "
                                                                     "(default = 1.)\nCan be reset with /analysis/hn/set command"]))
            ps.append(P(ax + "valUnit", "s", True, default="none", guidance=["The unit applied to filled " + ax + "-values"]))
            ps.append(P(ax + "valFcn", "s", True, default="none", candidates="log log10 exp none",
                        guidance=["The function applied to filled " + ax + "-values (log, log10, exp, none)."]))
            if not last_prof:
                ps.append(P(ax + "valBinScheme", "s", True, default="linear", candidates="linear log",
                            guidance=["The binning scheme (linear, log)."]))
            if first_omittable:
                for q in ps[:3]:
                    q["omittable"] = True
            return ps

        t = lambda name, g, params, states=PI, extra=(): out.append(
            C(d + name, "G4UIcommand", [g + tobj, *extra], params, TM, states=states))
        create = [P("name", "s", guidance=["Histogram name (label)"]), P("title", "s", guidance=["Histogram title"])]
        for i in range(dim):
            create += dimpars(i, True)
        t("create", "Create ", create)
        setp = [hid()]
        for i in range(dim):
            setp += dimpars(i)
        t("set", "Set ", setp, extra=["\n  nbins; valMin; valMax; unit; function; binScheme"])
        for i in range(dim):
            A = "XYZ"[i]
            t("set" + A, "Set " + A + " parameters for the ", [hid()] + dimpars(i),
              extra=["\n  n" + A + "bins; " + A + "valMin; " + A + "valMax; " + A + "unit; " + A + "function; " + A + "binScheme"])
        t("delete", "Delete ", [hid(), P("keepSetting", "b", True, default="false", guidance=[
            "If set true, activation, plotting, etc. options will be kept\nand applied when a new object with the same "
            "id is created."])])
        t("setTitle", "Set title for the ", [hid(), P("title", "s", True, default="none", guidance=[tobj + " title"])])
        for i in range(min(dim + 1, 3)):
            A = "XYZ"[i]
            t("set" + A + "axis", "Set " + A + "-axis title for the ",
              [hid(), P("axis", "s", guidance=[tobj + " " + A + "-axis title"])])
        t("list", "List all/activate ", [P("onlyIfActive", "b", True, default="true",
                                           guidance=["Option whether to list only active objects"])], ACTIVE)
        t("get", "Get the address of the ", [hid()], ACTIVE, ["This command is only for Geant4 internal use."])
        t("getVector", "Get the address of the vector of the ", [], ACTIVE, ["This command is only for Geant4 internal use."])

        h = lambda name, cls, g, params: out.append(C(d + name, cls, [g + hobj], params, HM, states=PI))
        opt = lambda o: P(o, "b", True, default="true", guidance=[hobj + " " + o + " option"])
        h("setAscii", "G4UIcommand", "Print  on ascii file the ", [hid(), opt("hnAscii")])
        h("setActivation", "G4UIcommand", "Set activation to the ", [hid(), opt("hnActivation")])
        h("setActivationToAll", "G4UIcmdWithABool", "Set activation to all", [P("Activation", "b")])
        h("setPlotting", "G4UIcommand", "(In)Activate batch plotting of the  ", [hid(), opt("hnPlotting")])
        h("setPlottingToAll", "G4UIcmdWithABool", "(In)Activate batch plotting of all ", [P("Plotting", "b")])
        h("setFileName", "G4UIcommand", "Set the output file name for the ",
          [hid(), P("hnFileName", "s", guidance=[hobj + " output file name"])])
        h("setFileNameToAll", "G4UIcmdWithAString", "Set output file name for all  ", [P("FileName", "s")])
        hdim = int(hn[1])
        for i in range(min(hdim + 1, 3)):
            A = "XYZ"[i]
            h("set" + A + "axisLog", "G4UIcommand", "Activate " + A + "-axis  log scale for plotting of the ",
              [hid(), P("axis", "b", guidance=[hobj + " " + A + "-axis log scale"])])
    return out, dirs


# ----------------------------------------------------------------------
# /vis/modeling and /vis/filtering
# ----------------------------------------------------------------------
BASES = {
    "G4ModelCmdApplyBool": ("G4UIcmdWithABool", [P("Bool", "b")]),
    "G4ModelCmdApplyNull": ("G4UIcommand", []),
    "G4ModelCmdApplyDouble": ("G4UIcmdWithADouble", [P("Double", "d")]),
    "G4ModelCmdApplyDoubleAndUnit": ("G4UIcmdWithADoubleAndUnit", [P("DoubleAndUnit", "d"), P("Unit", "s")]),
    "G4ModelCmdApplyInteger": ("G4UIcmdWithAnInteger", [P("Integer", "i")]),
    "G4ModelCmdApplyString": ("G4UIcmdWithAString", [P("", "s")]),
}
RGBA = [P("Red component", "d"), P("Green component", "d"), P("Blue component", "d"), P("Alpha component", "d")]


def model_classes(root):
    """class G4ModelCmdXxx : public G4ModelCmdApplyYyy<M>: default name, guidance, candidates."""
    out = {}
    for rel in ("G4ModelCommandsT.hh", "G4ModelCompoundCommandsT.hh"):
        src = strip_comments(open(os.path.join(root, "source/visualization/modeling/include", rel), encoding="utf-8").read())
        for m in re.finditer(r"class\s+(G4ModelCmd\w+)\s*:\s*public\s+(G4ModelCmdApply\w+)\s*<\s*M\s*>", src):
            body = src[m.end(): src.find("template", m.end()) if src.find("template", m.end()) > 0 else len(src)]
            nm = re.search(r"const\s+G4String\s*&\s*cmdName\s*=\s*((?:\s*\"[^\"]*\")+)", body)
            info = {"base": m.group(2), "name": S.eval(nm.group(1)) if nm else "", "guidance": [], "candidates": None,
                    "file": "source/visualization/modeling/include/" + rel}
            for g in re.finditer(r"SetGuidance\s*\(", body):
                a, _ = call_args(body, g.end() - 1)
                info["guidance"].append(S.eval(a[0]))
            c = re.search(r"Set(?:Parameter)?Candidates\s*\(", body)
            if c:
                a, _ = call_args(body, c.end() - 1)
                info["candidates"] = S.eval(a[0])
            out[m.group(1)] = info
    return out


def model_commands(classes, cls, prefix, override=None):
    """The commands one messenger class makes under prefix (placement/model)."""
    k = classes[cls]
    name = override or k["name"]
    path = prefix + "/" + name
    base = k["base"]
    if base == "G4ModelCmdApplyStringColour":
        return [C(path, "G4UIcommand", ["Set variable colour through a string"] + k["guidance"],
                  [P("Variable", "s"), P("Value", "s")], k["file"]),
                C(path + "RGBA", "G4UIcommand", ["Set variable colour through red, green, blue and alpha components"],
                  [P("Variable", "s")] + [dict(x) for x in RGBA], k["file"])]
    if base == "G4ModelCmdApplyColour":
        return [C(path, "G4UIcommand", ["Set colour through a string"] + k["guidance"], [P("Variable", "s")], k["file"]),
                C(path + "RGBA", "G4UIcommand", ["Set colour through red, green, blue and alpha components",
                                                 "Four inputs are expected."], [dict(x) for x in RGBA], k["file"])]
    ucls, params = BASES[base]
    params = [dict(x) for x in params]
    if k["candidates"] and params:
        params[0]["candidates"] = k["candidates"]
    return [C(path, ucls, list(k["guidance"]), params, k["file"])]


def factories(root):
    """(placement, factory name, [(messenger class, name override)], with a default context)."""
    placements = {"G4TrajectoryModelFactories.cc": "/vis/modeling/trajectories",
                  "G4TrajectoryFilterFactories.cc": "/vis/filtering/trajectories",
                  "G4HitFilterFactories.cc": "/vis/filtering/hits",
                  "G4DigiFilterFactories.cc": "/vis/filtering/digi"}
    out = []
    for fn, placement in placements.items():
        src = strip_comments(open(os.path.join(root, "source/visualization/modeling/src", fn), encoding="utf-8").read())
        names = {m.group(1): m.group(2) for m in re.finditer(r"(\w+Factory)::\1\s*\(\s*\)\s*:\s*G4VModelFactory<.*?>\s*\(\s*\"(\w+)\"", src)}
        for m in re.finditer(r"ModelAndMessengers\s+(\w+Factory)::Create\s*\(", src):
            end = src.find("ModelAndMessengers", m.end())
            body = src[m.end(): end if end > 0 else len(src)]
            msgrs = [(x.group(1), x.group(2)) for x in re.finditer(
                r"new\s+(G4ModelCmd\w+)\s*<\s*\w+\s*>\s*\(\s*model\s*,\s*placement\s*(?:,\s*\"(\w+)\")?\s*\)", body)]
            out.append((placement, names[m.group(1)], msgrs, "AddContextMsgrs" in body,
                        "source/visualization/modeling/src/" + fn))
    return out


CONTEXT = ["G4ModelCmdSetDrawLine", "G4ModelCmdSetLineVisible", "G4ModelCmdSetLineColour", "G4ModelCmdSetLineWidth",
           "G4ModelCmdSetDrawStepPts", "G4ModelCmdSetStepPtsVisible", "G4ModelCmdSetStepPtsColour",
           "G4ModelCmdSetStepPtsSize", "G4ModelCmdSetStepPtsSizeType", "G4ModelCmdSetStepPtsType",
           "G4ModelCmdSetStepPtsFillStyle", "G4ModelCmdSetDrawAuxPts", "G4ModelCmdSetAuxPtsVisible",
           "G4ModelCmdSetAuxPtsColour", "G4ModelCmdSetAuxPtsSize", "G4ModelCmdSetAuxPtsSizeType",
           "G4ModelCmdSetAuxPtsType", "G4ModelCmdSetAuxPtsFillStyle", "G4ModelCmdSetTimeSliceInterval"]


def vis_models(root):
    classes = model_classes(root)
    out, dirs = [], []
    LM = "source/visualization/management/include/G4VisCommandsListManager.hh"
    MC = "source/visualization/management/include/G4VisCommandModelCreate.hh"
    facts = factories(root)
    for placement in ("/vis/modeling/trajectories", "/vis/filtering/trajectories", "/vis/filtering/hits",
                      "/vis/filtering/digi"):
        out.append(C(placement + "/list", "G4UIcmdWithAString", ["List objects registered with list manager"],
                     [P("name", "s", True)], LM))
        if placement.startswith("/vis/modeling"):
            out.append(C(placement + "/select", "G4UIcmdWithAString", ["Select created object"], [P("name", "s")], LM))
        else:
            out.append(C(placement + "/mode", "G4UIcmdWithAString", ["Set mode of operation"],
                         [P("mode", "s", candidates="soft hard")], LM))
    for placement, fname, msgrs, context, src in facts:
        out.append(C(placement + "/create/" + fname, "G4UIcmdWithAString",
                     ["Create a " + fname + " model and associated messengers.", "Generated model becomes current.",
                      "Without a name the model is called " + fname + "-0, " + fname + "-1, ..."],
                     [P("model-name", "s", True)], MC))
        model = placement + "/" + fname + "-0"
        dirs.append((model + "/", "Commands of a " + fname + " model, named " + fname + "-0 when created without a name"))
        for cls, override in msgrs:
            for c in model_commands(classes, cls, model, override):
                c["template"] = True
                out.append(c)
        if context:
            dirs.append((model + "/default/", "Commands for default configuration"))
            for cls in CONTEXT:
                for c in model_commands(classes, cls, model + "/default"):
                    c["template"] = True
                    out.append(c)
    return out, dirs


# ----------------------------------------------------------------------

DROP_SOURCES = ("source/intercoms/src/G4GenericMessenger.cc", "source/intercoms/src/G4UImessenger.cc",
                "source/visualization/modeling/include/", "source/visualization/management/include/G4VisCommandModelCreate.hh",
                "source/visualization/management/include/G4VisCommandsListManager.hh", "source/analysis/")
TYPE_LABEL = {"s": "string", "b": "bool", "i": "int", "d": "double", "c": "char", "l": "long"}


def main():
    root, raw, outp = sys.argv[1], sys.argv[2], sys.argv[3]
    d = json.load(open(raw, encoding="utf-8"))
    cmds, dirs = [], {x["path"]: x for x in d["directories"] if "<" not in x["path"]}
    for c in d["commands"]:
        if c["source"].startswith(DROP_SOURCES) and ("<" in c["path"] or c["source"].startswith("source/analysis/")):
            continue
        p = c["path"]
        m = re.match(r"^/field/<\w+>/(\w+)$", p)
        if m:
            c["path"] = "/field/" + m.group(1)
            c["guidance"].append("For a field local to a logical volume: /field/<volumeName>/" + m.group(1))
        m = re.match(r"^(/persistency/(?:store|set/writeFile)/)<wrObji>$", p)
        if m:
            for obj in ("HepMC", "MCTruth", "Hits"):
                x = json.loads(json.dumps(c))
                x["path"] = m.group(1) + obj
                x["guidance"] = [g.replace("<wrObji>", obj) for g in x["guidance"]]
                cmds.append(x)
            continue
        if p == "/persistency/set/ReadFile/<rdObj0>":
            c["path"] = "/persistency/set/ReadFile/Hits"
            c["guidance"] = [g.replace("<rdObj0>", "Hits") for g in c["guidance"]]
        if "<" in c["path"]:
            c["template"] = True
        cmds.append(c)
    a, ad = analysis()
    v, vd = vis_models(root)
    cmds += a + v
    for path, g in ad + vd + [("/field/", "Magnetic (or other type) field control commands.")]:
        dirs.setdefault(path, {"path": path, "guidance": [g], "sources": []})
    # clean up: placeholders in values, names for unnamed parameters, the dynamic flag
    for c in cmds:
        c.pop("dynamic", None)
        for q in c["params"]:
            for k in ("candidates", "default", "range"):
                if isinstance(q.get(k), str) and re.search(r"<\w+>", q[k]):
                    del q[k]
            if not q.get("name"):
                q["name"] = TYPE_LABEL.get(q["type"], "value")
            if isinstance(q.get("candidates"), str):
                q["candidates"] = " ".join(q["candidates"].split())
        if isinstance(c.get("range"), str) and re.search(r"<\w+>", c["range"]):
            del c["range"]
    # every directory on the way to a command
    for c in cmds:
        parts = c["path"].strip("/").split("/")[:-1]
        for i in range(1, len(parts) + 1):
            dp = "/" + "/".join(parts[:i]) + "/"
            dirs.setdefault(dp, {"path": dp, "guidance": [], "sources": []})
    seen, unique = set(), []
    for c in sorted(cmds, key=lambda c: c["path"].lower()):
        if c["path"] in seen:
            continue
        seen.add(c["path"])
        unique.append(c)
    doc = {"geant4": sys.argv[4] if len(sys.argv) > 4 else "","directories": sorted(dirs.values(), key=lambda x: x["path"].lower()),
           "commands": unique}
    # compact, as Sphere carries it: no empty fields, no false flags, no extraction bookkeeping
    for c in doc["commands"]:
        for k in [k for k, v in c.items() if v in ([], None, False)]:
            del c[k]
        for p in c.get("params", []):
            for k in [k for k, v in p.items() if v in ([], None) or (k in ("omittable", "currentAsDefault") and v is False)]:
                del p[k]
    for x in doc["directories"]:
        x.pop("sources", None)
        x.pop("weak", None)
    with open(outp, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
    print(len(unique), "commands,", len(doc["directories"]), "directories,",
          sum(1 for c in unique if c.get("template")), "templates")


if __name__ == "__main__":
    main()
