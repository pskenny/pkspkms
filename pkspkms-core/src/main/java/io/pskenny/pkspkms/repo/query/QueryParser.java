package io.pskenny.pkspkms.repo.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Lucene classic-style query parser producing a flat clause list.
 *
 * Operators only affect their adjacent clauses: "a AND b OR c" means
 * a MUST, b MUST, c SHOULD (classic Lucene quirk, preserved). Conflicts
 * resolve MUST_NOT &gt; MUST &gt; SHOULD. Bare terms match any property value.
 */
public final class QueryParser {

    /** Default "field" for bare terms: matches any property value. */
    public static final String ALL_FIELD = "_all";

    /** Fresh lexer context per parse — safe to share one instance. */
    public Query parse(String input) {
        return new Ctx(input == null ? "" : input).parse();
    }

    private static final class Ctx {

        private enum T { WORD, PHRASE, COLON, PLUS, MINUS, LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE, AND, OR, NOT, EOF }

        private record Token(T type, String text, int offset) {}

        private final String input;
        private final List<Token> tokens = new ArrayList<>();
        private int pos = 0;

        Ctx(String input) {
            this.input = input;
            lex();
        }

        Query parse() {
            return new Query(parseClauses(null));
        }

    // --- clause list ---

    private List<Query.Clause> parseClauses(T terminator) {
        List<Query.Clause> clauses = new ArrayList<>();
        Query.Occur pending = Query.Occur.SHOULD;
        boolean expectingClause = false;
        while (true) {
            Token t = peek();
            if (t.type() == T.EOF) {
                if (terminator != null) {
                    throw error(t, "Expected '" + terminatorName(terminator) + "'");
                }
                if (expectingClause) {
                    throw error(t, "Expected a search clause");
                }
                return clauses;
            }
            if (t.type() == terminator) {
                if (expectingClause) {
                    throw error(t, "Expected a search clause");
                }
                consume();
                return clauses;
            }

            switch (t.type()) {
                case PLUS -> { consume(); pending = Query.Occur.MUST; expectingClause = true; continue; }
                case MINUS -> { consume(); pending = Query.Occur.MUST_NOT; expectingClause = true; continue; }
                case NOT -> { consume(); pending = Query.Occur.MUST_NOT; expectingClause = true; continue; }
                case AND -> { consume(); pending = Query.Occur.MUST; promotePrevious(clauses); expectingClause = true; continue; }
                case OR -> { consume(); pending = Query.Occur.SHOULD; expectingClause = true; continue; }
                default -> { }
            }
            clauses.add(new Query.Clause(pending, parseClauseBody()));
            pending = Query.Occur.SHOULD;
            expectingClause = false;
        }
    }

    // "a AND b": AND promotes the preceding SHOULD clause to MUST as well
    private void promotePrevious(List<Query.Clause> clauses) {
        if (clauses.isEmpty()) {
            return;
        }
        Query.Clause last = clauses.get(clauses.size() - 1);
        if (last.occur() == Query.Occur.SHOULD) {
            clauses.set(clauses.size() - 1, new Query.Clause(Query.Occur.MUST, last.node()));
        }
    }

    private QueryNode parseClauseBody() {
        Token t = peek();
        return switch (t.type()) {
            case WORD -> {
                String word = t.text();
                if (peekNext().type() == T.COLON) {
                    consume(); // field
                    consume(); // colon
                    yield parseFieldBody(word);
                }
                consume();
                yield classifyBare(word);
            }
            case PHRASE -> {
                consume();
                yield new QueryNode.AnyField(new QueryNode.FieldEq(ALL_FIELD, t.text()));
            }
            case LPAREN -> {
                consume();
                yield new QueryNode.Bool(parseClauses(T.RPAREN));
            }
            case COLON -> throw error(t, "Expected a field before ':'");
            case LBRACKET, LBRACE -> throw error(t, "Range filters need a field, e.g. price:[1 TO 5]");
            case PLUS, MINUS, AND, OR, NOT -> throw error(t, "Expected a search clause");
            default -> throw error(t, "Unexpected input");
        };
    }

