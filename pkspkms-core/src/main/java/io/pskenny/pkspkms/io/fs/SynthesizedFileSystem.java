package io.pskenny.pkspkms.io.fs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Read-only vault over synthesized in-memory files (feeds, OPML outlines).
 * Subclasses build the path -> content map; the interface plumbing — listing,
 * lookup, reading, and refusing writes — lives here. Every entry's mtime is
 * per-file so the incremental loader re-parses only what the source changed.
 */
public abstract class SynthesizedFileSystem implements PkmsFileSystem {

    /** Synthesized file: markdown content plus the mtime reported to the loader. */
    public record SynthFile(String content, long mtime) {}

    private static final String UNSUPPORTED = "Synthesized vaults are read-only";

    private final SortedMap<String, SynthFile> files;

    protected SynthesizedFileSystem(Map<String, SynthFile> files) {
        this.files = new TreeMap<>(files);
    }

    @Override
    public List<PkmsEntry> listFiles(List<String> excludedDirectories) throws IOException {
        List<PkmsEntry> entries = new ArrayList<>();
        for (Map.Entry<String, SynthFile> file : files.entrySet()) {
            if (excludedDirectory(excludedDirectories, file.getKey())) {
                continue;
            }
            entries.add(new SynthEntry(file.getKey(), file.getValue().mtime()));
        }
        return entries;
    }

    private static boolean excludedDirectory(List<String> excluded, String path) {
        for (String segment : path.split("/")) {
            if (excluded.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public PkmsEntry resolve(String relativePath) throws IOException {
        SynthFile file = files.get(relativePath);
        if (file == null) {
            throw new IOException("No such entry: " + relativePath);
        }
        return new SynthEntry(relativePath, file.mtime());
    }

    @Override
    public InputStream openInput(String relativePath) throws IOException {
        SynthFile file = files.get(relativePath);
        if (file == null) {
            throw new IOException("No such entry: " + relativePath);
        }
        return new ByteArrayInputStream(file.content().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public boolean exists(String relativePath) {
        return files.containsKey(relativePath);
    }

    @Override
    public OutputStream openOutput(String relativePath) throws IOException {
        throw new UnsupportedOperationException(UNSUPPORTED);
    }

    @Override
    public void writeString(String relativePath, String content) throws IOException {
        throw new UnsupportedOperationException(UNSUPPORTED);
    }

    @Override
    public void copyFrom(InputStream source, String destRelativePath) throws IOException {
        source.close();
        throw new UnsupportedOperationException(UNSUPPORTED);
    }

    private record SynthEntry(String path, long mtime) implements PkmsEntry {
        @Override
        public String relativePath() {
            return path;
        }

        @Override
        public String name() {
            return io.pskenny.pkspkms.io.PathUtil.baseName(path);
        }

        @Override
        public long lastModified() {
            return mtime;
        }
    }
}
