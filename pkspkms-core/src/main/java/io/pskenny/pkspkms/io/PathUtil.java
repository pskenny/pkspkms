package io.pskenny.pkspkms.io;

public final class PathUtil {
    private PathUtil() {}

    public static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash == -1 ? path : path.substring(slash + 1);
    }
}