package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.CriticalClaimContextBinding;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopCourtFactEvidenceRoundTripTest {
  @TempDir Path temporaryDirectory;

  @Test
  void verifiedCourtFactRemainsExactEvidenceAfterCheckpointRestore() throws Exception {
    String claimId = "claim-fact-round-trip";
    QuantifierSpec quantifier =
        new QuantifierSpec("x", "D", "forall", 0, List.of(), "round-trip-x");
    VariableBinding variable =
        new VariableBinding(List.of("x"), "x", "D", "critical-claim", "round-trip-x");
    CriticalClaim critical =
        new CriticalClaim(
            claimId,
            List.of(),
            "Check the exact context.",
            "required",
            null,
            "P(x)",
            "needs_check");
    CriticalClaimContextBinding binding =
        new CriticalClaimContextBinding(
            claimId,
            "@claim",
            List.of(),
            List.of("H"),
            List.of(quantifier),
            List.of(variable),
            List.of("scope-D"),
            "positive");
    DesktopSolveCheckpoint checkpoint;
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory, "fact-round-trip")) {
      harness.freezeAndCreateRoute(
          DesktopClaimSalvageTestHarness.strategyWithCriticalClaim(
              "strategy-fact-round-trip", critical, binding));
      harness.installSingleClaimRound(0, claimId, "P(x)");
      harness.integrateInstalledRound();
      checkpoint = harness.checkpointRoundTrip();
    }

    try (DesktopClaimSalvageTestHarness restored =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory, "fact-round-trip")) {
      restored.restore(checkpoint);
      var frozen = restored.frozenClaim(claimId);
      var fact = restored.typedMemory().find(claimId).orElseThrow();
      int failures = restored.factMatchesFrozenClaim(fact, frozen) ? 0 : 1;

      assertThat(fact.claimSemanticHash()).isEqualTo(frozen.claimSemanticHash());
      assertThat(fact.polarity()).isEqualTo(frozen.polarity());
      assertThat(failures).isZero();
      System.out.println("FACT_EVIDENCE_ROUND_TRIP_FAILURES=" + failures);
    }
  }
}
