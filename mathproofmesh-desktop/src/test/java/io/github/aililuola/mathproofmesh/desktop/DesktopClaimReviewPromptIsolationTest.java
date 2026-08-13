package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import java.nio.file.Path;
import java.util.stream.StreamSupport;
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

      assertThat(harness.claimReviewRequests()).hasSize(1);
      ProviderRequest request = harness.claimReviewRequests().getFirst();
      JsonNode context = sanitizedContext(request);
      assertThat(context.properties().stream().map(java.util.Map.Entry::getKey).toList())
          .containsExactlyInAnyOrder(
              "immutable_problem",
              "problem_hash",
              "route_id",
              "attempt_id",
              "attempt_status",
              "route_status",
              "candidate_artifacts",
              "candidate_claims",
              "required_claim_ids",
              "author_excluded",
              "review_rule");
      assertThat(context.path("immutable_problem").path("exact_statement").asText())
          .isEqualTo(DesktopClaimSalvageTestHarness.SOURCE);
      assertThat(context.path("candidate_artifacts")).hasSize(4);
      assertThat(context.path("candidate_claims")).hasSize(4);
      assertThat(
              StreamSupport.stream(
                      context.path("required_claim_ids").spliterator(), false)
                  .map(JsonNode::asText)
                  .toList())
          .containsExactlyInAnyOrder(
              "correct-local-0",
              "false-local-0",
              "unsupported-local-0",
              "counterexample-0");

      String prompt = request.messages().getLast().content();
      assertThat(prompt)
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
