package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ClaimStatementAssessment;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeRegistry;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeSnapshot;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimStatementAuthorityService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopNegativeKnowledgePolarityRestoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void polaritySurvivesCheckpointRestoreAndLegacyUnknownDoesNotExactBlock() throws Exception {
    DesktopSolveCheckpoint checkpoint;
    DesktopComputationSemanticContextTestSupport.ContextFixture fixture;
    try (var first =
        DesktopComputationIssue010CoordinatorHarness.open(
            temporaryDirectory, "negative-polarity-restore")) {
      first.initializeRoute();
      fixture =
          DesktopComputationSemanticContextTestSupport.bind(
              first,
              DesktopComputationIssue010Support.graphCounterexample(
                  "negative-polarity-restore", 63),
              "negative-polarity-restore-claim");
      first.runComputation(fixture.spec());
      checkpoint = first.checkpointRoundTrip();
    }

    int postRestorePolarityLosses;
    try (var restored =
        DesktopComputationIssue010CoordinatorHarness.open(
            temporaryDirectory, "negative-polarity-restore")) {
      restored.restore(checkpoint);
      String counterexampleId = restored.typedMemory().negatives().getFirst().messageId();
      String restoredPolarity =
          restored.typedMemory().negativeKnowledgeRegistry().records().stream()
              .filter(record -> record.evidenceMessageIds().contains(counterexampleId))
              .findFirst()
              .orElseThrow()
              .polarity();
      postRestorePolarityLosses = "positive".equals(restoredPolarity) ? 0 : 1;
    }

    ObjectNode legacyJson =
        (ObjectNode) ContractObjectMapper.toTree(checkpoint.typedMemory().negativeKnowledge());
    legacyJson.put("schemaVersion", 1);
    legacyJson.path("records").forEach(node -> ((ObjectNode) node).remove("polarity"));
    NegativeKnowledgeSnapshot legacySnapshot =
        ContractObjectMapper.read(
            ContractObjectMapper.write(legacyJson), NegativeKnowledgeSnapshot.class);
    NegativeKnowledgeRegistry legacyRegistry = NegativeKnowledgeRegistry.restore(legacySnapshot);
    var positive =
        DesktopNegativeKnowledgePolarityTestSupport.frozen(
            fixture.binding(), "positive");
    var legacyResult =
        new ClaimStatementAuthorityService()
            .assess(
                positive,
                DesktopNegativeKnowledgePolarityTestSupport.noCounterexample(positive),
                legacyRegistry,
                0,
                List.of());
    int legacyUnspecifiedExactBlocks =
        legacyResult.assessment() == ClaimStatementAssessment.REFUTED_BY_VERIFIED_EVIDENCE
            ? 1
            : 0;

    assertThat(postRestorePolarityLosses).isZero();
    assertThat(legacyUnspecifiedExactBlocks).isZero();
    assertThat(legacyResult.assessment()).isEqualTo(ClaimStatementAssessment.INCONCLUSIVE);
    System.out.println("POST_RESTORE_POLARITY_LOSSES=" + postRestorePolarityLosses);
    System.out.println("LEGACY_UNSPECIFIED_EXACT_BLOCKS=" + legacyUnspecifiedExactBlocks);
    System.out.println("RESULT=PASS");
  }
}
