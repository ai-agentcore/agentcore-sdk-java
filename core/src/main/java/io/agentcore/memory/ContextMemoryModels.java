package io.agentcore.memory;

import io.agentcore.Json;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Native Context API values. A null optional field means absent from the service. */
public final class ContextMemoryModels {
  private ContextMemoryModels() {}

  public enum MemoryType {
    SEMANTIC("semantic"),
    SKILL("skill"),
    USER_PROFILE("user_profile"),
    SESSION_SUMMARY("session_summary");
    private final String value;

    MemoryType(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }

    public static MemoryType fromValue(String value) {
      for (var type : values()) if (type.value.equals(value)) return type;
      throw new IllegalArgumentException("Unsupported memory type");
    }
  }

  public record CallOptions(Duration timeout) {
    public CallOptions {
      if (timeout != null && (timeout.toMillis() < 1 || timeout.toMillis() > Integer.MAX_VALUE))
        throw new IllegalArgumentException("timeout must be positive and fit milliseconds");
    }

    public static CallOptions defaults() {
      return new CallOptions(null);
    }
  }

  public record SearchOptions(
      MemoryScope scope,
      List<MemoryType> types,
      Integer topK,
      Map<String, String> metadata,
      Boolean enableRerank,
      Double minSimilarity,
      Double minScore) {
    public SearchOptions {
      if (types != null) types = types.stream().distinct().toList();
      if (types != null) types.forEach(java.util.Objects::requireNonNull);
      if (topK != null && (topK < 1 || topK > 200))
        throw new IllegalArgumentException("topK must be between 1 and 200");
      if (metadata != null) metadata = Map.copyOf(metadata);
      if (minSimilarity != null && !Double.isFinite(minSimilarity)
          || minScore != null && !Double.isFinite(minScore))
        throw new IllegalArgumentException("Search thresholds must be finite");
    }

    public static SearchOptions defaults() {
      return new SearchOptions(null, null, null, null, null, null, null);
    }
  }

  public record ListEventsOptions(MemoryScope scope, String nextToken, Integer maxResults) {
    public ListEventsOptions {
      if (nextToken != null) Json.text(nextToken, "nextToken");
      if (maxResults != null && (maxResults < 1 || maxResults > 100))
        throw new IllegalArgumentException("maxResults must be between 1 and 100");
    }

    public static ListEventsOptions defaults() {
      return new ListEventsOptions(null, null, null);
    }
  }

  public record MemoryContent(String text) {
    public MemoryContent {
      java.util.Objects.requireNonNull(text);
    }
  }

  public record ContextMemory(
      String memoryId,
      MemoryContent content,
      MemoryScope scope,
      Map<String, String> metadata,
      MemoryType type,
      String source,
      Boolean valid,
      List<String> sourceEventIds,
      Object version,
      String createdAt,
      String updatedAt) {
    public ContextMemory {
      Json.text(memoryId, "memoryId");
      java.util.Objects.requireNonNull(content);
      java.util.Objects.requireNonNull(scope);
      if (metadata != null) metadata = Map.copyOf(metadata);
      if (sourceEventIds != null) {
        sourceEventIds = List.copyOf(sourceEventIds);
        sourceEventIds.forEach(id -> Json.text(id, "sourceEventId"));
      }
      if (version != null
          && !(version instanceof String)
          && !(version instanceof Integer)
          && !(version instanceof Long)
          && !(version instanceof java.math.BigInteger))
        throw new IllegalArgumentException("Invalid memory version");
    }
  }

  public record ContextMemorySearchHit(ContextMemory memory, double score, double similarity) {
    public ContextMemorySearchHit {
      java.util.Objects.requireNonNull(memory);
      if (!Double.isFinite(score) || !Double.isFinite(similarity))
        throw new IllegalArgumentException("Invalid search score");
    }
  }

  public record ContextMemorySearchResult(List<ContextMemorySearchHit> memories, String requestId) {
    public ContextMemorySearchResult {
      memories = List.copyOf(memories);
    }
  }

  public sealed interface EventReceipt permits StoredEvent, OtherEventReceipt {
    int inputIndex();

    String status();

    String eventId();

    String code();

    String message();
  }

  public record StoredEvent(int inputIndex, String eventId, String code, String message)
      implements EventReceipt {
    public StoredEvent {
      if (inputIndex < 0) throw new IllegalArgumentException("Invalid inputIndex");
      Json.text(eventId, "eventId");
    }

    @Override
    public String status() {
      return "STORED";
    }
  }

  public record OtherEventReceipt(
      int inputIndex, String status, String eventId, String code, String message)
      implements EventReceipt {
    public OtherEventReceipt {
      if (inputIndex < 0 || Json.text(status, "status").equals("STORED"))
        throw new IllegalArgumentException("Invalid receipt");
      if (eventId != null) Json.text(eventId, "eventId");
    }
  }

  public record CreateEventsResult(List<EventReceipt> results, String requestId) {
    public CreateEventsResult {
      results = List.copyOf(results);
    }
  }

  /** Preserves the native event envelope without assuming its deployed row schema. */
  public record ContextEventRecord(Map<String, Object> envelope) {
    public ContextEventRecord {
      envelope = ContextEvent.immutableObject(envelope);
    }
  }

  public record EventPage(
      List<ContextEventRecord> items,
      String nextToken,
      Integer maxResults,
      Long totalCount,
      String requestId) {
    public EventPage {
      items = List.copyOf(items);
    }
  }
}
