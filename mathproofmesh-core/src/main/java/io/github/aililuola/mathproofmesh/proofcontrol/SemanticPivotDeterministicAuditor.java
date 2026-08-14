package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Fail-closed deterministic audit for semantic identity, authority, and non-empty state change. */
public final class SemanticPivotDeterministicAuditor {
  public static final String EMPTY_SEMANTIC_DELTA = "EMPTY_SEMANTIC_DELTA";
  public static final String TEXT_ONLY_REVISION = "TEXT_ONLY_REVISION";
  public static final String CORE_IDEA_APPEND_ONLY = "CORE_IDEA_APPEND_ONLY";
  public static final String NO_OBJECT_CHANGE = "NO_OBJECT_CHANGE";
  public static final String NO_TARGET_CHANGE = "NO_TARGET_CHANGE";
  public static final String NO_DIRECTION_CHANGE = "NO_DIRECTION_CHANGE";
  public static final String NO_ASSUMPTION_CHANGE = "NO_ASSUMPTION_CHANGE";
  public static final String NO_OBLIGATION_GRAPH_CHANGE = "NO_OBLIGATION_GRAPH_CHANGE";
  public static final String UNKNOWN_OBSTRUCTION = "UNKNOWN_OBSTRUCTION";
  public static final String OBSTRUCTION_SCOPE_MISMATCH = "OBSTRUCTION_SCOPE_MISMATCH";
  public static final String ROOT_GOAL_MISMATCH = "ROOT_GOAL_MISMATCH";
  public static final String UNAUTHORIZED_CLAIM_RETIREMENT =
      "UNAUTHORIZED_CLAIM_RETIREMENT";
  public static final String UNAUTHORIZED_OBLIGATION_CLOSURE =
      "UNAUTHORIZED_OBLIGATION_CLOSURE";
  public static final String PERMANENT_NEGATIVE_CONFLICT = "PERMANENT_NEGATIVE_CONFLICT";
  public static final String FOCUSED_RECOVERY_BINDING_MISMATCH =
      "FOCUSED_RECOVERY_BINDING_MISMATCH";
  public static final String CAPACITY_OR_QUOTA_BLOCK = "CAPACITY_OR_QUOTA_BLOCK";

  public PivotDeltaAudit audit(
      PivotDelta delta,
      PivotStructuralSignature sourceSignature,
      PivotStructuralSignature proposedSignature,
      PivotAuthorityContext authority) {
    java.util.Objects.requireNonNull(delta, "delta");
    java.util.Objects.requireNonNull(sourceSignature, "sourceSignature");
    java.util.Objects.requireNonNull(proposedSignature, "proposedSignature");
    java.util.Objects.requireNonNull(authority, "authority");
    LinkedHashSet<String> failures = new LinkedHashSet<>();
    Map<String, String> details = new LinkedHashMap<>();

    if (!delta.problemHash().equals(authority.problemHash())
        || !delta.routeId().equals(authority.routeId())
        || !delta.sourceStrategyId().equals(authority.sourceStrategyId())) {
      failures.add(OBSTRUCTION_SCOPE_MISMATCH);
    }
    if (!delta.rootGoalHash().equals(authority.rootGoalHash())) {
      failures.add(ROOT_GOAL_MISMATCH);
    }
    if (!sourceSignature.strategyId().equals(delta.sourceStrategyId())
        || !proposedSignature.strategyId().equals(delta.proposedStrategyId())) {
      failures.add(OBSTRUCTION_SCOPE_MISMATCH);
    }

    auditObstructions(delta, authority, failures);
    auditObjects(delta, authority, failures);
    auditClaims(delta, authority, failures);
    auditObligations(delta, authority, failures);
    auditDeclaredTransformations(delta, failures);

    boolean explicitStateChange = explicitStateChange(delta);
    boolean structuralStateChange = !sourceSignature.sameSemanticState(proposedSignature);
    if (!explicitStateChange || !structuralStateChange) {
      failures.add(EMPTY_SEMANTIC_DELTA);
      failures.add(TEXT_ONLY_REVISION);
      if (isCoreIdeaAppendOnly(delta, sourceSignature, proposedSignature)) {
        failures.add(CORE_IDEA_APPEND_ONLY);
      }
    }
    if (!authority.permanentNegativeConflicts().isEmpty()) {
      failures.add(PERMANENT_NEGATIVE_CONFLICT);
      details.put(
          PERMANENT_NEGATIVE_CONFLICT,
          String.join(",", authority.permanentNegativeConflicts().stream().sorted().toList()));
    }
    if (authority.focusedRecovery() && !selectedBinding(delta, authority)) {
      failures.add(FOCUSED_RECOVERY_BINDING_MISMATCH);
    }
    if (!authority.capacityAvailable()
        && delta.obligationChanges().stream()
            .anyMatch(change -> change.action() == PivotObligationAction.ADD_NEW_OBLIGATION)) {
      failures.add(CAPACITY_OR_QUOTA_BLOCK);
    }

    List<String> result = List.copyOf(failures);
    return new PivotDeltaAudit(
        delta.pivotId(),
        result.isEmpty()
            ? PivotDeltaStatus.AWAITING_REVIEW
            : PivotDeltaStatus.DETERMINISTICALLY_REJECTED,
        sourceSignature,
        proposedSignature,
        result,
        details);
  }

