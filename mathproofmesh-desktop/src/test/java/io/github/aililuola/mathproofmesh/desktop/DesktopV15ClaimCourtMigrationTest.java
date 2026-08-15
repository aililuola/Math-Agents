package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopV15ClaimCourtMigrationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void legacyClaimAuthoritySurvivesEmptyCourtMigrationWithoutProviderReplay()
      throws Exception {
    Path runDirectory = temporaryDirectory.resolve("v15-claim-court");
    String runId = "v15-claim-court";
    DesktopSolveCheckpoint versionFifteen;
    String rootHash;
    String negativeHash;

    try (DesktopClaimSalvageTestHarness source =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      source.freezeAndCreateRoute();
      source.runSingleLegacyClaimRound(
          0, "legacy-verified", "VALID_LEGACY: a zero-kernel map is injective.");
      source.runSingleLegacyClaimRound(
          1,
          "legacy-rejected",
          "REFUTED_LEGACY: every connected finite graph is Hamiltonian.");
      DesktopSolveCheckpoint current = source.checkpointRoundTrip();
      rootHash = source.rootGoal().sourceStatementHash();
      negativeHash = source.negativeRegistryHash();

      ObjectNode json = (ObjectNode) ContractObjectMapper.toTree(current);
      json.put("schemaVersion", 15);
      json.remove("claimProofRevisions");
      json.remove("claimCourt");
      json.remove("claimCourtExecutions");
      versionFifteen = ContractObjectMapper.read(json, DesktopSolveCheckpoint.class);
    }

    assertThat(versionFifteen.schemaVersion()).isEqualTo(15);
    assertThat(versionFifteen.claimProofRevisions().records()).isEmpty();
    assertThat(versionFifteen.claimCourt().records()).isEmpty();
    assertThat(versionFifteen.claimCourtExecutions().records()).isEmpty();

    DesktopSolveCheckpoint versionSixteen;
    String firstRevisionHash;
    try (DesktopClaimSalvageTestHarness restored =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      restored.restore(versionFifteen);
      Map<String, ClaimStatus> statuses = statuses(restored);

      assertThat(statuses)
          .containsEntry("legacy-verified", ClaimStatus.VERIFIED)
          .containsEntry("legacy-rejected", ClaimStatus.REJECTED);
      assertThat(restored.typedMemory().facts())
          .filteredOn(fact -> fact.messageId().equals("legacy-verified"))
          .hasSize(1);
      assertThat(restored.typedMemory().facts())
          .noneMatch(fact -> fact.messageId().equals("legacy-rejected"));
      assertThat(restored.claimProofRevisions().recordsForClaim("legacy-verified"))
          .hasSize(1);
      assertThat(restored.claimProofRevisions().recordsForClaim("legacy-rejected"))
          .hasSize(1);
      assertThat(restored.claimCourt().records()).isEmpty();
      assertThat(restored.claimReviewRequests()).isEmpty();
      assertThat(restored.rootGoal().sourceStatementHash()).isEqualTo(rootHash);
      assertThat(restored.negativeRegistryHash()).isEqualTo(negativeHash);

      firstRevisionHash = restored.claimProofRevisions().stableHash();
      versionSixteen = restored.checkpointRoundTrip();
      assertThat(versionSixteen.schemaVersion())
          .isEqualTo(DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION);
    }

    try (DesktopClaimSalvageTestHarness restoredAgain =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      restoredAgain.restore(versionSixteen);
      assertThat(statuses(restoredAgain))
          .containsEntry("legacy-verified", ClaimStatus.VERIFIED)
          .containsEntry("legacy-rejected", ClaimStatus.REJECTED);
      assertThat(restoredAgain.claimProofRevisions().stableHash())
          .isEqualTo(firstRevisionHash);
      assertThat(restoredAgain.typedMemory().facts())
          .filteredOn(fact -> fact.messageId().equals("legacy-verified"))
          .hasSize(1);
      assertThat(restoredAgain.claimReviewRequests()).isEmpty();
      assertThat(restoredAgain.rootGoal().sourceStatementHash()).isEqualTo(rootHash);
      assertThat(restoredAgain.negativeRegistryHash()).isEqualTo(negativeHash);
    }
  }

  private static Map<String, ClaimStatus> statuses(
      DesktopClaimSalvageTestHarness harness) {
    return harness.lemmaMemory().claims().stream()
        .filter(claim -> claim.claimId().startsWith("legacy-"))
        .collect(
            Collectors.toMap(
                claim -> claim.claimId(),
                claim -> claim.status(),
                (left, right) -> left));
  }
}
