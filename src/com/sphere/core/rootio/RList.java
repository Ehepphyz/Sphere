package com.sphere.core.rootio;

import java.util.ArrayList;

/**
 * A TObjArray or TList as read or to be written: its items, and what its own
 * TObject and name say (the owner bit, kIsOwner, among the bits).
 */
public final class RList extends ArrayList<Object> {

    /** kIsOnHeap and kNotDeleted, as ROOT 6.20 wrote them. */
    public static final long BITS = 0x03000000L;
    /** The same, for a collection owning its items (TCollection::kIsOwner). */
    public static final long BITS_OWNER = 0x03004000L;

    public long bits = BITS;
    /** The bits of TObjString items that are not the usual ones, by position. */
    public final java.util.Map<Integer, Long> stringBits = new java.util.HashMap<>();
    /** TObjArray or TList. */
    public String className = "TObjArray";
    public String name = "";

    public RList() {
    }

    public RList(int capacity) {
        super(capacity);
    }

    public static RList owning() {
        final RList l = new RList();
        l.bits = BITS_OWNER;
        return l;
    }
}
