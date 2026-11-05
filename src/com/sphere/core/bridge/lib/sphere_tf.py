"""sphere_tf -- TensorFlow's models read without TensorFlow: a frozen graph (.pb) or a SavedModel (TF1 or TF2,
its variables restored from their checkpoint) made the portable graph of sphere_graph, which Sphere runs in
numpy, Java and C++.

    import sphere_tf
    g = sphere_tf.load("model.pb")                       # or a SavedModel's folder
    g = sphere_tf.load("saved_model_dir", name="tagger", feeds="particles")

Nothing of TensorFlow is imported, nor of protobuf: the files are decoded from the wire format of the messages
TensorFlow defines (graph.proto, node_def.proto, attr_value.proto, tensor.proto, saved_model.proto,
meta_graph.proto, function.proto, tensor_bundle.proto), the variables' checkpoint from the table TensorFlow
writes it in. A TF2 model's functions are inlined; its variables are bound to their values by following the
restore its saver wrote (RestoreV2 into Assign or AssignVariableOp), in TF1 and TF2 alike.

The graph is then converted by running it: each TensorFlow operation becomes the core ATen operations that
Sphere's three runtimes know, executed at once on two batches of probe inputs (5 and 7 rows), so that every
shape is known as the conversion goes -- the paddings of SAME, the sizes a Reshape is given -- and what the
model computes from its input's shape (Shape, StridedSlice, Pack into a Reshape) stays computed from it: the
graph takes any batch. TensorFlow computes in float32; the graph in float64 from the same float32 weights.
"""

import os
import struct

import numpy as np

import sphere_graph as sg


class TFError(ValueError):
    """What sphere_tf cannot read, or convert."""


# ---------------------------------------------------------------------------
# The protobuf wire format, and TensorFlow's messages in it
# ---------------------------------------------------------------------------

def _varint(b, i):
    shift = result = 0
    while True:
        c = b[i]
        i += 1
        result |= (c & 0x7F) << shift
        if c < 0x80:
            return result, i
        shift += 7


def _int64(v):
    return v - (1 << 64) if v >= 1 << 63 else v


def _fields(b):
    """(field number, wire type, value) of a message: varints as ints, length-delimited fields as bytes, fixed32
    and fixed64 as their raw bytes."""
    i, n = 0, len(b)
    while i < n:
        key, i = _varint(b, i)
        f, wt = key >> 3, key & 7
        if wt == 0:
            v, i = _varint(b, i)
        elif wt == 2:
            length, i = _varint(b, i)
            v = b[i:i + length]
            i += length
        elif wt == 1:
            v = b[i:i + 8]
            i += 8
        elif wt == 5:
            v = b[i:i + 4]
            i += 4
        else:
            raise TFError("protobuf wire type %d, which TensorFlow's messages do not use: not a TensorFlow file" % wt)
        yield f, wt, v


def _varints(wt, v):
    if wt == 0:
        return [_int64(v)]
    out, i = [], 0
    while i < len(v):
        x, i = _varint(v, i)
        out.append(_int64(x))
    return out


def _floats(wt, v, double=False):
    if wt == 2:
        return list(np.frombuffer(bytes(v), "<f8" if double else "<f4").astype(np.float64))
    return [struct.unpack("<d" if double else "<f", bytes(v))[0]]


def _text(v):
    return bytes(v).decode("utf-8", "replace")


class DType(int):
    """A DataType of types.proto."""

    NAMES = {1: "float32", 2: "float64", 3: "int32", 4: "uint8", 5: "int16", 6: "int8", 7: "string", 8: "complex64",
             9: "int64", 10: "bool", 14: "bfloat16", 17: "uint16", 18: "complex128", 19: "float16", 20: "resource",
             21: "variant", 22: "uint32", 23: "uint64"}

    def __repr__(self):
        return self.NAMES.get(int(self) % 100, "dtype%d" % self)

    @property
    def kind(self):
        n = int(self) % 100   # the _REF types are the type + 100
        return "f" if n in (1, 2, 14, 19) else "b" if n == 10 else "s" if n == 7 else "r" if n in (20, 21) else "i"


_NUMPY = {1: "<f4", 2: "<f8", 3: "<i4", 4: "u1", 5: "<i2", 6: "i1", 9: "<i8", 10: "?", 17: "<u2", 19: "<f2", 22: "<u4",
          23: "<u8"}


def _shape(b):
    """TensorShapeProto: its sizes (-1 where unknown), None for an unknown rank."""
    dims, unknown = [], False
    for f, _, v in _fields(b):
        if f == 2:
            size = -1
            for g, _, w in _fields(v):
                if g == 1:
                    size = _int64(w)
            dims.append(size)
        elif f == 3:
            unknown = bool(v)
    return None if unknown else dims


def _array(dtype, shape, raw):
    """A tensor's bytes as numpy, in float64 for numbers (TensorFlow's float32 values exactly)."""
    if dtype % 100 == 14:   # bfloat16: the upper half of a float32
        a = (np.frombuffer(raw, "<u2").astype(np.uint32) << 16).view(np.float32)
    elif dtype % 100 in _NUMPY:
        a = np.frombuffer(raw, _NUMPY[dtype % 100])
    else:
        raise TFError("a tensor of type %r" % DType(dtype))
    return a.reshape(shape).astype(np.float64)


def _tensor(b):
    """TensorProto as numpy (float64), or a list of bytes for strings."""
    dtype, shape, content, vals = 1, [], None, {}
    for f, wt, v in _fields(b):
        if f == 1:
            dtype = v
        elif f == 2:
            shape = _shape(v) or []
        elif f == 4:
            content = bytes(v)
        elif f == 8:
            vals.setdefault(8, []).append(bytes(v))
        elif f in (5, 6):
            vals.setdefault(f, []).extend(_floats(wt, v, double=f == 6))
        elif f in (7, 10, 11, 13, 16, 17):
            vals.setdefault(f, []).extend(_varints(wt, v))
    return _make_tensor(dtype, shape, content, vals)


def _make_tensor(dtype, shape, content, vals):
    """A tensor from its type, shape, raw content or values by their TensorProto field (5 float_val, 6 double_val,
    7 int_val, 8 string_val, 10 int64_val, 11 bool_val, 13 half_val, 16/17 uint32/uint64_val)."""
    n = int(np.prod(shape)) if shape else 1
    if dtype % 100 == 7:
        s = vals.get(8, [])
        return (s + [s[-1]] * (n - len(s)) if s and len(s) < n else s) if n != 1 or shape else (s or [b""])
    if content is not None:
        return _array(dtype, shape, content)
    kind = dtype % 100
    field = {1: 5, 2: 6, 9: 10, 10: 11, 19: 13, 14: 13, 22: 16, 23: 17}.get(kind, 7)
    v = vals.get(field, [])
    if kind == 19:
        v = list(np.array(v, np.uint16).view(np.float16).astype(np.float64))
    elif kind == 14:
        v = list((np.array(v, np.uint32) << 16).view(np.float32).astype(np.float64))
    if len(v) < n:   # TensorFlow repeats the last value to fill the shape (one value: a splat), zeros if none
        v = list(v) + [v[-1] if v else 0.0] * (n - len(v))
    return np.asarray(v[:n], dtype=np.float64).reshape(shape)


class Func(tuple):
    """NameAttrList: a function by its name, and its attributes."""

    @property
    def name(self):
        return self[0]


def _attr(b):
    for f, wt, v in _fields(b):
        if f == 2:
            return bytes(v)
        if f == 3:
            return _int64(v)
        if f == 4:
            return struct.unpack("<f", bytes(v))[0]
        if f == 5:
            return bool(v)
        if f == 6:
            return DType(v)
        if f == 7:
            return ("shape", _shape(v))
        if f == 8:
            return _tensor(v)
        if f == 1:
            return _list(v)
        if f == 10:
            return _func(v)
        if f == 9:
            return ("placeholder", _text(v))
    return None


def _list(b):
    out = []
    for f, wt, v in _fields(b):
        if f == 2:
            out.append(bytes(v))
        elif f in (3, 5):
            out.extend(bool(x) if f == 5 else x for x in _varints(wt, v))
        elif f == 4:
            out.extend(_floats(wt, v))
        elif f == 6:
            out.extend(DType(x) for x in _varints(wt, v))
        elif f == 7:
            out.append(("shape", _shape(v)))
        elif f == 8:
            out.append(_tensor(v))
        elif f == 9:
            out.append(_func(v))
    return out


def _map_entry(b, value):
    key, val = "", None
    for f, _, v in _fields(b):
        if f == 1:
            key = _text(v) if not isinstance(v, int) else v
        elif f == 2:
            val = value(v)
    return key, val


def _func(b):
    name, attrs = "", {}
    for f, _, v in _fields(b):
        if f == 1:
            name = _text(v)
        elif f == 2:
            k, a = _map_entry(v, _attr)
            attrs[k] = a
    return Func((name, attrs))


def _node(b):
    """NodeDef: name, op, inputs, attributes."""
    nd = {"name": "", "op": "", "inputs": [], "attr": {}}
    for f, _, v in _fields(b):
        if f == 1:
            nd["name"] = _text(v)
        elif f == 2:
            nd["op"] = _text(v)
        elif f == 3:
            nd["inputs"].append(_text(v))
        elif f == 5:
            k, a = _map_entry(v, _attr)
            nd["attr"][k] = a
    return nd


def _argdef(b):
    a = {"name": "", "type": 0, "type_attr": "", "number_attr": "", "type_list_attr": ""}
    for f, _, v in _fields(b):
        if f == 1:
            a["name"] = _text(v)
        elif f == 3:
            a["type"] = v
        elif f == 4:
            a["type_attr"] = _text(v)
        elif f == 5:
            a["number_attr"] = _text(v)
        elif f == 6:
            a["type_list_attr"] = _text(v)
    return a


def _opdef(b):
    """OpDef: its name, its inputs' and outputs' arguments, its attributes' defaults."""
    op = {"name": "", "inputs": [], "outputs": [], "defaults": {}}
    for f, _, v in _fields(b):
        if f == 1:
            op["name"] = _text(v)
        elif f == 2:
            op["inputs"].append(_argdef(v))
        elif f == 3:
            op["outputs"].append(_argdef(v))
        elif f == 4:
            name, default = "", None
            for g, _, w in _fields(v):
                if g == 1:
                    name = _text(w)
                elif g == 3:
                    default = _attr(w)
            if default is not None:
                op["defaults"][name] = default
    return op


def _function(b):
    fn = {"signature": None, "nodes": [], "ret": {}}
    for f, _, v in _fields(b):
        if f == 1:
            fn["signature"] = _opdef(v)
        elif f == 3:
            fn["nodes"].append(_node(v))
        elif f == 4:
            k, r = _map_entry(v, _text)
            fn["ret"][k] = r
    return fn


def _graphdef(b):
    """GraphDef: its nodes and its function library."""
    nodes, library = [], {}
    for f, _, v in _fields(b):
        if f == 1:
            nodes.append(_node(v))
        elif f == 2:
            for g, _, w in _fields(v):
                if g == 1:
                    fn = _function(w)
                    library[fn["signature"]["name"]] = fn
    return nodes, library


def _tensorinfo(b):
    info = {"name": "", "dtype": 0, "shape": None}
    for f, _, v in _fields(b):
        if f == 1:
            info["name"] = _text(v)
        elif f == 2:
            info["dtype"] = DType(v)
        elif f == 3:
            info["shape"] = _shape(v)
    return info


def _signature(b):
    sig = {"inputs": {}, "outputs": {}, "method": ""}
    for f, _, v in _fields(b):
        if f in (1, 2):
            k, info = _map_entry(v, _tensorinfo)
            sig["inputs" if f == 1 else "outputs"][k] = info
        elif f == 3:
            sig["method"] = _text(v)
    return sig


def _metagraph(b):
    mg = {"tags": [], "ops": {}, "graph": ([], {}), "restore": "", "signatures": {}}
    for f, _, v in _fields(b):
        if f == 1:
            for g, _, w in _fields(v):
                if g == 4:
                    mg["tags"].append(_text(w))
                elif g == 2:
                    for h, _, u in _fields(w):
                        if h == 1:
                            op = _opdef(u)
                            mg["ops"][op["name"]] = op
        elif f == 2:
            mg["graph"] = _graphdef(v)
        elif f == 3:
            for g, _, w in _fields(v):
                if g == 3:
                    mg["restore"] = _text(w)
        elif f == 5:
            k, sig = _map_entry(v, _signature)
            mg["signatures"][k] = sig
    return mg


# ---------------------------------------------------------------------------
# The text format of protobuf (.pbtxt): the same messages, written out
# ---------------------------------------------------------------------------

_DT_TEXT = {"DT_INVALID": 0, "DT_FLOAT": 1, "DT_DOUBLE": 2, "DT_INT32": 3, "DT_UINT8": 4, "DT_INT16": 5, "DT_INT8": 6,
            "DT_STRING": 7, "DT_COMPLEX64": 8, "DT_INT64": 9, "DT_BOOL": 10, "DT_QINT8": 11, "DT_QUINT8": 12,
            "DT_QINT32": 13, "DT_BFLOAT16": 14, "DT_QINT16": 15, "DT_QUINT16": 16, "DT_UINT16": 17, "DT_COMPLEX128": 18,
            "DT_HALF": 19, "DT_RESOURCE": 20, "DT_VARIANT": 21, "DT_UINT32": 22, "DT_UINT64": 23}


