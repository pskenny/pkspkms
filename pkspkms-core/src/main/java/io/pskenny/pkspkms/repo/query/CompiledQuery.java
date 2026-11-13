package io.pskenny.pkspkms.repo.query;

import java.util.List;

/** A query compiled to parameterized SQLite SQL over FILES.properties. */
public record CompiledQuery(String sql, List<Object> params, boolean textProbe) {

    // Legacy shape for existing callers; text probes are opt-in per compile.
    public CompiledQuery(String sql, List<Object> params) {
        this(sql, params, false);
    }
}
