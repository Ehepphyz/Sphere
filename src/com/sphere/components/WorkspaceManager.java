package com.sphere.components;

import com.sphere.components.workspace.WorkspaceListener;
import com.sphere.utils.AppLogger;
import com.sphere.components.workspace.MinimalJson;
import com.sphere.components.workspace.MinimalPresetJson;
import com.sphere.components.workspace.WkProjectAnalysis;

import javax.swing.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Thread-safe controller responsible for monitoring active workspaces, 
 * importing loose data assets, managing presets, and managing lifecycle operations.
 */
public class WorkspaceManager {

    private final File workspaceDirectory;
    private final List<WorkspaceListener> listeners = new CopyOnWriteArrayList<>();
    private final List<File> projects = new CopyOnWriteArrayList<>();
    
    private File selectedProject;
    private long lastCheckedSettingsTimestamp = 0L;
    private Map<String, PresetRule> presetRules = Collections.synchronizedMap(new LinkedHashMap<>());

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Workspace-Monitor-Thread");
        thread.setDaemon(true);
        return thread;
    });

    public WorkspaceManager() {
        this.workspaceDirectory = new File("WorkSpace");
        checkAndCreateWorkspace();
        scanWorkspace();
        startAutoRefresh(1);
    }

    private File getPresetFile(File projectDir) {
        return new File(projectDir, ".presets");
    }

    private void checkAndCreateWorkspace() {
        if (!workspaceDirectory.exists() && workspaceDirectory.mkdirs()) {
            AppLogger.success("Workspace directory was missing. Created new workspace root.");
        }
    }

    public void addWorkspaceListener(WorkspaceListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeWorkspaceListener(WorkspaceListener listener) {
        listeners.remove(listener);
    }

    private void startAutoRefresh(int intervalSeconds) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkAndCreateWorkspace();

                boolean workspaceChanged = hasWorkspaceChanged();
                boolean selectedProjectChanged = hasSelectedProjectChanged();

                if (workspaceChanged || selectedProjectChanged) {
                    final boolean refreshScan = workspaceChanged;
                    final File currentProject = this.selectedProject;

                    SwingUtilities.invokeLater(() -> {
                        if (refreshScan) {
                            scanWorkspace();
                        }

                        if (currentProject != null && currentProject.exists()) {
                            WkProjectAnalysis analyzer = new WkProjectAnalysis();
                            analyzer.analyze(currentProject);

                            for (WorkspaceListener listener : listeners) {
                                listener.onProjectStructureUpdated(currentProject);
                            }
                        }
                    });
                }
            } catch (Exception e) {
                AppLogger.error("Error in workspace scheduler loop: " + e.getMessage());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private boolean hasWorkspaceChanged() {
        if (!workspaceDirectory.exists() || !workspaceDirectory.isDirectory()) return false;

        File[] subDirs = workspaceDirectory.listFiles(File::isDirectory);
        int currentCount = (subDirs != null) ? subDirs.length : 0;

        if (currentCount != projects.size()) return true;

        if (subDirs != null) {
            for (File dir : subDirs) {
                if (!projects.contains(dir)) return true;
            }
        }
        return false;
    }

    private boolean hasSelectedProjectChanged() {
        if (selectedProject == null || !selectedProject.exists()) return false;

        File settingsFile = new File(selectedProject, ".projectsettings");
        if (!settingsFile.exists()) return true;

        long settingsTimestamp = settingsFile.lastModified();
        
        if (settingsTimestamp > lastCheckedSettingsTimestamp) {
            lastCheckedSettingsTimestamp = settingsTimestamp;
            return true;
        }

        try (Stream<Path> pathStream = Files.walk(selectedProject.toPath())) {
            return pathStream.parallel().anyMatch(path -> {
                File file = path.toFile();
                if (!file.getName().startsWith(".")) {
                    return file.lastModified() > settingsTimestamp;
                }
                return false;
            });
        } catch (IOException e) {
            return false;
        }
    }

    public void scanWorkspace() {
        checkAndCreateWorkspace();
        
        List<File> discoveredProjects = new ArrayList<>();
        if (workspaceDirectory.exists() && workspaceDirectory.isDirectory()) {
            File[] files = workspaceDirectory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        discoveredProjects.add(file);
                    }
                }
            }
        }

        projects.clear();
        projects.addAll(discoveredProjects);
        notifyWorkspaceChanged();
    }

    private void notifyWorkspaceChanged() {
        List<File> currentSnapshot = new ArrayList<>(projects);
        for (WorkspaceListener listener : listeners) {
            listener.onWorkspaceChanged(currentSnapshot);
        }
    }

    public void selectProject(File project) {
        this.selectedProject = project;
        if (project != null && project.exists()) {
            File settingsFile = new File(project, ".projectsettings");
            this.lastCheckedSettingsTimestamp = settingsFile.exists() ? settingsFile.lastModified() : 0L;
        }
        
        loadPresetRules(project);
        
        for (WorkspaceListener listener : listeners) {
            listener.onProjectSelected(project);
        }
    }

    public File getWorkspaceDirectory() {
        return workspaceDirectory;
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public boolean deleteProject(File projectDir) {
        if (projectDir == null || !projectDir.exists()) return false;

        String name = projectDir.getName();
        try {
            Files.walkFileTree(projectDir.toPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            
            AppLogger.success("Project deleted: " + name);
            scanWorkspace();
            return true;
        } catch (IOException e) {
            AppLogger.error("Failed to delete project '" + name + "': " + e.getMessage());
            return false;
        }
    }

    public boolean renameProject(File projectDir, String newName) {
        if (projectDir == null || !projectDir.exists() || newName == null || newName.trim().isEmpty()) {
            AppLogger.error("Rename failed: Invalid project parameters.");
            return false;
        }

        if (!newName.matches("^[a-zA-Z0-9._-]+$")) {
            AppLogger.error("Rename rejected: Illegal characters inside name filter.");
            return false;
        }

        File destination = new File(projectDir.getParentFile(), newName);
        if (destination.exists()) {
            AppLogger.error("Rename failed: A project named '" + newName + "' already exists.");
            return false;
        }

        try {
            Files.move(projectDir.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            AppLogger.success("Project renamed to '" + newName + "'");
            scanWorkspace();
            return true;
        } catch (IOException e) {
            AppLogger.error("OS failed to execute rename task: " + e.getMessage());
            return false;
        }
    }

    /**
     * Imports selected file assets securely into targeted workspace subdirectories.
     * Prevents workspace directory loops by using explicit self-copy restriction filters.
     */
    public void importAssets(File[] selectedItems) {
        if (selectedItems == null || selectedItems.length == 0) return;

        // CRITICAL SECURE CHECK: Drop processing if the selected location matches the workspace root itself
        for (File item : selectedItems) {
            if (item.getAbsolutePath().equals(workspaceDirectory.getAbsolutePath())) {
                AppLogger.error("Import aborted: Cannot import the workspace into itself.");
                return;
            }
        }

        AppLogger.info("Starting workspace asset import pipeline...");

        try {
            if (selectedItems.length == 1 && selectedItems[0].isDirectory()) {
                File sourceDir = selectedItems[0];
                File targetDir = new File(workspaceDirectory, sourceDir.getName());

                if (targetDir.exists()) {
                    AppLogger.error("Import rejected: Target location already exists.");
                    return;
                }

                AppLogger.info("Copying root project directory: " + sourceDir.getName());
                copyDirectoryRecursive(sourceDir.toPath(), targetDir.toPath());

                WkProjectAnalysis analyzer = new WkProjectAnalysis();
                analyzer.analyze(targetDir);
                AppLogger.success("Project directory structure imported successfully.");

            } else {
                File targetDir = new File(workspaceDirectory, "Imported_Assets");

                if (!targetDir.exists() && !targetDir.mkdir()) {
                    AppLogger.error("Import failed: Unable to initialize container directory.");
                    return;
                }

                AppLogger.info("Importing loose assets into: " + targetDir.getName());

                for (File item : selectedItems) {
                    Path targetPath = targetDir.toPath().resolve(item.getName());
                    if (item.isFile()) {
                        Files.copy(item.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                    } else if (item.isDirectory()) {
                        copyDirectoryRecursive(item.toPath(), targetPath);
                    }
                }

                WkProjectAnalysis analyzer = new WkProjectAnalysis();
                analyzer.analyze(targetDir);
                AppLogger.success("Assets nested inside transient layout container.");
            }

            scanWorkspace();
        } catch (IOException ex) {
            AppLogger.error("I/O exception raised during import cycle: " + ex.getMessage());
        }
    }

    private void copyDirectoryRecursive(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetDir = target.resolve(source.relativize(dir));
                if (!Files.exists(targetDir)) {
                    Files.createDirectories(targetDir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public void loadOrCreatePresetFile(File projectDir) {
        File f = getPresetFile(projectDir);
        if (!f.exists()) {
            try (FileWriter w = new FileWriter(f)) {
                w.write(getDefaultPresetJson());
                AppLogger.success("Created default .presets configuration for: " + projectDir.getName());
            } catch (Exception e) {
                AppLogger.error("Failed to materialize layout file: " + e.getMessage());
            }
        }
    }

    public void loadPresetRules(File projectDir) {
        try {
            File f = getPresetFile(projectDir);
            if (!f.exists()) {
                presetRules = Collections.synchronizedMap(new LinkedHashMap<>());
                return;
            }

            String json = Files.readString(f.toPath());
            Map<String, PresetRule> parsedRules = MinimalPresetJson.loadPresetRules(json);
            
            presetRules.clear();
            if (parsedRules != null) {
                presetRules.putAll(parsedRules);
            }
            AppLogger.info("Preset parameters parsed for: " + projectDir.getName());
        } catch (Exception e) {
            AppLogger.error("Failed to parse configurations: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public void modifyPresetFileIfNeeded(File projectDir) {
        try {
            File f = getPresetFile(projectDir);
            if (!f.exists()) return;

            String json = Files.readString(f.toPath());
            Map<String, Object> root = (Map<String, Object>) MinimalJson.parse(json);

            boolean isModified = false;
            isModified |= ensurePresetEntry(root, "ATLAS", List.of("24"));
            isModified |= ensurePresetEntry(root, "CMS", List.of("13", "23"));
            isModified |= ensurePresetEntry(root, "LHCb", List.of("v"));
            isModified |= ensurePresetEntry(root, "Belle II", List.of("b2"));

            if (isModified) {
                try (FileWriter w = new FileWriter(f)) {
                    w.write(MinimalJson.toJson(root));
                    AppLogger.info(".presets structural fields updated for: " + projectDir.getName());
                }
                loadPresetRules(projectDir);
            }
        } catch (Exception e) {
            AppLogger.error("Encountered adjustments mutation error: " + e.getMessage());
        }
    }

    public boolean ensurePresetEntry(Map<String, Object> root, String experiment, List<String> defaults) {
        Object obj = root.get(experiment);

        if (obj == null) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("allowedPrefixes", new ArrayList<>(defaults));
            root.put(experiment, entry);
            return true;
        }

        if (!(obj instanceof Map rawMap)) {
            return false;
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        for (Object keyObj : rawMap.keySet()) {
            if (keyObj instanceof String key) {
                entry.put(key, rawMap.get(key));
            }
        }

        if (!entry.containsKey("allowedPrefixes")) {
            entry.put("allowedPrefixes", new ArrayList<>(defaults));
            root.put(experiment, entry);
            return true;
        }

        return false;
    }

    private String getDefaultPresetJson() {
        return """
               {
                 "ATLAS": {"allowedPrefixes": ["24"]},
                 "CMS": {"allowedPrefixes": ["13", "23"]},
                 "LHCb": {"allowedPrefixes": ["v"]},
                 "Belle II": {"allowedPrefixes": ["b2"]}
               }""";
    }

    public Map<String, PresetRule> getPresetRules() {
        return presetRules;
    }

    public static class PresetRule {
        public final List<String> allowedPrefixes;
        public PresetRule(List<String> p) { 
            this.allowedPrefixes = p != null ? List.copyOf(p) : List.of(); 
        }
    }
}
