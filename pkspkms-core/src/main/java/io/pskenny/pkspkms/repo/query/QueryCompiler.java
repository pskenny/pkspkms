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

import java.util.ArrayList;
import java.util.List;


/**
 * Compiles the query AST to parameterized SQLite SQL over FILES.properties.
 * Every comparison is a dual probe: the scalar value (json_extract) or any
 * array element (json_each). All keys and values are bound parameters.
 */
public final class QueryCompiler {

    // text: clause handling (FTS5 full-text, side-by-side with property search).
    // When no text: clause exists, compiled SQL is byte-identical to before.
    private static final String TEXT_FIELD = "text";

    private boolean textProbe;

    public CompiledQuery compile(Query query) {
        return compile(query, PropertyTypes.empty());
    }

    public CompiledQuery compile(Query query, PropertyTypes types) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        textProbe = false;
        assemble(query.clauses(), types, sql, params);
        return new CompiledQuery(sql.toString(), params, textProbe);
    }

    // Lucene flat-clause semantics: MUSTs AND; SHOULDs OR (only when no MUSTs);
    // MUST_NOTs excluded, null-safe so field-absent rows match; pure
    // MUST_NOTs anchored with 1=1.
    private void assemble(List<Query.Clause> clauses, PropertyTypes types, StringBuilder sql, List<Object> params) {
        List<Query.Clause> musts = new ArrayList<>();
        List<Query.Clause> shoulds = new ArrayList<>();
        List<Query.Clause> nots = new ArrayList<>();
        for (Query.Clause c : clauses) {
            switch (c.occur()) {
                case MUST -> musts.add(c);
                case SHOULD -> shoulds.add(c);
                case MUST_NOT -> nots.add(c);
            }
        }

        List<String> parts = new ArrayList<>();
        if (!musts.isEmpty()) {
            for (Query.Clause c : musts) {
                parts.add(node(c.node(), types, params));
            }
        } else if (shoulds.size() == 1) {
            parts.add(node(shoulds.get(0).node(), types, params));
        } else if (!shoulds.isEmpty()) {
            List<String> ors = new ArrayList<>();
            for (Query.Clause c : shoulds) {
                ors.add(node(c.node(), types, params));
            }
            parts.add("(" + String.join(" OR ", ors) + ")");
        } else {
            parts.add("1=1");
        }
        for (Query.Clause c : nots) {
            // Null-safe negation: absent/null property -> pred NULL -> COALESCE 0 -> NOT
            // matches. Lucene semantics: NOT matches docs not containing the term.
            parts.add("NOT COALESCE((" + node(c.node(), types, params) + "), 0)");
        }
        sql.append(String.join(" AND ", parts));
    }

    private String node(QueryNode n, PropertyTypes types, List<Object> params) {
        String textField = textFieldOf(n);
        if (textField != null) {
            textProbe = true;
            return textProbeSql(n, params);
        }
        if (n instanceof QueryNode.FieldEq f) {
            return fieldEq(f.field(), f.value(), types, params);
        }
        if (n instanceof QueryNode.FieldWildcard w) {
            return fieldLike(w.field(), likePattern(w.pattern()), params);
        }
        if (n instanceof QueryNode.FieldRange r) {
            return fieldRange(r, types, params);
        }
        if (n instanceof QueryNode.FieldExists f) {
            if (f.field().equals("*")) {
                return "1=1";
            }
            params.add(jsonPath(f.field()));
            return "json_extract(properties, ?) IS NOT NULL";
        }
        if (n instanceof QueryNode.AnyField a) {
            return anyField(a.inner(), params);
        }
        if (n instanceof QueryNode.Or or) {
            List<String> parts = or.options().stream().map(o -> node(o, types, params)).toList();
            return "(" + String.join(" OR ", parts) + ")";
        }
        if (n instanceof QueryNode.And and) {
            List<String> parts = and.children().stream().map(c -> node(c, types, params)).toList();
            return "(" + String.join(" AND ", parts) + ")";
        }
        if (n instanceof QueryNode.Bool b) {
            StringBuilder inner = new StringBuilder();
            assemble(b.clauses(), types, inner, params);
            return "(" + inner + ")";
        }
        if (n instanceof QueryNode.AllMatch) {
            return "1=1";
        }
        throw new IllegalStateException("Unknown query node: " + n);
    }

    // --- text: probes (FTS5 full-text, side-by-side with property search) ---

    private static String textFieldOf(QueryNode n) {
        if (n instanceof QueryNode.FieldEq f) {
            return TEXT_FIELD.equals(f.field()) ? TEXT_FIELD : null;
        }
        if (n instanceof QueryNode.FieldWildcard w) {
            return TEXT_FIELD.equals(w.field()) ? TEXT_FIELD : null;
        }
        if (n instanceof QueryNode.FieldExists f) {
            return TEXT_FIELD.equals(f.field()) ? TEXT_FIELD : null;
        }
        return null;
    }

    /**
     * One text clause = a bound FTS5 MATCH probe. Wrapped double quotes make
     * multi-word phrases and reserved words (and/or/not) literal; a trailing
     * {@code *} (suffix wildcard) rides outside the quotes per FTS5 syntax.
     */
    private String textProbeSql(QueryNode n, List<Object> params) {
        String arg;
        if (n instanceof QueryNode.FieldEq f) {
            arg = '"' + f.value().replace("\"", "\"\"") + '"';
        } else if (n instanceof QueryNode.FieldWildcard w) {
            String pattern = w.pattern();
            if (!pattern.endsWith("*")) {
                // interior `*`/`?` — FTS5 allows suffix-only prefix queries
                throw new QueryParseException(-1,
                        "text: wildcards must be a suffix (e.g. rust*)");
            }
            String token = pattern.substring(0, pattern.length() - 1);
            if (token.contains("*") || token.contains("?") || token.contains("\"")) {
                throw new QueryParseException(-1,
                        "text: wildcards must be a suffix (e.g. rust*)");
            }
            arg = '"' + token.replace("\"", "\"\"") + "\"*";
        } else if (n instanceof QueryNode.FieldExists) {
            // text:* = body exists: match every indexed row
            arg = null;
        } else {
            throw new QueryParseException(-1, "text: does not support this form");
        }

        if (arg == null) {
            // text:* = "has body text": `<> ''` — production binaries hold ''
            // (v3 NOT NULL DEFAULT), so `IS NOT NULL` counted every .mp4/.png
            // row; a NULL body (raw tables) would fail the probe too → doubly safe
            return "FILES.body <> ''";
        }
        params.add(arg);
        return "FILES.id IN (SELECT rowid FROM files_fts WHERE files_fts MATCH ?)";
    }

    // Comparison compiled according to the declared property type; UNDECLARED
    // keeps the legacy shape (bound sniffed as number or text).
    private String fieldEq(String field, String value, PropertyTypes types, List<Object> params) {
        String path = jsonPath(field);
        switch (types.typeOf(field)) {
            case NUMBER: {
                // Typed comparison: garbage must surface as the query's parse
                // error (HTTP 400 / MCP tool error), not a raw
                // NumberFormatException leaking as a 500 (BUGS.md M7a)
                Object v = parseNumber(value, field);
                params.add(path);
                params.add(v);
                params.add(path);
                params.add(v);
                return "(CAST(json_extract(properties, ?) AS REAL) = CAST(? AS REAL) OR EXISTS (SELECT 1 FROM json_each(properties, ?) WHERE CAST(value AS REAL) = CAST(? AS REAL)))";
            }
            case DATE, DATETIME, TEXT, CHECKBOX: {
                params.add(path);
                params.add(value);
                params.add(path);
                params.add(value);
                return "(CAST(json_extract(properties, ?) AS TEXT) = ? OR EXISTS (SELECT 1 FROM json_each(properties, ?) WHERE CAST(value AS TEXT) = ?))";
            }
            default: {
                Object v = numberOrText(value);
                params.add(path);
                params.add(v);
                params.add(path);
                params.add(v);
                return "(json_extract(properties, ?) = ? OR EXISTS (SELECT 1 FROM json_each(properties, ?) WHERE value = ?))";
            }
        }
    }

    private String fieldLike(String field, String like, List<Object> params) {
        String path = jsonPath(field);
        params.add(path);
        params.add(like);
        params.add(path);
        params.add(like);
        return "(json_extract(properties, ?) LIKE ? ESCAPE '\\' OR EXISTS (SELECT 1 FROM json_each(properties, ?) WHERE value LIKE ? ESCAPE '\\'))";
    }

    private String fieldRange(QueryNode.FieldRange r, PropertyTypes types, List<Object> params) {
        String path = jsonPath(r.field());
        String loOp = r.loIncl() ? ">=" : ">";
        String hiOp = r.hiIncl() ? "<=" : "<";
        StringBuilder sb = new StringBuilder("(");
        // Track processed bounds separately: the old `first` flag only flipped
        // on the LO branch, so an open-lower range emitted `1=1` after the hi
        // predicate and SQLite rejected the whole WHERE (near "1")
        boolean any = false;
        PropertyTypes.Kind kind = types.typeOf(r.field());
        if (r.lo() != null) {
            Object lo = boundFor(kind, r.lo());
            params.add(path);
            params.add(lo);
            sb.append("(").append(valueProbe(kind)).append(" ").append(loOp).append(" ?");
            params.add(path);
            params.add(lo);
            sb.append(" OR EXISTS (SELECT 1 FROM json_each(properties, ?) WHERE ").append(elementCompare(kind)).append(" ").append(loOp).append(" ?))");
            any = true;
        }
        if (r.hi() != null) {
            if (any) {
                sb.append(" AND ");
            }
            Object hi = boundFor(kind, r.hi());
            params.add(path);
            params.add(hi);
            sb.append("(").append(valueProbe(kind)).append(" ").append(hiOp).append(" ?");
            params.add(path);
            params.add(hi);
            sb.append(" OR EXISTS (SELECT 1 FROM json_each(properties, ?) WHERE ").append(elementCompare(kind)).append(" ").append(hiOp).append(" ?))");
            any = true;
        }
        if (!any) {
            // open-open range ([:] with * bounds) = match all
            sb.append("1=1");
        }
        return sb.append(")").toString();
    }

    // --- typed comparison helpers ---

    // value probe per declared kind: UNDECLARED keeps the legacy raw compare
    private static String valueProbe(PropertyTypes.Kind kind) {
        return switch (kind) {
            case NUMBER -> "CAST(json_extract(properties, ?) AS REAL)";
            case DATE, DATETIME, TEXT, MULTITEXT, CHECKBOX -> "CAST(json_extract(properties, ?) AS TEXT)";
            case UNDECLARED -> "json_extract(properties, ?)";
        };
    }

    private static String elementCompare(PropertyTypes.Kind kind) {
        return switch (kind) {
            case NUMBER -> "CAST(value AS REAL)";
            case DATE, DATETIME, TEXT, CHECKBOX, MULTITEXT -> "CAST(value AS TEXT)";
            case UNDECLARED -> "value";
        };
    }

    // Bound form per declared kind: NUMBER binds numerically; UNDECLARED keeps the legacy sniff
    private static Object boundFor(PropertyTypes.Kind kind, String bound) {
        return switch (kind) {
            case NUMBER -> parseNumber(bound, bound);
            case DATE, DATETIME, TEXT, CHECKBOX, MULTITEXT -> bound;
            case UNDECLARED -> numberOrText(bound);
        };
    }

    // Typed numeric bound: parse errors ride the query's parse error (M7a)
    private static Double parseNumber(String value, String field) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new QueryParseException(-1, "not a number: '" + value + "' (field:" + field + ")");
        }
    }

    private String anyField(QueryNode inner, List<Object> params) {
        String pred;
        if (inner instanceof QueryNode.FieldEq f) {
            // bare terms are substring matches: useful search-box behavior
            params.add(containsPattern(f.value()));
            pred = "value LIKE ? ESCAPE '\\'";
        } else if (inner instanceof QueryNode.FieldWildcard w) {
            params.add(likePattern(w.pattern()));
            pred = "value LIKE ? ESCAPE '\\'";
        } else if (inner instanceof QueryNode.FieldRange r) {
            pred = valueRange(r, params);
        } else {
            throw new IllegalStateException("Unsupported _all predicate: " + inner);
        }
        return "EXISTS (SELECT 1 FROM json_each(properties) WHERE " + pred + ")";
    }

    // %term% — the _all contains pattern for bare search terms
    private static String containsPattern(String term) {
        StringBuilder sb = new StringBuilder("%");
        for (char c : term.toCharArray()) {
            switch (c) {
                case '%', '_', '\\' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.append('%').toString();
    }

    private String valueRange(QueryNode.FieldRange r, List<Object> params) {
        StringBuilder sb = new StringBuilder();
        if (r.lo() != null) {
            params.add(numberOrText(r.lo()));
            sb.append("value ").append(r.loIncl() ? ">=" : ">").append(" ?");
        }
        if (r.hi() != null) {
            if (r.lo() != null) {
                sb.append(" AND ");
            }
            params.add(numberOrText(r.hi()));
            sb.append("value ").append(r.hiIncl() ? "<=" : "<").append(" ?");
        }
        return sb.isEmpty() ? "1=1" : sb.toString();
    }

    // JSON path with a quoted key, so kebab-case and friends work; shared
    // with the facet SQL surface in FilesDao (same escaping contract).
    // Runtime-verified against the sqlite driver (JsonPathEscapeProbeTest):
    // D3 — `"` rides as `\"`, and a literal `\` must double to `\\` (a raw
    // backslash silently yields null).
    public static String jsonPath(String field) {
        return "$.\"" + field.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // Numeric-looking bounds bind as numbers; everything else (ISO dates, ...) as text.
    private static Object numberOrText(String value) {
        return value.matches("-?\\d+(\\.\\d+)?") ? Double.parseDouble(value) : value;
    }

    // * -> %, ? -> _; literal % _ \ escaped for LIKE ... ESCAPE '\'
    private static String likePattern(String wildcard) {
        StringBuilder sb = new StringBuilder();
        for (char c : wildcard.toCharArray()) {
            switch (c) {
                case '*' -> sb.append('%');
                case '?' -> sb.append('_');
                case '%', '_', '\\' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
