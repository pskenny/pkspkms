package io.pskenny.pkspkms.test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

public class FileUtil {

    public static void createFile(Path TEST_DIR, String fileName, Map<String, Object> frontmatter) throws IOException {
        createFile(TEST_DIR, fileName, frontmatter, "");
    }

    public static void createFile(Path testDirectory, String fileName, Map<String, Object> frontmatter, String content) throws IOException {
        StringBuilder contentBuilder = new StringBuilder();
        if (frontmatter.isEmpty()) {
            contentBuilder.append(content);

            Path filePath = testDirectory.resolve(fileName);
            Files.writeString(filePath, contentBuilder.toString());
            return;
        }
        contentBuilder.append("---\n");

        for (Map.Entry<String, Object> entry : frontmatter.entrySet()) {
            contentBuilder.append(entry.getKey()).append(": ");
            Object value = entry.getValue();
            if (value instanceof Collection) {
                contentBuilder.append(
                        ((Collection<?>) value).stream()
                                .map(item -> "\n  - " + item.toString())
                                .collect(Collectors.joining())
                );
            } else {
                contentBuilder.append(value.toString());
            }
            contentBuilder.append("\n");
        }

        contentBuilder.append("---\n# This is the body content for: ")
                .append(fileName)
                .append("\n")
                .append(content);

        Path filePath = testDirectory.resolve(fileName);
        Files.writeString(filePath, contentBuilder.toString());
    }

    public static String readTestData(String relativePath) {
        return readFile("test/data/" + relativePath);
    }

    public static String readFile(String relativePath) {
        try {
            Path path = resolveFromProjectRoot(relativePath);
            return Files.readString(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read test data file: " + relativePath, e);
        }
    }

    public static boolean fileExists(String relativePath) {
        Path path = resolveFromProjectRoot(relativePath);
        return path.toFile().exists();
    }

    private static Path resolveFromProjectRoot(String relativePath) {
        Path start = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path current = start;
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
            if (Files.exists(current.resolve(".git"))) {
                // Reached project root; return candidate even if missing so caller gets proper error
                return current.resolve(relativePath);
            }
            current = current.getParent();
        }
        return start.resolve(relativePath);
    }
}
