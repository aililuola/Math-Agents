package io.github.aililuola.mathproofmesh.communication.artifact;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.BrokerVerifiedEffectType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class BrokerArtifactEffectVerifier {
  public Verification verify(
      BrokerArtifactLineageRecord lineage,
      BrokerDeliveryBaseline baseline,
      BrokerArtifactEffectObservation observation) {
    Set<BrokerVerifiedEffectType> effects = new LinkedHashSet<>();
    Set<String> affected = new LinkedHashSet<>();
    if (intersects(lineage.downstreamProofStepIds(), observation.committedStepIds())) {
      effects.add(BrokerVerifiedEffectType.COMMITTED_STEP_REUSE);
      lineage.downstreamProofStepIds().stream().filter(observation.committedStepIds()::contains)
          .forEach(affected::add);
    }
    if (intersects(lineage.downstreamClaimIds(), observation.verifiedClaimIds())) {
      effects.add(BrokerVerifiedEffectType.VERIFIED_CLAIM_DERIVED);
      lineage.downstreamClaimIds().stream().filter(observation.verifiedClaimIds()::contains)
          .forEach(affected::add);
    }
    if (lineage.useKind() == BrokerArtifactUseKind.REFUTES_CLAIM
        && intersects(lineage.downstreamClaimIds(), observation.refutedClaimIds())) {
      effects.add(BrokerVerifiedEffectType.EXACT_CLAIM_REFUTED);
      lineage.downstreamClaimIds().stream().filter(observation.refutedClaimIds()::contains)
          .forEach(affected::add);
    }
    if (intersects(lineage.downstreamObligationIds(), observation.closedObligationIds())) {
      effects.add(BrokerVerifiedEffectType.OBLIGATION_CLOSED);
      lineage.downstreamObligationIds().stream().filter(observation.closedObligationIds()::contains)
          .forEach(affected::add);
    }
    if (lineage.useKind() == BrokerArtifactUseKind.RETIRES_DEPENDENCY
        && intersects(lineage.downstreamClaimIds(), observation.retiredDependencyIds())) {
      effects.add(BrokerVerifiedEffectType.DEPENDENCY_RETIRED);
    }
    if (lineage.useKind() == BrokerArtifactUseKind.SELECTS_FOCUS_OBLIGATION
        && observation.focusCanonicalTargetIdAfter() != null
        && !observation.focusCanonicalTargetIdAfter().equals(baseline.focusCanonicalTargetIdBefore())) {
      effects.add(BrokerVerifiedEffectType.FOCUS_CHANGED);
      affected.add(observation.focusCanonicalTargetIdAfter());
    }
    if (lineage.useKind() == BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR
        && observation.localRepairId() != null) {
      effects.add(BrokerVerifiedEffectType.LOCAL_REPAIR_BOUND);
      affected.add(observation.localRepairId());
    }
    if (lineage.useKind() == BrokerArtifactUseKind.TRIGGERS_SEMANTIC_PIVOT
        && observation.semanticPivotId() != null) {
      effects.add(BrokerVerifiedEffectType.SEMANTIC_PIVOT_BOUND);
      affected.add(observation.semanticPivotId());
    }
    if (lineage.useKind() == BrokerArtifactUseKind.SUPPORTS_COMPUTATION_PLAN
        && observation.computationPlanId() != null) {
      effects.add(BrokerVerifiedEffectType.COMPUTATION_PLAN_BOUND);
      affected.add(observation.computationPlanId());
    }
    if (lineage.useKind() == BrokerArtifactUseKind.CITED_IN_FINAL_PROOF
        && observation.citedByFinalProof()) {
      effects.add(BrokerVerifiedEffectType.FINAL_PROOF_CITATION);
    }
    return new Verification(List.copyOf(effects), List.copyOf(affected),
        Math.max(0.0d, baseline.canonicalProofDebtBefore() - observation.canonicalProofDebtAfter()));
  }

  private static boolean intersects(List<String> declared, Set<String> actual) {
    return declared.stream().anyMatch(actual::contains);
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "The compact constructor stores immutable defensive copies.")
  public record Verification(
      List<BrokerVerifiedEffectType> effectTypes,
      List<String> affectedDownstreamIds,
      double proofDebtReduction) {
    public Verification {
      effectTypes = BrokerArtifactValues.list(effectTypes);
      affectedDownstreamIds = BrokerArtifactValues.list(affectedDownstreamIds);
    }
    public boolean verified() { return !effectTypes.isEmpty(); }
  }
}
