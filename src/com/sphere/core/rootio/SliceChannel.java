package com.sphere.core.rootio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;

/** A window [start, start+size) of another channel, read as a channel of its own (a member of an archive). */
public final class SliceChannel implements SeekableByteChannel {

    private final SeekableByteChannel base;
    private final long start;
    private final long size;
    private long position;

    public SliceChannel(SeekableByteChannel base, long start, long size) {
        this.base = base;
        this.start = start;
        this.size = size;
    }

    @Override
    public synchronized int read(ByteBuffer dst) throws IOException {
        if (position >= size) {
            return -1;
        }
        final int room = (int) Math.min(dst.remaining(), size - position);
        final ByteBuffer view = dst.duplicate();
        view.limit(view.position() + room);
        base.position(start + position);
        final int n = base.read(view);
        if (n > 0) {
            dst.position(dst.position() + n);
            position += n;
        }
        return n;
    }

    @Override
    public int write(ByteBuffer src) {
        throw new NonWritableChannelException();
    }

    @Override
    public synchronized long position() {
        return position;
    }

    @Override
    public synchronized SeekableByteChannel position(long newPosition) {
        position = newPosition;
        return this;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public SeekableByteChannel truncate(long newSize) {
        throw new NonWritableChannelException();
    }

    @Override
    public boolean isOpen() {
        return base.isOpen();
    }

    @Override
    public void close() throws IOException {
        base.close();
    }
}
