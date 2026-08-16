package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationArtifactKind;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionFailurePoint;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionStatus;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class DesktopComputationAuthorityProjectionTestSupport {
  private DesktopComputationAuthorityProjectionTestSupport() {}

  static RecoveryMetrics hardCrash(
      Path root, ComputationExecutionFailurePoint failurePoint, MutationKind kind)
      throws Exception {
    String suffix =
        kind.name().toLowerCase(java.util.Locale.ROOT)
            + "-"
            + failurePoint.name().toLowerCase(java.util.Locale.ROOT);
    Path runDirectory = root.resolve(suffix);
    String runId = "authority-crash-" + suffix;
    String obligationId = "authority-target-" + suffix;
    String experimentId = "authority-experiment-" + suffix;
    DesktopSolveCheckpoint durable;
    int factsBefore;
    int negativesBefore;
    String rootHashBefore;
    AtomicBoolean injected = new AtomicBoolean();

    try (var first =
        DesktopComputationIssue010CoordinatorHarness.open(runDirectory, runId)) {
      first.initializeRoute();
      var source =
          kind == MutationKind.FACT
              ? DesktopComputationIssue010Support.finiteMap(experimentId)
              : DesktopComputationIssue010Support.graphCounterexample(
                  experimentId, failurePoint.ordinal() + 30);
      first.addObligation(obligationId, source.targetClaim());
      var spec = first.exactBound(source, obligationId);
      DesktopSolveCheckpoint before = first.checkpointRoundTrip();
      rootHashBefore = before.problem().goalHash();
      factsBefore = first.typedMemory().facts().size();
      negativesBefore = first.typedMemory().negatives().size();
      first
          .computation()
          .setExecutionHook(
              (observed, executionId) -> {
                if (observed == failurePoint && injected.compareAndSet(false, true)) {
                  throw new SimulatedProcessTermination(failurePoint.name());
                }
              });

      try {
        first.runComputation(spec);
        throw new AssertionError("hard crash was not injected at " + failurePoint);
      } catch (SimulatedProcessTermination expected) {
        assertThat(expected.getMessage()).isEqualTo(failurePoint.name());
      }
      durable = first.readCheckpoint();
    }

    int restoreFailures = 0;
    int ledgerWithoutMutation;
    int mutationWithoutLedger;
    int duplicateFacts;
    int duplicateCounterexamples;
    int partialProjections;
    int rootHashChanges;
    int secondRestoreChanges;
    String authorityStateHash;
    DesktopSolveCheckpoint appliedCheckpoint;

    try (var restored =
        DesktopComputationIssue010CoordinatorHarness.open(runDirectory, runId)) {
      try {
        restored.restore(durable);
      } catch (RuntimeException exception) {
        restoreFailures++;
        throw exception;
      }
      var record = restored.execution(experimentId);
      var mutation =
          restored
              .computation()
              .executionService()
              .authorityMutationReceipt(record.executionId());
      int factDelta = restored.typedMemory().facts().size() - factsBefore;
      int negativeDelta = restored.typedMemory().negatives().size() - negativesBefore;
      boolean mathematicalMutation =
          kind == MutationKind.FACT
              ? factDelta == 1
              : negativeDelta == 1
                  && "refuted".equals(restored.obligation(obligationId).status());
      boolean ledgerMutation =
          record.status() == ComputationExecutionStatus.AUTHORITY_APPLIED
              && record.authorityProjections() == 1
              && mutation.isPresent()
              && mutation.orElseThrow().changedMathematicalAuthority();

      ledgerWithoutMutation = ledgerMutation && mathematicalMutation ? 0 : ledgerMutation ? 1 : 0;
      mutationWithoutLedger = mathematicalMutation && ledgerMutation ? 0 : mathematicalMutation ? 1 : 0;
      duplicateFacts = kind == MutationKind.FACT ? Math.max(0, factDelta - 1) : 0;
      duplicateCounterexamples =
          kind == MutationKind.COUNTEREXAMPLE ? Math.max(0, negativeDelta - 1) : 0;
      partialProjections = ledgerMutation && mathematicalMutation ? 0 : 1;
      appliedCheckpoint = restored.checkpointRoundTrip();
      authorityStateHash = authorityStateHash(restored, record, obligationId);
      rootHashChanges =
          rootHashBefore.equals(appliedCheckpoint.problem().goalHash()) ? 0 : 1;

      long mutationArtifacts =
          restored.computation().snapshot().artifacts().records().stream()
              .filter(value -> value.executionId().equals(record.executionId()))
              .filter(value -> value.kind() == ComputationArtifactKind.AUTHORITY_MUTATION_RECEIPT)
              .count();
      assertThat(mutationArtifacts).isEqualTo(1L);
    }

    try (var second =
        DesktopComputationIssue010CoordinatorHarness.open(runDirectory, runId)) {
      second.restore(appliedCheckpoint);
      var secondRecord = second.execution(experimentId);
      int factDelta = second.typedMemory().facts().size() - factsBefore;
      int negativeDelta = second.typedMemory().negatives().size() - negativesBefore;
      DesktopSolveCheckpoint secondCheckpoint = second.checkpointRoundTrip();
      boolean stable =
          secondRecord.status() == ComputationExecutionStatus.AUTHORITY_APPLIED
              && secondRecord.authorityProjections() == 1
              && (kind == MutationKind.FACT
                  ? factDelta == 1
                  : negativeDelta == 1
                      && "refuted".equals(second.obligation(obligationId).status()))
              && authorityStateHash.equals(
                  authorityStateHash(second, secondRecord, obligationId));
      secondRestoreChanges = stable ? 0 : 1;
      rootHashChanges +=
          rootHashBefore.equals(secondCheckpoint.problem().goalHash()) ? 0 : 1;
    }

    return new RecoveryMetrics(
        1,
        restoreFailures,
        ledgerWithoutMutation,
        mutationWithoutLedger,
        duplicateFacts,
        duplicateCounterexamples,
        partialProjections,
        rootHashChanges,
        secondRestoreChanges);
  }

  private static String authorityStateHash(
      DesktopComputationIssue010CoordinatorHarness harness,
      io.github.aililuola.mathproofmesh.computation.ComputationExecutionRecord record,
      String obligationId)
      throws ReflectiveOperationException {
    return CanonicalJson.stableHash(
        Map.of(
            "execution", record,
            "fact_ids",
                harness.typedMemory().facts().stream().map(value -> value.messageId()).sorted().toList(),
            "negative_ids",
                harness.typedMemory().negatives().stream().map(value -> value.messageId()).sorted().toList(),
            "obligation", harness.obligation(obligationId)));
  }

  enum MutationKind {
    FACT,
    COUNTEREXAMPLE
  }

  record RecoveryMetrics(
      int hardCrashes,
      int restoreFailures,
      int authorityLedgerWithoutMutation,
      int mutationWithoutAuthorityLedger,
      int duplicateFactProjections,
      int duplicateCounterexampleProjections,
      int partialAuthorityProjections,
      int rootHashChanges,
      int secondRestoreChanges) {
    RecoveryMetrics plus(RecoveryMetrics other) {
      return new RecoveryMetrics(
          hardCrashes + other.hardCrashes,
          restoreFailures + other.restoreFailures,
          authorityLedgerWithoutMutation + other.authorityLedgerWithoutMutation,
          mutationWithoutAuthorityLedger + other.mutationWithoutAuthorityLedger,
          duplicateFactProjections + other.duplicateFactProjections,
          duplicateCounterexampleProjections + other.duplicateCounterexampleProjections,
          partialAuthorityProjections + other.partialAuthorityProjections,
          rootHashChanges + other.rootHashChanges,
          secondRestoreChanges + other.secondRestoreChanges);
    }

    static RecoveryMetrics zero() {
      return new RecoveryMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
  }

  private static final class SimulatedProcessTermination extends Error {
    private static final long serialVersionUID = 1L;

    private SimulatedProcessTermination(String message) {
      super(message);
    }
  }
}
