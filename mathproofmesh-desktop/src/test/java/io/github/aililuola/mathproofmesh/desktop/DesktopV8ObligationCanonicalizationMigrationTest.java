package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofgraph.ObligationCanonicalizationSnapshot;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphPolicy;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphSnapshot;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopV8ObligationCanonicalizationMigrationTest {
  @Test
  void v8GraphWithoutOperationalProjectionRebuildsDeterministically(@TempDir Path directory)
      throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-v8-migration",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      ProofGraphSnapshot current =
          DesktopProofGraphIssue005BlackBoxSupport.graph(harness).snapshot();
      ProofGraphSnapshot v8 =
          new ProofGraphSnapshot(
              current.problemHash(),
              current.frozen(),
              current.obligations(),
              current.claimNodes(),
              current.edges(),
              current.aliases(),
              current.needsReverify(),
              current.versions(),
              current.audit(),
              ObligationCanonicalizationSnapshot.empty());

      ProofGraphStore first = ProofGraphStore.restore(v8, ProofGraphPolicy.defaults());
      ProofGraphStore second = ProofGraphStore.restore(v8, ProofGraphPolicy.defaults());
      assertThat(first.rawObligationOccurrences()).hasSize(current.obligations().size());
      assertThat(first.allCanonicalTargets()).isNotEmpty();
      assertThat(first.canonicalizationHash()).isEqualTo(second.canonicalizationHash());
      assertThat(first.audit())
          .anyMatch(event -> event.eventType().equals("canonicalization_rebuilt_from_raw"));
    }
  }
}
