"""Where each variable of the namespace comes from, and whether it is stale.

Every run of a cell records, from the cell's own syntax tree, the names it
binds and the names it reads as it runs (a function's body only reads when it
is called, so it is left out). A variable is then stale when a name it was
computed from has been bound again since -- re-running the cell that changed
x without the one that computed y = f(x) leaves y describing an x that no
longer exists. The notebook colors such names and says why.

A method that changes its object in place (x.append, x[i] = v, model.fit...)
counts as a new version of that object.
"""

import ast

_MUTATORS = {
    "append", "extend", "insert", "remove", "pop", "clear", "sort", "reverse", "update", "setdefault",
    "add", "discard", "popitem", "fill", "resize", "fit", "partial_fit", "train", "load_state_dict",
    "Fill", "Add", "Scale", "Reset", "SetBinContent", "Sumw2", "Rebin",
}


class _Visitor(ast.NodeVisitor):

    def __init__(self):
        self.bound = set()
        self.reads = set()
        self.mutated = set()
        self.deleted = set()
        self._local = [set()]  # names local to comprehensions being visited

    # what runs only later
    def visit_FunctionDef(self, node):
        self.bound.add(node.name)
        for d in node.decorator_list:
            self.visit(d)
        self._visit_arguments(node.args)
        if node.returns is not None:
            self.visit(node.returns)

    visit_AsyncFunctionDef = visit_FunctionDef

    def _visit_arguments(self, args):
        for default in list(args.defaults) + [d for d in args.kw_defaults if d is not None]:
            self.visit(default)

    def visit_Lambda(self, node):
        self._visit_arguments(node.args)

    def visit_ClassDef(self, node):
        self.bound.add(node.name)
        for e in list(node.bases) + [k.value for k in node.keywords] + list(node.decorator_list):
            self.visit(e)
        # the body runs now, but what it binds belongs to the class
        saved = self.bound
        self.bound = set()
        for stmt in node.body:
            self.visit(stmt)
        self.bound = saved

    def visit_Import(self, node):
        for alias in node.names:
            self.bound.add(alias.asname or alias.name.split(".")[0])

    def visit_ImportFrom(self, node):
        for alias in node.names:
            if alias.name != "*":
                self.bound.add(alias.asname or alias.name)

    def visit_Name(self, node):
        if any(node.id in scope for scope in self._local):
            return
        if isinstance(node.ctx, ast.Store):
            self.bound.add(node.id)
        elif isinstance(node.ctx, ast.Del):
            self.deleted.add(node.id)
        else:
            self.reads.add(node.id)

    def visit_NamedExpr(self, node):
        self.visit(node.value)
        if isinstance(node.target, ast.Name):
            self.bound.add(node.target.id)

    def _comprehension(self, node, elements):
        local = set()
        for gen in node.generators:
            for n in ast.walk(gen.target):
                if isinstance(n, ast.Name):
                    local.add(n.id)
        first = True
        for gen in node.generators:
            if first:
                self.visit(gen.iter)  # the first iterable is read outside the comprehension
                self._local.append(local)
                first = False
            else:
                self.visit(gen.iter)
            for cond in gen.ifs:
                self.visit(cond)
        for e in elements:
            self.visit(e)
        self._local.pop()

    def visit_ListComp(self, node):
        self._comprehension(node, [node.elt])

    visit_SetComp = visit_ListComp
    visit_GeneratorExp = visit_ListComp

    def visit_DictComp(self, node):
        self._comprehension(node, [node.key, node.value])

    def _base(self, target):
        while isinstance(target, (ast.Attribute, ast.Subscript, ast.Starred)):
            target = target.value
        return target.id if isinstance(target, ast.Name) else None

    def visit_Assign(self, node):
        self.visit(node.value)
        for t in node.targets:
            self._target(t)

    def visit_AnnAssign(self, node):
        if node.value is not None:
            self.visit(node.value)
            self._target(node.target)

    def visit_AugAssign(self, node):
        self.visit(node.value)
        base = self._base(node.target)
        if base:
            self.reads.add(base)
            if isinstance(node.target, ast.Name):
                self.bound.add(base)
            else:
                self.mutated.add(base)

    def _target(self, t):
        if isinstance(t, (ast.Tuple, ast.List)):
            for e in t.elts:
                self._target(e)
        elif isinstance(t, ast.Starred):
            self._target(t.value)
        elif isinstance(t, ast.Name):
            self.bound.add(t.id)
        else:
            base = self._base(t)
            if base:
                self.mutated.add(base)
            self.visit(t)  # what the target reads: the object, the index

    def visit_Call(self, node):
        f = node.func
        if isinstance(f, ast.Attribute) and (f.attr in _MUTATORS or f.attr.endswith("_") and not f.attr.startswith("_")):
            base = self._base(f.value)
            if base:
                self.mutated.add(base)
        self.generic_visit(node)


def analyze(statement):
    """(bound, read, mutated, deleted) names of one module-level statement."""
    v = _Visitor()
    v.visit(statement)
    return v.bound, v.reads, v.mutated, v.deleted


class Provenance:

    def __init__(self):
        self.version = {}   # name -> execution count of its last binding or change
        self.deps = {}      # name -> {name read: its version then}
        self.cell = {}      # name -> execution count that bound it

    def record(self, tree, count, namespace):
        """Statement by statement: y = f(x) after x = ... in the same cell reads the new x."""
        for index, statement in enumerate(getattr(tree, "body", [])):
            try:
                bound, reads, mutated, deleted = analyze(statement)
            except Exception:
                continue
            known = {r: self.version.get(r, 0) for r in reads if r in namespace or r in self.version}
            for name in bound:
                self.deps[name] = {d: v for d, v in known.items() if d != name}
            for name in mutated - bound:
                self.deps.setdefault(name, {}).update({d: v for d, v in known.items() if d != name})
            # Versions are fractional within a cell, so a later statement is newer than an earlier one.
            stamp = count + index * 1e-6
            for name in bound | mutated:
                self.version[name] = stamp
                if name in bound:
                    self.cell[name] = count
            for name in deleted:
                self.forget(name)

    def forget(self, name=None):
        if name is None:
            self.version.clear()
            self.deps.clear()
            self.cell.clear()
            return
        self.version.pop(name, None)
        self.deps.pop(name, None)
        self.cell.pop(name, None)

    def why_stale(self, name, _seen=None):
        """Why a variable no longer matches what it was computed from, or None."""
        seen = _seen or {name}
        for dep, version in sorted(self.deps.get(name, {}).items()):
            now = self.version.get(dep)
            if now is not None and now > version:
                return "computed in cell [%s] from '%s', which cell [%s] has changed since" % (
                    self.cell.get(name, "?"), dep, int(now))
            if dep not in seen:
                seen.add(dep)
                inner = self.why_stale(dep, seen)
                if inner:
                    return "computed in cell [%s] from '%s', itself stale (%s)" % (self.cell.get(name, "?"), dep, inner)
        return None

    def stale(self, namespace):
        out = {}
        for name in list(self.deps):
            if name in namespace:
                why = self.why_stale(name)
                if why:
                    out[name] = why
        return out

    def table(self, namespace):
        """Rows for %deps: name, defining cell, what it was computed from, staleness."""
        rows = []
        for name in sorted(self.cell):
            if name not in namespace:
                continue
            rows.append((name, self.cell[name], sorted(self.deps.get(name, {})), self.why_stale(name)))
        return rows
