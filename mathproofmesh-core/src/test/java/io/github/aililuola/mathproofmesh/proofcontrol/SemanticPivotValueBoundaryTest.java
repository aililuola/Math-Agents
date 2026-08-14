package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.SemanticPivotReviewDecision;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticPivotValueBoundaryTest {
  @Test
  void assumptionChangesRequireARealOneSidedOrTwoSidedDelta() {
    assertThatThrownBy(
            () -> new PivotAssumptionChange(null, null, "missing change", List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("old or new");
    assertThatThrownBy(
            () ->
                new PivotAssumptionChange(
                    "same assumption", "  same assumption  ", "paraphrase", List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("paraphrase");

    assertThat(new PivotAssumptionChange("old", null, "remove", null).oldAssumption())
        .isEqualTo("old");
    assertThat(new PivotAssumptionChange(null, "new", "add", List.of()).newAssumption())
        .isEqualTo("new");
    assertThat(
            new PivotAssumptionChange(
                    "old", "new", "replace", List.of("obstruction"))
                .evidenceRefs())
        .containsExactly("obstruction");
  }

  @Test
  void authorityContextCopiesNullAndNonNullTrustedCollections() {
    PivotAuthorityContext empty =
        new PivotAuthorityContext(
            "problem",
            "root",
            "route",
            "strategy",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            " ",
            null,
            false,
            true);
    assertThat(empty.knownObstructions()).isEmpty();
    assertThat(empty.activeObjectIds()).isEmpty();
    assertThat(empty.activeCanonicalTargetIds()).isEmpty();
    assertThat(empty.knownObligationIds()).isEmpty();
    assertThat(empty.verifiedClaimIds()).isEmpty();
    assertThat(empty.knownClaimIds()).isEmpty();
    assertThat(empty.permanentNegativeConflicts()).isEmpty();
    assertThat(empty.selectedFamilyId()).isNull();
    assertThat(empty.selectedCanonicalTargetIds()).isEmpty();

    PivotAuthorityContext populated = SemanticPivotTestFixtures.authority();
    assertThat(populated.knownObstructions()).containsKey(SemanticPivotTestFixtures.OBSTRUCTION_ID);
    assertThat(populated.activeObjectIds()).contains(SemanticPivotTestFixtures.OLD_OBJECT);
  }

  @Test
  void structuralSignatureFactoryNormalizesNullBlankAndCompiledGraphState() {
    PivotStructuralSignatureFactory factory = new PivotStructuralSignatureFactory();
    PivotStructuralSignature empty =
        factory.create(
            SemanticPivotTestFixtures.sourceStrategy(),
            null,
            null,
            null,
            null,
            null,
            null,
            "direction",
            null);
    assertThat(empty.activeObjectIds()).isEmpty();
    assertThat(empty.activeAssumptionHashes()).isEmpty();

    StrategyBlueprintCompiler.Node source =
        new StrategyBlueprintCompiler.Node(
            "node-source",
            ProofControlModels.BlueprintNodeKind.LEMMA,
            "  Source statement  ",
            "expected_lemmas",
            "prove source",
            0.9d);
    StrategyBlueprintCompiler.Node target =
        new StrategyBlueprintCompiler.Node(
            "node-target",
            ProofControlModels.BlueprintNodeKind.TARGET,
            "Target statement",
            "main_goal",
            "",
            1.0d);
    StrategyBlueprintCompiler.Edge edge =
        new StrategyBlueprintCompiler.Edge(
            "edge-1",
            source.id(),
            target.id(),
            "supports",
            List.of("source implies target"),
            false,
            "strategy");
    StrategyBlueprintCompiler.Blueprint blueprint =
        new StrategyBlueprintCompiler.Blueprint(
            "blueprint",
            SemanticPivotTestFixtures.SOURCE_ID,
            SemanticPivotTestFixtures.PROBLEM_HASH,
            List.of(source, target),
            List.of(edge),
            target.id(),
            List.of(target.id()),
            List.of(source.id()),
            List.of(),
            true,
            true,
            0.9d,
            "accepted");
    StrategyBlueprintCompiler.Compilation compilation =
        new StrategyBlueprintCompiler.Compilation(blueprint, List.of(), List.of());
    PivotStructuralSignature populated =
        factory.create(
            SemanticPivotTestFixtures.sourceStrategy(),
            Set.of(" object ", " "),
            Set.of(" target ", ""),
            Set.of(" assumption ", " "),
            Set.of(" claim ", ""),
            Set.of(" proposed ", " "),
            Set.of(" obligation ", ""),
            "direction",
            compilation);

    assertThat(populated.activeObjectIds()).containsExactly("object");
    assertThat(populated.activeCanonicalTargetIds()).containsExactly("target");
    assertThat(populated.blueprintStructureHash()).isNotEqualTo(empty.blueprintStructureHash());
  }

  @Test
  void applyPlanValidatesEachGateAndServerOwnedIdentity() {
    PivotDelta delta = SemanticPivotTestFixtures.validDelta();
    PivotDeltaAudit passed = passedAudit(delta.pivotId());
    SemanticPivotReviewDecision review =
        SemanticPivotTestFixtures.acceptedReview(delta).decisions().getFirst();
    SemanticPivotApplyPlan valid =
        new SemanticPivotApplyPlan(
            null,
            delta,
            passed,
            review,
            "proposer",
            "reviewer",
            List.of(SemanticPivotTestFixtures.NEW_OBLIGATION),
            delta.proposedStrategyId());
    assertThat(
            new SemanticPivotApplyPlan(
                    " ",
                    delta,
                    passed,
                    review,
                    "proposer",
                    "reviewer",
                    List.of(SemanticPivotTestFixtures.NEW_OBLIGATION),
                    delta.proposedStrategyId())
                .planId())
        .isEqualTo(valid.planId());
    assertThat(
            new SemanticPivotApplyPlan(
                    valid.planId(),
                    delta,
                    passed,
                    review,
                    "proposer",
                    "reviewer",
                    List.of(SemanticPivotTestFixtures.NEW_OBLIGATION),
                    delta.proposedStrategyId())
                .planId())
        .isEqualTo(valid.planId());
    assertThatThrownBy(
            () ->
                new SemanticPivotApplyPlan(
                    "model-plan",
                    delta,
                    passed,
                    review,
                    "proposer",
                    "reviewer",
                    List.of(),
                    delta.proposedStrategyId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("server-owned");

    assertInvalidPlan(delta, failedAudit(delta.pivotId()), review, "proposer", "reviewer", delta.proposedStrategyId());
    assertInvalidPlan(delta, passedAudit("other-pivot"), review, "proposer", "reviewer", delta.proposedStrategyId());
    SemanticPivotReviewDecision wrongReview =
        new SemanticPivotReviewDecision(
            "other-pivot",
            review.verdict(),
            review.confidence(),
            review.obstructionBindingValid(),
            review.rootGoalPreserved(),
            review.objectChangeCoherent(),
            review.targetChangeCoherent(),
            review.retainedClaimsCompatible(),
            review.newObligationsLoadBearing(),
            review.noAuthorityEscalation(),
            review.issues(),
            review.conciseFeedback());
    assertInvalidPlan(delta, passed, wrongReview, "proposer", "reviewer", delta.proposedStrategyId());
    assertInvalidPlan(delta, passed, review, "same-agent", "same-agent", delta.proposedStrategyId());
    assertInvalidPlan(delta, passed, review, "proposer", "reviewer", "other-epoch");
  }

  @Test
  void applyReceiptRejectsNegativeRoundsAndProviderOwnedIdentity() {
    PivotDelta delta = SemanticPivotTestFixtures.validDelta();
    assertThatThrownBy(
            () ->
                new SemanticPivotApplyReceipt(
                    null,
                    delta.pivotId(),
                    delta.structuralDeltaHash(),
                    delta.routeId(),
                    delta.sourceStrategyId(),
                    delta.proposedStrategyId(),
                    null,
                    null,
                    -1,
                    true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nonnegative");
    assertThatThrownBy(
            () ->
                new SemanticPivotApplyReceipt(
                    "model-receipt",
                    delta.pivotId(),
                    delta.structuralDeltaHash(),
                    delta.routeId(),
                    delta.sourceStrategyId(),
                    delta.proposedStrategyId(),
                    List.of(),
                    List.of(),
                    0,
                    true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("server-owned");
  }

  private static void assertInvalidPlan(
      PivotDelta delta,
      PivotDeltaAudit audit,
      SemanticPivotReviewDecision review,
      String proposer,
      String reviewer,
      String epoch) {
    assertThatThrownBy(
            () ->
                new SemanticPivotApplyPlan(
                    null, delta, audit, review, proposer, reviewer, List.of(), epoch))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("every gate");
  }

  private static PivotDeltaAudit passedAudit(String pivotId) {
    return new PivotDeltaAudit(
        pivotId,
        PivotDeltaStatus.AWAITING_REVIEW,
        SemanticPivotTestFixtures.sourceSignature(),
        SemanticPivotTestFixtures.proposedSignature(),
        List.of(),
        Map.of());
  }

  private static PivotDeltaAudit failedAudit(String pivotId) {
    return new PivotDeltaAudit(
        pivotId,
        PivotDeltaStatus.DETERMINISTICALLY_REJECTED,
        SemanticPivotTestFixtures.sourceSignature(),
        SemanticPivotTestFixtures.proposedSignature(),
        List.of("failure"),
        Map.of());
  }
}
