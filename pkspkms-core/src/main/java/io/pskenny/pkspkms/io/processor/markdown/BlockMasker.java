package io.pskenny.pkspkms.io.processor.markdown;

/**
 * Blanks the inside of fenced code blocks so link/wikilink/base detectors
 * never match demo content (BUGS.md B14/B15). Output is the same length as
 * the input — only non-newline characters inside fences become spaces — so
 * any index math callers do on the unmasked string (e.g. Export's
 * frontmatterEnd) stays valid.
 *
 * Scope: block fences only (``` and ~~~, commonmark indentation rules).
 * Inline spans and indented code blocks are deliberately out of scope.
 */
public final class BlockMasker {

    private BlockMasker() {}

    public static String maskFencedBlocks(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        if (content.indexOf('`') == -1 && content.indexOf('~') == -1) {
            return content;
        }

        String[] lines = content.split("\n", -1);
        StringBuilder out = new StringBuilder(content.length());
        boolean inFence = false;
        boolean exempt = false; // base/luabase fence: tracked for state, interior not masked
        char fenceChar = '`';
        int fenceRun = 0;

        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];

            if (!inFence) {
                int[] opener = openerRun(line);
                if (opener == null) {
                    out.append(line);
                    continue;
                }
                int indent = opener[0];
                int run = opener[1];
                inFence = true;
                fenceChar = line.charAt(indent);
                fenceRun = run;
                // ```base / ```luabase blocks are themselves fenced code —
                // Obsidian Bases. Track their fence for closer matching but
                // keep the interior visible, so the YAML stays detectable;
                // demo blocks inside another (outer) fence are still masked
                // wholesale by that outer fence's interior branch.
                String info = line.substring(indent + run).strip().toLowerCase(java.util.Locale.ROOT);
                exempt = info.equals("base") || info.equals("luabase");
                out.append(line);
            } else {
                int closeRunEnd = closerRun(line, fenceChar, fenceRun);
                if (closeRunEnd >= 0) {
                    out.append(line);
                    inFence = false;
                    exempt = false;
                } else if (exempt) {
                    out.append(line);
                } else {
                    for (int c = 0; c < line.length(); c++) {
                        out.append(line.charAt(c) == '\n' ? '\n' : ' ');
                    }
                }
            }
        }
        return out.toString();
    }

    // Opener: up to 3 leading spaces, then 3+ of the same fence char
    private static int[] openerRun(String line) {
        int indent = 0;
        while (indent < line.length() && indent < 3 && line.charAt(indent) == ' ') {
            indent++;
        }
        if (indent >= line.length()) {
            return null;
        }
        char c = line.charAt(indent);
        if (c != '`' && c != '~') {
            // 4-space indents are indented code blocks, not fences
            return null;
        }
        int run = 0;
        while (indent + run < line.length() && line.charAt(indent + run) == c) {
            run++;
        }
        if (run < 3) {
            return null;
        }
        return new int[]{indent, run};
    }

    // Closer: same char, run >= open length, leading indent <= 3.
    // Returns the index just past the closing run, or -1.
    private static int closerRun(String line, char fenceChar, int fenceRun) {
        int indent = 0;
        while (indent < line.length() && indent < 3 && line.charAt(indent) == ' ') {
            indent++;
        }
        if (indent >= line.length() || line.charAt(indent) != fenceChar) {
            return -1;
        }
        int run = 0;
        int i = indent;
        while (i < line.length() && line.charAt(i) == fenceChar) {
            run++;
            i++;
        }
        if (run < fenceRun) {
            return -1;
        }
        // Commonmark: the closer must consist of only fence characters and
        // (optional) trailing whitespace
        for (int j = i; j < line.length(); j++) {
            if (!Character.isWhitespace(line.charAt(j))) {
                return -1;
            }
        }
        return i;
    }
}
