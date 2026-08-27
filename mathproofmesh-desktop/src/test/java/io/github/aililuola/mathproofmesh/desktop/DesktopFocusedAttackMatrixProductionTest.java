package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopFocusedAttackMatrixProductionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void focusedRolesUseTheAuthoritativeCoordinatorEpochAndAllResearchSlots() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("focused-matrix"), "focused-matrix-production")) {
      harness.prepareFocusedExploration();
      harness.runFocusedExploration(100L);

      DesktopSolveCoordinator.AuthoritativeConcurrencyDiagnostics diagnostics =
          harness.concurrencyDiagnostics();
      Set<ResearchWorkKind> expected =
          Set.of(
              ResearchWorkKind.FOCUSED_PROVER,
              ResearchWorkKind.FOCUSED_FALSIFIER,
              ResearchWorkKind.FOCUSED_REPROVER,
              ResearchWorkKind.DEPENDENCY_AUDITOR);

      assertThat(diagnostics.workItemCount()).isEqualTo(4);
      assertThat(diagnostics.resultArtifactCount()).isEqualTo(4);
      assertThat(diagnostics.committedEpochCount()).isEqualTo(1L);
      assertThat(diagnostics.liveMergeReceiptCount()).isEqualTo(1);
      assertThat(diagnostics.directWorkerAuthorityMutations()).isZero();
      assertThat(diagnostics.workKinds()).containsExactlyInAnyOrderElementsOf(expected);
      assertThat(harness.maximumConcurrentExplorationCalls()).isEqualTo(4);

      System.out.println("FOCUSED ATTACK MATRIX PRODUCTION DIAGNOSTIC");
      System.out.println("FOCUSED_WORK_ITEMS=" + diagnostics.workItemCount());
      System.out.println("FOCUSED_RESULT_ARTIFACTS=" + diagnostics.resultArtifactCount());
      System.out.println("FOCUSED_MERGE_RECEIPTS=" + diagnostics.liveMergeReceiptCount());
      System.out.println("FOCUSED_WORK_KINDS=" + diagnostics.workKinds());
      System.out.println(
          "FOCUSED_MAX_CONCURRENCY=" + harness.maximumConcurrentExplorationCalls());
      System.out.println(
          "DIRECT_WORKER_AUTHORITY_MUTATIONS=" + diagnostics.directWorkerAuthorityMutations());
      System.out.println("RESULT=PASS");
    }
  }
}
