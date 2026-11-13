package io.pskenny.pkspkms.repo;

/** Vault registry + property index management. */
public interface VaultRegistry {

    int addVaultAlias(String alias, String directory, VaultKind kind);

    boolean vaultAliasExists(String alias);

    void createPropertyIndex(String propertyKey, String type);
}