def _pt_tokens(text):
    import re
    out = []
    for m in re.finditer(r'\s+|#[^\n]*|("(?:[^"\\\n]|\\.)*"|\'(?:[^\'\\\n]|\\.)*\')|([{}<>\[\]:,;])|([^\s{}<>\[\]:,;"\'#]+)', text):
        if m.group(1):
            out.append(("s", _pt_unescape(m.group(1)[1:-1])))
        elif m.group(2):
            out.append(("p", m.group(2)))
        elif m.group(3):
            out.append(("a", m.group(3)))
    return out


def _pt_unescape(s):
    """A text-format string's bytes: C's escapes, octal and hexadecimal ones (tensor_content is written so)."""
    out, i = bytearray(), 0
    simple = {"n": b"\n", "t": b"\t", "r": b"\r", "a": b"\a", "b": b"\b", "f": b"\f", "v": b"\v", "\\": b"\\", "'": b"'",
              '"': b'"', "?": b"?"}
    while i < len(s):
        if s[i] != "\\":
            out += s[i].encode("utf-8")
            i += 1
            continue
        n = s[i + 1]
        if n in "01234567":
            j = i + 1
            while j < len(s) and j < i + 4 and s[j] in "01234567":
                j += 1
            out.append(int(s[i + 1:j], 8) & 0xFF)
            i = j
        elif n in "xX":
            j = i + 2
            while j < len(s) and j < i + 4 and s[j] in "0123456789abcdefABCDEF":
                j += 1
            out.append(int(s[i + 2:j], 16))
            i = j
        else:
            out += simple.get(n, n.encode("utf-8"))
            i += 2
    return bytes(out)


def _pt_message(tok, i):
    """A message's fields, each a list of its values: bytes for strings, str for numbers and enums, dicts for messages."""
    msg = {}
    while i < len(tok):
        kind, val = tok[i]
        if kind == "p" and val in "}>":
            return msg, i + 1
        if kind != "a":
            raise TFError("a text protobuf: a field's name expected, %r found" % (val,))
        name, i = val, i + 1
        if i < len(tok) and tok[i] == ("p", ":"):
            i += 1
        if tok[i] == ("p", "["):
            i += 1
            while tok[i] != ("p", "]"):
                v, i = _pt_value(tok, i)
                msg.setdefault(name, []).append(v)
                if tok[i] == ("p", ","):
                    i += 1
            i += 1
        else:
            v, i = _pt_value(tok, i)
            msg.setdefault(name, []).append(v)
        if i < len(tok) and tok[i][0] == "p" and tok[i][1] in ",;":
            i += 1
    return msg, i


def _pt_value(tok, i):
    kind, val = tok[i]
    if kind == "p" and val in "{<":
        return _pt_message(tok, i + 1)
    if kind == "s":   # adjacent strings are one
        v, i = val, i + 1
        while i < len(tok) and tok[i][0] == "s":
            v, i = v + tok[i][1], i + 1
        return v, i
    return val, i + 1


def _pt_first(msg, key, default=None):
    v = msg.get(key)
    return v[0] if v else default


def _pt_text(v):
    return v.decode("utf-8", "replace") if isinstance(v, bytes) else str(v)


def _pt_int(a):
    a = _pt_text(a)
    return int(a, 0) if not any(ch in a for ch in ".eEnN") or a.lower().startswith("0x") else int(float(a))


def _pt_float(a):
    a = _pt_text(a).lower().rstrip("f") if not _pt_text(a).lower().startswith("inf") else _pt_text(a).lower()
    return float({"infinity": "inf", "-infinity": "-inf"}.get(a, a))


def _pt_bool(a):
    return _pt_text(a) in ("true", "True", "t", "1")


def _pt_dtype(a):
    a = _pt_text(a)
    if a.startswith("DT_"):
        ref = a.endswith("_REF")
        return DType(_DT_TEXT[a[:-4] if ref else a] + (100 if ref else 0))
    return DType(int(a))


def _pt_shape(msg):
    if _pt_bool(_pt_first(msg, "unknown_rank", "false")):
        return None
    return [_pt_int(_pt_first(d, "size", "0")) for d in msg.get("dim", [])]


def _pt_tensor(msg):
    dtype = _pt_dtype(_pt_first(msg, "dtype", "DT_FLOAT"))
    shape = _pt_shape(_pt_first(msg, "tensor_shape", {})) or []
    vals = {}
    for key, field in (("float_val", 5), ("double_val", 6), ("int_val", 7), ("int64_val", 10), ("bool_val", 11),
                       ("half_val", 13), ("uint32_val", 16), ("uint64_val", 17)):
        if key in msg:
            vals[field] = [_pt_float(x) if field in (5, 6) else int(_pt_bool(x)) if _pt_text(x) in ("true", "false")
                           else _pt_int(x) for x in msg[key]]
    if "string_val" in msg:
        vals[8] = list(msg["string_val"])
    return _make_tensor(dtype, shape, _pt_first(msg, "tensor_content"), vals)


def _pt_func(msg):
    return Func((_pt_text(_pt_first(msg, "name", b"")),
                 {_pt_text(_pt_first(e, "key", b"")): _pt_attr(_pt_first(e, "value", {})) for e in msg.get("attr", [])}))


def _pt_attr(msg):
    for key, vals in msg.items():
        v = vals[0]
        if key == "s":
            return v
        if key == "i":
            return _pt_int(v)
        if key == "f":
            return _pt_float(v)
        if key == "b":
            return _pt_bool(v)
        if key == "type":
            return _pt_dtype(v)
        if key == "shape":
            return ("shape", _pt_shape(v))
        if key == "tensor":
            return _pt_tensor(v)
        if key == "func":
            return _pt_func(v)
        if key == "placeholder":
            return ("placeholder", _pt_text(v))
        if key == "list":
            conv = {"s": lambda x: x, "i": _pt_int, "f": _pt_float, "b": _pt_bool, "type": _pt_dtype,
                    "shape": lambda x: ("shape", _pt_shape(x)), "tensor": _pt_tensor, "func": _pt_func}
            return [conv[k](x) for k, xs in v.items() if k in conv for x in xs]
    return None


def _pt_node(msg):
    return {"name": _pt_text(_pt_first(msg, "name", b"")), "op": _pt_text(_pt_first(msg, "op", b"")),
            "inputs": [_pt_text(x) for x in msg.get("input", [])],
            "attr": {_pt_text(_pt_first(e, "key", b"")): _pt_attr(_pt_first(e, "value", {})) for e in msg.get("attr", [])}}


def _pt_opdef(msg):
    def arg(a):
        return {"name": _pt_text(_pt_first(a, "name", b"")), "type": int(_pt_dtype(_pt_first(a, "type", "0"))),
                "type_attr": _pt_text(_pt_first(a, "type_attr", b"")), "number_attr": _pt_text(_pt_first(a, "number_attr", b"")),
                "type_list_attr": _pt_text(_pt_first(a, "type_list_attr", b""))}
    return {"name": _pt_text(_pt_first(msg, "name", b"")), "inputs": [arg(a) for a in msg.get("input_arg", [])],
            "outputs": [arg(a) for a in msg.get("output_arg", [])], "defaults": {}}


def _pbtxt(text):
    """A GraphDef in protobuf's text format: its nodes and its function library, as _graphdef gives them."""
    msg, _ = _pt_message(_pt_tokens(text), 0)
    nodes = [_pt_node(n) for n in msg.get("node", [])]
    library = {}
    for lib in msg.get("library", []):
        for f in lib.get("function", []):
            fn = {"signature": _pt_opdef(_pt_first(f, "signature", {})), "nodes": [_pt_node(n) for n in f.get("node_def", [])],
                  "ret": {_pt_text(_pt_first(e, "key", b"")): _pt_text(_pt_first(e, "value", b"")) for e in f.get("ret", [])}}
            library[fn["signature"]["name"]] = fn
    return nodes, library


# ---------------------------------------------------------------------------
# Variables: the checkpoint's table (TensorBundle, LevelDB's table format, uncompressed)
# ---------------------------------------------------------------------------

_TABLE_MAGIC = 0xdb4775248b80fb57


def _block(data, offset, size):
    if data[offset + size] != 0:
        raise TFError("a compressed checkpoint table (TensorFlow writes them uncompressed)")
    return data[offset:offset + size]


def _entries(block):
    """The (key, value) pairs of a table block: keys prefix-compressed, the restart points at its end."""
    restarts = struct.unpack("<I", block[-4:])[0]
    end = len(block) - 4 * (restarts + 1)
    i, key = 0, b""
    while i < end:
        shared, i = _varint(block, i)
        own, i = _varint(block, i)
        size, i = _varint(block, i)
        key = key[:shared] + bytes(block[i:i + own])
        i += own
        yield key, block[i:i + size]
        i += size


def bundle(prefix):
    """The tensors of a checkpoint (prefix.index and its data shards), by name, as numpy (float64)."""
    with open(prefix + ".index", "rb") as fh:
        index = fh.read()
    footer = index[-48:]
    if struct.unpack("<Q", footer[40:])[0] != _TABLE_MAGIC:
        raise TFError(prefix + ".index is not a TensorFlow checkpoint table")
    i = 0
    for _ in range(2):   # the metaindex's handle, then the index's
        offset, i = _varint(footer, i)
        size, i = _varint(footer, i)
    entries = {}
    for _, handle in _entries(_block(index, offset, size)):
        o, j = _varint(handle, 0)
        s, _ = _varint(handle, j)
        entries.update(_entries(_block(index, o, s)))
    shards = 1
    for f, _, v in _fields(entries.pop(b"", b"")):
        if f == 1:
            shards = v
    out, files = {}, {}
    for key, value in entries.items():
        e = {"dtype": 1, "shape": [], "shard": 0, "offset": 0, "size": 0, "slices": False}
        for f, _, v in _fields(value):
            if f == 1:
                e["dtype"] = v
            elif f == 2:
                e["shape"] = _shape(v) or []
            elif f == 3:
                e["shard"] = v
            elif f == 4:
                e["offset"] = v
            elif f == 5:
                e["size"] = v
            elif f == 7:
                e["slices"] = True
        name = key.decode("utf-8", "replace")
        if e["slices"] or e["dtype"] % 100 not in _NUMPY and e["dtype"] % 100 != 14:
            continue   # partitioned variables and strings: not a network's weights
        if e["shard"] not in files:
            with open("%s.data-%05d-of-%05d" % (prefix, e["shard"], shards), "rb") as fh:
                files[e["shard"]] = fh.read()
        raw = files[e["shard"]][e["offset"]:e["offset"] + e["size"]]
        out[name] = _array(e["dtype"], e["shape"], raw)
    return out


# ---------------------------------------------------------------------------
# The model made flat: functions inlined, variables bound
# ---------------------------------------------------------------------------

_CALLS = ("PartitionedCall", "StatefulPartitionedCall")
_IDENTITY = ("Identity", "IdentityN", "StopGradient", "PreventGradient", "Snapshot", "CheckNumerics", "EnsureShape",
             "PlaceholderWithDefault", "_Retval")
_VARIABLES = ("VariableV2", "Variable", "VarHandleOp", "VarIsInitializedOp")
_NOWHERE = frozenset()   # the condition of what runs whatever the predicates
_OUTPUTS = {   # the output arguments of TensorFlow's multi-output operations, where no OpDef comes with the graph
    "FusedBatchNorm": ["y", "batch_mean", "batch_variance", "reserve_space_1", "reserve_space_2"],
    "FusedBatchNormV2": ["y", "batch_mean", "batch_variance", "reserve_space_1", "reserve_space_2"],
    "FusedBatchNormV3": ["y", "batch_mean", "batch_variance", "reserve_space_1", "reserve_space_2", "reserve_space_3"],
    "TopKV2": ["values", "indices"], "Unique": ["y", "idx"],
}


def _ref(s):
    """An input of a NodeDef: (node, output index), None for a control input."""
    if s.startswith("^"):
        return None
    name, _, idx = s.partition(":")
    return name, int(idx) if idx else 0


