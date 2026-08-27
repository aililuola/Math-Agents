package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DesktopBrokerPromptProjectionTest {
  @Test
  void promptContainsBoundedAuthorityProjectionInsteadOfLegacyEnvelope() {
    DesktopBrokerArtifactFixture fixture = new DesktopBrokerArtifactFixture();
    var artifact = fixture.artifact("prompt", "source-route");
    fixture.broker.publish(artifact, List.of(fixture.related("target-route", "prompt")), 0, 8);
    var batch =
        fixture.broker.consumeForPrompt(
            "target-route", "prompt-request", 0, 8, 1.0d, Set.of("target-prompt"),
            Set.of(), Set.of(), "strategy-target", "target-prompt");

    assertThat(batch.artifacts())
        .singleElement()
        .satisfies(
            projected -> {
              assertThat(projected.artifactId()).isEqualTo(artifact.artifactId());
              assertThat(projected.authority()).isEqualTo(artifact.authority());
              assertThat(projected.sourceClaimRevisionId())
                  .isEqualTo(artifact.sourceClaimRevisionId());
              assertThat(projected.exactStatement()).isNotBlank();
              assertThat(projected.allowedUseKinds()).isNotEmpty();
            });
    assertThat(batch.usageInstruction()).contains("artifact_id").contains("NOT_USED");
  }
}
