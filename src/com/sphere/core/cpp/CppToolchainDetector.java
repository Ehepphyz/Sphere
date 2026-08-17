package com.sphere.core.cpp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

public final class CppToolchainDetector {
    public enum OsType {
        WINDOWS,
        LINUX,
        MACOS,
        WSL,
        UNKNOWN
    }

    public enum ToolchainKind {
        NATIVE,
        WSL
    }

    public static final class DetectedToolchain {
        private final String id;
        private final String executablePath;
        private final OsType osType;
        private final ToolchainKind kind;

        public DetectedToolchain(String id, String executablePath, OsType osType, ToolchainKind kind) {
            this.id = id;
            this.executablePath = executablePath;
            this.osType = osType;
            this.kind = kind;
        }

        public String getId() { return id; }
        public String getExecutablePath() { return executablePath; }
        public OsType getOsType() { return osType; }
        public ToolchainKind getKind() { return kind; }

        @Override
        public String toString() {
            return String.format("[%s] %s (%s - %s)", id.toUpperCase(), executablePath, osType, kind);
        }
    }

    public static OsType detectOsType() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return isWslEnvironment() ? OsType.WSL : OsType.WINDOWS;
        }
        if (os.contains("mac")) {
            return OsType.MACOS;
        }
        if (os.contains("nux") || os.contains("nix")) {
            return isWslEnvironment() ? OsType.WSL : OsType.LINUX;
        }
        return OsType.UNKNOWN;
    }

    private static boolean isWslEnvironment() {
        Path procVersion = Paths.get("/proc/version");
        if (!Files.exists(procVersion)) {
            return false;
        }
        try (BufferedReader reader = Files.newBufferedReader(procVersion)) {
            String line = reader.readLine();
            if (line != null) {
                String lower = line.toLowerCase(Locale.ROOT);
                return lower.contains("microsoft") || lower.contains("wsl");
            }
        } catch (Exception ignored) {
            // Quietly bypass environments where /proc/version is protected or unreadable
        }
        return false;
    }

    public static List<DetectedToolchain> detectAll() {
        OsType osType = detectOsType();
        List<DetectedToolchain> result = new ArrayList<>();
        
        // Step 1: Scan active environment PATH variables (highest user priority)
        Map<String, String> pathMap = scanPathExecutables(osType);
        addIfPresent(result, "gcc", pathMap.get("gcc"), osType);
        addIfPresent(result, "g++", pathMap.get("g++"), osType);
        addIfPresent(result, "clang", pathMap.get("clang"), osType);
        addIfPresent(result, "clang++", pathMap.get("clang++"), osType);
        addIfPresent(result, "msvc", pathMap.get("cl.exe"), osType);

        // Step 2: Resilient discovery fallback loops targeting standard locations
        if (osType == OsType.WINDOWS) {
            Map<String, String> extraWin = scanWindowsDefaultLocations();
            addIfAbsent(result, "msvc", extraWin.get("cl.exe"), osType);
            addIfAbsent(result, "clang", extraWin.get("clang.exe"), osType);
            addIfAbsent(result, "clang++", extraWin.get("clang++.exe"), osType);
            addIfAbsent(result, "gcc", extraWin.get("gcc.exe"), osType);
            addIfAbsent(result, "g++", extraWin.get("g++.exe"), osType);
        } else if (osType == OsType.LINUX || osType == OsType.MACOS || osType == OsType.WSL) {
            Map<String, String> extraUnix = scanUnixDefaultLocations();
            addIfAbsent(result, "gcc", extraUnix.get("gcc"), osType);
            addIfAbsent(result, "g++", extraUnix.get("g++"), osType);
            addIfAbsent(result, "clang", extraUnix.get("clang"), osType);
            addIfAbsent(result, "clang++", extraUnix.get("clang++"), osType);
        }
        return result;
    }

    private static void addIfPresent(List<DetectedToolchain> list, String id, String path, OsType osType) {
        if (path != null && !path.isEmpty()) {
            ToolchainKind kind = osType == OsType.WSL ? ToolchainKind.WSL : ToolchainKind.NATIVE;
            list.add(new DetectedToolchain(id, path, osType, kind));
        }
    }

    private static void addIfAbsent(List<DetectedToolchain> list, String id, String path, OsType osType) {
        if (path == null || path.isEmpty()) return;
        
        boolean exists = list.stream().anyMatch(dt -> dt.getId().equals(id));
        if (!exists) {
            ToolchainKind kind = osType == OsType.WSL ? ToolchainKind.WSL : ToolchainKind.NATIVE;
            list.add(new DetectedToolchain(id, path, osType, kind));
        }
    }

    private static Map<String, String> scanPathExecutables(OsType osType) {
        Map<String, String> found = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) {
            return found;
        }

        String[] segments = pathEnv.split(File.pathSeparator);
        List<String> targets = osType == OsType.WINDOWS 
            ? Arrays.asList("gcc.exe", "g++.exe", "clang.exe", "clang++.exe", "cl.exe")
            : Arrays.asList("gcc", "g++", "clang", "clang++");

        for (String segment : segments) {
            Path dirPath = Paths.get(segment.trim());
            if (!Files.isDirectory(dirPath)) continue;

            for (String target : targets) {
                Path candidate = dirPath.resolve(target);
                // Validation boundary: Verify execution access constraints safely via NIO
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    found.put(target.toLowerCase(Locale.ROOT), candidate.toAbsolutePath().toString());
                }
            }
        }
        return normalizeKeys(found);
    }

    private static Map<String, String> normalizeKeys(Map<String, String> raw) {
        Map<String, String> normalized = new HashMap<>();
        raw.forEach((key, val) -> {
            String cleanKey = key.toLowerCase(Locale.ROOT);
            if (cleanKey.contains("g++")) normalized.put("g++", val);
            else if (cleanKey.contains("gcc")) normalized.put("gcc", val);
            else if (cleanKey.contains("clang++")) normalized.put("clang++", val);
            else if (cleanKey.contains("clang")) normalized.put("clang", val);
            else if (cleanKey.equals("cl.exe")) normalized.put("cl.exe", val);
        });
        return normalized;
    }

    private static Map<String, String> scanWindowsDefaultLocations() {
        Map<String, String> found = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<Path> scanRoots = new ArrayList<>();

        // Capture standard environment variables safely
        String progFiles = System.getenv("ProgramFiles");
        String progFilesX86 = System.getenv("ProgramFiles(x86)");
        String llvmHome = System.getenv("LLVM_HOME");

        if (progFiles != null) scanRoots.add(Paths.get(progFiles));
        if (progFilesX86 != null) scanRoots.add(Paths.get(progFilesX86));
        if (llvmHome != null) scanRoots.add(Paths.get(llvmHome));

        // Add common structural roots where C++ ecosystems deploy tools on Windows
        scanRoots.add(Paths.get("C:\\msys64"));
        scanRoots.add(Paths.get("C:\\MinGW"));

        List<String> targets = Arrays.asList("cl.exe", "clang.exe", "clang++.exe", "gcc.exe", "g++.exe");

        for (Path root : scanRoots) {
            if (Files.exists(root) && Files.isDirectory(root)) {
                // Critical Performance Fix: Enforce a maximum depth of 5 layers 
                // to completely bypass linear traversal lockups on major system directories.
                try (Stream<Path> walk = Files.walk(root, 5)) {
                    walk.filter(Files::isRegularFile)
                        .filter(Files::isExecutable)
                        .forEach(p -> {
                            String filename = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            if (targets.contains(filename)) {
                                found.putIfAbsent(filename, p.toAbsolutePath().toString());
                            }
                        });
                } catch (Exception ignored) {
                    // Suppress structural access denied exceptions gracefully
                }
            }
        }
        return found;
    }

    private static Map<String, String> scanUnixDefaultLocations() {
        Map<String, String> found = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<String> roots = Arrays.asList("/usr/bin", "/usr/local/bin", "/opt/homebrew/bin", "/opt/local/bin");
        List<String> targets = Arrays.asList("gcc", "g++", "clang", "clang++");

        for (String rootPath : roots) {
            Path root = Paths.get(rootPath);
            if (Files.exists(root) && Files.isDirectory(root)) {
                // Unix binary directories are flat; a depth limit of 2 keeps execution blazing fast
                try (Stream<Path> walk = Files.walk(root, 2)) {
                    walk.filter(Files::isRegularFile)
                        .filter(Files::isExecutable)
                        .forEach(p -> {
                            String filename = p.getFileName().toString();
                            if (targets.contains(filename)) {
                                found.putIfAbsent(filename, p.toAbsolutePath().toString());
                            }
                        });
                } catch (Exception ignored) {
                    // Bypass system or virtual symlink access issues safely
                }
            }
        }
        return found;
    }
}