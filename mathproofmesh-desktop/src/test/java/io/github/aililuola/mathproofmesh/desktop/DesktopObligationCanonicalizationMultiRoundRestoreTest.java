package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.proofgraph.BottleneckRelationType;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationCreationContext;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationOccurrenceSchedulingState;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationSourceType;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import io.github.aililuola.mathproofmesh.proofgraph.ProofTaskScope;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopObligationCanonicalizationMultiRoundRestoreTest {
  @Test
  void twentyRoundsPreserveCanonicalFamiliesAlternativesAndTaskLeasesAcrossCurrentRestore(
      @TempDir Path directory) throws Exception {
    DesktopResearchCheckpointBlackBoxHarness harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-canonical-multiround",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused");
    int proposals = 0;
    int independentAdmissions = 0;
    int postRestoreCanonicalLosses = 0;
    int postRestoreFamilyLosses = 0;
    Set<String> canonicalBeforeRestore = Set.of();
    Set<String> familiesBeforeRestore = Set.of();
    try {
      harness.prepareProductionRoute();
      DesktopProofGraphIssue005BlackBoxSupport.replaceGraph(
          harness, new ProofGraphStore(DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH));
      for (int round = 0; round < 20; round++) {
        ProofGraphStore graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
        for (int variant = 0; variant < 3; variant++) {
          add(graph, round, "a-" + variant, "target a", "family-a", proposals++);
        }
        add(graph, round, "b1", "target b1", "family-b", proposals++);
        add(graph, round, "b2", "target b2", "family-b", proposals++);
        String independentId = add(graph, round, "c", "target c", "", proposals++);

        String familyObligation = "round-" + round + "-b1";
        for (String source : List.of("proof-debt", "meta-review", "inspiration", "bridge")) {
          DesktopProofGraphIssue005BlackBoxSupport.enqueue(
              harness, source, "route-1", familyObligation, "DEEPEN");
        }
        if (DesktopProofGraphIssue005BlackBoxSupport.enqueue(
            harness,
            "route-specific-" + round,
            "route-1",
            independentId,
            "DEEPEN-" + round)) {
          independentAdmissions++;
        }

        if (round == 9) {
          canonicalBeforeRestore = canonicalIds(graph);
          familiesBeforeRestore = familyIds(graph);
          DesktopSolveCheckpoint checkpoint = harness.checkpointRoundTrip();
          assertThat(checkpoint.schemaVersion())
              .isEqualTo(DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION);
          DesktopResearchCheckpointBlackBoxHarness restored = harness.restored(checkpoint);
          harness.close();
          harness = restored;
          ProofGraphStore restoredGraph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
          postRestoreCanonicalLosses =
              (int)
                  canonicalBeforeRestore.stream()
                      .filter(id -> !canonicalIds(restoredGraph).contains(id))
                      .count();
          postRestoreFamilyLosses =
              (int)
                  familiesBeforeRestore.stream()
                      .filter(id -> !familyIds(restoredGraph).contains(id))
                      .count();
        }
      }

      ProofGraphStore graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
      List<DesktopSolveCheckpoint.ScheduledProofTask> tasks =
          DesktopProofGraphIssue005BlackBoxSupport.pendingTasks(harness).stream()
              .map(DesktopSolveCheckpoint.ScheduledProofTask.class::cast)
              .toList();
      long duplicateCanonicalTasks = duplicates(tasks, ProofTaskScope.CANONICAL_TARGET);
      long duplicateFamilyTasks = duplicates(tasks, ProofTaskScope.BOTTLENECK_FAMILY);
      long multiMemberFamilies =
          graph.allBottleneckFamilies().stream()
              .filter(family -> family.canonicalTargetIds().size() > 1)
              .count();
      int postRestoreTaskDuplicates = (int) (duplicateCanonicalTasks + duplicateFamilyTasks);

      System.out.println("OBLIGATION CANONICALIZATION MULTI-ROUND DIAGNOSTIC");
      System.out.println("ROUNDS=20");
      System.out.println(
          "RESTORE_SCHEMA_VERSION=" + DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION);
      System.out.println("RESTORE_ROUND=10");
      System.out.println("RAW_OBLIGATION_PROPOSALS=" + proposals);
      System.out.println("RAW_OCCURRENCES_RECORDED=" + graph.rawObligationOccurrences().size());
      System.out.println("CANONICAL_TARGETS=" + graph.allCanonicalTargets().size());
      System.out.println("MULTI_MEMBER_BOTTLENECK_FAMILIES=" + multiMemberFamilies);
      System.out.println("SCHEDULABLE_WORK_ITEMS=" + graph.coreOpenWorkItems().size());
      System.out.println("DUPLICATE_CANONICAL_TASKS=" + duplicateCanonicalTasks);
      System.out.println("DUPLICATE_FAMILY_TASKS=" + duplicateFamilyTasks);
      System.out.println("INDEPENDENT_ROUTE_TASKS_SUPPRESSED=" + (20 - independentAdmissions));
      System.out.println("POST_RESTORE_CANONICAL_LOSSES=" + postRestoreCanonicalLosses);
      System.out.println("POST_RESTORE_FAMILY_LOSSES=" + postRestoreFamilyLosses);
      System.out.println("POST_RESTORE_TASK_DUPLICATES=" + postRestoreTaskDuplicates);

      assertThat(proposals).isEqualTo(120);
      assertThat(graph.rawObligationOccurrences()).hasSize(120);
      assertThat(graph.allCanonicalTargets()).hasSize(4);
      assertThat(multiMemberFamilies).isEqualTo(1);
      assertThat(graph.coreOpenWorkItems()).hasSize(3);
      assertThat(duplicateCanonicalTasks).isZero();
      assertThat(duplicateFamilyTasks).isZero();
      assertThat(independentAdmissions).isEqualTo(20);
      assertThat(postRestoreCanonicalLosses).isZero();
      assertThat(postRestoreFamilyLosses).isZero();
      assertThat(postRestoreTaskDuplicates).isZero();
    } finally {
      harness.close();
    }
  }

  private static String add(
      ProofGraphStore graph,
      int round,
      String suffix,
      String target,
      String family,
      int proposal) {
    String id = "round-" + round + "-" + suffix;
    ProofObligation obligation =
        DesktopProofGraphIssue005BlackBoxSupport.obligation(
            id, "route-1", target, target, family, "plan-" + proposal);
    graph.addObligationCanonicalized(
        obligation,
        new ObligationCreationContext(
            obligation.problemHash(),
            "route-1",
            "strategy-route-1",
            ObligationSourceType.STRATEGY_BLUEPRINT,
            "blueprint://" + id,
            List.of(),
            "positive",
            Map.of(),
            family,
            family,
            BottleneckRelationType.SHARES_UPSTREAM_BOTTLENECK,
            ObligationOccurrenceSchedulingState.ACTIVE,
            round));
    return id;
  }

  private static Set<String> canonicalIds(ProofGraphStore graph) {
    Set<String> result = new LinkedHashSet<>();
    graph.allCanonicalTargets().forEach(target -> result.add(target.canonicalTargetId()));
    return Set.copyOf(result);
  }

  private static Set<String> familyIds(ProofGraphStore graph) {
    Set<String> result = new LinkedHashSet<>();
    graph.allBottleneckFamilies().forEach(family -> result.add(family.familyId()));
    return Set.copyOf(result);
  }

  private static long duplicates(
      List<DesktopSolveCheckpoint.ScheduledProofTask> tasks, ProofTaskScope scope) {
    List<String> keys =
        tasks.stream()
            .filter(task -> task.scope() == scope)
            .map(
                task ->
                    (scope == ProofTaskScope.BOTTLENECK_FAMILY
                            ? task.familyId()
                            : task.canonicalTargetId())
                        + ":"
                        + task.actionKey())
            .toList();
    return keys.size() - keys.stream().distinct().count();
  }
}
