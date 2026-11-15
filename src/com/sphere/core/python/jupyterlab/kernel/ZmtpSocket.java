package com.sphere.core.python.jupyterlab.kernel;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * A ZeroMQ socket written out by hand: ZMTP 3.0 over TCP with the NULL security
 * mechanism, which is all a Jupyter kernel on this machine speaks. Two roles are
 * enough for a notebook client: DEALER, for the request channels (shell, stdin,
 * control), and SUB, for the kernel's broadcasts (iopub).
 *
 * Sphere carries no native library and no jar beyond the JDK, so this is what
 * lets it drive an ipykernel, or any other Jupyter kernel, without pyzmq on its
 * own side. The protocol is RFC 23 (ZMTP 3.0): a 64-byte greeting, one READY
 * command each way, then frames of a flag byte, a size and a body.
 */
public final class ZmtpSocket implements Closeable {

    public enum Type { DEALER, SUB }

    private static final int FLAG_MORE = 0x01;
    private static final int FLAG_LONG = 0x02;
    private static final int FLAG_COMMAND = 0x04;

    /** A message larger than this is refused rather than allocated: no kernel output is that big. */
    private static final long MAX_FRAME = 1L << 30;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final Type type;
    private final Object writeLock = new Object();
    private volatile boolean closed;

