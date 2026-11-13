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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.pskenny.pkspkms.repo.PksFileRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Transport-agnostic MCP (Model Context Protocol) dispatcher: JSON-RPC 2.0 in,
 * JSON-RPC 2.0 out. {@code Server} feeds it the raw {@code POST /mcp} body;
 * a future stdio transport would feed it stdin lines. Stateless by design —
 * no sessions are minted or tracked (the 2026-07-28 draft removes protocol
 * sessions; older clients' {@code Mcp-Session-Id} headers are ignored).
 *
 * Tool execution failures surface as tool results with {@code isError: true};
 * protocol-level failures (unknown method, bad params) are JSON-RPC errors.
 */
public class McpDispatcher {

    public static final String SERVER_NAME = "pkspkms";
    // Must move with the pom.xml version; pinned by McpDispatcherTest.
    // public: the MCP health tool's server block reads it (repository layer)
    public static final String SERVER_VERSION = "0.1.0";
    static final String LATEST_PROTOCOL_VERSION = "2025-11-25";
    // Draft 2026-07-28: missing MCP-Protocol-Version header → assume 2025-03-26
    private static final String DEFAULT_PROTOCOL_VERSION = "2025-03-26";
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS =
            Set.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private final List<McpTool> tools;

    public McpDispatcher(PksFileRepository repository) {
        this.tools = PkspkmsTools.create(repository);
    }

    /**
     * Handles one JSON-RPC message.
     *
     * @return the JSON-RPC response, or {@code null} for an accepted
     *         notification (the HTTP layer answers 202 Accepted)
     */
    public String handle(String body) {
        JsonNode request;
        try {
            request = MAPPER.readTree(body);
        } catch (Exception e) {
            return errorResponse(null, PARSE_ERROR, "Parse error");
        }
        if (request == null || !request.isObject()) {
            return errorResponse(null, INVALID_REQUEST, "Invalid Request");
        }

        JsonNode id = request.get("id");
        if (id == null || id.isNull()) {
            // Notifications carry no id and expect no response; the stateless
            // server accepts and ignores them all
            return null;
        }
        if (!request.hasNonNull("method") || !request.get("method").isTextual()) {
            return errorResponse(id, INVALID_REQUEST, "Invalid Request");
        }
        String method = request.get("method").asText();
        JsonNode params = request.get("params");

        try {
            return switch (method) {
                case "initialize" -> MAPPER.writeValueAsString(ok(id, initializeResult(params)));
                case "ping" -> MAPPER.writeValueAsString(ok(id, new LinkedHashMap<String, Object>()));
                case "tools/list" -> MAPPER.writeValueAsString(ok(id, Map.of("tools", toolDescriptors())));
                case "tools/call" -> toolsCall(id, params);
                default -> errorResponse(id, METHOD_NOT_FOUND, "Method not found: " + method);
            };
        } catch (Exception e) {
            return errorResponse(id, INTERNAL_ERROR, "Internal error");
        }
    }

    public List<String> toolNames() {
        return tools.stream().map(McpTool::name).toList();
    }

    // Echo the client's requested version when we support it, otherwise answer
    // with our latest — the spec's negotiation rule
    private Map<String, Object> initializeResult(JsonNode params) {
        String requested = params != null && params.hasNonNull("protocolVersion")
                ? params.get("protocolVersion").asText()
                : null;
        String negotiated = requested != null && SUPPORTED_PROTOCOL_VERSIONS.contains(requested)
                ? requested
                : LATEST_PROTOCOL_VERSION;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", negotiated);
        result.put("capabilities", Map.of(
                "tools", Map.of(),
                "resources", Map.of()));
        result.put("serverInfo", Map.of("name", SERVER_NAME, "version", SERVER_VERSION));
        result.put("instructions", """
                Single-user personal knowledge vault. Search with Lucene-style property \
                queries (tags:foo, filePath:*.md, NOT tags:archive, "quoted phrases", \
                wildcards * ?) — text: searches bodies (FTS over rendered prose) and \
                hits carry the `modified` ISO timestamp, so recency ranges like \
                modified:[2026-11-01 TO *] work; limit: 0 returns totals only. \
                read_file on hits (optional `section` = heading-bounded slice with \
                repair hints on miss); virtual vaults are prefixed @alias/. \
                related/backlinks for the link graph (missing targets return \
                exists:false, never errors), outline for heading structure, \
                broken_links for the three-section hygiene report (wiki danglers, \
                dead markdown destinations, anchor rot with heading repair hints — \
                per-occurrence {source, line, offset} facts), graph_hops for \
                1–2-hop neighborhoods, embeds for base/luabase view provenance, \
                orphans for unlinked notes, tasks for open checkbox items \
                (line facts ride the spans). `path` is the single note argument; \
                list tools report rows_returned.""");
        return result;
    }

    private List<Map<String, Object>> toolDescriptors() {
        return tools.stream()
                .map(tool -> {
                    Map<String, Object> descriptor = new LinkedHashMap<>();
                    descriptor.put("name", tool.name());
                    descriptor.put("description", tool.description());
                    descriptor.put("inputSchema", tool.inputSchema());
                    // The entire tool surface is read-only: annotation carried on
                    // every descriptor so clients skip permission prompts
                    descriptor.put("annotations", Map.of("readOnlyHint", true));
                    return descriptor;
                })
                .toList();
    }

    private String toolsCall(JsonNode id, JsonNode params) throws Exception {
        if (params == null || !params.isObject()
                || !params.hasNonNull("name") || !params.get("name").isTextual()) {
            return errorResponse(id, INVALID_PARAMS, "Missing tool name");
        }
        String name = params.get("name").asText();
        McpTool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
        if (tool == null) {
            return errorResponse(id, INVALID_PARAMS, "Unknown tool: " + name);
        }

        JsonNode arguments = params.get("arguments");
        Map<String, Object> args;
        if (arguments == null || arguments.isNull()) {
            args = Map.of();
        } else if (arguments.isObject()) {
            args = MAPPER.convertValue(arguments, MAP_TYPE_REF);
        } else {
            return errorResponse(id, INVALID_PARAMS, "arguments must be an object");
        }

        try {
            String text = tool.handler().apply(args);
            return MAPPER.writeValueAsString(ok(id, Map.of(
                    "content", List.of(Map.of("type", "text", "text", text)),
                    "isError", false)));
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.toString();
            return MAPPER.writeValueAsString(ok(id, Map.of(
                    "content", List.of(Map.of("type", "text", "text", message)),
                    "isError", true)));
        }
    }

    private static Map<String, Object> ok(JsonNode id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private static String errorResponse(JsonNode id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id == null ? null : id);
        response.put("error", Map.of("code", code, "message", message));
        try {
            return MAPPER.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JSON-RPC error", e);
        }
    }
}
