package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.MessageType;
import org.junit.jupiter.api.Test;

class BrokerArtifactControlBoundaryTest {
  @Test
  void genericFailureAndSchedulerActionsAreNotMathematicalArtifacts() {
    BrokerControlBoundaryPolicy policy = new BrokerControlBoundaryPolicy();
    assertThat(policy.audit(MessageType.FAILURE_RECORD, "").code())
        .isEqualTo("GENERIC_FAILURE_RECORD");
    assertThat(policy.audit(MessageType.REPAIR_REQUEST, "create_minimal_bridge").allowed())
        .isFalse();
    assertThat(policy.audit(MessageType.VERIFIED_LEMMA, "").code())
        .isEqualTo("MISSING_EXACT_MATHEMATICAL_PAYLOAD");
  }
}
