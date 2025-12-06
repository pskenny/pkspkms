package io.github.pskenny.io.parser;

import io.github.pskenny.io.PksFile;

import java.io.File;
import java.io.IOException;

public interface Parser {
    PksFile parse(File file, String directory) throws IOException;
}
