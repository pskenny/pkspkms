package io.github.pskenny.luabase;

import org.yaml.snakeyaml.Yaml;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

public class YamlParser {
    public static class JsDateWrapper extends Date {
        private final String dateValue;

        public JsDateWrapper(String dateValue) {
            this.dateValue = dateValue;

            try {
                LocalDate localDate = LocalDate.parse(dateValue);
                super.setYear(localDate.getYear() - 1900);
                super.setMonth(localDate.getMonthValue() - 1);
                super.setDate(localDate.getDayOfMonth());
                super.setHours(0);
                super.setMinutes(0);
                super.setSeconds(0);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid date format or value for YYYY-MM-DD: " + dateValue, e);
            }
        }

        @Override
        public String toString() {
            return dateValue;
        }
    }

    public Map<String, Object> parse(String yamlString) {
        Yaml yaml = new Yaml(new JsDateInterceptConstructor());
        return yaml.load(yamlString);
    }

    public static class JsDateInterceptConstructor extends Constructor {
        public static final Pattern DATE_ONLY_PATTERN =
                Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

        public JsDateInterceptConstructor() {
            super(new LoaderOptions());
            this.yamlConstructors.put(Tag.TIMESTAMP, new ConstructDate());
        }

        private class ConstructDate extends AbstractConstruct {
            @Override
            public Object construct(Node node) {
                String val = ((ScalarNode) node).getValue();
                Matcher m = DATE_ONLY_PATTERN.matcher(val);
                if (m.matches()) {
                    return new JsDateWrapper(val);
                } else {
                    return Date.parse(val);
                }
            }
        }
    }
}