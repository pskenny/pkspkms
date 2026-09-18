package io.pskenny.pkspkms.io;

import java.util.Locale;

public final class PathUtil {
    private static final int MAX_FILE_NAME = 80;

    private PathUtil() {}

    public static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash == -1 ? path : path.substring(slash + 1);
    }

    // File-name-safe text for synthesized vault entries: separators, wildcards
    // and control characters removed; whitespace collapsed; length capped
    public static String safeFileName(String raw) {
        if (raw == null) {
            return "untitled";
        }
        String cleaned = raw
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "")
                .strip()
                .replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            return "untitled";
        }
        return cleaned.length() > MAX_FILE_NAME
                ? cleaned.substring(0, MAX_FILE_NAME)
                : cleaned;
    }

    // Lowercased for case-insensitive matchers (Obsidian-style ignores)
    public static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}