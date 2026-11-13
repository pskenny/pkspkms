package io.pskenny.pkspkms.luabase.renderer;

/**
 * Parses a column spec — the Lua value-expression strings the converters emit
 * for {@code order:} lists, shaped as {@code EXPRESSION, "Header"} — into its
 * expression and header label.
 *
 * The old parser ({@code lastIndexOf(",") + 3}) broke whenever the header
 * label itself contained a comma (BUGS.md B17): the last comma then sits
 * inside the label. This parser works from the quote direction instead: the
 * label is always the final quoted run, so the expression is everything
 * before its opening quote. A fallback keeps the legacy shape working for
 * hand-written labels without quotes.
 */
final class ColSpec {

    private ColSpec() {}

    /** Returns {@code {expression, headerLabel}}. */
    static String[] parse(String colSpec) {
        String text = colSpec == null ? "" : colSpec.trim();

        // Preferred shape: ...EXPRESSION, "LABEL"
        if (text.endsWith("\"")) {
            // BUGS.md D1: walk back over escaped quotes — a label like
            // "Note \"x\"" means the second-to-last quote is INSIDE the
            // label, so a plain lastIndexOf("") truncates mid-argument
            int openQuote = openQuoteOf(text);
            if (openQuote > 0 && isSeparatorBefore(text, openQuote)) {
                String label = text.substring(openQuote + 1, text.length() - 1);
                String expression = text.substring(0, openQuote);
                // Drop the ", " separator tail; verify the separator so plain
                // file paths with a comma in the expression don't over-trim
                if (expression.endsWith(", ")) {
                    expression = expression.substring(0, expression.length() - 2);
                } else if (expression.endsWith(",")) {
                    expression = expression.substring(0, expression.length() - 1);
                }
                return new String[]{expression, label};
            }
        }

        // Legacy fallback: everything up to the last comma, label from +3
        int lastComma = text.lastIndexOf(',');
        if (lastComma >= 0 && lastComma + 3 <= text.length() - 1) {
            return new String[]{
                    text.substring(0, lastComma),
                    text.substring(lastComma + 3, text.length() - 1)
            };
        }
        // Degenerate: whole spec as the expression, no label
        return new String[]{text, ""};
    }

    // Index of the opening quote of the LAST label run: skip escaped quotes
    // scanning from the end
    private static int openQuoteOf(String text) {
        int close = text.length() - 1; // final quote
        for (int i = close - 1; i >= 0; i--) {
            if (text.charAt(i) == '"' && !isEscaped(text, i)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isEscaped(String text, int at) {
        int backslashes = 0;
        for (int i = at - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    // The expression must end in a `", "`-shaped separator for the parsed
    // label to be real (not e.g. `foo, "x"` inside an expression's own arg)
    private static boolean isSeparatorBefore(String text, int openQuote) {
        if (openQuote < 2) {
            return false;
        }
        if (text.charAt(openQuote - 1) != ' ') {
            return false;
        }
        int prev = text.charAt(openQuote - 2);
        return prev == ',' || prev == ' ';
    }
}
