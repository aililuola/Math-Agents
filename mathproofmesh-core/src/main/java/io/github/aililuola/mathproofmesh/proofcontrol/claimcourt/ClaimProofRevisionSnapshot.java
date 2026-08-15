package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ClaimProofRevisionSnapshot(
    int schemaVersion,
    Map<String, ClaimProofRevisionRecord> records,
    List<ClaimProofRevisionAuditEvent> audit) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public ClaimProofRevisionSnapshot {
    if (schemaVersion < 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported proof revision snapshot schema");
    }
    records =
        records == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(records));
    audit = ClaimCourtValues.copy(audit);
  }

  public static ClaimProofRevisionSnapshot empty() {
    return new ClaimProofRevisionSnapshot(CURRENT_SCHEMA_VERSION, Map.of(), List.of());
  }

  @Override
  public Map<String, ClaimProofRevisionRecord> records() {
    return Map.copyOf(records);
  }

  @Override
  public List<ClaimProofRevisionAuditEvent> audit() {
    return List.copyOf(audit);
  }
}
