package io.pskenny.pkspkms.io.parser;

import io.pskenny.pkspkms.io.PksFile;

import java.io.File;
import java.io.IOException;

public interface Parser {
    PksFile parse(File file, String directory) throws IOException;
}
