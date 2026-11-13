package io.pskenny.pkspkms.luabase;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Static analysis of converter-generated Lua filter expressions.
//
// The recognizer is a strict whitelist: it accepts exactly the call shapes the
// NaiveBaseToLuaBaseConverter emits (hasPropertyValue(...), fileInFolder(...),
// numeric comparisons, not/and/or, parentheses) plus literal comparisons on
// getPropertyValue(...). Anything else — unknown functions, indexing
// (file.foo, file["x"]), method calls, whole-table use (pairs(file)),
// concatenation, dynamic arguments — is "unrecognized" and the caller falls
// back to coercing every property into the Lua table.
//
// Recognized filters carry the set of note properties they read, so
// LuaBaseInterpreter.evaluateExpression can build the per-row Lua table with
// only those keys instead of materializing the whole note. A recognized
// result is a proof, never a heuristic: the whitelist grammar is the only
// acceptance path, so a false negative just loses the optimization.
final class FilterAnalyzer {

    record Result(boolean recognized, Set<String> referencedProperties, FilterExpr ast) {
        static Result unrecognized() {
            return new Result(false, Set.of(), null);
        }
    }

    private static final Set<String> BOOLEAN_LITERALS = Set.of("true", "false", "nil");

    // Recognition gate = FUNCTION_ARITY below (the old FILTER_FUNCTIONS set
    // was dead weight — the recognizer only consults the arity map).

    // Exact arities of the whitelisted functions; anything else is not
    // converter-emitted and falls back.
    private static final Map<String, Integer> FUNCTION_ARITY = Map.ofEntries(
            Map.entry("hasProperty", 2),
            Map.entry("fileInFolder", 2),
            Map.entry("hasEmptyProperty", 2),
            Map.entry("hasPropertyValue", 3),
            Map.entry("hasPropertyValueIn", 3),
            Map.entry("hasPropertyContaining", 3),
            Map.entry("hasPropertyContainingAll", 3),
            Map.entry("hasPropertyValueStartsWith", 3),
            Map.entry("hasPropertyValueGreaterThan", 3),
            Map.entry("hasPropertyValueGreaterThanOrEqual", 3),
            Map.entry("hasPropertyValueLessThan", 3),
            Map.entry("hasPropertyValueLessThanOrEqual", 3),
            Map.entry("fileFieldStartsWith", 3),
            Map.entry("getPropertyValue", 3));

    static Result analyze(String expression) {
        if (expression == null || expression.isBlank()) {
            return Result.unrecognized();
        }
        try {
            Parser parser = new Parser(tokenize(expression));
            FilterExpr result = parser.parseExpression();
            parser.expectEnd();
            return new Result(true, parser.referencedProperties, result);
        } catch (UnrecognizedException e) {
            return Result.unrecognized();
        }
    }

    private static final class UnrecognizedException extends RuntimeException {
    }

    private enum Type {
        NAME, STRING, NUMBER, LPAREN, RPAREN, LBRACE, RBRACE, COMMA, SEMI,
        DOT, CONCAT, EQ, NEQ, LT, LE, GT, GE, AND, OR, NOT, END
    }

    private record Token(Type type, String text, int position) {
    }

