package io.pskenny.pkspkms.repo;

import io.pskenny.pkspkms.io.fs.PkmsFileSystem;

/**
 * Load/mount orchestration: vault walk, incremental diff, virtual vault
 * mounts, embed rendering. The application drives the sequence
 * (load → mount all → processEmbeds); repositories only carry the phases.
 */
public interface VaultLoader {

    void loadDirectoryIntoRepository();

    /**
     * Same as {@link #loadDirectoryIntoRepository()}, with embed rendering
     * mode explicit for callers that run a global embed pass later. The
     * default is {@link EmbedMode#RENDER_NOW} (CLI export path).
     */
    default void loadDirectoryIntoRepository(EmbedMode mode) {
        loadDirectoryIntoRepository();
    }

    void loadVirtualVault(PkmsFileSystem aliasFs, String alias);

    /** Renders base/luabase embeds; call once after all vaults have loaded. */
    default void processEmbeds() {}
}
