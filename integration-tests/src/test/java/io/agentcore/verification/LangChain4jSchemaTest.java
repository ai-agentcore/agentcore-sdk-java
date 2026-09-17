package io.agentcore.verification;

import io.agentcore.Json;
import io.agentcore.langchain4j.AgentCoreLangChain4j;
import io.agentcore.tool.Tool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class LangChain4jSchemaTest {
    @ParameterizedTest
    @ValueSource(strings = {"OPENAI", "ANTHROPIC"})
    void nativeModelRequestsCarryDefinitions(String protocol) throws Exception {
        var request = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            request.set(Json.read(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            String response = protocol.equals("OPENAI")
                ? "{\"id\":\"reply\",\"model\":\"test\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"done\"},\"finish_reason\":\"stop\"}]}"
                : "{\"id\":\"reply\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"test\",\"content\":[{\"type\":\"text\",\"text\":\"done\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
            var bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try (var core = io.agentcore.AgentCore.auto()) {
            var schema = Json.read("""
                {"type":"object","properties":{"address":{"$ref":"#/$defs/Address"}},"required":["address"],
                 "$defs":{"Address":{"type":"object","properties":{"city":{"type":"string","minLength":1}}}}}
                """);
            var tool = new Tool("lookup", "Look up an address", schema, args -> Mono.just("done"));
            var specification = AgentCoreLangChain4j.tools(List.of(tool)).keySet().iterator().next();
            var source = core.directModel("test", java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                io.agentcore.model.ModelClient.Protocol.valueOf(protocol), () -> Mono.just(Map.of()));
            AgentCoreLangChain4j.model(source).chat(dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(dev.langchain4j.data.message.UserMessage.from("Hello"))
                .toolSpecifications(specification).build());
            var wireTool = Json.object(((List<?>) request.get().get("tools")).get(0));
            var parameters = protocol.equals("OPENAI") ? Json.object(Json.object(wireTool.get("function")).get("parameters"))
                : Json.object(wireTool.get("input_schema"));
            assertEquals(schema.get("$defs"), parameters.get("$defs"));
            assertEquals(schema.get("properties"), parameters.get("properties"));
            assertEquals(schema.get("required"), parameters.get("required"));
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"$defs", "definitions"})
    void referencedAndRecursiveToolParametersArePreserved(String keyword) {
        var schema = Json.read("""
            {"type":"object","properties":{"node":{"$ref":"#/DEFS/Node"}},
             "required":["node"],"additionalProperties":false,
             "DEFS":{"Node":{"type":"object","properties":{
               "name":{"type":"string","minLength":1},
               "children":{"type":"array","items":{"$ref":"#/DEFS/Node"}},
               "literal":{"type":"object","default":{"$ref":"#/DEFS/not-a-schema"}}},
               "required":["name"]}}}
            """.replace("DEFS", keyword));
        var tool = new Tool("tree", "Read a tree", schema, args -> Mono.just("done"));
        var specification = AgentCoreLangChain4j.tools(List.of(tool)).keySet().iterator().next();
        var output = Json.object(Json.read(specification.toJson()).get("parameters"));
        assertEquals(List.of("node"), output.get("required"));
        assertEquals(false, output.get("additionalProperties"));
        assertEquals(Map.of("$ref", "#/$defs/Node"), Json.object(output.get("properties")).get("node"));
        var node = Json.object(Json.object(output.get("$defs")).get("Node"));
        assertEquals(List.of("name"), node.get("required"));
        var properties = Json.object(node.get("properties"));
        assertEquals(Map.of("type", "string", "minLength", 1), properties.get("name"));
        assertEquals(Map.of("$ref", "#/$defs/Node"), Json.object(properties.get("children")).get("items"));
        assertEquals(Map.of("$ref", "#/" + keyword + "/not-a-schema"), Json.object(properties.get("literal")).get("default"));
        assertTrue(schema.containsKey(keyword)); // Conversion must not mutate caller-owned schemas.
    }
}
