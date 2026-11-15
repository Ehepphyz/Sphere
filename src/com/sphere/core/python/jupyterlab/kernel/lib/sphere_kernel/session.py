"""The Jupyter messaging protocol (5.4) over ZMTP frames.

A message is: the routing identities, the delimiter <IDS|MSG>, an HMAC of the
four JSON parts, then header, parent header, metadata and content, then any
binary buffers. The key comes from the connection file; a message whose
signature does not match it is dropped, since any process on the machine can
reach a local port.
"""

import base64
import datetime
import hashlib
import hmac
import json
import os
import uuid

DELIMITER = b"<IDS|MSG>"
PROTOCOL_VERSION = "5.4"


def _default(obj):
    if isinstance(obj, (datetime.datetime, datetime.date)):
        return obj.isoformat()
    if isinstance(obj, (bytes, bytearray, memoryview)):
        return base64.b64encode(bytes(obj)).decode("ascii")
    if isinstance(obj, (set, frozenset, tuple)):
        return list(obj)
    try:
        import numpy  # a value from a user expression or a display's metadata
        if isinstance(obj, numpy.generic):
            return obj.item()
        if isinstance(obj, numpy.ndarray):
            return obj.tolist()
    except Exception:
        pass
    return repr(obj)


def dumps(obj):
    return json.dumps(obj, default=_default, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")


class Session:

    def __init__(self, key=b"", scheme="hmac-sha256", username="sphere"):
        if isinstance(key, str):
            key = key.encode("ascii")
        self.key = key or b""
        algorithm = scheme.split("-", 1)[1] if "-" in scheme else "sha256"
        self.digestmod = getattr(hashlib, algorithm, hashlib.sha256)
        self.session = str(uuid.uuid4())
        self.username = username or os.environ.get("USER") or os.environ.get("USERNAME") or "sphere"

    def sign(self, parts):
        if not self.key:
            return b""
        mac = hmac.new(self.key, digestmod=self.digestmod)
        for part in parts:
            mac.update(part)
        return mac.hexdigest().encode("ascii")

    def header(self, msg_type):
        return {"msg_id": uuid.uuid4().hex, "session": self.session, "username": self.username,
                "date": now(), "msg_type": msg_type, "version": PROTOCOL_VERSION}

    def serialize(self, msg_type, content, parent=None, metadata=None, idents=(), buffers=()):
        """The frames of a message; parent is the request's header it answers, if any."""
        parts = [dumps(self.header(msg_type)), dumps(parent or {}), dumps(metadata or {}), dumps(content or {})]
        return list(idents) + [DELIMITER, self.sign(parts)] + parts + list(buffers)

    def deserialize(self, frames):
        """(idents, message dict) of a message off the wire; ValueError when it is not a valid one."""
        try:
            d = frames.index(DELIMITER)
        except ValueError:
            raise ValueError("no delimiter")
        if len(frames) < d + 6:
            raise ValueError("truncated message")
        idents = frames[:d]
        signature = frames[d + 1]
        parts = frames[d + 2:d + 6]
        if self.key and not hmac.compare_digest(self.sign(parts), bytes(signature)):
            raise ValueError("bad signature")
        header, parent, metadata, content = (json.loads(p.decode("utf-8")) if p else {} for p in parts)
        return idents, {"header": header, "parent_header": parent, "metadata": metadata,
                        "content": content, "buffers": frames[d + 6:],
                        "msg_type": header.get("msg_type", "")}
