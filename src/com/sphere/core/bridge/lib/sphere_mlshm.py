"""sphere_mlshm -- Sphere's tensor arena as the learning engine sees it: the jets Java wrote, as numpy arrays and as
PyTorch, JAX and TensorFlow tensors, without a byte copied.

    import sphere_mlshm as shm
    a = shm.attach(path)                 # the file Java's MlShm mapped
    x = a.numpy("x")                     # a view of the slot: its memory is the mapping's
    t = a.torch("x")                     # torch.from_numpy: the same memory
    j, f = a.jax("x"), a.tf("x")         # through DLPack: the same memory again (Java aligns every slot to 64 bytes)
    a.probe("x")                         # the proof, framework by framework

The region describes itself (MlShm.java writes it): a header -- the magic "SPMLSHM1", version, slots, size, bytes
used, generation -- then a table of 64 slots of 128 bytes (name, dtype, rank, shape, offset, bytes, live), then the
tensors. Java allocates; the engine reads its inputs there and writes its outputs into the slots Java gave it.
Requests and replies go by the engine's JSON lines: their system calls order the writes of both sides.
"""

import mmap
import struct
import time

import numpy as np

MAGIC = 0x314D48534C4D5053
OFF_TABLE, SLOTS, SLOT_BYTES = 256, 64, 128
DTYPES = {0: np.float64, 1: np.float32, 2: np.int64, 3: np.int32, 4: np.uint8}


class ArenaError(RuntimeError):
    """A file that is not an arena, or a slot it has not."""


class Slot:
    def __init__(self, index, name, dtype, shape, offset, nbytes):
        self.index, self.name, self.dtype, self.shape, self.offset, self.nbytes = index, name, dtype, shape, offset, nbytes

    def __repr__(self):
        return "<slot %d %s %s%s at %d>" % (self.index, self.name, np.dtype(self.dtype).name, list(self.shape), self.offset)


class Arena:
    """An arena attached: the file mapped, its slots read from its table."""

    def __init__(self, path):
        self.path = path
        self._fh = open(path, "r+b")
        self.mm = mmap.mmap(self._fh.fileno(), 0)   # the whole file, shared with Java's mapping of it
        magic, version, slots, size = struct.unpack_from("<QiiQ", self.mm, 0)
        if magic != MAGIC:
            self.close()
            raise ArenaError("%s is not a Sphere tensor arena" % path)
        self.version, self.size = version, size
        self._base = np.frombuffer(self.mm, dtype=np.uint8, count=1).ctypes.data

    def generation(self):
        return struct.unpack_from("<Q", self.mm, 32)[0]

    def slots(self):
        out = []
        for i in range(SLOTS):
            e = OFF_TABLE + i * SLOT_BYTES
            if struct.unpack_from("<i", self.mm, e + 120)[0] != 1:
                continue
            name = bytes(self.mm[e:e + 32]).split(b"\0", 1)[0].decode("utf-8")
            dtype, rank = struct.unpack_from("<ii", self.mm, e + 32)
            shape = struct.unpack_from("<%dq" % rank, self.mm, e + 40) if rank else ()
            offset, nbytes = struct.unpack_from("<qq", self.mm, e + 104)
            out.append(Slot(i, name, DTYPES[dtype], tuple(int(s) for s in shape), int(offset), int(nbytes)))
        return out

    def slot(self, key):
        for s in self.slots():
            if s.name == key or s.index == key:
                return s
        raise ArenaError("no slot %r in %s (%s)" % (key, self.path, ", ".join(s.name for s in self.slots())))

    def numpy(self, key):
        """The slot as a numpy array over the mapping itself: writing it writes the arena."""
        s = self.slot(key)
        count = int(np.prod(s.shape)) if s.shape else 1
        return np.frombuffer(self.mm, dtype=s.dtype, count=count, offset=s.offset).reshape(s.shape)

    def address(self, key):
        return self._base + self.slot(key).offset

    def torch(self, key):
        import torch
        return torch.from_numpy(self.numpy(key))

    def jax(self, key):
        """The slot as a JAX array over the arena's memory. JAX canonicalizes to 32 bits unless in its 64-bit mode:
        a float64 slot would be converted -- copied -- so the 64-bit mode is turned on for it."""
        import jax
        if self.slot(key).dtype in (np.float64, np.int64) and not jax.config.read("jax_enable_x64"):
            jax.config.update("jax_enable_x64", True)
        return jax.dlpack.from_dlpack(self.numpy(key), copy=False)

    def tf(self, key):
        import tensorflow as tf
        return tf.experimental.dlpack.from_dlpack(self.numpy(key).__dlpack__())

    def probe(self, key, frameworks=("numpy", "torch", "jax", "tensorflow")):
        """Whether each framework's tensor of the slot is the arena's memory: the tensor made, then a sentinel written
        into the mapping by another path (struct, not the views), then read back through the tensor -- a framework
        that copied reads the old value. With the addresses where a framework says them, and the time each took."""
        import importlib.util
        s = self.slot(key)
        if s.dtype not in (np.float64, np.float32):
            raise ArenaError("probe a float slot")
        made, out = {}, {}
        for fw in frameworks:
            if fw not in ("numpy",) and importlib.util.find_spec(fw if fw != "tensorflow" else "tensorflow") is None:
                out[fw] = {"available": False}
                continue
            t0 = time.perf_counter()
            try:
                made[fw] = getattr(self, fw if fw != "tensorflow" else "tf")(key)
                out[fw] = {"available": True, "microseconds": 1e6 * (time.perf_counter() - t0)}
            except Exception as e:
                out[fw] = {"available": True, "error": "%s: %s" % (type(e).__name__, e)}
        sentinel = float(np.random.default_rng().uniform(1e3, 2e3))
        fmt = "<d" if s.dtype == np.float64 else "<f"
        original = bytes(self.mm[s.offset:s.offset + struct.calcsize(fmt)])   # put back once read: the slot is Java's data
        struct.pack_into(fmt, self.mm, s.offset, sentinel)
        expect = struct.unpack_from(fmt, self.mm, s.offset)[0]
        for fw, t in made.items():
            try:
                flat = t.reshape(-1) if hasattr(t, "reshape") else t
                first = float(np.asarray(flat[0]) if fw in ("numpy", "jax") else flat[0].numpy() if fw == "tensorflow"
                              else flat[0].item())
                out[fw]["zero_copy"] = first == expect
                if fw == "numpy":
                    out[fw]["address_matches"] = t.ctypes.data == self.address(key)
                elif fw == "torch":
                    out[fw]["address_matches"] = t.data_ptr() == self.address(key)
                elif fw == "jax":
                    try:
                        out[fw]["address_matches"] = t.unsafe_buffer_pointer() == self.address(key)
                    except Exception:
                        pass
            except Exception as e:
                out[fw]["error"] = "%s: %s" % (type(e).__name__, e)
        self.mm[s.offset:s.offset + len(original)] = original
        made.clear()
        # what a copy would have cost, for scale
        view = self.numpy(key)
        t0 = time.perf_counter()
        np.array(view, copy=True)
        copy_us = 1e6 * (time.perf_counter() - t0)
        return {"slot": s.name, "dtype": np.dtype(s.dtype).name, "shape": list(s.shape), "bytes": s.nbytes,
                "aligned_64": (self.address(key) % 64) == 0, "frameworks": out, "copy_microseconds": copy_us}

    def close(self):
        """Detach: the mapping closed once no view of it is left (numpy keeps the buffer while an array lives)."""
        import gc
        for _ in range(3):
            try:
                self.mm.close()
                break
            except BufferError:
                gc.collect()
        try:
            self._fh.close()
        except Exception:
            pass


