package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.Map;

public record StrategyCandidateSnapshot(
    int schemaVersion, Map<String, StrategyCandidateRecord> records, long version) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public StrategyCandidateSnapshot {
    records = records == null ? Map.of() : Map.copyOf(records);
    if (version < 0L) {
      throw new IllegalArgumentException("version must be nonnegative");
    }
  }

  public static StrategyCandidateSnapshot empty() {
    return new StrategyCandidateSnapshot(CURRENT_SCHEMA_VERSION, Map.of(), 0L);
  }

  @Override
  public Map<String, StrategyCandidateRecord> records() {
    return Map.copyOf(records);
  }
}
