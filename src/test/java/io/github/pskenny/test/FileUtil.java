package io.github.pskenny.test;

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

    public static String readFile(String relativePath) {
        try {
            Path path = Paths.get(System.getProperty("user.dir"), relativePath);
            return Files.readString(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read test data file: " + relativePath, e);
        }
    }
}
