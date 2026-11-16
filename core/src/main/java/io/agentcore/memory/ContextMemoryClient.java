package io.agentcore.memory;

import io.agentcore.AgentCoreException;
import io.agentcore.Json;
import io.agentcore.auth.CredentialProvider;
import io.agentcore.controlplane.ControlPlane;
import io.agentcore.internal.PopRequest;
import io.agentcore.memory.ContextMemoryModels.CallOptions;
import io.agentcore.memory.ContextMemoryModels.ContextEventRecord;
import io.agentcore.memory.ContextMemoryModels.ContextMemory;
import io.agentcore.memory.ContextMemoryModels.ContextMemorySearchHit;
import io.agentcore.memory.ContextMemoryModels.ContextMemorySearchResult;
import io.agentcore.memory.ContextMemoryModels.CreateEventsResult;
import io.agentcore.memory.ContextMemoryModels.EventPage;
import io.agentcore.memory.ContextMemoryModels.EventReceipt;
import io.agentcore.memory.ContextMemoryModels.ListEventsOptions;
import io.agentcore.memory.ContextMemoryModels.MemoryContent;
import io.agentcore.memory.ContextMemoryModels.MemoryType;
import io.agentcore.memory.ContextMemoryModels.OtherEventReceipt;
import io.agentcore.memory.ContextMemoryModels.SearchOptions;
import io.agentcore.memory.ContextMemoryModels.StoredEvent;
import io.agentcore.runtime.AgentConfig;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Three native Context operations, with context-scoped credentials and no automatic retries. */
public final class ContextMemoryClient {
  public record Options(
      String workspaceId,
      String regionId,
      String memoryStoreName,
      URI endpoint,
      CredentialProvider credentials) {
    public Options {
      Json.text(workspaceId, "workspaceId");
      Json.text(regionId, "regionId");
      Json.text(memoryStoreName, "memoryStoreName");
      java.util.Objects.requireNonNull(credentials);
      if (endpoint != null) endpoint = ContextMemoryClient.endpoint(endpoint);
    }

    public Options(
        String workspaceId,
        String regionId,
        String memoryStoreName,
        CredentialProvider credentials) {
      this(workspaceId, regionId, memoryStoreName, null, credentials);
    }
  }

  private final Mono<Options> configuration;

  private enum Dispatch {
    NEW,
    DISPATCHED,
    CANCELLED
  }

  private final String memoryStoreName;

  private ContextMemoryClient(String memoryStoreName, Mono<Options> configuration) {
    this.memoryStoreName = Json.text(memoryStoreName, "memoryStoreName");
    this.configuration =
        configuration.map(
            options -> {
              if (!memoryStoreName.equals(options.memoryStoreName()))
                throw new IllegalArgumentException("Memory store configuration mismatch");
              return options;
            });
  }

  public static ContextMemoryClient fromCredentials(Options options) {
    java.util.Objects.requireNonNull(options);
    return new ContextMemoryClient(options.memoryStoreName(), Mono.just(options));
  }

  /** Lazily resolves public connection options, for runtimes discovered on first subscription. */
  public static ContextMemoryClient deferred(
      String memoryStoreName, Supplier<Mono<Options>> options) {
    return new ContextMemoryClient(
        memoryStoreName, Mono.defer(java.util.Objects.requireNonNull(options)));
  }

  public String memoryStoreName() {
    return memoryStoreName;
  }

