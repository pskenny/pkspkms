package io.pskenny.pkspkms.test;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.repo.PksFileRepository;
import io.pskenny.pkspkms.repo.VaultKind;
import io.pskenny.pkspkms.repo.query.CompiledQuery;
import io.pskenny.pkspkms.repo.query.PropertyTypes;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Test stand-in: every role method throws by default; anonymous subclasses
 * override only what their test exercises (ExportTest's minimal repositories).
 */
public abstract class UnsupportedPksRepository implements PksFileRepository {

    @Override
    public List<PksFile> searchRegular(CompiledQuery query) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<PksFile> loadCorpus() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String resolveWikilink(String wikilink) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String readFile(String filePath) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Map<String, Object>> propertiesForPaths(List<String> paths) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> facets(CompiledQuery query, List<String> keys, int limit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> related(String pathOrWikilink) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<String> orphanPaths(int limit, int offset) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long orphanCount() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> graphHops(String pathOrWikilink, int hops) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> brokenLinks(String sort, int limit, int minRefs, String aliasScope) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> binaryFile(String path, String mode) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> cacheFile(String address, String location, String cacheDirectory) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> manifest() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getMarkdownFromLuaBase(String luaBaseYaml) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getMarkdownFromBase(String baseYaml) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int addVaultAlias(String alias, String directory, VaultKind kind) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean vaultAliasExists(String alias) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void createPropertyIndex(String propertyKey, String type) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void loadDirectoryIntoRepository() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void loadVirtualVault(io.pskenny.pkspkms.io.fs.PkmsFileSystem aliasFs, String alias) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException();
    }
}
