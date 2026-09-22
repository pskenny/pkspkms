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
