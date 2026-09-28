package com.sphere.core.fastjet;

/** What FastJet calls fastjet::Error: a misuse or an impossible request. */
public class FastJetException extends RuntimeException {

    public FastJetException(String message) {
        super(message);
    }

    /**
     * An inconsistency inside a clustering, fastjet::InternalError. It is
     * thrown rather than asserted so that a caller may retry with another
     * strategy.
     */
    public static class Internal extends FastJetException {
        public Internal(String message) {
            super(message);
        }
    }
}
