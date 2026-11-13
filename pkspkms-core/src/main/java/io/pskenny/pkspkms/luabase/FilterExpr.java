package io.pskenny.pkspkms.luabase;

import java.util.List;

/**
 * Typed AST for converter-whitelisted filter expressions. Built by
 * {@link FilterAnalyzer}'s parser alongside the recognized/referenced sets;
 * evaluated natively by {@link FilterEval} with Lua-parity semantics, so the
 * sandbox escape is unnecessary for recognized filters.
 */
public sealed interface FilterExpr {

    record BoolLit(boolean value) implements FilterExpr {}

    record NilLit() implements FilterExpr {}

    record Not(FilterExpr inner) implements FilterExpr {}

    record And(FilterExpr left, FilterExpr right) implements FilterExpr {}

    record Or(FilterExpr left, FilterExpr right) implements FilterExpr {}

    /**
     * Whitelisted call: function name + literal arguments (first arg is always
     * the {@code file} table marker). Bare-call operand truthiness = non-nil
     * and not-false, matching Lua.
     */
    record Call(String function, List<Object> arguments) implements FilterExpr {}

    /**
     * Left operand compared with a literal via one of the six operators. The
     * left is a call, possibly wrapped in Lua's unary-`not` binding —
     * `not x > y` parses as `(not x) > y`, so the not-chain lives on the
     * compared value, not around the comparison (BUGS.md M1-C4).
     */
    record Compare(FilterExpr left, Op operator, Object right) implements FilterExpr {}

    public enum Op {
        EQ, NEQ, LT, LE, GT, GE,
    }
}
