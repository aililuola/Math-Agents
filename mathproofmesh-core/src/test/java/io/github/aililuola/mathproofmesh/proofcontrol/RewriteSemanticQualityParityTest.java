package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RewriteSemanticQualityParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_self_implication_rewrite_is_rejected",
        "test_placeholder_rewrite_is_rejected",
        "test_rewrite_requires_child_lineage_and_preserves_domain_mechanism");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("RewriteSemanticQualityParityTest", authorityFunction);
  }
}
