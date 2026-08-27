package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.concurrency.ResearchAuthorityCommitProtocol;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochStatus;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopPreexistingPoisonedEpochCheckpointMigrationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void preexistingLegacyProtocolWithReceiptEraEvidenceUpgradesAndCannotFailOpen()
      throws Exception {
    String runId = "preexisting-poisoned-epoch-checkpoint";
    DesktopSolveCheckpoint committed;
    try (DesktopClaimSalvageTestHarness source =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory.resolve("source"), runId)) {
      source.prepareMixedClaimCourtBatch();
      source.integrateInstalledRound();
      committed = source.readPersistedCheckpoint();
    }

    DesktopSolveCheckpoint poisoned = withLegacyProtocol(committed);
    long poisonedCommittedEpochs =
        poisoned.researchEpochs().epochs().stream()
            .filter(epoch -> epoch.status() == ResearchEpochStatus.COMMITTED)
            .filter(
                epoch ->
                    epoch.authorityCommitProtocol()
                        == ResearchAuthorityCommitProtocol.LEGACY_NO_RECEIPT)
            .filter(epoch -> !epoch.authorityHashAfterCommit().isBlank())
            .count();

    int providerCallReplays = 0;
    DesktopSolveCheckpoint upgraded;
    try (DesktopClaimSalvageTestHarness first =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory.resolve("upgrade"), runId)) {
      first.restore(poisoned);
      upgraded = first.checkpointRoundTrip();
      providerCallReplays += first.providerCallCount();
    }
    long protocolUpgrades =
        upgraded.researchEpochs().epochs().stream()
            .filter(
                epoch ->
                    epoch.authorityCommitProtocol()
                        == ResearchAuthorityCommitProtocol.RECEIPT_V1)
            .count();

    DesktopSolveCheckpoint secondRoundTrip;
    try (DesktopClaimSalvageTestHarness second =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory.resolve("round-trip"), runId)) {
      second.restore(upgraded);
      secondRoundTrip = second.checkpointRoundTrip();
      providerCallReplays += second.providerCallCount();
    }
    long roundTripProtocolLosses =
        secondRoundTrip.researchEpochs().epochs().stream()
            .filter(
                epoch ->
                    epoch.authorityCommitProtocol()
                        != ResearchAuthorityCommitProtocol.RECEIPT_V1)
            .count();

    Map<String, DesktopSolveCheckpoint> corrupted = new LinkedHashMap<>();
    corrupted.put("missing_both", withReceiptPresence(poisoned, false, false));
    corrupted.put("mutation_only", withReceiptPresence(poisoned, true, false));
    corrupted.put("merge_only", withReceiptPresence(poisoned, false, true));
    long missingBothQuarantines = 0L;
    long mutationOnlyQuarantines = 0L;
    long mergeOnlyQuarantines = 0L;
    long legacyFailOpenAccepts = 0L;
    for (Map.Entry<String, DesktopSolveCheckpoint> entry : corrupted.entrySet()) {
      try (DesktopClaimSalvageTestHarness restored =
          DesktopClaimSalvageTestHarness.open(
              temporaryDirectory.resolve(entry.getKey()), runId)) {
        try {
          restored.restore(entry.getValue());
          legacyFailOpenAccepts++;
        } catch (IllegalStateException exception) {
          assertThat(exception)
              .hasMessageContaining("QUARANTINED_PARTIAL_AUTHORITY_COMMIT")
              .hasMessageContaining("MISSING_COMMITTED_RECEIPT");
          switch (entry.getKey()) {
            case "missing_both" -> missingBothQuarantines++;
            case "mutation_only" -> mutationOnlyQuarantines++;
            case "merge_only" -> mergeOnlyQuarantines++;
            default -> throw new AssertionError("unexpected corruption case");
          }
        }
        providerCallReplays += restored.providerCallCount();
      }
    }

    System.out.println("PREEXISTING POISONED EPOCH CHECKPOINT DIAGNOSTIC");
    System.out.println("POISONED_COMMITTED_EPOCHS=" + poisonedCommittedEpochs);
    System.out.println("FULL_RECEIPT_PROTOCOL_UPGRADES=" + protocolUpgrades);
    System.out.println("ROUND_TRIP_PROTOCOL_LOSSES=" + roundTripProtocolLosses);
    System.out.println("MISSING_BOTH_RECEIPT_QUARANTINES=" + missingBothQuarantines);
    System.out.println("MUTATION_ONLY_QUARANTINES=" + mutationOnlyQuarantines);
    System.out.println("MERGE_ONLY_QUARANTINES=" + mergeOnlyQuarantines);
    System.out.println("LEGACY_FAIL_OPEN_ACCEPTS=" + legacyFailOpenAccepts);
    System.out.println("PROVIDER_CALL_REPLAYS=" + providerCallReplays);

    assertThat(poisonedCommittedEpochs).isEqualTo(1L);
    assertThat(protocolUpgrades).isEqualTo(1L);
    assertThat(roundTripProtocolLosses).isZero();
    assertThat(missingBothQuarantines).isEqualTo(1L);
    assertThat(mutationOnlyQuarantines).isEqualTo(1L);
    assertThat(mergeOnlyQuarantines).isEqualTo(1L);
    assertThat(legacyFailOpenAccepts).isZero();
    assertThat(providerCallReplays).isZero();
    System.out.println("RESULT=PASS");
  }

  private static DesktopSolveCheckpoint withLegacyProtocol(DesktopSolveCheckpoint checkpoint) {
    ObjectNode json =
        (ObjectNode) ContractObjectMapper.parseTree(ContractObjectMapper.write(checkpoint));
    json.put("schemaVersion", 21);
    ObjectNode epochs = (ObjectNode) json.get("researchEpochs");
    ArrayNode records = (ArrayNode) epochs.get("epochs");
    records.forEach(
        value -> {
          if (value instanceof ObjectNode record) {
            record.put(
                "authorityCommitProtocol",
                ResearchAuthorityCommitProtocol.LEGACY_NO_RECEIPT.name());
          }
        });
    return ContractObjectMapper.read(json.toString(), DesktopSolveCheckpoint.class);
  }

  private static DesktopSolveCheckpoint withReceiptPresence(
      DesktopSolveCheckpoint checkpoint, boolean keepMutation, boolean keepMerge) {
    ObjectNode json =
        (ObjectNode) ContractObjectMapper.parseTree(ContractObjectMapper.write(checkpoint));
    ObjectNode mutations = (ObjectNode) json.get("researchAuthorityMutations");
    if (!keepMutation) {
      ((ArrayNode) mutations.get("authorityMutations")).removeAll();
    }
    if (!keepMerge) {
      ((ArrayNode) mutations.get("mergeReceipts")).removeAll();
    }
    mutations.remove("stableHash");
    return ContractObjectMapper.read(json.toString(), DesktopSolveCheckpoint.class);
  }
}
