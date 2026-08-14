package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DesktopCrossRouteDuplicateObligationBlackBoxTest {
  private static final String NORMALIZED =
      "prove that every global inclusion-minimal hitting set contains only primes not exceeding a_1";

  @Test
  void crossRouteVariantsProduceOneSchedulableMathematicalTarget() throws Exception {
    ProofGraphStore graph =
        new ProofGraphStore(DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH);
    List<String> statements =
        List.of(
            "Prove that every global inclusion-minimal hitting set contains only primes not exceeding a1.",
            " Prove  that every global inclusion-minimal hitting set contains only primes not exceeding a_1. ",
            "Prove that every global inclusion-minimal hitting set contains only primes not exceeding $a_{1}$.",
            "Prove that every global inclusion-minimal hitting set contains only primes not exceeding a\u2081.",
            "Prove that every global inclusion-minimal hitting set contains only primes \u2264 a_1.");
    Set<String> dependencyPlans = new LinkedHashSet<>();
    for (int index = 0; index < statements.size(); index++) {
      String routeId = "route-" + (index + 1);
      String dependencyPlan = "plan-" + (index + 1);
      graph.addObligation(
          DesktopProofGraphIssue005BlackBoxSupport.obligation(
              "duplicate-target-" + index,
              routeId,
              statements.get(index),
              NORMALIZED,
              "global-hitting-set",
              dependencyPlan));
      dependencyPlans.add(
          CanonicalJson.stableHash(
              Map.of("route_id", routeId, "dependency_plan", dependencyPlan)));
    }

    int rawOpenObligations =
        (int)
            graph.obligations().stream()
                .map(ProofObligation::obligationId)
                .filter(id -> id.startsWith("duplicate-target-"))
                .count();
    int actualSchedulableWorkItems =
        DesktopProofGraphIssue005BlackBoxSupport.schedulableWorkItems(graph);

    System.out.println("CROSS ROUTE DUPLICATE OBLIGATION BASELINE");
    System.out.println("RAW_OPEN_OBLIGATIONS=" + rawOpenObligations);
    System.out.println("EXPECTED_CANONICAL_TARGETS=1");
    System.out.println("ACTUAL_SCHEDULABLE_WORK_ITEMS=" + actualSchedulableWorkItems);
    System.out.println("DEPENDENCY_PLAN_SIGNATURES=" + dependencyPlans.size());

    assertThat(rawOpenObligations).isEqualTo(5);
    assertThat(dependencyPlans).hasSize(5);
    assertThat(actualSchedulableWorkItems).isEqualTo(1);
  }
}
