package io.github.aililuola.mathproofmesh.provider;

import java.util.Objects;
import java.util.regex.Pattern;

public record ProviderCallPlan(
    String runId,
    String callId,
    String idempotencyKey,
    String agentId,
    String provider,
    String model,
    String stage,
    String requestHash,
    String requestArtifactHash) {
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  public ProviderCallPlan {
    runId = requireText(runId, "runId");
    callId = requireText(callId, "callId");
    idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
    agentId = requireText(agentId, "agentId");
    provider = requireText(provider, "provider");
    model = requireText(model, "model");
    stage = requireText(stage, "stage");
    requestHash = requireHash(requestHash, "requestHash");
    requestArtifactHash =
        requireHash(requestArtifactHash, "requestArtifactHash");
  }

  private static String requireText(String value, String label) {
    Objects.requireNonNull(value, label);
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return normalized;
  }

  private static String requireHash(String value, String label) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " must be a lowercase SHA-256");
    }
    return value;
  }
}
