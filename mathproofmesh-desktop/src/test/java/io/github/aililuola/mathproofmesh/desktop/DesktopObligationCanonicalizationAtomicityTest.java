package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopObligationCanonicalizationAtomicityTest {
  @Test
  void failedRawGraphMutationCannotLeaveCanonicalOrFamilyResidue(@TempDir Path directory)
      throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-canonical-atomicity",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      var graph = DesktopProofGraphIssue005BlackBoxSupport.graph(harness);
      int rawBefore = graph.obligations().size();
      int occurrencesBefore = graph.rawObligationOccurrences().size();
      int canonicalBefore = graph.allCanonicalTargets().size();
      int familiesBefore = graph.allBottleneckFamilies().size();
      String hashBefore = graph.canonicalizationHash();
      ProofObligation invalid =
          new ProofObligation(
              List.of(),
              0.5d,
              "",
              List.of("missing-dependency"),
              List.of(),
              List.of(),
              "atomic-family",
              ObligationKind.LEMMA,
              "invalid atomic target",
              "invalid-atomic-target",
              0.5d,
              DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH,
              List.of(),
              List.of("route-1"),
              "Invalid atomic target",
              "open");

      assertThatThrownBy(() -> graph.addObligation(invalid))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("missing proof dependency");
      assertThat(graph.obligations()).hasSize(rawBefore);
      assertThat(graph.rawObligationOccurrences()).hasSize(occurrencesBefore);
      assertThat(graph.allCanonicalTargets()).hasSize(canonicalBefore);
      assertThat(graph.allBottleneckFamilies()).hasSize(familiesBefore);
      assertThat(graph.canonicalizationHash()).isEqualTo(hashBefore);
    }
  }
}
