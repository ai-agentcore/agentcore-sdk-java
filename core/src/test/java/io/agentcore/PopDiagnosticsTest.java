package io.agentcore;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.agentcore.auth.AccessKeyCredential;
import io.agentcore.controlplane.ControlPlane;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

class PopDiagnosticsTest {
  private final Logger logger = (Logger) LoggerFactory.getLogger("io.agentcore");
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void captureLogs() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachLogs() {
    logger.detachAppender(appender);
    appender.stop();
  }

  @Test
  void controlPlaneLogsServiceDiagnosticsWithoutSecrets() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] bytes =
              Json.write(
                      Map.of(
                          "Code",
                          "Forbidden",
                          "RequestId",
                          "req-pop",
                          "Message",
                          "Workspace access denied; token=PRIVATE_TOKEN"))
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(403, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var error =
          assertThrows(
              AgentCoreException.class,
              () ->
                  controlPlane(server.getAddress().getPort())
                      .request("ListMcps", "GET", "/mcps", Map.of())
                      .block(Duration.ofSeconds(5)));
      assertEquals(403, error.status());
      assertEquals("Forbidden", error.serviceCode());
      assertEquals("req-pop", error.requestId());
      String failure = failureLog();
      for (var expected :
          new String[] {
            "operation=ListMcps",
            "status=403",
            "code=Forbidden",
            "requestId=req-pop",
            "Workspace access denied"
          }) assertTrue(failure.contains(expected), failure);
      for (var secret : new String[] {"PRIVATE_TOKEN", "PRIVATE_SK", "PRIVATE_STS"}) {
        assertFalse(failure.contains(secret), failure);
        assertFalse(error.getMessage().contains(secret));
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void connectionFailureStillEmitsDiagnosticLog() throws Exception {
    int port;
    try (var reservation = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
      port = reservation.getLocalPort();
    }
    var error =
        assertThrows(
            AgentCoreException.class,
            () ->
                controlPlane(port)
                    .request("ListMcps", "GET", "/mcps", Map.of(), null, false, 500)
                    .block(Duration.ofSeconds(5)));
    assertNotNull(error.getCause());
    String failure = failureLog();
    assertTrue(failure.contains("operation=ListMcps"), failure);
    assertTrue(failure.contains("host=127.0.0.1:" + port), failure);
    assertTrue(
        failure.contains("error_type=" + error.getCause().getClass().getSimpleName()), failure);
    assertFalse(failure.contains("PRIVATE"), failure);
  }

  private ControlPlane controlPlane(int port) {
    return new ControlPlane(
        "ws-test",
        "cn-hangzhou",
        URI.create("http://127.0.0.1:" + port),
        purpose -> Mono.just(new AccessKeyCredential("test-ak", "PRIVATE_SK", "PRIVATE_STS")));
  }

  private String failureLog() {
    var failures =
        appender.list.stream()
            .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    assertEquals(1, failures.size(), failures.toString());
    return failures.get(0);
  }
}
