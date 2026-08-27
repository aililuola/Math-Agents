package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphControlMode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopV9ConvergenceMigrationTest {
  @Test
  void v9CheckpointDefaultsToNormalWithoutGuessingControlHistory(@TempDir Path directory)
      throws Exception {
    try (var source =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-v9-convergence-migration",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      source.prepareProductionRoute();
      DesktopProofGraphIssue005BlackBoxSupport.enterFocusedRecovery(source);
      String rootHash = source.rootHash();
      String negativeHash = source.negativeHash();
      String attemptHash = source.attemptArtifactHash();
      String claimHash = source.claimLifecycleHash();
      String researchHash = source.researchLedger().ledgerHash();
      String canonicalizationHash =
          DesktopProofGraphIssue005BlackBoxSupport.canonicalizationHash(source);
      int providerCalls = source.providerCallCount();

      DesktopSolveCheckpoint current = source.checkpointRoundTrip();
      String persistedCanonicalizationHash =
          CanonicalJson.stableHash(current.proofGraph().canonicalization());
      String sourceAfterPersistHash =
          DesktopProofGraphIssue005BlackBoxSupport.canonicalizationHash(source);
      ObjectNode json = (ObjectNode) ContractObjectMapper.toTree(current);
      json.put("schemaVersion", 9);
      json.remove("proofGraphConvergence");
      json.remove("deferredExpansions");
      DesktopSolveCheckpoint versionNine =
          ContractObjectMapper.read(json.toString(), DesktopSolveCheckpoint.class);
      String versionNineCanonicalizationHash =
          CanonicalJson.stableHash(versionNine.proofGraph().canonicalization());

      assertThat(versionNine.schemaVersion()).isEqualTo(9);
      assertThat(versionNine.proofGraphConvergence().roundHistory()).isEmpty();
      assertThat(versionNine.proofGraphConvergence().focusedRecoveryPlan()).isNull();
      assertThat(versionNine.deferredExpansions().records()).isEmpty();

      try (var restored = source.restored(versionNine)) {
        assertThat(DesktopProofGraphIssue005BlackBoxSupport.controlMode(restored))
            .isEqualTo(ProofGraphControlMode.NORMAL_EXPANSION.name());
        assertThat(DesktopProofGraphIssue005BlackBoxSupport.convergence(restored).roundHistory())
            .isEmpty();
        assertThat(
                DesktopProofGraphIssue005BlackBoxSupport.convergence(restored)
                    .focusedRecoveryPlan())
            .isEmpty();
        assertThat(DesktopProofGraphIssue005BlackBoxSupport.deferredExpansions(restored).records())
            .isEmpty();
        assertThat(restored.rootHash()).isEqualTo(rootHash);
        assertThat(restored.negativeHash()).isEqualTo(negativeHash);
        assertThat(restored.attemptArtifactHash()).isEqualTo(attemptHash);
        assertThat(restored.claimLifecycleHash()).isEqualTo(claimHash);
        assertThat(restored.researchLedger().ledgerHash()).isEqualTo(researchHash);
        String restoredCanonicalizationHash =
            DesktopProofGraphIssue005BlackBoxSupport.canonicalizationHash(restored);
        System.out.println("V9_CANONICAL_HASH_BEFORE_PERSIST=" + canonicalizationHash);
        System.out.println("V9_CANONICAL_HASH_AFTER_PERSIST=" + sourceAfterPersistHash);
        System.out.println("V9_CANONICAL_HASH_IN_CHECKPOINT=" + persistedCanonicalizationHash);
        System.out.println("V9_CANONICAL_HASH_AFTER_JSON=" + versionNineCanonicalizationHash);
        System.out.println("V9_CANONICAL_HASH_AFTER_RESTORE=" + restoredCanonicalizationHash);
        System.out.println(
            "V9_CANONICAL_VERSION_BEFORE="
                + versionNine.proofGraph().canonicalization().version());
        System.out.println(
            "V9_CANONICAL_VERSION_AFTER="
                + DesktopProofGraphIssue005BlackBoxSupport.graph(restored)
                    .canonicalizationSnapshot()
                    .version());
        System.out.println(
            "V9_CANONICAL_LEASES_BEFORE="
                + versionNine.proofGraph().canonicalization().taskLeaseKeys());
        System.out.println(
            "V9_CANONICAL_LEASES_AFTER="
                + DesktopProofGraphIssue005BlackBoxSupport.graph(restored)
                    .canonicalizationSnapshot()
                    .taskLeaseKeys());
        assertThat(DesktopProofGraphIssue005BlackBoxSupport.canonicalizationHash(restored))
            .isEqualTo(persistedCanonicalizationHash);
        assertThat(sourceAfterPersistHash).isEqualTo(canonicalizationHash);
        assertThat(restored.providerCallCount()).isEqualTo(providerCalls);
      }
    }
  }
}
