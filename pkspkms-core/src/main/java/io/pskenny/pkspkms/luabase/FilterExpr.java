/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of PKSPKMS.
 * Copyright (C) 2026 Paul Kenny
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

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
