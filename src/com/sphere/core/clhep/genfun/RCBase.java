package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Genfun::RCBase (RCBase.hh, .cc): a reference count, an unsigned short. The C++ deletes the
 * object when an unref brings it to zero; here the garbage collector does.
 */
public class RCBase {

    private int _count;

    public void ref() {
        _count = (_count + 1) & 0xffff;
    }

    public void unref() {
        if (_count != 0) _count--;
    }

    public int refCount() {
        return _count;
    }
}
