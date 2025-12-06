package io.github.pskenny;

import io.github.pskenny.io.PksFile;
import io.github.pskenny.repo.InMemoryFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static io.github.pskenny.io.FileUtil.*;

public class Export {
    private static final Logger logger = LoggerFactory.getLogger(Export.class);

    private final InMemoryFileRepository inMemoryFileRepository;
    private final String directory;
    private final boolean dryRun;

    public Export(String directory, boolean dryRun) {
        this.directory = directory;
        this.dryRun = dryRun;
        this.inMemoryFileRepository = new InMemoryFileRepository(directory);
    }

    public void export(String query, String type, String output) {
        Map<String, PksFile> matchedFiles = inMemoryFileRepository.search(query);
        if (matchedFiles.isEmpty()) {
            System.out.println("No files to export");
            return;
        } else {
            System.out.println("Matched " + matchedFiles.size() + " files");
        }

        switch (type) {
            case "markdown":
                markdownExport(matchedFiles, directory, output, dryRun);
                break;
            case "copy":
                copyExport(matchedFiles, directory, output, dryRun);
                break;
            default:
                System.err.println("No export type specified");
                break;
        }
    }

    private void markdownExport(Map<String, PksFile> files, String pkms, String outputDirectory, boolean dryRun) {
        files.values().forEach(file -> {
            copyLinkedFiles(file, pkms, outputDirectory, dryRun);
            writePksFile(file, pkms, outputDirectory, dryRun);
        });
    }

    private void copyExport(Map<String, PksFile> files, String pkms, String outputDirectory, boolean dryRun) {
        files.values().forEach(file -> {
            copyLinkedFiles(file, pkms, outputDirectory, dryRun);
            copyFile(file.getFilePath(), pkms, outputDirectory, dryRun);
        });
    }
}
