package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ClaimCourtSnapshot(
    int schemaVersion,
    Map<String, ClaimCourtRecord> records,
    List<ClaimCourtAuditEvent> audit) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public ClaimCourtSnapshot {
    if (schemaVersion < 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported claim court snapshot schema");
    }
    records = records == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(records));
    audit = ClaimCourtValues.copy(audit);
  }

  public static ClaimCourtSnapshot empty() {
    return new ClaimCourtSnapshot(CURRENT_SCHEMA_VERSION, Map.of(), List.of());
  }

  @Override
  public Map<String, ClaimCourtRecord> records() {
    return Map.copyOf(records);
  }

  @Override
  public List<ClaimCourtAuditEvent> audit() {
    return List.copyOf(audit);
  }
}
