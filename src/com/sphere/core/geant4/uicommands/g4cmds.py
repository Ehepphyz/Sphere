"""Extract Geant4 UI commands statically from the Geant4 source tree.

Usage: python -I g4cmds.py <geant4-source-root> <out.json>

Walks every .cc/.icc/.hh under source/, follows G4UIdirectory, G4UIcommand,
G4UIcmdWith*, G4UIparameter and G4GenericMessenger constructions and the
setters applied to them, and resolves simple string expressions (literals,
concatenations, local string variables). Paths it cannot resolve keep a
<placeholder> for the unknown part and are flagged "dynamic".
"""
import json
import os
import re
import sys

CMD_TYPES = {
    "G4UIcommand": [],
    "G4UIcmdWithoutParameter": [],
    "G4UIcmdWithAString": [("s",)],
    "G4UIcmdWithABool": [("b",)],
    "G4UIcmdWithAnInteger": [("i",)],
    "G4UIcmdWithADouble": [("d",)],
    "G4UIcmdWithADoubleAndUnit": [("d",), ("s", "Unit")],
    "G4UIcmdWith3Vector": [("d",), ("d",), ("d",)],
    "G4UIcmdWith3VectorAndUnit": [("d",), ("d",), ("d",), ("s", "Unit")],
    "G4UIcmdWithNucleusLimits": [("i",), ("i",), ("i",), ("i",)],
}
STATE_NAMES = {"G4State_PreInit": "PreInit", "G4State_Init": "Init", "G4State_Idle": "Idle",
               "G4State_GeomClosed": "GeomClosed", "G4State_EventProc": "EventProc",
               "G4State_Quit": "Quit", "G4State_Abort": "Abort"}


def strip_comments(src):
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '"' or c == "'":
            j = i + 1
            while j < n and src[j] != c:
                j += 2 if src[j] == "\\" else 1
            out.append(src[i:j + 1])
            i = j + 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            out.append(" ")
            i = n if j < 0 else j + 2
        elif src.startswith('R"', i):
            m = re.match(r'R"([^(]*)\(', src[i:])
            end = src.find(")" + m.group(1) + '"', i)
            out.append(json.dumps(src[i + len(m.group(0)):end]))
            i = end + len(m.group(1)) + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def drop_preprocessor(src):
    return "\n".join("" if l.lstrip().startswith("#") else l for l in src.split("\n"))


def statements(src):
    """Split on ; { } at any depth, keeping strings intact."""
    buf, i, n, depth = [], 0, len(src), 0
    while i < n:
        c = src[i]
        if c == '"' or c == "'":
            j = i + 1
            while j < n and src[j] != c:
                j += 2 if src[j] == "\\" else 1
            buf.append(src[i:j + 1])
            i = j + 1
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        if (c in ";{}" and depth <= 0) or (c in "{}" and depth > 0):
            s = "".join(buf).strip()
            if s:
                yield s
            buf = []
            if c in "{}":
                yield c
            depth = max(depth, 0) if c == ";" else depth
        else:
            buf.append(c)
        i += 1


def split_args(s):
    args, buf, depth, i, n = [], [], 0, 0, len(s)
    while i < n:
        c = s[i]
        if c == '"' or c == "'":
            j = i + 1
            while j < n and s[j] != c:
                j += 2 if s[j] == "\\" else 1
            buf.append(s[i:j + 1])
            i = j + 1
            continue
        if c in "([{<" and not (c == "<" and depth == 0 and not re.match(r"\w", s[i + 1:i + 2] or " ")):
            depth += 1
        elif c in ")]}>" and depth > 0 and not (c == ">" and s[i - 1:i] == "-"):
            depth -= 1
        if c == "," and depth == 0:
            args.append("".join(buf).strip())
            buf = []
        else:
            buf.append(c)
        i += 1
    if "".join(buf).strip():
        args.append("".join(buf).strip())
    return args


