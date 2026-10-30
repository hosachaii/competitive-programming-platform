package com.cpplatform.service;

/**
 * Compares a program's stdout against a test case's expected output. Competitive
 * judges are lenient about insignificant whitespace, so comparison is done on a
 * normalized form:
 *   - CRLF / CR line endings are converted to LF,
 *   - trailing spaces/tabs on each line are dropped,
 *   - leading/trailing blank lines (and surrounding whitespace) are trimmed.
 * Whitespace WITHIN a line (e.g. the single spaces between tokens) is preserved.
 */
public final class OutputComparator {

    private OutputComparator() {}

    public static String normalize(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n")
                .replace("\r", "\n")
                .replaceAll("[ \\t]+\\n", "\n")
                .strip();
    }

    public static boolean matches(String actual, String expected) {
        return normalize(actual).equals(normalize(expected));
    }
}
