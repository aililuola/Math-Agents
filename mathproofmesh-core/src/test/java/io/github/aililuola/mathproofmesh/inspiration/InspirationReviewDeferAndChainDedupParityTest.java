package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class InspirationReviewDeferAndChainDedupParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_referee_budget_exhaustion_defers_proposal",
        "test_deferred_inspiration_can_be_reassigned",
        "test_unreviewed_proposal_cannot_materialize_route",
        "test_basic_math_tool_not_forbidden_by_chain_dedup",
        "test_duplicate_mechanism_chain_is_blocked");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("InspirationReviewDeferAndChainDedupParityTest", authorityFunction);
  }
}
