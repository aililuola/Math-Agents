package io.github.aililuola.mathproofmesh.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record ProviderCallRecord(
    String runId,
    String callId,
    String idempotencyKey,
    String agentId,
    String provider,
    String model,
    String stage,
    String requestHash,
    ProviderCallState state,
    long inputTokens,
    long outputTokens,
    BigDecimal costUsd,
    double latencyMs,
    String requestArtifactHash,
    String responseArtifactHash,
    String requestId,
    int retryCount,
    BigDecimal possibleDuplicateCostUsd,
    JsonNode ambiguityPayload,
    Instant appliedAt,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public ProviderCallRecord {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(callId, "callId");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(agentId, "agentId");
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(requestHash, "requestHash");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(costUsd, "costUsd");
    Objects.requireNonNull(possibleDuplicateCostUsd, "possibleDuplicateCostUsd");
    ambiguityPayload =
        ambiguityPayload == null
            ? JsonNodeFactory.instance.objectNode()
            : ambiguityPayload.deepCopy();
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  @Override
  public JsonNode ambiguityPayload() {
    return ambiguityPayload.deepCopy();
  }

  public long totalTokens() {
    return Math.addExact(inputTokens, outputTokens);
  }
}
