package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ProofGraphCanonicalControlBoundaryTest {
  @Test
  void mainGoalClosureFiltersUnrelatedTargetsAndKeepsTheAvailableFamilyRepresentative() {
    ProofGraphStore graph = graph();
    ProofObligation outside = add(graph, "outside", "route-out", "shared-family");
    ProofObligation core = add(graph, "core", "route-core", "shared-family");
    add(graph, "independent", "route-other", "independent-family");
    graph.addRootGoalObligation(rootDependingOn(core.obligationId()));

    List<ProofGraphWorkItem> workItems = graph.coreOpenWorkItems();
    ProofGraphWorkItem familyItem =
        workItems.stream()
            .filter(item -> item.scope() == ProofTaskScope.BOTTLENECK_FAMILY)
            .findFirst()
            .orElseThrow();

    String coreCanonical =
        graph.canonicalTargetForObligation(core.obligationId())
            .orElseThrow()
            .canonicalTargetId();
    String outsideCanonical =
        graph.canonicalTargetForObligation(outside.obligationId())
            .orElseThrow()
            .canonicalTargetId();
    assertThat(familyItem.canonicalTargetIds()).containsExactly(coreCanonical);
    assertThat(familyItem.representativeCanonicalTargetId()).isEqualTo(coreCanonical);
    assertThat(familyItem.canonicalTargetIds()).doesNotContain(outsideCanonical);
    assertThat(workItems)
        .noneMatch(
            item ->
                item.canonicalTargetIds().stream()
                    .anyMatch(
                        id ->
                            graph.representativeStatement(id)
                                .contains("independent")));
  }

  @Test
  void mixedFamilyRemainsSchedulableButFullyRefutedFamilyDoesNot() {
    ProofGraphStore graph = graph();
    ProofObligation original = add(graph, "mixed-a", "route-a", "mixed-family");
    ProofObligation alias =
        ObligationCanonicalizationTestFixtures.obligation(
            "mixed-b",
            "route-b",
            original.statement(),
            original.normalizedStatement(),
            "mixed-family");
    graph.addObligationCanonicalized(
        alias,
        ObligationCanonicalizationTestFixtures.context(
            alias,
            "route-b",
            "mixed-family",
            List.of("global"),
            "positive",
            Map.of(),
            1));

    graph.refuteObligation(original.obligationId(), null);
    var fact =
        ObligationCanonicalizationTestFixtures.verifiedFact(
            "mixed-fact", "A verified local fact closes one occurrence.");
    graph.addClaimNode(fact);
    graph.closeObligation(alias.obligationId(), fact.messageId(), 1.0d);
    String canonicalId =
        graph.canonicalTargetForObligation(original.obligationId())
            .orElseThrow()
            .canonicalTargetId();
    assertThat(graph.canonicalStatus(canonicalId)).isEqualTo(CanonicalObligationStatus.MIXED);
    assertThat(graph.activeBottleneckFamilies()).hasSize(1);

    graph.refuteObligation(alias.obligationId(), null);
    assertThat(graph.canonicalStatus(canonicalId)).isEqualTo(CanonicalObligationStatus.REFUTED);
    assertThat(graph.activeBottleneckFamilies()).isEmpty();
    assertThat(graph.canonicalOpenTargets()).isEmpty();
  }

  private static ProofGraphStore graph() {
    return new ProofGraphStore(
        ObligationCanonicalizationTestFixtures.PROBLEM_HASH, ProofGraphPolicy.defaults());
  }

  private static ProofObligation add(
      ProofGraphStore graph, String id, String routeId, String family) {
    ProofObligation obligation =
        ObligationCanonicalizationTestFixtures.obligation(
            id,
            routeId,
            "Prove " + id + " target.",
            "prove " + id + " target",
            family);
    graph.addObligationCanonicalized(
        obligation,
        ObligationCanonicalizationTestFixtures.context(
            obligation,
            routeId,
            family,
            List.of("global"),
            "positive",
            Map.of(),
            0));
    return obligation;
  }

  private static ProofObligation rootDependingOn(String dependencyId) {
    return new ProofObligation(
        List.of(),
        1.0d,
        "",
        List.of(dependencyId),
        List.of(),
        List.of(),
        null,
        ObligationKind.MAIN_GOAL,
        "prove the immutable main goal",
        "main-goal",
        1.0d,
        ObligationCanonicalizationTestFixtures.PROBLEM_HASH,
        List.of(),
        List.of("route-main"),
        "Prove the immutable main goal.",
        "open");
  }
}
