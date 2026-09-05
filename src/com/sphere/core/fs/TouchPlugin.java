package com.sphere.core.fs;

import com.sphere.core.CommandRouter;
import com.sphere.utils.AppLogger;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** :touch -- create an empty file, or move an existing one's timestamp to now. */
public class TouchPlugin implements CommandRouter.CommandPlugin {

    private final CommandRouter router;

    public TouchPlugin(CommandRouter router) {
        this.router = router;
    }

    @Override
    public String getName() {
        return "touch";
    }

    @Override
    public boolean supports(String input) {
        if (input == null) return false;
        String t = input.trim();
        if (t.startsWith("::")) return false;
        return t.equals(":touch") || t.startsWith(":touch ");
    }

    @Override
    public void execute(String input) {
        List<String> tokens = FsSupport.tokenize(input.trim());

        boolean noCreate = false;
        List<String> operands = new ArrayList<>();

        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("--help")) { usage(); return; }
            if (t.equals("-c")) { noCreate = true; continue; }
            if (t.startsWith("-")) { AppLogger.error("Unknown option: " + t); return; }
            operands.add(t);
        }

        if (operands.isEmpty()) { usage(); return; }

        for (String operand : operands) {
            Path file = FsSupport.resolve(router, operand);
            try {
                if (Files.exists(file)) {
                    Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
                    AppLogger.success("Timestamp updated: " + file);
                } else if (noCreate) {
                    AppLogger.error("Not found, and -c forbids creating it: " + file);
                } else {
                    if (file.getParent() != null) Files.createDirectories(file.getParent());
                    Files.createFile(file);
                    AppLogger.success("Created " + file);
                }
            } catch (FileAlreadyExistsException race) {
                AppLogger.error("Already exists: " + file);
            } catch (IOException e) {
                AppLogger.error("Could not touch " + file + ": " + e.getMessage());
            }
        }
    }

    private void usage() {
        AppLogger.raw("Usage: :touch [-c] <file> [file...]");
        AppLogger.raw("Options:");
        AppLogger.raw("  -c            do not create the file if it is missing");
        AppLogger.raw("  --help        show this help");
    }
}