  public Mono<CreateEventsResult> createEvents(
      MemoryScope scope, List<ContextEvent> events, CallOptions options) {
    var snapshot = List.copyOf(events);
    if (snapshot.isEmpty() || snapshot.size() > 100)
      throw new IllegalArgumentException("events must contain 1 to 100 entries");
    var body = new LinkedHashMap<String, Object>();
    if (scope != null) body.put("scope", scope.toMap());
    body.put("events", snapshot.stream().map(ContextEvent::toMap).toList());
    if (Json.write(body).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024)
      throw new IllegalArgumentException("CreateEvents request is too large");
    return request(
        "CreateEvents",
        "POST",
        "events",
        Map.of(),
        body,
        options,
        response -> {
          var data = data(response);
          var rows = list(data.get("results"), "results");
          if (rows.size() != snapshot.size())
            throw new IllegalArgumentException("Incomplete event receipts");
          var seen = new HashSet<Integer>();
          var results = new ArrayList<EventReceipt>();
          for (var value : rows) {
            var row = Json.object(value);
            int index = integer(row.get("inputIndex"), "inputIndex");
            if (index < 0 || index >= snapshot.size() || !seen.add(index))
              throw new IllegalArgumentException("Invalid receipt inputIndex");
            String status = Json.text(row.get("status"), "status");
            String eventId = optionalText(row.get("eventId"), "eventId");
            String code = optionalString(row.get("code"), "code");
            String message = optionalString(row.get("message"), "message");
            results.add(
                status.equals("STORED")
                    ? new StoredEvent(index, eventId, code, message)
                    : new OtherEventReceipt(index, status, eventId, code, message));
          }
          return new CreateEventsResult(results, requestId(response));
        });
  }

  public Mono<EventPage> listEvents(ListEventsOptions options, CallOptions callOptions) {
    var selected = options == null ? ListEventsOptions.defaults() : options;
    var query = new LinkedHashMap<String, String>();
    if (selected.scope() != null)
      selected.scope().toMap().forEach((key, value) -> query.put(key, (String) value));
    if (selected.nextToken() != null) query.put("nextToken", selected.nextToken());
    if (selected.maxResults() != null) query.put("maxResults", selected.maxResults().toString());
    return request(
        "ListEvents",
        "GET",
        "events",
        query,
        null,
        callOptions,
        response -> {
          var items =
              list(response.get("items"), "items").stream()
                  .map(value -> new ContextEventRecord(Json.object(value)))
                  .toList();
          String token = optionalString(response.get("nextToken"), "nextToken");
          return new EventPage(
              items,
              token,
              response.get("maxResults") == null
                  ? null
                  : integer(response.get("maxResults"), "maxResults"),
              response.get("totalCount") == null
                  ? null
                  : nonnegativeLong(response.get("totalCount"), "totalCount"),
              requestId(response));
        });
  }

  public Mono<ContextMemorySearchResult> searchMemories(
      String query, SearchOptions options, CallOptions callOptions) {
    var selected = options == null ? SearchOptions.defaults() : options;
    var body = new LinkedHashMap<String, Object>();
    body.put("query", Json.text(query, "query"));
    if (selected.scope() != null) body.put("scope", selected.scope().toMap());
    if (selected.types() != null && !selected.types().isEmpty())
      body.put("types", selected.types().stream().map(MemoryType::value).toList());
    if (selected.topK() != null) body.put("topK", selected.topK());
    if (selected.metadata() != null) body.put("metadata", selected.metadata());
    if (selected.enableRerank() != null) body.put("enableRerank", selected.enableRerank());
    if (selected.minSimilarity() != null) body.put("minSimilarity", selected.minSimilarity());
    if (selected.minScore() != null) body.put("minScore", selected.minScore());
    return request(
        "SearchMemories",
        "POST",
        "memories/search",
        Map.of(),
        body,
        callOptions,
        response -> {
          var hits = new ArrayList<ContextMemorySearchHit>();
          for (var value : list(data(response).get("memories"), "memories")) {
            var row = Json.object(value);
            var memory = Json.object(row.get("memory"));
            var scope = parseScope(Json.object(memory.get("scope")));
            if (selected.scope() != null)
              selected
                  .scope()
                  .toMap()
                  .forEach(
                      (key, expected) -> {
                        if (!expected.equals(scope.toMap().get(key)))
                          throw new IllegalArgumentException("Search scope mismatch");
                      });
            var type =
                memory.get("type") == null
                    ? null
                    : MemoryType.fromValue(Json.text(memory.get("type"), "type"));
            if (type != null
                && selected.types() != null
                && !selected.types().isEmpty()
                && !selected.types().contains(type))
              throw new IllegalArgumentException("Search type mismatch");
            var content = Json.object(memory.get("content")).get("text");
            if (!(content instanceof String text))
              throw new IllegalArgumentException("content.text must be a string");
            var result =
                new ContextMemory(
                    Json.text(memory.get("memoryId"), "memoryId"),
                    new MemoryContent(text),
                    scope,
                    stringMap(memory.get("metadata")),
                    type,
                    optionalText(memory.get("source"), "source"),
                    optionalBoolean(memory.get("valid")),
                    strings(memory.get("sourceEventIds")),
                    memory.get("version"),
                    optionalText(memory.get("createdAt"), "createdAt"),
                    optionalText(memory.get("updatedAt"), "updatedAt"));
            hits.add(
                new ContextMemorySearchHit(
                    result,
                    finite(row.get("score"), "score"),
                    finite(row.get("similarity"), "similarity")));
          }
          return new ContextMemorySearchResult(hits, requestId(response));
        });
  }