_ATTACHED = {}


def attach(path):
    """The arena at path, attached once per engine."""
    a = _ATTACHED.get(path)
    if a is None or a.mm.closed:
        a = _ATTACHED[path] = Arena(path)
    return a


def detach(path):
    a = _ATTACHED.pop(path, None)
    if a is not None:
        a.close()
    return {"detached": a is not None}


_NETS = {}


def predict(path, model, x=None, mask=None, hl=None, out="logits", engine="torch", precision="float32"):
    """A zoo network's logits for the jets of the arena's slots x, mask (and hl), written into its slot out: Java's
    jets in, the framework's logits back, through the mapping both ways. The network is built once per engine,
    precision and model; the inputs are prepared (standardized) as sphere_ml prepares them."""
    import sphere_ml as ml
    a = attach(path)
    key = (model, engine, precision)
    if key not in _NETS:
        spec, weights = ml.load(model)
        if engine == "torch":
            net = ml._torch_net(spec, weights, precision)
        elif engine == "numpy":
            net = None
        else:
            raise ValueError("engine torch or numpy, not " + engine)
        _NETS[key] = (spec, weights, net)
    spec, weights, net = _NETS[key]
    t0 = time.perf_counter()
    xs = a.numpy(x) if x is not None else None
    ms = a.numpy(mask) if mask is not None else None
    hs = a.numpy(hl) if hl is not None else None
    dtype = np.float64 if precision == "float64" or engine == "numpy" else np.float32
    inputs = ml.prepare(spec, spec["norm"], x=xs, mask=ms, hl=hs, dtype=dtype)
    t1 = time.perf_counter()
    if engine == "torch":
        import torch
        with torch.no_grad():
            z = net({k: torch.from_numpy(np.ascontiguousarray(v)) for k, v in inputs.items()}).double().numpy()
    else:
        z = np.asarray(ml.forward(ml.NumpyOps, spec, weights, inputs), dtype=np.float64)
    t2 = time.perf_counter()
    target = a.numpy(out)
    if target.shape != z.shape:
        raise ValueError("slot %s is %s, the logits %s" % (out, list(target.shape), list(z.shape)))
    target[...] = z   # the one write: into Java's memory
    t3 = time.perf_counter()
    return {"rows": int(z.shape[0]), "model": model, "engine": engine, "precision": precision,
            "prepare_ms": 1e3 * (t1 - t0), "forward_ms": 1e3 * (t2 - t1), "write_ms": 1e3 * (t3 - t2)}
