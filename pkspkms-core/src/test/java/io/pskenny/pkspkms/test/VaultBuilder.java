package io.pskenny.pkspkms.test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Fluent wrapper around {@link FileUtil#createFile} — the test-vault idiom in
 * one place, including the fact every fixture list relies on: a note written
 * with NONEMPTY frontmatter gets the stamped heading line
 * {@code # This is the body content for: <file>} at the top of its body
 * (writes chain, failures propagate).
 */
public final class VaultBuilder {

    private final Path dir;

    private VaultBuilder(Path dir) {
        this.dir = dir;
    }

    public static VaultBuilder at(Path dir) {
        return new VaultBuilder(dir);
    }

    public VaultBuilder note(String fileName, Map<String, Object> frontmatter) throws IOException {
        return note(fileName, frontmatter, "");
    }

    public VaultBuilder note(String fileName, Map<String, Object> frontmatter, String content) throws IOException {
        FileUtil.createFile(dir, fileName, frontmatter == null ? Map.of() : frontmatter, content);
        return this;
    }

    /** Text blocks are common enough to deserve their own (Java 15+ blocks tr). */
    public VaultBuilder md(String fileName, Map<String, Object> frontmatter, String block) throws IOException {
        return note(fileName, frontmatter, block);
    }
}
