package io.github.aililuola.mathproofmesh.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunStateFailureReconciliationClaimLifecycleTest {
  @TempDir Path temporaryDirectory;

  @Test
  void failureRecoveryReadsAuthoritativeLifecycleStateFromEntries() throws Exception {
    Path structured = temporaryDirectory.resolve("structured");
    Files.createDirectories(structured);
    Files.writeString(
        structured.resolve("desktop-solve-state.json"),
        """
        {
          "roundIndex": 3,
          "claimLifecycle": {
            "entries": {
              "claim-verified": {
                "claimId": "claim-verified",
                "state": "INDEPENDENTLY_VERIFIED"
              },
              "claim-proposed": {
                "claimId": "claim-proposed",
                "state": "PROPOSED"
              }
            }
          }
        }
        """,
        StandardCharsets.UTF_8);
    RunExecutionBackend.RunExecutionResult failure =
        new RunExecutionBackend.RunExecutionResult(
            "failed", "proof", "execution failed", List.of(), List.of(), "", 0);

    RunExecutionBackend.RunExecutionResult reconciled =
        new RunStateReconciliationService().reconcileFailure(temporaryDirectory, failure);

    assertThat(reconciled.verifiedLocalClaimIds()).containsExactly("claim-verified");
    assertThat(reconciled.logicalSteps()).isEqualTo(3);
  }
}