def call_args(stmt, start):
    """Arguments of the call whose '(' is at stmt[start]."""
    depth, i = 0, start
    while i < len(stmt):
        c = stmt[i]
        if c == '"' or c == "'":
            j = i + 1
            while j < len(stmt) and stmt[j] != c:
                j += 2 if stmt[j] == "\\" else 1
            i = j + 1
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return split_args(stmt[start + 1:i]), i
        i += 1
    return split_args(stmt[start + 1:]), len(stmt)


def unescape(lit):
    try:
        return json.loads(lit)
    except Exception:
        return lit[1:-1].replace('\\"', '"').replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")


class Strings:
    """Best-effort evaluation of C++ string expressions."""

    def __init__(self, env):
        self.env = env

    def eval(self, expr):
        e = expr.strip()
        for suffix in (".c_str()", ".data()", ".str()"):
            while e.endswith(suffix):
                e = e[: -len(suffix)].strip()
        while e.startswith("(") and e.endswith(")") and self._balanced(e[1:-1]):
            e = e[1:-1].strip()
            for suffix in (".c_str()", ".data()"):
                while e.endswith(suffix):
                    e = e[: -len(suffix)].strip()
        m = re.fullmatch(r"(?:G4String|std::string|std::move|G4UIcommand::ConvertToString|std::to_string)\s*\((.*)\)", e, re.S)
        if m:
            inner = split_args(m.group(1))
            return self.eval(inner[0]) if inner else ""
        parts = self._split_plus(e)
        if len(parts) > 1:
            return "".join(self.eval(p) for p in parts)
        if re.fullmatch(r'(\s*"(?:[^"\\]|\\.)*"\s*)+', e):
            return "".join(unescape(x) for x in re.findall(r'"(?:[^"\\]|\\.)*"', e))
        if re.fullmatch(r"'(?:[^'\\]|\\.)'", e):
            return unescape('"' + e[1:-1] + '"')
        key = re.sub(r"^(this->|\*)", "", e)
        if key in self.env:
            return self.env[key]
        name = re.sub(r"\W+", "", key.split("->")[-1].split(".")[-1]) or "x"
        name = re.sub(r"^(f|fp|the|m_)(?=[A-Z])", "", name)
        return "<" + name[:1].lower() + name[1:] + ">"

    @staticmethod
    def _balanced(s):
        d = 0
        for c in s:
            d += c == "("
            d -= c == ")"
            if d < 0:
                return False
        return d == 0

    @staticmethod
    def _split_plus(e):
        parts, buf, depth, i, n = [], [], 0, 0, len(e)
        while i < n:
            c = e[i]
            if c == '"':
                j = i + 1
                while j < n and e[j] != '"':
                    j += 2 if e[j] == "\\" else 1
                buf.append(e[i:j + 1])
                i = j + 1
                continue
            if c in "([":
                depth += 1
            elif c in ")]":
                depth -= 1
            if c == "+" and depth == 0 and e[i + 1:i + 2] != "+" and e[i - 1:i] != "+" and e[i + 1:i + 2] != "=":
                parts.append("".join(buf))
                buf = []
            else:
                buf.append(c)
            i += 1
        parts.append("".join(buf))
        return [p for p in parts if p.strip()] if len(parts) > 1 else parts


