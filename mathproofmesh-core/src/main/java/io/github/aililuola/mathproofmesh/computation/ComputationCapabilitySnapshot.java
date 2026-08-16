package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.Comparator;
import java.util.List;

public record ComputationCapabilitySnapshot(
    List<ComputationCapabilityDescriptor> descriptors, String stableHash) {
  public ComputationCapabilitySnapshot {
    descriptors =
        descriptors == null
            ? List.of()
            : descriptors.stream()
                .sorted(Comparator.comparing(value -> value.method().value()))
                .toList();
    String expected = CanonicalJson.stableHash(descriptors);
    stableHash = stableHash == null || stableHash.isBlank() ? expected : stableHash.strip();
    if (!ComputationJson.hashesEqual(expected, stableHash)) {
      throw new IllegalArgumentException("capability snapshot hash mismatch");
    }
  }

  public static ComputationCapabilitySnapshot empty() {
    return new ComputationCapabilitySnapshot(List.of(), null);
  }

  @Override
  public List<ComputationCapabilityDescriptor> descriptors() {
    return List.copyOf(descriptors);
  }
}