class _Flat:
    """The nodes of a graph, its function calls inlined (their outputs aliases of what the functions return)."""

    def __init__(self, nodes, library, opdefs):
        self.nodes, self.alias, self.library, self.opdefs = {}, {}, library, opdefs
        self._ctx = None
        self.inner = set()   # the nodes inside TF1 loops' frames (functionalize)
        for nd in nodes:
            self.add(nd)

    def add(self, nd):
        fname = nd["attr"]["f"].name if nd["op"] in _CALLS and isinstance(nd["attr"].get("f"), Func) else \
            nd["op"] if nd["op"] in self.library else None
        if nd["op"] == "TRTEngineOp" and isinstance(nd["attr"].get("segment_func"), Func):
            fname = nd["attr"]["segment_func"].name   # a TensorRT engine: the native segment TF-TRT keeps beside it
        if fname is not None and fname not in self.library and nd["op"] == "TRTEngineOp":
            fname = None
        if fname is None:   # control inputs (^name) kept apart: what places a TF1 branch's constants in it
            nd = dict(nd, inputs=[r for r in (_ref(s) for s in nd["inputs"]) if r is not None],
                      control=[s[1:] for s in nd["inputs"] if s.startswith("^")])
            self.nodes[nd["name"]] = nd
            return
        if fname not in self.library:
            raise TFError("node %s calls a function %s the graph's library has not" % (nd["name"], fname))
        fn, prefix = self.library[fname], nd["name"] + "/"
        args = [r for r in (_ref(s) for s in nd["inputs"]) if r is not None]
        names = [a["name"] for a in fn["signature"]["inputs"]]
        bound = dict(zip(names, args))
        local = {b["name"]: b for b in fn["nodes"]}

        def resolve(s):
            if s.startswith("^"):
                return None
            parts = s.split(":")
            if len(parts) == 1:
                return bound[parts[0]] if parts[0] in bound else (prefix + parts[0], 0)
            if len(parts) == 2:
                return prefix + parts[0], int(parts[1])
            body = local.get(parts[0])
            return prefix + parts[0], self.index(body, parts[1], int(parts[2])) if body else int(parts[2])

        for b in fn["nodes"]:
            self.add(dict(b, name=prefix + b["name"], inputs=["%s:%d" % r for r in (resolve(s) for s in b["inputs"])
                                                              if r is not None] + [
                "^" + prefix + s[1:] for s in b["inputs"] if s.startswith("^") and s[1:] in local]))
        for k, out in enumerate(fn["signature"]["outputs"]):
            self.alias[(nd["name"], k)] = resolve(fn["ret"][out["name"]])

    def index(self, nd, arg, idx):
        """The flat output index of output argument arg's idx-th tensor, by the op's output arguments."""
        op = self.opdefs.get(nd["op"]) or (self.library[nd["op"]]["signature"] if nd["op"] in self.library else None)
        if op is None:
            names = _OUTPUTS.get(nd["op"])
            return names.index(arg) + idx if names and arg in names else idx
        at = 0
        for out in op["outputs"]:
            if out["name"] == arg:
                return at + idx
            at += int(nd["attr"].get(out["number_attr"], 1)) if out["number_attr"] else \
                len(nd["attr"].get(out["type_list_attr"], [])) if out["type_list_attr"] else 1
        return idx

    def functionalize(self):
        """TF1's loops -- frames of Enter, Merge, Switch, LoopCond, NextIteration, Exit -- made functional, as
        TensorFlow's own FunctionalizeControlFlow does: a node __FrameWhile a frame, its inputs the loop values'
        first values then the frame's invariants, its Exits aliases of its outputs. The number of frames."""
        frames = {}
        for n, nd in self.nodes.items():
            if nd["op"] in ("Enter", "RefEnter"):
                f = nd["attr"].get("frame_name", b"")
                frames.setdefault(f.decode() if isinstance(f, bytes) else str(f), []).append(n)
        users, followers = {}, {}
        for n, nd in self.nodes.items():
            for i, r in enumerate(nd["inputs"]):
                users.setdefault(r[0], []).append((n, i))
            for c in nd.get("control", ()):
                followers.setdefault(c, []).append(n)

        def user(n, ops, where=None):
            return next((u for u, i in users.get(n, []) if self.nodes[u]["op"] in ops and (where is None or where(u, i))), None)

        for frame, enters in frames.items():
            merges, inits, switches, nexts, exits, invariants, kept = [], [], [], [], [], [], []
            pred = None
            for e in enters:
                ent = self.nodes[e]
                if ent["attr"].get("is_constant"):
                    invariants.append(e)
                    kept.append(ent["inputs"][0])
                    continue
                m = user(e, ("Merge", "RefMerge"))
                if m is None:
                    continue
                nx = next((r for r in self.nodes[m]["inputs"] if self.nodes.get(r[0], {}).get("op") in
                           ("NextIteration", "RefNextIteration")), None)
                sw = user(m, ("Switch", "RefSwitch"), lambda u, i: i == 0)
                if nx is None or sw is None:
                    raise TFError("TF1 loop %s: %s has no NextIteration or Switch" % (frame, m))
                lc = self.nodes.get(self.nodes[sw]["inputs"][1][0], {})
                if lc.get("op") != "LoopCond":
                    raise TFError("TF1 loop %s: the Switch %s is not on a LoopCond" % (frame, sw))
                pred = lc["inputs"][0]
                merges.append(m)
                inits.append(ent["inputs"][0])
                switches.append(sw)
                nexts.append(self.nodes[nx[0]]["inputs"][0])
                exits.append(user(sw, ("Exit", "RefExit"), lambda u, i: self.nodes[u]["inputs"][0] == (sw, 0)))
            if not merges:
                continue
            name = "@frame/" + frame
            self.nodes[name] = {"name": name, "op": "__FrameWhile", "inputs": inits + kept,
                                "attr": {"frame": frame, "merges": merges, "switches": switches, "nexts": nexts, "pred": pred,
                                         "invariants": invariants}}
            for k, ex in enumerate(exits):
                if ex is not None:
                    self.alias[(ex, 0)] = (name, k)
            # what lies inside the frame: all its Enters lead to, up to its Exits
            stack, stop = list(enters), set(e for e in exits if e is not None)
            while stack:
                n = stack.pop()
                if n in self.inner or n in stop:
                    continue
                self.inner.add(n)
                stack.extend(u for u, _ in users.get(n, []))
                stack.extend(followers.get(n, []))
        return len(frames)

    def unalias(self, r):
        seen = 0
        while r in self.alias and seen < 10000:
            r, seen = self.alias[r], seen + 1
        return r

    def node_ctx(self, n):
        """The TF1 condition a node runs under, as TensorFlow's executor decides it: the (predicate, side) of
        each Switch it lies behind, a frozenset, the predicates tensors through identities."""
        if self._ctx is None:
            self._ctx = {}
            self._conditions(self._ctx)
        return self._ctx.get(n, _NOWHERE)

    def out_ctx(self, r):
        """The condition a tensor is computed under: its node's, and a Switch's output also on its own side."""
        r = self.unalias(r)
        c = self.node_ctx(r[0])
        nd = self.nodes.get(r[0])
        if nd is not None and nd["op"] in ("Switch", "RefSwitch") and len(nd["inputs"]) > 1:
            p = self.predicate(nd)
            if p is not None:
                c = c | {(p, r[1] == 1)}
        return c

    def predicate(self, nd):
        """A Switch's predicate, through identities; None for a loop's (on LoopCond), which no branch has."""
        p = self.follow(nd["inputs"][1])
        return None if self.nodes.get(p[0], {}).get("op") == "LoopCond" else p

    def _conditions(self, ctx):
        """Every node's condition into ctx, depth first without recursion: a Switch's outputs add their side, a
        Merge runs under what its inputs share (a condition's two sides: what is outside it), any other node
        under all its inputs' together, control inputs too. Loops' back edges (NextIteration into Merge) are
        left out."""
        busy = set()

        def inputs(nd):
            ins = nd["inputs"]
            if nd["op"] in ("Merge", "RefMerge"):
                ins = [r for r in ins if self.nodes.get(self.unalias(r)[0], {}).get("op") not in
                       ("NextIteration", "RefNextIteration")]
            return [self.unalias(r) for r in ins], nd.get("control", ())

        for start in self.nodes:
            stack = [(start, False)]
            while stack:
                n, ready = stack.pop()
                if n in ctx:
                    continue
                nd = self.nodes.get(n)
                if nd is None:
                    ctx[n] = _NOWHERE
                    continue
                ins, control = inputs(nd)
                if not ready:
                    if n in busy:   # a cycle that is not a loop's: nothing to add through it
                        continue
                    busy.add(n)
                    stack.append((n, True))
                    stack.extend((m, False) for m in [r[0] for r in ins] + list(control) if m not in ctx and m not in busy)
                    continue
                busy.discard(n)
                outs = [self.out_ctx(r) for r in ins]
                more = [ctx.get(m, _NOWHERE) for m in control]
                if nd["op"] in ("Merge", "RefMerge") and outs:
                    ctx[n] = frozenset.intersection(*outs).union(*more)
                else:
                    ctx[n] = _NOWHERE.union(*outs, *more)

    def follow(self, r):
        """A tensor through the aliases of inlined calls and through identities, to what computes it."""
        seen = 0
        while seen < 10000:
            seen += 1
            if r in self.alias:
                r = self.alias[r]
                continue
            nd = self.nodes.get(r[0])
            if nd is not None and nd["op"] in _IDENTITY and nd["op"] != "PlaceholderWithDefault" and nd["inputs"]:
                r = nd["inputs"][r[1] if nd["op"] == "IdentityN" else 0]
                continue
            return r
        raise TFError("a cycle of identities at " + str(r))

    def variables(self, values):
        """Each variable's value: the restore the saver wrote read backward -- RestoreV2's tensor_names into
        Assign or AssignVariableOp of the variable -- and the checkpoint's values (values) by those names."""
        keys = {}
        for nd in self.nodes.values():
            if nd["op"] in ("RestoreV2", "Restore") and len(nd["inputs"]) > 1:
                names = self.nodes.get(self.follow(nd["inputs"][1])[0])
                if names is not None and names["op"] == "Const":
                    for i, k in enumerate(names["attr"].get("value") or []):
                        keys[(nd["name"], i)] = k.decode("utf-8", "replace") if isinstance(k, bytes) else str(k)
        bound = {}
        for nd in self.nodes.values():
            if nd["op"] in ("Assign", "AssignVariableOp") and len(nd["inputs"]) > 1:
                var, val = self.follow(nd["inputs"][0]), self.follow(nd["inputs"][1])
                if val in keys and keys[val] in values:
                    bound[var[0]] = values[keys[val]]
        return bound


# ---------------------------------------------------------------------------
# The conversion: TensorFlow's operations as core ATen's, run on probe inputs as they are made
# ---------------------------------------------------------------------------

class Ref(str):
    """A value of the graph being built, by its name."""


DEAD = ("dead",)   # the output of a TF1 Switch on the side its (fixed) predicate does not take


class _Builder:
    """The graph being built, and its values on each probe's inputs: every node run as soon as it is made. A block's
    builder (child) shares the weights and the blocks, its names its own (prefix)."""

    def __init__(self, probes, consts=None, blocks=None, prefix=""):
        import collections
        self.probes, self.prefix = probes, prefix
        self.nodes, self.inputs, self.names = [], [], []
        self.consts = {} if consts is None else consts
        self.blocks = {} if blocks is None else blocks
        self.env = [collections.ChainMap({}, self.consts) for _ in range(probes)]
        self.ctx = {"consts": self.consts, "blocks": self.blocks}
        self.fixed = set()    # the values computed from weights alone
        self.static = set()   # the values that do not depend on the inputs' values (weights, shapes, sizes)

    def input(self, name, arrays):
        r = Ref("%sx%d" % (self.prefix, len(self.inputs)))
        self.inputs.append(r)
        self.names.append(name)
        for p, a in enumerate(arrays):
            self.env[p][r] = a
        return r

    def const(self, value):
        r = Ref("c%d" % len(self.consts))
        self.consts[r] = np.asarray(value, dtype=np.float64)
        self.fixed.add(r)
        self.static.add(r)
        return r

    def child(self, tag, arrays):
        """A block's builder, its inputs those values (one list a probe for each input)."""
        c = _Builder(self.probes, self.consts, self.blocks, prefix=tag + ".")
        return c, [c.input("in%d" % k, a) for k, a in enumerate(arrays)]

    def raw(self, aten, enc):
        """A node of encoded arguments, run on every probe: control operations run their blocks."""
        r = Ref("%sv%d" % (self.prefix, len(self.nodes)))
        self.nodes.append({"name": r, "op": aten, "enc": enc, "dtype": ""})
        for env in self.env:
            env[r] = sg._apply(self.ctx, aten, sg._decode(enc, env))
        refs = sg._refs(enc)
        if refs and all(x in self.fixed for x in refs):
            self.fixed.add(r)
        if aten in ("sym_size.int", "sym_numel.default") or all(x in self.static for x in refs):
            self.static.add(r)
        return r

    def inline(self, name, args):
        """A block's nodes put here, its inputs these values: its outputs' values."""
        blk = self.blocks[name]
        m = dict(zip(blk["inputs"], args))

        def remap(t):
            if t.startswith("n:"):
                return "n:" + m[t[2:]] if t[2:] in m else t
            if t.startswith(("L:", "T:")) and len(t) > 2:
                return t[:2] + ",".join(remap(e) if t[0] == "L" else (m.get(e, e)) for e in t[2:].split(","))
            return t

        for nd in blk["nodes"]:
            m[nd["name"]] = self.raw(nd["op"], [remap(t) for t in nd["enc"]])
        return [m[o] if o in m else Ref(o) for o in blk["outputs"]]

    def run_block(self, name, values):
        return sg._run_block(self.ctx, name, values)

    def enc(self, a):
        if isinstance(a, Ref):
            return "n:" + a
        if a is None:
            return "none"
        if isinstance(a, (bool, np.bool_)):
            return "b:%d" % a
        if isinstance(a, (int, np.integer)):
            return "i:%d" % a
        if isinstance(a, (float, np.floating)):
            return "f:" + repr(float(a))
        if isinstance(a, str):
            return "s:" + a
        if isinstance(a, (list, tuple)):
            return "L:" + ",".join(self.enc(e) for e in a)
        raise TFError("an argument %r" % (a,))

    def op(self, aten, *args):
        if aten not in sg.OPS and aten not in sg.CONTROL:
            raise TFError("no ATen operation " + aten)
        return self.raw(aten, [self.enc(a) for a in args])

    def value(self, r, p=0):
        return self.env[p][r]

    def literal(self, r):
        """A value the same on every probe, as Python numbers (a list for a tensor), else None."""
        vals = [np.asarray(env[r]) for env in self.env]
        if all(v.shape == vals[0].shape and np.array_equal(v, vals[0]) for v in vals[1:]):
            v = vals[0]
            return v.tolist() if v.ndim else v.item()
        return None

    def ints(self, r, what):
        v = self.literal(r)
        if v is None:
            raise TFError("%s changes with the batch: Sphere needs it fixed" % what)
        return [int(x) for x in (v if isinstance(v, list) else [v])]

    def sizes(self, r):
        """A 1-d tensor of sizes as an argument: each size a number where fixed, computed where the batch moves it."""
        vals = [np.asarray(env[r]).reshape(-1) for env in self.env]
        out = []
        for i in range(vals[0].size):
            same = all(v[i] == vals[0][i] for v in vals[1:])
            out.append(int(vals[0][i]) if same else self.op("select.int", r, 0, i))
        return out

    def shape(self, r, p=0):
        return list(np.shape(self.env[p][r]))


