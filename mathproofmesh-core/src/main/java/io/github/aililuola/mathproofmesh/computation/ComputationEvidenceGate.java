package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;

/** Prevents bounded or merely result-producing calculations from becoming facts. */
public final class ComputationEvidenceGate {
  private ComputationEvidenceGate() {}

  public static EvidenceAuthority authority(ExperimentResult result) {
    java.util.Objects.requireNonNull(result, "result");
    if (result.outcome() == ExperimentOutcome.COUNTEREXAMPLE_FOUND
        && result.evidenceStrength() == EvidenceStrength.COUNTEREXAMPLE
        && result.independentlyVerified()) {
      return EvidenceAuthority.REFUTED;
    }
    if (result.outcome() == ExperimentOutcome.NOT_REFUTED) {
      return EvidenceAuthority.NOT_REFUTED;
    }
    if (result.outcome() == ExperimentOutcome.CERTIFIED
        && result.evidenceStrength() == EvidenceStrength.EXHAUSTIVE_CERTIFICATE
        && result.scope().path("complete_domain").asBoolean(false)) {
      return EvidenceAuthority.VERIFIED_BOUNDED;
    }
    if (result.outcome() == ExperimentOutcome.CERTIFIED
        && result.evidenceStrength() == EvidenceStrength.FORMAL_CERTIFICATE
        && result.method() == ComputationMethod.LEAN_CHECK) {
      return EvidenceAuthority.VERIFIED;
    }
    return EvidenceAuthority.INCONCLUSIVE;
  }

  public static FactDecision evaluate(ExperimentResult result) {
    if (result.outcome() == ExperimentOutcome.COUNTEREXAMPLE_FOUND
        && result.evidenceStrength() == EvidenceStrength.COUNTEREXAMPLE
        && result.independentlyVerified()) {
      return new FactDecision(false, true, "independently replayed counterexample");
    }
    if (result.evidenceStrength() == EvidenceStrength.BOUNDED_EVIDENCE
        || result.outcome() == ExperimentOutcome.NOT_REFUTED) {
      return new FactDecision(
          false, false, "bounded computation cannot satisfy the Fact gate");
    }
    if (result.evidenceStrength() == EvidenceStrength.FORMAL_CERTIFICATE
        && result.outcome() == ExperimentOutcome.CERTIFIED
        && result.method() == ComputationMethod.LEAN_CHECK) {
      return new FactDecision(true, false, "formal kernel certificate");
    }
    if (result.evidenceStrength() == EvidenceStrength.EXHAUSTIVE_CERTIFICATE
        && result.outcome() == ExperimentOutcome.CERTIFIED
        && result.scope().path("complete_domain").asBoolean(false)) {
      return new FactDecision(true, false, "complete finite-domain certificate");
    }
    return new FactDecision(
        false, false, "evidence does not meet a fact-admission rule");
  }

  public record FactDecision(
      boolean factAdmissible, boolean negativeAdmissible, String reason) {}

  public enum EvidenceAuthority {
    REFUTED,
    NOT_REFUTED,
    VERIFIED_BOUNDED,
    VERIFIED,
    INCONCLUSIVE
  }
}
