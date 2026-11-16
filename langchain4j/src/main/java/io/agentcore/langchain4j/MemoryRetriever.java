package io.agentcore.langchain4j;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.agentcore.memory.MemoryContext;
import io.agentcore.memory.FrameworkMemory;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Scope is chosen by application code. This is not a chat-history store. */
public final class MemoryRetriever implements ContentRetriever {
    private final FrameworkMemory memory;
    public MemoryRetriever(MemoryContext memory) { this((FrameworkMemory) memory); }
    public MemoryRetriever(FrameworkMemory memory) { this.memory = memory; }
    @Override public List<Content> retrieve(Query query) { return retrieveAsync(query).join(); }
    @Override public CompletableFuture<List<Content>> retrieveAsync(Query query) {
        return memory.recallForTurn(query.text()).map(text -> text.isEmpty() ? List.<Content>of() : List.of(Content.from(text))).toFuture();
    }
}
