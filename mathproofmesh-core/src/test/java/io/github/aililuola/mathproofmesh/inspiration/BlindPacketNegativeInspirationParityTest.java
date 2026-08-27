package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class BlindPacketNegativeInspirationParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_rejected_inspiration_proposal_has_stable_identity_free_blind_packet");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("BlindPacketNegativeInspirationParityTest", authorityFunction);
  }
}
