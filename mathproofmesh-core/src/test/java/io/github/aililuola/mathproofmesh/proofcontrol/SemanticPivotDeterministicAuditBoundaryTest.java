package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticPivotDeterministicAuditBoundaryTest {
  private final SemanticPivotDeterministicAuditor auditor =
      new SemanticPivotDeterministicAuditor();

  @Test
  void everyProblemRouteStrategyAndSignatureBindingFailsClosed() {
    PivotDelta valid = SemanticPivotTestFixtures.validDelta();
    PivotAuthorityContext authority = SemanticPivotTestFixtures.authority();

    assertFailure(
        valid,
        SemanticPivotTestFixtures.sourceSignature(),
        SemanticPivotTestFixtures.proposedSignature(),
        withAuthorityScope(
            authority,
            "other-problem",
            authority.rootGoalHash(),
            authority.routeId(),
            authority.sourceStrategyId()),
        SemanticPivotDeterministicAuditor.OBSTRUCTION_SCOPE_MISMATCH);
    assertFailure(
        valid,
        SemanticPivotTestFixtures.sourceSignature(),
        SemanticPivotTestFixtures.proposedSignature(),
        withAuthorityScope(
            authority,
            authority.problemHash(),
            authority.rootGoalHash(),
            "other-route",
            authority.sourceStrategyId()),
        SemanticPivotDeterministicAuditor.OBSTRUCTION_SCOPE_MISMATCH);
    assertFailure(
        valid,
        SemanticPivotTestFixtures.sourceSignature(),
        SemanticPivotTestFixtures.proposedSignature(),
        withAuthorityScope(
            authority,
            authority.problemHash(),
            authority.rootGoalHash(),
            authority.routeId(),
            "other-source"),
        SemanticPivotDeterministicAuditor.OBSTRUCTION_SCOPE_MISMATCH);
    assertFailure(
        valid,
        SemanticPivotTestFixtures.sourceSignature(),
        SemanticPivotTestFixtures.proposedSignature(),
        withAuthorityScope(
            authority,
            authority.problemHash(),
            "other-root",
            authority.routeId(),
            authority.sourceStrategyId()),
        SemanticPivotDeterministicAuditor.ROOT_GOAL_MISMATCH);
    assertFailure(
        valid,
        withStrategyId(SemanticPivotTestFixtures.sourceSignature(), "wrong-source-signature"),
        SemanticPivotTestFixtures.proposedSignature(),
        authority,
        SemanticPivotDeterministicAuditor.OBSTRUCTION_SCOPE_MISMATCH);
    assertFailure(
        valid,
        SemanticPivotTestFixtures.sourceSignature(),
        withStrategyId(SemanticPivotTestFixtures.proposedSignature(), "wrong-proposed-signature"),
        authority,
        SemanticPivotDeterministicAuditor.OBSTRUCTION_SCOPE_MISMATCH);
  }

  @Test
  void obstructionBindingsRequireLocatedExactAndActiveTrustedArtifacts() {
    PivotDelta valid = SemanticPivotTestFixtures.validDelta();
    PivotAuthorityContext authority = SemanticPivotTestFixtures.authority();

    assertFailure(
        withObstructions(valid, List.of()),
        SemanticPivotTestFixtures.sourceSignature(),
        SemanticPivotTestFixtures.proposedSignature(),
        authority,
        SemanticPivotDeterministicAuditor.UNKNOWN_OBSTRUCTION);

    PivotAuthorityContext unlocated =
        withObstructions(
            authority,
            Map.of(
                SemanticPivotTestFixtures.OBSTRUCTION_ID,
                new PivotAuthorityContext.KnownObstruction(
                    SemanticPivotTestFixtures.obstruction(),
                    SemanticPivotTestFixtures.PROBLEM_HASH,
                    false)));
    assertFailure(
        valid,
        SemanticPivotTestFixtures.sourceSignature(),
        SemanticPivotTestFixtures.proposedSignature(),
        unlocated,
        SemanticPivotDeterministicAuditor.UNKNOWN_OBSTRUCTION);

    PivotObstructionRef unboundTarget =
        new PivotObstructionRef(
            SemanticPivotTestFixtures.OBSTRUCTION_ID,
            PivotEvidenceAuthority.VERIFIED_COUNTEREXAMPLE,
            "attempt-artifact://counterexample-1",
            SemanticPivotTestFixtures.ROUTE_ID,
            SemanticPivotTestFixtures.SOURCE_ID,
            null,
            "statement-hash");
    PivotDelta targetless = withObstructions(valid, List.of(unboundTarget));
    PivotAuthorityContext targetlessAuthority =
        withObstructions(
            authority,
            Map.of(
                unboundTarget.obstructionId(),
                new PivotAuthorityContext.KnownObstruction(
                    unboundTarget, SemanticPivotTestFixtures.PROBLEM_HASH, true)));
    assertThat(audit(targetless, targetlessAuthority).failureCodes())
        .doesNotContain(SemanticPivotDeterministicAuditor.OBSTRUCTION_SCOPE_MISMATCH);

    PivotObstructionRef inactiveTarget =
        new PivotObstructionRef(
            SemanticPivotTestFixtures.OBSTRUCTION_ID,
            PivotEvidenceAuthority.VERIFIED_COUNTEREXAMPLE,
            "attempt-artifact://counterexample-1",
            SemanticPivotTestFixtures.ROUTE_ID,
            SemanticPivotTestFixtures.SOURCE_ID,
            "inactive-target",
            "statement-hash");
    PivotDelta inactive = withObstructions(valid, List.of(inactiveTarget));
    PivotAuthorityContext inactiveAuthority =
        withObstructions(
            authority,
            Map.of(
                inactiveTarget.obstructionId(),
                new PivotAuthorityContext.KnownObstruction(
                    inactiveTarget, SemanticPivotTestFixtures.PROBLEM_HASH, true)));
    assertFailure(
        inactive,
        SemanticPivotTestFixtures.sourceSignature(),
        SemanticPivotTestFixtures.proposedSignature(),
        inactiveAuthority,
        SemanticPivotDeterministicAuditor.OBSTRUCTION_SCOPE_MISMATCH);
  }

  @Test
  void objectClaimAndObligationChangesStayWithinTrustedState() {
    PivotDelta valid = SemanticPivotTestFixtures.validDelta();
    PivotAuthorityContext authority = SemanticPivotTestFixtures.authority();
    MathematicalObjectChange unknownReplacement =
        new MathematicalObjectChange(
            "unknown-old",
            "unknown",
            PivotObjectDisposition.REPLACE,
            "replacement",
            "replacement object",
            "retain the valid part",
            List.of());
    assertFailure(
        withChanges(
            valid,
            valid.transformationTypes(),
            List.of(unknownReplacement),
            valid.directionChanges(),
            valid.assumptionChanges(),
            valid.claimUseChanges(),
            valid.obligationChanges()),
        SemanticPivotDeterministicAuditor.NO_OBJECT_CHANGE);

    MathematicalObjectChange duplicateAddition =
        new MathematicalObjectChange(
            null,
            null,
            PivotObjectDisposition.ADD,
            SemanticPivotTestFixtures.OLD_OBJECT,
            "already active",
            null,
            List.of());
    assertFailure(
        withChanges(
            valid,
            List.of(PivotTransformationType.AUXILIARY_OBJECT_INTRODUCTION),
            List.of(duplicateAddition),
            List.of(),
            List.of(),
            List.of(),
            List.of()),
        SemanticPivotDeterministicAuditor.NO_OBJECT_CHANGE);

    List<PivotClaimUseChange> invalidClaims =
        List.of(
            new PivotClaimUseChange(
                "unknown-fact",
                "hash",
                PivotClaimUsageAction.RETAIN_AS_VERIFIED_FACT,
                "unknown fact"),
            new PivotClaimUseChange(
                "unknown-retirement",
                "hash",
                PivotClaimUsageAction.RETIRE_FROM_ACTIVE_DEPENDENCY,
                "not used by this strategy"),
            new PivotClaimUseChange(
                SemanticPivotTestFixtures.VERIFIED_CLAIM,
                "hash",
                PivotClaimUsageAction.RETIRE_FROM_ACTIVE_DEPENDENCY,
                "This is an invalid claim."),
            new PivotClaimUseChange(
                "known-proposed-claim",
                "hash",
                PivotClaimUsageAction.ADD_AS_PROPOSED_CLAIM,
                "duplicate proposal"));
    PivotAuthorityContext claimsAuthority =
        new PivotAuthorityContext(
            authority.problemHash(),
            authority.rootGoalHash(),
            authority.routeId(),
            authority.sourceStrategyId(),
            authority.knownObstructions(),
            authority.activeObjectIds(),
            authority.activeCanonicalTargetIds(),
            authority.knownObligationIds(),
            authority.verifiedClaimIds(),
            Set.of(SemanticPivotTestFixtures.VERIFIED_CLAIM, "known-proposed-claim"),
            authority.permanentNegativeConflicts(),
            authority.selectedFamilyId(),
            authority.selectedCanonicalTargetIds(),
            authority.focusedRecovery(),
            authority.capacityAvailable());
    for (PivotClaimUseChange invalidClaim : invalidClaims) {
      assertFailure(
          withChanges(
              valid,
              valid.transformationTypes(),
              valid.objectChanges(),
              valid.directionChanges(),
              valid.assumptionChanges(),
              List.of(invalidClaim),
              valid.obligationChanges()),
          SemanticPivotTestFixtures.sourceSignature(),
          SemanticPivotTestFixtures.proposedSignature(),
          claimsAuthority,
          SemanticPivotDeterministicAuditor.UNAUTHORIZED_CLAIM_RETIREMENT);
    }

    PivotObligationChange duplicateObligation =
        new PivotObligationChange(
            SemanticPivotTestFixtures.OLD_OBLIGATION,
            null,
            PivotObligationAction.ADD_NEW_OBLIGATION,
            "duplicate target",
            ObligationKind.SUBGOAL,
            List.of(),
            List.of(),
            "must not duplicate a known obligation");
    assertFailure(
        withChanges(
            valid,
            valid.transformationTypes(),
            valid.objectChanges(),
            valid.directionChanges(),
            valid.assumptionChanges(),
            valid.claimUseChanges(),
            List.of(duplicateObligation)),
        SemanticPivotDeterministicAuditor.NO_OBLIGATION_GRAPH_CHANGE);
  }

  @Test
  void declaredTransformationsAndAuthorityGatesCannotBeSatisfiedByLabels() {
    PivotDelta valid = SemanticPivotTestFixtures.validDelta();
    PivotDelta labelsOnly =
        withChanges(
            valid,
            List.of(
                PivotTransformationType.OBJECT_REPLACEMENT,
                PivotTransformationType.AUXILIARY_OBJECT_INTRODUCTION,
                PivotTransformationType.TARGET_REFORMULATION,
                PivotTransformationType.DIRECTION_REVERSAL,
                PivotTransformationType.DUALIZATION,
                PivotTransformationType.REPRESENTATION_CHANGE,
                PivotTransformationType.ASSUMPTION_CHANGE,
                PivotTransformationType.DECOMPOSITION_CHANGE),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    assertThat(audit(labelsOnly, SemanticPivotTestFixtures.authority()).failureCodes())
        .contains(
            SemanticPivotDeterministicAuditor.NO_OBJECT_CHANGE,
            SemanticPivotDeterministicAuditor.NO_TARGET_CHANGE,
            SemanticPivotDeterministicAuditor.NO_DIRECTION_CHANGE,
            SemanticPivotDeterministicAuditor.NO_ASSUMPTION_CHANGE,
            SemanticPivotDeterministicAuditor.NO_OBLIGATION_GRAPH_CHANGE,
            SemanticPivotDeterministicAuditor.EMPTY_SEMANTIC_DELTA);

    PivotAuthorityContext permanentConflict =
        withGates(
            SemanticPivotTestFixtures.authority(),
            Set.of("permanent-negative"),
            null,
            Set.of(),
            false,
            true);
    assertFailure(
        valid,
        SemanticPivotTestFixtures.sourceSignature(),
        SemanticPivotTestFixtures.proposedSignature(),
        permanentConflict,
        SemanticPivotDeterministicAuditor.PERMANENT_NEGATIVE_CONFLICT);

    PivotAuthorityContext unboundFocused =
        withGates(
            SemanticPivotTestFixtures.authority(), Set.of(), null, Set.of(), true, true);
    assertFailure(
        valid,
        SemanticPivotTestFixtures.sourceSignature(),
        SemanticPivotTestFixtures.proposedSignature(),
        unboundFocused,
        SemanticPivotDeterministicAuditor.FOCUSED_RECOVERY_BINDING_MISMATCH);

    PivotAuthorityContext selectedTarget =
        withGates(
            SemanticPivotTestFixtures.authority(),
            Set.of(),
            null,
            Set.of(SemanticPivotTestFixtures.OLD_TARGET),
            true,
            true);
    assertThat(audit(valid, selectedTarget).failureCodes())
        .doesNotContain(SemanticPivotDeterministicAuditor.FOCUSED_RECOVERY_BINDING_MISMATCH);

    PivotAuthorityContext noCapacity =
        withGates(
            SemanticPivotTestFixtures.authority(), Set.of(), null, Set.of(), false, false);
    assertFailure(
        valid,
        SemanticPivotTestFixtures.sourceSignature(),
        SemanticPivotTestFixtures.proposedSignature(),
        noCapacity,
        SemanticPivotDeterministicAuditor.CAPACITY_OR_QUOTA_BLOCK);
  }

  @Test
  void eachExplicitChangeCategoryCanProduceARealDelta() {
    PivotDelta valid = SemanticPivotTestFixtures.validDelta();
    List<PivotDelta> deltas =
        List.of(
            withChanges(
                valid,
                List.of(PivotTransformationType.REPRESENTATION_CHANGE),
                List.of(),
                List.of(SemanticPivotTestFixtures.directionChange()),
                List.of(),
                List.of(),
                List.of()),
            withChanges(
                valid,
                List.of(PivotTransformationType.ASSUMPTION_CHANGE),
                List.of(),
                List.of(),
                List.of(
                    new PivotAssumptionChange(
                        "prefix monotonicity",
                        "global minimality",
                        "the obstruction changes the working assumption",
                        List.of(SemanticPivotTestFixtures.OBSTRUCTION_ID))),
                List.of(),
                List.of()),
            withChanges(
                valid,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                    new PivotClaimUseChange(
                        "new-proposed-claim",
                        "new-claim-hash",
                        PivotClaimUsageAction.ADD_AS_PROPOSED_CLAIM,
                        "new claim requires ordinary review")),
                List.of()),
            withChanges(
                valid,
                List.of(PivotTransformationType.DECOMPOSITION_CHANGE),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(valid.obligationChanges().getFirst())));

    for (PivotDelta delta : deltas) {
      assertThat(audit(delta, SemanticPivotTestFixtures.authority()).failureCodes())
          .doesNotContain(SemanticPivotDeterministicAuditor.EMPTY_SEMANTIC_DELTA);
    }
  }

  private PivotDeltaAudit audit(PivotDelta delta, PivotAuthorityContext authority) {
    return auditor.audit(
        delta,
        SemanticPivotTestFixtures.sourceSignature(),
        SemanticPivotTestFixtures.proposedSignature(),
        authority);
  }

  private void assertFailure(PivotDelta delta, String code) {
    assertFailure(
        delta,
        SemanticPivotTestFixtures.sourceSignature(),
        SemanticPivotTestFixtures.proposedSignature(),
        SemanticPivotTestFixtures.authority(),
        code);
  }

  private void assertFailure(
      PivotDelta delta,
      PivotStructuralSignature source,
      PivotStructuralSignature proposed,
      PivotAuthorityContext authority,
      String code) {
    assertThat(auditor.audit(delta, source, proposed, authority).failureCodes()).contains(code);
  }

  private static PivotDelta withObstructions(
      PivotDelta source, List<PivotObstructionRef> obstructions) {
    return PivotDelta.create(
        source.problemHash(),
        source.rootGoalHash(),
        source.routeId(),
        source.sourceStrategyId(),
        source.transformationTypes(),
        obstructions,
        source.objectChanges(),
        source.directionChanges(),
        source.assumptionChanges(),
        source.claimUseChanges(),
        source.obligationChanges(),
        source.proposedStrategy(),
        source.rationale());
  }

  private static PivotDelta withChanges(
      PivotDelta source,
      List<PivotTransformationType> transformations,
      List<MathematicalObjectChange> objects,
      List<PivotDirectionChange> directions,
      List<PivotAssumptionChange> assumptions,
      List<PivotClaimUseChange> claims,
      List<PivotObligationChange> obligations) {
    return PivotDelta.create(
        source.problemHash(),
        source.rootGoalHash(),
        source.routeId(),
        source.sourceStrategyId(),
        transformations,
        source.obstructionRefs(),
        objects,
        directions,
        assumptions,
        claims,
        obligations,
        source.proposedStrategy(),
        source.rationale());
  }

  private static PivotStructuralSignature withStrategyId(
      PivotStructuralSignature source, String strategyId) {
    return new PivotStructuralSignature(
        strategyId,
        source.activeObjectIds(),
        source.activeCanonicalTargetIds(),
        source.activeAssumptionHashes(),
        source.retainedVerifiedClaimIds(),
        source.proposedClaimHashes(),
        source.activeObligationSignatures(),
        source.directionSignature(),
        source.blueprintStructureHash());
  }

  private static PivotAuthorityContext withObstructions(
      PivotAuthorityContext source,
      Map<String, PivotAuthorityContext.KnownObstruction> obstructions) {
    return new PivotAuthorityContext(
        source.problemHash(),
        source.rootGoalHash(),
        source.routeId(),
        source.sourceStrategyId(),
        obstructions,
        source.activeObjectIds(),
        source.activeCanonicalTargetIds(),
        source.knownObligationIds(),
        source.verifiedClaimIds(),
        source.knownClaimIds(),
        source.permanentNegativeConflicts(),
        source.selectedFamilyId(),
        source.selectedCanonicalTargetIds(),
        source.focusedRecovery(),
        source.capacityAvailable());
  }

  private static PivotAuthorityContext withAuthorityScope(
      PivotAuthorityContext source,
      String problemHash,
      String rootGoalHash,
      String routeId,
      String sourceStrategyId) {
    return new PivotAuthorityContext(
        problemHash,
        rootGoalHash,
        routeId,
        sourceStrategyId,
        source.knownObstructions(),
        source.activeObjectIds(),
        source.activeCanonicalTargetIds(),
        source.knownObligationIds(),
        source.verifiedClaimIds(),
        source.knownClaimIds(),
        source.permanentNegativeConflicts(),
        source.selectedFamilyId(),
        source.selectedCanonicalTargetIds(),
        source.focusedRecovery(),
        source.capacityAvailable());
  }

  private static PivotAuthorityContext withGates(
      PivotAuthorityContext source,
      Set<String> negativeConflicts,
      String selectedFamilyId,
      Set<String> selectedTargets,
      boolean focusedRecovery,
      boolean capacityAvailable) {
    return new PivotAuthorityContext(
        source.problemHash(),
        source.rootGoalHash(),
        source.routeId(),
        source.sourceStrategyId(),
        source.knownObstructions(),
        source.activeObjectIds(),
        source.activeCanonicalTargetIds(),
        source.knownObligationIds(),
        source.verifiedClaimIds(),
        source.knownClaimIds(),
        negativeConflicts,
        selectedFamilyId,
        selectedTargets,
        focusedRecovery,
        capacityAvailable);
  }
}
