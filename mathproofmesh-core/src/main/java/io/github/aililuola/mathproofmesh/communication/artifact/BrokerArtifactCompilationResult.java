package io.github.aililuola.mathproofmesh.communication.artifact;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import java.util.List;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor stores immutable defensive copies.")
public record BrokerArtifactCompilationResult(
    boolean accepted, BrokerArtifactEnvelope artifact, List<String> rejectionCodes) {
  public BrokerArtifactCompilationResult {
    rejectionCodes = BrokerArtifactValues.list(rejectionCodes);
    if (accepted == (artifact == null)) {
      throw new IllegalArgumentException("accepted compilation must contain exactly one artifact");
    }
  }

  public static BrokerArtifactCompilationResult accepted(BrokerArtifactEnvelope artifact) {
    return new BrokerArtifactCompilationResult(true, artifact, List.of());
  }

  public static BrokerArtifactCompilationResult rejected(String code) {
    return new BrokerArtifactCompilationResult(false, null, List.of(code));
  }
}
