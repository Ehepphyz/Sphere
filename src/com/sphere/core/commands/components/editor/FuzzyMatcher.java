package com.sphere.components.editor;

/**
 * Scores how well a typed query matches a name, the way quick-open boxes do:
 * "gEB" finds getElementById, "tll" finds target_link_libraries. A prefix wins
 * over word starts, word starts over a scattered match.
 */
public final class FuzzyMatcher {

    public static final int NO_MATCH = Integer.MIN_VALUE;

    private FuzzyMatcher() {
    }

    /**
     * @param positions receives the matched indexes when not null (length at least query's)
     * @return a score, higher is better, or NO_MATCH
     */
    public static int score(String query, String candidate, int[] positions) {
        if (query == null || query.isEmpty()) {
            return 0;
        }
        if (candidate == null || candidate.length() < query.length()) {
            return NO_MATCH;
        }
        int m = query.length();
        int n = candidate.length();

        // Fast paths: a prefix.
        if (candidate.startsWith(query)) {
            fill(positions, 0, m);
            return 1000 - Math.min(n - m, 200) + (n == m ? 200 : 0);
        }
        if (candidate.regionMatches(true, 0, query, 0, m)) {
            fill(positions, 0, m);
            return 900 - Math.min(n - m, 200) + (n == m ? 150 : 0);
        }

        int greedy = greedy(query, candidate, positions);
        // A contiguous substring can beat a scattered greedy match.
        int sub = candidate.toLowerCase().indexOf(query.toLowerCase());
        if (sub > 0) {
            int s = 300 - sub * 2 - Math.min(n - m, 100) + (isBoundary(candidate, sub) ? 150 : 0);
            if (s > greedy) {
                fill(positions, sub, m);
                return s;
            }
        }
        return greedy;
    }

    private static int greedy(String query, String candidate, int[] positions) {
        int m = query.length();
        int n = candidate.length();
        int score = 0;
        int pos = 0;
        int previous = -2;
        int[] taken = positions != null ? positions : new int[m];
        for (int i = 0; i < m; i++) {
            char q = query.charAt(i);
            char ql = Character.toLowerCase(q);
            // Prefer the next occurrence on a word start, when there is one before the next plain one is far.
            int boundary = -1;
            int plain = -1;
            for (int j = pos; j < n; j++) {
                char c = candidate.charAt(j);
                if (Character.toLowerCase(c) != ql) {
                    continue;
                }
                if (plain < 0) {
                    plain = j;
                }
                if (isBoundary(candidate, j)) {
                    boundary = j;
                    break;
                }
            }
            if (plain < 0) {
                return NO_MATCH;
            }
            int j = plain;
            // Consecutive beats boundary only when it continues the previous match.
            if (boundary >= 0 && !(plain == previous + 1)) {
                j = boundary;
            }
            char c = candidate.charAt(j);
            int s = 10;
            if (isBoundary(candidate, j)) {
                s += 18;
            }
            if (c == q) {
                s += 4;
            }
            if (j == previous + 1) {
                s += 14;
            } else if (previous >= 0) {
                s -= Math.min(10, j - previous - 1);
            }
            if (i == 0) {
                s -= Math.min(20, j * 2);
            }
            score += s;
            taken[i] = j;
            previous = j;
            pos = j + 1;
        }
        score -= Math.min(30, (n - m) / 2);
        return score;
    }

    /** A word start: first char, after a separator, a lower-to-upper step, or a letter-digit step. */
    static boolean isBoundary(String s, int i) {
        if (i == 0) {
            return true;
        }
        char p = s.charAt(i - 1);
        char c = s.charAt(i);
        if (p == '_' || p == '-' || p == '.' || p == ' ' || p == '/' || p == '\\' || p == ':' || p == '(') {
            return true;
        }
        if (Character.isLowerCase(p) && Character.isUpperCase(c)) {
            return true;
        }
        return Character.isLetter(p) && Character.isDigit(c);
    }

    private static void fill(int[] positions, int from, int count) {
        if (positions == null) {
            return;
        }
        for (int k = 0; k < count && k < positions.length; k++) {
            positions[k] = from + k;
        }
    }
}