def _probe_batches(inputs):
    return [5, 7] if any(s and s[0] in (-1, None) for s in inputs) else [None]


UNROLL_TRIPS = 4096      # a loop of fixed iterations is unrolled up to this many of them...
UNROLL_NODES = 20000     # ...and this many nodes put in its place; past them, it stays a loop
_COUNTER = iter(range(1 << 62))


def _tag(kind):
    return "%s%d" % (kind, next(_COUNTER))


def _deps(blk):
    """For each output of a block, the inputs whose values it depends on."""
    d = {name: {k} for k, name in enumerate(blk["inputs"])}
    for nd in blk["nodes"]:
        s = set()
        for r in sg._refs(nd["enc"]):
            s |= d.get(r, set())
        d[nd["name"]] = s
    return [d.get(o, set()) for o in blk["outputs"]]


class _Converter:
    def __init__(self, flat, values, b):
        self.flat, self.values, self.b = flat, values, b
        self.out = {}          # (node, index) -> Ref, or ("var", name) for a resource
        self.done = set()      # the nodes converted (an operation without outputs among them)
        self.missing = {}
        self.zeros = False     # variables the checkpoint lacks as zeros (missing="zeros"), else an error
        self.cond = {}         # a TF1 predicate (tensor) -> True/False where fixed here, None where the data move it
        self.plans = {}        # a Merge -> what it takes (merge_plan)
        self.notes = []        # what was made of the model's control flow

    def function_block(self, fname, ins, tag):
        """A TensorFlow function as a block of its tensor arguments (resources bound as they are): the block's
        name and the function's outputs, in the block's names."""
        fn = self.flat.library.get(fname)
        if fn is None:
            raise TFError("a function %s the graph's library has not" % fname)
        tensors = [k for k, v in enumerate(ins) if isinstance(v, Ref)]
        child, refs = self.b.child(tag, [[self.b.value(ins[k], p) for p in range(self.b.probes)] for k in tensors])
        sub = _Flat([], self.flat.library, self.flat.opdefs)
        for k in range(len(ins)):
            sub.nodes["@a%d" % k] = {"name": "@a%d" % k, "op": "Placeholder", "inputs": [], "attr": {}}
        sub.add({"name": "@f", "op": fname, "inputs": ["@a%d" % k for k in range(len(ins))], "attr": {}})
        conv = _Converter(sub, self.values, child)
        conv.zeros, conv.notes, conv.cond = self.zeros, self.notes, dict(self.cond)
        for k, v in enumerate(ins):
            conv.out[("@a%d" % k, 0)] = refs[tensors.index(k)] if k in tensors else v
        outs = [conv.get(("@f", k)) for k in range(len(fn["signature"]["outputs"]))]
        name = "@" + tag
        self.b.blocks[name] = {"inputs": list(child.inputs), "nodes": child.nodes,
                               "outputs": [str(o) for o in outs if isinstance(o, Ref)]}
        return name, outs

    def cut_block(self, tag, binds, values, outputs, given=None, assume=None, branch=False):
        """A block of this graph's own nodes between cuts -- a TF1 loop's condition or body, a TF1 condition's
        branch: binds maps the tensors where it starts to its inputs (positions in values), given to what they
        are without being inputs (weights, variables), its outputs those tensors. assume: the predicates the
        block runs under, by their truth there. A branch's inputs keep the values' independence of the data
        (a loop's change from one iteration to the next)."""
        child, refs = self.b.child(tag, [[self.b.value(v, p) for p in range(self.b.probes)] for v in values])
        if branch:
            child.static.update(r for r, v in zip(refs, values) if v in self.b.static)
        conv = _Converter(self.flat, self.values, child)
        conv.zeros, conv.notes, conv.cond = self.zeros, self.notes, dict(self.cond)
        conv.cond.update(assume or {})
        for r, k in binds.items():
            conv.out[r] = refs[k]
        conv.out.update(given or {})
        outs = [conv.get(r) for r in outputs]
        if not all(isinstance(o, Ref) for o in outs):
            raise TFError("block %s gives what is not a tensor" % tag)
        name = "@" + tag
        self.b.blocks[name] = {"inputs": list(child.inputs), "nodes": child.nodes, "outputs": [str(o) for o in outs]}
        return name

    def truth(self, p):
        """A TF1 predicate's truth where fixed -- by the weights and the shapes, or by the branch this block is --
        else None: the data move it."""
        if p not in self.cond:
            v = self.get(p)
            lit = np.asarray(self.b.literal(v)).reshape(-1) if isinstance(v, Ref) and v in self.b.static else None
            self.cond[p] = bool(lit[0]) if lit is not None and lit.size == 1 else None
        return self.cond[p]

    def dead(self, ctx):
        """Whether a condition never holds here: a side of a Switch its fixed predicate does not take, or both."""
        side = {}
        for p, s in ctx:
            if side.setdefault(p, s) != s:
                return True
        return any(self.truth(p) not in (None, s) for p, s in ctx)

    def predicates(self, nd):
        """The predicates whether nd runs depends on, not yet known here; a Merge's, those of all its inputs."""
        ctx = set(self.flat.node_ctx(nd["name"]))
        if nd["op"] in ("Merge", "RefMerge"):
            for r in nd["inputs"]:
                ctx |= self.flat.out_ctx(r)
        return [p for p, _ in ctx if p not in self.cond]

    def merge_plan(self, nd):
        """What a TF1 Merge takes, its predicates known: ("dead",), ("live", k) -- the one input that runs --,
        ("case", p, kt, kf, cut_t, cut_f) -- a condition the data move, its two sides cut out of the graph
        (branch_cut) --, or ("where", live, conditions) -- sides that will not cut apart, all computed and the
        predicates picking. Last, the tensors to convert here before it."""
        ins = nd["inputs"]
        ctxs = [self.flat.out_ctx(r) for r in ins]
        live = [k for k, c in enumerate(ctxs) if not self.dead(c)]
        if not live:
            return "dead", []
        if len(live) == 1:
            return "live", live[0], [ins[live[0]]]
        moving = [frozenset(l for l in ctxs[k] if self.truth(l[0]) is None) for k in live]
        rest = [m - frozenset.intersection(*moving) for m in moving]
        if len(live) == 2 and all(len(r) == 1 for r in rest):
            (p, s0), (q, s1) = next(iter(rest[0])), next(iter(rest[1]))
            if p == q and s0 != s1:
                kt, kf = (live[0], live[1]) if s0 else (live[1], live[0])
                cut_t, cut_f = self.branch_cut(p, True, ins[kt]), self.branch_cut(p, False, ins[kf])
                return "case", p, kt, kf, cut_t, cut_f, list(cut_t.values()) + list(cut_f.values()) + [p]
        return "where", live, moving, [ins[k] for k in live] + [l[0] for m in moving for l in m]

    def branch_cut(self, p, side, out):
        """A TF1 condition's branch (side of predicate p) cut out of the graph, backward from its output: what
        it takes from outside -- the data of its Switches on p, or a value computed outside the branch -- by
        the tensor standing for it inside."""
        flat, lit = self.flat, (p, side)
        cut, seen, stack = {}, set(), [flat.follow(out)]
        while stack:
            r = stack.pop()
            if r in seen:
                continue
            seen.add(r)
            nd = flat.nodes.get(r[0])
            if nd is not None and nd["op"] in ("Switch", "RefSwitch") and len(nd["inputs"]) > 1 and flat.predicate(nd) == p:
                cut[r] = flat.follow(nd["inputs"][0])   # a door of the branch: what comes in through it
            elif nd is None or r in self.out or lit not in flat.out_ctx(r):
                cut[r] = r   # computed outside the branch (or here already): passed in
            else:
                stack.extend(flat.follow(x) for x in nd["inputs"])
        return cut

    def case(self, nd, p, sides, kt, kf):
        """A TF1 condition the data move made a sphere.case: each side (output tensor, its cut, its side) a
        block of what the branch computes, both taking what either takes from outside; only the branch the
        predicate takes runs. Its outputs: the value, and the index of the input taken (Merge's)."""
        b, outside, cuts = self.b, [], []
        for out, cut, side in sides:
            bind, given, assume = {}, {}, {p: side}
            for inner, outer in cut.items():
                v = self.get(outer)
                if v is DEAD:
                    raise TFError("%s: a branch takes a value that never runs" % nd["name"])
                if outer == p:
                    assume[inner] = side   # the predicate itself let through: known in its branch
                if isinstance(v, Ref) and v not in b.consts:
                    if v not in outside:
                        outside.append(v)
                    bind[inner] = outside.index(v)
                else:
                    given[inner] = v   # a weight or a variable: the block reads it as it is
            cuts.append((out, bind, given, assume, side))
        names = [self.cut_block(_tag("then" if side else "else"), bind, outside, [out], given=given, assume=assume,
                                branch=True) for out, bind, given, assume, side in cuts]
        pred = self.get(p)
        r = b.raw("sphere.case", [b.enc(b.op("logical_not.default", pred)), "L:" + ",".join("s:" + n for n in names),
                                  "L:" + ",".join("n:" + v for v in outside)])
        self.notes.append("TF1 condition %s: its two branches made blocks, only the one the predicate takes run"
                          " (the data move it)" % nd["name"])
        return [b.op("py.getitem", r, 0), b.op("where.self", pred, float(kt), float(kf))]

    def pick(self, items):
        """Values under conditions that exclude each other (their moving literals), one of them live: picked
        where by where, predicate by predicate; and the index of the one picked."""
        if len(items) == 1:
            return items[0][1], float(items[0][2])
        for p in sorted({l[0] for l in items[0][0]}):
            sides = [dict(it[0]).get(p) for it in items]
            if None not in sides and True in sides and False in sides:
                t = self.pick([(it[0] - {(p, True)}, it[1], it[2]) for it, s in zip(items, sides) if s])
                f = self.pick([(it[0] - {(p, False)}, it[1], it[2]) for it, s in zip(items, sides) if not s])
                pred = self.get(p)
                return self.b.op("where.self", pred, t[0], f[0]), self.b.op("where.self", pred, t[1], f[1])
        raise TFError("a Merge of values not of one TF1 condition")

    def loop(self, cname, bname, values, what):
        """A loop of condition cname and body bname over values: unrolled when the number of its iterations
        depends on no input's value (a fixed point of the body's dependencies tells) and stays small, a
        sphere.while else."""
        b = self.b
        dep_body, dep_cond = _deps(b.blocks[bname]), _deps(b.blocks[cname])
        if len(dep_body) != len(values):
            raise TFError("%s: a body of %d outputs for %d loop values" % (what, len(dep_body), len(values)))
        static = {k for k, v in enumerate(values) if v in b.static}
        while True:
            keep = {k for k in static if dep_body[k] <= static}
            if keep == static:
                break
            static = keep
        if dep_cond[0] <= static:   # the iterations' number fixed by the weights and the shapes alone
            counts = []
            for p in range(b.probes):
                vals, n = [b.value(v, p) for v in values], 0
                while n <= UNROLL_TRIPS and sg._truth(b.run_block(cname, vals)[0]):
                    vals, n = b.run_block(bname, vals), n + 1
                counts.append(n)
            size = max(1, len(b.blocks[bname]["nodes"]))
            if len(set(counts)) == 1 and counts[0] <= UNROLL_TRIPS and counts[0] * size <= UNROLL_NODES:
                out = list(values)
                for _ in range(counts[0]):
                    out = b.inline(bname, out)
                self.notes.append("%s: unrolled, %d iterations fixed by the weights and shapes" % (what, counts[0]))
                return out
        r = b.raw("sphere.while", ["s:" + cname, "s:" + bname, "L:" + ",".join("n:" + v for v in values)])
        self.notes.append("%s: kept a loop, its iterations depending on the data" % what if not dep_cond[0] <= static
                          else "%s: kept a loop, too long to unroll" % what)
        return [b.op("py.getitem", r, k) for k in range(len(values))]

    def branches(self, nd, fnames, sel, args, is_if):
        """If and Case: the branch taken where the selector depends on no input's value (inlined), else every
        branch a block and a sphere.case."""
        b = self.b
        lit = b.literal(sel) if sel in b.static else None
        tensor_args = [a for a in args if isinstance(a, Ref)]
        if lit is not None:
            v = lit[0] if isinstance(lit, list) else lit
            k = (0 if v else 1) if is_if else (int(v) if 0 <= int(v) < len(fnames) else len(fnames) - 1)
            name, _ = self.function_block(fnames[k], args, _tag("branch"))
            self.notes.append("%s %s: branch %d of %d taken, its selector fixed" % (nd["op"], nd["name"], k, len(fnames)))
            return b.inline(name, tensor_args)
        names = [self.function_block(f, args, _tag("branch"))[0] for f in fnames]
        idx = b.op("logical_not.default", sel) if is_if else sel
        r = b.raw("sphere.case", [b.enc(idx), "L:" + ",".join("s:" + n for n in names),
                                  "L:" + ",".join("n:" + a for a in tensor_args)])
        self.notes.append("%s %s: %d branches kept, the selector depending on the data" % (nd["op"], nd["name"], len(fnames)))
        return [b.op("py.getitem", r, k) for k in range(len(b.blocks[names[0]]["outputs"]))]

    def get(self, r):
        r = self.flat.follow(r)
        if r not in self.out:
            self.emit(r[0])
        if r not in self.out:
            raise TFError("node %s has no output %d" % r)
        return self.out[r]

    def emit(self, name):
        """name and what it needs converted, depth first without Python's recursion (graphs of thousands of nodes);
        path holds the nodes whose inputs are being converted, their ancestors: one met again is a cycle. First
        the TF1 predicates a node's running depends on (phase 0), then its inputs -- a Merge's only once its
        plan says which (phase 1): the live one, or what its branches take from outside -- then the node."""
        stack, path = [(name, 0)], set()
        while stack:
            n, phase = stack.pop()
            if n in self.done:
                continue
            nd = self.flat.nodes.get(n)
            if nd is None:
                raise TFError("the graph has no node " + n)
            if phase == 2:
                path.discard(n)
                try:
                    self.convert(nd)
                except (TFError, _Missing):
                    raise
                except Exception as e:
                    shapes = [list(np.shape(self.b.value(x))) if isinstance(x, Ref) else type(x).__name__ for x in
                              (self.out.get(self.flat.follow(r)) for r in nd["inputs"])]
                    raise TFError("%s (%s), its inputs of shapes %s: %s: %s" % (n, nd["op"], shapes, type(e).__name__, e))
                self.done.add(n)
                continue
            merge = nd["op"] in ("Merge", "RefMerge")
            if phase == 0:
                if n in path:
                    raise TFError("a cycle through %s (%s): control flow, which Sphere's graphs have not" % (n, nd["op"]))
                path.add(n)
                stack.append((n, 1 if merge else 2))
                needs = self.predicates(nd)
                if not merge and nd["op"] not in _VARIABLES + ("Const",):
                    needs += nd["inputs"]
            else:   # a Merge, its predicates converted: its plan, and what the plan takes
                stack.append((n, 2))
                self.plans[n] = plan = self.merge_plan(nd)
                needs = plan[-1]
            for r in needs:
                f = self.flat.follow(r)
                if f in self.out or f[0] == n:
                    continue
                if f[0] in self.done:
                    raise TFError("%s (%s) takes output %d of %s, which gives none" % (n, nd["op"], f[1], f[0]))
                if f[0] in path:
                    raise TFError("a cycle through %s (%s): control flow, which Sphere's graphs have not" % (n, nd["op"]))
                stack.append((f[0], 0))

    def convert(self, nd):
        op, name = nd["op"], nd["name"]
        if op not in ("Merge", "RefMerge") and self.dead(self.flat.node_ctx(name)):
            for k in range(8):   # on a side a fixed predicate does not take: by its data or its control inputs
                self.out[(name, k)] = DEAD
            return
        if op == "Const":
            v = nd["attr"].get("value")
            if isinstance(v, list):
                self.out[(name, 0)] = ("strings", v)
            else:
                self.out[(name, 0)] = self.b.const(v)
            return
        if op in _VARIABLES:
            if name not in self.values and self.zeros:   # a structure's check: zeros of the variable's shape
                s = nd["attr"].get("shape")
                self.values[name] = np.zeros([max(d, 1) for d in (s[1] or [])] if isinstance(s, tuple) else [])
            if name not in self.values:
                raise TFError("variable %s has no value in the checkpoint" % name)
            self.out[(name, 0)] = self.b.const(self.values[name]) if op in ("VariableV2", "Variable") else ("var", name)
            return
        ins = [self.out.get(self.flat.follow(r)) for r in nd["inputs"]]
        if op not in ("Merge", "RefMerge") and any(v is DEAD for v in ins):   # on the side a fixed Switch does not take
            for k in range(8):
                self.out[(name, k)] = DEAD
            return
        if op in ("ReadVariableOp", "ResourceGather"):
            res = ins[0]
            if not (isinstance(res, tuple) and res[0] == "var"):
                raise TFError("%s reads a resource that is not a variable" % name)
            v = self.b.const(self.values[res[1]])
            self.out[(name, 0)] = v if op == "ReadVariableOp" else self.b.op(
                "index_select.default", v, int(nd["attr"].get("axis", 0) or 0), ins[1])
            return
        if op in ("NoOp", "Assert", "AssignVariableOp", "Assign", "RestoreV2", "SaveV2", "MergeV2Checkpoints",
                  "StringJoin", "ShardedFilename", "Placeholder"):
            if op == "Placeholder":
                raise TFError("placeholder %s is not one of the model's inputs" % name)
            return
        handler = _TF.get(op)
        if handler is None:
            self.missing.setdefault(op, name)
            raise _Missing(op, name)
        outs = handler(self.b, nd, ins, self)
        outs = outs if isinstance(outs, (list, tuple)) else [outs]
        for k, o in enumerate(outs):
            self.out[(name, k)] = o


