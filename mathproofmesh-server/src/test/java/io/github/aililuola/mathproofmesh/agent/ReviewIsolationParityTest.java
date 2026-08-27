package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReviewIsolationParityTest {
  @Test
  void finalReviewExcludesEveryWinningChainAuthor() {
    Set<String> excluded =
        ReviewIsolationPolicy.finalReviewAuthorIds(
            List.of("attempt-winning"),
            List.of(
                new ReviewIsolationPolicy.AttemptAttribution(
                    "attempt-winning",
                    "explorer-a",
                    List.of("explorer-b"),
                    "path-winning"),
                new ReviewIsolationPolicy.AttemptAttribution(
                    "attempt-other",
                    "explorer-c",
                    List.of(),
                    "path-other")),
            List.of(
                new ReviewIsolationPolicy.CheckpointAttribution(
                    "path-winning",
                    "explorer-d",
                    List.of("explorer-e"))),
            "synthesizer-a");

    assertThat(excluded)
        .containsExactlyInAnyOrder(
            "synthesizer-a",
            "explorer-a",
            "explorer-b",
            "explorer-d",
            "explorer-e")
        .doesNotContain("explorer-c");
  }
}
