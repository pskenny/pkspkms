package io.pskenny.pkspkms.repo;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.repo.query.CompiledQuery;
import io.pskenny.pkspkms.repo.query.PropertyTypes;
import io.pskenny.pkspkms.io.fs.PkmsFileSystem;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface PksFileRepository extends AutoCloseable {

    void loadDirectoryIntoRepository();

    void loadVirtualVault(PkmsFileSystem aliasFs, String alias);

    /** Renders base/luabase embeds; call once after all vaults have loaded. */
    default void processEmbeds() {}

    List<PksFile> searchRegular(CompiledQuery query);

    /** Property types declared in the vault's .obsidian/types.json; empty when unknown. */
    default PropertyTypes getPropertyTypes() {
        return PropertyTypes.empty();
    }

    default void searchRegular(CompiledQuery query, Consumer<PksFile> consumer) {
        searchRegular(query).forEach(consumer);
    }

    /**
     * All files as parsed PksFile maps, in storage order. One JSON parse per
     * file — the corpus for single-pass embed rendering.
     */
    List<PksFile> loadCorpus();

    String resolveWikilink(String wikilink);

    int addVaultAlias(String alias, String directory, boolean isVirtual);

    boolean vaultAliasExists(String alias);

    Map<String, Object> cacheFile(String address, String location, String cacheDirectory);

    /**
     * Per-alias {@code {filePath, blake3}} pairs for pkspkms:// targets.
     * Main-vault rows (vault_alias_id null) are excluded: /cache only serves
     * registered aliases.
     */
    default Map<String, Object> manifest() {
        return Map.of();
    }

    void createPropertyIndex(String propertyKey, String type);

    String getMarkdownFromLuaBase(String luaBaseYaml);

    String getMarkdownFromBase(String baseYaml);

    @Override
    void close();
}
