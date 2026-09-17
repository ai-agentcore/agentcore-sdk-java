package io.agentcore.auth;

import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teaopenapi.models.OpenApiRequest;
import com.aliyun.teaopenapi.models.Params;
import com.aliyun.teautil.models.RuntimeOptions;
import io.agentcore.AgentCoreException;
import io.agentcore.Json;
import io.agentcore.controlplane.ControlPlane;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class BoundCredentials {
    private final Supplier<Mono<ControlPlane>> controlPlane;
    private final Supplier<Mono<ControllerCredentials>> controller;
    public BoundCredentials(Supplier<Mono<ControlPlane>> controlPlane, Supplier<Mono<ControllerCredentials>> controller) {
        this.controlPlane = controlPlane; this.controller = controller;
    }
    public Mono<BoundCredential> get(String name) { return resolve(name, null); }
    public Mono<BoundCredential> forMcp(String name, String serverId) { return resolve(name, serverId); }
    private Mono<BoundCredential> resolve(String name, String serverId) {
        return Mono.defer(controlPlane).flatMap(cp -> cp.list("ListCredentials", "/credentials", Map.of("name", name))
            .map(items -> ControlPlane.exact(items, "name", name)).flatMap(metadata -> {
                if (serverId != null) validateScope(metadata, serverId);
                return Mono.defer(controller).flatMap(source -> source.get("agentidentitydata")
                    .flatMap(sts -> source.workloadAccessToken().flatMap(wat -> exchange(cp, name, sts, wat)
                        .onErrorResume(WatRejected.class, error -> {
                            source.invalidateWorkloadAccessToken(wat);
                            return source.workloadAccessToken().flatMap(fresh -> exchange(cp, name, sts, fresh));
                        })))).map(value -> new BoundCredential(value, metadata));
            }));
    }
    private static void validateScope(Map<String, Object> metadata, String serverId) {
        if (!"mcpHeader".equals(metadata.get("credentialType"))) throw new IllegalArgumentException("MCP binding requires an MCP Header credential");
        if ("ALL".equals(metadata.get("resourceScope"))) return;
        var refs = (List<?>) metadata.getOrDefault("resourceRefs", List.of());
        if ("SPECIFIED".equals(metadata.get("resourceScope")) && refs.stream().map(Json::object)
            .anyMatch(ref -> "mcpServer".equals(ref.get("resourceType")) && serverId.equals(ref.get("resourceId")))) return;
        throw new IllegalArgumentException("Credential scope does not include this MCP server");
    }
    private static Mono<String> exchange(ControlPlane cp, String name, AccessKeyCredential sts, String wat) {
        return Mono.fromCallable(() -> {
            var config = new Config().setAccessKeyId(sts.accessKeyId()).setAccessKeySecret(sts.accessKeySecret())
                .setSecurityToken(sts.securityToken()).setRegionId(cp.regionId()).setEndpoint("agentidentitydata." + cp.regionId() + ".aliyuncs.com");
            var client = new com.aliyun.teaopenapi.Client(config);
            var params = new Params().setAction("GetResourceAPIKey").setVersion("2025-11-27").setProtocol("HTTPS")
                .setPathname("/").setMethod("POST").setAuthType("AK").setStyle("RPC").setReqBodyType("formData").setBodyType("json");
            var request = new OpenApiRequest().setBody(Map.of("ResourceCredentialProviderName", cp.workspaceId() + "-" + name, "WorkloadAccessToken", wat));
            try {
                var result = client.callApi(params, request, new RuntimeOptions().setAutoretry(false).setConnectTimeout(10_000).setReadTimeout(30_000));
                return Json.text(Json.object(result.get("body")).get("APIKey"), "APIKey");
            } catch (com.aliyun.tea.TeaException error) {
                var data = error.getData();
                Object id = data == null ? null : data.getOrDefault("RequestId", data.get("requestId"));
                Integer status = data != null && data.get("statusCode") instanceof Number n ? n.intValue() : error.getStatusCode();
                Object message = data == null ? null : data.getOrDefault("Message", data.get("message"));
                var failure = new AgentCoreException("GetResourceAPIKey", status, id instanceof String s ? s : null,
                    error.getCode(), message instanceof String s ? s : error.getMessage(), error);
                org.slf4j.LoggerFactory.getLogger(BoundCredentials.class).warn(
                    "agentcore.credential.api.failed operation=GetResourceAPIKey provider_name={} host={} status={} code={} requestId={} message={}",
                    cp.workspaceId() + "-" + name, config.getEndpoint(), failure.status(), failure.serviceCode(), failure.requestId(), failure.serviceMessage());
                if (List.of("WORKLOAD_ACCESS_TOKEN_EXPIRED", "WORKLOAD_ACCESS_TOKEN_INVALID").contains(error.getCode())) throw new WatRejected();
                throw failure;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
    private static final class WatRejected extends RuntimeException {}
}