NEW_RE = re.compile(r"(?:new\s+|std::make_unique\s*<\s*|make_unique\s*<\s*)(G4UI(?:directory|command|cmd\w+|parameter))\b\s*>?\s*\(")
ASSIGN_RE = re.compile(r"^(?:(?:const\s+)?(?:auto|G4UI\w+|G4UIcommand)\s*\*?\s*&?\s*)?([\w\[\]\.\->:]+?)\s*=\s*")
SETTER_RE = re.compile(r"^([\w\[\]\.\->:()*]+?)\s*(?:->|\.)\s*(\w+)\s*\(")
STRVAR_RE = re.compile(r"^(?:static\s+)?(?:const\s+)?(?:G4String|std::string|auto|const\s+char\s*\*|char\s*const\s*\*)\s*&?\s*(?:\w+::)*(\w+)\s*(?:=\s*(.+)|\((.+)\)|\{(.+)\})$", re.S)
REASSIGN_RE = re.compile(r"^([\w\.\->]+)\s*(\+?=)\s*(.+)$", re.S)
GENERIC_RE = re.compile(r"(?:new\s+|make_unique\s*<\s*)G4GenericMessenger\s*>?\s*\(")


def boolarg(a):
    """A bool argument as written: true, false, or omitable = true (an assignment inside the call)."""
    return re.sub(r"^\w+\s*=\s*", "", a.strip())


def norm_key(k):
    return re.sub(r"^this->", "", k.strip().strip("*").strip())


