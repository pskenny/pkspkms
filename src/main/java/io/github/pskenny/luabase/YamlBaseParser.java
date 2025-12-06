package io.github.pskenny.luabase;

import org.yaml.snakeyaml.Yaml;

import java.util.Map;

public class YamlBaseParser {
    public Map<String, Object> parse(String yamlString) {
        Yaml yaml = new Yaml();
        return yaml.load(yamlString);
    }
}
