package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopCanonicalPromptProjectionTest {
  @Test
  void downstreamRouteContextContainsBoundedNonAuthoritativeCanonicalProjection(
      @TempDir Path directory) throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "issue-005-canonical-prompt",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "unused")) {
      harness.prepareProductionRoute();
      Map<String, Object> context =
          DesktopProofGraphIssue005BlackBoxSupport.firstRouteContext(harness);

      assertThat(context)
          .containsKeys(
              "focused_raw_obligation",
              "focused_canonical_target",
              "focused_dependency_plan",
              "focused_bottleneck_family",
              "canonical_open_targets",
              "bottleneck_family_summary",
              "canonicalization_authority_rule");
      assertThat((List<?>) context.get("canonical_open_targets")).hasSizeLessThanOrEqualTo(8);
      assertThat((List<?>) context.get("bottleneck_family_summary")).hasSizeLessThanOrEqualTo(6);
      assertThat(String.valueOf(context.get("canonicalization_authority_rule")))
          .contains("never mathematical equivalence")
          .contains("never", "Claim", "Fact");
    }
  }
}