    private QueryNode classifyBare(String word) {
        if (word.equals("*")) {
            return new QueryNode.AllMatch();
        }
        if (word.contains("*") || word.contains("?")) {
            return new QueryNode.AnyField(new QueryNode.FieldWildcard(ALL_FIELD, word));
        }
        return new QueryNode.AnyField(new QueryNode.FieldEq(ALL_FIELD, word));
    }

    // --- field values ---

    private QueryNode parseFieldBody(String field) {
        Token t = peek();
        return switch (t.type()) {
            case WORD -> {
                consume();
                yield parseFieldWord(field, t.text());
            }
            case PHRASE -> {
                consume();
                yield new QueryNode.FieldEq(field, t.text());
            }
            case LPAREN -> {
                consume();
                yield parseFieldGroup(field);
            }
            case LBRACKET, LBRACE -> parseRange(field, t);
            default -> throw error(t, "Expected a value after '" + field + ":'");
        };
    }

    private QueryNode parseFieldWord(String field, String word) {
        if (word.equals("*")) {
            return new QueryNode.FieldExists(field);
        }
        if (word.contains("*") || word.contains("?")) {
            return new QueryNode.FieldWildcard(field, word);
        }
        return new QueryNode.FieldEq(field, word);
    }

    private QueryNode parseFieldGroup(String field) {
        List<QueryNode> values = new ArrayList<>();
        boolean sawAnd = false;
        boolean sawOr = false;
        while (true) {
            Token t = peek();
            switch (t.type()) {
                case WORD -> {
                    consume();
                    values.add(parseFieldWord(field, t.text()));
                }
                case PHRASE -> {
                    consume();
                    values.add(new QueryNode.FieldEq(field, t.text()));
                }
                default -> throw error(t, "Expected a value in field group");
            }
            Token sep = peek();
            if (sep.type() == T.RPAREN) {
                consume();
                break;
            }
            if (sep.type() == T.AND) {
                consume();
                sawAnd = true;
            } else if (sep.type() == T.OR) {
                consume();
                sawOr = true;
            } else {
                throw error(sep, "Expected ')' in field group");
            }
            if (sawAnd && sawOr) {
                throw error(sep, "Mixing AND and OR inside a field group is not supported");
            }
        }
        if (values.size() == 1) {
            return values.get(0);
        }
        return sawAnd ? new QueryNode.And(values) : new QueryNode.Or(values);
    }

    private QueryNode parseRange(String field, Token open) {
        boolean loIncl = open.type() == T.LBRACKET;
        consume();
        String lo = parseBound();
        Token to = peek();
        if (to.type() != T.WORD || !to.text().equalsIgnoreCase("TO")) {
            throw error(to, "Expected 'TO' in range");
        }
        consume();
        String hi = parseBound();
        Token close = peek();
        if (close.type() != T.RBRACKET && close.type() != T.RBRACE) {
            throw error(close, "Expected ']' or '}' to close range");
        }
        boolean hiIncl = close.type() == T.RBRACKET;
        consume();
        return new QueryNode.FieldRange(field, lo, hi, loIncl, hiIncl);
    }

    private String parseBound() {
        Token t = peek();
        if (t.type() == T.WORD) {
            consume();
            return t.text().equals("*") ? null : t.text();
        }
        if (t.type() == T.PHRASE) {
            consume();
            return t.text();
        }
        throw error(t, "Expected a range bound or '*'");
    }

    // --- lexer ---

