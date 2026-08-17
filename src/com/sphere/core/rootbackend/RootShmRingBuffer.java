package com.sphere.core.rootbackend;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/**
 * Low-latency Shared Memory (SHM) Ring Buffer implementation for inter-process communications.
 * Uses explicit FFM memory ordering barriers for zero-copy lock-free polling.
 */
public final class RootShmRingBuffer {
    private static final long HEAD_OFFSET = 0L;
    private static final long TAIL_OFFSET = 8L;
    private static final long CAPACITY_OFFSET = 16L;
    private static final long PAYLOAD_START_OFFSET = 64L; // Cache-line aligned (64 bytes)
    private static final long SLOT_SIZE = 256L;

    // Direct VarHandle for atomic off-heap memory ordering access
    private static final VarHandle LONG_HANDLE = ValueLayout.JAVA_LONG.varHandle();

    private final MemorySegment shmSegment;
    private final long capacity;

    public RootShmRingBuffer(MemorySegment shmSegment) {
        this.shmSegment = shmSegment;
        this.capacity = shmSegment.get(ValueLayout.JAVA_LONG, CAPACITY_OFFSET);
    }

    /**
     * Polls the next available response from the ring buffer in a lock-free manner
     */
    public boolean tryPollResponse(ResponseConsumer consumer) {
        // Acquire memory semantics for volatile atomic read across processes
        long currentHead = (long) LONG_HANDLE.getAcquire(shmSegment, HEAD_OFFSET);
        long currentTail = (long) LONG_HANDLE.getAcquire(shmSegment, TAIL_OFFSET);

        if (currentHead == currentTail) {
            return false;
        }

        long slotIndex = currentHead % capacity;
        long slotOffset = PAYLOAD_START_OFFSET + (slotIndex * SLOT_SIZE);

        MemorySegment slotSegment = shmSegment.asSlice(slotOffset, SLOT_SIZE);

        consumer.accept(slotSegment);

        // Release memory semantics to update head sequence index safely
        LONG_HANDLE.setRelease(shmSegment, HEAD_OFFSET, currentHead + 1);
        return true;
    }

    @FunctionalInterface
    public interface ResponseConsumer {
        void accept(MemorySegment payload);
    }
}