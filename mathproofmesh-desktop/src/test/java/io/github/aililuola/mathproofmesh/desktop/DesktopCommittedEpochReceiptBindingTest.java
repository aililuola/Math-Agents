package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.concurrency.ResearchAuthorityCommitProtocol;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopCommittedEpochReceiptBindingTest {
  @TempDir Path temporaryDirectory;

  @Test
  void everyModernCommittedReceiptFieldIsRevalidatedByTheDesktopRestoreBoundary()
      throws Exception {
    String runId = "committed-epoch-receipt-binding";
    DesktopSolveCheckpoint committed = committedCheckpoint(runId);
    Map<String, BiConsumer<ObjectNode, ObjectNode>> attacks = new LinkedHashMap<>();
    attacks.put(
        "foreign_epoch",
        (mutation, merge) -> {
          mutation.put("epochId", "foreign-epoch");
          mutation.remove("receiptHash");
        });
    attacks.put(
        "merge_plan",
        (mutation, merge) -> {
          mutation.put("mergePlanHash", "tampered-merge-plan");
          mutation.remove("receiptHash");
          merge.put("mergePlanHash", "tampered-merge-plan");
        });
    attacks.put(
        "authority_before",
        (mutation, merge) -> {
          mutation.put("authorityHashBefore", "tampered-authority-before");
          mutation.remove("receiptHash");
        });
    attacks.put(
        "authority_after",
        (mutation, merge) -> {
          mutation.put("authorityHashAfter", "tampered-authority-after");
          mutation.remove("receiptHash");
          merge.put("authorityHashAfterCommit", "tampered-authority-after");
        });
    attacks.put(
        "accepted_results",
        (mutation, merge) -> {
          replaceArray(mutation, "acceptedResultHashes", "tampered-result-hash");
          mutation.remove("receiptHash");
          replaceArray(merge, "acceptedResultHashes", "tampered-result-hash");
        });
    attacks.put("dangling_merge", (mutation, merge) -> mutation.removeAll());

    Map<String, Integer> accepts = new LinkedHashMap<>();
    int ordinal = 0;
    for (Map.Entry<String, BiConsumer<ObjectNode, ObjectNode>> attack : attacks.entrySet()) {
      DesktopSolveCheckpoint tampered = tamper(committed, attack.getValue());
      int accepted = 0;
      try (DesktopClaimSalvageTestHarness restored =
          DesktopClaimSalvageTestHarness.open(
              temporaryDirectory.resolve("attack-" + ordinal), runId)) {
        try {
          restored.restore(tampered);
          accepted = 1;
        } catch (IllegalArgumentException | IllegalStateException expected) {
          accepted = 0;
        }
      }
      accepts.put(attack.getKey(), accepted);
      ordinal++;
    }

    assertThat(accepts.values()).containsOnly(0);

    System.out.println("COMMITTED EPOCH RECEIPT BINDING DIAGNOSTIC");
    System.out.println("FOREIGN_EPOCH_RECEIPT_ACCEPTS=" + accepts.get("foreign_epoch"));
    System.out.println("MERGE_PLAN_MISMATCH_ACCEPTS=" + accepts.get("merge_plan"));
    System.out.println(
        "AUTHORITY_BEFORE_MISMATCH_ACCEPTS=" + accepts.get("authority_before"));
    System.out.println(
        "AUTHORITY_AFTER_MISMATCH_ACCEPTS=" + accepts.get("authority_after"));
    System.out.println(
        "ACCEPTED_RESULT_MISMATCH_ACCEPTS=" + accepts.get("accepted_results"));
    System.out.println(
        "DANGLING_MERGE_RECEIPT_ACCEPTS=" + accepts.get("dangling_merge"));
    System.out.println("RESULT=PASS");
  }

  @Test
  void preProtocolSchemaTwentyOneReceiptBindingIsMigratedOnceAndThenPersisted()
      throws Exception {
    String runId = "schema-21-pre-protocol-receipt";
    DesktopSolveCheckpoint committed = committedCheckpoint(runId);
    ObjectNode root =
        (ObjectNode) ContractObjectMapper.parseTree(ContractObjectMapper.write(committed));
    ObjectNode epochs = (ObjectNode) root.get("researchEpochs");
    ArrayNode records = (ArrayNode) epochs.get("epochs");
    records.forEach(
        value -> {
          if (value instanceof ObjectNode record) {
            record.remove("authorityCommitProtocol");
            record.remove("authorityHashAfterCommit");
          }
        });
    DesktopSolveCheckpoint preProtocol =
        ContractObjectMapper.read(root.toString(), DesktopSolveCheckpoint.class);

    DesktopSolveCheckpoint migrated;
    try (DesktopClaimSalvageTestHarness restored =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory.resolve("pre-protocol"), runId)) {
      restored.restore(preProtocol);
      migrated = restored.checkpointRoundTrip();
      assertThat(restored.providerCallCount()).isZero();
    }

    assertThat(migrated.researchEpochs().epochs())
        .singleElement()
        .satisfies(
            epoch -> {
              assertThat(epoch.authorityCommitProtocol())
                  .isEqualTo(ResearchAuthorityCommitProtocol.RECEIPT_V1);
              assertThat(epoch.authorityHashAfterCommit())
                  .isEqualTo(
                      migrated
                          .researchAuthorityMutations()
                          .authorityMutations()
                          .getFirst()
                          .authorityHashAfter());
            });
  }

  private DesktopSolveCheckpoint committedCheckpoint(String runId) throws Exception {
    try (DesktopClaimSalvageTestHarness source =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory.resolve("source"), runId)) {
      source.prepareMixedClaimCourtBatch();
      source.integrateInstalledRound();
      DesktopSolveCheckpoint committed = source.readPersistedCheckpoint();
      assertThat(committed.researchAuthorityMutations().authorityMutations()).hasSize(1);
      assertThat(committed.researchAuthorityMutations().mergeReceipts()).hasSize(1);
      return committed;
    }
  }

  private static DesktopSolveCheckpoint tamper(
      DesktopSolveCheckpoint checkpoint, BiConsumer<ObjectNode, ObjectNode> attack) {
    ObjectNode root =
        (ObjectNode) ContractObjectMapper.parseTree(ContractObjectMapper.write(checkpoint));
    ObjectNode ledger = (ObjectNode) root.get("researchAuthorityMutations");
    ObjectNode mutation = (ObjectNode) ledger.withArray("authorityMutations").get(0);
    ObjectNode merge = (ObjectNode) ledger.withArray("mergeReceipts").get(0);
    attack.accept(mutation, merge);
    if (mutation.isEmpty()) {
      ledger.set("authorityMutations", ledger.arrayNode());
    }
    ledger.remove("stableHash");
    return ContractObjectMapper.read(root.toString(), DesktopSolveCheckpoint.class);
  }

  private static void replaceArray(ObjectNode node, String field, String value) {
    ArrayNode values = node.putArray(field);
    values.add(value);
  }
}
