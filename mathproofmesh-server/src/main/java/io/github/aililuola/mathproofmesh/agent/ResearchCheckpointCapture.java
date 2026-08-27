package io.github.aililuola.mathproofmesh.agent;

import io.github.aililuola.mathproofmesh.contract.ResearchCheckpointFrame;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingUpdateBatch;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointTraceSpan;
import java.util.List;

/** One synchronously delivered capture, suitable for commit before provider-call application. */
public record ResearchCheckpointCapture(
    String providerCallId,
    String responseArtifactRef,
    String reasoningTraceCallId,
    String reasoningTraceTaskId,
    String reasoningTraceSha256,
    long reasoningTraceCharacters,
    List<ResearchCheckpointTraceSpan> traceFrames,
    ResearchCheckpointFrame envelopeFrame,
    ResearchFindingUpdateBatch findingUpdates) {

  public ResearchCheckpointCapture {
    providerCallId = required(providerCallId, "providerCallId");
    responseArtifactRef = required(responseArtifactRef, "responseArtifactRef");
    reasoningTraceCallId = normalize(reasoningTraceCallId);
    reasoningTraceTaskId = normalize(reasoningTraceTaskId);
    reasoningTraceSha256 = normalize(reasoningTraceSha256);
    reasoningTraceCharacters = Math.max(0L, reasoningTraceCharacters);
    traceFrames = traceFrames == null ? List.of() : List.copyOf(traceFrames);
    findingUpdates =
        findingUpdates == null ? ResearchFindingUpdateBatch.empty() : findingUpdates;
  }

  @Override
  public List<ResearchCheckpointTraceSpan> traceFrames() {
    return List.copyOf(traceFrames);
  }

  private static String required(String value, String name) {
    String normalized = normalize(value);
    if (normalized == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return normalized;
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.strip();
    return normalized.isEmpty() ? null : normalized;
  }
}
