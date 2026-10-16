package com.sphere.core.rootio;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A remote file read through HTTP range requests, as ROOT's TWebFile and
 * TDavixFile read one: only the bytes asked for travel, so the keys of a
 * multi-gigabyte file on the CERN Open Data portal, or one branch of its
 * tree, come without downloading the file.
 *
 * Reads go through a cache of fixed blocks. A read that misses fetches all
 * its missing blocks in one request; reads that follow one another (a basket
 * after the previous one) grow a read-ahead window, up to a few megabytes, so
 * a sequential scan costs few round trips while a random access to the key
 * list costs one small request. Transient failures are retried with back-off.
 */
public final class HttpRangeChannel implements SeekableByteChannel {

    private static final int BLOCK = 64 * 1024;
    private static final int MAX_CACHED_BLOCKS = 512;          // 32 MB
    private static final int MAX_READ_AHEAD_BLOCKS = 64;       // 4 MB
    private static final int ATTEMPTS = 3;

    private final HttpClient client;
    private final URI uri;
    private final long size;
    private long position;
    private boolean open = true;

    private final Map<Long, byte[]> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
            return size() > MAX_CACHED_BLOCKS;
        }
    };

    private long lastBlockRead = -2;
    private int readAhead = 1;

    /** How many requests went out and how many bytes came back, for the report. */
    private long requests;
    private long bytesFetched;

    private HttpRangeChannel(HttpClient client, URI uri, long size) {
        this.client = client;
        this.uri = uri;
        this.size = size;
    }

    /** Opens a remote file: asks its size, and refuses a server that cannot serve ranges. */
    public static HttpRangeChannel open(URI uri) throws IOException {
        final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
        final HttpRequest probe = HttpRequest.newBuilder(uri)
            .header("Range", "bytes=0-0")
            .header("User-Agent", "Sphere-ROOT-Lab")
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build();
        HttpResponse<byte[]> response = send(client, probe);
        final URI finalUri = response.uri();
        if (response.statusCode() == 206) {
            final long total = totalFromContentRange(response.headers().firstValue("Content-Range"));
            if (total < 0) {
                throw new IOException(uri + ": the server answered a range without saying the file's size");
            }
            return new HttpRangeChannel(client, finalUri, total);
        }
        if (response.statusCode() == 200) {
            throw new IOException(uri + ": the server does not serve byte ranges (it sent the whole file)");
        }
        throw new IOException(uri + ": HTTP " + response.statusCode());
    }

    private static long totalFromContentRange(Optional<String> header) {
        // Content-Range: bytes 0-0/12345
        if (header.isEmpty()) {
            return -1;
        }
        final String v = header.get();
        final int slash = v.lastIndexOf('/');
        if (slash < 0) {
            return -1;
        }
        try {
            return Long.parseLong(v.substring(slash + 1).strip());
        } catch (NumberFormatException unknown) {
            return -1;
        }
    }

    private static HttpResponse<byte[]> send(HttpClient client, HttpRequest request) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                final HttpResponse<byte[]> r = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() >= 500 && attempt < ATTEMPTS) {
                    last = new IOException("HTTP " + r.statusCode());
                } else {
                    return r;
                }
            } catch (IOException failed) {
                last = failed;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while reading " + request.uri());
            }
            try {
                Thread.sleep(250L * attempt * attempt);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while reading " + request.uri());
            }
        }
        throw last == null ? new IOException("no answer from " + request.uri()) : last;
    }

    public URI uri() {
        return uri;
    }

    public long requests() {
        return requests;
    }

    public long bytesFetched() {
        return bytesFetched;
    }

    // ---- SeekableByteChannel ------------------------------------------------------

    @Override
    public synchronized int read(ByteBuffer dst) throws IOException {
        ensureOpen();
        if (position >= size) {
            return -1;
        }
        final int wanted = (int) Math.min(dst.remaining(), size - position);
        if (wanted == 0) {
            return 0;
        }
        final long firstBlock = position / BLOCK;
        final long lastBlock = (position + wanted - 1) / BLOCK;
        fetch(firstBlock, lastBlock);
        int copied = 0;
        while (copied < wanted) {
            final long at = position + copied;
            final long blockIndex = at / BLOCK;
            byte[] block = cache.get(blockIndex);
            if (block == null) {
                fetch(blockIndex, blockIndex);
                block = cache.get(blockIndex);
            }
            final int offset = (int) (at - blockIndex * BLOCK);
            final int n = Math.min(wanted - copied, block.length - offset);
            dst.put(block, offset, n);
            copied += n;
        }
        position += copied;
        return copied;
    }

    /** Makes sure blocks [first, last] are cached, plus the read-ahead when reading goes forward. */
    private void fetch(long first, long last) throws IOException {
        final long blocks = (size + BLOCK - 1) / BLOCK;
        if (first == lastBlockRead || first == lastBlockRead + 1) {
            readAhead = Math.min(MAX_READ_AHEAD_BLOCKS, readAhead * 2);
        } else {
            readAhead = 1;
        }
        lastBlockRead = last;
        final long until = Math.min(blocks - 1, last + readAhead - 1);
        long b = first;
        while (b <= until) {
            if (cache.containsKey(b)) {
                b++;
                continue;
            }
            final long runStart = b;
            while (b <= until && !cache.containsKey(b)) {
                b++;
            }
            request(runStart, b - 1);              // one request per run of missing blocks
        }
    }

    private void request(long firstBlock, long lastBlock) throws IOException {
        final long from = firstBlock * BLOCK;
        final long to = Math.min(size, (lastBlock + 1) * BLOCK) - 1;
        final HttpRequest request = HttpRequest.newBuilder(uri)
            .header("Range", "bytes=" + from + "-" + to)
            .header("User-Agent", "Sphere-ROOT-Lab")
            .timeout(Duration.ofSeconds(120))
            .GET()
            .build();
        final HttpResponse<byte[]> response = send(client, request);
        requests++;
        final byte[] body = response.body();
        final byte[] data;
        if (response.statusCode() == 206) {
            data = body;
        } else if (response.statusCode() == 200 && body.length == size) {
            // a server that ignored the range: keep what was asked for
            data = java.util.Arrays.copyOfRange(body, (int) from, (int) to + 1);
        } else {
            throw new IOException(uri + ": HTTP " + response.statusCode() + " for bytes " + from + "-" + to);
        }
        if (data.length != to - from + 1) {
            throw new IOException(uri + ": asked for " + (to - from + 1) + " bytes, got " + data.length);
        }
        bytesFetched += data.length;
        for (long b = firstBlock; b <= lastBlock; b++) {
            final int offset = (int) ((b - firstBlock) * BLOCK);
            final int length = Math.min(BLOCK, data.length - offset);
            cache.put(b, java.util.Arrays.copyOfRange(data, offset, offset + length));
        }
    }

    @Override
    public int write(ByteBuffer src) {
        throw new NonWritableChannelException();
    }

    @Override
    public synchronized long position() throws IOException {
        ensureOpen();
        return position;
    }

    @Override
    public synchronized SeekableByteChannel position(long newPosition) throws IOException {
        ensureOpen();
        if (newPosition < 0) {
            throw new IllegalArgumentException("negative position");
        }
        position = newPosition;
        return this;
    }

    @Override
    public long size() throws IOException {
        ensureOpen();
        return size;
    }

    @Override
    public SeekableByteChannel truncate(long newSize) {
        throw new NonWritableChannelException();
    }

    @Override
    public synchronized boolean isOpen() {
        return open;
    }

    @Override
    public synchronized void close() {
        open = false;
        cache.clear();
    }

    private void ensureOpen() throws IOException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s (%d bytes, %d requests, %.1f kB fetched)",
                             uri, size, requests, bytesFetched / 1024.0);
    }
}
