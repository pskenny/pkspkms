package io.pskenny.pkspkms.io.yaml;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.HashMap;
import java.util.Map;

// Unused currently
public class YamlPatchEngine {

    private final Yaml yaml;

    public YamlPatchEngine() {
        // Configure options to output clean, human-readable Block-style YAML
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        this.yaml = new Yaml(options);
    }

    /**
     * Applies a YAML patch string to a target YAML frontmatter string.
     * * @param targetYaml The original YAML string.
     * @param patchYaml  The patch YAML string containing updates/deletions.
     * @return The updated, freshly dumped YAML string.
     */
    public String applyPatch(String targetYaml, String patchYaml) {
        Map<String, Object> targetMap = yaml.load(targetYaml);
        Map<String, Object> patchMap = yaml.load(patchYaml);

        // Handle edge cases where input might be blank
        if (targetMap == null) targetMap = new HashMap<>();
        if (patchMap == null) return yaml.dump(targetMap);

        // Execute the deep merge
        deepMerge(targetMap, patchMap);

        return yaml.dump(targetMap);
    }

    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> target, Map<String, Object> patch) {
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = entry.getKey();
            Object patchValue = entry.getValue();

            if (patchValue == null) {
                // Rule: Explicit null means delete the key
                target.remove(key);
            } else if (patchValue instanceof Map) {
                Object targetValue = target.get(key);

                if (targetValue instanceof Map) {
                    // Both are nesting maps -> Recurse deeper
                    deepMerge((Map<String, Object>) targetValue, (Map<String, Object>) patchValue);
                } else {
                    // Target isn't a map or doesn't exist -> Overwrite/Inject the patch map entirely
                    target.put(key, patchValue);
                }
            } else {
                // Rule: Primitives, Lists, and Strings overwrite the target completely
                target.put(key, patchValue);
            }
        }
    }
}