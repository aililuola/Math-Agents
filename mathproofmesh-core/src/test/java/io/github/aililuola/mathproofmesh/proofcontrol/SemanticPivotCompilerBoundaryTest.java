package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.SemanticPivotProposal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticPivotCompilerBoundaryTest {
  private final SemanticPivotCompiler compiler = new SemanticPivotCompiler();

  @Test
  void compilesEveryTypedDraftUsingOnlyTrustedObstructionAuthority() {
    SemanticPivotProposal proposal = fullProposal(null, null, "subgoal");
    PivotDelta compiled =
        compiler.compile(
            proposal,
            Map.of(
                SemanticPivotTestFixtures.OBSTRUCTION_ID,
                SemanticPivotTestFixtures.obstruction()));

    assertThat(compiled.transformationTypes())
        .containsExactly(
            PivotTransformationType.OBJECT_REPLACEMENT,
            PivotTransformationType.REPRESENTATION_CHANGE,
            PivotTransformationType.ASSUMPTION_CHANGE);
    assertThat(compiled.objectChanges()).hasSize(1);
    assertThat(compiled.directionChanges()).hasSize(1);
    assertThat(compiled.assumptionChanges()).hasSize(1);
    assertThat(compiled.claimUseChanges()).hasSize(1);
    assertThat(compiled.obligationChanges()).hasSize(2);
    assertThat(compiled.obligationChanges().getLast().proposedKind())
        .isEqualTo(io.github.aililuola.mathproofmesh.contract.ObligationKind.SUBGOAL);
  }

  @Test
  void rejectsProviderOwnedIdentityUnknownObstructionsAndEnums() {
    assertThatThrownBy(() -> compiler.compile(fullProposal("model-id", null, "subgoal"), Map.of()))
        .isInstanceOf(PivotCompilationException.class)
        .hasMessageContaining("server-owned");
    assertThatThrownBy(() -> compiler.compile(fullProposal(null, "model-hash", "subgoal"), Map.of()))
        .isInstanceOf(PivotCompilationException.class)
        .hasMessageContaining("server-owned");
    assertThatThrownBy(() -> compiler.compile(fullProposal(null, null, "subgoal"), null))
        .isInstanceOf(PivotCompilationException.class)
        .hasMessageContaining("unknown obstruction");

    SemanticPivotProposal unknownTransformation =
        proposalWithEnums(List.of("not-a-transformation"), "replace", "retain_as_verified_fact", "add_new_obligation");
    assertUnknownEnum(unknownTransformation, "transformation type");
    assertUnknownEnum(
        proposalWithEnums(List.of("object_replacement"), "not-an-object", "retain_as_verified_fact", "add_new_obligation"),
        "object disposition");
    assertUnknownEnum(
        proposalWithEnums(List.of("object_replacement"), "replace", "not-a-claim-action", "add_new_obligation"),
        "claim use action");
    assertUnknownEnum(
        proposalWithEnums(List.of("object_replacement"), "replace", "retain_as_verified_fact", "not-an-obligation-action"),
        "obligation action");
  }

  private void assertUnknownEnum(SemanticPivotProposal proposal, String field) {
    assertThatThrownBy(
            () ->
                compiler.compile(
                    proposal,
                    Map.of(
                        SemanticPivotTestFixtures.OBSTRUCTION_ID,
                        SemanticPivotTestFixtures.obstruction())))
        .isInstanceOf(PivotCompilationException.class)
        .hasMessageContaining(field);
  }

  private static SemanticPivotProposal fullProposal(
      String claimedPivotId, String claimedHash, String proposedKind) {
    return new SemanticPivotProposal(
        "proposal-1",
        "proposer",
        SemanticPivotTestFixtures.PROBLEM_HASH,
        SemanticPivotTestFixtures.ROOT_HASH,
        SemanticPivotTestFixtures.ROUTE_ID,
        SemanticPivotTestFixtures.SOURCE_ID,
        List.of("object_replacement", "representation_change", "assumption_change"),
        List.of(SemanticPivotTestFixtures.OBSTRUCTION_ID),
        List.of(
            new SemanticPivotProposal.ObjectChangeDraft(
                SemanticPivotTestFixtures.OLD_OBJECT,
                "old object",
                "replace",
                SemanticPivotTestFixtures.NEW_OBJECT,
                "new object",
                "retain the support formulation",
                List.of(SemanticPivotTestFixtures.OBSTRUCTION_ID))),
        List.of(
            new SemanticPivotProposal.DirectionChangeDraft(
                "prefix direction",
                "global direction",
                "the exact obstruction changes direction",
                List.of(SemanticPivotTestFixtures.OBSTRUCTION_ID))),
        List.of(
            new SemanticPivotProposal.AssumptionChangeDraft(
                "prefix monotonicity",
                "global minimality",
                "replace the refuted assumption",
                List.of(SemanticPivotTestFixtures.OBSTRUCTION_ID))),
        List.of(
            new SemanticPivotProposal.ClaimUseChangeDraft(
                SemanticPivotTestFixtures.VERIFIED_CLAIM,
                "verified-claim-hash",
                "retain_as_verified_fact",
                "the verified fact remains usable")),
        List.of(
            new SemanticPivotProposal.ObligationChangeDraft(
                SemanticPivotTestFixtures.OLD_OBLIGATION,
                SemanticPivotTestFixtures.OLD_TARGET,
                "retire_from_strategy_focus",
                null,
                null,
                List.of(),
                List.of(),
                "retire only from strategy focus"),
            new SemanticPivotProposal.ObligationChangeDraft(
                SemanticPivotTestFixtures.NEW_OBLIGATION,
                null,
                "add_new_obligation",
                "Prove the global large-prime reduction.",
                proposedKind,
                List.of("p is large"),
                List.of(),
                "new load-bearing target")),
        SemanticPivotTestFixtures.proposedStrategy(),
        "Apply a bounded semantic transition.",
        claimedPivotId,
        claimedHash);
  }

  private static SemanticPivotProposal proposalWithEnums(
      List<String> transformations,
      String objectDisposition,
      String claimAction,
      String obligationAction) {
    SemanticPivotProposal full = fullProposal(null, null, "subgoal");
    return new SemanticPivotProposal(
        full.proposalId(),
        full.proposerAgentId(),
        full.problemHash(),
        full.rootGoalHash(),
        full.routeId(),
        full.sourceStrategyId(),
        transformations,
        full.obstructionIds(),
        List.of(
            new SemanticPivotProposal.ObjectChangeDraft(
                SemanticPivotTestFixtures.OLD_OBJECT,
                "old object",
                objectDisposition,
                SemanticPivotTestFixtures.NEW_OBJECT,
                "new object",
                "retain the support formulation",
                List.of())),
        full.directionChanges(),
        full.assumptionChanges(),
        List.of(
            new SemanticPivotProposal.ClaimUseChangeDraft(
                SemanticPivotTestFixtures.VERIFIED_CLAIM,
                "hash",
                claimAction,
                "bounded claim use")),
        List.of(
            new SemanticPivotProposal.ObligationChangeDraft(
                SemanticPivotTestFixtures.NEW_OBLIGATION,
                null,
                obligationAction,
                "new obligation",
                "subgoal",
                List.of(),
                List.of(),
                "bounded obligation change")),
        full.proposedStrategy(),
        full.rationale(),
        null,
        null);
  }
}