    private void lex() {
        int i = 0;
        boolean termContext = false; // right after ':' '+' '-' '[' '{': +/- and friends are literal
        while (i < input.length()) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                termContext = false;
                continue;
            }
            switch (c) {
                case ':' -> { tokens.add(new Token(T.COLON, ":", i)); i++; termContext = true; }
                case '(' -> { tokens.add(new Token(T.LPAREN, "(", i)); i++; termContext = false; }
                case ')' -> { tokens.add(new Token(T.RPAREN, ")", i)); i++; termContext = false; }
                case '[' -> { tokens.add(new Token(T.LBRACKET, "[", i)); i++; termContext = true; }
                case ']' -> { tokens.add(new Token(T.RBRACKET, "]", i)); i++; termContext = false; }
                case '{' -> { tokens.add(new Token(T.LBRACE, "{", i)); i++; termContext = true; }
                case '}' -> { tokens.add(new Token(T.RBRACE, "}", i)); i++; termContext = false; }
                case '"' -> { i = lexPhrase(i + 1); termContext = false; }
                default -> {
                    String op = operatorAt(i);
                    if (op != null) {
                        tokens.add(new Token(
                                op.equals("AND") ? T.AND : op.equals("OR") ? T.OR : T.NOT,
                                op, i));
                        i += op.length();
                        termContext = false;
                    } else if (c == '+' || c == '-' || c == '!') {
                        tokens.add(new Token(
                                c == '+' ? T.PLUS : c == '-' ? T.MINUS : T.NOT,
                                String.valueOf(c), i));
                        i++;
                        termContext = true;
                    } else if (c == '&' && i + 1 < input.length() && input.charAt(i + 1) == '&') {
                        tokens.add(new Token(T.AND, "&&", i));
                        i += 2;
                        termContext = false;
                    } else if (c == '|' && i + 1 < input.length() && input.charAt(i + 1) == '|') {
                        tokens.add(new Token(T.OR, "||", i));
                        i += 2;
                        termContext = false;
                    } else if (c == '~') {
                        throw new QueryParseException(i, "Fuzzy search (~) is not supported (escape it as \\~ for a literal tilde)");
                    } else {
                        i = lexWord(i);
                        termContext = false;
                    }
                }
            }
        }
        tokens.add(new Token(T.EOF, "", i));
    }

    // Standalone AND/OR/NOT keyword check: prefix match with word-boundary terminator
    private String operatorAt(int i) {
        for (String kw : new String[]{"AND", "OR", "NOT"}) {
            if (input.regionMatches(true, i, kw, 0, kw.length())) {
                int end = i + kw.length();
                if (end == input.length()
                        || Character.isWhitespace(input.charAt(end))
                        || input.charAt(end) == '(' || input.charAt(end) == ')') {
                    return kw;
                }
            }
        }
        return null;
    }

    private int lexWord(int start) {
        int i = start;
        StringBuilder sb = new StringBuilder();
        while (i < input.length()) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c) || ":()[]{}\"~".indexOf(c) >= 0) {
                break;
            }
            if (c == '\\' && i + 1 < input.length()) {
                sb.append(input.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '&' && i + 1 < input.length() && input.charAt(i + 1) == '&') {
                break;
            }
            if (c == '|' && i + 1 < input.length() && input.charAt(i + 1) == '|') {
                break;
            }
            sb.append(c);
            i++;
        }
        tokens.add(new Token(T.WORD, sb.toString(), start));
        return i;
    }

    private int lexPhrase(int start) {
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c == '\\' && i + 1 < input.length()) {
                sb.append(input.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '"') {
                tokens.add(new Token(T.PHRASE, sb.toString(), start - 1));
                return i + 1;
            }
            sb.append(c);
            i++;
        }
        throw new QueryParseException(start, "Unmatched quote in phrase");
    }

    // --- token helpers ---

    private Token peek() {
        return tokens.get(pos);
    }

    private Token peekNext() {
        return tokens.get(Math.min(pos + 1, tokens.size() - 1));
    }

    private void consume() {
        pos++;
    }

    private QueryParseException error(Token t, String message) {
        return new QueryParseException(t.offset(), message);
    }

    private static String terminatorName(T t) {
        return switch (t) {
            case RPAREN -> ")";
            case RBRACKET -> "]";
            case RBRACE -> "}";
            default -> t.name();
        };
    }
    }
}
