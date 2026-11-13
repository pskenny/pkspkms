package io.pskenny.pkspkms.io.processor.markdown;

/**
 * Strips a leading UTF-8 BOM from decoded content so frontmatter detection
 * starts at position 0 (BUGS.md B16 — BOM-prefixed files silently lost all
 * frontmatter). Content hashing keeps the original bytes; only parsing sees
 * the stripped string.
 */
public final class BomStripper {

    private BomStripper() {}

    public static String strip(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        return content.charAt(0) == '\uFEFF'
                ? content.substring(1)
                : content;
    }
}
