package com.sphere.core.rootbackend;

import com.sphere.utils.AppLogger;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;

/**
 * Manages the explicit off-heap lifecycle and memory-mapping of shared memory files.
 */
public final class RootShmSegment implements AutoCloseable {
    private final Arena arena;
    private final MemorySegment segment;

    private RootShmSegment(Arena arena, MemorySegment segment) {
        this.arena = arena;
        this.segment = segment;
    }

    /**
     * Maps a shared memory file region off-heap using Java FFM Arenas
     */
    public static RootShmSegment openSharedMemory(File shmPath, long size) throws Exception {
        Arena arena = Arena.ofShared();
        
        try (RandomAccessFile raf = new RandomAccessFile(shmPath, "rw");
             FileChannel channel = raf.getChannel()) {
            
            MemorySegment segment = channel.map(
                FileChannel.MapMode.READ_WRITE, 
                0, 
                size, 
                arena
            );
            
            return new RootShmSegment(arena, segment);
        } catch (Exception e) {
            if (arena.scope().isAlive()) {
                arena.close();
            }
            AppLogger.error("Failed to map shared memory file: " + shmPath.getAbsolutePath(), e);
            throw e;
        }
    }

    public MemorySegment segment() {
        return segment;
    }

    @Override
    public void close() {
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}