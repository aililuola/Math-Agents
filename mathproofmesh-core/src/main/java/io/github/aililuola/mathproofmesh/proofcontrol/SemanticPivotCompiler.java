package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.SemanticPivotProposal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Compiles a non-authoritative provider proposal into server-owned typed state. */
public final class SemanticPivotCompiler {
  public PivotDelta compile(
      SemanticPivotProposal proposal, Map<String, PivotObstructionRef> trustedObstructions) {
    java.util.Objects.requireNonNull(proposal, "proposal");
    Map<String, PivotObstructionRef> known =
        trustedObstructions == null ? Map.of() : Map.copyOf(trustedObstructions);
    if (proposal.claimedPivotId() != null || proposal.claimedStructuralDeltaHash() != null) {
      throw new PivotCompilationException(
          "SERVER_OWNED_ID_ASSERTED", "provider proposal asserted a server-owned pivot identity");
    }
    List<PivotObstructionRef> obstructions =
        proposal.obstructionIds().stream()
            .map(
                id -> {
                  PivotObstructionRef reference = known.get(id);
                  if (reference == null) {
                    throw new PivotCompilationException(
                        "UNKNOWN_OBSTRUCTION", "proposal references unknown obstruction " + id);
                  }
                  return reference;
                })
            .toList();
    return PivotDelta.create(
        proposal.problemHash(),
        proposal.rootGoalHash(),
        proposal.routeId(),
        proposal.sourceStrategyId(),
        proposal.transformationTypes().stream()
            .map(value -> enumValue(PivotTransformationType.class, value, "transformation type"))
            .toList(),
        obstructions,
        proposal.objectChanges().stream()
            .map(
                change ->
                    new MathematicalObjectChange(
                        change.oldObjectId(),
                        change.oldDescription(),
                        enumValue(PivotObjectDisposition.class, change.disposition(), "object disposition"),
                        change.newObjectId(),
                        change.newDescription(),
                        change.bridgeStatement(),
                        change.evidenceRefs()))
            .toList(),
        proposal.directionChanges().stream()
            .map(
                change ->
                    new PivotDirectionChange(
                        change.oldDirectionSignature(),
                        change.newDirectionSignature(),
                        change.mathematicalReason(),
                        change.evidenceRefs()))
            .toList(),
        proposal.assumptionChanges().stream()
            .map(
                change ->
                    new PivotAssumptionChange(
                        change.oldAssumption(),
                        change.newAssumption(),
                        change.reason(),
                        change.evidenceRefs()))
            .toList(),
        proposal.claimUseChanges().stream()
            .map(
                change ->
                    new PivotClaimUseChange(
                        change.claimId(),
                        change.claimStatementHash(),
                        enumValue(PivotClaimUsageAction.class, change.action(), "claim use action"),
                        change.reason()))
            .toList(),
        proposal.obligationChanges().stream()
            .map(
                change ->
                    new PivotObligationChange(
                        change.obligationId(),
                        change.canonicalTargetId(),
                        enumValue(PivotObligationAction.class, change.action(), "obligation action"),
                        change.proposedStatement(),
                        change.proposedKind() == null
                            ? null
                            : ObligationKind.fromValue(change.proposedKind()),
                        change.assumptions(),
                        change.dependencyIds(),
                        change.reason()))
            .toList(),
        proposal.proposedStrategy(),
        proposal.rationale());
  }

  private static <E extends Enum<E>> E enumValue(
      Class<E> enumType, String value, String field) {
    if (value == null) {
      throw new PivotCompilationException(
          "UNKNOWN_PIVOT_ENUM", "unknown " + field + ": null");
    }
    try {
      return Enum.valueOf(enumType, value.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new PivotCompilationException(
          "UNKNOWN_PIVOT_ENUM", "unknown " + field + ": " + value);
    }
  }
}
