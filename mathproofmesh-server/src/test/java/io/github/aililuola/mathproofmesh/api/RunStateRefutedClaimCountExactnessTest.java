package io.github.aililuola.mathproofmesh.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunStateRefutedClaimCountExactnessTest {
  @TempDir Path temporaryDirectory;

  @Test
  void onlyTrustedExactStatementRefutationsCountAsRefuted() throws Exception {
    Path structured = temporaryDirectory.resolve("structured");
    Files.createDirectories(structured);
    Files.writeString(
        structured.resolve("desktop-solve-state.json"),
        """
        {
          "terminal": false,
          "claimLifecycle": {
            "entries": {
              "claim-refuted": {
                "claimId":"claim-refuted",
                "state":"REJECTED",
                "invalidationReason":"verified exact statement refutation hash",
                "invalidatingEvidenceIds":["evidence-1"],
                "history":["rejected:verified exact statement refutation hash"]
              },
              "claim-rejected": {"claimId":"claim-rejected","state":"REJECTED"},
              "claim-invalidated": {"claimId":"claim-invalidated","state":"INVALIDATED"},
              "claim-verified": {"claimId":"claim-verified","state":"INDEPENDENTLY_VERIFIED"}
            }
          }
        }
        """,
        StandardCharsets.UTF_8);
    RunExecutionBackend.RunExecutionResult result =
        new RunExecutionBackend.RunExecutionResult(
            "unverified", "proof", "partial", List.of(), List.of("claim-verified"), "", 1);

    var state =
        RunStateApiProjection.reconcile(
            new SolveRequest("Prove P.", "refuted-exact", null, "smoke"),
            "refuted-exact",
            "attempt-one",
            temporaryDirectory,
            result,
            null);

    assertThat(state.authority().mathematicalProgress().verifiedLocalClaims()).isEqualTo(1);
    assertThat(state.authority().mathematicalProgress().refutedClaims()).isEqualTo(1);
    assertThat(state.authority().mathematicalProgress().refutedClaimIds())
        .containsExactly("claim-refuted");
  }
}
