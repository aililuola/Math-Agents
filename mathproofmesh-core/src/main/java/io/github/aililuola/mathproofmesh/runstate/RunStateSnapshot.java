package io.github.aililuola.mathproofmesh.runstate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RunStateSnapshot(
    int schemaVersion,
    RunAuthoritySnapshot authority,
    RunProjectionSnapshot projection,
    RunReconciliationStatus reconciliationStatus,
    List<RunStateConflict> conflicts,
    String stateHash,
    Instant updatedAt) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public RunStateSnapshot {
    if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported run state schema");
    }
    authority = Objects.requireNonNull(authority, "authority");
    projection =
        projection == null ? RunProjectionSnapshot.absent(authority.authorityHash()) : projection;
    reconciliationStatus =
        reconciliationStatus == null ? RunReconciliationStatus.CONSISTENT : reconciliationStatus;
    conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", schemaVersion);
    payload.put("authority", authority);
    payload.put("projection", projection);
    payload.put("reconciliationStatus", reconciliationStatus);
    payload.put("conflicts", conflicts);
    stateHash = RunStateHashes.generatedOrVerified(stateHash, payload, "state");
  }

  public static RunStateSnapshot create(
      RunAuthoritySnapshot authority,
      RunProjectionSnapshot projection,
      RunReconciliationStatus reconciliationStatus,
      List<RunStateConflict> conflicts,
      Instant updatedAt) {
    return new RunStateSnapshot(
        CURRENT_SCHEMA_VERSION,
        authority,
        projection,
        reconciliationStatus,
        conflicts,
        null,
        updatedAt);
  }

  @Override
  public List<RunStateConflict> conflicts() {
    return List.copyOf(conflicts);
  }
}
