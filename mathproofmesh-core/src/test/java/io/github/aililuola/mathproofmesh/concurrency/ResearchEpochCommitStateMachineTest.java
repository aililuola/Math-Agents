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
                    Optional.empty(),
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
                    Optional.of(mergeReceipt(receipt)),
                    true)
                .action())
        .isEqualTo(ResearchEpochCommitStateMachine.RecoveryAction.NO_OP_COMMITTED);
    assertThat(
            stateMachine
                .reconcile(
                    epoch(ResearchEpochStatus.MERGE_PREPARED),
                    receipt.authorityHashAfter(),
                    Optional.of(receipt),
                    Optional.empty(),
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
            Optional.empty(),
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
                    Optional.of(
                        new ResearchMergeReceipt(
                            epoch.epochId(),
                            epoch.mergePlanHash(),
                            List.of("result-a"),
                            List.of(),
                            "authority-after")),
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
                    Optional.empty(),
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
                    Optional.empty(),
                    false)
                .action())
        .isEqualTo(ResearchEpochCommitStateMachine.RecoveryAction.LEGACY_NO_RECEIPT);
  }

  @Test
  void legacyCommittedEpochWithOnlyOneReceiptIsQuarantined() {
    ResearchEpochRecord epoch =
        epoch(ResearchEpochStatus.COMMITTED)
            .withAuthorityCommitProtocol(
                ResearchAuthorityCommitProtocol.LEGACY_NO_RECEIPT);

    assertThat(
            stateMachine
                .reconcile(
                    epoch,
                    "later-authority",
                    Optional.of(receipt()),
                    Optional.empty(),
                    false)
                .quarantined())
        .isTrue();
  }

  private static ResearchEpochRecord epoch(ResearchEpochStatus status) {
    FrozenResearchSnapshot frozen = ConcurrencyTestFixtures.snapshot();
    ResearchEpochRecord epoch =
        new ResearchEpochRecord(
            frozen.epochId(),
            frozen.snapshotHash(),
            status,
            List.of("work-a"),
            List.of("result-a"),
            "merge",
            frozen.authority(),
            1L);
    return status == ResearchEpochStatus.COMMITTED
        ? epoch.withAuthorityHashAfterCommit("authority-after")
        : epoch;
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

  private static ResearchMergeReceipt mergeReceipt(
      ResearchAuthorityMutationReceipt mutation) {
    return new ResearchMergeReceipt(
        mutation.epochId(),
        mutation.mergePlanHash(),
        mutation.acceptedResultHashes(),
        List.of(),
        mutation.authorityHashAfter());
  }
}
