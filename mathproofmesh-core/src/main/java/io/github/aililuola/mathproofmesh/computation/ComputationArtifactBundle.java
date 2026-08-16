package io.github.aililuola.mathproofmesh.computation;

import java.util.List;
import java.util.Objects;

public record ComputationArtifactBundle(
    ComputationArtifactRecord request,
    ComputationArtifactRecord result,
    ComputationArtifactRecord certificate,
    ComputationArtifactRecord verificationReceipt,
    ComputationArtifactRecord outcomeApplicationReceipt) {
  public ComputationArtifactBundle {
    request = Objects.requireNonNull(request, "request");
    result = Objects.requireNonNull(result, "result");
    certificate = Objects.requireNonNull(certificate, "certificate");
    verificationReceipt = Objects.requireNonNull(verificationReceipt, "verificationReceipt");
  }

  public List<ComputationArtifactRecord> evidenceArtifacts() {
    return List.of(request, result, certificate, verificationReceipt);
  }
}
