package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.EvidenceRef;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClaimBlindReviewPacketFactory {
  public ClaimBlindReviewPacket create(
      FrozenClaimSnapshot frozen,
      ClaimProofRevisionRecord revision,
      Set<String> verifiedDependencyClaimIds,
      Collection<TrustedClaimEvidence> availableEvidence) {
    java.util.Objects.requireNonNull(frozen, "frozen");
    java.util.Objects.requireNonNull(revision, "revision");
    if (!frozen.claimId().equals(revision.claimId())
        || !frozen.claimSemanticHash().equals(revision.claimSemanticHash())) {
      throw new IllegalArgumentException("FROZEN_CLAIM_MUTATION");
    }
    List<String> verified =
        verifiedDependencyClaimIds == null
            ? List.of()
            : verifiedDependencyClaimIds.stream().sorted().toList();
    if (!verified.containsAll(revision.dependencyClaimIds())) {
      throw new IllegalArgumentException("blind packet contains unverified claim dependency");
    }
    Map<EvidenceRef, EvidenceRef> resolvedEvidence =
        resolveEvidence(frozen, revision, availableEvidence);
    return new ClaimBlindReviewPacket(
        frozen.courtCaseId(),
        frozen.problemHash(),
        frozen.rootGoalHash(),
        frozen.claimId(),
        frozen.claimSemanticHash(),
        frozen.statement(),
        frozen.conclusion(),
        frozen.assumptions(),
        frozen.quantifiers(),
        frozen.variableBindings(),
        frozen.scopeLimitations(),
        frozen.polarity(),
        revision.revisionId(),
        revision.proofHash(),
        trustedProofSteps(revision.proofSteps(), resolvedEvidence),
        verified,
        List.copyOf(resolvedEvidence.values()));
  }

  private static Map<EvidenceRef, EvidenceRef> resolveEvidence(
      FrozenClaimSnapshot frozen,
      ClaimProofRevisionRecord revision,
      Collection<TrustedClaimEvidence> availableEvidence) {
    LinkedHashMap<EvidenceRef, EvidenceRef> resolved = new LinkedHashMap<>();
    ClaimTrustedEvidenceResolver resolver = new ClaimTrustedEvidenceResolver();
    List<EvidenceRef> referenced = new ArrayList<>(revision.evidenceRefs());
    revision.proofSteps().stream()
        .flatMap(step -> step.calculationEvidenceRefs().stream())
        .forEach(referenced::add);
    for (EvidenceRef reference : referenced) {
      ClaimTrustedEvidenceResolver.Resolution resolution =
          resolver.resolve(frozen, reference, availableEvidence);
      if (!resolution.accepted()) {
        throw new IllegalArgumentException(
            resolution.failureCode() + ":" + reference.artifactRef());
      }
      resolved.putIfAbsent(reference, resolution.evidence().evidenceRef());
    }
    return Map.copyOf(resolved);
  }

  private static List<ProofStep> trustedProofSteps(
      List<ProofStep> proofSteps, Map<EvidenceRef, EvidenceRef> resolvedEvidence) {
    return proofSteps.stream()
        .map(
            step ->
                new ProofStep(
                    step.branchLabel(),
                    step.calculationChecks(),
                    step.calculationEvidenceRefs().stream()
                        .map(resolvedEvidence::get)
                        .toList(),
                    step.calculations(),
                    step.citations(),
                    step.confidence(),
                    step.dependencies(),
                    step.dependencyRefs(),
                    step.isKeyStep(),
                    step.justification(),
                    step.statement(),
                    step.stepId(),
                    step.stepType()))
        .toList();
  }
}
