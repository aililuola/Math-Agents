package io.github.aililuola.mathproofmesh.memory;

import io.github.aililuola.mathproofmesh.computation.ComputationEvidenceGate;
import java.util.List;

public final class VerifiedCounterexampleAuthority {
  private final boolean resultPresent;
  private final boolean replayValid;
  private final ComputationEvidenceGate.EvidenceAuthority authority;
  private final String experimentArtifactRef;
  private final String targetStatement;
  private final String rawSourceRef;
  private final List<String> trustedAliases;

  private VerifiedCounterexampleAuthority(
      boolean resultPresent,
      boolean replayValid,
      ComputationEvidenceGate.EvidenceAuthority authority,
      String experimentArtifactRef,
      String targetStatement,
      String rawSourceRef,
      List<String> trustedAliases) {
    this.resultPresent = resultPresent;
    this.replayValid = replayValid;
    this.authority = java.util.Objects.requireNonNull(authority, "authority");
    this.experimentArtifactRef = trim(experimentArtifactRef);
    this.targetStatement = trim(targetStatement);
    this.rawSourceRef = trim(rawSourceRef);
    this.trustedAliases = trustedAliases == null ? List.of() : List.copyOf(trustedAliases);
  }

  public static VerifiedCounterexampleAuthority independentReplay(
      boolean resultPresent,
      boolean replayValid,
      ComputationEvidenceGate.EvidenceAuthority authority,
      String experimentArtifactRef,
      String targetStatement,
      String rawSourceRef,
      List<String> trustedAliases) {
    return new VerifiedCounterexampleAuthority(
        resultPresent,
        replayValid,
        authority,
        experimentArtifactRef,
        targetStatement,
        rawSourceRef,
        trustedAliases);
  }

  public boolean trusted() {
    return resultPresent
        && replayValid
        && authority == ComputationEvidenceGate.EvidenceAuthority.REFUTED
        && experimentArtifactRef != null
        && !experimentArtifactRef.isBlank()
        && targetStatement != null
        && !targetStatement.isBlank()
        && rawSourceRef != null
        && !rawSourceRef.isBlank();
  }

  public String experimentArtifactRef() {
    return experimentArtifactRef;
  }

  public String targetStatement() {
    return targetStatement;
  }

  public String rawSourceRef() {
    return rawSourceRef;
  }

  public List<String> trustedAliases() {
    return List.copyOf(trustedAliases);
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }
}
