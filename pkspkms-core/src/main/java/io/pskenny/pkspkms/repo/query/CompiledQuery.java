package io.pskenny.pkspkms.repo.query;

import java.util.List;

/** A query compiled to parameterized SQLite SQL over FILES.properties. */
public record CompiledQuery(String sql, List<Object> params) {}
