/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of PKSPKMS.
 * Copyright (C) 2026 Paul Kenny
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package io.pskenny.pkspkms.io;

import org.yaml.snakeyaml.Yaml;

import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

public class YamlParser {

    public Map<String, Object> parse(String yamlString) {
        Yaml yaml = new Yaml(new JsDateInterceptConstructor());
        return yaml.load(expandLeadingTabs(yamlString));
    }

    // Obsidian tolerates tab indentation; SnakeYAML does not. Expand every tab inside
    // the leading whitespace run (spaces and tabs may interleave), 1 tab = 4 spaces.
    // Tabs inside quoted scalars are preserved.
    public static String expandLeadingTabs(String yaml) {
        if (yaml == null || yaml.indexOf('\t') == -1) {
            return yaml;
        }
        String[] lines = yaml.split("\n", -1);
        StringBuilder sb = new StringBuilder(yaml.length() + 64);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int indentEnd = 0;
            while (indentEnd < line.length()
                    && (line.charAt(indentEnd) == ' ' || line.charAt(indentEnd) == '\t')) {
                indentEnd++;
            }
            if (i > 0) {
                sb.append('\n');
            }
            for (int c = 0; c < indentEnd; c++) {
                char ch = line.charAt(c);
                sb.append(ch == '\t' ? "    " : ch);
            }
            sb.append(line.substring(indentEnd));
        }
        return sb.toString();
    }

    public static class JsDateInterceptConstructor extends Constructor {
        public JsDateInterceptConstructor() {
            super(new LoaderOptions());
            this.yamlConstructors.put(Tag.TIMESTAMP, new ConstructDate());
        }

        // Timestamps keep their original text: Date objects would be serialized to
        // epoch millis by Jackson on the DB properties round trip. Nothing
        // downstream needs date arithmetic; ISO strings still sort lexicographically.
        private static class ConstructDate extends AbstractConstruct {
            @Override
            public Object construct(Node node) {
                return ((ScalarNode) node).getValue();
            }
        }
    }
}