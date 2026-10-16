package com.sphere.core.rootbase;

/**
 * ROOT's TString (core/base) as the Java translations of ROOT use it: a
 * TString is an immutable {@link String} there, and every TString operation
 * that changes the string is written as an assignment of what this class
 * returns, with TString's semantics:
 *
 * <ul>
 *   <li>{@code s(start, len)} is a TSubString clamped to the string, and the
 *       null substring (empty) when start is past the end or len is not
 *       positive ({@link #sub});</li>
 *   <li>assigning to a TSubString replaces those characters, nothing when it
 *       is null ({@link #replace});</li>
 *   <li>{@code s[i]} reads the terminating '\0' at the length ({@link #at});</li>
 *   <li>Resize truncates or pads with blanks; ToUpper is C's toupper.</li>
 * </ul>
 *
 * <p>C strings: {@code char[]} buffers terminated by '\0' ({@link #cstr},
 * {@link #snprintf}, {@link #strcpy}) and the string.h functions on strings
 * (strncmp, strcmp, strstr, strspn, strlen), which stop at a '\0'.
 */
public final class TStr {

    private TStr() {
    }

    /** {@code new TString[n]}: n empty strings. */
    public static String[] array(int n) {
        final String[] a = new String[n];
        java.util.Arrays.fill(a, "");
        return a;
    }

    /** {@code s(start, len)}: TString::operator()(Ssiz_t, Ssiz_t), a copy of the TSubString. */
    public static String sub(String s, int start, int len) {
        final int n = s.length();
        if (start < n && len > 0) {
            if (start + len > n) len = n - start;
            return s.substring(start, start + len);
        }
        return "";
    }

    /** {@code s(start, len) = t}: TSubString::operator=, s with those characters replaced by t. */
    public static String replace(String s, int start, int len, String t) {
        final int n = s.length();
        if (start < n && len > 0) {
            if (start + len > n) len = n - start;
            return s.substring(0, start) + t + s.substring(start + len);
        }
        return s;
    }

    /** {@code s[i]}: the character, '\0' at the length. */
    public static char at(String s, int i) {
        return i == s.length() ? '\0' : s.charAt(i);
    }

    /** {@code s[i] = c}. */
    public static String set(String s, int i, char c) {
        if (i == s.length()) return c == '\0' ? s : s + c;
        final char[] a = s.toCharArray();
        a[i] = c;
        return new String(a);
    }

    /** TString::ToUpper: toupper of the C locale on each character. */
    public static String upper(String s) {
        final char[] a = s.toCharArray();
        for (int i = 0; i < a.length; i++) {
            if (a[i] >= 'a' && a[i] <= 'z') a[i] = (char) (a[i] - 32);
        }
        return new String(a);
    }

    /** TString::Resize: truncated, or padded with blanks. */
    public static String resize(String s, int n) {
        if (n < s.length()) return s.substring(0, Math.max(n, 0));
        return s + " ".repeat(n - s.length());
    }

    /** TString == TString (or const char*). */
    public static boolean eq(String a, String b) {
        return a.equals(b);
    }

    /** The C string a '\0' would end: what C reads of s. */
    private static int clen(CharSequence s) {
        final int n = s.length();
        for (int i = 0; i < n; i++) {
            if (s.charAt(i) == '\0') return i;
        }
        return n;
    }

    public static int strlen(String s) {
        return clen(s);
    }

    /** strncmp: the first difference of the unsigned characters, within n and before a '\0'. */
    public static int strncmp(String a, String b, int n) {
        final int la = clen(a);
        final int lb = clen(b);
        for (int i = 0; i < n; i++) {
            final int ca = i < la ? (a.charAt(i) & 0xFF) : 0;
            final int cb = i < lb ? (b.charAt(i) & 0xFF) : 0;
            if (ca != cb) return ca - cb;
            if (ca == 0) return 0;
        }
        return 0;
    }

    public static int strcmp(String a, String b) {
        return strncmp(a, b, Integer.MAX_VALUE);
    }

    /** strstr(h, n) != NULL. */
    public static boolean strstr(String h, String n) {
        return h.substring(0, clen(h)).contains(n.substring(0, clen(n)));
    }

    /** strspn: the length of the leading part of s made of characters of accept. */
    public static int strspn(String s, String accept) {
        final int n = clen(s);
        final String acc = accept.substring(0, clen(accept));
        int i = 0;
        while (i < n && acc.indexOf(s.charAt(i)) >= 0) i++;
        return i;
    }

    /** The string a char buffer holds: up to its '\0'. */
    public static String cstr(char[] a) {
        int n = 0;
        while (n < a.length && a[n] != '\0') n++;
        return new String(a, 0, n);
    }

    /** snprintf(buf, size, ...) of an already formatted text: at most size-1 characters and the '\0'. */
    public static int snprintf(char[] buf, int size, String formatted) {
        if (size > 0) {
            final int n = Math.min(formatted.length(), size - 1);
            formatted.getChars(0, n, buf, 0);
            buf[n] = '\0';
        }
        return formatted.length();
    }

    /** strcpy(buf, s). */
    public static void strcpy(char[] buf, String s) {
        s.getChars(0, s.length(), buf, 0);
        buf[s.length()] = '\0';
    }
}
