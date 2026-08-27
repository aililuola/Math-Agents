package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ResearchEpochCommitProtocolMigrationTest {
  @Test
  void version20MigrationClassifiesProtocolFromCommitStateRatherThanSchemaAlone() {
    ResearchEpochRecord prepared =
        record("prepared", ResearchEpochStatus.MERGE_PREPARED, null);
    ResearchEpochRecord committed = record("committed", ResearchEpochStatus.COMMITTED, null);

    ResearchEpochSnapshot migrated =
        ResearchEpochCommitProtocolMigration.migrate(
            20,
            new ResearchEpochSnapshot(List.of(prepared, committed), 2L),
            ResearchAuthorityMutationSnapshot.empty());

    assertThat(migrated.epochs())
        .filteredOn(epoch -> epoch.epochId().equals("prepared"))
        .singleElement()
        .extracting(ResearchEpochRecord::authorityCommitProtocol)
        .isEqualTo(ResearchAuthorityCommitProtocol.RECEIPT_V1);
    assertThat(migrated.epochs())
        .filteredOn(epoch -> epoch.epochId().equals("committed"))
        .singleElement()
        .extracting(ResearchEpochRecord::authorityCommitProtocol)
        .isEqualTo(ResearchAuthorityCommitProtocol.LEGACY_NO_RECEIPT);
  }

  @Test
  void commitThatCreatesModernReceiptsUpgradesAReplayableLegacyPreparedEpoch() {
    FrozenResearchSnapshot snapshot = ConcurrencyTestFixtures.snapshot();
    ResearchEpochLedger ledger = new ResearchEpochLedger();
    ledger.plan(snapshot, List.of("work"));
    ledger.transition(snapshot.epochId(), ResearchEpochStatus.DISPATCHING, List.of(), "");
    ledger.transition(snapshot.epochId(), ResearchEpochStatus.ALL_SETTLED, List.of("result"), "");
    ledger.transition(
        snapshot.epochId(), ResearchEpochStatus.MERGE_PREPARED, List.of("result"), "merge");
    ResearchEpochRecord legacyPrepared =
        ledger
            .require(snapshot.epochId())
            .withAuthorityCommitProtocol(ResearchAuthorityCommitProtocol.LEGACY_NO_RECEIPT);
    ledger.restore(new ResearchEpochSnapshot(List.of(legacyPrepared), 4L));

    ResearchEpochRecord committed = ledger.commit(snapshot.epochId(), "authority-after");

    assertThat(committed.status()).isEqualTo(ResearchEpochStatus.COMMITTED);
    assertThat(committed.authorityCommitProtocol())
        .isEqualTo(ResearchAuthorityCommitProtocol.RECEIPT_V1);
    assertThat(committed.authorityHashAfterCommit()).isEqualTo("authority-after");
  }

  @Test
  void explicitLegacyProtocolWithReceiptEraEvidenceIsReclassifiedAsReceiptVersionOne() {
    ResearchEpochRecord poisoned =
        record(
                "poisoned",
                ResearchEpochStatus.COMMITTED,
                ResearchAuthorityCommitProtocol.LEGACY_NO_RECEIPT)
            .withAuthorityHashAfterCommit("authority-after");

    ResearchEpochSnapshot migrated =
        ResearchEpochCommitProtocolMigration.migrate(
            21,
            new ResearchEpochSnapshot(List.of(poisoned), 1L),
            receiptsFor(poisoned, true, true));

    assertThat(migrated.epochs())
        .singleElement()
        .satisfies(
            epoch -> {
              assertThat(epoch.authorityCommitProtocol())
                  .isEqualTo(ResearchAuthorityCommitProtocol.RECEIPT_V1);
              assertThat(epoch.authorityHashAfterCommit()).isEqualTo("authority-after");
            });
  }

  @Test
  void explicitLegacyProtocolWithModernAfterHashButMissingReceiptsFailsClosed() {
    ResearchEpochRecord poisonedWithoutReceipts =
        record(
                "poisoned-missing",
                ResearchEpochStatus.COMMITTED,
                ResearchAuthorityCommitProtocol.LEGACY_NO_RECEIPT)
            .withAuthorityHashAfterCommit("authority-after");

    ResearchEpochSnapshot migrated =
        ResearchEpochCommitProtocolMigration.migrate(
            21,
            new ResearchEpochSnapshot(List.of(poisonedWithoutReceipts), 1L),
            ResearchAuthorityMutationSnapshot.empty());

    assertThat(migrated.epochs())
        .singleElement()
        .extracting(ResearchEpochRecord::authorityCommitProtocol)
        .isEqualTo(ResearchAuthorityCommitProtocol.RECEIPT_V1);
  }

  @Test
  void explicitLegacyProtocolWithOnlyOneModernReceiptFailsClosed() {
    ResearchEpochRecord poisoned =
        record(
            "poisoned-partial",
            ResearchEpochStatus.COMMITTED,
            ResearchAuthorityCommitProtocol.LEGACY_NO_RECEIPT);

    ResearchEpochSnapshot mutationOnly =
        ResearchEpochCommitProtocolMigration.migrate(
            21,
            new ResearchEpochSnapshot(List.of(poisoned), 1L),
            receiptsFor(poisoned, true, false));
    ResearchEpochSnapshot mergeOnly =
        ResearchEpochCommitProtocolMigration.migrate(
            21,
            new ResearchEpochSnapshot(List.of(poisoned), 1L),
            receiptsFor(poisoned, false, true));

    assertThat(mutationOnly.epochs().getFirst().authorityCommitProtocol())
        .isEqualTo(ResearchAuthorityCommitProtocol.RECEIPT_V1);
    assertThat(mergeOnly.epochs().getFirst().authorityCommitProtocol())
        .isEqualTo(ResearchAuthorityCommitProtocol.RECEIPT_V1);
  }

  @Test
  void explicitLegacyProtocolWithoutReceiptEraEvidenceRemainsHistoricalLegacy() {
    ResearchEpochRecord historical =
        record(
            "historical",
            ResearchEpochStatus.COMMITTED,
            ResearchAuthorityCommitProtocol.LEGACY_NO_RECEIPT);

    ResearchEpochSnapshot migrated =
        ResearchEpochCommitProtocolMigration.migrate(
            21,
            new ResearchEpochSnapshot(List.of(historical), 1L),
            ResearchAuthorityMutationSnapshot.empty());

    assertThat(migrated.epochs())
        .singleElement()
        .satisfies(
            epoch -> {
              assertThat(epoch.authorityCommitProtocol())
                  .isEqualTo(ResearchAuthorityCommitProtocol.LEGACY_NO_RECEIPT);
              assertThat(epoch.authorityHashAfterCommit()).isBlank();
            });
  }

  private static ResearchAuthorityMutationSnapshot receiptsFor(
      ResearchEpochRecord epoch, boolean includeMutation, boolean includeMerge) {
    ResearchAuthorityMutationReceipt mutation =
        ResearchAuthorityMutationReceipt.create(
            epoch.epochId(),
            epoch.mergePlanHash(),
            epoch.authority().stableHash(),
            "authority-after",
            epoch.durableResultIds(),
            List.of(),
            List.of(),
            List.of());
    ResearchMergeReceipt merge =
        new ResearchMergeReceipt(
            epoch.epochId(),
            epoch.mergePlanHash(),
            epoch.durableResultIds(),
            List.of(),
            "authority-after");
    return new ResearchAuthorityMutationSnapshot(
        includeMutation ? List.of(mutation) : List.of(),
        includeMerge ? List.of(merge) : List.of(),
        1L,
        null);
  }

  private static ResearchEpochRecord record(
      String epochId,
      ResearchEpochStatus status,
      ResearchAuthorityCommitProtocol protocol) {
    return new ResearchEpochRecord(
        epochId,
        "snapshot-" + epochId,
        status,
        List.of("work-" + epochId),
        List.of("result-" + epochId),
        "merge-" + epochId,
        ConcurrencyTestFixtures.anchor(),
        protocol,
        "",
        1L);
  }
}
