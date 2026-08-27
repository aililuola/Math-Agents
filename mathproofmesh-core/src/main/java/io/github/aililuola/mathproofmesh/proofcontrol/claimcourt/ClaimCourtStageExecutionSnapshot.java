package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import java.util.LinkedHashMap;
import java.util.Map;

public record ClaimCourtStageExecutionSnapshot(
    int schemaVersion, Map<String, ClaimCourtStageExecutionRecord> records) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public ClaimCourtStageExecutionSnapshot {
    if (schemaVersion < 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported claim court execution snapshot schema");
    }
    records = records == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(records));
  }

  public static ClaimCourtStageExecutionSnapshot empty() {
    return new ClaimCourtStageExecutionSnapshot(CURRENT_SCHEMA_VERSION, Map.of());
  }

  @Override
  public Map<String, ClaimCourtStageExecutionRecord> records() {
    return Map.copyOf(records);
  }
}
