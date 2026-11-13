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

public class ListRenderer {
    private static final Logger logger = LoggerFactory.getLogger(ListRenderer.class);

    public String render(List<String> orderSpec, Collection<PksFile> files, LuaBaseInterpreter luaBaseInterpreter) {
        orderSpec = TableRenderer.nonBlank(orderSpec);
        if (orderSpec.isEmpty()) {
            // what do you do when you aren't given an order? every file has a filePath
            orderSpec = List.of("getPropertyValue(file, \"filePath\", \"\"), \"\"");
        }
        List<String[]> columns = new ArrayList<>();
        orderSpec.forEach(colSpec -> columns.add(ColSpec.parse(colSpec)));
        // headers unused by the list shape; columns carry every spec
        // Blank order entries drop once here — not per-row compile errors

        // Row- and column-level failure isolation (BUGS.md M8/M2): one failing
        // cell degrades to an "error" marker instead of aborting the whole
        // list render — symmetric with TableRenderer. Oversized values clamp
        // via CellEscapes.
        final AtomicInteger failures = new AtomicInteger();
        StringBuilder sb = new StringBuilder();
        files.forEach(pksFile -> {
            StringBuilder row = new StringBuilder("-");
            java.util.Map<String, Object> fileProperties = pksFile.getMutableProperties();
            try {
                for (String[] column : columns) {
                    String propertyValue = LuaCells.text(
                            luaBaseInterpreter.evaluateLuaExpression(column[0], fileProperties));
                    row.append(" ").append(CellEscapes.mdCell(propertyValue));
                }
            } catch (Throwable t) {
                failures.incrementAndGet();
                row.append(" error");
            }
            sb.append(row);
            sb.append("\n");
        });
        if (failures.get() > 0) {
            logger.warn("List render: {} row(s) had failing expressions", failures.get());
        }
        return sb.toString();
    }
}
