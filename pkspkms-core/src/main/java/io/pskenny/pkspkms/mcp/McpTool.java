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
