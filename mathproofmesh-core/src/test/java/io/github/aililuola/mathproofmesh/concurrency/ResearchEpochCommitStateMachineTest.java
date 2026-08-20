package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ResearchEpochCommitStateMachineTest {
  private final ResearchEpochCommitStateMachine stateMachine =
      new ResearchEpochCommitStateMachine();

  @Test
  void preparedFrozenAuthorityWithoutReceiptReplaysExactlyOnce() {
    ResearchEpochRecord epoch = epoch(ResearchEpochStatus.MERGE_PREPARED);

    assertThat(
            stateMachine
                .reconcile(
                    epoch,
                    epoch.authority().stableHash(),
                    Optional.empty(),
                    false,
                    true)
                .action())
        .isEqualTo(ResearchEpochCommitStateMachine.RecoveryAction.REPLAY_PREPARED);
  }

  @Test
  void committedReceiptedAuthorityIsANoOpAndReceiptedPreparedAuthorityRollsForward() {
    ResearchAuthorityMutationReceipt receipt = receipt();

    assertThat(
            stateMachine
                .reconcile(
                    epoch(ResearchEpochStatus.COMMITTED),
                    receipt.authorityHashAfter(),
                    Optional.of(receipt),
                    true,
                    true)
                .action())
        .isEqualTo(ResearchEpochCommitStateMachine.RecoveryAction.NO_OP_COMMITTED);
    assertThat(
            stateMachine
                .reconcile(
                    epoch(ResearchEpochStatus.MERGE_PREPARED),
                    receipt.authorityHashAfter(),
                    Optional.of(receipt),
                    false,
                    true)
                .action())
        .isEqualTo(ResearchEpochCommitStateMachine.RecoveryAction.ROLL_FORWARD_RECEIPTED);
  }

  @Test
  void advancedPreparedAuthorityWithoutReceiptIsQuarantined() {
    var decision =
        stateMachine.reconcile(
            epoch(ResearchEpochStatus.MERGE_PREPARED),
            "partially-advanced-authority",
            Optional.empty(),
            false,
            true);

    assertThat(decision.quarantined()).isTrue();
    assertThat(decision.code()).isEqualTo("QUARANTINED_PARTIAL_AUTHORITY_COMMIT");
  }

  @Test
  void danglingMergeOrReceiptBoundToAnotherEpochIsQuarantined() {
    ResearchEpochRecord epoch = epoch(ResearchEpochStatus.MERGE_PREPARED);

    assertThat(
            stateMachine
                .reconcile(
                    epoch,
                    epoch.authority().stableHash(),
                    Optional.empty(),
                    true,
                    true)
                .quarantined())
        .isTrue();
    ResearchAuthorityMutationReceipt foreign =
        ResearchAuthorityMutationReceipt.create(
            "foreign-epoch",
            epoch.mergePlanHash(),
            epoch.authority().stableHash(),
            "authority-after",
            List.of("result-a"),
            List.of(),
            List.of(),
            List.of());
    assertThat(
            stateMachine
                .reconcile(
                    epoch,
                    foreign.authorityHashAfter(),
                    Optional.of(foreign),
                    false,
                    true)
                .quarantined())
        .isTrue();
  }

  @Test
  void preReceiptCommittedEpochsRemainReadableOnlyAsLegacyState() {
    assertThat(
            stateMachine
                .reconcile(
                    epoch(ResearchEpochStatus.COMMITTED),
                    "legacy-authority",
                    Optional.empty(),
                    false,
                    false)
                .action())
        .isEqualTo(ResearchEpochCommitStateMachine.RecoveryAction.LEGACY_NO_RECEIPT);
  }

  private static ResearchEpochRecord epoch(ResearchEpochStatus status) {
    FrozenResearchSnapshot frozen = ConcurrencyTestFixtures.snapshot();
    return new ResearchEpochRecord(
        frozen.epochId(),
        frozen.snapshotHash(),
        status,
        List.of("work-a"),
        List.of("result-a"),
        "merge",
        frozen.authority(),
        1L);
  }

  private static ResearchAuthorityMutationReceipt receipt() {
    ResearchEpochRecord epoch = epoch(ResearchEpochStatus.MERGE_PREPARED);
    return ResearchAuthorityMutationReceipt.create(
        epoch.epochId(),
        epoch.mergePlanHash(),
        epoch.authority().stableHash(),
        "authority-after",
        List.of("result-a"),
        List.of("claim-a"),
        List.of(),
        List.of());
  }
}
