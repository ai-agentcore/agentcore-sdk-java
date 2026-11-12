package io.agentcore.internal;

import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teaopenapi.models.OpenApiRequest;
import com.aliyun.teaopenapi.models.Params;
import com.aliyun.teautil.models.RuntimeOptions;
import io.agentcore.AgentCoreException;
import io.agentcore.Json;
import io.agentcore.auth.AccessKeyCredential;
import io.agentcore.controlplane.EndpointFallback;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Shared generated POP invocation. Credential purpose belongs to the calling client. */
public final class PopRequest {
  private PopRequest() {}

  public static Map<String, Object> call(
      AccessKeyCredential credential,
      String regionId,
      URI endpoint,
      String action,
      String method,
      String path,
      Map<String, String> query,
      Map<String, Object> body,
      boolean form,
      int timeoutMillis,
      boolean allowFallback,
      Consumer<URI> discovered,
      Runnable dispatch)
      throws Exception {
    var config =
        new Config()
            .setAccessKeyId(credential.accessKeyId())
            .setAccessKeySecret(credential.accessKeySecret())
            .setSecurityToken(credential.securityToken())
            .setRegionId(regionId);
    if (endpoint != null)
      config.setEndpoint(endpoint.getAuthority()).setProtocol(endpoint.getScheme());
    var client = new com.aliyun.agentcore20260804.Client(config);
    var params =
        new Params()
            .setAction(action)
            .setVersion("2026-08-04")
            .setProtocol("HTTPS")
            .setPathname(path)
            .setMethod(method)
            .setAuthType("AK")
            .setStyle("ROA")
            .setReqBodyType(form ? "formData" : "json")
            .setBodyType("json");
    var request = new OpenApiRequest().setQuery(query).setHeaders(Map.of());
    if (body != null) request.setBody(form ? Map.of("body", Json.write(body)) : body);
    var options =
        new RuntimeOptions()
            .setAutoretry(false)
            .setConnectTimeout(timeoutMillis)
            .setReadTimeout(timeoutMillis);
    var log = org.slf4j.LoggerFactory.getLogger(PopRequest.class);
    log.debug("agentcore.pop.request operation={} host={} path={}", action, client._endpoint, path);
    try {
      dispatch.run();
      try {
        return responseBody(client.callApi(params, request, options), action);
      } catch (Exception first) {
        URI fallback =
            EndpointFallback.controlPlane(
                URI.create("https://" + client._endpoint), regionId, !allowFallback, action, first);
        if (fallback == null) throw first;
        log.warn(
            "agentcore.control_plane.endpoint.fallback operation={} host={}",
            action,
            fallback.getHost());
        client._endpoint = fallback.getAuthority();
        var result = responseBody(client.callApi(params, request, options), action);
        discovered.accept(fallback);
        return result;
      }
    } catch (com.aliyun.tea.TeaException error) {
      Map<String, Object> data = error.getData();
      Object rawStatus =
          data == null ? null : data.getOrDefault("statusCode", data.get("StatusCode"));
      Integer status = rawStatus instanceof Number n ? n.intValue() : null;
      String id = data == null ? null : text(data.getOrDefault("RequestId", data.get("requestId")));
      String message =
          data == null ? null : text(data.getOrDefault("Message", data.get("message")));
      var failure = new AgentCoreException(action, status, id, error.getCode(), message, error);
      log.warn(
          "agentcore.pop.failed operation={} host={} status={} code={} requestId={}",
          action,
          client._endpoint,
          status,
          error.getCode(),
          id);
      throw failure;
    } catch (AgentCoreException error) {
      throw error;
    } catch (Exception error) {
      throw new AgentCoreException(action, null, null, null, error);
    }
  }

  private static Map<String, Object> responseBody(Map<String, ?> response, String action) {
    String requestId = null;
    if (response.get("headers") instanceof Map<?, ?> headers) {
      for (var entry : headers.entrySet())
        if ("x-acs-request-id".equalsIgnoreCase(String.valueOf(entry.getKey()))
            && entry.getValue() instanceof String id) requestId = id;
    }
    if (!(response.get("body") instanceof Map<?, ?>))
      throw new AgentCoreException(
          action, 200, requestId, "InvalidResponse", "Expected a response body object", null);
    var body = new LinkedHashMap<>(Json.object(response.get("body")));
    if (body.get("requestId") == null && body.get("RequestId") == null && requestId != null)
      body.put("requestId", requestId);
    return body;
  }

  private static String text(Object value) {
    return value instanceof String s ? s : null;
  }
}
