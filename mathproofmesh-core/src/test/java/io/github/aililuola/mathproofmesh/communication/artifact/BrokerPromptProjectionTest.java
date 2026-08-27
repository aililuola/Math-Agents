package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BrokerPromptProjectionTest {
  @Test
  void projectionIsBoundedAndCarriesAuthorityAndUsageRules() {
    BrokerArtifactPromptProjectionService projection =
        new BrokerArtifactPromptProjectionService();
    var projected =
        projection.project(
            IntStream.range(0, 12)
                .mapToObj(ignored -> BrokerArtifactTestFixtures.verifiedClaim())
                .toList());
    assertThat(projected).hasSize(8);
    assertThat(projected.getFirst().authority()).isNotNull();
    assertThat(projection.instruction())
        .contains("does not mean it was used", "REVIEWED_OPEN", "BOUNDED");
  }
}
