package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopGenericFailureRecordBroadcastBlackBoxTest {
  @TempDir Path temporaryDirectory;

  @Test
  void genericFailureControlRecordNeverEntersProductionMathematicalBroker() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory, "generic-failure-production")) {
      harness.freezeAndCreateRoute();
      harness.runFailedRound(0);
      harness.addRelatedRouteForClaim(
          "correct-local-0", "CORRECT_LOCAL_R0: every finite tree has a leaf.");
      harness.distributeBrokerArtifacts();

      long legacyFailures =
          harness.legacyMessageStore().messages().values().stream()
              .filter(message -> message.messageType() == MessageType.FAILURE_RECORD)
              .count();
      Set<String> genericArtifactIds =
          harness.mathematicalArtifactBroker().artifacts().stream()
              .filter(
                  artifact -> {
                    String json = ContractObjectMapper.write(artifact);
                    return json.contains("create_minimal_bridge")
                        || json.contains("Route failure: BRIDGE");
                  })
              .map(artifact -> artifact.artifactId())
              .collect(java.util.stream.Collectors.toSet());
      long admitted = legacyFailures + genericArtifactIds.size();
      long inPrompt =
          harness.exploreUnstartedRoutesAndCaptureBrokerArtifactIds().stream()
              .filter(genericArtifactIds::contains)
              .count();

      System.out.println("GENERIC_FAILURE_BROADCAST_ATTEMPTS=1");
      System.out.println("GENERIC_FAILURE_MESSAGES_ADMITTED=" + admitted);
      System.out.println("TARGET_PROMPT_GENERIC_FAILURE_MESSAGES=" + inPrompt);
      System.out.println("EXPECTED=0");
      assertThat(admitted).isZero();
      assertThat(inPrompt).isZero();
    }
  }
}
