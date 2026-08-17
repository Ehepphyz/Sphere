package com.sphere.core.tasks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TaskRegistry {
    private static final Map<String, ScientificTask> activeTasks = new ConcurrentHashMap<>();

    public static void register(ScientificTask task) {
        activeTasks.put(task.getId(), task);
    }

    public static void remove(String id) {
        activeTasks.remove(id);
    }

    public static ScientificTask get(String id) {
        return activeTasks.get(id);
    }
}
