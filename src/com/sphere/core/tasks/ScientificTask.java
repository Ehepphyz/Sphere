package com.sphere.core.tasks;

import java.util.function.Consumer;

public class ScientificTask {
    private final String id;
    private final String description;
    private final Process process;
    private Consumer<String> progressListener;

    public ScientificTask(String id, String description, Process process) {
        this.id = id;
        this.description = description;
        this.process = process;
    }

    public void setProgressListener(Consumer<String> listener) {
        this.progressListener = listener;
    }

    public void updateProgress(String logLine) {
        if (progressListener != null) {
            progressListener.accept(logLine);
        }
    }

    public String getId() { return id; }
    public String getDescription() { return description; }
    public Process getProcess() { return process; }
}
