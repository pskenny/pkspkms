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
    private final ExportConfig config;

    public Export(ExportConfig config) {
        this.config = config;
        this.inMemoryFileRepository = new InMemoryFileRepository(config.directory());
    }

    public void export() {
        Map<String, PksFile> matchedFiles = inMemoryFileRepository.search(config.query());
        if (matchedFiles.isEmpty()) {
            System.out.println("No files to export");
            return;
        } else {
            System.out.println("Matched " + matchedFiles.size() + " files");
        }

        switch (config.type()) {
            case "markdown":
                markdownExport(matchedFiles, config.directory(), config.output(), config.dryRun());
                break;
            case "copy":
                copyExport(matchedFiles, config.directory(), config.output(), config.dryRun());
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

    public record ExportConfig(String directory, String query, String output, String type, String sqliteDb, String options, int depth, boolean dryRun, boolean load){}
}