  private <T> Mono<T> request(
      String action,
      String method,
      String suffix,
      Map<String, String> query,
      Map<String, Object> body,
      CallOptions options,
      Function<Map<String, Object>, T> parse) {
    Duration deadline =
        options == null || options.timeout() == null ? Duration.ofSeconds(30) : options.timeout();
    return Mono.defer(
        () -> {
          var dispatched = new AtomicReference<>(Dispatch.NEW);
          var responseId = new java.util.concurrent.atomic.AtomicReference<String>();
          Mono<T> operation =
              configuration
                  .flatMap(
                      config ->
                          Mono.defer(() -> config.credentials().get("context"))
                              .flatMap(
                                  credential ->
                                      Mono.fromCallable(
                                              () -> {
                                                URI endpoint = config.endpoint();
                                                if (endpoint == null) {
                                                  String projected =
                                                      System.getenv("AGENTCORE_MEMORY_ENDPOINT");
                                                  endpoint =
                                                      projected == null
                                                          ? URI.create(
                                                              "https://agentcore."
                                                                  + config.regionId()
                                                                  + ".aliyuncs.com")
                                                          : endpoint(URI.create(projected));
                                                }
                                                return PopRequest.call(
                                                    credential,
                                                    config.regionId(),
                                                    endpoint,
                                                    action,
                                                    method,
                                                    "/workspaces/"
                                                        + ControlPlane.encode(config.workspaceId())
                                                        + "/memorystores/"
                                                        + ControlPlane.encode(
                                                            config.memoryStoreName())
                                                        + "/"
                                                        + suffix,
                                                    query,
                                                    body,
                                                    body != null,
                                                    (int) deadline.toMillis(),
                                                    false,
                                                    ignored -> {},
                                                    () -> {
                                                      if (!dispatched.compareAndSet(
                                                          Dispatch.NEW, Dispatch.DISPATCHED))
                                                        throw new java.util.concurrent
                                                            .CancellationException(
                                                            "Context request cancelled before"
                                                                + " dispatch");
                                                    });
                                              })
                                          .subscribeOn(Schedulers.boundedElastic())))
                  .map(
                      response -> {
                        responseId.set(requestId(response));
                        if (Boolean.FALSE.equals(response.get("success"))) {
                          int status =
                              response.get("httpStatusCode") instanceof Number n
                                  ? n.intValue()
                                  : 200;
                          var rejected =
                              new Rejected(
                                  action,
                                  status,
                                  responseId.get(),
                                  response.get("code") instanceof String code ? code : null,
                                  response.get("message") instanceof String message
                                      ? message
                                      : null);
                          if (status >= 500)
                            throw new AgentCoreException(
                                action,
                                status,
                                rejected.requestId(),
                                rejected.serviceCode(),
                                rejected.serviceMessage(),
                                rejected);
                          throw rejected;
                        }
                        try {
                          if (!Boolean.TRUE.equals(response.get("success")))
                            throw new IllegalArgumentException("Missing success flag");
                          return parse.apply(response);
                        } catch (IllegalArgumentException error) {
                          throw new AgentCoreException(
                              action,
                              200,
                              responseId.get(),
                              "InvalidResponse",
                              error.getMessage(),
                              error);
                        }
                      });
          return operation
              .doOnCancel(() -> dispatched.compareAndSet(Dispatch.NEW, Dispatch.CANCELLED))
              .timeout(deadline)
              .onErrorMap(
                  error -> {
                    if (error instanceof Rejected) return error;
                    AgentCoreException api =
                        error instanceof AgentCoreException value
                            ? value
                            : new AgentCoreException(action, null, responseId.get(), null, error);
                    if (action.equals("CreateEvents")
                        && dispatched.get() == Dispatch.DISPATCHED
                        && (api.status() == null
                            || api.status() >= 500
                            || "InvalidResponse".equals(api.serviceCode())))
                      return new CreateEventsOutcomeUnknownException(api);
                    return api;
                  });
        });
  }

