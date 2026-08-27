package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;

public record ComputationCapabilityFingerprint(String value) {
  public ComputationCapabilityFingerprint {
    value = value == null ? "" : value.strip();
    if (!value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("capability fingerprint must be a SHA-256 hash");
    }
  }

  public static ComputationCapabilityFingerprint of(ComputationCapabilityDescriptor descriptor) {
    return new ComputationCapabilityFingerprint(CanonicalJson.stableHash(descriptor));
  }
}
