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

package io.pskenny.pkspkms.repo.query;

import java.util.List;

/** AST for the Lucene-style query language. Values live in the tree; SQL binding happens in QueryCompiler. */
public sealed interface QueryNode {

    record FieldEq(String field, String value) implements QueryNode {}

    record FieldWildcard(String field, String pattern) implements QueryNode {}

    record FieldRange(String field, String lo, String hi, boolean loIncl, boolean hiIncl) implements QueryNode {}

    record FieldExists(String field) implements QueryNode {}

    /** Bare terms and phrases: the predicate applies to any property value. */
    record AnyField(QueryNode inner) implements QueryNode {}

    /** Grouped values: field:(a OR b). */
    record Or(List<QueryNode> options) implements QueryNode {}

    record And(List<QueryNode> children) implements QueryNode {}

    /** Parenthesized sub-query. */
    record Bool(List<Query.Clause> clauses) implements QueryNode {}

    record AllMatch() implements QueryNode {}
}
