package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class BottleneckCompressionParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_semantic_compression_preserves_all_original_nodes",
        "test_scope_mismatch_prevents_semantic_cluster",
        "test_cluster_resolves_without_deleting_nodes");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("BottleneckCompressionParityTest", authorityFunction);
  }
}
