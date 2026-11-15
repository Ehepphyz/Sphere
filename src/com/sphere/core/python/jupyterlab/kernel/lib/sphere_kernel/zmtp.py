"""ZeroMQ's wire protocol, written out with the standard library.

A Jupyter kernel binds five sockets: ROUTER for shell, control and stdin, PUB
for iopub, REP for the heartbeat. This module speaks ZMTP 3.0/3.1 (RFC 23 and
37) with the NULL security mechanism on TCP, which is what every Jupyter client
uses on one machine -- pyzmq's libzmq as well as Sphere's own Java client. No
pyzmq, no native library: the kernel runs on any Python 3.9+.

All the I/O happens on one selector thread. Other threads hand it bytes to send
through a queue and a wake-up socket, so no socket is ever touched by two
threads at once and nothing the kernel runs can block the heartbeat.
"""

import collections
import os
import selectors
import socket
import struct
import threading

FLAG_MORE = 0x01
FLAG_LONG = 0x02
FLAG_COMMAND = 0x04

SIGNATURE = b"\xff" + b"\x00" * 8 + b"\x7f"
MAX_FRAME = 1 << 31
#: A subscriber slower than this many queued bytes loses messages, as libzmq's high-water mark does.
PUB_HIGH_WATER = 256 * 1024 * 1024


class ProtocolError(Exception):
    pass


def greeting(minor=0):
    """The 64 bytes each peer sends first: signature, version 3.minor, NULL, as-server."""
    g = bytearray(64)
    g[0:10] = SIGNATURE
    g[10] = 3
    g[11] = minor
    g[12:16] = b"NULL"
    g[32] = 1
    return bytes(g)


def encode(frames, command=False):
    out = bytearray()
    last = len(frames) - 1
    for i, frame in enumerate(frames):
        if isinstance(frame, str):
            frame = frame.encode("utf-8")
        elif not isinstance(frame, (bytes, bytearray)):
            frame = bytes(frame)
        flags = (FLAG_COMMAND if command else 0) | (FLAG_MORE if i < last else 0)
        size = len(frame)
        if size > 255:
            out += struct.pack(">BQ", flags | FLAG_LONG, size)
        else:
            out += struct.pack(">BB", flags, size)
        out += frame
    return bytes(out)


def command(name, body=b""):
    raw = name.encode("ascii")
    return encode([bytes([len(raw)]) + raw + body], command=True)


def ready(socket_type, identity=b""):
    props = bytearray()
    for key, value in (("Socket-Type", socket_type.encode("ascii")), ("Identity", identity)):
        k = key.encode("ascii")
        props += bytes([len(k)]) + k + struct.pack(">I", len(value)) + value
    return command("READY", bytes(props))


def parse_properties(data):
    props = {}
    i = 0
    while i < len(data):
        n = data[i]
        key = bytes(data[i + 1:i + 1 + n]).decode("ascii", "replace")
        i += 1 + n
        if i + 4 > len(data):
            break
        size = struct.unpack_from(">I", data, i)[0]
        i += 4
        props[key] = bytes(data[i:i + size])
        i += size
    return props


