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

package io.pskenny.pkspkms.mcp;

import java.util.Map;
import java.util.function.Function;

/**
 * One MCP tool. Transport-agnostic: the handler takes the decoded
 * {@code arguments} object from a {@code tools/call} request and returns the
 * text content the client should show. Throwing from the handler is fine —
 * {@link McpDispatcher} turns it into a tool result with {@code isError: true}.
 */
public record McpTool(
        String name,
        String description,
        Map<String, Object> inputSchema,
        Function<Map<String, Object>, String> handler) {
}
