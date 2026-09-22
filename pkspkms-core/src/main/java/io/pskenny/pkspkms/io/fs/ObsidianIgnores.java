package io.pskenny.pkspkms.io.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Obsidian's Excluded files list: userIgnoreFilters in .obsidian/app.json.
 * Pure replacement — no (valid) app.json means nothing is ignored beyond the
 * loader's always-on .obsidian/.trash. Entries match case-insensitively:
 * folder/path entries use a boundary-aware prefix (so "Archive/" does not hit
 * "Archives/x.md"); entries containing '*' compile to a regex glob.
 */
public final class ObsidianIgnores {

    private static final Logger logger = LoggerFactory.getLogger(ObsidianIgnores.class);

    private static final String APP_JSON = ".obsidian/app.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Filter> filters;

    private ObsidianIgnores(List<Filter> filters) {
        this.filters = filters;
    }

    public static Predicate<String> from(PkmsFileSystem fs) {
        try (InputStream in = fs.openInput(APP_JSON)) {
            JsonNode filtersNode = MAPPER.readTree(in).get("userIgnoreFilters");
            if (filtersNode == null || !filtersNode.isArray()) {
                logger.warn("{} has no userIgnoreFilters array; ignoring nothing", APP_JSON);
                return path -> false;
            }

            List<Filter> compiled = new ArrayList<>();
            for (JsonNode node : filtersNode) {
                if (node.isTextual()) {
                    compiled.add(Filter.of(node.asText()));
                }
            }
            ObsidianIgnores ignores = new ObsidianIgnores(compiled);
            return ignores::ignores;
        } catch (IOException e) {
            return path -> false;
        }
    }

    private boolean ignores(String relativePath) {
        String path = relativePath.toLowerCase(Locale.ROOT);
        for (Filter filter : filters) {
            if (filter.matches(path)) {
                return true;
            }
        }
        return false;
    }

    private record Filter(String prefix, Pattern glob) {

        static Filter of(String raw) {
            String filter = raw.strip()
                    .replaceAll("^/+", "")
                    .replaceAll("/+$", "")
                    .toLowerCase(Locale.ROOT);

            if (filter.isEmpty()) {
                return new Filter("", null);
            }
            if (filter.contains("*")) {
                return new Filter(null, globToRegex(filter));
            }
            return new Filter(filter, null);
        }

        private static Pattern globToRegex(String glob) {
            StringBuilder regex = new StringBuilder();
            for (char c : glob.toCharArray()) {
                if (c == '*') {
                    regex.append(".*");
                } else {
                    regex.append(Pattern.quote(String.valueOf(c)));
                }
            }
            return Pattern.compile(regex.toString());
        }

        boolean matches(String lowercasePath) {
            if (glob != null) {
                return glob.matcher(lowercasePath).matches();
            }
            if (prefix.isEmpty()) {
                return false;
            }
            return lowercasePath.equals(prefix) || lowercasePath.startsWith(prefix + "/");
        }
    }
}
