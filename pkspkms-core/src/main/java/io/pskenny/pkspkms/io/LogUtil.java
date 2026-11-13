package io.pskenny.pkspkms.io;

// Log hygiene (BUGS.md B24): filesystem- and user-derived values reach log
// lines unscrubbed — a CR/LF in a note title or path can forge entries. Every
// log call that interpolates such values wraps them through oneLine.
public final class LogUtil {

    private LogUtil() {}

    public static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", " ")
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("\0", " ");
    }
}
