package io.agentcore.langchain4j;

import dev.langchain4j.http.client.*;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import io.agentcore.model.ModelClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Supply actual credentials at transport time, replacing native provider placeholder headers. */
final class ModelHttpClientBuilder implements HttpClientBuilder {
    private final HttpClientBuilder delegate = HttpClientBuilderLoader.loadHttpClientBuilder();
    private final ModelClient model;
    ModelHttpClientBuilder(ModelClient model) { this.model = model; }
    @Override public Duration connectTimeout() { return delegate.connectTimeout(); }
    @Override public HttpClientBuilder connectTimeout(Duration value) { delegate.connectTimeout(value); return this; }
    @Override public Duration readTimeout() { return delegate.readTimeout(); }
    @Override public HttpClientBuilder readTimeout(Duration value) { delegate.readTimeout(value); return this; }
    @Override public HttpClient build() {
        var client = delegate.build();
        return new HttpClient() {
            @Override public SuccessfulHttpResponse execute(HttpRequest request) { return client.execute(authenticate(request)); }
            @Override public CompletableFuture<SuccessfulHttpResponse> executeAsync(HttpRequest request) {
                return model.headers().map(values -> authenticate(request, values)).toFuture().thenCompose(client::executeAsync);
            }
            @Override public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
                client.execute(authenticate(request), parser, listener);
            }
        };
    }
    private HttpRequest authenticate(HttpRequest request) { return authenticate(request, model.headers().block()); }
    private HttpRequest authenticate(HttpRequest request, java.util.Map<String, String> credentials) {
        var headers = new LinkedHashMap<String, List<String>>();
        request.headers().forEach((name, values) -> { if (!name.equalsIgnoreCase("Authorization") && !name.equalsIgnoreCase("x-api-key")) headers.put(name.toLowerCase(java.util.Locale.ROOT), values); });
        credentials.forEach((name, value) -> headers.put(name.toLowerCase(java.util.Locale.ROOT), List.of(value)));
        return HttpRequest.builder().url(request.url()).method(request.method()).headers(headers).body(request.body())
            .formDataFields(request.formDataFields()).formDataFiles(request.formDataFiles()).build();
    }
}
