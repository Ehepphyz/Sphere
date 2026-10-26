package com.sphere.core.geant4;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Tessellations shared by every solid of the same shape (see Solid.Shape), whatever its name or document.
 *
 * An undo reads the document back from GDML into new solids, which found no mesh of their own and were all
 * tessellated again, booleans' CSG included: tens of seconds of CPU for a big geometry after each Ctrl+Z.
 * A ROOT geometry names a thousand crystals of one size a thousand times, and each was tessellated. Here a
 * shape is tessellated once, and a second solid asking for it while it is being made waits for that one
 * rather than making it again.
 *
 * Bounded in bytes, the least recently used out first. A mesh a live solid holds stays with that solid when
 * it leaves the cache: what is bounded is what is kept for solids that may come back (undo, reopen).
 */
final class MeshCache {

    private MeshCache() {
    }

    private record Key(Solid.Shape shape, int quality) {
    }

    /** A quarter of the heap, at most 768 MB: the cache is what an undo saves, never what a frame needs. */
    private static final long BUDGET = Math.min(Runtime.getRuntime().maxMemory() / 4, 768L << 20);

    private static final LinkedHashMap<Key, Mesh> MESHES = new LinkedHashMap<>(256, 0.75f, true);
    private static final Map<Key, CompletableFuture<Mesh>> MAKING = new HashMap<>();
    private static long bytes;

    /** The mesh of a shape at a quality if one was made, or null; makes nothing. */
    static Mesh peek(Solid.Shape shape, int quality) {
        synchronized (MESHES) {
            return MESHES.get(new Key(shape, quality));
        }
    }

    /**
     * The mesh of a shape at a quality: kept, being made by another thread (waited for), or made here. A shape
     * waits only for shapes it is made of, which are smaller, so two threads never wait for each other.
     */
    static Mesh get(Solid.Shape shape, int quality, Supplier<Mesh> make) {
        final Key k = new Key(shape, quality);
        CompletableFuture<Mesh> f;
        boolean mine = false;
        synchronized (MESHES) {
            final Mesh m = MESHES.get(k);
            if (m != null) {
                return m;
            }
            f = MAKING.get(k);
            if (f == null) {
                f = new CompletableFuture<>();
                MAKING.put(k, f);
                mine = true;
            }
        }
        // made outside the lock: tessellations of different shapes run side by side
        return mine ? make(k, f, make) : f.join();
    }

    private static Mesh make(Key k, CompletableFuture<Mesh> f, Supplier<Mesh> make) {
        try {
            final Mesh m = make.get();
            synchronized (MESHES) {
                final Mesh old = MESHES.put(k, m);
                if (old != null) {
                    bytes -= sizeOf(old);
                }
                bytes += sizeOf(m);
                trim();
            }
            f.complete(m);
            return m;
        } catch (RuntimeException e) {
            f.completeExceptionally(e);
            throw e;
        } finally {
            synchronized (MESHES) {
                MAKING.remove(k, f);
            }
        }
    }

    private static void trim() {
        final Iterator<Map.Entry<Key, Mesh>> it = MESHES.entrySet().iterator();
        while (bytes > BUDGET && it.hasNext()) {
            bytes -= sizeOf(it.next().getValue());
            it.remove();
        }
    }

    private static long sizeOf(Mesh m) {
        return 4L * (m.pos.length + m.nrm.length + m.tri.length) + 96;
    }

    /** What the cache holds: meshes, and their bytes. */
    static long[] stats() {
        synchronized (MESHES) {
            return new long[] {MESHES.size(), bytes};
        }
    }
}
