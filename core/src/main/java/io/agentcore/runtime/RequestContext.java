package io.agentcore.runtime;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import reactor.util.context.ContextView;

/** Immutable invocation metadata, propagated through Reactor Context. */
public final class RequestContext {
    private final Map<String, String> headers;
    private final java.util.List<java.util.function.Supplier<reactor.core.publisher.Mono<Void>>> cleanups = new java.util.ArrayList<>();
    public Map<String, String> headers() { return headers; }
    public synchronized void onClose(java.util.function.Supplier<reactor.core.publisher.Mono<Void>> cleanup) { cleanups.add(cleanup); }
    public reactor.core.publisher.Mono<Void> closeAsync() {
        return reactor.core.publisher.Mono.defer(() -> {
            java.util.List<java.util.function.Supplier<reactor.core.publisher.Mono<Void>>> pending;
            synchronized (this) { pending = java.util.List.copyOf(cleanups); cleanups.clear(); }
            return reactor.core.publisher.Mono.whenDelayError(pending.stream()
                .map(cleanup -> reactor.core.publisher.Mono.defer(cleanup)).toList());
        });
    }
    public static final String ACCESSOR_WAT = "x-agentcore-accessor-wat";
    public static final String KEY = RequestContext.class.getName();
    public RequestContext(Map<String, String> headers) {
        this.headers = Map.copyOf(headers.entrySet().stream().collect(Collectors.toMap(
            entry -> entry.getKey().toLowerCase(Locale.ROOT), Map.Entry::getValue)));
    }
    public static String accessorWat(ContextView context) {
        RequestContext request = context.getOrDefault(RequestContext.class, null);
        return request == null ? null : request.headers().get(ACCESSOR_WAT);
    }
    public static Map<String, Object> toolContext(ContextView context) {
        RequestContext request = context.getOrDefault(RequestContext.class, null);
        return request == null ? Map.of() : Map.of(KEY, request);
    }
    @Override public String toString() { return "RequestContext(headers=<redacted>)"; }
}
