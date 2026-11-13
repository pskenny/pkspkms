package io.pskenny.pkspkms.io.processor.markdown;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts checkbox tasks from note bodies, line-based: open {@code - [ ] x}
 * items feed the {@code tasks} property, {@code [x]/[X]} items feed
 * {@code tasksDone}. Feed it the fence-masked content (the standard
 * BlockMasker pass) so demo checkboxes inside code fences never index.
 *
 * Deliberately line-simple: dash/star/plus list markers only, indented items
 * count, quoted or ordered-list tasks are out of scope; texts are trimmed,
 * deduplicated and capped so a pathological note can't balloon the
 * properties JSON.
 */
public class TaskReader {

    // 200 distinct tasks per list per note — mirror of the body cap's spirit:
    // bound the row size, not the intent
    private static final int MAX_TASKS = 200;

    private static final Pattern TASK_PATTERN =
            Pattern.compile("^\\s*([-*+])\\s+\\[([ xX])\\]\\s+(.+)\\s*$", Pattern.MULTILINE);

    /** Open and done task texts for one note. Both lists are distinct. */
    public record TaskLists(List<String> open, List<String> done) {}

    public TaskLists read(String content) {
        if (content == null || content.isEmpty()) {
            return new TaskLists(new ArrayList<>(), new ArrayList<>());
        }
        LinkedHashSet<String> open = new LinkedHashSet<>();
        LinkedHashSet<String> done = new LinkedHashSet<>();
        Matcher matcher = TASK_PATTERN.matcher(content);
        while (matcher.find()) {
            String text = matcher.group(3).strip();
            if (text.isEmpty()) {
                continue;
            }
            if (" ".equals(matcher.group(2))) {
                if (open.size() < MAX_TASKS) {
                    open.add(text);
                }
            } else if (done.size() < MAX_TASKS) {
                done.add(text);
            }
        }
        return new TaskLists(new ArrayList<>(open), new ArrayList<>(done));
    }
}
