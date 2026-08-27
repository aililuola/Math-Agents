package io.github.aililuola.mathproofmesh.proofgraph;

import java.util.Map;

/** Restore-safe snapshot of all capacity and focused-recovery deferrals. */
public record DeferredExpansionSnapshot(
    Map<String, DeferredExpansionRecord> records, long version) {

  public DeferredExpansionSnapshot {
    records = records == null ? Map.of() : Map.copyOf(records);
    if (version < 0) {
      throw new IllegalArgumentException("version must be nonnegative");
    }
  }

  public static DeferredExpansionSnapshot empty() {
    return new DeferredExpansionSnapshot(Map.of(), 0L);
  }

  @Override
  public Map<String, DeferredExpansionRecord> records() {
    return Map.copyOf(records);
  }
}
