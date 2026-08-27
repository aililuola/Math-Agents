package io.github.aililuola.mathproofmesh.research;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ResearchCheckpointSnapshot(
    int schemaVersion,
    Map<String, ResearchCheckpointRecord> checkpoints,
    Map<String, ResearchFindingRecord> findings,
    List<ResearchFindingAuditEvent> audit) {

  public static final int CURRENT_SCHEMA_VERSION = 1;

  public ResearchCheckpointSnapshot {
    if (schemaVersion < 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported research checkpoint schema version");
    }
    checkpoints =
        checkpoints == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(checkpoints));
    findings = findings == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(findings));
    audit = audit == null ? List.of() : List.copyOf(audit);
  }

  public static ResearchCheckpointSnapshot empty() {
    return new ResearchCheckpointSnapshot(CURRENT_SCHEMA_VERSION, Map.of(), Map.of(), List.of());
  }
}
