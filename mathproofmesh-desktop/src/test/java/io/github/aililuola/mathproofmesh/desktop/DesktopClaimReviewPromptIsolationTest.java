package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopClaimReviewPromptIsolationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void claimReviewReceivesBoundedArtifactsButNotTheFailedAttemptVerdict() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("prompt-isolation"), "claim-review-prompt-isolation")) {
      harness.freezeAndCreateRoute();
      harness.addCounterexampleTargets();
      harness.runFailedRound(0);

      List<ProviderRequest> requests = harness.claimReviewRequests();
      assertThat(requests).hasSize(9);
      assertThat(requests)
          .filteredOn(request -> "ClaimStatementFalsificationBatch".equals(request.schemaName()))
          .hasSize(4);
      assertThat(requests)
          .filteredOn(
              request -> "ClaimCounterexampleWitnessReviewBatch".equals(request.schemaName()))
          .hasSize(1);
      assertThat(requests)
          .filteredOn(request -> "ClaimProofAuditBatch".equals(request.schemaName()))
          .hasSize(2);
      assertThat(requests)
          .filteredOn(request -> "ClaimBlindAdjudicationBatch".equals(request.schemaName()))
          .hasSize(2);
      assertThat(requests)
          .extracting(ProviderRequest::schemaName)
          .doesNotContain("ClaimReviewBatch");

      for (ProviderRequest request : requests) {
        JsonNode context = sanitizedContext(request);
        assertThat(context.path("immutable_problem").path("exact_statement").asText())
            .isEqualTo(DesktopClaimSalvageTestHarness.SOURCE);
        assertThat(request.messages().getLast().content())
            .doesNotContain(
                "The route contains bounded local results but its main bridge fails.",
                "the route theorem does not follow",
                "FALSE_ROUTE_THEOREM_R0",
                "detailedReview",
                "detailed_review",
                "proofSketch",
                "proof_sketch");
      }
    }
  }

  private static JsonNode sanitizedContext(ProviderRequest request) {
    String prompt = request.messages().getLast().content();
    String startMarker = "SANITIZED CONTEXT:\n";
    String endMarker = "\n\nOUTPUT LANGUAGE:";
    int start = prompt.indexOf(startMarker);
    assertThat(start).isNotNegative();
    start += startMarker.length();
    int end = prompt.indexOf(endMarker, start);
    assertThat(end).isGreaterThan(start);
    return ContractObjectMapper.parseTree(prompt.substring(start, end));
  }
}
