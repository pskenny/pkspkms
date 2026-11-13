package io.pskenny.pkspkms.io;

/**
 * Frontmatter line-bounds oracle — the single grammar shared by every
 * consumer that slices a note's frontmatter region (the MarkdownProcessor
 * body slice, the YamlFrontmatterReader YAML extract, and Export's strip).
 *
 * Grammar (matching the YAML reader's {@code \A---[ \t]*\r?\n ... ^---[ \t]*$}):
 * - the opener must be LINE 0, exactly {@code ---} plus optional
 *   spaces/tabs, ended by a line terminator ({@code ----} is not frontmatter);
 * - the closer is the first LATER line that is exactly {@code ---} plus
 *   optional spaces/tabs — a {@code ---} inside a YAML value or a block
 *   scalar never closes the region;
 * - no closer → no frontmatter region.
 */
public final class Frontmatter {

    private Frontmatter() {}

    /**
     * Index just past the closer line's terminator, or -1 when the document
     * has no frontmatter region.
     */
    public static int endOf(String content) {
        if (content == null || !content.startsWith("---")) {
            return -1;
        }
        int len = content.length();
        int p = 3;
        while (p < len && (content.charAt(p) == ' ' || content.charAt(p) == '\t')) {
            p++;
        }
        if (p >= len) {
            return -1; // unterminated opener line — no region
        }
        char opener = content.charAt(p);
        if (opener != '\r' && opener != '\n') {
            return -1; // 4-plus dashes ("---- …") never opens frontmatter
        }
        p = (opener == '\r')
                ? (p + 1 < len && content.charAt(p + 1) == '\n' ? p + 2 : p + 1)
                : p + 1;

        while (p < len) {
            int lineEnd = content.indexOf('\n', p);
            if (lineEnd < 0) {
                lineEnd = len;
            }
            int e = lineEnd;
            if (e > p && content.charAt(e - 1) == '\r') {
                e--;
            }
            if (isDashLine(content, p, e)) {
                // just past the closer's terminator (== len when the closer
                // line had no trailing newline)
                return Math.min(lineEnd + 1, len);
            }
            if (lineEnd == len) {
                break; // last line had no newline
            }
            p = lineEnd + 1;
        }
        return -1;
    }

    /**
     * The YAML body: from the opener line's terminator up to (not including)
     * the closer line's start. Empty string when there is no frontmatter
     * region.
     */
    public static String yamlBlock(String content) {
        int end = endOf(content);
        if (end < 0) {
            return "";
        }
        int openerEnd = indexOfOpenerTerminator(content);
        if (openerEnd < 0) {
            return "";
        }
        int start = openerEnd + 1;
        // rescan lines to find the closer line's own start
        int len = content.length();
        int p = start;
        while (p < len) {
            int lineEnd = content.indexOf('\n', p);
            if (lineEnd < 0) {
                lineEnd = len;
            }
            int e = lineEnd;
            if (e > p && content.charAt(e - 1) == '\r') {
                e--;
            }
            if (isDashLine(content, p, e)) {
                return start <= p ? content.substring(start, p) : "";
            }
            if (lineEnd == len) {
                break;
            }
            p = lineEnd + 1;
        }
        return "";
    }

    // Opener line always begins at 0 ("---"); this returns the index of the
    // terminator that ends it, or -1 when the opener is unterminated.
    private static int indexOfOpenerTerminator(String content) {
        int len = content.length();
        int p = 3;
        while (p < len && (content.charAt(p) == ' ' || content.charAt(p) == '\t')) {
            p++;
        }
        if (p >= len) {
            return -1;
        }
        char c = content.charAt(p);
        if (c != '\r' && c != '\n') {
            return -1;
        }
        if (c == '\r' && p + 1 < len && content.charAt(p + 1) == '\n') {
            return p + 1;
        }
        return p;
    }

    private static boolean isDashLine(String content, int from, int to) {
        int k = from;
        while (k < to && content.charAt(k) == '-') {
            k++;
        }
        if (k - from != 3) {
            return false;
        }
        while (k < to && (content.charAt(k) == ' ' || content.charAt(k) == '\t')) {
            k++;
        }
        return k == to;
    }
}
