package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class ClaimBlindPacketIsolationTest {
  @Test
  void blindPacketTypeHasNoIdentityOrPriorDecisionFields() throws ClassNotFoundException {
    Class<?> packetType =
        Class.forName(
            "io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimBlindReviewPacket");
    assertThat(
            Arrays.stream(packetType.getRecordComponents())
                .map(component -> component.getName()))
        .doesNotContain(
            "authorAgentId",
            "falsifierAgentId",
            "auditorAgentId",
            "repairerAgentId",
            "priorVerdict",
            "repairHint",
            "routeStatus");
  }
}