class FileParser:
    def __init__(self, path, rel, result):
        self.path, self.rel, self.res = path, rel, result
        self.objs = {}       # variable -> command/parameter/directory dict
        self.generic = {}    # generic messenger variable -> directory
        self.env = {}        # string variables
        self.S = Strings(self.env)

    def parse(self):
        with open(self.path, encoding="utf-8", errors="replace") as f:
            src = drop_preprocessor(strip_comments(f.read()))
        if "G4UI" not in src and "G4GenericMessenger" not in src:
            return
        # file-level string constants first, so later uses resolve
        for st in statements(src):
            if st in "{}":
                continue
            self.statement(st)

    def statement(self, st):
        st = re.sub(r"\s+", " ", st).strip()
        parts = split_args(st)
        if len(parts) > 1 and re.match(r"^[\w\[\]\.\->]+\s*(->|\.)\s*\w+\s*\(", parts[0]) and parts[0].endswith(")"):
            # comma operator joining two statements: a->Set(...), b = new ...
            for part in parts:
                self.statement(part)
            return
        st = re.sub(r"^(?:else\s+|return\s+)", "", st)
        m = re.match(r"^(?:if|for|while|switch)\s*\(", st)
        if m:
            _, end = call_args(st, st.index("("))
            st = st[end + 1:].strip()
            if not st:
                return
        fp = re.match(r"^(?:const\s+)?G4UIcommand\s*\*\s*(\w+)\s*=\s*[\w\->\.]+->FindPath\s*\(\s*\"([^\"]+)\"\s*\)$", st)
        if fp:
            self.objs[fp.group(1)] = {"path": fp.group(2), "params": [], "class": "", "guidance": [], "ref": True}
            return
        cp = re.match(r"^Copy(Guidance|Parameters)From\s*\(", st)
        if cp:
            # G4VVisCommand: guidance (from a line on) or parameters of another command, copied at run time
            args, _ = call_args(st, st.index("("))
            src, dst = (self.objs.get(norm_key(a)) for a in args[:2])
            if src is not None and dst is not None and "params" in dst:
                if cp.group(1) == "Guidance":
                    dst["copyGuidanceFrom"] = [src["path"], int(args[2]) if len(args) > 2 and args[2].strip().isdigit() else 0]
                else:
                    dst["copyParametersFrom"] = src["path"]
            return
        dv = re.match(r"^(?:G4String|std::string)\s+(\w+)$", st)
        if dv:
            # declared empty, filled by += below (often in a loop over particles or processes)
            self.env[dv.group(1)] = ""
            return
        inits = list(re.finditer(r"(\w+)\s*[({]\s*new\s+(G4UI(?:directory|command|cmd\w+))\s*\(", st))
        if inits and not re.match(r"^[\w\[\]\.\->]+\s*=", st) and not inits[0].start() == 0:
            # constructor initializer list: fpActivateChem(new G4UIcmdWithABool("/chem/activate", this)), ...
            for im in inits:
                args, _ = call_args(st, im.end() - 1)
                self.statement(im.group(1) + " = new " + im.group(2) + "(" + ", ".join(args) + ")")
            return
        sm = STRVAR_RE.match(st)
        if sm and "new " not in st and "->Declare" not in st:
            val = sm.group(2) or sm.group(3) or sm.group(4)
            if val is not None and ('"' in val or "+" in val or "std::move" in val or not st.startswith("auto")
                                    or re.fullmatch(r"[\w\->\.]+(\(\))?", val.strip())):
                self.env[sm.group(1)] = self.S.eval(val)
            return
        rm = REASSIGN_RE.match(st)
        if rm and "new " not in st and "make_unique" not in st and "(" not in rm.group(1) and not re.search(r"\bCreate\w*\s*[<(]", rm.group(3)):
            name, op, val = rm.groups()
            if '"' in val or name in self.env or (op == "+=" and not re.search(r"\b(new|make_unique)\b", val)):
                v = self.S.eval(val)
                self.env[name] = (self.env.get(name, "") + v) if op == "+=" else v
                return
        am2 = re.match(r"^(\w+)\s*\.\s*append\s*\((.+)\)$", st)
        if am2 and am2.group(1) in self.env:
            self.env[am2.group(1)] += self.S.eval(am2.group(2))
            return
        cd = re.match(r"^CreateDirectory\s*\(", st)
        if cd:
            args, _ = call_args(st, st.index("("))
            if not args:
                return
            path = self.S.eval(args[0])
            self.base_dir = path if path.endswith("/") else path + "/"
            self.res.directory(path, self.S.eval(args[1]) if len(args) > 1 else "", self.rel)
            return
        cc = re.search(r"(?<![\w:])CreateCommand\s*<\s*(G4UI\w+)\s*>\s*\(", st)
        if cc and "/analysis/" not in self.rel:
            args, _ = call_args(st, cc.end() - 1)
            name = self.S.eval(args[0]) if args else ""
            path = name if name.startswith("/") else getattr(self, "base_dir", "/") + name
            params = [{"name": "", "type": t[0], "omittable": False, **({"name": t[1]} if len(t) > 1 else {})}
                      for t in CMD_TYPES.get(cc.group(1), [])]
            c = self.res.command(path, cc.group(1), params, self.rel)
            if len(args) > 1:
                c["guidance"].append(self.S.eval(args[1]))
            am = ASSIGN_RE.match(st)
            if am:
                self.objs[norm_key(am.group(1))] = c
            return
        nm = NEW_RE.search(st)
        if nm:
            self.creation(st, nm)
            return
        gm = GENERIC_RE.search(st)
        if gm:
            am = ASSIGN_RE.match(st)
            args, _ = call_args(st, gm.end() - 1)
            if am and len(args) >= 2:
                d = self.S.eval(args[1])
                self.generic[norm_key(am.group(1))] = d
                self.res.directory(d, self.S.eval(args[2]) if len(args) > 2 else "", self.rel, weak=True)
            return
        dm = re.search(r"([\w\.\->]+?)\s*->\s*(Declare\w+)\s*\(", st)
        if dm and norm_key(dm.group(1)) in self.generic:
            # auto& cmd = messenger->DeclareProperty(...): later cmd.SetXxx(...) apply to that command
            args, _ = call_args(st, dm.end() - 1)
            before = set(map(id, self.res.commands.values()))
            self.generic_decl(norm_key(dm.group(1)), dm.group(2), args, st[dm.start():])
            made = [c for c in self.res.commands.values() if id(c) not in before]
            am = re.match(r"^(?:auto|G4GenericMessenger::Command)\s*&\s*(\w+)\s*=", st)
            if am and made:
                self.objs[am.group(1)] = made[-1]
            return
        sm2 = SETTER_RE.match(st)
        if sm2:
            self.setter(st, sm2)

    def creation(self, st, nm):
        cls = nm.group(1)
        args, end = call_args(st, nm.end() - 1)
        am = ASSIGN_RE.match(st)
        var = norm_key(am.group(1)) if am and am.start(1) < nm.start() else None
        pm = re.match(r"^([\w\[\]\.\->]+?)\s*(?:->|\.)\s*(?:push_back|emplace_back|insert)\s*\(", st)
        if var is None and pm:
            var = norm_key(pm.group(1)) + "[]"
        if cls == "G4UIparameter":
            p = {"name": self.S.eval(args[0]) if args else "", "type": "s", "omittable": False}
            if len(args) > 1:
                t = args[1].strip().strip("'")
                p["type"] = t.lower() if len(t) == 1 else "s"
            if len(args) > 2:
                p["omittable"] = boolarg(args[2]) == "true"
            if var:
                self.objs[var] = p
            return
        if not args:
            return
        path = self.S.eval(args[0])
        if cls == "G4UIdirectory":
            d = self.res.directory(path, "", self.rel)
            self.base_dir = path if path.endswith("/") else path + "/"
            if var:
                self.objs[var] = d
            return
        params = [{"name": "", "type": t[0], "omittable": False, **({"name": t[1]} if len(t) > 1 else {})}
                  for t in CMD_TYPES.get(cls, [])]
        c = self.res.command(path, cls, params, self.rel)
        if var:
            self.objs[var] = c
        # chained setter on the same line, e.g. (new X(...))->SetGuidance(...)
        rest = st[end + 1:]
        if rest.strip().startswith("->"):
            self.setter_on(c, rest)

    def setter_on(self, obj, rest):
        for mm in re.finditer(r"->\s*(\w+)\s*\(", rest):
            args, _ = call_args(rest, mm.end() - 1)
            self.apply(obj, mm.group(1), args)

    def setter(self, st, sm):
        key = norm_key(sm.group(1))
        method = sm.group(2)
        args, _ = call_args(st, sm.end() - 1)
        if key in self.generic:
            self.generic_decl(key, method, args, st)
            return
        obj = self.objs.get(key)
        if obj is None:
            base = re.sub(r"\[[^\]]*\]$", "[]", key)
            obj = self.objs.get(base)
        if obj is None:
            m = re.match(r"(\w+)\.(?:back|front)\(\)$", key)
            obj = self.objs.get(m.group(1) + "[]") if m else None
        if obj is None and method == "SetParameter" and args:
            return
        if obj is not None:
            self.apply(obj, method, args)

    def generic_decl(self, key, method, args, st):
        d = self.generic[key]
        if not method.startswith("Declare") or not args:
            if method == "SetGuidance" and args:
                self.res.directory(d, self.S.eval(args[0]), self.rel, weak=True)
            return
        name = self.S.eval(args[0])
        guidance = ""
        unit = None
        if "WithUnit" in method:
            unit = self.S.eval(args[1]) if len(args) > 1 else None
            guidance = self.S.eval(args[3]) if len(args) > 3 else ""
        else:
            guidance = self.S.eval(args[2]) if len(args) > 2 else ""
        params = [{"name": "value", "type": "s", "omittable": False}] if "Property" in method else []
        if unit:
            params = [{"name": "value", "type": "d", "omittable": False, "unit": unit}]
        c = self.res.command(d.rstrip("/") + "/" + name, "G4GenericMessenger." + method, params, self.rel)
        if guidance:
            c["guidance"].append(guidance)
        # chained Command& setters: .SetGuidance(...).SetParameterName(...)...
        tail = st[st.find(")", st.find(method)) + 1:]
        for mm in re.finditer(r"\.\s*(\w+)\s*\(", tail):
            a, _ = call_args(tail, mm.end() - 1)
            self.apply(c, mm.group(1), a, generic=True)

    def apply(self, obj, method, args, generic=False):
        S = self.S
        is_cmd = "path" in obj and "params" in obj
        is_dir = "path" in obj and "params" not in obj
        if method == "SetGuidance" and args:
            if obj.get("weak"):
                # a G4UIdirectory's own guidance replaces what a G4GenericMessenger said of the path
                obj["guidance"], obj["weak"] = [], False
            obj.setdefault("guidance", []).append(S.eval(args[0]))
            return
        if is_dir:
            return
        if not is_cmd:  # parameter
            if method == "SetDefaultValue" and args:
                obj["default"] = self.literal(args[0])
            elif method == "SetParameterCandidates" and args:
                obj["candidates"] = S.eval(args[0])
            elif method == "SetParameterRange" and args:
                obj["range"] = S.eval(args[0])
            elif method == "SetDefaultUnit" and args:
                obj["defaultUnit"] = S.eval(args[0])
            elif method == "SetOmittable" and args:
                obj["omittable"] = boolarg(args[0]) == "true"
            elif method == "SetCurrentAsDefault" and args:
                obj["currentAsDefault"] = boolarg(args[0]) == "true"
            elif method == "SetParameterName" and args:
                obj["name"] = S.eval(args[0])
            elif method == "SetParameterType" and args:
                obj["type"] = args[0].strip().strip("'").lower()
            return
        params = obj["params"]
        if method == "SetParameter" and args:
            p = self.objs.get(norm_key(args[0]))
            if p is None:
                nm = NEW_RE.search(args[0])
                if nm and nm.group(1) == "G4UIparameter":
                    a, _ = call_args(args[0], nm.end() - 1)
                    p = {"name": S.eval(a[0]) if a else "", "type": a[1].strip().strip("'").lower() if len(a) > 1 else "s",
                         "omittable": len(a) > 2 and boolarg(a[2]) == "true"}
            if p is not None:
                params.append(p)
        elif method == "SetParameterName" and args:
            names = [a for a in args if a.strip().startswith('"')
                     or re.fullmatch(r"\w+", a.strip()) and boolarg(a) not in ("true", "false")]
            bools = [boolarg(a) == "true" for a in args if boolarg(a) in ("true", "false")]
            if generic:
                if params:
                    params[0]["name"] = S.eval(args[0])
                    if len(args) > 1:
                        params[0]["omittable"] = boolarg(args[1]) == "true"
                return
            n_names = max(1, min(len(params) - (1 if params and params[-1].get("name") == "Unit" else 0), len(names)))
            for i, a in enumerate(names[:n_names]):
                if i < len(params):
                    params[i]["name"] = S.eval(a)
            named = params[:-1] if obj["class"].endswith("AndUnit") else params
            if bools:
                for p in named:
                    p["omittable"] = bools[0]
                if len(bools) > 1 and bools[1]:
                    for p in named:
                        p["currentAsDefault"] = True
        elif method == "SetDefaultValue" and args:
            vals = [self.literal(a) for a in args]
            if obj["class"] in ("G4UIcmdWith3Vector", "G4UIcmdWith3VectorAndUnit") and len(args) == 1:
                vm = re.match(r"G4ThreeVector\s*\((.*)\)", args[0].strip())
                vals = [self.literal(v) for v in split_args(vm.group(1))] if vm else vals
            for p, v in zip(params, vals):
                p["default"] = v
        elif method in ("SetCandidates", "SetParameterCandidates") and args:
            if params:
                params[0]["candidates"] = S.eval(args[0])
        elif method == "SetRange" and args:
            obj["range"] = S.eval(args[0])
        elif method == "SetParameterRange" and args and generic:
            if params:
                params[0]["range"] = S.eval(args[0])
        elif method in ("SetDefaultUnit", "SetUnitCandidates", "SetUnitCategory", "SetUnitCategory") and args:
            v = S.eval(args[0])
            key = {"SetDefaultUnit": "defaultUnit", "SetUnitCandidates": "unitCandidates",
                   "SetUnitCategory": "unitCategory"}[method]
            if obj["class"].endswith("AndUnit") and params:
                params[-1][key] = v
                if method == "SetDefaultUnit":
                    params[-1]["omittable"] = True
                    params[-1]["default"] = v
            else:
                obj[key] = v
            if generic and method == "SetUnitCategory" and params:
                params[0]["unitCategory"] = v
        elif method in ("AvailableForStates", "SetStates"):
            states = [STATE_NAMES.get(a.strip(), a.strip()) for a in args]
            obj["states"] = states
        elif method == "SetToBeBroadcasted" and args:
            obj["broadcast"] = boolarg(args[0]) == "true"
        elif method == "SetDefaultValueForStates":
            pass

    def literal(self, a):
        a = a.strip()
        if a.startswith('"') or "+" in a or a.startswith("G4String"):
            return self.S.eval(a)
        if a in ("true", "false"):
            return a
        m = re.fullmatch(r"(-?[\d.]+(?:[eE][-+]?\d+)?)\s*[fFlL]?\s*(?:\*\s*(\w+))?", a)
        if m:
            return m.group(1) + (" " + m.group(2) if m.group(2) else "")
        return a


