package io.github.aililuola.mathproofmesh.workflow;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class V082CheckpointMigrationParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_v081_checkpoint_migrates_with_empty_v082_sidecars",
        "test_v082_sidecars_roundtrip_exactly_once",
        "test_legacy_self_implication_is_quarantined_not_deleted");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    TemporalParityScenarios.verify("V082CheckpointMigrationParityTest", authorityFunction);
  }
}
