package io.agentcore.runtime;

import io.agentcore.Json;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Opaque credentials are never exposed by toString or written to disk. */
public final class BootstrapToken {
    private final String jwt;
    private final URI controller;
    private final URI gateway;
    private final URI matrix;
    private BootstrapToken(String jwt, URI controller, URI gateway, URI matrix) {
        this.jwt = jwt; this.controller = controller; this.gateway = gateway; this.matrix = matrix;
    }
    public static BootstrapToken parse(String encoded) {
        try {
            var data = Json.read(new String(Base64.getDecoder().decode(encoded.trim()), StandardCharsets.UTF_8));
            if (!"agentcore".equals(data.get("product"))) throw new IllegalArgumentException();
            URI controller = AgentConfig.httpUrl(data.get("controllerUrl"));
            if (!controller.getPath().isEmpty() && !controller.getPath().equals("/")) throw new IllegalArgumentException();
            return new BootstrapToken(Json.text(data.get("jwtToken"), "jwtToken"), controller,
                AgentConfig.httpUrl(data.get("modelGatewayUrl")), AgentConfig.httpUrl(data.get("matrixUrl")));
        } catch (Exception error) { throw new IllegalArgumentException("AGENTCORE_DEBUG_TOKEN is invalid"); }
    }
    String jwt() { return jwt; }
    public URI controller() { return controller; }
    public URI gateway() { return gateway; }
    public URI matrix() { return matrix; }
    @Override public String toString() { return "BootstrapToken(<redacted>)"; }
}
