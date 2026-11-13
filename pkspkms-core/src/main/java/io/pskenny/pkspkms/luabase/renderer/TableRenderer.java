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

package io.pskenny.pkspkms.luabase.renderer;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.luabase.LuaBaseInterpreter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class TableRenderer {
    private static final Logger logger = LoggerFactory.getLogger(TableRenderer.class);

    // BUGS.md D1: blank order entries drop ONCE here — they used to compile-error
    // per row (a `{"", ""}` colSpec recompiles+rethrows every row)
    static List<String> nonBlank(List<String> orderSpec) {
        if (orderSpec == null) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>(orderSpec.size());
        for (String entry : orderSpec) {
            if (entry != null && !entry.isBlank()) {
                cleaned.add(entry);
            }
        }
        return cleaned;
    }

    public String render(List<String> orderSpec, Collection<PksFile> files, LuaBaseInterpreter luaBaseInterpreter) {
        orderSpec = nonBlank(orderSpec);
        if (orderSpec.isEmpty()) {
            // what do you do when you aren't given an order? every file has a filePath
            orderSpec = List.of("getPropertyValue(file, \"filePath\", \"\"), \"Path\"");
        }
        List<String[]> columns = new ArrayList<>();
        orderSpec.forEach(colSpec -> columns.add(ColSpec.parse(colSpec)));

        List<String> headers = new ArrayList<>();
        for (String[] column : columns) {
            headers.add(CellEscapes.mdCell(column[1]));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("| " + String.join(" | ", headers) + " |")
                .append("\n")
                .append("|" + "---|".repeat(headers.size() - 1) + "---|")
                .append("\n");

        // Render-time failures are counted, not spammed: one row's failing
        // expression used to log a full stack trace per row (thousands of lines).
        // M8: the catch is PER COLUMN — a failing middle column renders its
        // own ` error |` marker while remaining columns still render, so the
        // cell count always matches the header.
        final AtomicInteger failures = new AtomicInteger();
        files.forEach(pksFile -> {
            StringBuilder row = new StringBuilder("|");
            java.util.Map<String, Object> fileProperties = pksFile.getMutableProperties();
            for (String[] column : columns) {
                try {
                    String propValue = LuaCells.text(
                            luaBaseInterpreter.evaluateLuaExpression(column[0], fileProperties));
                    row.append(" ").append(CellEscapes.mdCell(propValue)).append(" |");
                } catch (Throwable t) {
                    failures.incrementAndGet();
                    row.append(" error |");
                }
            }
            sb.append(row);
            sb.append("\n");
        });
        if (failures.get() > 0) {
            logger.warn("Table render: {} cell(s) had failing expressions", failures.get());
        }
        return sb.toString();
    }
}
