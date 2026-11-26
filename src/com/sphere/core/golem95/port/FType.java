package com.sphere.core.golem95.port;

/** A derived type of the translated Fortran: a value that copies itself on assignment. */
public interface FType {

    /** A copy with every component copied, as Fortran's intrinsic assignment. */
    Object copyValue();
}