  private static void auditObstructions(
      PivotDelta delta, PivotAuthorityContext authority, Set<String> failures) {
    if (delta.obstructionRefs().isEmpty()) {
      failures.add(UNKNOWN_OBSTRUCTION);
      return;
    }
    for (PivotObstructionRef reference : delta.obstructionRefs()) {
      PivotAuthorityContext.KnownObstruction known =
          authority.knownObstructions().get(reference.obstructionId());
      if (known == null || !known.reference().equals(reference) || !known.sourceArtifactLocated()) {
        failures.add(UNKNOWN_OBSTRUCTION);
        continue;
      }
      if (!authority.problemHash().equals(known.problemHash())
          || !authority.routeId().equals(reference.boundRouteId())
          || !authority.sourceStrategyId().equals(reference.boundStrategyId())
          || (reference.boundCanonicalTargetId() != null
              && !authority.activeCanonicalTargetIds().contains(
                  reference.boundCanonicalTargetId()))) {
        failures.add(OBSTRUCTION_SCOPE_MISMATCH);
      }
    }
  }

  private static void auditObjects(
      PivotDelta delta, PivotAuthorityContext authority, Set<String> failures) {
    for (MathematicalObjectChange change : delta.objectChanges()) {
      if (change.disposition() != PivotObjectDisposition.ADD
          && !authority.activeObjectIds().contains(change.oldObjectId())) {
        failures.add(NO_OBJECT_CHANGE);
      }
      if (change.disposition() == PivotObjectDisposition.ADD
          && authority.activeObjectIds().contains(change.newObjectId())) {
        failures.add(NO_OBJECT_CHANGE);
      }
    }
  }

  private static void auditClaims(
      PivotDelta delta, PivotAuthorityContext authority, Set<String> failures) {
    for (PivotClaimUseChange change : delta.claimUseChanges()) {
      if (change.action() == PivotClaimUsageAction.RETAIN_AS_VERIFIED_FACT
          && !authority.verifiedClaimIds().contains(change.claimId())) {
        failures.add(UNAUTHORIZED_CLAIM_RETIREMENT);
      }
      if (change.action() == PivotClaimUsageAction.RETIRE_FROM_ACTIVE_DEPENDENCY) {
        if (!authority.knownClaimIds().contains(change.claimId())
            || (authority.verifiedClaimIds().contains(change.claimId())
                && claimsMathematicalFalsity(change.reason()))) {
          failures.add(UNAUTHORIZED_CLAIM_RETIREMENT);
        }
      }
      if (change.action() == PivotClaimUsageAction.ADD_AS_PROPOSED_CLAIM
          && authority.knownClaimIds().contains(change.claimId())) {
        failures.add(UNAUTHORIZED_CLAIM_RETIREMENT);
      }
    }
  }

