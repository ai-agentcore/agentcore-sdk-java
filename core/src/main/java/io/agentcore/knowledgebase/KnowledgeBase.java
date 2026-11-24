package io.agentcore.knowledgebase;

import io.agentcore.AgentCoreException;
import io.agentcore.Json;
import io.agentcore.controlplane.ControlPlane;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/** Retrieval from an existing native AgentCore knowledge base. */
public final class KnowledgeBase {
  /** Null fields inherit the knowledge base configuration. */
  public record SearchOptions(
      String mode, Integer topK, String metadataFilter, Boolean rerank,
      Integer rrfK, String rankAlgorithm, Double vectorWeight, String rerankModel) {
    public SearchOptions(Integer topK) {
      this(null, topK, null, null, null, null, null, null);
    }
  }

  public record Chunk(
      String content, String assetId, String assetName, String fileName,
      Integer chunkSeq, String chunkToken, Double score, Map<String, Double> scores,
      String titlePath, String sourceLocation) {}

  public record SearchResult(List<Chunk> chunks, Long timeSpent, String scoreType) {}

  private final String knowledgeBaseId;
  private final Supplier<Mono<ControlPlane>> provider;

  public KnowledgeBase(String knowledgeBaseId, Supplier<Mono<ControlPlane>> provider) {
    this.knowledgeBaseId = Json.text(knowledgeBaseId, "knowledgeBaseId");
    this.provider = provider;
  }

  public String knowledgeBaseId() { return knowledgeBaseId; }

  public Mono<SearchResult> search(String query) { return search(query, null); }

  public Mono<SearchResult> search(String query, SearchOptions options) {
    var body = new LinkedHashMap<String, Object>();
    body.put("query", Json.text(query, "query"));
    if (options != null) {
      body.put("mode", options.mode()); body.put("topK", options.topK());
      body.put("metadataFilter", options.metadataFilter()); body.put("rerank", options.rerank());
      body.put("rrfK", options.rrfK()); body.put("rankAlgorithm", options.rankAlgorithm());
      body.put("vectorWeight", options.vectorWeight()); body.put("rerankModel", options.rerankModel());
      body.values().removeIf(java.util.Objects::isNull);
    }
    return Mono.defer(provider).flatMap(cp -> cp.request(
        "SearchKnowledgeBase", "POST",
        cp.workspacePath() + "/knowledge-bases/" + ControlPlane.encode(knowledgeBaseId) + "/search",
        Map.of(), body, false, 60_000)).map(KnowledgeBase::result);
  }

  private static SearchResult result(Map<String, Object> response) {
    String requestId = (String) response.get("requestId");
    if (Boolean.FALSE.equals(response.get("success"))) {
      var error = new AgentCoreException("SearchKnowledgeBase",
          response.get("httpStatusCode") instanceof Number n ? n.intValue() : null,
          requestId, (String) response.get("code"), (String) response.get("message"), null);
      org.slf4j.LoggerFactory.getLogger(KnowledgeBase.class).warn("agentcore.knowledge_base.search.failed {}", error.getMessage());
      throw error;
    }
    try {
      var data = Json.object(response.get("data"));
      if (!(data.get("chunks") instanceof List<?> chunks))
        throw new IllegalArgumentException("Missing chunks");
      var hits = chunks.stream().map(item -> {
        var chunk = Json.object(item);
        if (!(chunk.get("content") instanceof String content))
          throw new IllegalArgumentException("Missing content");
        var scores = new LinkedHashMap<String, Double>();
        if (chunk.get("scores") != null)
          Json.object(chunk.get("scores")).forEach((key, value) -> {
            if (value != null) scores.put(key, ((Number) value).doubleValue());
          });
        return new Chunk(content, (String) chunk.get("assetId"), (String) chunk.get("assetName"),
            (String) chunk.get("fileName"),
            chunk.get("chunkSeq") instanceof Number n ? n.intValue() : null,
            (String) chunk.get("chunkToken"),
            chunk.get("score") instanceof Number n ? n.doubleValue() : null,
            Map.copyOf(scores), (String) chunk.get("titlePath"), (String) chunk.get("sourceLocation"));
      }).toList();
      return new SearchResult(hits, data.get("timeSpent") instanceof Number n ? n.longValue() : null,
          (String) data.get("scoreType"));
    } catch (IllegalArgumentException | ClassCastException error) {
      throw new AgentCoreException("SearchKnowledgeBase", 200, requestId, "InvalidResponse",
          "Expected knowledge base search results", error);
    }
  }
}
