package com.sphere.core.golem95.port;

/**
 * A STOP of the Fortran: the library gave up on what it was asked. The Fortran program would end;
 * the engine throws this instead, with what the library said before it stopped, and Sphere goes on.
 */
public final class FStop extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FStop(String message) {
        super(message);
    }
}
