package io.pskenny.pkspkms.repo.sqlite;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SqlQueryParser {
    private List<QueryLexer.Token> tokens = List.of();
    private int current = 0;
    private final List<Object> parameters = new ArrayList<>();

    public record ParseResult(String sql, List<Object> params) {
        public ParseResult {
            params = List.copyOf(params);
        }
    }

    public ParseResult parse(String query) {
        this.tokens = new QueryLexer(query).tokenize();
        this.current = 0;
        this.parameters.clear();
        return new ParseResult(expression(), parameters);
    }

    public String parseToFullJoinQuery(String query) {
        ParseResult result = parse(query);
        return "SELECT * FROM FILES WHERE %s".formatted(result.sql());
    }

    private String expression() {
        StringBuilder left = new StringBuilder(term());
        while (match(QueryLexer.TokenType.OR)) {
            left.append(" OR ").append(term());
        }
        return left.toString();
    }

    private String term() {
        StringBuilder left = new StringBuilder(factor());
        while (match(QueryLexer.TokenType.AND)) {
            left.append(" AND ").append(factor());
        }
        return left.toString();
    }

    private String factor() {
        if (match(QueryLexer.TokenType.NOT)) return "NOT " + factor();
        if (match(QueryLexer.TokenType.LPAREN)) {
            String expr = expression();
            consume(QueryLexer.TokenType.RPAREN, "Expect ')' after expression.");
            return "(" + expr + ")";
        }
        if (isAtEnd()) return "1=1";
        return translateComparison(advance().value());
    }

    private String translateComparison(String raw) {
        String trimmed = raw.trim();
        // Added ':' as an optional shorthand for LIKE
        Pattern p = Pattern.compile("(?i)([\\w.]+)\\s*(>=|<=|!=|=|>|<|LIKE|:)\\s*(.*)");
        Matcher m = p.matcher(trimmed);

        if (!m.matches()) {
            if (trimmed.matches("[\\w.]+")) {
                return "json_type(properties, '$.%s') IS NOT NULL".formatted(trimmed);
            }
            return "1=1";
        }

        String key = m.group(1);
        String op = m.group(2).toUpperCase(Locale.ROOT);
        String val = m.group(3).trim().replaceAll("^['\"]|['\"]$", "");

        // --- NEW LOGIC START ---
        // 1. If the value contains a '*', convert it to SQL's '%'
        if (val.contains("*")) {
            val = val.replace("*", "%");
            // 2. If they used '=' or ':', force the operator to 'LIKE'
            if (op.equals("=") || op.equals(":")) {
                op = "LIKE";
            }
        }

        // 3. Just in case they use ':' as a general shorthand
        if (op.equals(":")) op = "LIKE";
        // --- NEW LOGIC END ---

        String formattedValue = val.matches("-?\\d+(\\.\\d+)?") ? val : "'" + val.replace("'", "''") + "'";

        return String.format(
                "(json_extract(properties, '$.%1$s') %2$s %3$s OR " +
                        "EXISTS (SELECT 1 FROM json_each(properties, '$.%1$s') WHERE value %2$s %3$s))",
                key, op, formattedValue
        );
    }

    private boolean match(QueryLexer.TokenType type) {
        if (check(type)) { advance(); return true; }
        return false;
    }

    private boolean check(QueryLexer.TokenType type) {
        return !isAtEnd() && tokens.get(current).type() == type;
    }

    private QueryLexer.Token advance() {
        if (!isAtEnd()) current++;
        return tokens.get(current - 1);
    }

    private boolean isAtEnd() {
        return tokens.get(current).type() == QueryLexer.TokenType.EOF;
    }

    private void consume(QueryLexer.TokenType type, String message) {
        if (!check(type)) throw new IllegalStateException(message);
        advance();
    }

    static class QueryLexer {
        public enum TokenType { LPAREN, RPAREN, AND, OR, NOT, COMPARISON, EOF }
        public record Token(TokenType type, String value) {}
        private final String input;
        private int pos = 0;

        public QueryLexer(String input) { this.input = input; }
        public List<Token> tokenize() {
            List<Token> tokens = new ArrayList<>();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (Character.isWhitespace(c)) { pos++; continue; }

                if (c == '(') { tokens.add(new Token(TokenType.LPAREN, "(")); pos++; }
                else if (c == ')') { tokens.add(new Token(TokenType.RPAREN, ")")); pos++; }
                // Use isLogical to ensure these are actual keywords, not prefixes of words
                else if (isKeyword("AND", pos)) { tokens.add(new Token(TokenType.AND, "AND")); pos += 3; }
                else if (isKeyword("OR", pos)) { tokens.add(new Token(TokenType.OR, "OR")); pos += 2; }
                else if (isKeyword("NOT", pos)) { tokens.add(new Token(TokenType.NOT, "NOT")); pos += 3; }
                else { tokens.add(new Token(TokenType.COMPARISON, readComparison())); }
            }
            tokens.add(new Token(TokenType.EOF, ""));
            return tokens;
        }

        // Helper to check for standalone keywords
        private boolean isKeyword(String kw, int index) {
            String up = input.toUpperCase(Locale.ROOT).substring(index);
            if (!up.startsWith(kw)) return false;
            int nextCharIdx = kw.length();
            return up.length() == nextCharIdx || Character.isWhitespace(up.charAt(nextCharIdx)) || up.charAt(nextCharIdx) == '(' || up.charAt(nextCharIdx) == ')';
        }

        private String readComparison() {
            StringBuilder sb = new StringBuilder();
            boolean inQuotes = false;
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '\'' || c == '"') inQuotes = !inQuotes;
                if (!inQuotes && (c == '(' || c == ')' || isLogical(pos))) break;
                sb.append(c);
                pos++;
            }
            return sb.toString().trim();
        }

        private boolean isLogical(int index) {
            String up = input.toUpperCase(Locale.ROOT).substring(index);
            return (up.startsWith("AND") && (up.length() == 3 || Character.isWhitespace(up.charAt(3)))) ||
                    (up.startsWith("OR") && (up.length() == 2 || Character.isWhitespace(up.charAt(2))));
        }
    }
}