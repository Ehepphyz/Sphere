package com.sphere.core.geant4;

/** A geometry that cannot be built: a bad expression, a missing reference, an impossible solid. */
public class GeometryException extends Exception {

    private static final long serialVersionUID = 1L;

    public GeometryException(String message) {
        super(message);
    }

    public GeometryException(String message, Throwable cause) {
        super(message, cause);
    }
}
