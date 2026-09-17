package io.agentcore;

import java.util.List;
import java.util.Map;
import java.util.Scanner;

/** Minimal wire peer for testing the official client's process transport. */
public final class StdioMcpFixture {
    static Object result(Map<String, Object> request) {
        return switch ((String) request.get("method")) {
            case "initialize" -> Map.of("protocolVersion", Json.object(request.get("params")).get("protocolVersion"),
                "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "fixture", "version", "1"));
            case "tools/list" -> Map.of("tools", List.of(Map.of("name", "echo", "description", "echo",
                "inputSchema", Map.of("type", "object", "properties", Map.of()))));
            case "tools/call" -> Map.of("content", List.of(Map.of("type", "text", "text", "echoed")), "isError", false);
            default -> Map.of();
        };
    }
    public static void main(String[] args) {
        try (var input = new Scanner(System.in)) {
            while (input.hasNextLine()) {
                var request = Json.read(input.nextLine());
                if (request.get("id") != null) {
                    System.out.println(Json.write(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result(request))));
                    System.out.flush();
                }
            }
        }
    }
}
