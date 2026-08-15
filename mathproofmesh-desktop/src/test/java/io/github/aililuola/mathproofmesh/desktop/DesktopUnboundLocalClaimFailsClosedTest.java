package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopUnboundLocalClaimFailsClosedTest {
  @TempDir Path temporaryDirectory;

  @Test
  void modernAttemptLocalClaimWithoutBindingNeverFallsBackToRoot() throws Exception {
    String claimId = "attempt-local-unbound";
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory, "attempt-local-unbound")) {
      harness.freezeAndCreateRoute();
      harness.installModernLocalClaimRound(0, claimId, "P(y)", null);
      harness.integrateInstalledRound();
      long courtAdmissions =
          harness.claimCourt().records().stream()
              .filter(record -> record.frozenClaim().claimId().equals(claimId))
              .count();
      int rootFallbacks = courtAdmissions == 0 ? 0 : 1;

      assertThat(courtAdmissions).isZero();
      assertThat(harness.typedMemory().find(claimId)).isEmpty();
      assertThat(harness.attemptArtifacts().records())
          .filteredOn(record -> record.claimId().equals(claimId))
          .singleElement()
          .satisfies(
              record -> {
                assertThat(record.status()).isEqualTo(AttemptArtifactStatus.UNCERTAIN);
                assertThat(record.history())
                    .anyMatch(
                        value ->
                            value.contains("MISSING_ATTEMPT_LOCAL_CLAIM_CONTEXT_BINDING"));
              });
      System.out.println("UNBOUND_MODERN_LOCAL_CLAIM_ADMISSIONS=" + courtAdmissions);
      System.out.println("UNBOUND_MODERN_LOCAL_CLAIM_ROOT_FALLBACKS=" + rootFallbacks);
    }
  }
}
