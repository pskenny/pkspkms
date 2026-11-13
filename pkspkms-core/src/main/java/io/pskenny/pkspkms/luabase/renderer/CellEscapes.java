package io.pskenny.pkspkms.luabase.renderer;

// Markdown-cell hygiene (BUGS.md B17): a computed value containing `|` or a
// newline corrupts the emitted table — escaped or flattened before rendering.
// Nulls render empty instead of the string "null".
//
// Cell-surface cap (BUGS.md M2): a malicious/huge computed value — e.g.
// `string.rep("A", 1e8)` — must not inflate the rendered view without bound;
// oversized cells truncate with a visible marker.
final class CellEscapes {

    /** Per-cell character ceiling for rendered views. */
    static final int MAX_CELL_CHARS = 64_000;

    private CellEscapes() {}

    static String clamp(String text) {
        if (text.length() <= MAX_CELL_CHARS) {
            return text;
        }
        return text.substring(0, MAX_CELL_CHARS) + "… [cell truncated at " + MAX_CELL_CHARS + " chars]";
    }

    static String mdCell(Object value) {
        if (value == null) {
            return "";
        }
        String text = clamp(String.valueOf(value));
        // Backslash first, then the pipe — escaping only the pipe leaves the
        // preceding `\` unpaired and the cell still splits (BUGS.md D1)
        text = text.replace("\\", "\\\\");
        text = text.replace("|", "\\|");
        text = text.replace("\r", "");
        text = text.replace("\n", " ");
        return text;
    }
}
