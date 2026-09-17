package io.pskenny.pkspkms.io.processor;

import io.pskenny.pkspkms.io.fs.PkmsEntry;
import io.pskenny.pkspkms.io.fs.PkmsFileSystem;
import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.io.Blake3Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;

public class Processors {
    private static final Logger logger = LoggerFactory.getLogger(Processors.class);

    private final MarkdownProcessor markdownProcessor = new MarkdownProcessor();

    public PksFile initialReadOnlyParse(PkmsEntry entry, PkmsFileSystem fs) {
        try {
            PksFile pksFile;
            if (entry.name().toLowerCase(Locale.ROOT).endsWith(".md")) {
                byte[] bytes;
                try (InputStream in = fs.openInput(entry.relativePath())) {
                    bytes = in.readAllBytes();
                }
                pksFile = markdownProcessor.initialParse(entry, bytes);
            } else {
                pksFile = new PksFile(entry.relativePath(), new HashMap<>());
                try (InputStream in = fs.openInput(entry.relativePath())) {
                    pksFile.setHash(Blake3Util.hashStream(in));
                }
                logger.debug("Stream-hashed binary: {}", entry.relativePath());
            }
            if (pksFile != null) {
                pksFile.setLastModified(entry.lastModified());
            }
            return pksFile;
        } catch (Exception e) {
            logger.error("Couldn't parse file at: {}", entry.relativePath(), e);
        }
        return null;
    }
}
