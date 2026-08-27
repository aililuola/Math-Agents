package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.CriticalClaimContextBinding;
import io.github.aililuola.mathproofmesh.contract.MechanismOperationDeclaration;
import io.github.aililuola.mathproofmesh.contract.MechanismOperationKind;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Supplies explicit Issue 007 metadata to production-path test candidates. */
final class DesktopStrategyMetadataTestSupport {
  private DesktopStrategyMetadataTestSupport() {}

  static StrategyCard complete(StrategyCard source) {
    return complete(source, MechanismOperationKind.DIRECT);
  }

  static StrategyCard complete(StrategyCard source, MechanismOperationKind defaultKind) {
    List<MechanismOperationDeclaration> operations =
        source.mechanismOperations().isEmpty()
            ? List.of(
                new MechanismOperationDeclaration(
                    "test-declared-mechanism",
                    defaultKind,
                    List.of("@roots"),
                    List.of("@direct_targets")))
            : source.mechanismOperations();
    Map<String, CriticalClaimContextBinding> bindings = new LinkedHashMap<>();
    source
        .criticalClaimContextBindings()
        .forEach(binding -> bindings.put(binding.claimId(), binding));
    source
        .criticalClaims()
        .forEach(
            claim ->
                bindings.putIfAbsent(
                    claim.claimId(),
                    new CriticalClaimContextBinding(
                        claim.claimId(),
                        "@claim",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        "positive")));
    return new StrategyCard(
        source.assignedAgentId(),
        source.bottleneck(),
        source.calculationChecks(),
        source.calculationEvidenceRefs(),
        source.computationHints(),
        source.coreIdea(),
        source.criticalClaims(),
        source.estimatedCost(),
        source.estimatedSuccess(),
        source.expectedLemmas(),
        source.falsificationTest(),
        source.independenceBasis(),
        source.inspirationProposalId(),
        source.keyOriginalStep(),
        source.parentStrategyIds(),
        source.prerequisites(),
        source.strategyId(),
        source.tags(),
        source.title(),
        operations,
        List.copyOf(bindings.values()));
  }

  static StrategyCard inherit(StrategyCard draft, StrategyCard source) {
    return complete(
        new StrategyCard(
            draft.assignedAgentId(),
            draft.bottleneck(),
            draft.calculationChecks(),
            draft.calculationEvidenceRefs(),
            draft.computationHints(),
            draft.coreIdea(),
            draft.criticalClaims(),
            draft.estimatedCost(),
            draft.estimatedSuccess(),
            draft.expectedLemmas(),
            draft.falsificationTest(),
            draft.independenceBasis(),
            draft.inspirationProposalId(),
            draft.keyOriginalStep(),
            draft.parentStrategyIds(),
            draft.prerequisites(),
            draft.strategyId(),
            draft.tags(),
            draft.title(),
            source.mechanismOperations(),
            source.criticalClaimContextBindings()));
  }
}
