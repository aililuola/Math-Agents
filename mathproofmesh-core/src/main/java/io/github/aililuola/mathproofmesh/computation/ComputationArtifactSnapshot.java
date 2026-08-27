package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.Comparator;
import java.util.List;

public record ComputationArtifactSnapshot(
    List<ComputationArtifactRecord> records, String stableHash) {
  public ComputationArtifactSnapshot {
    records =
        records == null
            ? List.of()
            : records.stream()
                .sorted(
                    Comparator.comparing(ComputationArtifactRecord::executionId)
                        .thenComparing(value -> value.kind().name()))
                .toList();
    String expected = CanonicalJson.stableHash(records);
    stableHash = stableHash == null || stableHash.isBlank() ? expected : stableHash.strip();
    if (!ComputationJson.hashesEqual(expected, stableHash)) {
      throw new IllegalArgumentException("computation artifact snapshot hash mismatch");
    }
  }

  public static ComputationArtifactSnapshot empty() {
    return new ComputationArtifactSnapshot(List.of(), null);
  }

  @Override
  public List<ComputationArtifactRecord> records() {
    return List.copyOf(records);
  }
}
