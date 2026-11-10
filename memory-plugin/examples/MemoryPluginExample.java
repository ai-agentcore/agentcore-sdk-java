import io.agentcore.memory.plugin.MemoryPlugin;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Explicit lifecycle wiring; callers supply authenticated identity and durable source records. */
public final class MemoryPluginExample {
  public record TurnResult(
      MemoryPlugin.RecallResult recalled, List<MemoryPlugin.CaptureReceipt> receipts) {}

  public static CompletableFuture<TurnResult> processTurn(
      MemoryPlugin plugin,
      MemoryPlugin.Binding binding,
      String sessionId,
      String query,
      List<MemoryPlugin.SourceRecord> persistedRecords) {
    var session = plugin.session(binding, sessionId);
    return session
        .recall(query)
        .thenCompose(
            recalled ->
                // Supply recalled.context() to the business framework as reference data.
                session
                    .capture(persistedRecords)
                    .thenApply(receipts -> new TurnResult(recalled, receipts)));
  }
}
