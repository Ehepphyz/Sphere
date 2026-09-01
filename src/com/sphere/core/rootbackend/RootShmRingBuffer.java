package com.sphere.core.rootbackend;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/**
* Low-latency Shared Memory (SHM) Ring Buffer implementation for inter-process communications.
* Uses explicit FFM memory ordering barriers for zero-copy lock-free polling.
*/
public final class RootShmRingBuffer {

    // Direct VarHandle for atomic off-heap memory ordering access
    private static final VarHandle LONG_HANDLE = ValueLayout.JAVA_LONG.varHandle();

    private final MemorySegment ring;
    private final long capacity;
    private final long mask;

    public RootShmRingBuffer(MemorySegment ringSegment, long capacity) {
        if (capacity <= 0 || Long.bitCount(capacity) != 1) {
            throw new IllegalArgumentException(
                "Ring capacity must be a power of two, got " + capacity
                + ". The partition table stores a slot COUNT, not an exponent.");
        }
        this.ring = ringSegment;
        this.capacity = capacity;
        this.mask = capacity - 1L;
    }

    public boolean isInitialized() {
        return (long) LONG_HANDLE.getAcquire(ring, RootBackend.RING_INIT_MAGIC_OFFSET)
               == RootBackend.RING_INIT_MAGIC;
    }

    public long capacity() {
        return capacity;
    }

    public long sizeApprox() {
        // Acquire memory semantics for volatile atomic read across processes
        long enq = (long) LONG_HANDLE.getAcquire(ring, RootBackend.RING_ENQUEUE_POS_OFFSET);
        long deq = (long) LONG_HANDLE.getAcquire(ring, RootBackend.RING_DEQUEUE_POS_OFFSET);
        return Math.max(0L, enq - deq);
    }

    public boolean tryPollResponse(ResponseConsumer consumer) {
        long pos = (long) LONG_HANDLE.getVolatile(ring, RootBackend.RING_DEQUEUE_POS_OFFSET);

        for (;;) {
            long cellOffset = RootBackend.RING_BUFFER_BASE_OFFSET + ((pos & mask) * RootBackend.CELL_SIZE);
            long seqOffset = cellOffset + RootBackend.CELL_SEQ_OFFSET;
            long dataOffset = cellOffset + RootBackend.CELL_DATA_OFFSET;

            long seq = (long) LONG_HANDLE.getVolatile(ring, seqOffset);
            long dif = seq - (pos + 1L);

            if (dif == 0) {
                long witness = (long) LONG_HANDLE.compareAndExchange(
                    ring, RootBackend.RING_DEQUEUE_POS_OFFSET, pos, pos + 1L);

                if (witness == pos) {
                    consumer.accept(ring.asSlice(dataOffset, RootBackend.BRIDGE_MESSAGE_SIZE));
                    LONG_HANDLE.setRelease(ring, seqOffset, pos + mask + 1L);
                    return true;
                }
                pos = witness;
            } else if (dif < 0) {
                return false; // empty
            } else {
                pos = (long) LONG_HANDLE.getVolatile(ring, RootBackend.RING_DEQUEUE_POS_OFFSET);
            }
            Thread.onSpinWait();
        }
    }

    @FunctionalInterface
    public interface ResponseConsumer {
        void accept(MemorySegment message);
    }
}
