package io.github.pskenny.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
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
        if (!parentDirectory.exists()) {
            parentDirectory.mkdirs();
        }

        if (file.getProperties().containsKey("content")) {
            System.out.println("Writing to: " + dest);
            if (dryRun) {
                return;
            }

            try {
                Files.write(Paths.get(outputDir, file.getFilePath()), ((String) file.getProperties().get("content")).getBytes());
            } catch (IOException e) {
                System.err.println("Couldn't write file to: " + outputDir);
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
                    || link.endsWith(".jpeg")) {
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

        File parentDirectory = destination.getParent().toFile();
        if (!parentDirectory.exists()) {
            parentDirectory.mkdirs();
        }

        System.out.println("Copying from " + source + " to " + destination);
        if (dryRun) {
            return;
        }

        try {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Couldn't write file to: " + destination);
        }
    }
}
