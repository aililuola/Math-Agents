package io.github.aililuola.mathproofmesh.api;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;

/** Scoped provider-to-trace association; the binding itself contains no reasoning text. */
public record ReasoningTraceBinding(
    ReasoningTraceStore store,
    String taskId,
    String agentId,
    String stage,
    String providerCallId) {
  private static final InheritableThreadLocal<ReasoningTraceBinding> CURRENT =
      new InheritableThreadLocal<>();

  public ReasoningTraceBinding {
    store = Objects.requireNonNull(store, "store");
    taskId = ActivitySanitizer.identifier(taskId, 240);
    agentId = ActivitySanitizer.identifier(agentId, 160);
    stage = ActivitySanitizer.identifier(stage, 160);
    providerCallId = ActivitySanitizer.identifier(providerCallId, 160);
    if (providerCallId.isBlank()) {
      throw new IllegalArgumentException("providerCallId is required");
    }
  }

  /** Compatibility constructor for callers that do not own a provider-call record. */
  public ReasoningTraceBinding(
      ReasoningTraceStore store, String taskId, String agentId, String stage) {
    this(store, taskId, agentId, stage, "unbound-provider-call");
  }

  public Scope bind() {
    ReasoningTraceBinding prior = CURRENT.get();
    CURRENT.set(this);
    return () -> {
      if (prior == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(prior);
      }
    };
  }

  @Override
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "The scoped binding intentionally delegates to the shared append-only trace store.")
  public ReasoningTraceStore store() {
    return store;
  }

  public static Optional<ReasoningTraceBinding> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  public static String agentTaskId(String stage, String agentId) {
    return ActivitySanitizer.identifier("agent:" + stage + ":" + agentId, 240);
  }

  @FunctionalInterface
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
