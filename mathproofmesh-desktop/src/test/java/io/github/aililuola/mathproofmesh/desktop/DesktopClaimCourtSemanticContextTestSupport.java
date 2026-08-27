package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.CriticalClaimContextBinding;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.FrozenClaimSnapshot;
import java.nio.file.Path;
import java.util.List;

final class DesktopClaimCourtSemanticContextTestSupport {
  static final String CLAIM_ID = "claim-context-bound";
  static final String STATEMENT = "P(x)";
  static final String VARIABLE_ID = "claim-context-x";

  private DesktopClaimCourtSemanticContextTestSupport() {}

  static FrozenClaimSnapshot freeze(
      Path runDirectory,
      String runId,
      String quantifierKind,
      List<String> aliases,
      List<String> localAssumptions,
      List<String> scope,
      String polarity)
      throws Exception {
    QuantifierSpec quantifier =
        new QuantifierSpec(
            "x", "finite set", quantifierKind, 0, List.of(), VARIABLE_ID);
    VariableBinding variable =
        new VariableBinding(
            aliases, "x", "finite set", "critical-claim", VARIABLE_ID);
    CriticalClaim claim =
        new CriticalClaim(
            CLAIM_ID,
            List.of(),
            "Search for an exact finite witness.",
            "required",
            null,
            STATEMENT,
            "needs_check");
    CriticalClaimContextBinding binding =
        new CriticalClaimContextBinding(
            CLAIM_ID,
            "@claim",
            List.of(),
            localAssumptions,
            List.of(quantifier),
            List.of(variable),
            scope,
            polarity);
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      harness.freezeAndCreateRoute(
          DesktopClaimSalvageTestHarness.strategyWithCriticalClaim(
              "strategy-" + runId, claim, binding));
      harness.installSingleClaimRound(0, CLAIM_ID, STATEMENT);
      harness.integrateInstalledRound();
      return harness.frozenClaim(CLAIM_ID);
    }
  }
}
