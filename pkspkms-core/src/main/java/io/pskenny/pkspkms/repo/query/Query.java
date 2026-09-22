package io.pskenny.pkspkms.repo.query;

import java.util.List;

/**
 * Flat clause list with Lucene classic semantics: default clauses are SHOULD,
 * AND promotes its adjacent clauses to MUST, -/NOT marks MUST_NOT.
 * Compile rules: MUSTs AND; SHOULDs OR (only when no MUSTs); MUST_NOTs excluded.
 */
public record Query(List<Clause> clauses) {

    public enum Occur { MUST, SHOULD, MUST_NOT }

    public record Clause(Occur occur, QueryNode node) {}
}
