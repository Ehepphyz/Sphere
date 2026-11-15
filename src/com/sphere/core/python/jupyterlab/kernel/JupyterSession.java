package com.sphere.core.python.jupyterlab.kernel;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Jupyter messaging protocol (version 5.3) on top of ZMTP frames: the
 * delimiter, the HMAC-SHA256 signature over header, parent, metadata and
 * content, and the four JSON frames themselves. One session per kernel
 * connection, as jupyter_client keeps it.
 */
final class JupyterSession {

    static final String PROTOCOL_VERSION = "5.3";
    private static final byte[] DELIMITER = "<IDS|MSG>".getBytes(StandardCharsets.US_ASCII);

    /** One message off the wire. */
    record Message(String type, Map<String, Object> header, Map<String, Object> parent,
                   Map<String, Object> metadata, Map<String, Object> content, List<byte[]> buffers) {

        String id() {
            return Json.string(header.get("msg_id"), "");
        }

        String parentId() {
            return Json.string(parent.get("msg_id"), "");
        }
    }

    final String sessionId = UUID.randomUUID().toString();
    private final byte[] key;
    private final ThreadLocal<Mac> mac;
    private final HexFormat hex = HexFormat.of();

    JupyterSession(String key) {
        this.key = key == null ? new byte[0] : key.getBytes(StandardCharsets.US_ASCII);
        this.mac = ThreadLocal.withInitial(() -> {
            if (this.key.length == 0) {
                return null;
            }
            try {
                Mac m = Mac.getInstance("HmacSHA256");
                m.init(new SecretKeySpec(this.key, "HmacSHA256"));
                return m;
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("HMAC-SHA256 unavailable", e);
            }
        });
    }

    /** The frames of a request; {@code header} receives the msg_id chosen. */
    List<byte[]> frames(String type, Map<String, Object> content, Map<String, Object> parent,
                        Map<String, Object> headerOut) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("msg_id", UUID.randomUUID().toString());
        header.put("session", sessionId);
        header.put("username", "sphere");
        header.put("date", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        header.put("msg_type", type);
        header.put("version", PROTOCOL_VERSION);
        if (headerOut != null) {
            headerOut.putAll(header);
        }
        byte[] h = Json.encode(header).getBytes(StandardCharsets.UTF_8);
        byte[] p = Json.encode(parent == null ? Map.of() : parent).getBytes(StandardCharsets.UTF_8);
        byte[] m = "{}".getBytes(StandardCharsets.UTF_8);
        byte[] c = Json.encode(content == null ? Map.of() : content).getBytes(StandardCharsets.UTF_8);
        List<byte[]> frames = new ArrayList<>(6);
        frames.add(DELIMITER);
        frames.add(sign(h, p, m, c).getBytes(StandardCharsets.US_ASCII));
        frames.add(h);
        frames.add(p);
        frames.add(m);
        frames.add(c);
        return frames;
    }

    /**
     * Reads a message, or returns null when it is not one of this session's: no
     * delimiter, or a signature that does not match the key. A kernel on a local
     * port can be reached by any process of the machine; the signature is what
     * says the message really came from the kernel Sphere started.
     */
    Message parse(List<byte[]> frames) {
        int d = -1;
        for (int i = 0; i < frames.size(); i++) {
            if (java.util.Arrays.equals(frames.get(i), DELIMITER)) {
                d = i;
                break;
            }
        }
        if (d < 0 || frames.size() < d + 6) {
            return null;
        }
        byte[] signature = frames.get(d + 1);
        byte[] h = frames.get(d + 2);
        byte[] p = frames.get(d + 3);
        byte[] m = frames.get(d + 4);
        byte[] c = frames.get(d + 5);
        if (key.length > 0) {
            byte[] expected = sign(h, p, m, c).getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(expected, signature)) {
                return null;
            }
        }
        Map<String, Object> header = Json.object(new String(h, StandardCharsets.UTF_8));
        List<byte[]> buffers = frames.size() > d + 6
                ? new ArrayList<>(frames.subList(d + 6, frames.size())) : List.of();
        return new Message(Json.string(header.get("msg_type"), ""), header,
                           Json.object(new String(p, StandardCharsets.UTF_8)),
                           Json.object(new String(m, StandardCharsets.UTF_8)),
                           Json.object(new String(c, StandardCharsets.UTF_8)), buffers);
    }

    private String sign(byte[]... parts) {
        Mac m = mac.get();
        if (m == null) {
            return "";
        }
        m.reset();
        for (byte[] part : parts) {
            m.update(part);
        }
        return hex.formatHex(m.doFinal());
    }
}
