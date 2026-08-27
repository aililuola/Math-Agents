package io.github.aililuola.mathproofmesh.workflow;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class LegacyCheckpointResumeUsesHierarchicalRoutePipelineParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_legacy_pre_strategy_checkpoint_rebuilds_hierarchical_routes");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    TemporalParityScenarios.verify("LegacyCheckpointResumeUsesHierarchicalRoutePipelineParityTest", authorityFunction);
  }
}
