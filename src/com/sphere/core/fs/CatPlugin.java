package com.sphere.core.fs;

import com.sphere.core.CommandRouter;
import com.sphere.utils.AppLogger;

import javax.swing.*;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugin to display file contents using Java NIO.
 * Exclusively intercepts the internal ':cat' syntax to avoid OS terminal conflicts.
 */
public class CatPlugin implements CommandRouter.CommandPlugin {

    private final CommandRouter router;

    private static final Pattern ARG_SPLIT = Pattern.compile(
        "\"((?:\\\\\"|[^\"])+)\"|'((?:\\\\'|[^'])+)'|([^\\s]+)"
    );

    private static final int BINARY_DETECT_BYTES = 4096;
    private static final int DEFAULT_MAX_LINES = 10000;

    public CatPlugin(CommandRouter router) {
        this.router = router;
    }

    @Override
    public String getName() { return "cat"; }

    @Override
    public boolean supports(String input) {
        if (input == null) return false;
        String trimmed = input.trim();
        if (trimmed.startsWith("::")) return false;
        return trimmed.equals(":cat") || trimmed.startsWith(":cat ");
    }

    @Override
    public void execute(String input) {
        if (input == null) {
            AppLogger.error("Null input.");
            return;
        }

        String trimmed = input.trim();

        if (trimmed.startsWith("::")) {
            AppLogger.error("Hybrid syntax (::cat) is not allowed for file display.");
            return;
        }

        if (!trimmed.startsWith(":")) {
            AppLogger.error("Internal command must start with ':' prefix.");
            return;
        }
        String withoutColon = trimmed.substring(1).trim();

        List<String> tokens = splitArgs(withoutColon);
        if (tokens.isEmpty() || !tokens.get(0).equals("cat")) {
            AppLogger.error("Invalid cat command.");
            return;
        }

        if (tokens.size() == 1) {
            AppLogger.error("Usage: :cat [--head N] [--tail N] <filename>");
            return;
        }

        Integer headLines = null;
        Integer tailLines = null;
        String filename = null;

        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            
            // FIX B: Flags are only valid if we haven't started capturing the filename yet
            if (filename == null && ("--head".equals(t) || "-n".equals(t))) {
                if (i + 1 < tokens.size()) {
                    try {
                        headLines = Integer.parseInt(tokens.get(++i));
                        if (headLines < 0) headLines = null;
                    } catch (NumberFormatException e) {
                        AppLogger.error("Invalid number for " + t);
                        return;
                    }
                } else {
                    AppLogger.error("Missing number after " + t);
                    return;
                }
            } else if (filename == null && "--tail".equals(t)) {
                if (i + 1 < tokens.size()) {
                    try {
                        tailLines = Integer.parseInt(tokens.get(++i));
                        if (tailLines < 0) tailLines = null;
                    } catch (NumberFormatException e) {
                        AppLogger.error("Invalid number for --tail");
                        return;
                    }
                } else {
                    AppLogger.error("Missing number after --tail");
                    return;
                }
            } else if (filename == null && t.startsWith("-")) {
                AppLogger.error("Unknown option: " + t);
                return;
            } else {
                // Compile the remaining tokens as the filename
                StringBuilder sb = new StringBuilder(t);
                for (int j = i + 1; j < tokens.size(); j++) {
                    sb.append(' ').append(tokens.get(j));
                }
                filename = sb.toString();
                break;
            }
        }

        if (filename == null || filename.isBlank()) {
            AppLogger.error("Usage: :cat [--head N] [--tail N] <filename>");
            return;
        }

        filename = expandHome(filename);

        Path filePath;
        try {
            Path p = Paths.get(filename);
            filePath = p.isAbsolute() ? p.toAbsolutePath().normalize()
                                     : router.getCurrentDirectory().resolve(p).toAbsolutePath().normalize();
        } catch (InvalidPathException ipe) {
            AppLogger.error("Invalid path: " + ipe.getMessage());
            return;
        }

        if (!Files.exists(filePath, LinkOption.NOFOLLOW_LINKS)) {
            AppLogger.error("File not found: " + filePath);
            return;
        }
        if (!Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
            AppLogger.error("Not a regular file: " + filePath);
            return;
        }

        try {
            if (isLikelyBinary(filePath)) {
                AppLogger.error("File appears to be binary. Use a hex viewer or download the file instead.");
                return;
            }
        } catch (IOException e) {
            AppLogger.error("Unable to inspect file: " + e.getMessage());
            return;
        }

        if (headLines != null && tailLines != null) {
            AppLogger.error("Cannot use --head and --tail together.");
            return;
        }

        final Integer finalHead = headLines;
        final Integer finalTail = tailLines;
        final int maxLines = (headLines != null) ? headLines : ((tailLines != null) ? tailLines : DEFAULT_MAX_LINES);

        // FIX A: Rewritten SwingWorker to process chunks properly and avoid flooding the EDT thread
        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                // FIX C: Safe UTF-8 Reader replacing malformed characters instead of crashing
                Charset charset = StandardCharsets.UTF_8;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(Files.newInputStream(filePath), 
                        charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)))) {
                    
                    String line;
                    int count = 0;

                    if (finalHead != null) {
                        while ((line = reader.readLine()) != null && count < finalHead) {
                            publish(line);
                            count++;
                        }
                        if (reader.readLine() != null) {
                            publish("[Output truncated: head " + finalHead + " lines shown]");
                        }
                    } else if (finalTail != null) {
                        Deque<String> buffer = new ArrayDeque<>(finalTail + 1);
                        while ((line = reader.readLine()) != null) {
                            if (buffer.size() == finalTail) buffer.removeFirst();
                            buffer.addLast(line);
                        }
                        for (String l : buffer) publish(l);
                    } else {
                        while ((line = reader.readLine()) != null) {
                            publish(line);
                            count++;
                            if (count >= maxLines) {
                                publish("[Output truncated: max " + maxLines + " lines shown]");
                                break;
                            }
                        }
                    }
                } catch (IOException e) {
                    publish("[Error reading file: " + e.getMessage() + "]");
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                // Safely batched updates on the EDT
                for (String line : chunks) {
                    AppLogger.raw(line);
                }
            }
        }.execute();
    }

    private List<String> splitArgs(String input) {
        List<String> parts = new ArrayList<>();
        Matcher m = ARG_SPLIT.matcher(input);
        while (m.find()) {
            if (m.group(1) != null) parts.add(m.group(1).replace("\\\"", "\""));
            else if (m.group(2) != null) parts.add(m.group(2).replace("\\'", "'"));
            else if (m.group(3) != null) parts.add(m.group(3));
        }
        return parts;
    }

    private String expandHome(String path) {
        if (path == null || path.isEmpty()) return path;
        if (path.equals("~")) return System.getProperty("user.home");
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    private boolean isLikelyBinary(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ)) {
            byte[] buf = new byte[BINARY_DETECT_BYTES];
            int read = in.read(buf);
            if (read <= 0) return false;
            int nonPrintable = 0;
            int printable = 0;
            for (int i = 0; i < read; i++) {
                byte b = buf[i];
                if (b == 0) return true; 
                int ub = b & 0xFF;
                if (ub >= 0x20 && ub <= 0x7E) {
                    printable++;
                } else if (ub == 0x09 || ub == 0x0A || ub == 0x0D) {
                    printable++;
                } else {
                    nonPrintable++;
                }
            }
            return nonPrintable > 0 && ((double) nonPrintable / (nonPrintable + printable)) > 0.30;
        }
    }
}