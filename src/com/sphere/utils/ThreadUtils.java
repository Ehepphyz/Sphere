package com.sphere.utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThreadUtils {
    public static ExecutorService createAdaptiveExecutor() {
        try {
            // Use reflection to check if Virtual Threads are supported by the runtime JVM
            var method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            return (ExecutorService) method.invoke(null);
        } catch (Exception e) {
            // Fallback gracefully to a high-performance Java 17 pool if Java 21+ features aren't present
            return Executors.newCachedThreadPool();
        }
    }
}