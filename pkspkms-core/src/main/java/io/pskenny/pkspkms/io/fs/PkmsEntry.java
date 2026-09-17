package io.pskenny.pkspkms.io.fs;

public interface PkmsEntry {
    String relativePath();
    String name();
    long lastModified();
}