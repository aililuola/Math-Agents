package io.github.aililuola.mathproofmesh.research;

import java.util.List;

public record ResearchCheckpointRecord(
    String checkpointId,
    String problemHash,
    String routeId,
    String stage,
    String providerCallId,
    String reasoningTraceCallId,
    String reasoningTraceTaskId,
    int frameSequence,
    String frameHash,
    String traceSha256,
    Integer markerStart,
    Integer markerEnd,
    List<String> findingIds,
    String source) {

  public ResearchCheckpointRecord {
    checkpointId = required(checkpointId, "checkpointId");
    problemHash = required(problemHash, "problemHash");
    routeId = required(routeId, "routeId");
    stage = required(stage, "stage");
    providerCallId = required(providerCallId, "providerCallId");
    reasoningTraceCallId = normalizeNullable(reasoningTraceCallId);
    reasoningTraceTaskId = normalizeNullable(reasoningTraceTaskId);
    if (frameSequence < 0) {
      throw new IllegalArgumentException("frameSequence must be nonnegative");
    }
    frameHash = required(frameHash, "frameHash");
    traceSha256 = normalizeNullable(traceSha256);
    if ((markerStart == null) != (markerEnd == null)
        || markerStart != null && (markerStart < 0 || markerEnd < markerStart)) {
      throw new IllegalArgumentException("marker offsets must be present and ordered together");
    }
    findingIds = findingIds == null ? List.of() : List.copyOf(findingIds);
    source = required(source, "source");
    if (!"reasoning_trace".equals(source) && !"final_envelope".equals(source)) {
      throw new IllegalArgumentException("unsupported checkpoint source");
    }
  }

  @Override
  public List<String> findingIds() {
    return List.copyOf(findingIds);
  }

  private static String required(String value, String name) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return normalized;
  }

  private static String normalizeNullable(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.strip();
    return normalized.isEmpty() ? null : normalized;
  }
}
