package io.pskenny.pkspkms.io.processor.markdown;

/**
 * Un-escapes markdown character escapes that commonmark's renderer emits when
 * re-serializing a section taken from an embedded note. A backslash is
 * removed only when it directly precedes one of commonmark's escapable ASCII
 * punctuation characters — everything else (C:\Users, \n, custom escapes)
 * survives verbatim. Replaces the old blanket backslash strip that corrupted
 * embed content (BUGS.md B8).
 */
public final class Unescape {

    private Unescape() {}

    public static String markdownPunctuation(String content) {
        if (content == null || content.indexOf('\\') == -1) {
            return content;
        }
        StringBuilder sb = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\\' && i + 1 < content.length() && isEscapable(content.charAt(i + 1))) {
                i++;
                sb.append(content.charAt(i)); // keep the escaped character, drop the backslash
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isEscapable(char c) {
        return c == '!' || c == '"' || c == '#' || c == '$' || c == '%' || c == '&'
                || c == '\'' || c == '(' || c == ')' || c == '*' || c == '+' || c == ','
                || c == '-' || c == '.' || c == '/' || c == ':' || c == ';' || c == '<'
                || c == '=' || c == '>' || c == '?' || c == '@' || c == '[' || c == ']'
                || c == '^' || c == '_' || c == '`' || c == '{' || c == '|' || c == '}'
                || c == '~' || c == '\\';
    }
}