  private static void auditObligations(
      PivotDelta delta, PivotAuthorityContext authority, Set<String> failures) {
    for (PivotObligationChange change : delta.obligationChanges()) {
      if (change.action() != PivotObligationAction.ADD_NEW_OBLIGATION
          && !authority.knownObligationIds().contains(change.obligationId())) {
        failures.add(UNAUTHORIZED_OBLIGATION_CLOSURE);
      }
      if (change.action() == PivotObligationAction.ADD_NEW_OBLIGATION
          && authority.knownObligationIds().contains(change.obligationId())) {
        failures.add(NO_OBLIGATION_GRAPH_CHANGE);
      }
    }
  }

  private static void auditDeclaredTransformations(PivotDelta delta, Set<String> failures) {
    Set<PivotTransformationType> types = Set.copyOf(delta.transformationTypes());
    boolean objectChange =
        delta.objectChanges().stream()
            .anyMatch(change -> change.disposition() != PivotObjectDisposition.RETAIN);
    boolean targetChange =
        delta.obligationChanges().stream()
            .anyMatch(
                change ->
                    change.action() == PivotObligationAction.ADD_NEW_OBLIGATION
                        || change.action() == PivotObligationAction.RETIRE_FROM_STRATEGY_FOCUS);
    if ((types.contains(PivotTransformationType.OBJECT_REPLACEMENT)
            || types.contains(PivotTransformationType.AUXILIARY_OBJECT_INTRODUCTION))
        && !objectChange) {
      failures.add(NO_OBJECT_CHANGE);
    }
    if (types.contains(PivotTransformationType.TARGET_REFORMULATION) && !targetChange) {
      failures.add(NO_TARGET_CHANGE);
    }
    if ((types.contains(PivotTransformationType.DIRECTION_REVERSAL)
            || types.contains(PivotTransformationType.DUALIZATION)
            || types.contains(PivotTransformationType.REPRESENTATION_CHANGE))
        && delta.directionChanges().isEmpty()) {
      failures.add(NO_DIRECTION_CHANGE);
    }
    if (types.contains(PivotTransformationType.ASSUMPTION_CHANGE)
        && delta.assumptionChanges().isEmpty()) {
      failures.add(NO_ASSUMPTION_CHANGE);
    }
    if (types.contains(PivotTransformationType.DECOMPOSITION_CHANGE)
        && delta.obligationChanges().isEmpty()) {
      failures.add(NO_OBLIGATION_GRAPH_CHANGE);
    }
  }

  private static boolean explicitStateChange(PivotDelta delta) {
    return delta.objectChanges().stream()
            .anyMatch(change -> change.disposition() != PivotObjectDisposition.RETAIN)
        || !delta.directionChanges().isEmpty()
        || !delta.assumptionChanges().isEmpty()
        || !delta.claimUseChanges().isEmpty()
        || delta.obligationChanges().stream()
            .anyMatch(change -> change.action() != PivotObligationAction.RETAIN_AS_ACTIVE_FOCUS);
  }

  private static boolean isCoreIdeaAppendOnly(
      PivotDelta delta,
      PivotStructuralSignature sourceSignature,
      PivotStructuralSignature proposedSignature) {
    return sourceSignature.sameSemanticState(proposedSignature)
        && !delta.proposedStrategy().coreIdea().isBlank();
  }

  private static boolean claimsMathematicalFalsity(String reason) {
    String normalized = reason.toLowerCase(Locale.ROOT);
    return normalized.contains(" is false")
        || normalized.contains("invalid claim")
        || normalized.contains("refuted claim")
        || normalized.contains("命题为假")
        || normalized.contains("错误命题");
  }

  private static boolean selectedBinding(PivotDelta delta, PivotAuthorityContext authority) {
    for (PivotObstructionRef reference : delta.obstructionRefs()) {
      if (reference.boundCanonicalTargetId() != null
          && authority.selectedCanonicalTargetIds().contains(
              reference.boundCanonicalTargetId())) {
        return true;
      }
      if (reference.authority() == PivotEvidenceAuthority.BOTTLENECK_FAMILY
          && authority.selectedFamilyId() != null
          && authority.selectedFamilyId().equals(reference.obstructionId())) {
        return true;
      }
    }
    return false;
  }
}
