package com.sphere.core.golem95.port;

/**
 * A complex, a character string or a pointer passed by reference.
 *
 * @param <T> what is held
 */
public final class Ref<T> {
    public T v;

    public Ref(T v) {
        this.v = v;
    }
}