class Result:
    def __init__(self):
        self.commands = {}
        self.dirs = {}

    def directory(self, path, guidance, src, weak=False):
        """weak: a G4GenericMessenger's description, kept only when no G4UIdirectory describes the path."""
        if not path.endswith("/"):
            path += "/"
        d = self.dirs.setdefault(path, {"path": path, "guidance": [], "sources": [], "weak": False})
        if guidance and guidance not in d["guidance"]:
            if weak and d["guidance"] and not d["weak"]:
                pass
            elif not weak and d["weak"]:
                d["guidance"], d["weak"] = [guidance], False
            else:
                d["guidance"].append(guidance)
                d["weak"] = weak if len(d["guidance"]) == 1 else d["weak"]
        if src not in d["sources"]:
            d["sources"].append(src)
        return d

    def command(self, path, cls, params, src):
        key = (path, src)
        if key in self.commands:
            # same path in a loop or in both branches of an #if: the last definition wins
            c = self.commands[key]
            c.clear()
            c.update({"path": path, "class": cls, "guidance": [], "params": params, "source": src})
            return c
        c = {"path": path, "class": cls, "guidance": [], "params": params, "source": src}
        self.commands[key] = c
        return c


def main():
    root, out = sys.argv[1], sys.argv[2]
    res = Result()
    srcdir = os.path.join(root, "source")
    for dp, _, files in os.walk(srcdir):
        for fn in sorted(files):
            if fn.endswith((".cc", ".icc", ".hh")):
                p = os.path.join(dp, fn)
                FileParser(p, os.path.relpath(p, root).replace("\\", "/"), res).parse()
    by_path = {c["path"]: c for c in res.commands.values()}
    for c in res.commands.values():
        g = c.pop("copyGuidanceFrom", None)
        if g and g[0] in by_path:
            c["guidance"] += by_path[g[0]]["guidance"][g[1]:]
        p = c.pop("copyParametersFrom", None)
        if p and p in by_path:
            c["params"] = json.loads(json.dumps(by_path[p]["params"]))
    cmds = []
    for c in res.commands.values():
        if not c["path"].startswith("/") and not c["path"].startswith("<"):
            continue
        c["dynamic"] = "<" in c["path"]
        cmds.append(c)
    cmds.sort(key=lambda c: (c["path"].lower(), c["source"]))
    dirs = sorted(res.dirs.values(), key=lambda d: d["path"].lower())
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"directories": dirs, "commands": cmds}, f, indent=1, ensure_ascii=False)
    print(f"{len(cmds)} commands ({sum(c['dynamic'] for c in cmds)} dynamic), {len(dirs)} directories")


if __name__ == "__main__":
    main()
