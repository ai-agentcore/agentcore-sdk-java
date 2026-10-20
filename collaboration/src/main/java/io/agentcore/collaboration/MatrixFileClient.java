package io.agentcore.collaboration;

import io.agentcore.Json;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Matrix room transfer only; credentials and workspace rules are shared with existing collaboration tools. */
final class MatrixFileClient {
    private static final Logger LOG = LoggerFactory.getLogger(MatrixFileClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final Supplier<Mono<Map<String, String>>> environment;
    private final Function<TeamsProvider.Snapshot, Mono<Map<String, String>>> credentials;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final CollaborationFiles files;

    MatrixFileClient(Supplier<Mono<Map<String, String>>> environment,
            Function<TeamsProvider.Snapshot, Mono<Map<String, String>>> credentials,
            CollaborationFiles files) {
        this.environment = environment; this.credentials = credentials; this.files = files;
    }

    Mono<Map<String, Object>> download(TeamsProvider.Snapshot snapshot, CollaborationContext context, String eventId) {
        return event(snapshot, context.roomId(), eventId).flatMap(content -> {
            String media = mediaPath(content.get("url"));
            String filename = filename(content.getOrDefault("filename", content.get("body")));
            Object type = object(content.get("info")).get("mimetype");
            String mime = type instanceof String s && !s.isBlank() ? s : "application/octet-stream";
            return limit(snapshot).flatMap(max -> files.store("attachments/" + UUID.randomUUID() + "/" + filename, false,
                target -> exchange(snapshot, "GET", media, Map.of(), BodyPublishers.noBody(), response ->
                    Mono.fromCallable(() -> {
                        long count = 0;
                        try (var output = Files.newOutputStream(target)) {
                            byte[] buffer = new byte[65536];
                            for (int length; (length = response.body().read(buffer)) != -1;) {
                                count += length;
                                if (count > max) throw tooLarge();
                                output.write(buffer, 0, length);
                            }
                        }
                        return count;
                    }).subscribeOn(Schedulers.boundedElastic())))
                .map(stored -> Map.<String, Object>of("event_id", eventId, "local_path", stored.get("path"),
                    "filename", filename, "content_type", mime, "size", stored.get("bytes"))));
        });
    }

    Mono<Map<String, Object>> send(TeamsProvider.Snapshot snapshot, CollaborationContext context, String localPath, String caption) {
        return limit(snapshot).flatMap(max -> Mono.fromCallable(() -> {
            Path source = files.local(localPath);
            if (!Files.isRegularFile(source)) throw new IllegalArgumentException("local_path must be a regular workspace file");
            long size = Files.size(source);
            if (size > max) throw tooLarge();
            String detected = Files.probeContentType(source);
            String mime = detected == null ? "application/octet-stream" : detected;
            String filename = source.getFileName().toString();
            return json(snapshot, "POST", "/_matrix/media/v3/upload?filename=" + id(filename),
                Map.of("Content-Type", mime), BodyPublishers.ofFile(source)).flatMap(uploaded -> {
                    Object uri = uploaded.get("content_uri");
                    try { mediaPath(uri); } catch (Failure ignored) { return Mono.error(unconfirmed("POST")); }
                    var content = Map.<String, Object>of(
                        "msgtype", List.of("image/png", "image/jpeg", "image/gif", "image/webp").contains(mime) ? "m.image" : "m.file",
                        "body", caption == null || caption.isBlank() ? filename : caption, "filename", filename, "url", uri,
                        "info", Map.of("size", size, "mimetype", mime),
                        "m.relates_to", Map.of("m.in_reply_to", Map.of("event_id", context.eventId())));
                    // A send retry keeps the same transaction, body and token. Never repeat the upload.
                    return json(snapshot, "PUT", "/_matrix/client/v3/rooms/" + id(context.roomId())
                        + "/send/m.room.message/" + UUID.randomUUID(), Map.of("Content-Type", "application/json"),
                        BodyPublishers.ofString(Json.write(content))).map(sent -> {
                            if (!(sent.get("event_id") instanceof String eventId) || eventId.isBlank()) throw unconfirmed("PUT");
                            LOG.info("agentcore.room_file.sent roomId={} requestEventId={} eventId={} bytes={}",
                                context.roomId(), context.eventId(), eventId, size);
                            return Map.<String, Object>of("event_id", eventId, "filename", filename, "status", "sent");
                        });
                });
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(value -> value));
    }

    private Mono<Map<String, Object>> event(TeamsProvider.Snapshot snapshot, String room, String eventId) {
        return get(snapshot, eventPath(room, eventId)).flatMap(event -> {
            var content = content(event, room);
            var relation = object(content.get("m.relates_to"));
            Mono<Map<String, Object>> selected = Mono.just(content);
            if ("m.replace".equals(relation.get("rel_type"))) {
                if (!(relation.get("event_id") instanceof String originalId) || originalId.isBlank()) throw unsupported();
                selected = get(snapshot, eventPath(room, originalId)).map(original -> {
                    var originalContent = content(original, room);
                    Object kind = object(originalContent.get("m.relates_to")).get("rel_type");
                    if (event.get("sender") == null || !event.get("sender").equals(original.get("sender"))
                        || "m.replace".equals(kind) || "m.thread".equals(kind)) throw unsupported();
                    return object(content.get("m.new_content"));
                });
            }
            return selected.map(value -> {
                if (!("m.file".equals(value.get("msgtype")) || "m.image".equals(value.get("msgtype")))
                    || value.containsKey("file") || "m.thread".equals(object(value.get("m.relates_to")).get("rel_type"))) throw unsupported();
                return value;
            });
        });
    }
    private Mono<Long> limit(TeamsProvider.Snapshot snapshot) {
        return get(snapshot, "/_matrix/client/v1/media/config").map(config -> {
            Object value = config.get("m.upload.size");
            if (!(value instanceof Number n) || n.longValue() <= 0 || n.doubleValue() != n.longValue())
                throw new Failure("ROOM_FILE_UNAVAILABLE", "Matrix media limit is unavailable.", true);
            return n.longValue();
        });
    }
    private Mono<Map<String, Object>> get(TeamsProvider.Snapshot snapshot, String path) {
        return json(snapshot, "GET", path, Map.of(), BodyPublishers.noBody());
    }
    private Mono<Map<String, Object>> json(TeamsProvider.Snapshot snapshot, String method, String path,
            Map<String, String> headers, BodyPublisher body) {
        return exchange(snapshot, method, path, headers, body, response -> Mono.fromCallable(() -> {
            try { return Json.read(new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException error) { throw unconfirmed(method); }
        }).subscribeOn(Schedulers.boundedElastic()));
    }
    private <T> Mono<T> exchange(TeamsProvider.Snapshot snapshot, String method, String path,
            Map<String, String> extra, BodyPublisher body, Function<HttpResponse<InputStream>, Mono<T>> consume) {
        return Mono.defer(environment).flatMap(env -> {
            String base = env.getOrDefault("AGENTCORE_MATRIX_URL", env.get("AGENTTEAMS_MATRIX_URL"));
            if (base == null) return Mono.error(new Failure("CONFIG_INVALID", "Matrix endpoint is unavailable.", true));
            final URI endpoint;
            try { endpoint = URI.create(base); }
            catch (IllegalArgumentException invalid) { return Mono.error(new Failure("CONFIG_INVALID", "Matrix endpoint is unavailable.", true)); }
            if (!List.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null)
                return Mono.error(new Failure("CONFIG_INVALID", "Matrix endpoint is unavailable.", true));
            URI uri = URI.create(base.replaceAll("/+$", "") + path);
            return credentials.apply(snapshot)
                .onErrorMap(error -> new Failure("CONFIG_INVALID", "Matrix identity is unavailable.", true))
                .flatMap(auth -> {
                    var headers = new java.util.LinkedHashMap<>(auth); headers.putAll(extra);
                    return attempt(snapshot, method, uri, headers, body, consume, 0, false);
                });
        }).onErrorMap(error -> error instanceof java.io.IOException || error instanceof java.util.concurrent.TimeoutException,
            error -> unconfirmed(method));
    }
    private <T> Mono<T> attempt(TeamsProvider.Snapshot snapshot, String method, URI uri, Map<String, String> headers,
            BodyPublisher body, Function<HttpResponse<InputStream>, Mono<T>> consume, int attempt, boolean uncertain) {
        Mono<HttpResponse<InputStream>> response = Mono.defer(() -> {
            var builder = HttpRequest.newBuilder(uri).timeout(TIMEOUT).method(method, body);
            if ("http".equals(uri.getScheme())) builder.version(HttpClient.Version.HTTP_1_1);
            headers.forEach(builder::setHeader);
            return Mono.fromFuture(() -> http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream()));
        })
            .onErrorResume(error -> {
                if ("PUT".equals(method) && attempt == 0) {
                    LOG.info("agentcore.room_file.send_retry retries=1 sameTransaction=true");
                    // Handle the retry below so status and body errors retain the uncertain state.
                    return Mono.error(new RetrySend());
                }
                return Mono.error(unconfirmed(method));
            });
        return Mono.usingWhen(response, value -> {
            int status = value.statusCode();
            if (status == 401 && attempt == 0 && !uncertain) return Mono.error(new RefreshToken());
            if (status < 200 || status >= 300) {
                LOG.warn("agentcore.room_file.http_failed method={} status={} retries={} requestId={}",
                    method, status, attempt, value.headers().firstValue("x-request-id").orElse(null));
                if (uncertain) return Mono.error(unconfirmed(method));
                String kind = switch (status) {
                    case 401, 403 -> "ROOM_FILE_UNAUTHORIZED";
                    case 404 -> "ROOM_FILE_NOT_FOUND";
                    case 413 -> "FILE_TOO_LARGE";
                    default -> "ROOM_FILE_UNAVAILABLE";
                };
                return Mono.error(new Failure(kind, "Matrix request failed (HTTP " + status + ").",
                    method.equals("GET") && status >= 500));
            }
            return consume.apply(value).timeout(TIMEOUT);
        }, value -> Mono.fromRunnable(() -> {
            try { value.body().close(); } catch (java.io.IOException ignored) { }
        }).subscribeOn(Schedulers.boundedElastic()))
            .onErrorResume(RefreshToken.class, error -> credentials.apply(snapshot)
                .onErrorMap(failure -> new Failure("CONFIG_INVALID", "Matrix identity is unavailable.", true)).flatMap(fresh -> {
                var refreshed = new java.util.LinkedHashMap<>(headers); refreshed.putAll(fresh);
                return attempt(snapshot, method, uri, refreshed, body, consume, 1, false);
            }))
            .onErrorResume(RetrySend.class, error -> attempt(snapshot, method, uri, headers, body, consume, 1, true));
    }
    private static final class RetrySend extends RuntimeException { }
    private static final class RefreshToken extends RuntimeException { }
    static final class Failure extends RuntimeException {
        final String code;
        final boolean retryable;
        Failure(String code, String message, boolean retryable) {
            super(message); this.code = "COLLABORATION_" + code; this.retryable = retryable;
        }
    }
    private static Failure tooLarge() { return new Failure("FILE_TOO_LARGE", "File exceeds the Matrix media limit.", false); }
    private static Failure unsupported() { return new Failure("ROOM_FILE_UNSUPPORTED", "This event is not a supported unencrypted room attachment.", false); }
    private static Failure unconfirmed(String method) {
        return method.equals("GET") ? new Failure("ROOM_FILE_UNAVAILABLE", "Matrix media service is unavailable.", true)
            : new Failure("ROOM_FILE_SEND_UNKNOWN", "File delivery could not be confirmed; do not resend blindly.", false);
    }
    private static Map<String, Object> object(Object value) { return value instanceof Map<?, ?> ? Json.object(value) : Map.of(); }
    private static Map<String, Object> content(Map<String, Object> event, String room) {
        if (!room.equals(event.get("room_id")) || !"m.room.message".equals(event.get("type"))) throw unsupported();
        return object(event.get("content"));
    }
    private static String id(String value) { return TaskServiceClient.id(value); }
    private static String eventPath(String room, String event) { return "/_matrix/client/v3/rooms/" + id(room) + "/event/" + id(event); }
    private static String mediaPath(Object value) {
        if (!(value instanceof String uri) || !uri.matches("mxc://[^/\\s]+/[^/?#\\s]+")) throw unsupported();
        String[] parts = uri.substring(6).split("/", 2);
        return "/_matrix/client/v1/media/download/" + id(parts[0]) + "/" + id(parts[1]);
    }
    private static String filename(Object value) {
        String name = String.valueOf(value == null ? "attachment" : value).replace('\\', '/').replaceAll("^.*/", "")
            .replaceAll("[\\x00-\\x1f\\x7f<>:\"|?*]", "_").replaceAll("^[ .]+|[ .]+$", "");
        if (name.isBlank()) return "attachment";
        if (name.matches("(?i)^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\\..*)?$")) name = "_" + name;
        return name.substring(0, Math.min(120, name.length()));
    }
}
