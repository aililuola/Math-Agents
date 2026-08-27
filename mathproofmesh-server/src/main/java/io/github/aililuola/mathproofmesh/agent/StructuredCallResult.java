package io.github.aililuola.mathproofmesh.agent;

import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import java.util.List;
import java.util.Objects;

public record StructuredCallResult<T>(
    T value,
    String runId,
    String callId,
    String agentId,
    String provider,
    String model,
    String promptArtifactRef,
    String responseArtifactRef,
    UsageRecord usage,
    boolean repaired,
    List<String> attemptedAgents) {

  public StructuredCallResult {
    value = Objects.requireNonNull(value, "value");
    runId = Objects.requireNonNull(runId, "runId");
    callId = Objects.requireNonNull(callId, "callId");
    agentId = Objects.requireNonNull(agentId, "agentId");
    provider = Objects.requireNonNull(provider, "provider");
    model = Objects.requireNonNull(model, "model");
    promptArtifactRef = Objects.requireNonNull(promptArtifactRef, "promptArtifactRef");
    responseArtifactRef =
        Objects.requireNonNull(responseArtifactRef, "responseArtifactRef");
    usage = Objects.requireNonNull(usage, "usage");
    attemptedAgents = List.copyOf(attemptedAgents);
  }
}
