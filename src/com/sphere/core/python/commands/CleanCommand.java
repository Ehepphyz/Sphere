package com.sphere.core.python.commands;

import com.sphere.core.python.PythonCommand;
import com.sphere.utils.AppLogger;
import java.io.File;

public class CleanCommand implements PythonCommand {
    @Override
    public void execute() {
        AppLogger.info("Cleaning Python cache files...");
        cleanDirectory(new File("."));
        AppLogger.success("Cleanup complete.");
    }

    private void cleanDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                if (f.getName().equals("__pycache__")) deleteRecursive(f);
                else cleanDirectory(f);
            } else if (f.getName().endsWith(".pyc")) f.delete();
        }
    }
    
    private void deleteRecursive(File f) {
        File[] files = f.listFiles();
        if (files != null) for (File child : files) deleteRecursive(child);
        f.delete();
    }
}
