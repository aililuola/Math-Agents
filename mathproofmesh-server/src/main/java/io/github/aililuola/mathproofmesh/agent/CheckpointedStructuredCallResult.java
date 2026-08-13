package io.github.aililuola.mathproofmesh.agent;

import io.github.aililuola.mathproofmesh.contract.ResearchCheckpointFrame;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingUpdateBatch;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointTraceSpan;
import java.util.List;
import java.util.Objects;

public record CheckpointedStructuredCallResult<T>(
    StructuredCallResult<T> result,
    String checkpointedProviderCallId,
    List<ResearchCheckpointTraceSpan> traceFrames,
    ResearchCheckpointFrame publicCheckpoint,
    ResearchFindingUpdateBatch findingUpdates) {

  public CheckpointedStructuredCallResult {
    result = Objects.requireNonNull(result, "result");
    checkpointedProviderCallId =
        Objects.requireNonNull(checkpointedProviderCallId, "checkpointedProviderCallId").strip();
    if (checkpointedProviderCallId.isEmpty()) {
      throw new IllegalArgumentException("checkpointedProviderCallId is required");
    }
    traceFrames = traceFrames == null ? List.of() : List.copyOf(traceFrames);
    findingUpdates =
        findingUpdates == null ? ResearchFindingUpdateBatch.empty() : findingUpdates;
  }

  @Override
  public List<ResearchCheckpointTraceSpan> traceFrames() {
    return List.copyOf(traceFrames);
  }
}