class Connection:
    """One peer of a bound socket: its handshake, its frames, the bytes still to write."""

    __slots__ = ("sock", "owner", "inbuf", "outbuf", "state", "identity", "peer_type",
                 "subscriptions", "parts", "closed", "writing")

    def __init__(self, sock, owner):
        self.sock = sock
        self.owner = owner
        self.inbuf = bytearray()
        self.outbuf = bytearray()
        self.state = "greeting"
        self.identity = b""
        self.peer_type = ""
        self.subscriptions = set()
        self.parts = []
        self.closed = False
        self.writing = False

    def feed(self, data):
        """Bytes received; returns the whole messages they complete."""
        self.inbuf += data
        messages = []
        while True:
            if self.state == "greeting":
                b = self.inbuf
                if len(b) >= 1 and b[0] != 0xFF:
                    raise ProtocolError("not a ZMTP peer")
                if len(b) >= 10 and not b[9] & 0x01:
                    raise ProtocolError("ZMTP 1.0 peer")
                if len(b) < 64:
                    break
                if b[10] < 3:
                    raise ProtocolError("ZMTP %d peer" % b[10])
                mechanism = bytes(b[12:32]).rstrip(b"\x00")
                if mechanism != b"NULL":
                    raise ProtocolError("security mechanism %r" % mechanism)
                del b[:64]
                self.state = "handshake"
                continue
            frame = self._frame()
            if frame is None:
                break
            flags, body = frame
            if flags & FLAG_COMMAND:
                self._command(body)
                continue
            if self.state != "traffic":
                raise ProtocolError("message before READY")
            self.parts.append(body)
            if not flags & FLAG_MORE:
                messages.append(self.parts)
                self.parts = []
        return messages

    def _frame(self):
        b = self.inbuf
        if len(b) < 2:
            return None
        flags = b[0]
        if flags & FLAG_LONG:
            if len(b) < 9:
                return None
            size = struct.unpack_from(">Q", b, 1)[0]
            head = 9
        else:
            size = b[1]
            head = 2
        if size > MAX_FRAME:
            raise ProtocolError("frame of %d bytes" % size)
        if len(b) < head + size:
            return None
        body = bytes(b[head:head + size])
        del b[:head + size]
        return flags, body

    def _command(self, body):
        if not body:
            return
        n = body[0]
        name = body[1:1 + n].decode("ascii", "replace")
        data = body[1 + n:]
        if name == "READY":
            props = parse_properties(data)
            self.peer_type = props.get("Socket-Type", b"").decode("ascii", "replace")
            self.identity = props.get("Identity", b"")
            self.state = "traffic"
            self.owner.on_ready(self)
        elif name == "PING":
            # ZMTP 3.1 heartbeat: a 2-byte TTL then up to 16 bytes of context, echoed in the PONG
            self.queue(command("PONG", data[2:] if len(data) >= 2 else b""))
        elif name == "SUBSCRIBE":
            self.subscriptions.add(bytes(data))
        elif name == "CANCEL":
            self.subscriptions.discard(bytes(data))
        elif name == "ERROR":
            raise ProtocolError("peer error")

    def queue(self, data):
        if self.closed:
            return
        self.outbuf += data
        self.owner.loop.want_write(self)


class Bound:
    """A listening socket and its peers; subclasses give it a ZeroMQ type."""

    kind = ""

    def __init__(self, loop, ip, port):
        self.loop = loop
        self.connections = []
        family = socket.AF_INET6 if ":" in ip else socket.AF_INET
        self.listener = socket.socket(family, socket.SOCK_STREAM)
        if os.name != "nt":
            self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.listener.bind((ip, port))
        self.listener.listen(16)
        self.listener.setblocking(False)
        self.port = self.listener.getsockname()[1]
        loop.add_listener(self)

    def on_ready(self, conn):
        pass

    def on_message(self, conn, frames):
        pass

    def on_close(self, conn):
        pass


class Router(Bound):
    """Requests in with their sender's identity first; replies routed back by it."""

    kind = "ROUTER"

    def __init__(self, loop, ip, port, handler):
        super().__init__(loop, ip, port)
        self.handler = handler
        self.by_identity = {}
        self.latest = None

    def on_ready(self, conn):
        if not conn.identity:
            conn.identity = b"\x00" + os.urandom(4)  # as libzmq names an anonymous peer
        self.by_identity[conn.identity] = conn
        self.latest = conn

    def on_message(self, conn, frames):
        self.handler([conn.identity] + frames)

    def on_close(self, conn):
        if self.by_identity.get(conn.identity) is conn:
            del self.by_identity[conn.identity]
        if self.latest is conn:
            self.latest = self.connections[-1] if self.connections else None

    def send(self, frames):
        """frames[0] is the identity to route to (dropped if unknown, as ROUTER does)."""
        data = encode(frames[1:])
        identity = bytes(frames[0])
        self.loop.call_soon(lambda: self._route(identity, data))

    def send_latest(self, frames):
        data = encode(frames)
        self.loop.call_soon(lambda: self.latest is not None and self.latest.queue(data))

    def _route(self, identity, data):
        conn = self.by_identity.get(identity)
        if conn is not None:
            conn.queue(data)

    def has(self, identity):
        return identity in self.by_identity


class Pub(Bound):
    """Broadcasts to every peer subscribed to a prefix of the first frame."""

    kind = "PUB"

    def on_message(self, conn, frames):
        # ZMTP 3.0 subscriptions are messages: 1 then the topic, 0 to cancel
        if frames and frames[0]:
            head, topic = frames[0][0], bytes(frames[0][1:])
            if head == 1:
                conn.subscriptions.add(topic)
            elif head == 0:
                conn.subscriptions.discard(topic)

    def send(self, frames):
        topic = bytes(frames[0]) if frames else b""
        data = encode(frames)
        self.loop.call_soon(lambda: self._broadcast(topic, data))

    def _broadcast(self, topic, data):
        for conn in self.connections:
            if conn.state != "traffic" or len(conn.outbuf) > PUB_HIGH_WATER:
                continue
            if any(topic.startswith(s) for s in conn.subscriptions):
                conn.queue(data)


