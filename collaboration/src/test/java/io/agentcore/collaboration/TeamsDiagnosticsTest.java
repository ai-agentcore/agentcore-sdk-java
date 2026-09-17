package io.agentcore.collaboration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class TeamsDiagnosticsTest {
    @TempDir Path directory;
    @Test void rejectedUpdateLogsReasonAndKeepsLastGood() throws Exception {
        var path = directory.resolve("teams.yaml");
        Files.writeString(path, """
            apiVersion: agentteams.io/v1alpha1
            kind: TeamsConfig
            metadata: {runtimeName: worker}
            spec:
              self: {name: worker, runtimeName: worker}
              teams: []
            """);
        var provider = new TeamsProvider(path); var good = provider.snapshot().block();
        var logger = (Logger) LoggerFactory.getLogger(TeamsProvider.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            Files.writeString(path, "kind: broken\nsecret: PRIVATE");
            assertSame(good, provider.snapshot().block());
            String log = appender.list.get(0).getFormattedMessage();
            assertTrue(log.contains("update_ignored")); assertTrue(log.contains("Unsupported teams.yaml kind or apiVersion"));
            assertFalse(log.contains("PRIVATE"));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
    @Test void diagnosticDescriptionDoesNotReachToolResult() {
        var error = new io.agentcore.AgentCoreException("Task Service", 503, "req-task", "Unavailable", "INTERNAL_DIAGNOSTIC", null);
        var result = TaskServiceClient.errorResult(error);
        assertTrue(error.getMessage().contains("INTERNAL_DIAGNOSTIC"));
        assertFalse(result.toString().contains("INTERNAL_DIAGNOSTIC"));
        assertEquals("COLLABORATION_TASK_UNAVAILABLE", result.get("code"));
        assertTrue(result.toString().contains("req-task"));
    }
}
