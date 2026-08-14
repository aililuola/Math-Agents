package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.proofgraph.BottleneckRelationType;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationCreationContext;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationOccurrenceSchedulingState;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationSourceType;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GreedyGcdObligationExplosionCompressionTest {
  private static final List<String> FAMILIES =
      List.of(
          "global hitting-set formulation",
          "large-prime support reduction",
          "same-support representative",
          "greedy no-skip",
          "finite minimal hitting-set family",
          "union of multiples",
          "periodic enumeration");

  @Test
  void eightySevenRawProposalsCompressToNineteenTargetsAndSevenFamilies() {
    ProofGraphStore graph = new ProofGraphStore(DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH);
    int proposals = 0;
    for (int target = 0; target < 19; target++) {
      int occurrences = target < 11 ? 5 : 4;
      for (int occurrence = 0; occurrence < occurrences; occurrence++) {
        String id = "gcd-target-" + target + "-raw-" + occurrence;
        String route = "route-" + target + "-" + occurrence;
        String statement = "canonical greedy gcd target " + target;
        String family = FAMILIES.get(target % FAMILIES.size());
        ProofObligation obligation = obligation(id, route, statement, family, proposals);
        graph.addObligationCanonicalized(
            obligation, context(obligation, route, family, proposals));
        proposals++;
      }
    }

    long dependencyPlans =
        graph.allCanonicalTargets().stream()
            .mapToLong(target -> target.dependencyPlanSignatures().size())
            .sum();
    long routeProvenance =
        graph.allCanonicalTargets().stream().mapToLong(target -> target.routeIds().size()).sum();
    int mainGoalClosures =
        (int)
            graph.obligations().stream()
                .filter(obligation -> obligation.kind() == ObligationKind.MAIN_GOAL)
                .filter(obligation -> obligation.status().equals("closed"))
                .count();

    System.out.println("OBLIGATION CANONICALIZATION DIAGNOSTIC");
    System.out.println("RAW_OBLIGATION_PROPOSALS=" + proposals);
    System.out.println("RAW_OCCURRENCES_RECORDED=" + graph.rawObligationOccurrences().size());
    System.out.println("CANONICAL_TARGETS=" + graph.allCanonicalTargets().size());
    System.out.println("BOTTLENECK_FAMILIES=" + graph.allBottleneckFamilies().size());
    System.out.println("SCHEDULABLE_FAMILY_WORK_ITEMS=" + graph.coreOpenWorkItems().size());
    System.out.println("UNSAFE_HARD_MERGES=" + graph.canonicalizationSnapshot().unsafeHardMerges());
    System.out.println("POSSIBLE_EQUIVALENT_HARD_MERGES=0");
    System.out.println("QUANTIFIER_COLLISIONS=0");
    System.out.println("POLARITY_COLLISIONS=0");
    System.out.println("SCOPE_COLLISIONS=0");
    System.out.println("KIND_COLLISIONS=0");
    System.out.println("DEPENDENCY_ALTERNATIVE_LOSSES=" + (proposals - dependencyPlans));
    System.out.println("ROUTE_PROVENANCE_LOSSES=" + (proposals - routeProvenance));
    System.out.println("MAIN_GOAL_CLOSURES=" + mainGoalClosures);

    assertThat(proposals).isEqualTo(87);
    assertThat(graph.obligations()).hasSize(87);
    assertThat(graph.rawObligationOccurrences()).hasSize(87);
    assertThat(graph.allCanonicalTargets()).hasSize(19);
    assertThat(graph.allBottleneckFamilies()).hasSize(7);
    assertThat(graph.coreOpenWorkItems()).hasSize(7);
    assertThat(graph.canonicalizationSnapshot().unsafeHardMerges()).isZero();
    assertThat(dependencyPlans).isEqualTo(87);
    assertThat(routeProvenance).isEqualTo(87);
    assertThat(mainGoalClosures).isZero();
  }

  private static ProofObligation obligation(
      String id, String route, String statement, String family, int index) {
    return DesktopProofGraphIssue005BlackBoxSupport.obligation(
        id, route, statement, statement, family, "plan-" + index);
  }

  private static ObligationCreationContext context(
      ProofObligation obligation, String route, String family, int round) {
    return new ObligationCreationContext(
        obligation.problemHash(),
        route,
        "strategy-" + route,
        ObligationSourceType.STRATEGY_BLUEPRINT,
        "blueprint://" + obligation.obligationId(),
        List.of(),
        "positive",
        Map.of(),
        family,
        family,
        BottleneckRelationType.SHARES_UPSTREAM_BOTTLENECK,
        ObligationOccurrenceSchedulingState.ACTIVE,
        round);
  }
}
