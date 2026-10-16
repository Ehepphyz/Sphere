package com.sphere.components.editor;

import java.util.Arrays;

/**
 * Which lines changed since the file was opened or saved, for the bars in the
 * gutter. Common lines are trimmed from both ends, the middle is aligned by a
 * longest common subsequence, and anything too large to align is shown as
 * modified rather than computed for seconds.
 */
final class LineDiff {

    static final byte NONE = 0;
    static final byte ADDED = 1;
    static final byte MODIFIED = 2;
    /** Or-ed in: lines were removed just above this one. */
    static final byte DELETED_ABOVE = 4;

    private static final long MAX_CELLS = 4_000_000L;

    private LineDiff() {
    }

    /** One mark per line of the current text. */
    static byte[] marks(String baseline, String current) {
        String[] cur = split(current);
        byte[] marks = new byte[cur.length];
        if (baseline == null) {
            return marks;
        }
        String[] base = split(baseline);
        int n = base.length;
        int m = cur.length;

        int prefix = 0;
        while (prefix < n && prefix < m && base[prefix].equals(cur[prefix])) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < n - prefix && suffix < m - prefix
               && base[n - 1 - suffix].equals(cur[m - 1 - suffix])) {
            suffix++;
        }
        int bn = n - prefix - suffix;
        int cm = m - prefix - suffix;
        if (bn == 0 && cm == 0) {
            return marks;
        }
        if (bn == 0) {
            for (int i = 0; i < cm; i++) {
                marks[prefix + i] = ADDED;
            }
            return marks;
        }
        if (cm == 0) {
            int at = Math.min(prefix, m - 1);
            if (at >= 0) {
                marks[at] |= DELETED_ABOVE;
            }
            return marks;
        }
        if ((long) bn * cm > MAX_CELLS) {
            for (int i = 0; i < cm; i++) {
                marks[prefix + i] = MODIFIED;
            }
            return marks;
        }

        // LCS over the middle, with hashes to keep comparisons cheap.
        int[] hb = new int[bn];
        int[] hc = new int[cm];
        for (int i = 0; i < bn; i++) {
            hb[i] = base[prefix + i].hashCode();
        }
        for (int j = 0; j < cm; j++) {
            hc[j] = cur[prefix + j].hashCode();
        }
        int[][] dp = new int[bn + 1][];
        dp[bn] = new int[cm + 1];
        for (int i = bn - 1; i >= 0; i--) {
            dp[i] = new int[cm + 1];
            for (int j = cm - 1; j >= 0; j--) {
                if (hb[i] == hc[j] && base[prefix + i].equals(cur[prefix + j])) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }
        // Walk the alignment; a run of removals next to a run of additions is a modification.
        int i = 0;
        int j = 0;
        int removed = 0;
        int addedStart = -1;
        int added = 0;
        while (i < bn || j < cm) {
            if (i < bn && j < cm && hb[i] == hc[j] && base[prefix + i].equals(cur[prefix + j])) {
                flush(marks, prefix, removed, addedStart, added, j);
                removed = 0;
                added = 0;
                addedStart = -1;
                i++;
                j++;
            } else if (j < cm && (i >= bn || dp[i][j + 1] >= dp[i + 1][j])) {
                if (addedStart < 0) {
                    addedStart = j;
                }
                added++;
                j++;
            } else {
                removed++;
                i++;
            }
        }
        flush(marks, prefix, removed, addedStart, added, j);
        return marks;
    }

    private static void flush(byte[] marks, int prefix, int removed, int addedStart, int added, int j) {
        if (added > 0) {
            int modified = Math.min(removed, added);
            for (int k = 0; k < added; k++) {
                marks[prefix + addedStart + k] = k < modified ? MODIFIED : ADDED;
            }
        } else if (removed > 0) {
            int at = Math.min(prefix + j, marks.length - 1);
            if (at >= 0) {
                marks[at] |= DELETED_ABOVE;
            }
        }
    }

    private static String[] split(String text) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (!l.isEmpty() && l.charAt(l.length() - 1) == '\r') {
                lines[i] = l.substring(0, l.length() - 1);
            }
        }
        return lines;
    }

    static int count(byte[] marks, byte kind) {
        int c = 0;
        for (byte b : marks) {
            if ((b & 3) == kind) {
                c++;
            }
        }
        return c;
    }

    static byte[] empty(int lines) {
        byte[] b = new byte[Math.max(1, lines)];
        Arrays.fill(b, NONE);
        return b;
    }
}
