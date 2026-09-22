package io.pskenny.pkspkms.repo.query;

public class QueryParseException extends RuntimeException {
    private final int offset;

    public QueryParseException(int offset, String message) {
        super(message + " (at position " + offset + ")");
        this.offset = offset;
    }

    public int getOffset() {
        return offset;
    }
}
