package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ComputationVerificationReceipt;
import io.github.aililuola.mathproofmesh.contract.ComputationVerifiedAuthority;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;

/** Prevents bounded or merely result-producing calculations from becoming facts. */
public final class ComputationEvidenceGate {
  private ComputationEvidenceGate() {}

  public static EvidenceAuthority authority(ExperimentResult result) {
    java.util.Objects.requireNonNull(result, "result");
    if (result.outcome() == ExperimentOutcome.NOT_REFUTED) {
      return EvidenceAuthority.NOT_REFUTED;
    }
    return EvidenceAuthority.INCONCLUSIVE;
  }

  public static EvidenceAuthority authority(
      ExperimentResult result,
      ComputationVerificationReceipt receipt,
      ComputationCapabilityDescriptor descriptor) {
    java.util.Objects.requireNonNull(result, "result");
    java.util.Objects.requireNonNull(receipt, "receipt");
    java.util.Objects.requireNonNull(descriptor, "descriptor");
    if (!receipt.valid()
        || result.method() != descriptor.method()
        || !receipt.verifierId().equals(descriptor.verifierId())
        || !receipt.verifierVersion().equals(descriptor.verifierVersion())
        || !receipt.verifiedScopeHash().equals(CanonicalJson.stableHash(result.scope()))
        || !descriptor.authorityCeiling().permits(receipt.authority())) {
      return result.outcome() == ExperimentOutcome.NOT_REFUTED
          ? EvidenceAuthority.NOT_REFUTED
          : EvidenceAuthority.INCONCLUSIVE;
    }
    return switch (receipt.authority()) {
      case EXACT_COUNTEREXAMPLE -> EvidenceAuthority.REFUTED;
      case FINITE_DOMAIN_CERTIFICATE -> EvidenceAuthority.VERIFIED_BOUNDED;
      case FORMAL_CERTIFICATE -> EvidenceAuthority.VERIFIED;
      case BOUNDED_OBSERVATION -> EvidenceAuthority.NOT_REFUTED;
      case AUDIT_ONLY -> EvidenceAuthority.INCONCLUSIVE;
    };
  }

  public static FactDecision evaluate(ExperimentResult result) {
    return new FactDecision(
        false, false, "a durable independent verification receipt is required");
  }

  public static FactDecision evaluate(
      ExperimentResult result,
      ComputationVerificationReceipt receipt,
      ComputationCapabilityDescriptor descriptor) {
    return switch (authority(result, receipt, descriptor)) {
      case REFUTED -> new FactDecision(false, true, "verified exact counterexample receipt");
      case VERIFIED -> new FactDecision(true, false, "formal kernel verification receipt");
      case VERIFIED_BOUNDED ->
          new FactDecision(true, false, "complete finite-domain verification receipt");
      case NOT_REFUTED, INCONCLUSIVE ->
          new FactDecision(false, false, "bounded or inconclusive computation is not a Fact");
    };
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
