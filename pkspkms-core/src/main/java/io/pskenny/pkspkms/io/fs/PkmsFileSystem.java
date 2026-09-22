package io.pskenny.pkspkms.io.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

// Why the abstraction? So I can implement it on Android using SAF
public interface PkmsFileSystem {
    List<PkmsEntry> listFiles(List<String> excludedDirectories) throws IOException;
    PkmsEntry resolve(String relativePath) throws IOException;
    InputStream openInput(String relativePath) throws IOException;
    boolean exists(String relativePath);
    OutputStream openOutput(String relativePath) throws IOException;
    void writeString(String relativePath, String content) throws IOException;
    void copyFrom(InputStream source, String destRelativePath) throws IOException;
}