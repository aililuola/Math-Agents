package io.github.aililuola.mathproofmesh.memory;

import java.util.List;

public record NegativeKnowledgeSnapshot(
    int schemaVersion,
    List<NegativeKnowledgeRecord> records,
    List<NegativeKnowledgeAuditEvent> audit) {

  public static final int CURRENT_SCHEMA_VERSION = 1;

  public NegativeKnowledgeSnapshot {
    if (schemaVersion < 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported negative knowledge schema version");
    }
    records = records == null ? List.of() : List.copyOf(records);
    audit = audit == null ? List.of() : List.copyOf(audit);
  }

  public static NegativeKnowledgeSnapshot empty() {
    return new NegativeKnowledgeSnapshot(CURRENT_SCHEMA_VERSION, List.of(), List.of());
  }
}
