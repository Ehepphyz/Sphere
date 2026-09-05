package com.sphere.core.fs;

import com.sphere.core.CommandRouter;
import com.sphere.utils.AppLogger;

import javax.swing.SwingWorker;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * :sha256 and :md5, and a --check that compares against an expected digest.
 * The question they answer for a physics workspace is whether a dataset moved
 * between machines intact.
 */
public class HashPlugin implements CommandRouter.CommandPlugin {

    private final CommandRouter router;

    public HashPlugin(CommandRouter router) {
        this.router = router;
    }

    @Override
    public String getName() {
        return "sha256";
    }

    @Override
    public boolean supports(String input) {
        if (input == null) return false;
        String t = input.trim();
        if (t.startsWith("::")) return false;
        return t.equals(":sha256") || t.startsWith(":sha256 ")
            || t.equals(":md5") || t.startsWith(":md5 ");
    }

    @Override
    public void execute(String input) {
        List<String> tokens = FsSupport.tokenize(input.trim());
        String algorithm = tokens.get(0).equals(":md5") ? "MD5" : "SHA-256";

        String expected = null;
        List<String> operands = new ArrayList<>();

        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("--help")) { usage(tokens.get(0)); return; }
            if (t.startsWith("--check=")) { expected = t.substring(8).trim(); continue; }
            if (t.equals("--check")) {
                if (i + 1 >= tokens.size()) { AppLogger.error("Missing digest after --check"); return; }
                expected = tokens.get(++i).trim();
                continue;
            }
            operands.add(t);
        }

        if (operands.isEmpty()) { usage(tokens.get(0)); return; }
        if (expected != null && operands.size() > 1) {
            AppLogger.error("--check works with one file at a time.");
            return;
        }

        final String finalAlgorithm = algorithm;
        final String finalExpected = expected;
        final List<String> finalOperands = operands;

        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                for (String operand : finalOperands) {
                    Path file = FsSupport.resolve(router, operand);
                    if (!Files.exists(file)) { publish("[!] Not found: " + file); continue; }
                    if (Files.isDirectory(file)) { publish("[!] Not a file: " + file); continue; }

                    String digest;
                    try {
                        digest = digest(file, finalAlgorithm);
                    } catch (IOException e) {
                        publish("[!] Could not read " + file + ": " + e.getMessage());
                        continue;
                    } catch (NoSuchAlgorithmException e) {
                        publish("[!] " + finalAlgorithm + " is not available on this runtime.");
                        return null;
                    }

                    if (finalExpected == null) {
                        publish(digest + "  " + file.getFileName());
                        continue;
                    }

                    if (digest.equalsIgnoreCase(finalExpected)) {
                        publish("[+] " + file.getFileName() + ": digests match");
                    } else {
                        publish("[!] " + file.getFileName() + ": digests differ");
                        publish("      expected  " + finalExpected.toLowerCase(Locale.ROOT));
                        publish("      computed  " + digest);
                    }
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String line : chunks) {
                    if (line.startsWith("[!] ")) AppLogger.error(line.substring(4));
                    else if (line.startsWith("[+] ")) AppLogger.success(line.substring(4));
                    else AppLogger.raw(line);
                }
            }
        }.execute();
    }

    private static String digest(Path file, String algorithm)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream digesting = new DigestInputStream(in, md)) {
            while (digesting.read(buffer) != -1) {
                // The digest is updated by the stream itself
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private void usage(String name) {
        AppLogger.raw("Usage: " + name + " [--check DIGEST] <file> [file...]");
        AppLogger.raw("Options:");
        AppLogger.raw("  --check DIGEST  compare one file against an expected digest");
        AppLogger.raw("  --help          show this help");
    }
}
