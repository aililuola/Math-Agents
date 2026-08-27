package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.List;
import java.util.Map;

public record SemanticPivotSnapshot(
    int schemaVersion,
    Map<String, SemanticPivotRecord> records,
    List<SemanticPivotAuditEvent> audit) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public SemanticPivotSnapshot {
    if (schemaVersion < 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported semantic pivot snapshot schema");
    }
    records = records == null ? Map.of() : Map.copyOf(records);
    audit = PivotValues.copy(audit);
  }

  public static SemanticPivotSnapshot empty() {
    return new SemanticPivotSnapshot(CURRENT_SCHEMA_VERSION, Map.of(), List.of());
  }

  @Override
  public Map<String, SemanticPivotRecord> records() {
    return Map.copyOf(records);
  }

  @Override
  public List<SemanticPivotAuditEvent> audit() {
    return List.copyOf(audit);
  }
}
