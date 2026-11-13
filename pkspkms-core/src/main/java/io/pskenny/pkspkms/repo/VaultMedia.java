package io.pskenny.pkspkms.repo;

import java.util.Map;

/** Binary/embed-read surface: MCP binary access, pkspkms:// caching, rendered content. */
public interface VaultMedia {

    /**
     * Read-only binary surface for MCP: {@code mode=stat} gives
     * {@code {path, size, modified, ext}}; {@code mode=base64} adds a
     * bounded Base64 {@code data} field (rejected over 5 MB). Same path
     * validation rules as {@link VaultReader#readFile}.
     */
    Map<String, Object> binaryFile(String path, String mode);

    Map<String, Object> cacheFile(String address, String location, String cacheDirectory);

    /**
     * Per-alias {@code {filePath, blake3}} pairs for pkspkms:// targets.
     * Main-vault rows (vault_alias_id null) are excluded: /cache only serves
     * registered aliases.
     */
    Map<String, Object> manifest();

    String getMarkdownFromLuaBase(String luaBaseYaml);

    String getMarkdownFromBase(String baseYaml);
}
