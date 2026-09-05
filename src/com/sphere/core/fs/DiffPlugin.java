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

/**
 * :diff, unified format. The longest common subsequence is computed with the
 * usual dynamic programming table, which is fine for configuration files and
 * job output; a guard refuses a pair too large for it.
 */
public class DiffPlugin implements CommandRouter.CommandPlugin {

    private static final long MAX_CELLS = 40_000_000L;
    private static final int DEFAULT_CONTEXT = 3;

    private final CommandRouter router;

    public DiffPlugin(CommandRouter router) {
        this.router = router;
    }

    @Override
    public String getName() {
        return "diff";
    }

    @Override
    public boolean supports(String input) {
        if (input == null) return false;
        String t = input.trim();
        if (t.startsWith("::")) return false;
        return t.equals(":diff") || t.startsWith(":diff ");
    }

    @Override
    public void execute(String input) {
        List<String> tokens = FsSupport.tokenize(input.trim());

        int context = DEFAULT_CONTEXT;
        boolean ignoreWhitespace = false;
        boolean briefOnly = false;
        List<String> operands = new ArrayList<>();

        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("--help")) { usage(); return; }
            if (t.equals("-w")) { ignoreWhitespace = true; continue; }
            if (t.equals("-q") || t.equals("--brief")) { briefOnly = true; continue; }
            if (t.startsWith("-U") || t.startsWith("-u")) {
                String v = t.length() > 2 ? t.substring(2) : "";
                if (!v.isEmpty()) {
                    try { context = Integer.parseInt(v); }
                    catch (NumberFormatException bad) { AppLogger.error("Expected a number after -U"); return; }
                }
                continue;
            }
            if (t.startsWith("-")) { AppLogger.error("Unknown option: " + t); return; }
            operands.add(t);
        }

        if (operands.size() != 2) { usage(); return; }

        Path left = FsSupport.resolve(router, operands.get(0));
        Path right = FsSupport.resolve(router, operands.get(1));

        for (Path p : new Path[]{left, right}) {
            if (!Files.exists(p)) { AppLogger.error("Not found: " + p); return; }
            if (Files.isDirectory(p)) { AppLogger.error("Not a file: " + p); return; }
            if (FsSupport.looksBinary(p)) {
                AppLogger.error("This looks like a binary file: " + p.getFileName()
                                + ". Use  :sha256  to compare binaries.");
                return;
            }
        }

        final Path finalLeft = left, finalRight = right;
        final int finalContext = Math.max(0, context);
        final boolean finalIgnoreWs = ignoreWhitespace;
        final boolean finalBrief = briefOnly;

        new SwingWorker<Void, String>() {
            private boolean identical = true;

            @Override
            protected Void doInBackground() {
                List<String> a, b;
                try {
                    a = readLines(finalLeft);
                    b = readLines(finalRight);
                } catch (IOException e) {
                    publish("[!] " + e.getMessage());
                    return null;
                }

                if ((long) (a.size() + 1) * (b.size() + 1) > MAX_CELLS) {
                    publish("[!] Files too large to diff here: " + a.size() + " and "
                            + b.size() + " lines.");
                    return null;
                }

                List<String> ka = finalIgnoreWs ? normalize(a) : a;
                List<String> kb = finalIgnoreWs ? normalize(b) : b;

                int[][] lcs = lcsTable(ka, kb);
                List<int[]> edits = backtrack(lcs, ka, kb);
                identical = edits.stream().allMatch(e -> e[0] == 0);

                if (identical) return null;
                if (finalBrief) {
                    publish("Files differ: " + finalLeft.getFileName()
                            + " and " + finalRight.getFileName());
                    return null;
                }

                publish("--- " + finalLeft);
                publish("+++ " + finalRight);
                emitHunks(edits, a, b, finalContext, this::publish);
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String line : chunks) {
                    if (line.startsWith("[!] ")) AppLogger.error(line.substring(4));
                    else AppLogger.raw(line);
                }
            }

            @Override
            protected void done() {
                if (identical) {
                    AppLogger.success("The two files are identical.");
                }
            }
        }.execute();
    }

    private static List<String> readLines(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = FsSupport.utf8Reader(file)) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    private static List<String> normalize(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) out.add(l.trim().replaceAll("\\s+", " "));
        return out;
    }

    private static int[][] lcsTable(List<String> a, List<String> b) {
        int[][] table = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                table[i][j] = a.get(i).equals(b.get(j))
                        ? table[i + 1][j + 1] + 1
                        : Math.max(table[i + 1][j], table[i][j + 1]);
            }
        }
        return table;
    }

    /** {kind, indexInA, indexInB} with kind 0 same, -1 removed, +1 added. */
    private static List<int[]> backtrack(int[][] table, List<String> a, List<String> b) {
        List<int[]> edits = new ArrayList<>();
        int i = 0, j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                edits.add(new int[]{0, i++, j++});
            } else if (table[i + 1][j] >= table[i][j + 1]) {
                edits.add(new int[]{-1, i++, -1});
            } else {
                edits.add(new int[]{1, -1, j++});
            }
        }
        while (i < a.size()) edits.add(new int[]{-1, i++, -1});
        while (j < b.size()) edits.add(new int[]{1, -1, j++});
        return edits;
    }

    private static void emitHunks(List<int[]> edits, List<String> a, List<String> b,
                                  int context, java.util.function.Consumer<String> out) {
        int index = 0;
        while (index < edits.size()) {
            if (edits.get(index)[0] == 0) { index++; continue; }

            int start = index;
            while (start > 0 && edits.get(start - 1)[0] == 0
                   && start > index - context) start--;

            int end = index;
            int quiet = 0;
            while (end < edits.size() && quiet <= context * 2) {
                quiet = edits.get(end)[0] == 0 ? quiet + 1 : 0;
                end++;
            }
            int stop = Math.min(edits.size(), end);
            while (stop > start && edits.get(stop - 1)[0] == 0
                   && stop > lastChange(edits, start, stop) + context + 1) stop--;

            int firstA = -1, firstB = -1, countA = 0, countB = 0;
            for (int k = start; k < stop; k++) {
                int[] e = edits.get(k);
                if (e[0] <= 0 && e[1] >= 0) { if (firstA < 0) firstA = e[1]; countA++; }
                if (e[0] >= 0 && e[2] >= 0) { if (firstB < 0) firstB = e[2]; countB++; }
            }
            out.accept("@@ -" + (firstA < 0 ? 0 : firstA + 1) + "," + countA
                     + " +" + (firstB < 0 ? 0 : firstB + 1) + "," + countB + " @@");

            for (int k = start; k < stop; k++) {
                int[] e = edits.get(k);
                if (e[0] == 0) out.accept(" " + a.get(e[1]));
                else if (e[0] < 0) out.accept("-" + a.get(e[1]));
                else out.accept("+" + b.get(e[2]));
            }
            index = stop;
        }
    }

    private static int lastChange(List<int[]> edits, int from, int to) {
        for (int k = to - 1; k >= from; k--) {
            if (edits.get(k)[0] != 0) return k;
        }
        return from;
    }

    private void usage() {
        AppLogger.raw("Usage: :diff [-U N] [-w] [-q] <file1> <file2>");
        AppLogger.raw("Options:");
        AppLogger.raw("  -U N          lines of context (default " + DEFAULT_CONTEXT + ")");
        AppLogger.raw("  -w            ignore whitespace differences");
        AppLogger.raw("  -q            say only whether the files differ");
        AppLogger.raw("  --help        show this help");
        AppLogger.raw("Binary files are refused; compare those with :sha256.");
    }
}
