package io.pskenny.pkspkms.repo;

import java.util.List;
import java.util.Map;

/** Link-graph reads: one-hop neighborhood, orphans, hop walks, dangling report. */
public interface VaultGraph {

    /**
     * One note's one-hop link neighborhood {@code {path, outgoing, incoming}}
     * for a path or wikilink-form input; unresolved inputs reject with
     * {@link IllegalArgumentException}.
     */
    Map<String, Object> related(String pathOrWikilink);

    /** Markdown notes with no link edges in either direction, capped at {@code limit}. */
    List<String> orphanPaths(int limit, int offset);

    long orphanCount();

    /**
     * Link-graph neighborhood: 1–2 hops, both directions, capped at 200 ids.
     * Graph-shaped file reductions ride the payload.
     */
    Map<String, Object> graphHops(String pathOrWikilink, int hops);

    /**
     * Vault-wide dangling-wikilink report: aggregated over the loader's
     * {@code danglingLinks} property — near-miss spellings cluster under one
     * normalized bucket; raw spelling of the max-refcount variant names the
     * bucket. Shape: {@code {total_broken, targets: [{target, refcount,
     * sources}]}}; refcount desc by default, sources capped.
     */
    Map<String, Object> brokenLinks(String sort, int limit, int minRefs, String aliasScope);
}
