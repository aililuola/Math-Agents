package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationCertificateEnvelope;
import java.util.Objects;

/** Claim-neutral producer evidence; authority must be rebound and reverified per request. */
public record CanonicalComputationCacheEntry(
    ComputationResultArtifact result, ComputationCertificateEnvelope certificate) {
  public CanonicalComputationCacheEntry {
    result = Objects.requireNonNull(result, "result");
    certificate = Objects.requireNonNull(certificate, "certificate");
  }
}
