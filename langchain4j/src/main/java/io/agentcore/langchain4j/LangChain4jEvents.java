package io.agentcore.langchain4j;

import dev.langchain4j.service.TokenStream;
import dev.langchain4j.model.chat.response.StreamingHandle;
import io.agentcore.event.AgentEvent;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;

/** One TokenStream per subscription; intermediate assistant messages end before tools execute. */
public final class LangChain4jEvents {
    private LangChain4jEvents() {}
    public static Flux<AgentEvent> from(Supplier<TokenStream> source) {
        return from(source, response -> reactor.core.publisher.Mono.empty());
    }
    static Flux<AgentEvent> from(Supplier<TokenStream> source,
            java.util.function.Function<dev.langchain4j.model.chat.response.ChatResponse, reactor.core.publisher.Mono<Void>> completed) {
        return Flux.<AgentEvent>create(sink -> {
            var active = new AtomicReference<StreamingHandle>();
            sink.onCancel(() -> { var handle = active.get(); if (handle != null) handle.cancel(); });
            class Messages {
                String id;
                final java.util.Map<String, String> toolParents = new java.util.concurrent.ConcurrentHashMap<>();
                synchronized String id() { if (id == null) { id = UUID.randomUUID().toString(); sink.next(AgentEvent.textStart(id)); } return id; }
                synchronized void text(String delta) { if (!delta.isEmpty()) sink.next(AgentEvent.text(id(), delta)); }
                synchronized void intermediate(dev.langchain4j.model.chat.response.ChatResponse response) {
                    String parent = id();
                    for (var call : response.aiMessage().toolExecutionRequests()) toolParents.put(call.id(), parent);
                    end();
                }
                synchronized void end() { if (id != null) { sink.next(AgentEvent.textEnd(id)); id = null; } }
            }
            var messages = new Messages();
            source.get().onPartialResponseWithContext((partial, context) -> {
                active.set(context.streamingHandle());
                if (sink.isCancelled()) { context.streamingHandle().cancel(); return; }
                messages.text(partial.text());
            }).onPartialThinkingWithContext((partial, context) -> {
                active.set(context.streamingHandle());
                if (sink.isCancelled()) context.streamingHandle().cancel();
            }).onPartialToolCallWithContext((partial, context) -> {
                active.set(context.streamingHandle());
                if (sink.isCancelled()) context.streamingHandle().cancel();
            }).beforeToolExecution(event -> {
                var call = event.request(); String messageId = messages.toolParents.remove(call.id());
                sink.next(AgentEvent.toolStart(messageId, call.id(), call.name()));
                sink.next(AgentEvent.toolArgs(call.id(), call.arguments())); sink.next(AgentEvent.toolEnd(call.id()));
            }).onToolExecuted(event -> sink.next(AgentEvent.toolResult(event.request().id(), event.result() == null
                    ? io.agentcore.Json.write(event.resultContents()) : event.result())))
                .onIntermediateResponse(messages::intermediate)
                .onCompleteResponse(response -> {
                    messages.end();
                    if (!sink.isCancelled()) sink.onDispose(completed.apply(response).subscribe(ignored -> {}, sink::error, sink::complete));
                }).onError(sink::error).start();
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
}
