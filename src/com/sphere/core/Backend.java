package com.sphere.core;

public interface Backend {
    String getName();
    void execute(String command);
    void activate();
}