  private static final class Rejected extends AgentCoreException {
    private Rejected(
        String operation, Integer status, String requestId, String code, String message) {
      super(operation, status, requestId, code, message, null);
    }
  }

  private static URI endpoint(URI value) {
    var uri = AgentConfig.httpUrl(value.toString());
    if (!uri.getPath().isEmpty() && !uri.getPath().equals("/"))
      throw new IllegalArgumentException("Context endpoint must be an origin");
    return uri;
  }

  private static Map<String, Object> data(Map<String, Object> response) {
    return Json.object(response.get("data"));
  }

  private static String requestId(Map<String, Object> response) {
    return optionalText(response.getOrDefault("requestId", response.get("RequestId")), "requestId");
  }

  private static String optionalText(Object value, String field) {
    return value == null ? null : Json.text(value, field);
  }

  private static String optionalString(Object value, String field) {
    if (value == null) return null;
    if (!(value instanceof String text))
      throw new IllegalArgumentException(field + " must be a string");
    return text;
  }

  private static List<?> list(Object value, String field) {
    if (!(value instanceof List<?> list))
      throw new IllegalArgumentException(field + " must be an array");
    return list;
  }

  private static MemoryScope parseScope(Map<String, Object> value) {
    return new MemoryScope(
        optionalText(value.get("userId"), "userId"),
        optionalText(value.get("teamId"), "teamId"),
        optionalText(value.get("agentId"), "agentId"),
        optionalText(value.get("sessionId"), "sessionId"));
  }

  private static int integer(Object value, String field) {
    long number = nonnegativeLong(value, field);
    if (number > Integer.MAX_VALUE) throw new IllegalArgumentException(field + " is too large");
    return (int) number;
  }

  private static long nonnegativeLong(Object value, String field) {
    if (!(value instanceof Byte
            || value instanceof Short
            || value instanceof Integer
            || value instanceof Long)
        || ((Number) value).longValue() < 0)
      throw new IllegalArgumentException(field + " must be a nonnegative integer");
    return ((Number) value).longValue();
  }

  private static double finite(Object value, String field) {
    if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
      throw new IllegalArgumentException(field + " must be finite");
    return number.doubleValue();
  }

  private static Boolean optionalBoolean(Object value) {
    if (value == null) return null;
    if (!(value instanceof Boolean b)) throw new IllegalArgumentException("valid must be boolean");
    return b;
  }

  private static Map<String, String> stringMap(Object value) {
    if (value == null) return null;
    var result = new LinkedHashMap<String, String>();
    Json.object(value)
        .forEach(
            (key, item) -> {
              if (!(item instanceof String text))
                throw new IllegalArgumentException("metadata values must be strings");
              result.put(key, text);
            });
    return result;
  }

  private static List<String> strings(Object value) {
    return value == null
        ? null
        : list(value, "sourceEventIds").stream()
            .map(item -> Json.text(item, "sourceEventId"))
            .toList();
  }
}