class _Missing(Exception):
    def __init__(self, op, name):
        super().__init__(op)
        self.op, self.name = op, name


def _a(nd, key, default=None):
    v = nd["attr"].get(key, default)
    return v.decode() if isinstance(v, bytes) else v


def _kind(nd, key="T"):
    t = nd["attr"].get(key)
    return t.kind if isinstance(t, DType) else "f"


def _nchw(nd):
    return _a(nd, "data_format", "NHWC") in ("NCHW", "NCDHW")


# ---- the operations

def _binary(aten):
    return lambda b, nd, ins, c: b.op(aten, ins[0], ins[1])


def _unary(aten, *extra):
    return lambda b, nd, ins, c: b.op(aten, ins[0], *extra)


def _identity(b, nd, ins, c):
    return list(ins) if nd["op"] == "IdentityN" else ins[0]


def _div(b, nd, ins, c):
    if _kind(nd) in "ib":
        return b.op("div.Tensor_mode", ins[0], ins[1], "trunc")
    return b.op("div.Tensor", ins[0], ins[1])


def _floordiv(b, nd, ins, c):
    return b.op("div.Tensor_mode", ins[0], ins[1], "floor")


def _sqdiff(b, nd, ins, c):
    d = b.op("sub.Tensor", ins[0], ins[1], 1)
    return b.op("mul.Tensor", d, d)


def _addn(b, nd, ins, c):
    acc = ins[0]
    for x in ins[1:]:
        acc = b.op("add.Tensor", acc, x, 1)
    return acc


def _matmul(b, nd, ins, c):
    x, y = ins[0], ins[1]
    if nd["attr"].get("transpose_a"):
        x = b.op("permute.default", x, [1, 0])
    if nd["attr"].get("transpose_b"):
        y = b.op("permute.default", y, [1, 0])
    return b.op("mm.default", x, y)


def _batchmatmul(b, nd, ins, c):
    x, y = ins[0], ins[1]
    for i, key in ((0, "adj_x"), (1, "adj_y")):
        if nd["attr"].get(key):
            r = len(b.shape(ins[i]))
            perm = list(range(r - 2)) + [r - 1, r - 2]
            if i == 0:
                x = b.op("permute.default", x, perm)
            else:
                y = b.op("permute.default", y, perm)
    return b.op("matmul.default", x, y)


def _biasadd(b, nd, ins, c):
    bias = ins[1]
    if _nchw(nd):
        r = len(b.shape(ins[0]))
        bias = b.op("view.default", bias, [-1] + [1] * (r - 2))
    return b.op("add.Tensor", ins[0], bias, 1)


def _cast(b, nd, ins, c):
    to = nd["attr"].get("DstT")
    name = repr(to) if isinstance(to, DType) else "float32"
    if name == "float64":
        return b.op("clone.default", ins[0], None)
    return b.op("_to_copy.default", ins[0], name)


def _axes(b, r, rank, what="an axis"):
    return [a % max(rank, 1) for a in b.ints(r, what)]


def _reduce(aten, keyed=True):
    def h(b, nd, ins, c):
        x, rank = ins[0], len(b.shape(ins[0]))
        axes = _axes(b, ins[1], rank, "a reduction's axes")
        keep = bool(nd["attr"].get("keep_dims", False))
        if not axes:
            return b.op("clone.default", x, None)   # over no axis: TensorFlow's reduction is the identity
        if aten in ("prod.dim_int", "any.dim"):
            for a in sorted(axes, reverse=True):
                x = b.op(aten, x, a, keep)
            return x
        return b.op(aten, x, axes, keep)
    return h


def _all(b, nd, ins, c):
    x, rank, keep = ins[0], len(b.shape(ins[0])), bool(nd["attr"].get("keep_dims", False))
    x = b.op("logical_not.default", x)
    for a in sorted(_axes(b, ins[1], rank), reverse=True):
        x = b.op("any.dim", x, a, keep)
    return b.op("logical_not.default", x)


def _argmax(aten):
    def h(b, nd, ins, c):
        return b.op(aten, ins[0], _axes(b, ins[1], len(b.shape(ins[0])))[0], False)
    return h


def _softmax(aten):
    return lambda b, nd, ins, c: b.op(aten, ins[0], -1, False)


def _shape_of(b, nd, ins, c):
    x = ins[0]
    dims = []
    for d in range(len(b.shape(x))):
        sizes = [b.shape(x, p)[d] for p in range(b.probes)]
        dims.append(b.op("scalar_tensor.default", sizes[0] if len(set(sizes)) == 1 else b.op("sym_size.int", x, d)))
    if not dims:
        return b.const(np.zeros(0))
    return b.op("stack.default", dims, 0)


def _size(b, nd, ins, c):
    return b.op("scalar_tensor.default", b.op("sym_numel.default", ins[0]))


def _rank(b, nd, ins, c):
    return b.const(np.float64(len(b.shape(ins[0]))))


def _reshape(b, nd, ins, c):
    return b.op("view.default", ins[0], b.sizes(ins[1]))


def _squeeze(b, nd, ins, c):
    dims = nd["attr"].get("squeeze_dims") or nd["attr"].get("axis") or []
    if not dims:
        return b.op("squeeze.default", ins[0])
    return b.op("squeeze.dims", ins[0], [int(d) for d in dims])


def _expand(b, nd, ins, c):
    return b.op("unsqueeze.default", ins[0], b.ints(ins[1], "ExpandDims's axis")[0])


def _transpose(b, nd, ins, c):
    return b.op("permute.default", ins[0], b.ints(ins[1], "a transpose's permutation"))


def _concat(b, nd, ins, c):
    if nd["op"] == "Concat":   # the axis first
        return b.op("cat.default", list(ins[1:]), b.ints(ins[0], "a concatenation's axis")[0])
    return b.op("cat.default", list(ins[:-1]), b.ints(ins[-1], "a concatenation's axis")[0])


def _pack(b, nd, ins, c):
    return b.op("stack.default", list(ins), int(nd["attr"].get("axis", 0) or 0))


def _unpack(b, nd, ins, c):
    parts = b.op("unbind.int", ins[0], int(nd["attr"].get("axis", 0) or 0))
    return [b.op("py.getitem", parts, i) for i in range(len(b.value(parts)))]


def _index(b, x, items):
    """x[items], numpy's basic indexing: slices, integers, None (a new axis), as slice, select and unsqueeze."""
    cur = 0
    for it in items:
        if it is None:
            x = b.op("unsqueeze.default", x, cur)
            cur += 1
        elif isinstance(it, int):
            x = b.op("select.int", x, cur, it)
        else:
            n = b.shape(x)[cur]
            start, stop, step = it.indices(n)
            if step < 0:   # backward: flipped, then forward
                idx = list(range(start, stop, step))
                x = b.op("flip.default", x, [cur])
                if idx:
                    x = b.op("slice.Tensor", x, cur, n - 1 - idx[0], n - 1 - idx[-1] + 1, -step)
                else:
                    x = b.op("slice.Tensor", x, cur, 0, 0, 1)
            elif (start, stop, step) != (0, n, 1):
                sizes = [b.shape(x, p)[cur] for p in range(b.probes)]
                if len(set(sizes)) > 1 and (it.start is not None or it.stop is not None):
                    raise TFError("a slice of the batch's dimension at fixed rows: Sphere's graph would fix the batch")
                x = b.op("slice.Tensor", x, cur, it.start, it.stop, step)
            cur += 1
    return x


def _strided_slice(b, nd, ins, c):
    begin, end = b.ints(ins[1], "StridedSlice's begin"), b.ints(ins[2], "StridedSlice's end")
    strides = b.ints(ins[3], "StridedSlice's strides")
    m = {k: int(nd["attr"].get(k + "_mask", 0) or 0) for k in ("begin", "end", "ellipsis", "new_axis", "shrink_axis")}
    rank = len(b.shape(ins[0]))
    real = sum(1 for i in range(len(begin)) if not (m["new_axis"] >> i & 1) and not (m["ellipsis"] >> i & 1))
    items = []
    for i in range(len(begin)):
        if m["ellipsis"] >> i & 1:
            items += [slice(None)] * (rank - real)
        elif m["new_axis"] >> i & 1:
            items.append(None)
        elif m["shrink_axis"] >> i & 1:
            items.append(begin[i])
        else:
            items.append(slice(None if m["begin"] >> i & 1 else begin[i], None if m["end"] >> i & 1 else end[i], strides[i]))
    return _index(b, ins[0], items)


