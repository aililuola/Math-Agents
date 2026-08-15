package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.communication.artifact.BrokerControlBoundaryPolicy;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopBrokerControlMessageIsolationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void productionFailureAndSchedulingTextCannotEnterCrossRoutePrompt() throws Exception {
    BrokerControlBoundaryPolicy boundary = new BrokerControlBoundaryPolicy();
    assertThat(
            List.of(
                boundary.audit(MessageType.FAILURE_RECORD, "Route failure: BRIDGE"),
                boundary.audit(MessageType.REPAIR_REQUEST, "create_minimal_bridge"),
                boundary.audit(MessageType.BRIDGE_LEMMA_REQUEST, "create_minimal_bridge"),
                boundary.audit(MessageType.STRATEGY_REWRITE_REQUEST, "DEEPEN")))
        .allMatch(decision -> !decision.allowed());

    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory, "control-isolation")) {
      harness.freezeAndCreateRoute();
      harness.runFailedRound(0);
      harness.addRelatedRouteForClaim(
          "correct-local-0", "CORRECT_LOCAL_R0: every finite tree has a leaf.");
      harness.distributeBrokerArtifacts();

      assertThat(harness.legacyMessageStore().messages().values())
          .noneMatch(message -> message.messageType() == MessageType.FAILURE_RECORD);
      Set<String> controlArtifactIds =
          harness.mathematicalArtifactBroker().artifacts().stream()
              .filter(
                  artifact -> {
                    String json = ContractObjectMapper.write(artifact);
                    return json.contains("create_minimal_bridge")
                        || json.contains("Route failure: BRIDGE")
                        || json.contains("STRATEGY_REWRITE_REQUEST");
                  })
              .map(artifact -> artifact.artifactId())
              .collect(java.util.stream.Collectors.toSet());
      assertThat(controlArtifactIds).isEmpty();
      assertThat(
              harness.exploreUnstartedRoutesAndCaptureBrokerArtifactIds().stream()
                  .filter(controlArtifactIds::contains))
          .isEmpty();
    }
  }
}
