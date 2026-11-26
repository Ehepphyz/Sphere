package com.sphere.core.golem95.port;

/**
 * An allocatable or pointer array of the translated Fortran: its elements and the lower bound of each
 * dimension, which an ALLOCATE may set to anything (OneLOop keeps tables from index 0 or 2).
 * A pointer shares the wrapper of its target, so a target reallocated is seen through it.
 *
 * @param <A> the Java array type of the elements: double[], Cx[][], ...
 */
public final class Alloc<A> {

    /** The elements, or null while the array is not allocated. */
    public A a;
    /** The lower bound of each dimension. */
    public int[] lb;

    public Alloc() {
    }

    public Alloc(A a, int[] lb) {
        this.a = a;
        this.lb = lb;
    }

    public boolean allocated() {
        return a != null;
    }

    /** A deep copy: Fortran's assignment of a derived type with an allocatable component. */
    @SuppressWarnings("unchecked")
    public Alloc<A> copy() {
        return new Alloc<>(a == null ? null : (A) F.copyAny(a), lb == null ? null : lb.clone());
    }
}
