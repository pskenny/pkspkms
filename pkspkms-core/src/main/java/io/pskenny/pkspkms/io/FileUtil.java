package io.pskenny.pkspkms.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class FileUtil {
    private static final Logger logger = LoggerFactory.getLogger(FileUtil.class);

    public static void writePksFile(PksFile file, String pkms, String output, boolean dryRun) {
        String outputDir = output;
        if (!outputDir.endsWith("/")) {
            outputDir += "/";
        }
        Path dest = Paths.get(outputDir, file.getFilePath());
        File parentDirectory = dest.toFile().getParentFile();
        if (parentDirectory != null && !parentDirectory.exists() && !parentDirectory.mkdirs()) {
            throw new IllegalStateException("Failed to create directory: " + parentDirectory);
        }

        if (file.getProperties().containsKey("content")) {
            logger.info("Writing to: {}", dest);
            if (dryRun) {
                return;
            }

            try {
                Files.write(dest, ((String) file.getProperties().get("content")).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                logger.error("Couldn't write file to: {}", outputDir, e);
            }
        } else {
            copyFile(file.getFilePath(), pkms, output, dryRun);
        }
    }

    public static void copyLinkedFiles(PksFile file, String pkms, String output, boolean dryRun) {
        if (!hasLinks(file)) {
            return;
        }

        ArrayList<String> links = (ArrayList<String>) file.getProperties().get("links");
        links.forEach(link -> {
            // strip alt text
            if (link.indexOf("|") != -1) {
                link = link.substring(0, link.indexOf("|"));
            }

            if (link.endsWith(".svg")
                    || link.endsWith(".png")
                    || link.endsWith(".jpg")
                    || link.endsWith(".jpeg")
                    || link.endsWith(".txt")) {
                copyFile(link, pkms, output, dryRun);
            }
        });
    }

    private static boolean hasLinks(PksFile file) {
        ArrayList<String> links = (ArrayList<String>) file.getProperties().get("links");
        return links != null && !links.isEmpty();
    }

    public static void copyFile(String filePath, String pkms, String output, boolean dryRun) {
        Path destination = Paths.get(output, filePath);
        Path source = Paths.get(pkms, filePath);

        if (destination.toFile().exists()) {
            return;
        }

        Path parent = destination.getParent();
        if (parent == null) {
            throw new IllegalStateException("Cannot determine parent directory for: " + destination);
        }
        File parentDirectory = parent.toFile();
        if (!parentDirectory.exists() && !parentDirectory.mkdirs()) {
            throw new IllegalStateException("Failed to create directory: " + parentDirectory);
        }

        logger.info("Copying from {} to {}", source, destination);
        if (dryRun) {
            return;
        }

        try {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.error("Couldn't write file to: {}", destination, e);
        }
    }


}
