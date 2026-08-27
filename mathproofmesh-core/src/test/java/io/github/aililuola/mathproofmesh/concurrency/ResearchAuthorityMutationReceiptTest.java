package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ResearchAuthorityMutationReceiptTest {
  @Test
  void receiptAndLedgerAreHashBoundCheckpointedAndIdempotent() {
    ResearchAuthorityMutationReceipt receipt = receipt("after");
    ResearchMergeReceipt merge =
        new ResearchMergeReceipt(
            "epoch", "merge", List.of("result-a"), List.of("result-b"), "after");
    ResearchAuthorityMutationLedger ledger = new ResearchAuthorityMutationLedger();

    ledger.recordAuthorityMutation(receipt);
    ledger.recordMergeReceipt(merge);
    ledger.recordAuthorityMutation(receipt);
    ledger.recordMergeReceipt(merge);
    ResearchAuthorityMutationSnapshot snapshot = ledger.snapshot();
    ResearchAuthorityMutationSnapshot roundTrip =
        ContractObjectMapper.read(
            ContractObjectMapper.write(snapshot), ResearchAuthorityMutationSnapshot.class);
    ResearchAuthorityMutationLedger restored = new ResearchAuthorityMutationLedger();
    restored.restore(roundTrip);

    assertThat(snapshot.version()).isEqualTo(2L);
    assertThat(restored.snapshot()).isEqualTo(snapshot);
    assertThat(restored.authorityMutation("epoch")).contains(receipt);
    assertThat(restored.mergeReceipt("epoch")).contains(merge);
    assertThatThrownBy(
            () ->
                new ResearchAuthorityMutationReceipt(
                    receipt.epochId(),
                    receipt.mergePlanHash(),
                    receipt.authorityHashBefore(),
                    receipt.authorityHashAfter(),
                    receipt.acceptedResultHashes(),
                    receipt.projectedClaimIds(),
                    receipt.factMessageIds(),
                    receipt.refutedObligationIds(),
                    "tampered"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("receipt hash mismatch");
    assertThatThrownBy(
            () ->
                restored.recordAuthorityMutation(
                    ResearchAuthorityMutationReceipt.create(
                        "epoch",
                        "merge",
                        "before",
                        "different-after",
                        List.of("result-a"),
                        List.of("claim-a"),
                        List.of(),
                        List.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("identity conflict");
  }

  @Test
  void snapshotRejectsDuplicateEpochsAndForgedStableHash() {
    ResearchAuthorityMutationReceipt receipt = receipt("after");

    assertThatThrownBy(
            () ->
                new ResearchAuthorityMutationSnapshot(
                    List.of(receipt, receipt), List.of(), 1L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unique");
    assertThatThrownBy(
            () ->
                new ResearchAuthorityMutationSnapshot(
                    List.of(receipt), List.of(), 1L, "forged"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("snapshot hash mismatch");
  }

  private static ResearchAuthorityMutationReceipt receipt(String after) {
    return ResearchAuthorityMutationReceipt.create(
        "epoch",
        "merge",
        "before",
        after,
        List.of("result-a"),
        List.of("claim-a"),
        List.of("fact-a"),
        List.of("obligation-a"));
  }
}
