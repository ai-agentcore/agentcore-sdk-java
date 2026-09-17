package io.agentcore;

import static org.junit.jupiter.api.Assertions.*;
import io.agentcore.auth.AccessKeyCredential;
import io.agentcore.runtime.AgentConfig;
import io.agentcore.runtime.RuntimeEnvironment;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeContractTest {
    @Test void explicitWorkspaceCannotBeCombinedWithRuntimePaths() {
        assertThrows(IllegalArgumentException.class, () -> AgentCore.builder().workspaceId("ws-explicit").regionId("cn-hangzhou")
            .configPath(java.nio.file.Path.of("agent.yaml")).build());
        assertThrows(IllegalArgumentException.class, () -> AgentCore.builder().workspaceId("ws-explicit").regionId("cn-hangzhou")
            .envPath(java.nio.file.Path.of("env")).build());
    }
    @Test void explicitWorkspaceDoesNotFallBackToRuntimeCredentials() {
        try (var core = AgentCore.builder().workspaceId("ws-explicit").regionId("cn-hangzhou").build()) {
            var error = assertThrows(IllegalStateException.class, () -> core.controlPlane().flatMap(cp ->
                cp.request("ListModels", "GET", "/models", Map.of())).block(java.time.Duration.ofSeconds(1)));
            assertTrue(error.getMessage().contains("accessKeyCredential"), error.getMessage());
        }
    }
    @Test void reusingBuilderDoesNotChangeAnExistingClient() {
        var builder = AgentCore.builder().workspaceId("ws-first").regionId("region-first")
            .accessKeyCredential(new AccessKeyCredential("test-ak", "test-sk"));
        try (var first = builder.build(); var second = builder.workspaceId("ws-second").build()) {
            assertEquals("ws-first", first.controlPlane().block().workspaceId());
            assertEquals("ws-second", second.controlPlane().block().workspaceId());
        }
    }
    @Test void configKeepsResourceContextAndNormalizesGatewayHeaders() {
        var config = AgentConfig.parse("""
            apiVersion: agentteams.io/v1alpha1
            kind: AgentConfig
            metadata:
              runtimeName: example-agent
              workspaceId: ws-example
              regionId: cn-hangzhou
            spec:
              model:
                gatewayUrl: https://gateway.example.com/model-connection
              mcp:
                gatewayUrl: https://gateway.example.com/mcp-servers
              credentials:
                header:
                  - key: Authorization
                    value: Bearer example-token
            """);
        assertEquals("ws-example", config.workspaceId());
        assertEquals("Bearer example-token", config.headers().get("authorization"));
        assertFalse(config.toString().contains("example-token"));
        assertThrows(UnsupportedOperationException.class, () -> config.headers().put("x-user", "other"));
    }

    @Test void environmentUsesAgentCoreNamesBeforeLegacyNames() {
        var env = RuntimeEnvironment.parse("""
            export AGENTTEAMS_CONTROLLER_URL='https://old.example.com'
            export AGENTCORE_CONTROLLER_URL="https://controller.example.com"
            export AGENTCORE_AUTH_TOKEN_FILE='/var/run/sa-token'
            """);
        assertEquals("https://controller.example.com", env.controllerUrl());
        assertEquals("/var/run/sa-token", env.tokenFile().toString());
    }

    @Test void credentialsNeverPrintSecrets() {
        var credential = new AccessKeyCredential("example-ak", "example-secret", "example-sts");
        assertFalse(credential.toString().contains("example-secret"));
        assertFalse(credential.toString().contains("example-sts"));
    }

    @Test void processEnvironmentOverridesFileButAgentCoreNamesWinOverLegacyNames() {
        var env = RuntimeEnvironment.parse("""
            export AGENTCORE_CONTROLLER_URL='https://file.example.com'
            export AGENTCORE_AUTH_TOKEN_FILE='/tmp/file-sa'
            """, Map.of("AGENTCORE_CONTROLLER_URL", "https://process.example.com",
                "AGENTTEAMS_AUTH_TOKEN_FILE", "/tmp/legacy-sa"));
        assertEquals("https://process.example.com", env.controllerUrl());
        assertEquals("/tmp/file-sa", env.tokenFile().toString());
    }

    @Test void headersCannotOverridePlatformAuthWithDifferentCase() {
        var platform = Map.of("Authorization", "Bearer example-token");
        assertThrows(IllegalArgumentException.class,
            () -> Headers.merge(platform, Map.of("aUtHoRiZaTiOn", "other")));
        assertThrows(IllegalArgumentException.class,
            () -> Headers.merge(platform, Map.of("Mcp-Session-ID", "other")));
        assertEquals("alice", Headers.merge(platform, Map.of("X-User", "alice")).get("x-user"));
        assertFalse(platform.containsKey("x-user"));
    }
    @Test void explicitHeadersCannotOverrideCredentialOrPlatformHeaders() {
        assertThrows(IllegalArgumentException.class, () -> Headers.merge(Map.of("Authorization", "Bearer platform"),
            Map.of("X-App", "credential"), Map.of("x-app", "explicit")));
        for (String name : java.util.List.of("Accept", "Content-Type", "Last-Event-ID", "Upgrade"))
            assertThrows(IllegalArgumentException.class, () -> Headers.merge(Map.of(), Map.of(name, "custom")));
        assertThrows(IllegalArgumentException.class, () -> Headers.merge(Map.of("X-Platform", "fixed"), Map.of("x-platform", "other")));
        assertThrows(IllegalArgumentException.class, () -> Headers.merge(Map.of(), Map.of("X-App", "one", "x-app", "two")));
    }
}
