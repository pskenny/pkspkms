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

package io.pskenny.pkspkms.io.processor.markdown;

import io.pskenny.pkspkms.io.YamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class YamlFrontmatterReader {
    private static final Logger logger = LoggerFactory.getLogger(YamlFrontmatterReader.class);

    public Map<String, Object> getFrontMatterProperties(final String fileContent) {
        if (fileContent == null || fileContent.isEmpty()) {
            return new LinkedHashMap<>();
        }
        // Shared Frontmatter oracle (BUGS.md H4): same grammar as the processor
        // body slice and Export's strip — one frontmatter definition, three readers.
        String yamlBlock = io.pskenny.pkspkms.io.Frontmatter.yamlBlock(fileContent);
        String yaml = yamlBlock.trim();
        if (yaml.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new YamlParser().parse(yaml);
    }
}