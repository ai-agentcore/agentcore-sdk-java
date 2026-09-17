package io.agentcore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Authenticated requests never follow redirects or retry side effects. */
public final class HttpTransport {
    private static final Logger LOG = LoggerFactory.getLogger(HttpTransport.class);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    public Mono<Map<String, Object>> json(String operation, String method, URI url,
            Supplier<Mono<Map<String, String>>> headers, Object body, Duration timeout) {
        return Mono.defer(headers).flatMap(values -> {
            var request = request(method, url, values, body, timeout);
            return Mono.fromFuture(() -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }).map(response -> {
            check(operation, url, response);
            return response.body().isBlank() ? Map.of() : Json.read(response.body());
        });
    }

    public Flux<Map<String, Object>> sse(String operation, URI url,
            Supplier<Mono<Map<String, String>>> headers, Object body, Duration timeout) {
        // HTTP rejections are JSON bodies, not SSE streams. Read them before closing the response.
        HttpResponse.BodyHandler<Stream<String>> handler = info -> info.statusCode() >= 300
            ? HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofString(java.nio.charset.StandardCharsets.UTF_8), Stream::of)
            : HttpResponse.BodyHandlers.ofLines().apply(info);
        Mono<Stream<String>> resource = Mono.defer(headers).flatMap(values ->
            Mono.fromFuture(() -> client.sendAsync(request("POST", url, values, body, timeout),
                handler))).map(response -> {
                    if (response.statusCode() >= 300) {
                        try (var lines = response.body()) {
                            check(operation, url, response, lines.findFirst().orElse(""));
                        }
                    }
                    return response.body();
                });
        return Flux.usingWhen(resource, lines -> {
            StringBuilder data = new StringBuilder();
            return Flux.fromStream(lines).subscribeOn(Schedulers.boundedElastic())
                .<String>handle((line, sink) -> {
                    if (line.isEmpty() && !data.isEmpty()) {
                        String event = data.toString(); data.setLength(0);
                        if (event.equals("[DONE]")) sink.complete(); else sink.next(event);
                    } else if (line.startsWith("data:")) {
                        if (!data.isEmpty()) data.append('\n');
                        String value = line.substring(5);
                        data.append(value.startsWith(" ") ? value.substring(1) : value);
                    }
                }).map(Json::read).timeout(timeout);
        }, lines -> Mono.fromRunnable(lines::close));
    }

    public Mono<byte[]> download(URI url) {
        if (!("http".equals(url.getScheme()) || "https".equals(url.getScheme())) || url.getUserInfo() != null)
            return Mono.error(new IllegalArgumentException("Invalid download URL"));
        var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30)).GET().build();
        return Mono.usingWhen(Mono.fromFuture(() -> client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())),
            response -> Mono.fromCallable(() -> {
                check("download", url, response);
                byte[] value = response.body().readNBytes(10 * 1024 * 1024 + 1);
                if (value.length > 10 * 1024 * 1024) throw new IllegalArgumentException("Download exceeds 10 MiB");
                return value;
            }).subscribeOn(Schedulers.boundedElastic()).timeout(Duration.ofSeconds(30)),
            response -> Mono.fromRunnable(() -> { try { response.body().close(); } catch (java.io.IOException ignored) { } }));
    }

    public Mono<byte[]> bytes(String operation, URI url, Supplier<Mono<Map<String, String>>> headers) {
        return Mono.defer(headers).flatMap(values -> Mono.fromFuture(() -> client.sendAsync(
            request("GET", url, values, null, Duration.ofSeconds(30)), HttpResponse.BodyHandlers.ofByteArray())))
            .map(response -> { check(operation, url, response); return response.body(); });
    }

    /** A multipart upload does not replay on failure. File publishers stream instead of buffering the file. */
    public Mono<Map<String, Object>> upload(String operation, URI url, Supplier<Mono<Map<String, String>>> headers,
            String filename, String contentType, HttpRequest.BodyPublisher content) {
        if (filename.contains("\r") || filename.contains("\n") || contentType.contains("\r") || contentType.contains("\n"))
            return Mono.error(new IllegalArgumentException("Invalid multipart metadata"));
        return Mono.defer(headers).flatMap(values -> {
            String boundary = "agentcore-" + java.util.UUID.randomUUID();
            var body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString("--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"\r\nContent-Type: " + contentType + "\r\n\r\n"), content,
                HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
            var builder = HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(5)).PUT(body);
            values.forEach(builder::setHeader);
            builder.setHeader("Content-Type", "multipart/form-data; boundary=" + boundary);
            return Mono.fromFuture(() -> client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()));
        }).map(response -> { check(operation, url, response); return Json.read(response.body()); });
    }

    /** The caller owns the destination, normally a temporary file committed only on success. */
    public Mono<Long> downloadTo(URI url, java.nio.file.Path destination) {
        var request = HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(5)).GET().build();
        return Mono.usingWhen(Mono.fromFuture(() -> client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())),
            response -> Mono.fromCallable(() -> {
                check("download", url, response);
                return java.nio.file.Files.copy(response.body(), destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }).subscribeOn(Schedulers.boundedElastic()).timeout(Duration.ofMinutes(5)),
            response -> Mono.fromRunnable(() -> { try { response.body().close(); } catch (java.io.IOException ignored) { } }));
    }

    private HttpRequest request(String method, URI url, Map<String, String> headers, Object body, Duration timeout) {
        var builder = HttpRequest.newBuilder(url).timeout(timeout);
        if (body != null) builder.header("Content-Type", "application/json");
        headers.forEach(builder::setHeader);
        LOG.debug("agentcore.http.request method={} endpoint={}{}", method, url.getAuthority(), url.getPath());
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
    }

    private void check(String operation, URI url, HttpResponse<?> response) {
        check(operation, url, response, response.body() instanceof String text ? text : null);
    }
    private void check(String operation, URI url, HttpResponse<?> response, String text) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) return;
        String id = response.headers().firstValue("x-acs-request-id")
            .or(() -> response.headers().firstValue("x-request-id"))
            .or(() -> response.headers().firstValue("request-id"))
            .or(() -> response.headers().firstValue("x-oss-request-id")).orElse(null);
        String code = null;
        String message = null;
        if (text != null) {
            try {
                var body = Json.read(text);
                var detail = body.get("error") instanceof Map<?, ?> nested ? nested : body;
                Object value = detail.containsKey("code") ? detail.get("code") : detail.get("Code");
                if (value == null) value = detail.get("type");
                if (value instanceof String s) code = s;
                Object description = detail.containsKey("message") ? detail.get("message") : detail.get("Message");
                if (description instanceof String s) message = s;
                Object requestId = body.getOrDefault("requestId", body.get("RequestId"));
                if (id == null && requestId instanceof String s) id = s;
            } catch (IllegalArgumentException ignored) { /* Non-JSON error bodies are not logged. */ }
        }
        var error = new AgentCoreException(operation, response.statusCode(), id, code, message, null);
        LOG.warn("agentcore.http.failed operation={} endpoint={}{} status={} code={} requestId={} message={}",
            operation, url.getAuthority(), url.getPath(), response.statusCode(), code, id, error.serviceMessage());
        throw error;
    }
}