class Rep(Bound):
    """The heartbeat: every message comes back as it came."""

    kind = "REP"

    def on_message(self, conn, frames):
        conn.queue(encode(frames))


class Loop(threading.Thread):
    """The one thread that reads and writes every socket."""

    def __init__(self):
        super().__init__(name="sphere-kernel-io", daemon=True)
        self.selector = selectors.DefaultSelector()
        self._wake_r, self._wake_w = socket.socketpair()
        self._wake_r.setblocking(False)
        self._wake_w.setblocking(False)
        self.selector.register(self._wake_r, selectors.EVENT_READ, ("wake", None))
        self._pending = collections.deque()
        self._stopped = False
        self._idle = threading.Event()

    # -- from any thread ------------------------------------------------------

    def call_soon(self, fn):
        self._pending.append(fn)
        try:
            self._wake_w.send(b"\x00")
        except (BlockingIOError, InterruptedError, OSError):
            pass

    def stop(self):
        self._stopped = True
        self.call_soon(lambda: None)

    def drain(self, timeout=2.0):
        """Waits until everything queued has been written, or the timeout."""
        done = threading.Event()

        def check():
            if not self._pending and not any(c.outbuf for b in self._bound for c in b.connections):
                done.set()
            elif not self._stopped:
                threading.Timer(0.02, lambda: self.call_soon(check)).start()

        self.call_soon(check)
        done.wait(timeout)

    # -- the loop's own thread -------------------------------------------------

    _bound = ()

    def add_listener(self, bound):
        self._bound = tuple(self._bound) + (bound,)
        self.selector.register(bound.listener, selectors.EVENT_READ, ("accept", bound))

    def want_write(self, conn):
        if conn.closed or conn.writing:
            return
        self._flush(conn)
        if conn.outbuf and not conn.closed:
            conn.writing = True
            self.selector.modify(conn.sock, selectors.EVENT_READ | selectors.EVENT_WRITE, ("conn", conn))

    def run(self):
        while not self._stopped:
            try:
                events = self.selector.select(timeout=1.0)
            except (OSError, ValueError):
                continue
            for key, mask in events:
                what, obj = key.data
                if what == "wake":
                    try:
                        while self._wake_r.recv(4096):
                            pass
                    except (BlockingIOError, InterruptedError, OSError):
                        pass
                elif what == "accept":
                    self._accept(obj)
                else:
                    if mask & selectors.EVENT_READ:
                        self._read(obj)
                    if mask & selectors.EVENT_WRITE and not obj.closed:
                        self._flush(obj)
                        if not obj.outbuf and not obj.closed:
                            obj.writing = False
                            self.selector.modify(obj.sock, selectors.EVENT_READ, ("conn", obj))
            while self._pending:
                fn = self._pending.popleft()
                try:
                    fn()
                except Exception:  # one failing send must not stop the I/O
                    pass

    def _accept(self, bound):
        while True:
            try:
                sock, _ = bound.listener.accept()
            except (BlockingIOError, InterruptedError):
                return
            except OSError:
                return
            sock.setblocking(False)
            try:
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            except OSError:
                pass
            conn = Connection(sock, bound)
            bound.connections.append(conn)
            self.selector.register(sock, selectors.EVENT_READ, ("conn", conn))
            # The whole greeting and READY at once: a libzmq peer reads them as they come.
            conn.outbuf += greeting(0) + ready(bound.kind)
            self.want_write(conn)

    def _read(self, conn):
        try:
            data = conn.sock.recv(1 << 16)
        except (BlockingIOError, InterruptedError):
            return
        except OSError:
            data = b""
        if not data:
            self._close(conn)
            return
        try:
            messages = conn.feed(data)
        except ProtocolError:
            self._close(conn)
            return
        for frames in messages:
            try:
                conn.owner.on_message(conn, frames)
            except Exception:
                pass

    def _flush(self, conn):
        while conn.outbuf:
            try:
                sent = conn.sock.send(conn.outbuf)
            except (BlockingIOError, InterruptedError):
                return
            except OSError:
                self._close(conn)
                return
            if sent <= 0:
                return
            del conn.outbuf[:sent]

    def _close(self, conn):
        if conn.closed:
            return
        conn.closed = True
        try:
            self.selector.unregister(conn.sock)
        except (KeyError, ValueError, OSError):
            pass
        try:
            conn.sock.close()
        except OSError:
            pass
        owner = conn.owner
        if conn in owner.connections:
            owner.connections.remove(conn)
        owner.on_close(conn)
