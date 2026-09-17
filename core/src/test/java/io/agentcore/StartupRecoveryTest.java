package io.agentcore;

import io.agentcore.auth.AccessKeyCredential;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StartupRecoveryTest {
    @TempDir Path directory;
    private static final String CONFIG = """
        apiVersion: agentteams.io/v1alpha1
        kind: AgentConfig
        metadata: {runtimeName: fixture, workspaceId: ws-fixture, regionId: cn-hangzhou}
        spec:
          model: {gatewayUrl: 'https://gateway.example.com/model-connection'}
          mcp: {gatewayUrl: 'https://gateway.example.com/mcp-servers'}
          credentials: {header: [{key: Authorization, value: 'Bearer fixture'}]}
        """;
    @Test void configurationWaitsForMountAndThenKeepsSnapshot() throws Exception {
        Path path = directory.resolve("agent.yaml");
        try (var core = AgentCore.builder().configPath(path).accessKeyCredential(new AccessKeyCredential("fixture-ak", "fixture-sk")).build()) {
            var pending = core.controlPlane().toFuture();
            Thread.sleep(100); assertFalse(pending.isDone());
            Files.writeString(path, CONFIG);
            assertEquals("ws-fixture", pending.get(3, java.util.concurrent.TimeUnit.SECONDS).workspaceId());
            Files.writeString(path, CONFIG.replace("ws-fixture", "ws-other"));
            assertEquals("ws-fixture", core.controlPlane().block(Duration.ofSeconds(1)).workspaceId());
        }
    }
    @Test void invalidConfigurationIsNotCachedAsHalfInitializedRuntime() throws Exception {
        Path path = directory.resolve("agent.yaml"); Files.writeString(path, "invalid: [");
        try (var core = AgentCore.builder().configPath(path).accessKeyCredential(new AccessKeyCredential("fixture-ak", "fixture-sk")).build()) {
            assertThrows(IllegalArgumentException.class, () -> core.controlPlane().block(Duration.ofSeconds(1)));
            Files.writeString(path, CONFIG);
            assertEquals("ws-fixture", core.controlPlane().block(Duration.ofSeconds(1)).workspaceId());
        }
    }
}
