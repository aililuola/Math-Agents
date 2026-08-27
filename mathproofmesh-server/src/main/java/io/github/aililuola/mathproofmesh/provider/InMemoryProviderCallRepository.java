package io.github.aililuola.mathproofmesh.provider;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class InMemoryProviderCallRepository
    implements ProviderCallRepository {
  private final Clock clock;
  private final Map<String, ProviderCallRecord> records = new LinkedHashMap<>();
  private final Map<String, String> idempotency = new LinkedHashMap<>();
  private final Map<String, String> applications = new LinkedHashMap<>();

  public InMemoryProviderCallRepository() {
    this(Clock.systemUTC());
  }

  public InMemoryProviderCallRepository(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public synchronized ProviderCallRecord plan(ProviderCallPlan plan) {
    Objects.requireNonNull(plan, "plan");
    String idempotencyKey = key(plan.runId(), plan.idempotencyKey());
    String existingCall = idempotency.get(idempotencyKey);
    if (existingCall != null) {
      ProviderCallRecord existing = records.get(key(plan.runId(), existingCall));
      if (!existing.requestHash().equals(plan.requestHash())) {
        throw new IllegalStateException(
            "idempotency key is already bound to a different request");
      }
      return existing;
    }
    Instant now = clock.instant();
    ProviderCallRecord record =
        new ProviderCallRecord(
            plan.runId(),
            plan.callId(),
            plan.idempotencyKey(),
            plan.agentId(),
            plan.provider(),
            plan.model(),
            plan.stage(),
            plan.requestHash(),
            ProviderCallState.PLANNED,
            0L,
            0L,
            BigDecimal.ZERO,
            0.0d,
            plan.requestArtifactHash(),
            null,
            null,
            0,
            BigDecimal.ZERO,
            null,
            null,
            0L,
            now,
            now);
    records.put(key(plan.runId(), plan.callId()), record);
    idempotency.put(idempotencyKey, plan.callId());
    return record;
  }

  @Override
  public synchronized ProviderCallRecord transition(
      ProviderCallTransition transition) {
    Objects.requireNonNull(transition, "transition");
    String key = key(transition.runId(), transition.callId());
    ProviderCallRecord current = records.get(key);
    if (current == null) {
      throw new IllegalArgumentException("unknown provider call");
    }
    if (current.state() != transition.expected()) {
      throw new IllegalStateException(
          "provider call state conflict: expected "
              + transition.expected()
              + " but was "
              + current.state());
    }
    ProviderCallRecord updated =
        new ProviderCallRecord(
            current.runId(),
            current.callId(),
            current.idempotencyKey(),
            current.agentId(),
            current.provider(),
            current.model(),
            current.stage(),
            current.requestHash(),
            transition.target(),
            transition.inputTokens(),
            transition.outputTokens(),
            transition.costUsd(),
            transition.latencyMs(),
            current.requestArtifactHash(),
            transition.responseArtifactHash(),
            transition.requestId(),
            transition.retryCount(),
            transition.possibleDuplicateCostUsd(),
            transition.ambiguityPayload(),
            current.appliedAt(),
            current.version() + 1L,
            current.createdAt(),
            clock.instant());
    records.put(key, updated);
    return updated;
  }

  @Override
  public synchronized boolean markApplied(
      String runId, String callId, String applicationKey) {
    String recordKey = key(runId, callId);
    ProviderCallRecord current = records.get(recordKey);
    if (current == null || current.state() != ProviderCallState.SUCCEEDED) {
      throw new IllegalStateException(
          "only a succeeded provider call can be applied");
    }
    String unique = key(runId, requireText(applicationKey, "applicationKey"));
    if (applications.containsKey(unique)
        || applications.containsValue(recordKey)) {
      return false;
    }
    applications.put(unique, recordKey);
    Instant now = clock.instant();
    records.put(
        recordKey,
        new ProviderCallRecord(
            current.runId(),
            current.callId(),
            current.idempotencyKey(),
            current.agentId(),
            current.provider(),
            current.model(),
            current.stage(),
            current.requestHash(),
            current.state(),
            current.inputTokens(),
            current.outputTokens(),
            current.costUsd(),
            current.latencyMs(),
            current.requestArtifactHash(),
            current.responseArtifactHash(),
            current.requestId(),
            current.retryCount(),
            current.possibleDuplicateCostUsd(),
            current.ambiguityPayload(),
            now,
            current.version() + 1L,
            current.createdAt(),
            now));
    return true;
  }

  @Override
  public synchronized Optional<ProviderCallRecord> findByIdempotencyKey(
      String runId, String idempotencyKey) {
    String callId = idempotency.get(key(runId, idempotencyKey));
    return callId == null
        ? Optional.empty()
        : Optional.of(records.get(key(runId, callId)));
  }

  @Override
  public synchronized List<ProviderCallRecord> findByRun(String runId) {
    List<ProviderCallRecord> result = new ArrayList<>();
    for (ProviderCallRecord record : records.values()) {
      if (record.runId().equals(runId)) {
        result.add(record);
      }
    }
    result.sort(Comparator.comparing(ProviderCallRecord::createdAt));
    return List.copyOf(result);
  }

  private static String key(String left, String right) {
    return requireText(left, "key part") + "\u0000" + requireText(right, "key part");
  }

  private static String requireText(String value, String label) {
    Objects.requireNonNull(value, label);
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return normalized;
  }
}