    // Converters emit plain double-quoted values; escapes other than the two
    // listed are treated as unrecognized rather than guessed at.
    private static java.util.List<Token> tokenize(String expression) {
        java.util.List<Token> tokens = new java.util.ArrayList<>();
        int i = 0;
        int n = expression.length();
        while (i < n) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '(') { tokens.add(new Token(Type.LPAREN, "(", i)); i++; continue; }
            if (c == ')') { tokens.add(new Token(Type.RPAREN, ")", i)); i++; continue; }
            if (c == '{') { tokens.add(new Token(Type.LBRACE, "{", i)); i++; continue; }
            if (c == '}') { tokens.add(new Token(Type.RBRACE, "}", i)); i++; continue; }
            if (c == ',') { tokens.add(new Token(Type.COMMA, ",", i)); i++; continue; }
            if (c == ';') { tokens.add(new Token(Type.SEMI, ";", i)); i++; continue; }
            if (c == '"' || c == '\'') {
                int start = i;
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n && expression.charAt(i) != c) {
                    char ch = expression.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        char next = expression.charAt(i + 1);
                        if (next == '"' || next == '\'' || next == '\\') {
                            sb.append(next);
                            i += 2;
                            continue;
                        }
                        throw new UnrecognizedException();
                    }
                    sb.append(ch);
                    i++;
                }
                if (i >= n) throw new UnrecognizedException(); // unterminated
                i++; // closing quote
                tokens.add(new Token(Type.STRING, sb.toString(), start));
                continue;
            }
            if (Character.isDigit(c) || (c == '-' && i + 1 < n && Character.isDigit(expression.charAt(i + 1)))) {
                int start = i;
                if (c == '-') i++;
                boolean dot = false;
                while (i < n && (Character.isDigit(expression.charAt(i)) || (expression.charAt(i) == '.' && !dot))) {
                    if (expression.charAt(i) == '.') dot = true;
                    i++;
                }
                tokens.add(new Token(Type.NUMBER, expression.substring(start, i), start));
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < n && Character.isJavaIdentifierPart(expression.charAt(i))) i++;
                tokens.add(new Token(Type.NAME, expression.substring(start, i), start));
                continue;
            }
            if (c == '.') {
                if (i + 1 < n && expression.charAt(i + 1) == '.') {
                    tokens.add(new Token(Type.CONCAT, "..", i));
                    i += 2;
                } else {
                    tokens.add(new Token(Type.DOT, ".", i));
                    i++;
                }
                continue;
            }
            switch (c) {
                case '=':
                    if (i + 1 < n && expression.charAt(i + 1) == '=') {
                        tokens.add(new Token(Type.EQ, "==", i));
                        i += 2;
                        continue;
                    }
                    throw new UnrecognizedException(); // single '=' is assignment, not a filter
                case '~':
                    if (i + 1 < n && expression.charAt(i + 1) == '=') {
                        tokens.add(new Token(Type.NEQ, "~=", i));
                        i += 2;
                        continue;
                    }
                    throw new UnrecognizedException();
                case '<':
                    if (i + 1 < n && expression.charAt(i + 1) == '=') {
                        tokens.add(new Token(Type.LE, "<=", i));
                        i += 2;
                    } else {
                        tokens.add(new Token(Type.LT, "<", i));
                        i++;
                    }
                    continue;
                case '>':
                    if (i + 1 < n && expression.charAt(i + 1) == '=') {
                        tokens.add(new Token(Type.GE, ">=", i));
                        i += 2;
                    } else {
                        tokens.add(new Token(Type.GT, ">", i));
                        i++;
                    }
                    continue;
                default:
                    throw new UnrecognizedException();
            }
        }
        tokens.add(new Token(Type.END, "", n));
        return tokens;
    }

    private static final class Parser {
        private final java.util.List<Token> tokens;
        private int index;
        private final Set<String> referencedProperties = new LinkedHashSet<>();

        Parser(java.util.List<Token> tokens) {
            this.tokens = tokens;
        }

        // Lua precedence: `a and b or c` folds as ((a and b) or c) — the
        // converter's chains are parenthesized anyway, but the recognizer
        // stays the correctness gate for anything it accepts, so parse
        // exactly what Lua does.
        FilterExpr parseExpression() {
            FilterExpr left = parseAnd();
            while (true) {
                Token token = peek();
                if (token.type() == Type.NAME && token.text().equals("or")) {
                    index++;
                    FilterExpr right = parseAnd();
                    left = new FilterExpr.Or(left, right);
                    continue;
                }
                break;
            }
            return left;
        }

        private FilterExpr parseAnd() {
            FilterExpr left = parseOperand();
            while (true) {
                Token token = peek();
                if (token.type() == Type.NAME && token.text().equals("and")) {
                    index++;
                    FilterExpr right = parseOperand();
                    left = new FilterExpr.And(left, right);
                    continue;
                }
                break;
            }
            return left;
        }

        // One operand: not* ( '(' expr ')' | boolean literal | call | comparison )
        // C4 (BUGS.md M1): a comparison tail consumes the not-chain itself —
        // Lua unary `not` binds TIGHTER than relational operators, so
        // `not x > y` ≡ `(not x) > y`; the factor wraps the CALL, never the
        // comparison.
        private FilterExpr parseOperand() {
            int notDepth = 0;
            while (matchKeyword("not")) {
                notDepth++;
            }
            Token token = peek();
            if (token.type() == Type.LPAREN) {
                index++;
                FilterExpr inner = parseExpression();
                if (peek().type() != Type.RPAREN) throw new UnrecognizedException();
                index++;
                return wrapParity(inner, notDepth);
            } else if (token.type() == Type.NAME && BOOLEAN_LITERALS.contains(token.text())) {
                index++;
                FilterExpr literal = switch (token.text()) {
                    case "true" -> new FilterExpr.BoolLit(true);
                    case "false" -> new FilterExpr.BoolLit(false);
                    default -> new FilterExpr.NilLit();
                };
                return wrapParity(literal, notDepth);
            }
            return parseCallOrComparison(notDepth);
        }

        // Non-comparison operands: double-not collapses to the identity
        // (existing semantics); comparison lefts keep the full not-chain
        // (C4) — the bool-vs-number order case must error/miss like Lua.
        private static FilterExpr wrapParity(FilterExpr operand, int notDepth) {
            return notDepth % 2 == 1 ? new FilterExpr.Not(operand) : operand;
        }

        private FilterExpr parseCallOrComparison(int notDepth) {
            Token name = peek();
            if (name.type() != Type.NAME) throw new UnrecognizedException();
            if (BOOLEAN_LITERALS.contains(name.text())) throw new UnrecognizedException();
            if (!FUNCTION_ARITY.containsKey(name.text())) throw new UnrecognizedException();
            index++;
            if (peek().type() != Type.LPAREN) throw new UnrecognizedException();
            index++;
            List<Object> arguments = parseCallArguments(name.text());
            if (peek().type() != Type.RPAREN) throw new UnrecognizedException();
            index++;
            FilterExpr.Call call = new FilterExpr.Call(name.text(), arguments);

            // Comparison tail: getPropertyValue(file, "p", <lit>) <op> <literal>;
            // the right-hand literal must be consumed too
            Token next = peek();
            if (isComparison(next)) {
                if (!name.text().equals("getPropertyValue")) throw new UnrecognizedException();
                index++;
                Token right = peek();
                Object literal = literalArg(right);
                index++;
                // C4 (BUGS.md M1): Lua's unary `not` binds tighter than the
                // relational tail — the not-chain wraps the CALL (the
                // compared value), so `not x > y` ≡ `(not x) > y`, which Lua
                // always errors on; the Java path misses identically.
                FilterExpr left = call;
                for (int i = 0; i < notDepth; i++) {
                    left = new FilterExpr.Not(left);
                }
                return new FilterExpr.Compare(left, op(next), literal);
            }
            return wrapParity(call, notDepth);
        }

        private FilterExpr.Op op(Token token) {
            return switch (token.type()) {
                case EQ -> FilterExpr.Op.EQ;
                case NEQ -> FilterExpr.Op.NEQ;
                case LT -> FilterExpr.Op.LT;
                case LE -> FilterExpr.Op.LE;
                case GT -> FilterExpr.Op.GT;
                case GE -> FilterExpr.Op.GE;
                default -> throw new UnrecognizedException();
            };
        }

        // Comparison-side literal: String | Double | Boolean | null(nil)
        private Object literalArg(Token token) {
            if (token.type() == Type.STRING) {
                return token.text();
            }
            if (token.type() == Type.NUMBER) {
                return Double.parseDouble(token.text());
            }
            if (token.type() == Type.NAME) {
                return switch (token.text()) {
                    case "true" -> Boolean.TRUE;
                    case "false" -> Boolean.FALSE;
                    case "nil" -> null;
                    default -> throw new UnrecognizedException();
                };
            }
            throw new UnrecognizedException();
        }

        private boolean isComparison(Token token) {
            return switch (token.type()) {
                case EQ, NEQ, LT, LE, GT, GE -> true;
                default -> false;
            };
        }

        // Arguments after the opening paren; the closing paren is NOT consumed.
        // Positional list for the call — the first arg is always the "file"
        // table marker (kept as the string "file" for referenced-props parity).
        private List<Object> parseCallArguments(String functionName) {
            List<Object> arguments = new java.util.ArrayList<>();
            Token first = peek();
            if (first.type() != Type.NAME || !first.text().equals("file")) {
                throw new UnrecognizedException();
            }
            index++;
            arguments.add(first.text());
            int arity = 1;

            if (peek().type() != Type.COMMA) throw new UnrecognizedException();
            index++;

            Token second = peek();
            if (second.type() != Type.STRING) throw new UnrecognizedException();
            index++;
            arguments.add(second.text());
            arity++;
            String referenced = switch (functionName) {
                case "fileFieldStartsWith", "fileInFolder" -> "filePath";
                default -> second.text();
            };
            referencedProperties.add(referenced);

            // Remaining arguments: literals (strings, numbers, nil/true/false)
            // or one table constructor of string literals. No nesting, no
            // expressions.
            while (peek().type() == Type.COMMA) {
                index++;
                arity++;
                Token arg = peek();
                if (arg.type() == Type.LBRACE) {
                    index++;
                    var values = new java.util.ArrayList<String>();
                    if (peek().type() == Type.RBRACE) {
                        index++;
                        arguments.add(java.util.List.copyOf(values));
                        continue;
                    }
                    while (true) {
                        Token item = peek();
                        if (item.type() != Type.STRING) throw new UnrecognizedException();
                        index++;
                        values.add(item.text());
                        if (peek().type() == Type.COMMA) {
                            index++;
                            continue;
                        }
                        break;
                    }
                    if (peek().type() != Type.RBRACE) throw new UnrecognizedException();
                    index++;
                    arguments.add(java.util.List.copyOf(values));
                } else if (arg.type() == Type.STRING) {
                    index++;
                    arguments.add(arg.text());
                } else if (arg.type() == Type.NUMBER) {
                    index++;
                    arguments.add(Double.parseDouble(arg.text()));
                } else if (arg.type() == Type.NAME && BOOLEAN_LITERALS.contains(arg.text())) {
                    index++;
                    arguments.add(switch (arg.text()) {
                        case "true" -> Boolean.TRUE;
                        case "false" -> Boolean.FALSE;
                        default -> null; // nil literal
                    });
                } else {
                    throw new UnrecognizedException();
                }
            }
            if (arity != FUNCTION_ARITY.get(functionName)) throw new UnrecognizedException();
            return arguments;
        }

        void expectEnd() {
            if (peek().type() != Type.END) throw new UnrecognizedException();
        }

        private Token peek() {
            return tokens.get(index);
        }

        private boolean matchKeyword(String keyword) {
            Token token = peek();
            if (token.type() == Type.NAME && token.text().equals(keyword)) {
                index++;
                return true;
            }
            return false;
        }
    }
}