    private ZmtpSocket(Socket socket, Type type) throws IOException {
        this.socket = socket;
        this.type = type;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16));
        this.out = new BufferedOutputStream(socket.getOutputStream(), 1 << 16);
    }

    /**
     * Connects and completes the handshake. The kernel binds its ports only once
     * it has started, which takes a second or two, so a refused connection is
     * retried until the deadline or until {@code alive} says the process is gone.
     */
    public static ZmtpSocket connect(String host, int port, Type type, byte[] identity,
                                     long deadlineMillis, BooleanSupplier alive) throws IOException {
        IOException last = null;
        while (true) {
            Socket s = new Socket();
            try {
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(host, port), 2000);
                ZmtpSocket z = new ZmtpSocket(s, type);
                z.handshake(identity);
                return z;
            } catch (IOException e) {
                last = e;
                try {
                    s.close();
                } catch (IOException ignored) {
                    // nothing to release
                }
                if (System.currentTimeMillis() > deadlineMillis || (alive != null && !alive.getAsBoolean())) {
                    throw last;
                }
                try {
                    Thread.sleep(40);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
    }

    private void handshake(byte[] identity) throws IOException {
        socket.setSoTimeout(10_000);
        byte[] greeting = new byte[64];
        greeting[0] = (byte) 0xFF;
        greeting[8] = 1;           // padding read as a length by ZMTP 1.0 peers
        greeting[9] = 0x7F;
        greeting[10] = 3;          // version 3.0: subscriptions travel as messages
        greeting[11] = 0;
        byte[] mechanism = "NULL".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(mechanism, 0, greeting, 12, mechanism.length);
        greeting[32] = 0;          // as-server: no, this side connects
        synchronized (writeLock) {
            out.write(greeting);
            out.flush();
        }

        byte[] peer = new byte[64];
        in.readFully(peer, 0, 10);
        if ((peer[0] & 0xFF) != 0xFF || (peer[9] & 0x01) == 0) {
            throw new IOException("the peer does not speak ZMTP 2 or later");
        }
        in.readFully(peer, 10, 54);
        if (peer[10] < 3) {
            throw new IOException("the peer speaks ZMTP " + peer[10] + ", older than 3.0");
        }
        String theirs = new String(peer, 12, 20, StandardCharsets.US_ASCII).trim();
        if (!"NULL".equals(theirs)) {
            throw new IOException("the kernel asks for the " + theirs + " security mechanism");
        }

        Map<String, byte[]> properties = new LinkedHashMap<>();
        properties.put("Socket-Type", type.name().getBytes(StandardCharsets.US_ASCII));
        if (type == Type.DEALER) {
            properties.put("Identity", identity == null ? new byte[0] : identity);
        }
        sendCommand("READY", properties);

        Frame ready = readFrame();
        if (!ready.isCommand()) {
            throw new IOException("the kernel sent a message before its READY");
        }
        String name = commandName(ready.data);
        if ("ERROR".equals(name)) {
            int len = ready.data.length > 6 ? ready.data[6] & 0xFF : 0;
            String reason = new String(ready.data, Math.min(7, ready.data.length),
                                       Math.min(len, Math.max(0, ready.data.length - 7)),
                                       StandardCharsets.UTF_8);
            throw new IOException("the kernel refused the connection: " + reason);
        }
        if (!"READY".equals(name)) {
            throw new IOException("expected READY from the kernel, got " + name);
        }
        socket.setSoTimeout(0);
        if (type == Type.SUB) {
            // ZMTP 3.0 subscription: a one-frame message, 1 then the topic; empty is everything.
            send(List.of(new byte[] { 1 }));
        }
    }

    private void sendCommand(String name, Map<String, byte[]> properties) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        body.write(n.length);
        body.write(n);
        for (Map.Entry<String, byte[]> p : properties.entrySet()) {
            byte[] key = p.getKey().getBytes(StandardCharsets.US_ASCII);
            body.write(key.length);
            body.write(key);
            byte[] value = p.getValue();
            body.write((value.length >>> 24) & 0xFF);
            body.write((value.length >>> 16) & 0xFF);
            body.write((value.length >>> 8) & 0xFF);
            body.write(value.length & 0xFF);
            body.write(value);
        }
        writeFrames(List.of(body.toByteArray()), FLAG_COMMAND);
    }

    /** Sends one multipart message. Safe to call from several threads. */
    public void send(List<byte[]> frames) throws IOException {
        writeFrames(frames, 0);
    }

    private void writeFrames(List<byte[]> frames, int baseFlags) throws IOException {
        if (closed) {
            throw new IOException("socket closed");
        }
        synchronized (writeLock) {
            for (int i = 0; i < frames.size(); i++) {
                byte[] data = frames.get(i);
                int flags = baseFlags | (i < frames.size() - 1 ? FLAG_MORE : 0);
                if (data.length > 255) {
                    out.write(flags | FLAG_LONG);
                    long size = data.length;
                    for (int shift = 56; shift >= 0; shift -= 8) {
                        out.write((int) (size >>> shift) & 0xFF);
                    }
                } else {
                    out.write(flags);
                    out.write(data.length);
                }
                out.write(data);
            }
            out.flush();
        }
    }

    /** Blocks until a whole message arrives. Commands met on the way are answered or skipped. */
    public List<byte[]> receive() throws IOException {
        List<byte[]> parts = new ArrayList<>(8);
        while (true) {
            Frame frame = readFrame();
            if (frame.isCommand()) {
                answer(frame.data);
                continue;
            }
            parts.add(frame.data);
            if ((frame.flags & FLAG_MORE) == 0) {
                return parts;
            }
        }
    }

    /** PING belongs to ZMTP 3.1, which this side does not announce; answered all the same. */
    private void answer(byte[] command) throws IOException {
        if ("PING".equals(commandName(command)) && command.length >= 7) {
            byte[] context = java.util.Arrays.copyOfRange(command, 7, command.length);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(4);
            body.write("PONG".getBytes(StandardCharsets.US_ASCII));
            body.write(context);
            writeFrames(List.of(body.toByteArray()), FLAG_COMMAND);
        }
    }

    private Frame readFrame() throws IOException {
        int flags = in.read();
        if (flags < 0) {
            throw new EOFException("the kernel closed the connection");
        }
        long size = (flags & FLAG_LONG) != 0 ? in.readLong() : in.readUnsignedByte();
        if (size < 0 || size > MAX_FRAME) {
            throw new IOException("frame of " + size + " bytes refused");
        }
        byte[] data = new byte[(int) size];
        in.readFully(data);
        return new Frame(flags, data);
    }

    private static String commandName(byte[] body) {
        if (body.length == 0) {
            return "";
        }
        int len = body[0] & 0xFF;
        return new String(body, 1, Math.min(len, body.length - 1), StandardCharsets.US_ASCII);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // already gone
        }
    }

    private record Frame(int flags, byte[] data) {
        boolean isCommand() {
            return (flags & FLAG_COMMAND) != 0;
        }
    }
}