def _slice(b, nd, ins, c):
    begin, size = b.ints(ins[1], "Slice's begin"), b.ints(ins[2], "Slice's size")
    return _index(b, ins[0], [slice(s, None if n == -1 else s + n) for s, n in zip(begin, size)])


def _split(b, nd, ins, c):
    if nd["op"] == "SplitV":
        x, sizes, axis = ins[0], b.ints(ins[1], "SplitV's sizes"), b.ints(ins[2], "SplitV's axis")[0]
        rank = len(b.shape(x))
        if -1 in sizes:
            sizes[sizes.index(-1)] = b.shape(x)[axis % rank] - sum(s for s in sizes if s != -1)
        parts = b.op("split_with_sizes.default", x, sizes, axis)
    else:
        axis, x = b.ints(ins[0], "Split's axis")[0], ins[1]
        n = int(nd["attr"].get("num_split", 1))
        parts = b.op("split.Tensor", x, b.shape(x)[axis % len(b.shape(x))] // n, axis)
    return [b.op("py.getitem", parts, i) for i in range(len(b.value(parts)))]


def _tile(b, nd, ins, c):
    return b.op("repeat.default", ins[0], b.sizes(ins[1]))


def _fill(b, nd, ins, c):
    v = b.literal(ins[1])
    return b.op("full.default", b.sizes(ins[0]), float(v) if v is not None else ins[1])


def _range(b, nd, ins, c):
    vals = [b.literal(r) for r in ins[:3]]
    args = [float(v) if v is not None else r for v, r in zip(vals, ins[:3])]
    return b.op("arange.start_step", *args)


def _gather(b, nd, ins, c):
    axis = 0 if nd["op"] == "Gather" else b.ints(ins[2], "GatherV2's axis")[0]
    if int(nd["attr"].get("batch_dims", 0) or 0):
        raise TFError("GatherV2 with batch_dims")
    return b.op("index_select.default", ins[0], axis % len(b.shape(ins[0])), ins[1])


def _onehot(b, nd, ins, c):
    depth = b.ints(ins[1], "OneHot's depth")[0]
    hot = b.op("eq.Tensor", b.op("unsqueeze.default", ins[0], -1), b.op("arange.default", depth))
    y = b.op("where.self", hot, ins[2], ins[3])
    axis = int(nd["attr"].get("axis", -1))
    if axis != -1:
        r = len(b.shape(y))
        perm = list(range(r - 1))
        perm.insert(axis % r, r - 1)
        y = b.op("permute.default", y, perm)
    return y


def _pad(b, nd, ins, c):
    pads = np.asarray(b.ints(ins[1], "the paddings")).reshape(-1, 2)
    flat = []
    for before, after in pads[::-1]:
        flat += [int(before), int(after)]
    value = b.literal(ins[2]) if len(ins) > 2 else 0.0
    return b.op("constant_pad_nd.default", ins[0], flat, float(value))


def _broadcast(b, nd, ins, c):
    return b.op("expand.default", ins[0], b.sizes(ins[1]))


def _select(b, nd, ins, c):
    cond, t = ins[0], ins[1]
    if nd["op"] == "Select" and len(b.shape(cond)) == 1 and len(b.shape(t)) > 1:   # TF1's Select picks rows
        cond = b.op("view.default", cond, [-1] + [1] * (len(b.shape(t)) - 1))
    return b.op("where.self", cond, t, ins[2])


def _window(nd, key, spatial, nchw):
    v = list(nd["attr"].get(key) or [1, 1, 1, 1])
    return [int(v[2]), int(v[3])] if nchw else [int(v[1]), int(v[2])]


def _same(size, kernel, stride, dilation):
    """TensorFlow's SAME (kernel_shape_util.cc): the output ceil(in / stride), the padding's odd unit at the end."""
    out = (size + stride - 1) // stride
    need = max(0, (out - 1) * stride + (kernel - 1) * dilation + 1 - size)
    return need // 2, need - need // 2


def _padding(b, nd, x, kernel, strides, dilations):
    """The padding of a 2-d window over x (NCHW), as (pad of the operation, x padded first or not)."""
    mode = _a(nd, "padding", "VALID")
    if mode == "VALID":
        return [0, 0], None
    if mode == "EXPLICIT":
        e = list(nd["attr"].get("explicit_paddings") or [])
        e = e[4:8] if not _nchw(nd) and len(e) == 8 else e[4:8] if len(e) == 8 else [0, 0, 0, 0]
        pads = [(int(e[0]), int(e[1])), (int(e[2]), int(e[3]))]
    else:
        h, w = b.shape(x)[2], b.shape(x)[3]
        pads = [_same(h, kernel[0], strides[0], dilations[0]), _same(w, kernel[1], strides[1], dilations[1])]
    if all(p[0] == p[1] for p in pads):
        return [pads[0][0], pads[1][0]], None
    return [0, 0], [pads[1][0], pads[1][1], pads[0][0], pads[0][1]]


def _to_nchw(b, nd, x):
    return x if _nchw(nd) else b.op("permute.default", x, [0, 3, 1, 2])


def _from_nchw(b, nd, y):
    return y if _nchw(nd) else b.op("permute.default", y, [0, 2, 3, 1])


def _conv(b, nd, ins, c):
    nchw = _nchw(nd)
    x = _to_nchw(b, nd, ins[0])
    filt = ins[1]
    kh, kw = b.shape(filt)[0], b.shape(filt)[1]
    strides, dil = _window(nd, "strides", 2, nchw), _window(nd, "dilations", 2, nchw)
    groups = 1
    if nd["op"] == "DepthwiseConv2dNative":   # [kh, kw, C, M] -> [C*M, 1, kh, kw], a group per input channel
        cin, mult = b.shape(filt)[2], b.shape(filt)[3]
        w = b.op("view.default", b.op("permute.default", filt, [2, 3, 0, 1]), [cin * mult, 1, kh, kw])
        groups = cin
    else:   # [kh, kw, Cin, Cout] -> [Cout, Cin, kh, kw]
        w = b.op("permute.default", filt, [3, 2, 0, 1])
        groups = max(1, b.shape(x)[1] // b.shape(filt)[2])
    pad, explicit = _padding(b, nd, x, [kh, kw], strides, dil)
    if explicit:
        x = b.op("constant_pad_nd.default", x, explicit, 0.0)
    y = b.op("convolution.default", x, w, None, strides, pad, dil, False, [0, 0], groups)
    return _from_nchw(b, nd, y)


def _pool(b, nd, ins, c):
    nchw = _nchw(nd)
    x = _to_nchw(b, nd, ins[0])
    k, s = _window(nd, "ksize", 2, nchw), _window(nd, "strides", 2, nchw)
    pad, explicit = _padding(b, nd, x, k, s, [1, 1])
    if nd["op"].startswith("MaxPool"):
        if explicit:
            x = b.op("constant_pad_nd.default", x, explicit, float("-inf"))
        y = b.op("py.getitem", b.op("max_pool2d_with_indices.default", x, k, s, pad, [1, 1], False), 0)
    elif not explicit and pad == [0, 0]:
        y = b.op("avg_pool2d.default", x, k, s, [0, 0], False, True, None)
    else:   # TensorFlow's average leaves the padding out of the count
        xp = b.op("constant_pad_nd.default", x, explicit, 0.0) if explicit else x
        ones = b.op("ones_like.default", x)
        op_ = b.op("constant_pad_nd.default", ones, explicit, 0.0) if explicit else ones
        total = b.op("avg_pool2d.default", xp, k, s, pad, False, True, 1)
        count = b.op("avg_pool2d.default", op_, k, s, pad, False, True, 1)
        y = b.op("div.Tensor", total, count)
    return _from_nchw(b, nd, y)


def _batchnorm(b, nd, ins, c):
    if nd["attr"].get("is_training"):
        raise TFError("%s is a batch normalization in training mode: freeze the model for inference" % nd["name"])
    x, scale, offset, mean, var = ins[:5]
    eps = float(nd["attr"].get("epsilon", 1e-4))
    rank = len(b.shape(x))
    gain = b.op("mul.Tensor", scale, b.op("rsqrt.default", b.op("add.Tensor", var, eps, 1)))
    shift = b.op("sub.Tensor", offset, b.op("mul.Tensor", mean, gain), 1)
    if _nchw(nd):
        gain = b.op("view.default", gain, [-1] + [1] * (rank - 2))
        shift = b.op("view.default", shift, [-1] + [1] * (rank - 2))
    y = b.op("add.Tensor", b.op("mul.Tensor", x, gain), shift, 1)
    return [y, mean, var, mean, var, mean]


def _topk(b, nd, ins, c):
    t = b.op("topk.default", ins[0], b.ints(ins[1], "TopK's k")[0], -1, True, True)
    return [b.op("py.getitem", t, 0), b.op("py.getitem", t, 1)]


def _selu(b, nd, ins, c):
    return b.op("elu.default", ins[0], 1.6732632423543772848170429916717, 1.0507009873554804934193349852946, 1.0)


def _softsign(b, nd, ins, c):
    return b.op("div.Tensor", ins[0], b.op("add.Tensor", b.op("abs.default", ins[0]), 1.0, 1))


def _square(b, nd, ins, c):
    return b.op("mul.Tensor", ins[0], ins[0])


def _like(aten):
    return lambda b, nd, ins, c: b.op(aten, ins[0], None, None, None, None, None)


def _no_nan(aten):
    """DivNoNan, MulNoNan: 0 where the second operand is 0, whatever the first."""
    def h(b, nd, ins, c):
        zero = b.op("eq.Tensor", ins[1], 0.0)
        return b.op("where.self", zero, 0.0, b.op(aten, ins[0], ins[1]))
    return h


def _fmod(b, nd, ins, c):   # TensorFlow's Mod and TruncateMod: C's fmod, the sign of the dividend
    q = b.op("div.Tensor_mode", ins[0], ins[1], "trunc")
    return b.op("sub.Tensor", ins[0], b.op("mul.Tensor", q, ins[1]), 1)


def _clip(b, nd, ins, c):
    return b.op("clamp.Tensor", ins[0], ins[1], ins[2])


def _cumsum(b, nd, ins, c):
    if nd["attr"].get("exclusive") or nd["attr"].get("reverse"):
        raise TFError("%s: Cumsum exclusive or reversed" % nd["name"])
    return b.op("cumsum.default", ins[0], b.ints(ins[1], "Cumsum's axis")[0], None)


def _reverse(b, nd, ins, c):
    return b.op("flip.default", ins[0], _axes(b, ins[1], len(b.shape(ins[0])), "ReverseV2's axes"))


def _finite(b, nd, ins, c):
    bad = b.op("logical_or.default", b.op("isnan.default", ins[0]), b.op("isinf.default", ins[0]))
    return b.op("logical_not.default", bad)


def _erfc(b, nd, ins, c):
    return b.op("rsub.Scalar", b.op("erf.default", ins[0]), 1.0, 1)


def _l2loss(b, nd, ins, c):
    return b.op("mul.Tensor", b.op("sum.default", b.op("mul.Tensor", ins[0], ins[0]), None), 0.5)


def _while(b, nd, ins, c):
    """While, StatelessWhile: condition and body functions as blocks of the tensor loop values (resources passed
    through as they are); unrolled or a loop, as the analysis of what the iterations depend on says."""
    cname, _ = c.function_block(nd["attr"]["cond"].name, ins, _tag("cond"))
    bname, _ = c.function_block(nd["attr"]["body"].name, ins, _tag("body"))
    tensors = [k for k, v in enumerate(ins) if isinstance(v, Ref)]
    res = c.loop(cname, bname, [ins[k] for k in tensors], "%s %s" % (nd["op"], nd["name"]))
    out = list(ins)
    for j, k in enumerate(tensors):
        out[k] = res[j]
    return out


def _if(b, nd, ins, c):
    return c.branches(nd, [nd["attr"]["then_branch"].name, nd["attr"]["else_branch"].name], ins[0], list(ins[1:]), True)


def _case(b, nd, ins, c):
    return c.branches(nd, [f.name for f in nd["attr"]["branches"]], ins[0], list(ins[1:]), False)


def _frame(b, nd, ins, c):
    """A TF1 loop made functional (_Flat.functionalize): its condition and its body cut out of the graph between
    the Merges (the loop values), the true outputs of the Switches and the frame's invariant Enters."""
    a = nd["attr"]
    n = len(a["merges"])
    if not all(isinstance(v, Ref) for v in ins):
        raise TFError("TF1 loop %s over values that are not tensors" % a["frame"])
    inv = {(e, 0): n + j for j, e in enumerate(a["invariants"])}
    cut = dict(inv)
    cut.update({(m, 0): k for k, m in enumerate(a["merges"])})
    cname = c.cut_block(_tag("cond"), cut, list(ins), [a["pred"]])
    cut = dict(inv)
    cut.update({(s, 1): k for k, s in enumerate(a["switches"])})
    bname = c.cut_block(_tag("body"), cut, list(ins), list(a["nexts"]) + [(e, 0) for e in a["invariants"]])
    return c.loop(cname, bname, list(ins), "TF1 loop %s" % a["frame"])[:n]


def _switch(b, nd, ins, c):
    """TF1's Switch outside a loop: a condition's fork. A predicate fixed -- by the weights and the shapes, or by
    the branch being converted -- leaves the other side dead; a moving one gives both sides the data, the
    conditions TensorFlow's graph puts every tensor under (_Flat.node_ctx) telling them apart."""
    p = c.flat.predicate(nd)
    if p is None:
        raise TFError("%s: a TF1 loop's Switch outside its loop" % nd["name"])
    t = c.truth(p)
    return [ins[0], ins[0]] if t is None else [DEAD if t else ins[0], ins[0] if t else DEAD]


def _merge(b, nd, ins, c):
    """TF1's Merge, as its plan says (_Converter.merge_plan): the input that runs, its predicates fixed; a
    condition the data move made a sphere.case of its two branches, cut into blocks, nested ones inside; or
    sides that will not cut apart all computed, the predicates picking."""
    plan = c.plans.pop(nd["name"])
    if plan[0] == "dead":
        return [DEAD, DEAD]
    if plan[0] == "live":
        if len(nd["inputs"]) > 1:
            c.notes.append("TF1 condition %s: one branch kept, its predicate fixed (by the weights and shapes, or by the"
                           " branch it lies in)" % nd["name"])
        return [c.get(nd["inputs"][plan[1]]), b.const(np.float64(plan[1]))]
    if plan[0] == "case":
        _, p, kt, kf, cut_t, cut_f, _ = plan
        return c.case(nd, p, [(nd["inputs"][kt], cut_t, True), (nd["inputs"][kf], cut_f, False)], kt, kf)
    _, live, moving, _ = plan
    values = [c.get(nd["inputs"][k]) for k in live]
    if not all(isinstance(v, Ref) for v in values):
        raise TFError("%s merges what is not a tensor" % nd["name"])
    c.notes.append("TF1 condition %s: its branches all computed, the predicates picking (they do not cut apart)" % nd["name"])
    return list(c.pick([(m, v, k) for m, v, k in zip(moving, values, live)]))


def _unstructured(b, nd, ins, c):
    raise TFError("%s (%s): a TF1 loop Sphere could not restructure into a functional one" % (nd["name"], nd["op"]))


def _sparse_xent(b, nd, ins, c):
    """SparseSoftmaxCrossEntropyWithLogits: the loss -log softmax(x)[label], and its gradient softmax(x) - onehot."""
    x, labels = ins[0], ins[1]
    classes = b.shape(x)[-1]
    hot = b.op("eq.Tensor", b.op("unsqueeze.default", labels, -1), b.op("arange.default", classes))
    loss = b.op("neg.default", b.op("sum.dim_IntList", b.op("mul.Tensor", hot, b.op("_log_softmax.default", x, -1, False)), [-1], False))
    grad = b.op("sub.Tensor", b.op("_softmax.default", x, -1, False), hot, 1)
    return [loss, grad]


def _perm(b, x, perm):
    return x if perm == sorted(perm) else b.op("permute.default", x, perm)


def _einsum(b, nd, ins, c):
    """Einsum of one or two operands (Keras's EinsumDense, MultiHeadAttention), as ATen's matmul does it: the indices
    of one operand alone summed, the rest permuted to (batch, free, contracted), multiplied batch by batch,
    reshaped to the output's indices. A size the model's batch moves is left to the reshape (-1)."""
    eq = _a(nd, "equation", "").replace(" ", "")
    lhs, _, out = eq.partition("->")
    terms = lhs.split(",")
    if len(terms) != len(ins) or not 1 <= len(terms) <= 2 or not out and "->" not in eq:
        raise TFError("Einsum %r" % eq)
    spare = [l for l in "ZYXWVUTSRQPONMLKJIHGFEDCBA" if l not in eq]
    if "..." in eq:   # an ellipsis: letters of their own, as many as each operand's rank asks
        widest = max(len(b.shape(x)) - (len(t) - 3) for t, x in zip(terms, ins) if "..." in t)
        ell = "".join(spare[:widest])
        terms = [t.replace("...", ell[widest - (len(b.shape(x)) - (len(t) - 3)):]) for t, x in zip(terms, ins)]
        out = out.replace("...", ell)
    if any(len(set(t)) != len(t) for t in terms):
        raise TFError("Einsum %r repeats an index (a trace or a diagonal)" % eq)
    size = {}
    for t, x in zip(terms, ins):
        for p in range(b.probes):
            for l, s in zip(t, b.shape(x, p)):
                size.setdefault(l, [None] * b.probes)[p] = s
    xs = []
    for k, (t, x) in enumerate(zip(terms, ins)):
        others = set("".join(u for j, u in enumerate(terms) if j != k)) | set(out)
        alone = [i for i, l in enumerate(t) if l not in others]
        if alone:
            x = b.op("sum.dim_IntList", x, alone, False)
            t = "".join(l for l in t if l in others)
        xs.append((t, x))
    if len(xs) == 1:
        t, x = xs[0]
        return _perm(b, x, [t.index(l) for l in out])

    def group(letters):
        vals = [int(np.prod([size[l][p] for l in letters])) if letters else 1 for p in range(b.probes)]
        return vals[0] if len(set(vals)) == 1 else -1

    (ta, xa), (tb, xb) = xs
    batch = [l for l in ta if l in tb and l in out]
    contract = [l for l in ta if l in tb and l not in out]
    fa, fb = [l for l in ta if l not in tb], [l for l in tb if l not in ta]
    xa = b.op("view.default", _perm(b, xa, [ta.index(l) for l in batch + fa + contract]), [group(batch), group(fa), group(contract)])
    xb = b.op("view.default", _perm(b, xb, [tb.index(l) for l in batch + contract + fb]), [group(batch), group(contract), group(fb)])
    dims = batch + fa + fb
    y = b.op("view.default", b.op("matmul.default", xa, xb), [size[l][0] if len(set(size[l])) == 1 else -1 for l in dims])
    return _perm(b, y, [dims.index(l) for l in out])


_TF = {
    "PlaceholderWithDefault": _identity,   # not fed: its default
    "Identity": _identity, "IdentityN": _identity, "StopGradient": _identity, "PreventGradient": _identity,
    "Snapshot": _identity, "CheckNumerics": _identity, "EnsureShape": _identity,
    "Add": _binary("add.Tensor"), "AddV2": _binary("add.Tensor"), "Sub": _binary("sub.Tensor"),
    "Mul": _binary("mul.Tensor"), "RealDiv": _binary("div.Tensor"), "Div": _div, "FloorDiv": _floordiv,
    "FloorMod": _binary("remainder.Tensor"), "Maximum": _binary("maximum.default"), "Minimum": _binary("minimum.default"),
    "Pow": _binary("pow.Tensor_Tensor"), "SquaredDifference": _sqdiff, "AddN": _addn, "BiasAdd": _biasadd,
    "BiasAddV1": _biasadd, "Atan2": _binary("atan2.default"),
    "Neg": _unary("neg.default"), "Abs": _unary("abs.default"), "Square": _square, "Sqrt": _unary("sqrt.default"),
    "Rsqrt": _unary("rsqrt.default"), "Exp": _unary("exp.default"), "Log": _unary("log.default"),
    "Log1p": _unary("log1p.default"), "Expm1": _unary("expm1.default"), "Sin": _unary("sin.default"),
    "Cos": _unary("cos.default"), "Tanh": _unary("tanh.default"), "Sigmoid": _unary("sigmoid.default"),
    "Relu": _unary("relu.default"), "Relu6": _unary("hardtanh.default", 0.0, 6.0), "Elu": _unary("elu.default"),
    "Selu": _selu, "Softplus": _unary("softplus.default", 1.0, 20.0), "Softsign": _softsign,
    "LeakyRelu": lambda b, nd, ins, c: b.op("leaky_relu.default", ins[0], float(nd["attr"].get("alpha", 0.2))),
    "Erf": _unary("erf.default"), "Reciprocal": _unary("reciprocal.default"), "Inv": _unary("reciprocal.default"),
    "Sign": _unary("sign.default"), "Floor": _unary("floor.default"), "Ceil": _unary("ceil.default"),
    "Round": _unary("round.default"), "Rint": _unary("round.default"), "IsNan": _unary("isnan.default"),
    "Equal": _binary("eq.Tensor"), "NotEqual": _binary("ne.Tensor"), "Less": _binary("lt.Tensor"),
    "LessEqual": _binary("le.Tensor"), "Greater": _binary("gt.Tensor"), "GreaterEqual": _binary("ge.Tensor"),
    "LogicalAnd": _binary("logical_and.default"), "LogicalOr": _binary("logical_or.default"),
    "LogicalNot": _unary("logical_not.default"), "Select": _select, "SelectV2": _select,
    "Sum": _reduce("sum.dim_IntList"), "Mean": _reduce("mean.dim"), "Max": _reduce("amax.default"),
    "Min": _reduce("amin.default"), "Prod": _reduce("prod.dim_int"), "Any": _reduce("any.dim"), "All": _all,
    "ArgMax": _argmax("argmax.default"), "ArgMin": _argmax("argmin.default"),
    "Softmax": _softmax("_softmax.default"), "LogSoftmax": _softmax("_log_softmax.default"),
    "MatMul": _matmul, "BatchMatMul": _batchmatmul, "BatchMatMulV2": _batchmatmul, "BatchMatMulV3": _batchmatmul,
    "Cast": _cast, "Shape": _shape_of, "Size": _size, "Rank": _rank, "Reshape": _reshape, "Squeeze": _squeeze,
    "ExpandDims": _expand, "Transpose": _transpose, "ConcatV2": _concat, "Concat": _concat, "Pack": _pack,
    "Unpack": _unpack, "StridedSlice": _strided_slice, "Slice": _slice, "Split": _split, "SplitV": _split,
    "Tile": _tile, "Fill": _fill, "ZerosLike": _like("zeros_like.default"), "OnesLike": _like("ones_like.default"),
    "Range": _range, "GatherV2": _gather, "Gather": _gather, "OneHot": _onehot, "Pad": _pad, "PadV2": _pad,
    "BroadcastTo": _broadcast, "Conv2D": _conv, "DepthwiseConv2dNative": _conv, "MaxPool": _pool, "MaxPoolV2": _pool,
    "AvgPool": _pool, "FusedBatchNorm": _batchnorm, "FusedBatchNormV2": _batchnorm, "FusedBatchNormV3": _batchnorm,
    "TopKV2": _topk, "Einsum": _einsum, "SparseSoftmaxCrossEntropyWithLogits": _sparse_xent,
    "DivNoNan": _no_nan("div.Tensor"), "MulNoNan": _no_nan("mul.Tensor"), "Mod": _fmod, "TruncateMod": _fmod,
    "TruncateDiv": lambda b, nd, ins, c: b.op("div.Tensor_mode", ins[0], ins[1], "trunc"), "ClipByValue": _clip,
    "Cumsum": _cumsum, "ReverseV2": _reverse, "IsInf": _unary("isinf.default"), "IsFinite": _finite, "Erfc": _erfc,
    "L2Loss": _l2loss, "Asin": _unary("asin.default"), "Acos": _unary("acos.default"), "Atan": _unary("atan.default"),
    "Sinh": _unary("sinh.default"), "Cosh": _unary("cosh.default"),
    # control flow: TF2's functional operations, TF1's frames (restructured) and conditions
    "While": _while, "StatelessWhile": _while, "If": _if, "StatelessIf": _if, "Case": _case, "StatelessCase": _case,
    "__FrameWhile": _frame, "Switch": _switch, "RefSwitch": _switch, "Merge": _merge, "RefMerge": _merge,
    "_Retval": _identity, "Enter": _unstructured, "Exit": _unstructured, "NextIteration": _unstructured,
    "LoopCond": _unstructured,
}


# ---------------------------------------------------------------------------
# Reading a model
# ---------------------------------------------------------------------------

def _read(path):
    """The model's graph, its library, its OpDefs, its variables' values, its signature: from a frozen GraphDef
    (.pb), a SavedModel's folder (or its saved_model.pb)."""
    folder = path if os.path.isdir(path) else os.path.dirname(path)
    file = os.path.join(path, "saved_model.pb") if os.path.isdir(path) else path
    with open(file, "rb") as fh:
        data = fh.read()
    metas = [_metagraph(v) for f, _, v in _fields(data) if f == 2] if os.path.basename(file) == "saved_model.pb" else []
    if metas:
        mg = next((m for m in metas if "serve" in m["tags"]), metas[0])
        nodes, library = mg["graph"]
        prefix = os.path.join(folder, "variables", "variables")
        values = bundle(prefix) if os.path.exists(prefix + ".index") else {}
        sigs = mg["signatures"]
        sig = sigs.get("serving_default") or next((s for k, s in sorted(sigs.items()) if not k.startswith("__")), None)
        return nodes, library, mg["ops"], values, sig, "SavedModel (%s)" % ", ".join(mg["tags"] or ["no tags"])
    if file.lower().endswith((".pbtxt", ".prototxt", ".txt")):
        nodes, library = _pbtxt(data.decode("utf-8", "replace"))
        kind = "GraphDef (text)"
    else:
        nodes, library = _graphdef(data)
        kind = "GraphDef"
    if not nodes:
        raise TFError(file + " holds no graph")
    return nodes, library, {}, {}, None, kind


_SAVER = ("SaveV2", "RestoreV2", "Restore", "MergeV2Checkpoints", "StringJoin", "ShardedFilename", "StaticRegexFullMatch",
          "Assign", "AssignVariableOp")


def _terminals(flat):
    """The outputs of a frozen graph, when none is named: what no other node takes, by its data or a control edge
    -- the saver's nodes and what reads a string (its file names) apart, and what TensorFlow itself will not let
    be fetched: the tensors of a TF1 condition's branch (its Switches' sides, its pivots), of a loop's frame."""
    used = {r[0] for nd in flat.nodes.values() for r in nd["inputs"]} | {
        c for nd in flat.nodes.values() for c in nd.get("control", ())}
    skip = ("Const", "NoOp", "Assert", "Placeholder", "Switch", "RefSwitch", "__FrameWhile") + _VARIABLES + _SAVER
    forks = {flat.follow(nd["inputs"][0]) for nd in flat.nodes.values() if nd["op"] in ("Switch", "RefSwitch")
             and len(nd["inputs"]) > 1 and flat.follow(nd["inputs"][0]) == flat.follow(nd["inputs"][1])}

    def saver(n):
        nd = flat.nodes[n]
        return n.split("/")[0] in ("save", "saver") or any(
            isinstance(t, DType) and t.kind == "s" for t in nd["attr"].values())

    def plumbing(nd):   # a condition's pred_id (Identity of what its Switch(p, p) forks on), tf.data's pipeline
        return (nd["op"] == "Identity" and nd["inputs"] and flat.follow(nd["inputs"][0]) in forks
                or nd["op"].endswith(("Dataset", "DatasetV2", "DatasetV3")) or "Iterator" in nd["op"])

    return [(n, 0) for n, nd in flat.nodes.items() if n not in used and nd["op"] not in skip and not saver(n)
            and n not in flat.inner and not flat.node_ctx(n) and not plumbing(nd)]


def load(path, name=None, inputs=None, outputs=None, shapes=None, feeds=None, folder=None, rows=8, missing="error",
         full_shapes=None, fixed=None):
    """A TensorFlow model as Sphere's graph (models/<name>.sng.spx), and its reference (numpy's outputs on its
    probe inputs' first rows) for Java's and C++'s checks. inputs/outputs: tensor names ("node" or "node:1"),
    by default the SavedModel's serving signature, or a frozen graph's placeholders and the nodes nothing takes;
    shapes: the rows' shapes of the inputs where the model leaves them free; missing="zeros" takes variables
    the checkpoint lacks for zeros -- a check of the structure, not a model to use. fixed: inputs (by name or
    signature key) or placeholders with a default made constants -- a training flag, a keep probability --,
    the conditions they choose resolved at import."""
    nodes, library, opdefs, ckpt, sig, kind = _read(path)
    flat = _Flat(nodes, library, opdefs)
    frames = flat.functionalize()
    values = flat.variables(ckpt)
    if sig and not inputs:
        inputs = [sig["inputs"][k]["name"] for k in sorted(sig["inputs"])]
        names = sorted(sig["inputs"])
    else:
        if not inputs:
            inputs = [n for n, nd in flat.nodes.items() if nd["op"] == "Placeholder"]
        names = [i.split(":")[0] for i in inputs]
    inputs, pinned = list(inputs), {}
    for key, v in (fixed or {}).items():   # each made a constant of its shape, out of the inputs
        k = next((j for j, (i, nm) in enumerate(zip(inputs, names)) if key in (nm, i, i.split(":")[0])), None)
        if k is not None:
            r, given = _ref(inputs[k]), sig["inputs"][names[k]]["shape"] if sig and names[k] in sig["inputs"] else None
            del inputs[k], names[k]
        elif flat.nodes.get(key, {}).get("op") in ("Placeholder", "PlaceholderV2", "PlaceholderWithDefault"):
            r, given = (key, 0), None
        else:
            raise TFError("cannot fix %s: the model has no input or placeholder of that name (its inputs: %s)"
                          % (key, ", ".join(names) or "none"))
        attr = flat.nodes.get(r[0], {"attr": {}})["attr"]
        s = attr["shape"][1] if isinstance(attr.get("shape"), tuple) else given
        if s and any(d in (-1, None) for d in s):
            raise TFError("cannot fix %s: its shape %s has free sizes (a batch), which no constant fills" % (key, s))
        v, t = np.full(s or [], float(v)), int(attr["dtype"]) % 100 if isinstance(attr.get("dtype"), DType) else 0
        pinned[r] = (key, v.astype(_NUMPY[t]).astype(np.float64) if t in _NUMPY else v)   # as TensorFlow takes it: 0.8 a float32's
    if sig and not outputs:
        outputs = [sig["outputs"][k]["name"] for k in sorted(sig["outputs"])]
    refs_in = [_ref(i) for i in inputs]
    refs_out = [_ref(o) for o in outputs] if outputs else _terminals(flat)
    if not refs_out:
        raise TFError("%s has no signature and no node left unused to take for an output: name the outputs" % path)
    # the inputs' shapes: the placeholders', the signature's, or given
    row_shapes, scalars = [], []
    for k, r in enumerate(refs_in):
        nd = flat.nodes.get(r[0], {"attr": {}})
        s = nd["attr"].get("shape")
        s = s[1] if isinstance(s, tuple) else None
        if sig and s is None:
            s = sig["inputs"][names[k]]["shape"]
        if shapes and k < len(shapes) and shapes[k] is not None:
            s = [-1] + list(shapes[k])
        if full_shapes is not None:   # the inputs' whole shapes, a scalar's []: by name, or a list in order
            given = full_shapes.get(names[k]) if isinstance(full_shapes, dict) else (
                full_shapes[k] if k < len(full_shapes) else None)
            s = list(given) if given is not None else s
        if s is None:   # no shape in the file, none given: a scalar if the model runs so (said), its rows asked for if not
            s = []
            scalars.append(inputs[k])
        if any(d in (-1, None) for d in s[1:]):
            raise TFError("input %s has free sizes %s: give its rows' shape (--shape)" % (inputs[k], s))
        row_shapes.append(s)
    batches = _probe_batches(row_shapes)
    # a batch to chunk by only where every input has one (weights fed as an input, a scalar: none)
    batched = bool(row_shapes) and all(s and s[0] in (-1, None) for s in row_shapes)
    b = _Builder(len(batches))
    rng = np.random.default_rng(0)
    conv = _Converter(flat, values, b)
    conv.zeros = missing == "zeros"
    for r, (_, v) in pinned.items():
        conv.out[flat.follow(r)] = b.const(v)   # a weight now: what depends on it alone is fixed, its conditions too
    if feeds == "particles":
        names = ["x", "mask"] + ["input%d" % i for i in range(2, len(names))]
    elif feeds == "high_level":
        names = ["x"] + ["input%d" % i for i in range(1, len(names))]
    for k, (r, s) in enumerate(zip(refs_in, row_shapes)):
        dtype = flat.nodes.get(r[0], {"attr": {}})["attr"].get("dtype")
        arrays = []
        for bt in batches:
            shape = [bt] + list(s[1:]) if s and s[0] in (-1, None) and bt is not None else list(s)
            kind_ = dtype.kind if isinstance(dtype, DType) else "f"
            base = rng.integers(0, 5, size=shape).astype(np.float64) if kind_ == "i" else (
                (rng.uniform(size=shape) > 0.5).astype(np.float64) if kind_ == "b" else rng.normal(size=shape))
            if feeds == "particles" and k == 1:
                base = (rng.uniform(size=shape) > 0.3).astype(np.float64)
            arrays.append(np.asarray(np.float32(base), dtype=np.float64))   # what TensorFlow would be fed: float32
        conv.out[flat.follow(r)] = b.input(names[k].replace("/", "_"), arrays)
    outs = []
    try:
        for r in refs_out:
            outs.append(conv.get(r))
    except _Missing as m:
        conv.missing.setdefault(m.op, m.name)
        # every operation the model needs that Sphere has not, not only the first
        for r in refs_out:
            try:
                conv.get(r)
            except _Missing as more:
                conv.missing.setdefault(more.op, more.name)
            except TFError:
                pass
        raise TFError("TensorFlow operations Sphere has not (yet), with a node that calls each:\n" + "\n".join(
            "  %s  (%s)" % kv for kv in sorted(conv.missing.items())))
    except Exception as e:
        if not scalars:
            raise
        raise TFError("%s: no shape in the file, so taken for scalars; the model does not run so (%s): give the rows' shape"
                      " (shapes=[[16, 7], ...], --shape 16,7)" % (", ".join(scalars), e)) from e
    for o in outs:
        if not isinstance(o, Ref):
            raise TFError("an output that is not a tensor")
    used = {}
    for nd in flat.nodes.values():
        used[nd["op"]] = used.get(nd["op"], 0) + 1
    if not name:   # a SavedModel in a version's folder (TF Serving's model/00000123) takes the model's name
        parts = [p for p in os.path.normpath(os.path.abspath(path)).split(os.sep) if p and p != "saved_model.pb"]
        name = parts[-2] if len(parts) > 1 and parts[-1].isdigit() else os.path.splitext(parts[-1])[0]
    graph = {"name": name, "inputs": list(b.inputs), "blocks": b.blocks,
             "input_names": list(b.names), "outputs": [str(o) for o in outs], "consts": dict(b.consts),
             "shapes": {k: list(v.shape) for k, v in b.consts.items()}, "nodes": b.nodes, "feeds": feeds or "",
             "framework": "TensorFlow %s, read by sphere_tf (no TensorFlow)" % kind}
    # outputs that are inputs or weights themselves: a node of their own, so that every output is computed
    for i, o in enumerate(graph["outputs"]):
        if o in graph["consts"] or o in graph["inputs"]:
            graph["outputs"][i] = str(b.op("clone.default", Ref(o), None))
            graph["nodes"] = b.nodes
    probe = [[b.value(Ref(i), p) for i in b.inputs] for p in range(b.probes)]
    expect = [[np.asarray(b.value(Ref(o), p)) for o in graph["outputs"]] for p in range(b.probes)]
    flags = _flags(b, [a for a in probe[0]])
    sg._fold(graph)
    sg._prune(graph)
    graph["optimized"] = sg.optimize(graph, probe[0])
    sg._shorten(graph)
    graph["example_shapes"] = [list(np.shape(a)) for a in probe[0]]
    graph["precision"] = "float64"
    graph["batch"] = "symbolic (TensorFlow's -1, its shape computations kept)" if batched else "fixed"
    graph["rows_independent"] = sg._rows_independent(graph, probe[0]) if batched else False
    p = sg.plan(graph) if batched else None
    if p:
        graph["memory"] = {"per_row_mb": p["per_row_peak"] / 2 ** 20, "weights_mb": p["weights"] / 2 ** 20,
                           "linear": p["linear"]}
    # the graph as saved against the conversion's own run, on every probe; then at batches neither probe had
    worst = 0.0
    for p_in, p_out in zip(probe, expect):
        got = sg._execute(graph, dict(zip(graph["inputs"], p_in)))
        for g_, e_ in zip(got, p_out):
            worst = max(worst, float(np.max(np.abs(np.asarray(g_) - e_))) if np.size(e_) else 0.0)
    free = []
    if batched:   # the outputs that have the batch (a loop's counter, a loss have none) keep it at other batches
        along = [k for k in range(len(graph["outputs"])) if all(
            np.shape(expect[p][k])[:1] == (bt,) for p, bt in enumerate(batches))]
        for bt in (1, 3, 17):
            ins = [np.resize(a, [bt] + list(a.shape[1:])) for a in probe[0]]
            got = sg._execute(graph, dict(zip(graph["inputs"], ins)))
            free.append(all(np.shape(got[k])[:1] == (bt,) for k in along))
    graph["tf"] = {"kind": kind, "ops": used, "variables": len(values), "inputs": inputs, "outputs": [
        "%s:%d" % r for r in refs_out], "consistent": worst, "batch_free": all(free) if free else None,
        "control": _summary(conv.notes), "frames": frames, "blocks": len(graph.get("blocks") or {}), "scalars": scalars,
        "fixed": {key: v.tolist() for key, v in pinned.values()}, "flags": [b.names[k] for k in flags],
        "flags_unbatch": bool(flags) and len(flags) < len(row_shapes) and all(
            s and s[0] in (-1, None) for k, s in enumerate(row_shapes) if k not in flags)}
    path_ = sg.save(graph, folder)
    graph["path"] = path_
    graph["reference"] = sg.reference(graph, [a[:rows] if np.ndim(a) and batched else a for a in probe[0]])
    return graph


def _flags(b, values):
    """The inputs that choose between branches: one value each (a training flag, a mode), reaching the selector
    of a sphere.case of the graph -- what fixed= would resolve at import. Their positions."""
    out = []
    for k, i in enumerate(b.inputs):
        if np.size(values[k]) != 1:
            continue
        dep = {i}
        for nd in b.nodes:
            if any(r in dep for r in sg._refs(nd["enc"])):
                if nd["op"] == "sphere.case" and any(r in dep for r in sg._refs(nd["enc"][:1])):
                    out.append(k)
                    break
                dep.add(nd["name"])
    return out


def _summary(notes):
    """The notes on control flow, those saying the same of many (a condition in every layer) said once."""
    said = {}
    for n in notes:
        what, _, how = n.partition(": ")
        said.setdefault(how, []).append(what)
    return [whats[0] + ": " + how if len(whats) == 1 else "%s and %d more like it: %s" % (whats[0], len(whats) - 1, how)
            for how, whats in said.items()]


def describe(graph):
    """sphere_graph's description -- the graph's own operations -- and what was read of TensorFlow (tf_ops: its nodes' kinds)."""
    d = sg.describe(graph)
    tf = dict(graph.get("tf", {}))
    d["tf_ops"] = tf.pop("ops", {})
    d.update(tf)
    return d


if __name__ == "__main__":
    import json
    import sys
    g = load(sys.argv[1], name=sys.argv[2] if len(sys.argv) > 2 else None)
    print(json.dumps({k: v for k, v in describe(g).items() if k != "ops"}, default=str, indent=1))
