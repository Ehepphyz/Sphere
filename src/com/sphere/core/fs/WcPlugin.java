package com.sphere.core.fs;

import com.sphere.core.CommandRouter;
import com.sphere.utils.AppLogger;

import javax.swing.SwingWorker;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** :wc -- lines, words and bytes, with a total when several files are given. */
public class WcPlugin implements CommandRouter.CommandPlugin {

    private final CommandRouter router;

    public WcPlugin(CommandRouter router) {
        this.router = router;
    }

    @Override
    public String getName() {
        return "wc";
    }

    @Override
    public boolean supports(String input) {
        if (input == null) return false;
        String t = input.trim();
        if (t.startsWith("::")) return false;
        return t.equals(":wc") || t.startsWith(":wc ");
    }

    @Override
    public void execute(String input) {
        List<String> tokens = FsSupport.tokenize(input.trim());

        boolean onlyLines = false, onlyWords = false, onlyBytes = false;
        List<String> operands = new ArrayList<>();

        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("--help")) { usage(); return; }
            if (t.startsWith("-") && t.length() > 1 && !t.startsWith("--")) {
                for (int k = 1; k < t.length(); k++) {
                    switch (t.charAt(k)) {
                        case 'l': onlyLines = true; break;
                        case 'w': onlyWords = true; break;
                        case 'c': onlyBytes = true; break;
                        default:
                            AppLogger.error("Unknown option: -" + t.charAt(k));
                            return;
                    }
                }
            } else {
                operands.add(t);
            }
        }

        if (operands.isEmpty()) { usage(); return; }

        final boolean showAll = !onlyLines && !onlyWords && !onlyBytes;
        final boolean showLines = showAll || onlyLines;
        final boolean showWords = showAll || onlyWords;
        final boolean showBytes = showAll || onlyBytes;
        final List<String> finalOperands = operands;

        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                long[] total = new long[3];
                int counted = 0;

                for (String operand : finalOperands) {
                    Path file = FsSupport.resolve(router, operand);
                    if (!Files.exists(file)) { publish("[!] Not found: " + file); continue; }
                    if (Files.isDirectory(file)) { publish("[!] Not a file: " + file); continue; }

                    long lines = 0, words = 0, bytes;
                    try {
                        bytes = Files.size(file);
                        try (BufferedReader reader = FsSupport.utf8Reader(file)) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                lines++;
                                String trimmed = line.trim();
                                if (!trimmed.isEmpty()) words += trimmed.split("\\s+").length;
                            }
                        }
                    } catch (IOException e) {
                        publish("[!] Could not read " + file + ": " + e.getMessage());
                        continue;
                    }

                    total[0] += lines; total[1] += words; total[2] += bytes;
                    counted++;
                    publish(format(lines, words, bytes, file.getFileName().toString()));
                }

                if (counted > 1) publish(format(total[0], total[1], total[2], "total"));
                return null;
            }

            private String format(long lines, long words, long bytes, String name) {
                StringBuilder b = new StringBuilder();
                if (showLines) b.append(String.format("%9d", lines));
                if (showWords) b.append(String.format("%9d", words));
                if (showBytes) b.append(String.format("%11d", bytes));
                return b + "  " + name;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String line : chunks) {
                    if (line.startsWith("[!] ")) AppLogger.error(line.substring(4));
                    else AppLogger.raw(line);
                }
            }
        }.execute();
    }

    private void usage() {
        AppLogger.raw("Usage: :wc [-l] [-w] [-c] <file> [file...]");
        AppLogger.raw("Options:");
        AppLogger.raw("  -l            lines only");
        AppLogger.raw("  -w            words only");
        AppLogger.raw("  -c            bytes only");
        AppLogger.raw("  --help        show this help");
        AppLogger.raw("Files are read as UTF-8. A total is added past one file.");
    }
}
