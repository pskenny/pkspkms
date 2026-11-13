package io.pskenny.pkspkms.repo;

/**
 * The vault repository composite: loading, reading, graph reads, media,
 * registry. Role interfaces each state one capability family abstractly —
 * there are no default-throw stubs; a repository either carries a family or
 * doesn't implement that role.
 */
public interface PksFileRepository extends VaultLoader, VaultReader, VaultGraph, VaultMedia, VaultRegistry, AutoCloseable {

    /**
     * Read-only diagnostics snapshot for the MCP {@code health} tool — never
     * writes, never caches. Default keeps non-SQLite consumers honest.
     */
    default java.util.Map<String, Object> healthSnapshot() {
        return java.util.Map.of("status", "unknown");
    }

    @Override
    void close();
}
